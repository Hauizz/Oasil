package com.example.ai.controller;

import com.example.ai.dto.ChatRequest;
import com.example.ai.service.AiChatService;
import org.springframework.web.bind.annotation.*;

/**
 * AI 对话接口（已撤销登录功能，无需登录即可访问）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest request) {
        return aiChatService.chat(request.message());
    }
}
