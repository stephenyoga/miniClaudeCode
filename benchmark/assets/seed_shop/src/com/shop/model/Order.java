package com.shop.model;

import java.util.ArrayList;
import java.util.List;

/** 订单。状态机：CREATED -> PAID -> SHIPPED；CREATED/PAID 可取消 -> CANCELLED */
public class Order {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_SHIPPED = "SHIPPED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private final String id;
    private final String customerId;
    private final List<OrderItem> items = new ArrayList<>();
    private final long createdAtMillis;
    private String status;
    private long totalCents;

    public Order(String id, String customerId, List<OrderItem> items, long totalCents) {
        this.id = id;
        this.customerId = customerId;
        this.items.addAll(items);
        this.totalCents = totalCents;
        this.createdAtMillis = System.currentTimeMillis();
        this.status = STATUS_CREATED;
    }

    public String id() { return id; }
    public String customerId() { return customerId; }
    public List<OrderItem> items() { return items; }
    public long totalCents() { return totalCents; }
    public String status() { return status; }
    public long createdAtMillis() { return createdAtMillis; }

    public void markPaid() {
        if (STATUS_CREATED.equals(status)) status = STATUS_PAID;
    }

    public void markCancelled() {
        status = STATUS_CANCELLED;
    }

    /** 已支付或已发货的订单才计入收入口径 */
    public boolean countsAsRevenue() {
        return STATUS_PAID.equals(status) || STATUS_SHIPPED.equals(status);
    }
}
