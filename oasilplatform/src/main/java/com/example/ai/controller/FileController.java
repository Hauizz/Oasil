package com.example.ai.controller;

import com.example.ai.service.FileRepositoryService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文件仓库接口（已撤销登录功能，无需登录即可访问）：
 *  - POST /api/files/upload  上传文件（multipart 字段名 file）
 *  - GET  /api/files         列出全部文件
 *  - GET  /api/files/{id}    下载文件
 *  - DELETE /api/files/{id}  删除文件
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileRepositoryService service;

    /** 已撤销登录功能，上传者统一记录为访客 */
    private static final String GUEST_NAME = "访客";

    public FileController(FileRepositoryService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        try {
            FileRepositoryService.FileRecord r = service.save(file, GUEST_NAME);
            return Map.of("ok", true, "message", "上传成功", "file", r);
        } catch (IOException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    @GetMapping
    public Map<String, Object> list() {
        List<FileRepositoryService.FileRecord> rows = service.list();
        return Map.of("ok", true, "count", rows.size(), "files", rows);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        FileRepositoryService.FileRecord r = service.get(id);
        if (r == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Path path = service.resolvePath(r);
        if (!Files.exists(path)) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        String encoded = URLEncoder.encode(r.originalName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(resolveMediaType(r.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(new FileSystemResource(path));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        boolean ok = service.delete(id);
        return ok
                ? Map.of("ok", true, "message", "已删除")
                : Map.of("ok", false, "message", "文件不存在或已被删除");
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType != null && !contentType.isBlank()) {
            try {
                return MediaType.parseMediaType(contentType);
            } catch (Exception ignore) {
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
