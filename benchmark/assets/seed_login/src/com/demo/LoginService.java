package com.demo;

/**
 * 登录认证服务：校验用户名与密码，是"认证"相关逻辑的核心。
 * 入口方法为 authenticate()，返回登录是否成功及用户角色。
 */
public class LoginService {

    private final UserRepository repository;

    public LoginService(UserRepository repository) {
        this.repository = repository;
    }

    /**
     * 认证入口：根据用户名查找账号，比对密码摘要。
     * @return 成功返回 "OK:<role>"，失败返回 "DENIED"。
     */
    public String authenticate(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null) {
            return "DENIED";
        }
        User user = repository.findByUsername(username);
        if (user == null) {
            return "DENIED";
        }
        String expected = UserRepository.hash(rawPassword);
        if (!user.passwordHash().equals(expected)) {
            return "DENIED";
        }
        return "OK:" + user.role();
    }

    /** 是否拥有管理员权限 */
    public boolean isAdmin(String result) {
        return result != null && result.equals("OK:ADMIN");
    }
}
