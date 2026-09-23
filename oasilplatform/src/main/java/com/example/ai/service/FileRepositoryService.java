package com.example.ai.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件仓库服务：把用户上传的文件（Word / PDF 等）落到项目本地目录 data/repository，
 * 并在 SQLite（data/repository.db 的 files 表）里记录元数据，供“仓库”页面查看 / 下载。
 * 文件内容按 UUID 重命名存储，避免同名覆盖与路径注入；原始文件名保留在元数据里。
 */
@Service
public class FileRepositoryService {

    /** 一条文件记录 */
    public record FileRecord(long id, String originalName, String storedName, String owner,
                             String contentType, long size, String createdAt) {
    }

    /** 允许上传的常见文档 / 办公 / 文本 / 图片类型 */
    private static final Set<String> ALLOWED_EXT = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv", "json", "xml",
            "png", "jpg", "jpeg", "gif", "webp");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String dirPath;
    private final String dbPath;

    public FileRepositoryService(@Value("${app.repository.dir:data/repository}") String dirPath,
                                 @Value("${app.repository.db-path:data/repository.db}") String dbPath) {
        this.dirPath = dirPath;
        this.dbPath = dbPath;
    }

    /** 文件存储根目录（绝对路径） */
    private Path rootDir() {
        return Paths.get(dirPath).toAbsolutePath().normalize();
    }

    /** 应用启动时确保存储目录与元数据表存在 */
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootDir());
            Path db = Paths.get(dbPath).toAbsolutePath().normalize();
            if (db.getParent() != null) {
                Files.createDirectories(db.getParent());
            }
            try (Connection conn = open(); Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS files (
                            id            INTEGER PRIMARY KEY AUTOINCREMENT,
                            original_name TEXT    NOT NULL,
                            stored_name   TEXT    NOT NULL,
                            owner         TEXT    NOT NULL,
                            content_type  TEXT,
                            size          INTEGER NOT NULL,
                            created_at    TEXT    NOT NULL
                        )""");
            }
            System.out.println("[文件仓库] 存储目录就绪: " + rootDir());
        } catch (Exception e) {
            System.err.println("[文件仓库] 初始化失败: " + e.getMessage());
        }
    }

    /** 校验并保存一个上传文件，返回保存后的记录（含数据库自增 id） */
    public FileRecord save(MultipartFile file, String owner) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("未选择文件或文件为空");
        }
        String original = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename();
        String ext = extension(original);
        if (ext.isEmpty() || !ALLOWED_EXT.contains(ext)) {
            throw new IOException("不支持的文件类型（." + ext + "），支持 Word / Excel / PPT / PDF / 文本 / 图片等");
        }
        String stored = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String normalizedOwner = (owner == null || owner.isBlank()) ? "unknown" : owner;
        Path target = rootDir().resolve(stored);
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String now = LocalDateTime.now().format(TS);
        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO files(original_name, stored_name, owner, content_type, size, created_at) " +
                    "VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, original);
                ps.setString(2, stored);
                ps.setString(3, normalizedOwner);
                ps.setString(4, file.getContentType());
                ps.setLong(5, file.getSize());
                ps.setString(6, now);
                ps.executeUpdate();
            }
            // 注意：sqlite-jdbc 不支持 getGeneratedKeys()，改用 last_insert_rowid()
            long id;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                id = rs.next() ? rs.getLong(1) : 0L;
            }
            return new FileRecord(id, original, stored, normalizedOwner, file.getContentType(), file.getSize(), now);
        } catch (Exception e) {
            // 元数据入库失败：清理可能已写入的记录与已落盘文件，避免出现“孤儿文件 / 孤儿记录”
            deleteByStoredName(stored);
            try {
                Files.deleteIfExists(target);
            } catch (Exception ignore) {
            }
            throw new IOException("保存文件元数据失败: " + e.getMessage(), e);
        }
    }

    /** 按存储文件名删除元数据记录（清理用，失败仅记录日志） */
    private void deleteByStoredName(String storedName) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM files WHERE stored_name = ?")) {
            ps.setString(1, storedName);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[文件仓库] 清理元数据失败: " + e.getMessage());
        }
    }

    /** 列出全部文件记录（新 → 旧） */
    public List<FileRecord> list() {
        List<FileRecord> rows = new ArrayList<>();
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, original_name, stored_name, owner, content_type, size, created_at " +
                     "FROM files ORDER BY id DESC")) {
            while (rs.next()) {
                rows.add(mapRow(rs));
            }
        } catch (Exception e) {
            System.err.println("[文件仓库] 查询失败: " + e.getMessage());
        }
        return rows;
    }

    /** 按 id 查询单条记录，不存在返回 null */
    public FileRecord get(long id) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, original_name, stored_name, owner, content_type, size, created_at " +
                     "FROM files WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        } catch (Exception e) {
            System.err.println("[文件仓库] 查询失败: " + e.getMessage());
        }
        return null;
    }

    /** 删除一条记录：先删数据库行，再删磁盘文件 */
    public boolean delete(long id) {
        FileRecord r = get(id);
        if (r == null) {
            return false;
        }
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM files WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[文件仓库] 删除元数据失败: " + e.getMessage());
            return false;
        }
        try {
            Files.deleteIfExists(resolvePath(r));
        } catch (Exception e) {
            System.err.println("[文件仓库] 删除磁盘文件失败: " + e.getMessage());
        }
        return true;
    }

    /** 根据记录解析磁盘上的实际文件路径 */
    public Path resolvePath(FileRecord r) {
        return rootDir().resolve(r.storedName()).normalize();
    }

    private FileRecord mapRow(ResultSet rs) throws Exception {
        return new FileRecord(
                rs.getLong("id"),
                rs.getString("original_name"),
                rs.getString("stored_name"),
                rs.getString("owner"),
                rs.getString("content_type"),
                rs.getLong("size"),
                rs.getString("created_at"));
    }

    /** 取小写扩展名（不含点），无扩展名返回空串 */
    private String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Connection open() throws Exception {
        Path path = Paths.get(dbPath).toAbsolutePath().normalize();
        SqliteSupport.ensureDriver();
        String url = "jdbc:sqlite:" + path.toString().replace('\\', '/');
        Connection conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=MEMORY");
            st.execute("PRAGMA temp_store=MEMORY");
        }
        return conn;
    }
}
