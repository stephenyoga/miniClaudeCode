package com.shop.service;

import com.shop.model.Customer;
import com.shop.model.Order;
import com.shop.model.OrderItem;
import com.shop.model.Product;
import com.shop.repository.CustomerRepository;
import com.shop.repository.InventoryRepository;
import com.shop.repository.OrderRepository;
import com.shop.repository.ProductRepository;
import com.shop.util.IdGenerator;
import com.shop.util.Validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 订单服务：下单（占库存）与取消（应回补库存） */
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final PricingService pricingService;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository,
                        InventoryService inventoryService,
                        PricingService pricingService,
                        CustomerRepository customerRepository,
                        ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.pricingService = pricingService;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
    }

    /**
     * 下单：逐项扣库存，任一不足则回滚已扣项并抛异常。
     */
    public Order placeOrder(String customerId, Map<String, Integer> qtyByProduct) {
        Customer customer = customerRepository.findById(customerId);
        if (customer == null) {
            throw new IllegalArgumentException("客户不存在: " + customerId);
        }

        List<OrderItem> items = new ArrayList<>();
        List<OrderItem> reserved = new ArrayList<>();
        try {
            for (Map.Entry<String, Integer> e : qtyByProduct.entrySet()) {
                String productId = e.getKey();
                int qty = e.getValue();
                Validation.requirePositive(qty, "购买数量");
                Product product = productRepository.findById(productId);
                if (product == null) {
                    throw new IllegalArgumentException("商品不存在: " + productId);
                }
                if (!inventoryService.reserve(productId, qty)) {
                    throw new IllegalStateException("库存不足: " + productId);
                }
                OrderItem item = new OrderItem(productId, qty, product.priceCents());
                items.add(item);
                reserved.add(item);
            }
            long subtotal = pricingService.subtotal(items);
            long total = pricingService.charge(customer, subtotal);
            Order order = new Order(IdGenerator.next("ord"), customerId, items, total);
            orderRepository.save(order);
            return order;
        } catch (RuntimeException e) {
            for (OrderItem item : reserved) {
                inventoryService.release(item.productId(), item.qty());
            }
            throw e;
        }
    }

    /**
     * 取消订单：把订单置为 CANCELLED。
     * 注意：已发货订单不可取消。
     * 取消动作会改变订单状态，需与库存等其它模块保持一致。
     */
    public boolean cancelOrder(String orderId) {
        Order order = orderRepository.findById(orderId);
        if (order == null || Order.STATUS_CANCELLED.equals(order.status())) {
            return false;
        }
        if (Order.STATUS_SHIPPED.equals(order.status())) {
            return false;
        }
        order.markCancelled();
        orderRepository.save(order);
        return true;
    }

    public Order getOrder(String orderId) {
        return orderRepository.findById(orderId);
    }
}
