package com.example.ai.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天日志服务：把每次用户提问与 AI 回答写入 SQLite（data/chat_logs.db 的 chat_logs 表），
 * 并提供最近记录与汇总统计。
 */
@Service
public class ChatLogService {

    /** 一条聊天日志 */
    public record ChatLogRow(long id, String username, String question,
                             String answer, String createdAt) {
    }

    /** 统计汇总 */
    public record Stats(long total, String lastLogTime) {
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String dbPath;

    public ChatLogService(@Value("${app.chat-log.db-path:data/chat_logs.db}") String dbPath) {
        this.dbPath = dbPath;
    }

    /** 应用启动时确保表存在 */
    @PostConstruct
    public void init() {
        Path path = Paths.get(dbPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path.getParent());
            try (Connection conn = open();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS chat_logs (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            username    TEXT    NOT NULL,
                            question    TEXT    NOT NULL,
                            answer      TEXT    NOT NULL,
                            kb_hits     INTEGER NOT NULL DEFAULT 0,
                            created_at  TEXT    NOT NULL
                        )""");
            }
            System.out.println("[聊天日志] 日志库就绪: " + path);
        } catch (Exception e) {
            System.err.println("[聊天日志] 初始化失败: " + e.getMessage());
        }
    }

    /** 记录一次问答（kb_hits 列保留兼容老库，统一写 0） */
    public void log(String username, String question, String answer) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chat_logs(username, question, answer, kb_hits, created_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, username == null ? "anonymous" : username);
            ps.setString(2, question);
            ps.setString(3, answer);
            ps.setInt(4, 0);
            ps.setString(5, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[聊天日志] 写入失败: " + e);
        }
    }

    /** 最近 limit 条聊天记录（新→旧） */
    public List<ChatLogRow> recent(int limit) {
        List<ChatLogRow> rows = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, username, question, answer, created_at " +
                     "FROM chat_logs ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ChatLogRow(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("question"),
                            rs.getString("answer"),
                            rs.getString("created_at")));
                }
            }
        } catch (Exception e) {
            System.err.println("[聊天日志] 查询失败: " + e.getMessage());
        }
        return rows;
    }

    /** 统计：总提问数 / 最近一次提问时间 */
    public Stats stats() {
        try (Connection conn = open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) AS total, MAX(created_at) AS last_time FROM chat_logs")) {
            if (rs.next()) {
                return new Stats(rs.getLong("total"), rs.getString("last_time"));
            }
        } catch (Exception e) {
            System.err.println("[聊天日志] 统计失败: " + e.getMessage());
        }
        return new Stats(0, null);
    }

    private Connection open() throws Exception {
        Path path = Paths.get(dbPath).toAbsolutePath().normalize();
        SqliteSupport.ensureDriver();
        String url = "jdbc:sqlite:" + path.toString().replace('\\', '/');
        Connection conn = DriverManager.getConnection(url);
        // journal 放内存，避免每次写入需要在数据库目录新建 journal 文件
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=MEMORY");
            st.execute("PRAGMA temp_store=MEMORY");
        }
        return conn;
    }
}
