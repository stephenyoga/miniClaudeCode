package com.shop.web;

import com.shop.model.Customer;
import com.shop.model.Order;
import com.shop.model.OrderItem;
import com.shop.model.Product;
import com.shop.repository.ProductRepository;
import com.shop.service.OrderService;
import com.shop.service.PricingService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 订单 HTTP 端点（用普通方法模拟路由） */
public class OrderController {

    private final OrderService orderService;
    private final PricingService pricingService;
    private final ProductRepository productRepository;

    public OrderController(OrderService orderService,
                           PricingService pricingService,
                           ProductRepository productRepository) {
        this.orderService = orderService;
        this.pricingService = pricingService;
        this.productRepository = productRepository;
    }

    /** spec 形如 "p1:2,p2:1" */
    public void createOrder(String customerId, String spec) {
        Map<String, Integer> qtyByProduct = new LinkedHashMap<>();
        for (String seg : spec.split(",")) {
            String[] kv = seg.split(":");
            qtyByProduct.put(kv[0], Integer.parseInt(kv[1].trim()));
        }
        Order order = orderService.placeOrder(customerId, qtyByProduct);
        System.out.println("下单成功 order=" + order.id() + " total=" + order.totalCents());
    }

    public void cancelOrder(String orderId) {
        boolean ok = orderService.cancelOrder(orderId);
        System.out.println("取消订单 " + orderId + " -> " + (ok ? "成功" : "失败"));
    }

    /** 下单前预览价格：复用定价服务的最终计价入口 */
    public long previewPrice(Customer customer, String spec) {
        List<OrderItem> items = new ArrayList<>();
        for (String seg : spec.split(",")) {
            String[] kv = seg.split(":");
            Product product = productRepository.findById(kv[0]);
            items.add(new OrderItem(kv[0], Integer.parseInt(kv[1].trim()), product.priceCents()));
        }
        return pricingService.charge(customer, pricingService.subtotal(items));
    }
}
