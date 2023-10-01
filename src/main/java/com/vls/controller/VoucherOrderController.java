package com.vls.controller;


import com.vls.annotation.RateLimiter;
import com.vls.constants.LimitType;
import com.vls.dto.Result;
import com.vls.service.IVoucherOrderService;
import com.vls.service.impl.VoucherOrderServiceImpl;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @PostMapping("seckill/{id}")
    @RateLimiter(time = 30,count = 5, type = LimitType.IP)
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.secKillVoucher(voucherId);
    }
}
