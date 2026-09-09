package com.shop.web;

import com.shop.model.Customer;
import com.shop.repository.CustomerRepository;
import com.shop.repository.InventoryRepository;
import com.shop.repository.OrderRepository;
import com.shop.repository.ProductRepository;
import com.shop.service.InventoryService;
import com.shop.service.OrderService;
import com.shop.service.PaymentService;
import com.shop.service.PricingService;
import com.shop.service.ReportService;

import java.util.Map;

/** 演示入口：组装各模块跑一个完整下单-支付-报表场景 */
public class App {

    public static void main(String[] args) {
        CustomerRepository customerRepository = new CustomerRepository();
        ProductRepository productRepository = new ProductRepository();
        InventoryRepository inventoryRepository = new InventoryRepository();
        OrderRepository orderRepository = new OrderRepository();

        InventoryService inventoryService = new InventoryService(inventoryRepository);
        PricingService pricingService = new PricingService();
        OrderService orderService = new OrderService(orderRepository, inventoryService, pricingService,
                customerRepository, productRepository);
        PaymentService paymentService = new PaymentService(orderRepository);
        ReportService reportService = new ReportService(orderRepository, customerRepository);

        OrderController orderController = new OrderController(orderService, pricingService, productRepository);
        ReportController reportController = new ReportController(reportService);
        ProductController productController = new ProductController(productRepository);
        Customer customer = customerRepository.findById("c2");

        productController.printCatalog();

        System.out.println("== 场景 ==");
        orderController.createOrder("c1", "p1:1,p2:1");
        var first = orderRepository.findAll().get(0);
        paymentService.pay(first.id());
        orderController.createOrder("c2", "p5:2");
        var second = orderRepository.findAll().get(1);
        paymentService.pay(second.id());
        orderController.createOrder("c2", "p5:2");
        var third = orderRepository.findAll().get(2);
        System.out.println("预览 c2 买 p1:2 应付(分): " + orderController.previewPrice(customer, "p1:2"));

        // 取消第三单：取消后 p5 库存应回补 2（从 8 回到 10）
        orderController.cancelOrder(third.id());
        System.out.println("p1 库存: " + inventoryService.stock("p1"));
        System.out.println("p5 库存: " + inventoryService.stock("p5") + " (期望 10，若仍为 8 说明取消未回补库存)");

        reportController.printRevenue();
        reportController.printCustomerRevenue();
    }
}
