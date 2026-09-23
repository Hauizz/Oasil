package com.example.ai.controller;

import com.example.ai.service.ChatLogService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 聊天日志查询接口（已撤销登录功能，无需登录即可访问）
 */
@RestController
@RequestMapping("/api/logs")
public class ChatLogController {

    private final ChatLogService chatLogService;

    public ChatLogController(ChatLogService chatLogService) {
        this.chatLogService = chatLogService;
    }

    /** 最近聊天记录，默认 50 条 */
    @GetMapping("/recent")
    public Map<String, Object> recent(@RequestParam(defaultValue = "50") int limit) {
        List<ChatLogService.ChatLogRow> rows = chatLogService.recent(limit);
        return Map.of("ok", true, "count", rows.size(), "logs", rows);
    }

    /** 统计汇总：总提问数 / 最近提问时间 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        ChatLogService.Stats s = chatLogService.stats();
        return Map.of(
                "ok", true,
                "total", s.total(),
                "lastLogTime", s.lastLogTime() == null ? "" : s.lastLogTime());
    }
}
