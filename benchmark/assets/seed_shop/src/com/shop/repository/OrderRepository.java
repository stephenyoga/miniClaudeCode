package com.shop.repository;

import com.shop.model.Order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 订单存储（内存） */
public class OrderRepository {

    private final Map<String, Order> orders = new LinkedHashMap<>();

    public void save(Order order) {
        orders.put(order.id(), order);
    }

    public Order findById(String id) {
        return orders.get(id);
    }

    public List<Order> findAll() {
        return new ArrayList<>(orders.values());
    }
}
