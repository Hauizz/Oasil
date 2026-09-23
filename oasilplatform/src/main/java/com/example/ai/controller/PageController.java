package com.example.ai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面入口控制：
 * 让 http://localhost:8080/ 直接进入菜单页 menu.html。
 *
 * 说明：Spring Boot 的默认欢迎页是 static/index.html，
 * 这里用 RequestMappingHandlerMapping（优先级高于欢迎页映射）显式接管 "/"，
 * 再 302 跳到 /menu.html。
 */
@Controller
public class PageController {

    /** 首页 -> 菜单页 */
    @GetMapping("/")
    public String index() {
        return "redirect:/menu.html";
    }
}
