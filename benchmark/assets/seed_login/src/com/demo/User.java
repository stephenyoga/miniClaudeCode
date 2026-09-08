package com.demo;

/** 用户实体 */
public class User {
    private final String username;
    private final String passwordHash;
    private final String role;

    public User(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public String username() { return username; }
    public String passwordHash() { return passwordHash; }
    public String role() { return role; }
}
