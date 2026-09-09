package com.shop.repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库存台账：库存与商品目录分离的单一数据源。
 * tryReserve 扣减库存，addStock 回补库存。
 */
public class InventoryRepository {

    private final Map<String, Integer> stock = new LinkedHashMap<>();

    public InventoryRepository() {
        stock.put("p1", 50);
        stock.put("p2", 30);
        stock.put("p3", 20);
        stock.put("p4", 40);
        stock.put("p5", 12);
    }

    /** 尝试扣减库存；不足或不存在返回 false，成功则扣减并返回 true */
    public boolean tryReserve(String productId, int qty) {
        Integer cur = stock.get(productId);
        if (cur == null || cur < qty) return false;
        stock.put(productId, cur - qty);
        return true;
    }

    /** 回补库存（取消订单/回滚时调用） */
    public void addStock(String productId, int qty) {
        stock.merge(productId, qty, Integer::sum);
    }

    public int getStock(String productId) {
        return stock.getOrDefault(productId, 0);
    }
}
