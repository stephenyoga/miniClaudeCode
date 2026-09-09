package com.shop.model;

/** 商品（不含库存——库存单列在 InventoryRepository，保证库存单一数据源） */
public record Product(String id, String name, long priceCents) {
}
