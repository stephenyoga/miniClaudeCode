package com.demo;

/** 演示入口 */
public class Main {

    public static void main(String[] args) {
        UserRepository repo = new UserRepository();
        LoginService service = new LoginService(repo);

        String r1 = service.authenticate("admin", "admin123");
        System.out.println("admin/admin123 -> " + r1);   // 期望 OK:ADMIN

        String r2 = service.authenticate("admin", "wrong");
        System.out.println("admin/wrong    -> " + r2);   // 期望 DENIED

        System.out.println("isAdmin(r1) = " + service.isAdmin(r1));   // 期望 true
        System.out.println("isAdmin(r2) = " + service.isAdmin(r2));   // 期望 false
    }
}
