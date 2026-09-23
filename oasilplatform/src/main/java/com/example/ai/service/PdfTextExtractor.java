package com.example.ai.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 正文提取（尽力而为，不依赖第三方库）。
 *
 * 思路：
 *  1. 按 ISO-8859-1 把 PDF 当字符串扫描，取出所有 obj 与 stream；
 *  2. FlateDecode 的流用 zlib 解压；
 *  3. 从 ToUnicode CMap（beginbfchar / beginbfrange）建立 CID -> Unicode 映射，
 *     并尽量关联到内容流里 /Fx ... Tf 选中的字体；
 *  4. 解析内容流里的 Tj / TJ / ' / " 文本算子，取出字符串并按映射还原文字。
 *
 * 局限（提取结果里会带 warning 提示）：
 *  - 扫描件（图片型 PDF）没有任何文本层，取不出文字；
 *  - 用 Identity-H 且缺少 ToUnicode 的字体无法还原成可读文字；
 *  - 不解析 ObjStm 等 PDF 1.5+ 的交叉引用结构（内容流仍能扫到，字体映射可能缺失）。
 */
final class PdfTextExtractor {

    private PdfTextExtractor() {
    }

    static DocumentTextExtractor.Result extract(byte[] pdf) {
        try {
            String raw = new String(pdf, StandardCharsets.ISO_8859_1);

            // ---------- 1. 收集所有对象体 ----------
            Map<Integer, String> objects = new LinkedHashMap<>();
            Matcher obj = Pattern.compile("(?s)(\\d+)\\s+\\d+\\s+obj(.*?)endobj").matcher(raw);
            while (obj.find()) {
                objects.put(Integer.parseInt(obj.group(1)), obj.group(2));
            }

            // ---------- 2. 解压所有流 ----------
            List<String> inflated = new ArrayList<>();
            for (String body : objects.values()) {
                String s = streamOf(body);
                if (s == null) {
                    continue;
                }
                byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
                if (body.contains("/FlateDecode")) {
                    byte[] out = DocumentTextExtractor.inflate(bytes);
                    if (out != null) {
                        inflated.add(new String(out, StandardCharsets.ISO_8859_1));
                        continue;
                    }
                }
                inflated.add(s);
            }

            // ---------- 3. ToUnicode CMap ----------
            Map<Integer, CMap> cmapByObject = new HashMap<>();
            CMap merged = new CMap();
            for (Map.Entry<Integer, String> e : objects.entrySet()) {
                String s = streamOf(e.getValue());
                if (s == null) {
                    continue;
                }
                String text = s;
                if (e.getValue().contains("/FlateDecode")) {
                    byte[] out = DocumentTextExtractor.inflate(s.getBytes(StandardCharsets.ISO_8859_1));
                    if (out != null) {
                        text = new String(out, StandardCharsets.ISO_8859_1);
                    }
                }
                if (text.contains("beginbfchar") || text.contains("beginbfrange")) {
                    CMap cm = parseCMap(text);
                    if (!cm.map.isEmpty()) {
                        cmapByObject.put(e.getKey(), cm);
                        merged.merge(cm);
                    }
                }
            }

            // ---------- 4. 字体资源名 -> ToUnicode ----------
            // 字体字典对象：/ToUnicode N 0 R
            Map<Integer, CMap> cmapByFontObj = new HashMap<>();
            for (Map.Entry<Integer, String> e : objects.entrySet()) {
                Matcher m = Pattern.compile("/ToUnicode\\s+(\\d+)\\s+\\d+\\s+R").matcher(e.getValue());
                if (m.find()) {
                    CMap cm = cmapByObject.get(Integer.parseInt(m.group(1)));
                    if (cm != null) {
                        cmapByFontObj.put(e.getKey(), cm);
                    }
                }
            }
            // 资源里的 /F1 5 0 R ：统计出现次数最多的对象，避免不同页重名冲突
            Map<String, Map<Integer, Integer>> nameVotes = new HashMap<>();
            for (String body : objects.values()) {
                Matcher m = Pattern.compile("/Font\\s*<<(.*?)>>", Pattern.DOTALL).matcher(body);
                while (m.find()) {
                    Matcher f = Pattern.compile("/([A-Za-z0-9#._+-]+)\\s+(\\d+)\\s+\\d+\\s+R").matcher(m.group(1));
                    while (f.find()) {
                        nameVotes.computeIfAbsent(f.group(1), k -> new HashMap<>())
                                .merge(Integer.parseInt(f.group(2)), 1, Integer::sum);
                    }
                }
            }
            Map<String, CMap> cmapByFontName = new HashMap<>();
            for (Map.Entry<String, Map<Integer, Integer>> e : nameVotes.entrySet()) {
                int bestObj = -1;
                int bestCount = -1;
                for (Map.Entry<Integer, Integer> v : e.getValue().entrySet()) {
                    if (v.getValue() > bestCount) {
                        bestCount = v.getValue();
                        bestObj = v.getKey();
                    }
                }
                CMap cm = cmapByFontObj.get(bestObj);
                if (cm != null) {
                    cmapByFontName.put(e.getKey(), cm);
                }
            }

            // ---------- 5. 解析内容流 ----------
            StringBuilder out = new StringBuilder();
            for (String content : inflated) {
                if (!looksLikeContent(content)) {
                    continue;
                }
                String page = extractFromContent(content, cmapByFontName, merged);
                if (!page.isBlank()) {
                    out.append(page).append('\n');
                }
            }

            String text = DocumentTextExtractor.normalize(out.toString());
            if (text.isBlank()) {
                return new DocumentTextExtractor.Result("", "pdf",
                        "这个 PDF 里没有可提取的文字层（可能是扫描件/纯图片，或者字体缺少 ToUnicode 映射）。"
                                + "建议改用 Word（.docx）或文本文件上传。");
            }

            String warning = "";
            double readable = readableRatio(text);
            if (readable < 0.5) {
                warning = "PDF 文字提取质量较低（可读字符占比 " + Math.round(readable * 100)
                        + "%），部分内容可能缺失或乱码。建议改用 Word（.docx）上传以获得更准确的结果。";
            }
            return new DocumentTextExtractor.Result(text, "pdf", warning);
        } catch (Exception e) {
            return new DocumentTextExtractor.Result("", "pdf", "PDF 解析失败：" + e.getMessage());
        }
    }

    /* ==================== 内容流 ==================== */

    /**
     * 解析内容流里的文本算子（Tf / TJ / Tj / ' / " / T* / Td / TD / Tm）。
     * 用逐字符扫描代替整段正则匹配，避免超大内容流触发正则回溯导致的 StackOverflowError。
     */
    private static String extractFromContent(String content, Map<String, CMap> byName, CMap merged) {
        StringBuilder sb = new StringBuilder();
        CMap current = null;
        int n = content.length();
        int i = 0;
        while (i < n) {
            char c = content.charAt(i);

            // /字体名 ... Tf —— 选中当前字体
            if (c == '/') {
                int nameEnd = i + 1;
                while (nameEnd < n && isPdfNameChar(content.charAt(nameEnd))) {
                    nameEnd++;
                }
                if (nameEnd > i + 1) {
                    String name = content.substring(i + 1, nameEnd);
                    int ws = skipWs(content, nameEnd);
                    int numEnd = skipNumberToken(content, ws);
                    int ws2 = skipWs(content, numEnd);
                    if (numEnd > ws && ws2 + 2 <= n && content.startsWith("Tf", ws2)) {
                        CMap cm = byName.get(name);
                        if (cm != null) {
                            current = cm;
                        }
                        i = ws2 + 2;
                        continue;
                    }
                    i = nameEnd;
                    continue;
                }
            }

            // [ ... ] TJ —— 字符串与字距混排的数组
            if (c == '[') {
                int j = scanToBracketEnd(content, i + 1);
                if (j < n) {
                    int k = skipWs(content, j + 1);
                    if (k + 2 <= n && content.startsWith("TJ", k)) {
                        sb.append(decodeArray(content.substring(i + 1, j), current, merged));
                        i = k + 2;
                        continue;
                    }
                    i = j + 1;
                    continue;
                }
            }

            // ( ... ) Tj / ' / " —— 单个字符串
            if (c == '(') {
                int j = readLiteralEnd(content, i);
                if (j > i) {
                    String lit = content.substring(i, j);
                    int k = skipWs(content, j);
                    if (k + 2 <= n && content.startsWith("Tj", k)) {
                        sb.append(decodeLiteral(lit, current, merged));
                        i = k + 2;
                        continue;
                    }
                    if (k < n && (content.charAt(k) == '\'' || content.charAt(k) == '"')) {
                        sb.append(decodeLiteral(lit, current, merged));
                        i = k + 1;
                        continue;
                    }
                    i = j;
                    continue;
                }
            }

            // T* —— 换行
            if (c == 'T' && i + 1 < n && content.charAt(i + 1) == '*') {
                sb.append('\n');
                i += 2;
                continue;
            }

            // num num Td / TD / Tm —— 定位算子，纵向位移明显时补一个换行
            if (c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                int a = skipNumberToken(content, i);
                if (a > i) {
                    int ws1 = skipWs(content, a);
                    int b = skipNumberToken(content, ws1);
                    int k = skipWs(content, b);
                    if (b > ws1 && k + 2 <= n
                            && (content.startsWith("Td", k) || content.startsWith("TD", k)
                            || content.startsWith("Tm", k))) {
                        try {
                            if (Math.abs(Double.parseDouble(content.substring(ws1, b).trim())) > 0.01) {
                                sb.append('\n');
                            }
                        } catch (NumberFormatException ignore) {
                            // 忽略
                        }
                        i = k + 2;
                        continue;
                    }
                }
            }

            i++;
        }
        return sb.toString();
    }

    /** TJ 数组：字符串与字距数字混排，较大的负数字距视为空格 */
    private static String decodeArray(String array, CMap font, CMap merged) {
        StringBuilder sb = new StringBuilder();
        int n = array.length();
        int i = 0;
        while (i < n) {
            char c = array.charAt(i);
            if (c == '(') {
                int j = readLiteralEnd(array, i);
                if (j > i) {
                    sb.append(decodeLiteral(array.substring(i, j), font, merged));
                    i = j;
                    continue;
                }
            }
            if (c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                int j = skipNumberToken(array, i);
                if (j > i) {
                    try {
                        if (Double.parseDouble(array.substring(i, j)) < -180) {
                            sb.append(' ');
                        }
                    } catch (NumberFormatException ignore) {
                        // 忽略
                    }
                    i = j;
                    continue;
                }
            }
            i++;
        }
        return sb.toString();
    }

    /* ---- 内容流扫描小工具 ---- */

    /** PDF 名字对象里的合法字符（不含空白与结构分隔符） */
    private static boolean isPdfNameChar(char c) {
        if (Character.isWhitespace(c)) {
            return false;
        }
        return "()<>[]{}/%".indexOf(c) < 0;
    }

    /** 跳过空白，返回下一个非空白下标 */
    private static int skipWs(String s, int i) {
        int n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 从 i 起跳过 - / . / 数字 组成的数字 token，返回其后的下标 */
    private static int skipNumberToken(String s, int i) {
        int n = s.length();
        int j = i;
        while (j < n) {
            char c = s.charAt(j);
            if (c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                j++;
            } else {
                break;
            }
        }
        return j;
    }

    /** 找到与 content[from-1] 处的 '[' 配对的 ']'，找不到返回 length */
    private static int scanToBracketEnd(String content, int from) {
        int n = content.length();
        int j = from;
        while (j < n && content.charAt(j) != ']') {
            if (content.charAt(j) == '\\') {
                j++;
            }
            j++;
        }
        return j;
    }

    /** 从 s[start]（应为 '('）读到配对的 ')' 之后，返回下标（不含右括号），失败返回 start */
    private static int readLiteralEnd(String s, int start) {
        int n = s.length();
        int depth = 0;
        int i = start;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return start;
    }

    private static String decodeLiteral(String literal, CMap font, CMap merged) {
        String inner = literal.substring(1, literal.length() - 1);
        byte[] bytes = pdfStringBytes(inner);
        return decodeBytes(bytes, font, merged);
    }

    /** PDF 字符串转义还原（\n \r \t \b \f \( \) \\ \ddd） */
    static byte[] pdfStringBytes(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                out.write(c & 0xFF);
                continue;
            }
            i++;
            if (i >= s.length()) {
                break;
            }
            char e = s.charAt(i);
            switch (e) {
                case 'n': out.write('\n'); break;
                case 'r': out.write('\r'); break;
                case 't': out.write('\t'); break;
                case 'b': out.write('\b'); break;
                case 'f': out.write('\f'); break;
                case '(': out.write('('); break;
                case ')': out.write(')'); break;
                case '\\': out.write('\\'); break;
                default:
                    if (e >= '0' && e <= '7') {
                        int v = 0;
                        int cnt = 0;
                        while (cnt < 3 && i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '7') {
                            v = v * 8 + (s.charAt(i) - '0');
                            i++;
                            cnt++;
                        }
                        i--;
                        out.write(v & 0xFF);
                    } else {
                        out.write(e & 0xFF);
                    }
            }
        }
        return out.toByteArray();
    }

    private static String decodeBytes(byte[] bytes, CMap font, CMap merged) {
        CMap cm = (font != null && !font.map.isEmpty()) ? font : null;
        if (cm == null && merged != null && !merged.map.isEmpty() && merged.twoByte) {
            cm = merged;
        }
        if (cm == null) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        StringBuilder sb = new StringBuilder();
        if (cm.width <= 1) {
            for (byte b : bytes) {
                Integer u = cm.map.get(b & 0xFF);
                sb.append(u != null ? new String(Character.toChars(u)) : String.valueOf((char) (b & 0xFF)));
            }
        } else {
            for (int i = 0; i + 1 < bytes.length; i += 2) {
                int code = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
                Integer u = cm.map.get(code);
                if (u != null) {
                    sb.appendCodePoint(u);
                }
            }
        }
        return sb.toString();
    }

    /* ==================== CMap ==================== */

    static final class CMap {
        final Map<Integer, Integer> map = new HashMap<>();
        int width = 1;
        boolean twoByte = false;

        void merge(CMap other) {
            map.putAll(other.map);
            if (other.twoByte) {
                twoByte = true;
                width = 2;
            }
        }
    }

    static CMap parseCMap(String cmap) {
        CMap cm = new CMap();

        Matcher csr = Pattern.compile("(?s)begincodespacerange(.*?)endcodespacerange").matcher(cmap);
        if (csr.find()) {
            Matcher r = Pattern.compile("<([0-9A-Fa-f]*)>\\s*<([0-9A-Fa-f]*)>").matcher(csr.group(1));
            if (r.find() && r.group(1).length() >= 4) {
                cm.width = 2;
                cm.twoByte = true;
            }
        }

        Matcher bc = Pattern.compile("(?s)beginbfchar(.*?)endbfchar").matcher(cmap);
        while (bc.find()) {
            Matcher p = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").matcher(bc.group(1));
            while (p.find()) {
                int src = Integer.parseInt(p.group(1), 16);
                cm.map.put(src, hexToCodePoint(p.group(2)));
                if (src > 0xFF) {
                    cm.twoByte = true;
                    cm.width = 2;
                }
            }
        }

        Matcher br = Pattern.compile("(?s)beginbfrange(.*?)endbfrange").matcher(cmap);
        while (br.find()) {
            Matcher p = Pattern.compile(
                    "(?s)<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(\\[[^\\]]*\\]|<[0-9A-Fa-f]+>)")
                    .matcher(br.group(1));
            while (p.find()) {
                int lo = (int) Long.parseLong(p.group(1), 16);
                int hi = (int) Long.parseLong(p.group(2), 16);
                if (hi < lo || hi - lo > 65535) {
                    continue;
                }
                String dst = p.group(3);
                if (dst.startsWith("[")) {
                    Matcher d = Pattern.compile("<([0-9A-Fa-f]+)>").matcher(dst);
                    int i = lo;
                    while (d.find() && i <= hi) {
                        cm.map.put(i++, hexToCodePoint(d.group(1)));
                    }
                } else {
                    String hex = dst.substring(1, dst.length() - 1);
                    int base = hexToCodePoint(hex);
                    for (int i = lo; i <= hi; i++) {
                        cm.map.put(i, base + (i - lo));
                    }
                }
                if (hi > 0xFF) {
                    cm.twoByte = true;
                    cm.width = 2;
                }
            }
        }
        return cm;
    }

    /** 4 位十六进制 = 一个 BMP 码点；8 位 = 可能是代理对 */
    private static int hexToCodePoint(String hex) {
        try {
            if (hex.length() <= 4) {
                return Integer.parseInt(hex, 16);
            }
            int hi = Integer.parseInt(hex.substring(0, 4), 16);
            if (hi >= 0xD800 && hi <= 0xDBFF && hex.length() >= 8) {
                int lo = Integer.parseInt(hex.substring(4, 8), 16);
                return Character.toCodePoint((char) hi, (char) lo);
            }
            return hi;
        } catch (Exception e) {
            return 0xFFFD;
        }
    }

    /* ==================== 杂项 ==================== */

    /** 取出对象体里的 stream 内容（去掉 stream/endstream 标记与首尾换行） */
    private static String streamOf(String body) {
        Matcher m = Pattern.compile("(?s)stream\\r?\\n(.*?)\\r?\\nendstream").matcher(body);
        if (!m.find()) {
            return null;
        }
        return m.group(1);
    }

    private static boolean looksLikeContent(String s) {
        return s.contains("BT") && (s.contains("Tj") || s.contains("TJ"));
    }

    /** 可读字符占比：中文/英文/数字/常用标点算可读 */
    private static double readableRatio(String text) {
        int total = 0;
        int good = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (Character.isLetterOrDigit(c) || isCjk(c) || "，。、；：？！（）《》“”‘’—…·,.;:?!()[]{}%+-*/=\"'<>".indexOf(c) >= 0) {
                good++;
            }
        }
        return total == 0 ? 0 : (double) good / total;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFFEF);
    }
}
