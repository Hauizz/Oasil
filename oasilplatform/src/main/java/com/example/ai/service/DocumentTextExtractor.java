package com.example.ai.service;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档正文提取：把仓库里的文件转换成纯文本，供「答题」页提取题目使用。
 *
 * 不依赖第三方库（离线环境无法下载 POI / PDFBox），全部用 JDK 自带能力实现：
 *   - txt / md / csv / json / xml  ：按编码直接读取（自动识别 BOM，UTF-8 失败退 GBK）
 *   - docx                        ：docx 本质是 zip，取 word/document.xml 里的 &lt;w:t&gt;
 *   - xlsx                        ：取 sharedStrings.xml + 各 sheet 的行列结构
 *   - pptx                        ：取各 slide xml 里的 &lt;a:t&gt;
 *   - pdf                          ：见 {@link PdfTextExtractor}（尽力而为，可能不完整）
 */
@Service
public class DocumentTextExtractor {

    /** 提取结果 */
    public record Result(String text, String kind, String warning) {
        public boolean ok() {
            return text != null && !text.isBlank();
        }
    }

    /* 强调标记：私有区字符，随正文一起带到前端，由页面渲染成下划线 / 高亮 */
    public static final char U_ON = '\uE000';
    public static final char U_OFF = '\uE001';
    public static final char H_ON = '\uE002';
    public static final char H_OFF = '\uE003';

    /** 去掉所有强调标记（解析、题干等不需要强调的地方用） */
    public static String stripEmphasis(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(String.valueOf(U_ON), "").replace(String.valueOf(U_OFF), "")
                .replace(String.valueOf(H_ON), "").replace(String.valueOf(H_OFF), "");
    }

    /**
     * 保留下划线 / 高亮的正文提取（目前只有 docx 支持）。
     * 下划线用 U_ON/U_OFF 包裹，高亮用 H_ON/H_OFF 包裹；其它类型退回 {@link #extract}。
     */
    public Result extractWithEmphasis(String originalName, Path path) {
        String ext = extOf(originalName);
        if (!"docx".equals(ext)) {
            return extract(originalName, path);
        }
        try {
            if (!Files.exists(path)) {
                return new Result("", ext, "文件不存在或已被删除。");
            }
            return new Result(readDocxEmphasis(Files.readAllBytes(path)), "docx", "");
        } catch (Exception e) {
            return extract(originalName, path);
        }
    }

    /** 逐段落、逐 run 解析 docx，把下划线 / 高亮转成标记字符 */
    private String readDocxEmphasis(byte[] bytes) throws Exception {
        byte[] xml = readZipEntry(bytes, "word/document.xml");
        if (xml == null) {
            throw new IllegalStateException("不是有效的 .docx 文件（缺少 word/document.xml）");
        }
        String doc = new String(xml, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        Matcher para = Pattern.compile("(?s)<w:p(?:\\s[^>]*)?>.*?</w:p>").matcher(doc);
        while (para.find()) {
            appendRuns(para.group(), sb);
            sb.append('\n');
        }
        return normalize(sb.toString());
    }

    private void appendRuns(String para, StringBuilder sb) {
        Matcher run = Pattern.compile("(?s)<w:r(?:\\s[^>]*)?>(.*?)</w:r>").matcher(para);
        while (run.find()) {
            String body = run.group(1);
            if (body.contains("<w:br") || body.contains("<w:tab")) {
                sb.append('\n');
            }
            String text = unescapeXml(textsOf(body, "w:t"));
            if (text.isEmpty()) {
                continue;
            }
            boolean underline = isOn(body, "w:u");
            boolean highlight = isOn(body, "w:highlight");
            // 只有空白的下划线 = 填空题的空格线，直接画成下划线，保证页面上看得见
            if (underline && text.trim().isEmpty()) {
                sb.append("_".repeat(Math.max(4, Math.min(12, text.length()))));
                continue;
            }
            if (underline) {
                sb.append(U_ON);
            }
            if (highlight) {
                sb.append(H_ON);
            }
            sb.append(text);
            if (highlight) {
                sb.append(H_OFF);
            }
            if (underline) {
                sb.append(U_OFF);
            }
        }
    }

    /** run 的 rPr 里某个属性是否生效（val 缺省或 none / 0 / false 视为未生效） */
    private boolean isOn(String runBody, String prop) {
        Matcher m = Pattern.compile("<" + prop + "(?:\\s([^>]*))?/?>").matcher(runBody);
        if (!m.find()) {
            return false;
        }
        String attrs = m.group(1);
        if (attrs == null) {
            return true;
        }
        Matcher v = Pattern.compile("w:val\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        if (!v.find()) {
            return true;
        }
        String val = v.group(1).toLowerCase(Locale.ROOT);
        return !val.equals("none") && !val.equals("0") && !val.equals("false");
    }

    /** 支持的纯文本类扩展名 */
    private static final List<String> TEXT_EXT =
            List.of("txt", "md", "markdown", "csv", "json", "xml", "log", "ini", "properties");

    public Result extract(String originalName, Path path) {
        String ext = extOf(originalName);
        try {
            if (!Files.exists(path)) {
                return new Result("", ext, "文件不存在或已被删除。");
            }
            if (TEXT_EXT.contains(ext)) {
                return new Result(decodeText(Files.readAllBytes(path)), ext, "");
            }
            switch (ext) {
                case "docx":
                    return new Result(readDocx(Files.readAllBytes(path)), "docx", "");
                case "xlsx":
                    return new Result(readXlsx(Files.readAllBytes(path)), "xlsx", "");
                case "pptx":
                    return new Result(readPptx(Files.readAllBytes(path)), "pptx", "");
                case "pdf":
                    return PdfTextExtractor.extract(Files.readAllBytes(path));
                case "doc":
                    return new Result("", "doc",
                            "旧版 .doc 是二进制格式，本地无法解析。请用 Word 另存为 .docx 后重新上传。");
                case "xls":
                case "ppt":
                    return new Result("", ext,
                            "旧版 ." + ext + " 是二进制格式，本地无法解析。请另存为 ." + ext + "x 后重新上传。");
                case "png":
                case "jpg":
                case "jpeg":
                case "gif":
                case "webp":
                    return new Result("", ext, "图片需要 OCR 才能提取文字，当前不支持。请上传 Word / PDF / 文本文件。");
                default:
                    if (ext.isEmpty()) {
                        return new Result("", ext, "无法识别文件类型（没有扩展名）。");
                    }
                    return new Result("", ext, "暂不支持解析 ." + ext + " 格式。");
            }
        } catch (Exception e) {
            return new Result("", ext, "读取失败：" + e.getMessage());
        }
    }

    /* ==================== 纯文本 ==================== */

    /** 按 BOM 判断编码；没有 BOM 时先严格按 UTF-8 解码，失败再用 GBK */
    public static String decodeText(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception ignore) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception e2) {
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
    }

    /* ==================== docx ==================== */

    /** 语句块被打断的标签：段落结束 / 换行 / 制表 / 单元格 / 行 */
    private static final Pattern DOCX_TOKEN = Pattern.compile(
            "(?is)(</w:p>)|(<w:br\\s*/?>)|(<w:tab\\s*/?>)|(</w:tc>)|(</w:tr>)|(<w:t(?:\\s[^>]*)?>(.*?)</w:t>)");

    private String readDocx(byte[] bytes) throws Exception {
        byte[] xml = readZipEntry(bytes, "word/document.xml");
        if (xml == null) {
            throw new IllegalStateException("不是有效的 .docx 文件（缺少 word/document.xml）");
        }
        String doc = new String(xml, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        Matcher m = DOCX_TOKEN.matcher(doc);
        while (m.find()) {
            if (m.group(1) != null || m.group(2) != null || m.group(5) != null) {
                sb.append('\n');
            } else if (m.group(3) != null || m.group(4) != null) {
                sb.append('\t');
            } else if (m.group(6) != null) {
                sb.append(unescapeXml(m.group(7)));
            }
        }
        return normalize(sb.toString());
    }

    /* ==================== xlsx ==================== */

    private String readXlsx(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = readZipAll(bytes);
        List<String> shared = new ArrayList<>();
        byte[] ss = entries.get("xl/sharedStrings.xml");
        if (ss != null) {
            String xml = new String(ss, StandardCharsets.UTF_8);
            Matcher si = Pattern.compile("(?is)<si>(.*?)</si>").matcher(xml);
            while (si.find()) {
                shared.add(textsOf(si.group(1), "t"));
            }
        }

        List<String> sheets = new ArrayList<>(entries.keySet());
        sheets.removeIf(k -> !k.startsWith("xl/worksheets/") || !k.endsWith(".xml"));
        sheets.sort(String::compareTo);

        StringBuilder out = new StringBuilder();
        for (String sheet : sheets) {
            String xml = new String(entries.get(sheet), StandardCharsets.UTF_8);
            Matcher row = Pattern.compile("(?is)<row[^>]*>(.*?)</row>").matcher(xml);
            while (row.find()) {
                List<String> cells = new ArrayList<>();
                Matcher cell = Pattern.compile("(?is)<c([^>]*)>(.*?)</c>").matcher(row.group(1));
                while (cell.find()) {
                    String attrs = cell.group(1);
                    String body = cell.group(2);
                    String type = attr(attrs, "t");
                    String value = "";
                    if ("inlineStr".equals(type)) {
                        value = textsOf(body, "t");
                    } else {
                        Matcher v = Pattern.compile("(?is)<v>(.*?)</v>").matcher(body);
                        if (v.find()) {
                            value = unescapeXml(v.group(1));
                            if ("s".equals(type)) {
                                try {
                                    int idx = Integer.parseInt(value.trim());
                                    if (idx >= 0 && idx < shared.size()) {
                                        value = shared.get(idx);
                                    }
                                } catch (NumberFormatException ignore) {
                                    // 保持原值
                                }
                            }
                        }
                    }
                    cells.add(value.trim());
                }
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                    cells.remove(cells.size() - 1);
                }
                if (!cells.isEmpty()) {
                    out.append(String.join("\t", cells)).append('\n');
                }
            }
            out.append('\n');
        }
        return normalize(out.toString());
    }

    /* ==================== pptx ==================== */

    private String readPptx(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = readZipAll(bytes);
        List<String> slides = new ArrayList<>(entries.keySet());
        slides.removeIf(k -> !k.matches("(?i)ppt/slides/slide\\d+\\.xml"));
        slides.sort((a, b) -> Integer.compare(slideNo(a), slideNo(b)));

        StringBuilder out = new StringBuilder();
        for (String slide : slides) {
            String xml = new String(entries.get(slide), StandardCharsets.UTF_8);
            String text = textsOf(xml, "a:t");
            if (!text.isBlank()) {
                out.append(text).append('\n');
            }
        }
        return normalize(out.toString());
    }

    private int slideNo(String name) {
        Matcher m = Pattern.compile("(\\d+)").matcher(name.replaceAll(".*/", ""));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /* ==================== 通用小工具 ==================== */

    /** 取出所有 &lt;tag&gt;文本&lt;/tag&gt; 的内容（按出现顺序拼接，标签之间不额外加分隔） */
    private String textsOf(String xml, String tag) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("(?is)<" + tag + "(?:\\s[^>]*)?>(.*?)</" + tag + ">").matcher(xml);
        while (m.find()) {
            sb.append(unescapeXml(m.group(1)));
        }
        return sb.toString();
    }

    private String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : "";
    }

    /** XML 实体还原（含数字实体） */
    public static String unescapeXml(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        String r = s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
        Matcher m = Pattern.compile("&#(x?)([0-9A-Fa-f]+);").matcher(r);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            sb.appendCodePoint(cp);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 统一换行、去掉多余空行 */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String r = s.replace("\r\n", "\n").replace('\r', '\n');
        r = r.replace('\u00A0', ' ');
        r = r.replaceAll("[ \\t]+\\n", "\n");
        r = r.replaceAll("\\n{3,}", "\n\n");
        return r.trim();
    }

    private byte[] readZipEntry(byte[] bytes, String name) throws Exception {
        return readZipAll(bytes).get(name);
    }

    /** 把 zip 里的所有条目读进内存（文档一般不大） */
    private Map<String, byte[]> readZipAll(byte[] bytes) throws Exception {
        Map<String, byte[]> map = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zip.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                map.put(e.getName(), bos.toByteArray());
            }
        }
        return map;
    }

    /** zlib 解压（PDF 的 FlateDecode 用） */
    static byte[] inflate(byte[] data) {
        for (int skip = 0; skip <= 2 && skip < data.length; skip++) {
            try {
                Inflater inf = new Inflater();
                inf.setInput(data, skip, data.length - skip);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length * 4));
                byte[] buf = new byte[8192];
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) {
                            break;
                        }
                    }
                    bos.write(buf, 0, n);
                }
                inf.end();
                if (bos.size() > 0) {
                    return bos.toByteArray();
                }
            } catch (Exception ignore) {
                // 换个起点再试
            }
        }
        return null;
    }

    private String extOf(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
