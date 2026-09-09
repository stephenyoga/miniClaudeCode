package com.shop.service;

import com.shop.model.Order;
import com.shop.repository.OrderRepository;

/** 支付服务：把 CREATED 订单标记为已支付 */
public class PaymentService {

    private final OrderRepository orderRepository;

    public PaymentService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public boolean pay(String orderId) {
        Order order = orderRepository.findById(orderId);
        if (order == null || !Order.STATUS_CREATED.equals(order.status())) {
            return false;
        }
        order.markPaid();
        orderRepository.save(order);
        return true;
    }
}
