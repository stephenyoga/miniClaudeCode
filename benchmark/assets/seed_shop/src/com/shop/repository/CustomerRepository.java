package com.shop.repository;

import com.shop.model.Customer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户存储（内存），启动预置两名测试客户 */
public class CustomerRepository {

    private final Map<String, Customer> customers = new LinkedHashMap<>();

    public CustomerRepository() {
        customers.put("c1", new Customer("c1", "张三", Customer.LEVEL_NORMAL));
        customers.put("c2", new Customer("c2", "李四", Customer.LEVEL_VIP));
    }

    public Customer findById(String id) {
        return customers.get(id);
    }

    public List<Customer> findAll() {
        return new ArrayList<>(customers.values());
    }

    public void save(Customer customer) {
        customers.put(customer.id(), customer);
    }
}
