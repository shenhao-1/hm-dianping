package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 优先检查前置的 RefreshTokenInterceptor 是否已经把用户放入 ThreadLocal。
        if (UserHolder.getUser() != null) {
            return true;
        }

        // 2. 兼容 session 登录态：从当前请求的 session 中获取登录用户。
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");
        if (user == null) {
            // 3. ThreadLocal 和 session 都没有用户信息，说明未登录，直接拦截后续 Controller 执行。
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        // 4. session 中存在用户时，保存到 ThreadLocal，方便本次请求后续业务代码直接获取当前用户。
        UserHolder.saveUser((UserDTO) user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 5. 请求结束后清理 ThreadLocal，避免线程复用时拿到上一次请求的用户信息。
        UserHolder.removeUser();
    }
}