package com.shop.model;

/** 订单行：下单时冻结该商品当时的单价 */
public record OrderItem(String productId, int qty, long unitPriceCents) {

    public long subtotal() {
        return unitPriceCents * qty;
    }
}
