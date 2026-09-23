package com.example.ai.controller;

import com.example.ai.service.DocumentTextExtractor;
import com.example.ai.service.FileRepositoryService;
import com.example.ai.service.QuestionParser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「答题」页接口（已撤销登录功能，无需登录即可访问）：
 *   GET /api/questions/{fileId}
 *   读取仓库里某个文件的内容 -> 提取正文 -> 解析出题目 -> 返回结构化 JSON
 */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    /** 原文预览最多返回多少字 */
    private static final int PREVIEW_LIMIT = 2000;

    private final FileRepositoryService fileRepository;
    private final DocumentTextExtractor extractor;
    private final QuestionParser parser;

    public QuestionController(FileRepositoryService fileRepository,
                              DocumentTextExtractor extractor,
                              QuestionParser parser) {
        this.fileRepository = fileRepository;
        this.extractor = extractor;
        this.parser = parser;
    }

    @GetMapping("/{fileId}")
    public Map<String, Object> questions(@PathVariable long fileId) {
        Map<String, Object> res = new LinkedHashMap<>();

        FileRepositoryService.FileRecord file = fileRepository.get(fileId);
        if (file == null) {
            res.put("ok", false);
            res.put("message", "文件不存在或已被删除");
            return res;
        }

        Path path = fileRepository.resolvePath(file);
        DocumentTextExtractor.Result extracted = extractor.extract(file.originalName(), path);

        List<QuestionParser.Question> questions = List.of();
        StringBuilder warning = new StringBuilder(extracted.warning() == null ? "" : extracted.warning());

        if (extracted.ok()) {
            QuestionParser.Result parsed = parser.parse(extracted.text());
            questions = parsed.questions();
            if (parsed.warning() != null && !parsed.warning().isEmpty()) {
                if (warning.length() > 0) {
                    warning.append(' ');
                }
                warning.append(parsed.warning());
            }
        }

        res.put("ok", true);
        res.put("file", file);
        res.put("kind", extracted.kind());
        res.put("warning", warning.toString());
        res.put("textLength", extracted.text() == null ? 0 : extracted.text().length());
        res.put("count", questions.size());
        res.put("questions", questions);
        res.put("preview", preview(extracted.text()));
        return res;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= PREVIEW_LIMIT ? text : text.substring(0, PREVIEW_LIMIT) + "\n…（已截断）";
    }

}
