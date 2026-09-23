package com.example.ai.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考试记录：模拟考试交卷后把作答落到 SQLite（data/exam_records.db），
 * 并按解析文件里的标准答案批改（听力 1-25、阅读 26-55 判对错；作文与翻译不打分）。
 *
 * 日志统计页读取这里的数据展示考试记录，点开可回顾逐题作答与解析。
 */
@Service
public class ExamRecordService {

    /** 一条考试记录（列表用） */
    public record Record(long id, String paperId, String paperTitle, String level,
                         int year, int month, int set,
                         String submittedAt, int durationSec,
                         int listeningTotal, int listeningCorrect,
                         int readingTotal, int readingCorrect,
                         int objectiveTotal, int objectiveCorrect,
                         String writing, String translation,
                         boolean graded) {
    }

    /** 单题批改结果 */
    public record Grade(String mine, String correct, boolean ok) {
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final String dbPath;

    public ExamRecordService(@Value("${app.exam.db-path:data/exam_records.db}") String dbPath) {
        this.dbPath = dbPath;
    }

    private Connection open() throws Exception {
        Path path = Paths.get(dbPath).toAbsolutePath().normalize();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        SqliteSupport.ensureDriver();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path.toString().replace('\\', '/'));
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=MEMORY");
            st.execute("PRAGMA temp_store=MEMORY");
            // 被其它连接（或重复启动的程序实例）占用时先等待，而不是立刻抛错
            st.execute("PRAGMA busy_timeout=8000");
        }
        return conn;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS exam_records (
                        id                INTEGER PRIMARY KEY AUTOINCREMENT,
                        paper_id          TEXT    NOT NULL,
                        paper_title       TEXT    NOT NULL,
                        level             TEXT,
                        year              INTEGER,
                        month             INTEGER,
                        set_no            INTEGER,
                        submitted_at      TEXT    NOT NULL,
                        duration_sec      INTEGER NOT NULL DEFAULT 0,
                        listening_total   INTEGER NOT NULL DEFAULT 0,
                        listening_correct INTEGER NOT NULL DEFAULT 0,
                        reading_total     INTEGER NOT NULL DEFAULT 0,
                        reading_correct   INTEGER NOT NULL DEFAULT 0,
                        objective_total   INTEGER NOT NULL DEFAULT 0,
                        objective_correct INTEGER NOT NULL DEFAULT 0,
                        writing           TEXT,
                        translation       TEXT,
                        answers_json      TEXT,
                        grading_json      TEXT
                    )""");
            System.out.println("[考试记录] 数据库就绪: " + Paths.get(dbPath).toAbsolutePath().normalize());
        } catch (Exception e) {
            System.err.println("[考试记录] 初始化失败: " + e.getMessage());
        }
    }

    /* ==================== 批改 ==================== */

    /**
     * 按标准答案批改：听力 1-25、阅读 26-55。
     * 返回 题号 -> 批改结果（含未作答的题）。
     */
    public Map<String, Grade> grade(ExamAnalysisParser.Analysis analysis,
                                    Map<String, String> mine,
                                    int listeningFrom, int listeningTo,
                                    int readingFrom, int readingTo) {
        Map<String, Grade> out = new LinkedHashMap<>();
        Map<String, String> key = analysis == null ? Map.of() : analysis.answers();
        if (key.isEmpty()) {
            return out;
        }
        List<Integer> numbers = new ArrayList<>();
        for (int n = listeningFrom; n <= listeningTo; n++) {
            numbers.add(n);
        }
        for (int n = readingFrom; n <= readingTo; n++) {
            numbers.add(n);
        }
        for (int n : numbers) {
            String correct = key.get(String.valueOf(n));
            if (correct == null || correct.isBlank()) {
                continue;
            }
            String my = mine == null ? null : mine.get(String.valueOf(n));
            boolean answered = my != null && !my.isBlank();
            out.put(String.valueOf(n), new Grade(answered ? my : "", correct,
                    answered && my.trim().equalsIgnoreCase(correct.trim())));
        }
        return out;
    }

    /** 统计某个题号区间里的 总题数 / 答对数（只统计有标准答案的题） */
    public int[] count(Map<String, Grade> grading, int from, int to) {
        int total = 0;
        int correct = 0;
        for (int n = from; n <= to; n++) {
            Grade g = grading.get(String.valueOf(n));
            if (g == null) {
                continue;
            }
            total++;
            if (g.ok()) {
                correct++;
            }
        }
        return new int[]{total, correct};
    }

    /* ==================== 存储 ==================== */

    public long save(ExamService.PaperInfo paper, Map<String, Grade> grading,
                     int[] listening, int[] reading,
                     int durationSec, String writing, String translation,
                     String answersText) {
        int total = listening[0] + reading[0];
        int correct = listening[1] + reading[1];
        String now = LocalDateTime.now().format(TS);
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO exam_records(paper_id, paper_title, level, year, month, set_no,
                         submitted_at, duration_sec, listening_total, listening_correct,
                         reading_total, reading_correct, objective_total, objective_correct,
                         writing, translation, answers_json, grading_json)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
            ps.setString(1, paper.id());
            ps.setString(2, paper.title());
            ps.setString(3, paper.level());
            ps.setInt(4, paper.year());
            ps.setInt(5, paper.month());
            ps.setInt(6, paper.set());
            ps.setString(7, now);
            ps.setInt(8, durationSec);
            ps.setInt(9, listening[0]);
            ps.setInt(10, listening[1]);
            ps.setInt(11, reading[0]);
            ps.setInt(12, reading[1]);
            ps.setInt(13, total);
            ps.setInt(14, correct);
            ps.setString(15, writing);
            ps.setString(16, translation);
            ps.setString(17, answersText);
            ps.setString(18, "");
            ps.executeUpdate();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            System.err.println("[考试记录] 保存失败: " + e);
            throw new IllegalStateException("保存考试记录失败：" + e.getMessage(), e);
        }
    }

    /* ==================== 作答的文本编码（避免依赖 JSON 库） ==================== */

    /** Map<题号, 答案> -> "1=A;2=B;26=D" */
    public static String encodeAnswers(Map<String, String> answers) {
        if (answers == null || answers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : answers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey().trim()).append('=').append(e.getValue().trim());
        }
        return sb.toString();
    }

    /** "1=A;2=B" -> Map<题号, 答案> */
    public static Map<String, String> decodeAnswers(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split(";")) {
            int i = part.indexOf('=');
            if (i <= 0) {
                continue;
            }
            out.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
        }
        return out;
    }

    /** 考试记录列表（新 → 旧） */
    public List<Record> list(int limit) {
        List<Record> out = new ArrayList<>();
        String sql = """
                SELECT id, paper_id, paper_title, level, year, month, set_no, submitted_at,
                       duration_sec, listening_total, listening_correct, reading_total, reading_correct,
                       objective_total, objective_correct, writing, translation,
                       CASE WHEN grading_json IS NULL OR grading_json = '' THEN 0 ELSE 1 END AS graded
                FROM exam_records ORDER BY id DESC LIMIT ?""";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Record(
                            rs.getLong("id"), rs.getString("paper_id"), rs.getString("paper_title"),
                            rs.getString("level"), rs.getInt("year"), rs.getInt("month"), rs.getInt("set_no"),
                            rs.getString("submitted_at"), rs.getInt("duration_sec"),
                            rs.getInt("listening_total"), rs.getInt("listening_correct"),
                            rs.getInt("reading_total"), rs.getInt("reading_correct"),
                            rs.getInt("objective_total"), rs.getInt("objective_correct"),
                            rs.getString("writing"), rs.getString("translation"),
                            rs.getInt("graded") == 1));
                }
            }
        } catch (Exception e) {
            System.err.println("[考试记录] 查询失败: " + e.getMessage());
        }
        return out;
    }

    /** 一条记录的明细（含作答与批改 JSON） */
    public Map<String, Object> detail(long id) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM exam_records WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("paperId", rs.getString("paper_id"));
                m.put("paperTitle", rs.getString("paper_title"));
                m.put("submittedAt", rs.getString("submitted_at"));
                m.put("durationSec", rs.getInt("duration_sec"));
                m.put("listeningTotal", rs.getInt("listening_total"));
                m.put("listeningCorrect", rs.getInt("listening_correct"));
                m.put("readingTotal", rs.getInt("reading_total"));
                m.put("readingCorrect", rs.getInt("reading_correct"));
                m.put("objectiveTotal", rs.getInt("objective_total"));
                m.put("objectiveCorrect", rs.getInt("objective_correct"));
                m.put("writing", rs.getString("writing"));
                m.put("translation", rs.getString("translation"));
                m.put("answersText", rs.getString("answers_json"));
                return m;
            }
        } catch (Exception e) {
            System.err.println("[考试记录] 查询明细失败: " + e.getMessage());
            return null;
        }
    }

    public boolean delete(long id) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM exam_records WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            System.err.println("[考试记录] 删除失败: " + e.getMessage());
            return false;
        }
    }
}
