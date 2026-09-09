package com.shop.model;

/** 客户。level: NORMAL / VIP */
public class Customer {

    public static final String LEVEL_NORMAL = "NORMAL";
    public static final String LEVEL_VIP = "VIP";

    private final String id;
    private final String name;
    private final String level;

    public Customer(String id, String name, String level) {
        this.id = id;
        this.name = name;
        this.level = level;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String level() { return level; }

    public boolean isVip() {
        return LEVEL_VIP.equals(level);
    }
}
