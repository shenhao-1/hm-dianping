package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.UvService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDate;

@RestController
@RequestMapping("/stats")
public class StatsController {

    @Resource
    private UvService uvService;

    @GetMapping("/uv")
    public Result uv(@RequestParam(value = "date", required = false)
                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        return Result.ok(uvService.count(targetDate));
    }
}