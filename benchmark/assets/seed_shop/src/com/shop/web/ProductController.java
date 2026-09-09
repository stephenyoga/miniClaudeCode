package com.shop.web;

import com.shop.model.Product;
import com.shop.repository.ProductRepository;
import com.shop.util.PriceFormatter;

/** 商品端点 */
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public void printCatalog() {
        System.out.println("== 商品目录 ==");
        for (Product p : productRepository.findAll()) {
            System.out.println(p.id() + " " + p.name() + " " + PriceFormatter.formatCents(p.priceCents()));
        }
    }
}
