package com.shop.repository;

import com.shop.model.Product;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 商品目录存储 */
public class ProductRepository {

    private final Map<String, Product> products = new LinkedHashMap<>();

    public ProductRepository() {
        products.put("p1", new Product("p1", "无线鼠标", 8900));
        products.put("p2", new Product("p2", "机械键盘", 39900));
        products.put("p3", new Product("p3", "USB-C 扩展坞", 19900));
        products.put("p4", new Product("p4", "显示器支架", 15900));
        products.put("p5", new Product("p5", "降噪耳机", 89900));
    }

    public Product findById(String id) {
        return products.get(id);
    }

    public List<Product> findAll() {
        return new ArrayList<>(products.values());
    }
}
