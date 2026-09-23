package com.example.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 四六级模拟考试服务：扫描「文件仓库」目录（默认 data/repository）下的
 * CET-4 / CET-6 子目录，识别各年各月、各套真题（Word 优先、PDF 兜底）与对应听力音频（MP3），
 * 并把 Word 版真题解析成结构化的题面内容。
 *
 * 目录结构约定（用户后续继续往仓库里添加四六级试题即可被自动识别）：
 *   data/repository/
 *     CET-4/
 *       2025年12月四级真题+听力+答案/
 *         2025.12四级真题第1套.pdf        真题（PDF）
 *         2025年12月四级听力第1套.mp3      听力音频
 *     CET-6/
 *       ...
 *
 * 真题判定：文件名含「真题 / 原题」，且不含「解析 / 答案 / 详解」。
 * 套数判定：文件名里的「第X套 / 第一套」。
 * 听力音频：优先按「第X套」精确匹配，其次识别「全1套 / 3套相同 / 共用」这类共享音频，
 *           最后按第2套、第1套兜底。
 */
@Service
public class ExamService {

    /** 一个级别（四级 / 六级） */
    public record LevelInfo(String key, String name, int paperCount) {
    }

    /** 一份试卷（某个级别某年某月的某一套） */
    public record PaperInfo(String id, String level, String levelName, int year, int month, int set,
                            String title, String pdfFile, String docxFile, String audioFile, String analysisFile,
                            boolean hasPdf, boolean hasDocx, boolean hasAudio, boolean hasAnalysis) {
    }

    /** 试卷内容：试卷元信息 + 解析出来的结构化题面 */
    public record ContentResult(PaperInfo paper, ExamContentParser.Content content) {
    }

    private static final Map<String, String> LEVEL_NAMES = Map.of(
            "CET-4", "英语四级",
            "CET-6", "英语六级");

    private static final List<String> LEVEL_ORDER = List.of("CET-4", "CET-6");

    private final String dirPath;
    private final String dirOverride;
    private final DocumentTextExtractor extractor;
    private final ExamContentParser parser;
    private final ExamAnalysisParser analysisParser;

    /** 实际使用的仓库根目录（懒解析 + 缓存） */
    private volatile Path examRootCache;

    public ExamService(@Value("${app.repository.dir:data/repository}") String dirPath,
                       @Value("${app.exam.dir:}") String dirOverride,
                       DocumentTextExtractor extractor,
                       ExamContentParser parser,
                       ExamAnalysisParser analysisParser) {
        this.dirPath = dirPath;
        this.dirOverride = dirOverride;
        this.extractor = extractor;
        this.parser = parser;
        this.analysisParser = analysisParser;
    }

    /**
     * 试卷仓库根目录（绝对路径）。
     *
     * 注意：用「启动网站.bat」启动时工作目录是项目目录，而试题目录常常放在
     * 外层仓库根目录（例如 D:/oasil/data/repository），因此这里不能只认工作目录。
     * 定位顺序：
     *   1. 显式配置 app.exam.dir（如果配了就用它）；
     *   2. app.repository.dir（默认 data/repository，相对工作目录）；
     *   3. 从工作目录逐级向上找 &lt;上级&gt;/data/repository；
     *   4. 从 jar 所在目录逐级向上找 &lt;上级&gt;/data/repository。
     * 取第一个「真的含 CET-4 / CET-6 试卷」的目录；都没有则退回配置目录。
     */
    public Path examRoot() {
        Path cached = examRootCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (examRootCache == null) {
                examRootCache = resolveExamRoot();
            }
            return examRootCache;
        }
    }

    private Path resolveExamRoot() {
        if (dirOverride != null && !dirOverride.isBlank()) {
            Path p = Paths.get(dirOverride.trim()).toAbsolutePath().normalize();
            if (hasExamContent(p)) {
                return p;
            }
        }
        Path configured = Paths.get(dirPath).toAbsolutePath().normalize();
        if (hasExamContent(configured)) {
            return configured;
        }
        for (Path base : new Path[]{userDir(), jarDir()}) {
            for (Path p = base; p != null; p = p.getParent()) {
                Path cand = p.resolve("data").resolve("repository").normalize();
                if (hasExamContent(cand)) {
                    return cand;
                }
            }
        }
        return configured;
    }

    private Path userDir() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    private Path jarDir() {
        try {
            Path p = Paths.get(ExamService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : p.getParent();
        } catch (Exception e) {
            return userDir();
        }
    }

    /** 该目录下是否存在某个级别的试卷目录（有子文件夹才算，避免命中空的 data/repository） */
    private boolean hasExamContent(Path repo) {
        if (repo == null || !Files.isDirectory(repo)) {
            return false;
        }
        for (String level : LEVEL_ORDER) {
            Path dir = repo.resolve(level);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                // 继续看下一个级别
            }
        }
        return false;
    }

    /** 列出有试卷的级别 */
    public List<LevelInfo> levels() {
        List<LevelInfo> out = new ArrayList<>();
        for (String key : LEVEL_ORDER) {
            Path dir = examRoot().resolve(key).normalize();
            if (!Files.isDirectory(dir)) {
                continue;
            }
            out.add(new LevelInfo(key, LEVEL_NAMES.getOrDefault(key, key), scanPapers(key).size()));
        }
        return out;
    }

    /** 扫描某个级别下的全部试卷 */
    public List<PaperInfo> scanPapers(String level) {
        Path base = examRoot().resolve(level).normalize();
        List<PaperInfo> out = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return out;
        }

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path examDir : ds) {
                if (!Files.isDirectory(examDir)) {
                    continue;
                }
                int[] ym = parseYearMonth(examDir.getFileName().toString());
                if (ym == null) {
                    continue;
                }
                Map<Integer, Path> pdfs = pickBest(listFiles(examDir, "pdf"));
                Map<Integer, Path> docxs = pickBest(listFiles(examDir, "docx"));
                Map<Integer, Path> ans = pickBest(listAnalysisFiles(examDir));
                List<Path> audios = listFiles(examDir, "mp3");

                List<Integer> sets = new ArrayList<>(pdfs.keySet());
                for (Integer s : docxs.keySet()) {
                    if (!sets.contains(s)) {
                        sets.add(s);
                    }
                }
                for (Integer s : ans.keySet()) {
                    if (!sets.contains(s)) {
                        sets.add(s);
                    }
                }
                sets.sort(Integer::compareTo);

                for (int set : sets) {
                    Path pdf = pdfs.get(set);
                    Path docx = docxs.get(set);
                    Path analysis = ans.get(set);
                    if (pdf == null && docx == null) {
                        continue;
                    }
                    String audioRel = matchAudio(audios, set);
                    String levelName = LEVEL_NAMES.getOrDefault(level, level);
                    out.add(new PaperInfo(
                            id(level, ym[0], ym[1], set),
                            level, levelName,
                            ym[0], ym[1], set,
                            ym[0] + "年" + ym[1] + "月" + levelName + "真题 第" + set + "套",
                            pdf == null ? null : rel(pdf),
                            docx == null ? null : rel(docx),
                            audioRel,
                            analysis == null ? null : rel(analysis),
                            pdf != null,
                            docx != null,
                            audioRel != null,
                            analysis != null));
                }
            }
        } catch (IOException e) {
            // 扫描失败按空处理，前端会提示无试卷
        }

        out.sort(Comparator.comparingInt(PaperInfo::year)
                .thenComparingInt(PaperInfo::month)
                .thenComparingInt(PaperInfo::set));
        return out;
    }

    /** 按试卷 id 查试卷（用于文件下发 / 内容解析） */
    public PaperInfo findPaper(String id) {
        int i = id.indexOf(':');
        if (i <= 0) {
            return null;
        }
        String level = id.substring(0, i);
        return scanPapers(level).stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析试卷内容：优先用 Word 版真题解析成结构化题面（听力 / 阅读 / 写作 / 翻译）。
     * 没有 Word 版、或 Word 版解析不出题目时，返回 quality=none，前端改用「原卷 PDF」模式。
     */
    public ContentResult content(String id) {
        PaperInfo p = findPaper(id);
        if (p == null) {
            return null;
        }
        ExamContentParser.Content content;
        if (p.docxFile() != null) {
            Path docx = resolveRel(p.docxFile());
            DocumentTextExtractor.Result r = extractor.extractWithEmphasis(fileName(p.docxFile()), docx);
            content = parser.parse(r.text(), "docx");
        } else {
            content = new ExamContentParser.Content("none", "none",
                    "这份试卷没有可解析的 Word 版真题，已切换为「原卷 PDF」模式。",
                    "", "", List.of(), List.of(), "", "",
                    List.of("writing", "listening", "reading", "translation"));
        }
        return new ContentResult(p, content);
    }

    /** 把试卷里的某个文件（pdf / audio）解析成磁盘路径，找不到或越界返回 null */
    public Path resolveFile(String id, String kind) {
        PaperInfo p = findPaper(id);
        if (p == null) {
            return null;
        }
        String rel = "pdf".equals(kind) ? p.pdfFile() : p.audioFile();
        if (rel == null) {
            return null;
        }
        Path path = examRoot().resolve(rel).normalize();
        if (!path.startsWith(examRoot()) || !Files.exists(path)) {
            return null;
        }
        return path;
    }

    /* ==================== 解析小工具 ==================== */

    private Path resolveRel(String rel) {
        return examRoot().resolve(rel).normalize();
    }

    private String fileName(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    private String id(String level, int year, int month, int set) {
        return level + ":" + year + ":" + month + ":" + set;
    }

    /** 相对仓库根目录的路径（用 / 分隔） */
    private String rel(Path p) {
        return examRoot().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** 目录名里的「2025年12月 / 2025年6月」 */
    private int[] parseYearMonth(String name) {
        Matcher m = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月").matcher(name);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            if (y >= 1900 && y <= 2100 && mo >= 1 && mo <= 12) {
                return new int[]{y, mo};
            }
        }
        return null;
    }

    /** 文件名里的「第1套 / 第一套」 */
    private int parseSet(String name) {
        Matcher m = Pattern.compile("第\\s*([一二三四五六123456])\\s*套").matcher(name);
        if (m.find()) {
            return cnNum(m.group(1));
        }
        return 0;
    }

    private int cnNum(String s) {
        return switch (s) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            default -> {
                try {
                    yield Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
        };
    }

    /** 递归收集某目录下的某种文件（试卷类只取真题，跳过 Word 临时文件 ~$xxx.docx） */
    private List<Path> listFiles(Path dir, String ext) {
        boolean paperLike = "pdf".equals(ext) || "docx".equals(ext);
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith("~$") && n.toLowerCase().endsWith("." + ext);
                    })
                    .filter(p -> !paperLike || isRealPaper(p.getFileName().toString()))
                    .forEach(out::add);
        } catch (IOException e) {
            // 忽略
        }
        return out;
    }

    /** 是否是真题（而非解析 / 答案） */
    private boolean isRealPaper(String name) {
        boolean paper = name.contains("真题") || name.contains("原题");
        boolean answer = name.contains("解析") || name.contains("答案") || name.contains("详解");
        return paper && !answer;
    }

    /** 同一套可能有多个文件（如另存了一份「扫描版」），按套去重并优先保留非扫描版 */
    private Map<Integer, Path> pickBest(List<Path> files) {
        Map<Integer, Path> best = new LinkedHashMap<>();
        for (Path f : files) {
            int set = parseSet(f.getFileName().toString());
            if (set <= 0) {
                continue;
            }
            Path prev = best.get(set);
            if (prev == null) {
                best.put(set, f);
            } else if (rel(prev).contains("扫描版") && !rel(f).contains("扫描版")) {
                best.put(set, f);
            }
        }
        return best;
    }

    /** 递归收集试卷文件夹里的「解析 / 答案 / 详解」Word 文件（用于批改与解析） */
    private List<Path> listAnalysisFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        if (n.startsWith("~$") || !n.toLowerCase().endsWith(".docx")) {
                            return false;
                        }
                        return n.contains("解析") || n.contains("答案") || n.contains("详解");
                    })
                    .forEach(out::add);
        } catch (IOException e) {
            // 忽略
        }
        return out;
    }

    /** 读取并解析某套试卷的解析文件 */
    public ExamAnalysisParser.Analysis analysis(String id) {
        PaperInfo p = findPaper(id);
        if (p == null) {
            return null;
        }
        if (p.analysisFile() == null) {
            return new ExamAnalysisParser.Analysis("none", "none",
                    "这份试卷还没有 Word 版解析文件。把「解析」Word 放进该套试卷所在的文件夹"
                            + "（文件名里带「解析」，并带「第X套」以区分套数）后即可自动批改。",
                    Map.of(), Map.of(), Map.of(), Map.of(),
                    "", "", "", "", Map.of(), Map.of(), "", "", "", List.of(), Map.of());
        }
        Path docx = resolveRel(p.analysisFile());
        DocumentTextExtractor.Result r = extractor.extractWithEmphasis(fileName(p.analysisFile()), docx);
        return analysisParser.parse(r.text(), "docx");
    }

    /** 是否是多套共用的共享听力（全1套 / 3套相同 / 共用） */    private boolean isSharedAudio(String name) {
        return Pattern.compile("全\\s*[一二三四五六123456]\\s*套").matcher(name).find()
                || name.contains("相同") || name.contains("共用")
                || name.contains("一样") || name.contains("全部");
    }

    private Path findAudioBySet(List<Path> audios, int set) {
        for (Path a : audios) {
            if (parseSet(a.getFileName().toString()) == set) {
                return a;
            }
        }
        return null;
    }

    /** 为某一套匹配听力音频 */
    private String matchAudio(List<Path> audios, int set) {
        Path exact = findAudioBySet(audios, set);
        if (exact != null) {
            return rel(exact);
        }
        for (Path a : audios) {
            if (isSharedAudio(a.getFileName().toString())) {
                return rel(a);
            }
        }
        if (audios.size() == 1) {
            return rel(audios.get(0));
        }
        // 第3套常与第2套 / 第1套共用音频，兜底按 2 -> 1 找
        for (int fb : new int[]{2, 1}) {
            if (fb != set) {
                Path p = findAudioBySet(audios, fb);
                if (p != null) {
                    return rel(p);
                }
            }
        }
        return null;
    }
}
