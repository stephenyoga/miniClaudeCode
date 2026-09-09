package com.shop.service;

import com.shop.model.Customer;
import com.shop.model.Order;
import com.shop.repository.CustomerRepository;
import com.shop.repository.OrderRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 报表服务：收入口径 = 已支付/已发货订单 */
public class ReportService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    public ReportService(OrderRepository orderRepository, CustomerRepository customerRepository) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
    }

    /** 客户收入汇总行 */
    public record CustomerReportRow(String customerId, String customerName, long spentCents) {}

    /** 总收入（仅统计计入收入口径的订单） */
    public long paidRevenue() {
        long sum = 0;
        for (Order order : orderRepository.findAll()) {
            if (order.countsAsRevenue()) {
                sum += order.totalCents();
            }
        }
        return sum;
    }

    /** 按客户聚合已支付金额，按消费额降序 */
    public List<CustomerReportRow> customerRevenue() {
        Map<String, Long> byCustomer = new LinkedHashMap<>();
        for (Order order : orderRepository.findAll()) {
            if (!order.countsAsRevenue()) continue;
            byCustomer.merge(order.customerId(), order.totalCents(), Long::sum);
        }
        List<CustomerReportRow> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : byCustomer.entrySet()) {
            Customer customer = customerRepository.findById(e.getKey());
            String name = customer != null ? customer.name() : "未知用户";
            rows.add(new CustomerReportRow(e.getKey(), name, e.getValue()));
        }
        rows.sort(Comparator.comparingLong(CustomerReportRow::spentCents).reversed());
        return rows;
    }
}
