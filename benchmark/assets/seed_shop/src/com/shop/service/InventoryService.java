package com.shop.service;

import com.shop.repository.InventoryRepository;

/** 库存服务：下单扣库存、取消/回滚回补库存的统一入口 */
public class InventoryService {

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    /** 下单占用库存，不足返回 false */
    public boolean reserve(String productId, int qty) {
        return repository.tryReserve(productId, qty);
    }

    /** 回补库存（取消订单或部分下单失败回滚时调用） */
    public void release(String productId, int qty) {
        repository.addStock(productId, qty);
    }

    public int stock(String productId) {
        return repository.getStock(productId);
    }
}
