package com.vls.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.rabbitmq.client.Channel;
import com.vls.annotation.RateLimiter;
import com.vls.constants.LimitType;
import com.vls.dto.Result;
import com.vls.entity.SeckillVoucher;
import com.vls.entity.Voucher;
import com.vls.entity.VoucherOrder;
import com.vls.mapper.VoucherOrderMapper;
import com.vls.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vls.service.IVoucherService;
import com.vls.utils.RedisConstants;
import com.vls.utils.RedisIdWorker;
import com.vls.utils.SimpleRedisLock;
import com.vls.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    //配置lua脚本
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT ;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

//    //异步处理线程池
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//
//    //内部类，用于线程池处理的任务
//    private class VoucherOrderHandler implements Runnable{
//        String queueName = "stream.orders";
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    //获取消息队列的消息XREADGROUP GROUP gl cl COUNT 1 BLOCK 2000 STREANS streams .order >
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
//                    );
//                    //获取失败，说明没有消息，等待下一次获取
//                    if(list == null || list.isEmpty()){
//                        continue;
//                    }
//                    //解析信息
//                    MapRecord<String, Object, Object> info = list.get(0);
//                    Map<Object, Object> values = info.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    //获取成功，就下单
//                    createVoucherOrder(voucherOrder);
//                    //ACK确认
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", info.getId());
//                } catch (Exception e) {
//                    handlePendingList();
//                }
//            }
//        }
//        private void handlePendingList() {
//            while(true){
//                try {
//                    //获取pendingList的消息XREADGROUP GROUP gl cl COUNT 1 BLOCK 2000 STREANS streams .order >
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1),
//                            StreamOffset.create(queueName, ReadOffset.from("0"))
//                    );
//                    //获取失败，说明没有消息，等待下一次获取
//                    if(list == null || list.isEmpty()){
//                        break;
//                    }
//                    //解析信息
//                    MapRecord<String, Object, Object> info = list.get(0);
//                    Map<Object, Object> values = info.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    //获取成功，就下单
//                    createVoucherOrder(voucherOrder);
//                    //ACK确认
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", info.getId());
//                } catch (Exception e) {
//                    log.error("处理pending-list异常" , e);
//                    try{
//                        Thread.sleep(20);
//                    }catch(Exception ex){
//                        ex.printStackTrace();
//                    }
//                }
//            }
//        }
//    }
//
//
//    //当前类执行完以后执行的初始化方法
//    @PostConstruct
//    public void init(){
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }


    @Override
    public Result secKillVoucher(Long voucherId) {
        //执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT, Collections.EMPTY_LIST,
                voucherId.toString(), userId.toString());
        //判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            //不为0没有购买资格
            return Result.fail(r == 1? " 库存不足" : "不能重复下单");
        }
        //发送消息到消息队列
        Long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        //放入MQ
        String voucherOrderStr = JSONUtil.toJsonStr(voucherOrder);
        rabbitTemplate.convertAndSend("X", "XA", voucherOrderStr);

        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        RLock lock = redissonClient.getLock("lock:order:" +  userId);
        try {
            //尝试10s
            lock.tryLock(10, 30, TimeUnit.SECONDS);
            //判断订单是否存在数据库
            VoucherOrder vo = getById(voucherOrder);
            if(vo != null){
                log.error("订单已存在");
                return;
            }
            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                log.error("该用户已经购买过");
                return;
            }
            // 5.扣减库存
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();// where id = ? and stock > 0
            if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
            }
            save(voucherOrder);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            //防止解错锁、锁过期解锁带来的异常
            if(lock != null && lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
    }

    //消费者,消费A队列
    @RabbitListener(queues = "QA")
    public void receiveA(Message message, Channel channel) throws Exception{
        String msg = new String(message.getBody());
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        log.info("普通队列QA：");
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info("订单编号为：" + voucherOrder.getId() + "的消息体：" + msg);
        //数据库创建订单，业务处理
        try{
            createVoucherOrder(voucherOrder);
            //手动肯定ack  第二个参数为false是表示仅仅确认当前消息 true表示确认之前所有的消息
            channel.basicAck(voucherOrder.getId(), false);
        }catch (RuntimeException e){
            try {
                //手动nack 告诉rabbitmq该消息消费失败  第三个参数：如果被拒绝的消息应该被重新请求，而不是被丢弃或变成死信，则为true
                channel.basicNack(deliveryTag,false,true);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
    //消费者,消费D队列
    @RabbitListener(queues = "DL_QD")
    public void receiveD(Message message){
        String msg = new String(message.getBody());
        log.info("死信队列DL_QD：");
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info("订单编号为：" + voucherOrder.getId() + "的消息体：" + msg);
        //数据库创建订单，业务处理
        createVoucherOrder(voucherOrder);
    }

}
