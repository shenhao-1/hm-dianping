package com.hmdp.utils;

import com.hmdp.service.UvService;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class UvInterceptor implements HandlerInterceptor {

    private final UvService uvService;

    public UvInterceptor(UvService uvService) {
        this.uvService = uvService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        uvService.recordVisit(request);
        return true;
    }
}