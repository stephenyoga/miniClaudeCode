package com.shop.service;

import com.shop.model.Customer;
import com.shop.model.OrderItem;

import java.util.List;

/**
 * 定价服务：小计、VIP 折扣、最终应付。
 * 注意：charge(...) 是订单与控制器共用的最终计价入口，改名需同步两处调用方。
 */
public class PricingService {

    public long subtotal(List<OrderItem> items) {
        long sum = 0;
        for (OrderItem item : items) {
            sum += item.subtotal();
        }
        return sum;
    }

    public long applyVipDiscount(String level, long subtotalCents) {
        return Customer.LEVEL_VIP.equals(level) ? subtotalCents * 9 / 10 : subtotalCents;
    }

    /** 客户最终应付金额（会员折扣后） */
    public long charge(Customer customer, long subtotalCents) {
        return applyVipDiscount(customer.level(), subtotalCents);
    }
}
