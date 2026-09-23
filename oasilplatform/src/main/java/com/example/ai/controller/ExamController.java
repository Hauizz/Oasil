package com.example.ai.controller;

import com.example.ai.service.ExamAnalysisParser;
import com.example.ai.service.ExamRecordService;
import com.example.ai.service.ExamService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四六级模拟考试接口：
 *   GET    /api/exam/levels                可选的级别（四级 / 六级）
 *   GET    /api/exam/papers?level=…        某级别下按年份月份分组的试卷（套）
 *   GET    /api/exam/content?id=…          试卷的结构化内容（Word 版解析出的题面）
 *   GET    /api/exam/file?id=…&amp;kind=…  下发试卷 PDF（kind=pdf）或听力音频（kind=audio）
 *   GET    /api/exam/analysis?id=…         解析文件（标准答案 / 逐题解析 / 原文译文 / 范文）
 *   POST   /api/exam/submit                交卷：批改 + 存入考试记录
 *   GET    /api/exam/records               考试记录列表
 *   GET    /api/exam/records/{id}          考试记录回顾（批改 + 题面 + 解析）
 *   DELETE /api/exam/records/{id}          删除一条考试记录
 */
@RestController
@RequestMapping("/api/exam")
public class ExamController {

    /** 客观题题号范围（听力 1-25 / 阅读 26-55） */
    private static final int LISTEN_FROM = 1;
    private static final int LISTEN_TO = 25;
    private static final int READ_FROM = 26;
    private static final int READ_TO = 55;

    private final ExamService service;
    private final ExamRecordService records;

    public ExamController(ExamService service, ExamRecordService records) {
        this.service = service;
        this.records = records;
    }

    @GetMapping("/levels")
    public Map<String, Object> levels() {
        List<ExamService.LevelInfo> levels = service.levels();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        // 实际使用的试卷仓库目录（便于排查「找不到试题」）
        res.put("repoDir", service.examRoot().toString());
        res.put("levels", levels);
        return res;
    }

    @GetMapping("/papers")
    public Map<String, Object> papers(@RequestParam("level") String level) {
        List<ExamService.PaperInfo> papers = service.scanPapers(level);
        return Map.of("ok", true, "level", level, "papers", papers);
    }

    @GetMapping("/paper/{id}")
    public Map<String, Object> paper(@PathVariable String id) {
        ExamService.PaperInfo p = service.findPaper(id);
        if (p == null) {
            return Map.of("ok", false, "message", "试卷不存在");
        }
        return Map.of("ok", true, "paper", p);
    }

    /** 试卷的结构化内容：听力 / 阅读 / 写作 / 翻译 */
    @GetMapping("/content")
    public Map<String, Object> content(@RequestParam("id") String id) {
        ExamService.ContentResult r = service.content(id);
        if (r == null) {
            return Map.of("ok", false, "message", "试卷不存在");
        }
        return Map.of("ok", true, "paper", r.paper(), "content", r.content());
    }

    @GetMapping("/file")
    public ResponseEntity<Resource> file(@RequestParam("id") String id,
                                         @RequestParam(value = "kind", defaultValue = "pdf") String kind) {
        Path path = service.resolveFile(id, kind);
        if (path == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        boolean audio = "audio".equals(kind);
        return ResponseEntity.ok()
                .contentType(audio ? MediaType.parseMediaType("audio/mpeg") : MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new FileSystemResource(path));
    }

    /* ==================== 解析文件（标准答案 / 逐题解析 / 原文译文） ==================== */

    @GetMapping("/analysis")
    public Map<String, Object> analysis(@RequestParam("id") String id) {
        ExamAnalysisParser.Analysis a = service.analysis(id);
        if (a == null) {
            return Map.of("ok", false, "message", "试卷不存在");
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("analysis", a);
        return res;
    }

    /* ==================== 交卷批改 + 考试记录 ==================== */

    /** 交卷：按解析文件的标准答案批改并存入考试记录 */
    @PostMapping("/submit")
    @SuppressWarnings("unchecked")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        String paperId = String.valueOf(body.getOrDefault("paperId", ""));
        ExamService.PaperInfo paper = service.findPaper(paperId);
        if (paper == null) {
            return Map.of("ok", false, "message", "试卷不存在");
        }
        Map<String, String> mine = new LinkedHashMap<>();
        Object raw = body.get("answers");
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() != null) {
                    mine.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        String writing = body.get("writing") == null ? "" : String.valueOf(body.get("writing"));
        String translation = body.get("translation") == null ? "" : String.valueOf(body.get("translation"));
        int duration = body.get("durationSec") instanceof Number n ? n.intValue() : 0;

        ExamAnalysisParser.Analysis analysis = service.analysis(paperId);
        Map<String, ExamRecordService.Grade> grading =
                records.grade(analysis, mine, LISTEN_FROM, LISTEN_TO, READ_FROM, READ_TO);
        int[] listening = records.count(grading, LISTEN_FROM, LISTEN_TO);
        int[] reading = records.count(grading, READ_FROM, READ_TO);

        long recordId = 0;
        String saveError = "";
        if (!grading.isEmpty() || !writing.isBlank() || !translation.isBlank()) {
            try {
                recordId = records.save(paper, grading, listening, reading, duration, writing, translation,
                        ExamRecordService.encodeAnswers(mine));
            } catch (Exception e) {
                // 保存失败时把原因带回前端，而不是只给一个含糊的「服务不可用」
                saveError = e.getMessage();
                System.err.println("[考试记录] " + e);
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("recordId", recordId);
        res.put("saveError", saveError);
        res.put("listening", Map.of("total", listening[0], "correct", listening[1]));
        res.put("reading", Map.of("total", reading[0], "correct", reading[1]));
        res.put("objective", Map.of("total", listening[0] + reading[0], "correct", listening[1] + reading[1]));
        res.put("grading", grading);
        res.put("analysisQuality", analysis == null ? "none" : analysis.quality());
        res.put("analysisWarning", analysis == null ? "" : analysis.warning());
        return res;
    }

    /** 考试记录列表 */
    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        List<ExamRecordService.Record> list = records.list(Math.max(1, Math.min(limit, 500)));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("count", list.size());
        res.put("records", list);
        return res;
    }

    /** 一条考试记录的回顾数据：批改结果 + 试卷内容 + 解析 */
    @GetMapping("/records/{id}")
    public Map<String, Object> recordDetail(@PathVariable long id) {
        Map<String, Object> d = records.detail(id);
        if (d == null) {
            return Map.of("ok", false, "message", "记录不存在");
        }
        String paperId = String.valueOf(d.get("paperId"));
        ExamService.PaperInfo paper = service.findPaper(paperId);
        Map<String, String> mine = ExamRecordService.decodeAnswers(String.valueOf(d.get("answersText")));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("record", d);
        res.put("answers", mine);
        res.put("paper", paper);
        if (paper != null) {
            ExamAnalysisParser.Analysis analysis = service.analysis(paperId);
            ExamService.ContentResult c = service.content(paperId);
            res.put("content", c == null ? null : c.content());
            res.put("analysis", analysis);
            res.put("grading", records.grade(analysis, mine, LISTEN_FROM, LISTEN_TO, READ_FROM, READ_TO));
        }
        return res;
    }

    @DeleteMapping("/records/{id}")
    public Map<String, Object> deleteRecord(@PathVariable long id) {
        return records.delete(id)
                ? Map.of("ok", true, "message", "已删除")
                : Map.of("ok", false, "message", "记录不存在");
    }
}
