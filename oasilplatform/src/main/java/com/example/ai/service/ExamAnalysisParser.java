package com.example.ai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析文件的解析：把「解析 / 答案 / 详解」的 Word 版正文，抽成
 * 标准答案 + 逐题解析 + 听力原文 + 逐段译文 + 写作/翻译参考内容。
 *
 * 解析 Word 的排版规律（以 2025.06 四级第一套为例）：
 *   Part I Writing
 *     ·审题 · / ·参考范文&点评 · / ·范文译文 ·
 *   Part II Listening Comprehension
 *     SectionA / News Report One / Conversation One / Passage One…
 *       ·听力原文 ·  → 听力原文
 *       ·答案详解 · / 解析 → 「1. 题干…\nA)…C)…\nB)…D)…\n解析文本（含"故选A"/"选项A为正确答案"）」
 *   Part III Reading Comprehension
 *     SectionA 选词填空：·概览 · / 逐段译文 / ·答案详解 ·  → 「26. D)complex …\n语法判断…\n语义判断…」
 *     SectionB 段落匹配：·概览 · / 中文文章（A) B) …）/ ·答案详解 · → 「36. 题干译文…\n答案解析 H。…」
 *     Section   仔细阅读：Passage One ·概览 · / ·全文翻译 · / ·答案详解 · → 「46. …? A)…C)… B)…D)… 解析」
 *   Part IV Translation
 *     ·难词译注 · / ·参考译文 · / 逐句讲解
 */
@Service
public class ExamAnalysisParser {

    /** 解析结果 */
    public record Analysis(String source, String quality, String warning,
                           Map<String, String> answers,
                           Map<String, String> explanations,
                           Map<String, String> stems,
                           Map<String, List<String>> options,
                           String listeningScript,
                           String writingAnalysis,
                           String writingModel,
                           String writingModelTranslation,
                           Map<String, String> overviews,
                           Map<String, List<String>> translations,
                           String translationWords,
                           String translationModel,
                           String translationNotes,
                           List<ListeningPart> listeningParts,
                           Map<String, String> distractors) {
    }

    /** 听力小节（Section A/B/C）：该节原文 + 属于该节的题号 */
    public record ListeningPart(String name, String script, List<Integer> numbers) {
    }

    private static final Pattern PART_HEAD = Pattern.compile("(?i)^\\s*part\\s+(?:iv|iii|ii|i|[1-4])\\b");
    /** 目录行（末尾带页码），需要跳过，避免把目录当成正文 */
    private static final Pattern TOC_LINE = Pattern.compile(".*[\\s.·]{1,}\\d{1,3}\\s*$");
    private static final Pattern SECTION_LINE = Pattern.compile("(?i)^\\s*section\\s*[〔(\\[（]?\\s*([a-c])?");
    private static final Pattern PASSAGE_LINE = Pattern.compile(
            "(?i)^\\s*passage\\s+(one|two|three|four|1|2|3|4)\\b");
    private static final Pattern GROUP_LINE = Pattern.compile(
            "(?i)^\\s*(news\\s+report|conversation|passage|talk|lecture)\\s+(one|two|three|four|1|2|3|4)\\b");
    private static final Pattern QOPT = Pattern.compile("([A-D])\\)");
    private static final Pattern BANK_ENTRY = Pattern.compile("^\\s*(\\d{1,3})\\s*[.、]\\s*([A-Za-z])\\s*\\)");
    private static final Pattern MATCH_ANSWER = Pattern.compile("^\\s*答案解析\\s*([A-Za-z])\\s*[。.、,，]");
    private static final Pattern STEM_TRANS = Pattern.compile("^\\s*(\\d{1,3})\\s*[.、]\\s*题干译文\\s*[：:]?\\s*(.*)$");
    private static final Pattern STEM_TRANS_BARE = Pattern.compile("^\\s*题干译文\\s*[：:]\\s*(.*)$");
    /** 「36.【答案】H」这种题号与答案写在同一行的写法 */
    private static final Pattern MATCH_TAGGED = Pattern.compile(
            "^\\s*(\\d{1,3})\\s*[.、]\\s*【\\s*答案\\s*】\\s*([A-Za-z])\\b");
    /** 「解析：…」这种解释行 */
    private static final Pattern EXP_PLAIN = Pattern.compile("^\\s*解析\\s*[：:]\\s*(.*)$");
    /** 新版解析用【答案】A / 【解析】… 直接给答案与解析 */
    private static final Pattern ANS_TAG = Pattern.compile("^\\s*【\\s*答案\\s*】\\s*([A-Za-z])\\b");
    private static final Pattern EXP_TAG = Pattern.compile("^\\s*【\\s*解析\\s*】\\s*(.*)$");
    private static final Pattern QNUM_LINE = Pattern.compile("^\\s*(\\d{1,3})\\s*[.、]\\s*(.*)$");
    private static final Pattern PAGE_FOOTER = Pattern.compile(
            "^\\s*[\\d\\s]*(四\\s*级|六级|四级|CET[-\\s]?[46])?[\\d\\s.第一二三四五六七八九十套]*$");

    /* 从解析文本里判断「正确答案」的短语，按优先级排（先去掉所有空白再匹配） */
    private static final Pattern[] ANSWER_PATTERNS = {
            Pattern.compile("(?:故选|应选|答案选|正确答案是?|正确选项为|答案是|答案为)([A-D])"),
            Pattern.compile("([A-D])项?为正确答案"),
            Pattern.compile("选项([A-D])[为是]正确"),
            Pattern.compile("([A-D])项?[^。]{0,40}为正确选"),
            Pattern.compile("([A-D])项?与原文[^。]{0,40}故为答案"),
            Pattern.compile("([A-D])为答案"),
            Pattern.compile("选项([A-D])")
    };

    private static final Pattern MATCH_ANSWER_ANY = Pattern.compile("答案解析\\s*([A-Za-z])\\s*[。.、,，]");
    /** 翻译逐句讲解的起始行，如「1.第一句：…」 */
    private static final Pattern NOTES_LINE = Pattern.compile("^\\s*\\d{1,2}\\s*[.、]\\s*第[一二三四五六七八九十]+句");
    /** 词库解析里题号后面残留的「complex (adj.复杂的)」 */
    private static final Pattern BANK_WORD_LEFTOVER = Pattern.compile(
            "^[A-Za-z][A-Za-z\\s'\\-]{0,26}\\s*\\((?:n|v|vt|vi|adj|adv)\\.?\\s*[^)]{0,40}\\)\\s*");

    /** 入口 */
    public Analysis parse(String text, String source) {
        try {
            if (text == null || text.isBlank()) {
                return empty(source, "解析正文为空。");
            }
            List<String> markedLines = clean(text);
            // 结构识别一律用「去掉强调标记」的文本；听力原文 / 译文再用回带标记的原文
            Map<String, String> byPlain = new LinkedHashMap<>();
            List<String> lines = new ArrayList<>(markedLines.size());
            for (String m : markedLines) {
                String p = plain(m);
                lines.add(p);
                byPlain.putIfAbsent(p, m);
            }

            // Part 标题可能和题型名不在同一行（如「Part III」下一行才是「Reading Comprehension」），
            // 所以在标题后面的若干行里找题型关键词来定性。
            int[] parts = findParts(lines);
            int wIdx = parts[0];
            int lIdx = parts[1];
            int rIdx = parts[2];
            int tIdx = parts[3];

            int[] marks = {wIdx, lIdx, rIdx, tIdx};

            // ---------- 写作 ----------
            Map<String, String> writing = new LinkedHashMap<>();
            if (wIdx >= 0) {
                splitWriting(slice(lines, wIdx + 1, firstAfter(marks, wIdx, lines.size())), writing);
            }

            // ---------- 听力 ----------
            Map<String, String> answers = new LinkedHashMap<>();
            Map<String, String> explanations = new LinkedHashMap<>();
            Map<String, String> stems = new LinkedHashMap<>();
            Map<String, List<String>> options = new LinkedHashMap<>();
            String script = "";
            List<ListeningPart> listeningParts = new ArrayList<>();
            if (lIdx >= 0) {
                List<String> listen = slice(lines, lIdx + 1, firstAfter(marks, lIdx, lines.size()));
                script = extractListeningScript(listen, byPlain);
                collectQuestionBlocks(listen, 1, 25, answers, explanations, stems, options);
                listeningParts = parseListeningParts(listen, byPlain);
            }

            // ---------- 阅读 ----------
            Map<String, String> overviews = new LinkedHashMap<>();
            Map<String, List<String>> translations = new LinkedHashMap<>();
            Map<String, String> distractors = new LinkedHashMap<>();
            if (rIdx >= 0) {
                parseReading(slice(lines, rIdx + 1, firstAfter(marks, rIdx, lines.size())),
                        answers, explanations, stems, options, overviews, translations, distractors, byPlain);
            }

            // ---------- 翻译 ----------
            Map<String, String> trans = new LinkedHashMap<>();
            if (tIdx >= 0) {
                splitTranslation(slice(lines, tIdx + 1, lines.size()), trans);
            }

            int answerCount = answers.size();
            String quality;
            String warning;
            if (answerCount == 0) {
                quality = "none";
                warning = "没有从解析文件里识别出标准答案（可能是扫描版 PDF，或排版差异较大）。";
            } else if (answerCount < 40) {
                quality = "partial";
                warning = "只识别出 " + answerCount + " 个标准答案，可能有遗漏。";
            } else {
                quality = "ok";
                warning = "";
            }

            return new Analysis(source, quality, warning,
                    answers, explanations, stems, options,
                    script,
                    writing.getOrDefault("analysis", ""),
                    writing.getOrDefault("model", ""),
                    writing.getOrDefault("modelTranslation", ""),
                    overviews, translations,
                    trans.getOrDefault("words", ""),
                    trans.getOrDefault("model", ""),
                    trans.getOrDefault("notes", ""),
                    listeningParts, distractors);
        } catch (Exception e) {
            return empty(source, "解析文件解析失败：" + e.getMessage());
        }
    }

    private Analysis empty(String source, String warning) {
        return new Analysis(source, "none", warning,
                Map.of(), Map.of(), Map.of(), Map.of(),
                "", "", "", "", Map.of(), Map.of(), "", "", "", List.of(), Map.of());
    }

    /** 找出 Writing / Listening / Reading / Translation 四个 Part 的起始行（跳过目录行） */
    private int[] findParts(List<String> lines) {
        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (PART_HEAD.matcher(line).find() && !TOC_LINE.matcher(line).matches()) {
                heads.add(i);
            }
        }
        int w = -1;
        int l = -1;
        int r = -1;
        int t = -1;
        // 目录里也会有「Part N 题型」的行（通常在正文之前），所以取最后一次出现 = 正文开头
        for (int k = 0; k < heads.size(); k++) {
            int start = heads.get(k);
            int end = (k + 1 < heads.size()) ? heads.get(k + 1) : lines.size();
            String kind = classify(lines, start, end);
            if (kind == null) {
                continue;
            }
            switch (kind) {
                case "writing" -> w = start;
                case "listening" -> l = start;
                case "reading" -> r = start;
                case "translation" -> t = start;
                default -> { }
            }
        }
        return new int[]{w, l, r, t};
    }

    /** 在 Part 标题之后的少量行里，按最先出现的关键词判断这是哪一部分 */
    private String classify(List<String> lines, int start, int end) {
        int limit = Math.min(end, start + 15);
        for (int i = start; i < limit; i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String low = line.toLowerCase(Locale.ROOT);
            if (low.contains("writing")) {
                return "writing";
            }
            if (low.contains("listening")) {
                return "listening";
            }
            if (low.contains("reading")) {
                return "reading";
            }
            if (low.contains("translation")) {
                return "translation";
            }
        }
        return null;
    }

    /* ==================== 写作 ==================== */
    private void splitWriting(List<String> lines, Map<String, String> out) {
        String cur = null;
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String marker = markerOf(line);
            if (marker != null && marker.startsWith("writing.")) {
                flush(out, cur, sb);
                cur = marker.substring("writing.".length());
                continue;
            }
            if (marker != null) {
                flush(out, cur, sb);
                cur = null;
                continue;
            }
            if (cur != null && !isNoise(line)) {
                appendSmart(sb, line);
            }
        }
        flush(out, cur, sb);
    }

    /* ==================== 听力原文 ==================== */
    /**
     * 按 Section A/B/C 拆分听力：新版解析里没有「·听力原文 ·」标记，
     * 结构是「Section X → News Report/Conversation/Passage One → 原文 → 题目+【答案】+【解析】」。
     * 每节返回自己的原文与题号，供回顾页做 Section 切换。
     */
    private List<ListeningPart> parseListeningParts(List<String> lines, Map<String, String> byPlain) {
        List<ListeningPart> out = new ArrayList<>();
        String secName = null;
        StringBuilder script = new StringBuilder();
        StringBuilder groupBody = new StringBuilder();
        String group = null;
        List<Integer> numbers = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher sec = SECTION_LINE.matcher(line);
            if (sec.find() && line.length() <= 30) {
                // 收尾上一节
                appendGroup(script, group, groupBody);
                group = null;
                if (secName != null) {
                    out.add(new ListeningPart(secName, script.toString().trim(), new ArrayList<>(numbers)));
                }
                script = new StringBuilder();
                numbers = new ArrayList<>();
                secName = shortSectionName(line);
                continue;
            }
            if (secName == null) {
                continue;
            }
            Matcher gl = GROUP_LINE.matcher(line);
            if (gl.find()) {
                appendGroup(script, group, groupBody);
                group = line;
                groupBody = new StringBuilder();
                continue;
            }
            if (markerOf(line) != null || isNoise(line)) {
                continue;
            }
            Matcher qn = QNUM_LINE.matcher(line);
            if (qn.matches()) {
                // 题目开始：原文到此为止，后面的内容交给题目解析
                appendGroup(script, group, groupBody);
                group = null;
                groupBody = new StringBuilder();
                int n = Integer.parseInt(qn.group(1));
                if (!numbers.contains(n)) {
                    numbers.add(n);
                }
                continue;
            }
            if (group != null) {
                appendScriptLine(groupBody, marked(byPlain, line));
            }
        }
        appendGroup(script, group, groupBody);
        if (secName != null) {
            out.add(new ListeningPart(secName, script.toString().trim(), new ArrayList<>(numbers)));
        }
        out.removeIf(p -> p.script().isBlank() && p.numbers().isEmpty());
        return out;
    }

    /** 「Section A 新闻听力（News Reports）」-> 「Section A」 */
    private String shortSectionName(String line) {
        Matcher m = Pattern.compile("(?i)section\\s*[〔(\\[（]?\\s*([a-c])").matcher(line);
        return m.find() ? "Section " + m.group(1).toUpperCase(Locale.ROOT) : line.trim();
    }

    private void appendGroup(StringBuilder out, String group, StringBuilder body) {
        if (body.length() == 0) {
            return;
        }
        if (out.length() > 0) {
            out.append("\n\n");
        }
        if (group != null && !group.isBlank()) {
            out.append('【').append(group.trim()).append("】\n");
        }
        out.append(body.toString().trim());
        body.setLength(0);
    }

    /** 对话里的说话人开头（M: / W: / Man: / Woman: / Speaker 1:） */
    private static final Pattern SPEAKER =
            Pattern.compile("^\\s*(?:[MWmw]|Man|Woman|Speaker\\s*\\d{1,2})\\s*[:：]");

    /**
     * 拼接听力原文：换说话人就另起一行（对话类原文一行一句交替），
     * 其余的续行仍用空格接上（原文里有被折行的长句）。
     */
    private void appendScriptLine(StringBuilder sb, String line) {
        String t = line.trim();
        if (t.isEmpty()) {
            return;
        }
        if (sb.length() == 0) {
            sb.append(t);
            return;
        }
        if (SPEAKER.matcher(t).find()) {
            sb.append('\n').append(t);
        } else {
            sb.append(' ').append(t);
        }
    }

    /** 取回带强调标记的原始行（没有则原样返回） */
    private String marked(Map<String, String> byPlain, String line) {
        if (byPlain == null) {
            return line;
        }
        String m = byPlain.get(line);
        return m == null ? line : m;
    }

    private String extractListeningScript(List<String> lines, Map<String, String> byPlain) {
        StringBuilder script = new StringBuilder();
        String group = null;
        boolean inScript = false;
        StringBuilder body = new StringBuilder();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher g = GROUP_LINE.matcher(line);
            if (g.find()) {
                flushScript(script, group, body);
                group = line;
                inScript = false;
                continue;
            }
            String marker = markerOf(line);
            if (marker != null) {
                if ("listening.script".equals(marker)) {
                    flushScript(script, group, body);
                    inScript = true;
                } else {
                    flushScript(script, group, body);
                    inScript = false;
                }
                continue;
            }
            if (inScript && !isNoise(line)) {
                appendScriptLine(body, marked(byPlain, line));
            }
        }
        flushScript(script, group, body);
        return script.toString().trim();
    }

    private void flushScript(StringBuilder out, String group, StringBuilder body) {
        if (body.length() == 0) {
            return;
        }
        if (out.length() > 0) {
            out.append("\n\n");
        }
        if (group != null && !group.isBlank()) {
            out.append('【').append(group.trim()).append("】\n");
        }
        out.append(body.toString().trim());
        body.setLength(0);
    }

    /* ==================== 阅读 ==================== */
    private void parseReading(List<String> lines,
                              Map<String, String> answers,
                              Map<String, String> explanations,
                              Map<String, String> stems,
                              Map<String, List<String>> options,
                              Map<String, String> overviews,
                              Map<String, List<String>> translations,
                              Map<String, String> distractors,
                              Map<String, String> byPlain) {
        String unit = null;
        int secIndex = 0;
        String mode = null;                 // overview | translation | answers
        List<String> buf = new ArrayList<>();
        List<String> answerLines = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();

            Matcher sec = SECTION_LINE.matcher(line);
            boolean isSection = sec.find() && line.length() <= 30;
            Matcher pass = PASSAGE_LINE.matcher(line);
            boolean isPassage = pass.find() && line.length() <= 30;

            if (isSection || isPassage) {
                flushReadingUnit(unit, mode, buf, answerLines, answers, explanations, stems, options,
                        overviews, translations, distractors);
                buf = new ArrayList<>();
                answerLines = new ArrayList<>();
                mode = null;
                if (isPassage) {
                    unit = normalizePassage(pass.group(1));
                } else {
                    unit = "Section " + (char) ('A' + Math.min(secIndex, 2));
                    secIndex++;
                }
                continue;
            }

            String marker = markerOf(line);
            if (marker != null) {
                flushReadingMode(unit, mode, buf, overviews, translations);
                mode = switch (marker) {
                    case "overview" -> "overview";
                    case "translation" -> "translation";
                    case "answers", "explain" -> "answers";
                    default -> null;
                };
                continue;
            }

            if (line.isEmpty() || isNoise(line)) {
                continue;
            }

            if ("answers".equals(mode)) {
                answerLines.add(line);
            } else if (("overview".equals(mode) || "translation".equals(mode))
                    && !buf.contains(marked(byPlain, line))) {
                // 解析 Word 里一段占一行，这里保持「一行 = 一段」；译文保留下划线/高亮标记
                buf.add(marked(byPlain, line));
            } else if (mode == null && unit != null) {
                // Section A 的逐段译文直接跟在「·概览 ·」之后（没有单独的全文翻译标记）
                buf.add(marked(byPlain, line));
                mode = "overview";
            }
        }
        flushReadingUnit(unit, mode, buf, answerLines, answers, explanations, stems, options,
                overviews, translations, distractors);
    }

    private void flushReadingUnit(String unit, String mode, List<String> buf, List<String> answerLines,
                                  Map<String, String> answers,
                                  Map<String, String> explanations,
                                  Map<String, String> stems,
                                  Map<String, List<String>> options,
                                  Map<String, String> overviews,
                                  Map<String, List<String>> translations,
                                  Map<String, String> distractors) {
        flushReadingMode(unit, mode, buf, overviews, translations);
        if (unit == null || answerLines.isEmpty()) {
            return;
        }
        String kind = unit.startsWith("Section A") ? "banked"
                : unit.startsWith("Section B") ? "matching" : "careful";
        collectUnitAnswers(kind, answerLines, answers, explanations, stems, options, unit, distractors);
    }

    /**
     * 概览与译文都收在同一个缓冲里：第一行是「概览」，其余行是逐段译文。
     * （Section C 有单独的「·全文翻译 ·」标记，Section A/B 没有，直接跟在概览后面）
     */
    private void flushReadingMode(String unit, String mode, List<String> buf,
                                  Map<String, String> overviews, Map<String, List<String>> translations) {
        List<String> paras = new ArrayList<>(buf);
        buf.clear();
        if (unit == null || mode == null || paras.isEmpty()) {
            return;
        }
        if ("translation".equals(mode)) {
            translations.put(unit, mergeTranslations(translations.get(unit), paras));
            return;
        }
        if (!"overview".equals(mode)) {
            return;
        }
        overviews.put(unit, paras.get(0));
        if (paras.size() > 1) {
            translations.put(unit,
                    mergeTranslations(translations.get(unit), new ArrayList<>(paras.subList(1, paras.size()))));
        }
    }

    private List<String> mergeTranslations(List<String> prev, List<String> next) {
        if (prev == null || prev.isEmpty()) {
            return next;
        }
        List<String> out = new ArrayList<>(prev);
        out.addAll(next);
        return out;
    }

    /** 一小节/一篇文章的答案详解 */
    private void collectUnitAnswers(String kind, List<String> lines,
                                    Map<String, String> answers,
                                    Map<String, String> explanations,
                                    Map<String, String> stems,
                                    Map<String, List<String>> options,
                                    String unit,
                                    Map<String, String> distractors) {
        if ("banked".equals(kind)) {
            // 26. D)complex (adj.复杂的) + 语法判断/语义判断 …
            String curNo = null;
            StringBuilder exp = new StringBuilder();
            StringBuilder dis = new StringBuilder();
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                // 「干扰项说明」单开一栏，不能算进最后一题的解析
                if (line.matches("^干扰项(说明|解析)?\\s*[：:]?.*$")) {
                    flushBank(answers, explanations, curNo, exp);
                    curNo = null;
                    String rest = line.replaceFirst("^干扰项(说明|解析)?\\s*[：:]?\\s*", "").trim();
                    if (!rest.isEmpty()) {
                        appendSmart(dis, rest);
                    }
                    continue;
                }
                if (dis.length() > 0) {
                    appendSmart(dis, line);
                    continue;
                }
                Matcher m = BANK_ENTRY.matcher(line);
                if (m.find()) {
                    flushBank(answers, explanations, curNo, exp);
                    curNo = m.group(1);
                    answers.put(curNo, m.group(2).toUpperCase(Locale.ROOT));
                    exp = new StringBuilder();
                    String rest = BANK_WORD_LEFTOVER.matcher(line.substring(m.end()).trim())
                            .replaceFirst("").trim();
                    if (!rest.isEmpty()) {
                        appendSmart(exp, rest);
                    }
                    continue;
                }
                if (curNo != null) {
                    appendSmart(exp, line);
                }
            }
            flushBank(answers, explanations, curNo, exp);
            if (dis.length() > 0 && unit != null) {
                distractors.put(unit, plain(dis.toString().trim()));
            }
            return;
        }

        if ("matching".equals(kind)) {
            // 旧版：「36. 题干译文 … / 答案解析 H。…」
            // 新版：「题干译文：… / 【答案】H / 【解析】…」（题号靠顺序推）
            String curNo = null;
            int auto = 36;
            StringBuilder exp = new StringBuilder();
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                // 1) 「36.【答案】H」：题号与答案同行
                Matcher tagged = MATCH_TAGGED.matcher(line);
                if (tagged.find()) {
                    flushMatch(answers, explanations, stems, curNo, exp);
                    curNo = tagged.group(1);
                    auto = Math.max(auto, Integer.parseInt(curNo) + 1);
                    answers.put(curNo, tagged.group(2).toUpperCase(Locale.ROOT));
                    exp = new StringBuilder();
                    String rest = line.substring(tagged.end()).trim();
                    if (!rest.isEmpty()) {
                        appendSmart(exp, rest);
                    }
                    continue;
                }
                // 2) 「36. 题干译文 …」
                Matcher st = STEM_TRANS.matcher(line);
                if (st.matches()) {
                    flushMatch(answers, explanations, stems, curNo, exp);
                    curNo = st.group(1);
                    auto = Math.max(auto, Integer.parseInt(curNo) + 1);
                    String rest = st.group(2).trim();
                    exp = new StringBuilder();
                    Matcher inline = MATCH_ANSWER_ANY.matcher(rest);
                    if (inline.find()) {
                        answers.put(curNo, inline.group(1).toUpperCase(Locale.ROOT));
                        stems.put(curNo, rest.substring(0, inline.start()).trim());
                        String tail = rest.substring(inline.end()).trim();
                        if (!tail.isEmpty()) {
                            appendSmart(exp, tail);
                        }
                    } else {
                        stems.put(curNo, rest);
                    }
                    continue;
                }
                // 3) 「题干译文：…」（题号由顺序推）
                Matcher bare = STEM_TRANS_BARE.matcher(line);
                if (bare.matches()) {
                    if (curNo == null) {
                        curNo = String.valueOf(auto++);
                    }
                    stems.putIfAbsent(curNo, bare.group(1).trim());
                    continue;
                }
                // 4) 【答案】X
                Matcher ansTag = ANS_TAG.matcher(line);
                if (ansTag.find() && curNo != null) {
                    answers.put(curNo, ansTag.group(1).toUpperCase(Locale.ROOT));
                    continue;
                }
                // 5) 【解析】… / 解析：…
                Matcher expTag = EXP_TAG.matcher(line);
                if (expTag.find() && curNo != null) {
                    if (!expTag.group(1).isBlank()) {
                        appendSmart(exp, expTag.group(1).trim());
                    }
                    continue;
                }
                Matcher expPlain = EXP_PLAIN.matcher(line);
                if (expPlain.matches() && curNo != null) {
                    if (!expPlain.group(1).isBlank()) {
                        appendSmart(exp, expPlain.group(1).trim());
                    }
                    continue;
                }
                // 6) 旧版「答案解析 H。…」
                Matcher ans = MATCH_ANSWER.matcher(line);
                if (ans.find() && curNo != null) {
                    answers.put(curNo, ans.group(1).toUpperCase(Locale.ROOT));
                    String rest = line.substring(ans.end()).trim();
                    if (!rest.isEmpty()) {
                        appendSmart(exp, rest);
                    }
                    continue;
                }
                // 7) 普通题号开头
                Matcher qn = QNUM_LINE.matcher(line);
                if (qn.matches() && !stems.containsKey(qn.group(1))) {
                    flushMatch(answers, explanations, stems, curNo, exp);
                    curNo = qn.group(1);
                    auto = Math.max(auto, Integer.parseInt(curNo) + 1);
                    stems.put(curNo, qn.group(2).trim());
                    exp = new StringBuilder();
                    continue;
                }
                if (curNo != null && !isNoise(line) && !isHeading(line)) {
                    appendSmart(exp, line);
                }
            }
            flushMatch(answers, explanations, stems, curNo, exp);
            return;
        }

        // 仔细阅读：题干 + 选项 + 解析
        collectQuestionBlocks(lines, 1, 100, answers, explanations, stems, options);
    }

    private void flushBank(Map<String, String> answers, Map<String, String> explanations,
                           String no, StringBuilder exp) {
        if (no == null) {
            return;
        }
        String t = exp.toString().trim();
        if (!t.isEmpty()) {
            explanations.put(no, t);
        }
    }

    private void flushMatch(Map<String, String> answers, Map<String, String> explanations,
                            Map<String, String> stems, String no, StringBuilder exp) {
        if (no == null) {
            return;
        }
        String t = exp.toString().trim();
        if (!t.isEmpty()) {
            explanations.put(no, t);
        }
    }

    /* ==================== 题块（听力 / 仔细阅读） ==================== */
    /**
     * 扫描「N. 题干 / A) … C) … / B) … D) … / 解析文本」这样的题块，
     * 抽出题干、选项、解析，并从解析文本里判断正确答案。
     */
    private void collectQuestionBlocks(List<String> lines, int from, int to,
                                       Map<String, String> answers,
                                       Map<String, String> explanations,
                                       Map<String, String> stems,
                                       Map<String, List<String>> options) {
        String curNo = null;
        List<String> cur = new ArrayList<>();
        int last = from - 1;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = QNUM_LINE.matcher(line);
            if (m.matches()) {
                int no = Integer.parseInt(m.group(1));
                if (no >= from && no <= to && (last < from || no == last + 1 || no > last)) {
                    flushQuestion(curNo, cur, answers, explanations, stems, options);
                    cur = new ArrayList<>();
                    curNo = m.group(1);
                    last = no;
                    cur.add(line);
                    continue;
                }
            }
            // 遇到下一篇听力原文（分组标题 / 小节标题）就结束当前题，原文不能再混进解析
            if (isScriptStart(line)) {
                flushQuestion(curNo, cur, answers, explanations, stems, options);
                curNo = null;
                cur = new ArrayList<>();
                continue;
            }
            if (curNo != null) {
                cur.add(line);
            }
        }
        flushQuestion(curNo, cur, answers, explanations, stems, options);
    }

    private void flushQuestion(String no, List<String> block,
                               Map<String, String> answers,
                               Map<String, String> explanations,
                               Map<String, String> stems,
                               Map<String, List<String>> options) {
        if (no == null || block.isEmpty()) {
            return;
        }
        String first = block.get(0);
        String stem = plain(first.replaceFirst("^\\s*\\d{1,3}\\s*[.、]\\s*", "").trim());

        List<String> opts = new ArrayList<>();
        StringBuilder exp = new StringBuilder();
        boolean inExplain = false;
        for (int i = 1; i < block.size(); i++) {
            String line = block.get(i);
            String flat = line.replaceAll("\\s+", "");
            // 新版解析：显式【答案】/【解析】标记
            Matcher ansTag = ANS_TAG.matcher(line);
            if (ansTag.find()) {
                answers.put(no, ansTag.group(1).toUpperCase(Locale.ROOT));
                continue;
            }
            Matcher expTag = EXP_TAG.matcher(line);
            if (expTag.find()) {
                inExplain = true;
                if (!expTag.group(1).isBlank()) {
                    appendSmart(exp, expTag.group(1).trim());
                }
                continue;
            }
            if (flat.equals("解析") || flat.equals("答案详解") || flat.startsWith("解析→")
                    || flat.startsWith("答案解析")) {
                inExplain = true;
                String rest = line.replaceFirst("^\\s*(解析|答案详解|答案解析)\\s*", "").trim();
                if (!rest.isEmpty()) {
                    appendSmart(exp, rest);
                }
                continue;
            }
            if (!inExplain && hasOptionLabel(line)) {
                for (String part : splitOptions(line)) {
                    if (!part.isBlank()) {
                        opts.add(part.trim());
                    }
                }
                continue;
            }
            inExplain = true;
            if (!isNoise(line)) {
                appendSmart(exp, line);
            }
        }

        String expText = plain(exp.toString().trim());
        if (!stem.isEmpty()) {
            stems.putIfAbsent(no, stem);
        }
        if (!opts.isEmpty()) {
            options.putIfAbsent(no, opts);
        }
        if (!expText.isEmpty()) {
            explanations.put(no, expText);
        }
        String a = pickAnswer(expText);
        if (a == null) {
            a = pickAnswer(stem);
        }
        if (a != null) {
            answers.put(no, a);
        }
    }

    /** 从解析文本里挑出正确答案（先把空白去掉，解析里常出现「答 案 是C 项」这种字间空格） */
    private String pickAnswer(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replaceAll("\\s+", "");
        for (Pattern p : ANSWER_PATTERNS) {
            Matcher m = p.matcher(flat);
            if (m.find()) {
                return m.group(1).toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private boolean hasOptionLabel(String line) {
        return QOPT.matcher(line).find();
    }

    /** 一行里可能并排两个选项（A) … C) …），按标签切开 */
    private List<String> splitOptions(String line) {
        List<String> out = new ArrayList<>();
        Matcher m = QOPT.matcher(line);
        List<int[]> spans = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        while (m.find()) {
            int i = m.start();
            if (i > 0 && !Character.isWhitespace(line.charAt(i - 1))) {
                continue;
            }
            spans.add(new int[]{m.start(), m.end()});
            labels.add(m.group(1));
        }
        for (int k = 0; k < spans.size(); k++) {
            int ts = spans.get(k)[1];
            int te = (k + 1 < spans.size()) ? spans.get(k + 1)[0] : line.length();
            if (te < ts) {
                te = ts;
            }
            out.add(labels.get(k) + ") " + line.substring(ts, te).trim());
        }
        return out;
    }

    /* ==================== 翻译 ==================== */
    private void splitTranslation(List<String> lines, Map<String, String> out) {
        String cur = null;
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String marker = markerOf(line);
            if (marker != null) {
                flush(out, cur, sb);
                cur = switch (marker) {
                    case "trans.words" -> "words";
                    case "trans.model" -> "model";
                    case "trans.notes" -> "notes";
                    default -> null;
                };
                continue;
            }
            // 逐句讲解以「1.第一句：…」开头，从这里开始归到 notes
            if (NOTES_LINE.matcher(line).find()) {
                flush(out, cur, sb);
                cur = "notes";
            }
            if (cur != null && !isNoise(line)) {
                // 难词注释排版为一行一个
                if ("words".equals(cur)) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(plain(line));
                } else {
                    appendSmart(sb, line);
                }
            }
        }
        flush(out, cur, sb);
    }

    private void flush(Map<String, String> out, String key, StringBuilder sb) {
        if (key != null && sb.length() > 0) {
            String prev = out.get(key);
            out.put(key, prev == null ? sb.toString().trim() : prev + "\n" + sb.toString().trim());
        }
        sb.setLength(0);
    }

    /* ==================== 通用 ==================== */
    private String markerOf(String line) {
        String core = line.replaceAll("^[·●•\\-\\s]+", "").replaceAll("[·\\s]+$", "").trim();
        if (core.isEmpty() || core.length() > 26) {
            return null;
        }
        String flat = core.replaceAll("\\s+", "");
        if (flat.contains("审题")) {
            return "writing.analysis";
        }
        if (flat.contains("参考范文") || flat.contains("范文&点评") || flat.contains("范文点评")) {
            return "writing.model";
        }
        if (flat.contains("范文译文")) {
            return "writing.modelTranslation";
        }
        if (flat.contains("听力原文")) {
            return "listening.script";
        }
        if (flat.contains("答案详解") || flat.contains("答案与解析") || flat.contains("答案及解析")
                || flat.contains("题目解析") || flat.contains("题目详解")) {
            return "answers";
        }
        if (flat.equals("解析") || flat.equals("解→") || flat.equals("答案解析")) {
            return "explain";
        }
        if (flat.contains("译点解析") || flat.contains("译点详解") || flat.contains("逐句讲解")
                || flat.contains("句型解析")) {
            return "trans.notes";
        }
        if (flat.contains("概览")) {
            return "overview";
        }
        if (flat.contains("全文翻译")) {
            return "translation";
        }
        if (flat.contains("难词译注")) {
            return "trans.words";
        }
        if (flat.contains("参考译文")) {
            return "trans.model";
        }
        return null;
    }

    /** 小节标题行（Section X / Passage One / 答案详解 等），不应混进解析正文 */
    private boolean isHeading(String line) {
        String s = line.trim();
        if (s.length() > 34) {
            return false;
        }
        return SECTION_LINE.matcher(s).find() || PASSAGE_LINE.matcher(s).find()
                || s.matches("^(答案详解|答案与解析|题目解析|干扰项说明|题干译文|全文翻译|难词译注|参考译文|译点解析|题目翻译).*$");
    }

    /** 是否是「新一段原文」的开始（听力分组标题 / 小节标题）——解析遇到它就该停止吸收 */
    private boolean isScriptStart(String line) {
        return GROUP_LINE.matcher(line.trim()).find() || isHeading(line);
    }

    /** 去掉强调标记（题干、选项、解析里不需要下划线/高亮） */
    private String plain(String s) {
        return DocumentTextExtractor.stripEmphasis(s);
    }

    /** 词汇注释 / 页码之类的噪声行 */
    private boolean isNoise(String line) {        String s = line.trim();
        if (s.isEmpty()) {
            return true;
        }
        if (s.startsWith("·") || s.startsWith("●") || s.startsWith("@") || s.startsWith("……")) {
            return true;
        }
        if (s.matches("^[.…\\s]+$")) {
            return true;
        }
        // 页码：四级2025.6 第一套 11 / 16 四级2025.6 第一套
        if (s.matches("^.*(四级|六级|第一套|第二套|第三套).{0,12}\\d{1,3}$") && s.length() <= 24) {
            return true;
        }
        if (s.matches("^\\d{1,3}\\s*(四级|六级).*$") && s.length() <= 24) {
            return true;
        }
        // 双栏排版留下的「定位解析」碎片
        String flat = s.replaceAll("\\s+", "");
        if (flat.length() <= 8 && (flat.equals("定位解析") || flat.equals("解析") || flat.equals("听力原文")
                || flat.equals("答案详解") || flat.equals("概览"))) {
            return true;
        }
        // 词汇注释：word n. 释义 / word v. …
        if (s.matches("^[A-Za-z][A-Za-z\\s'\\-]{0,24}\\s+(n|v|vt|vi|adj|adv|prep|conj|pron)\\.?\\s.*$")
                && s.length() <= 80) {
            return true;
        }
        return false;
    }

    private List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            String p = para.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private void appendSmart(StringBuilder sb, String next) {
        if (sb.length() == 0) {
            sb.append(next);
            return;
        }
        char a = sb.charAt(sb.length() - 1);
        char b = next.charAt(0);
        if (isCjk(a) && isCjk(b)) {
            sb.append(next);
        } else {
            sb.append(' ').append(next);
        }
    }

    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF);
    }

    private List<String> clean(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.replace('\t', ' ').replace("\r", "").split("\n")) {
            String line = raw.replaceAll("[ ]{2,}", " ").trim();
            line = line.replace("Ⅳ", "IV").replace("Ⅲ", "III").replace("Ⅱ", "II").replace("Ⅰ", "I");
            out.add(line);
        }
        return out;
    }

    private List<String> slice(List<String> lines, int from, int to) {
        if (from < 0 || from >= lines.size()) {
            return List.of();
        }
        int end = Math.max(from, Math.min(to, lines.size()));
        return lines.subList(from, end);
    }

    private int firstAfter(int[] marks, int from, int fallback) {
        int best = fallback;
        for (int m : marks) {
            if (m > from && m < best) {
                best = m;
            }
        }
        return best;
    }

    private String normalizePassage(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "one", "1" -> "Passage One";
            case "two", "2" -> "Passage Two";
            case "three", "3" -> "Passage Three";
            case "four", "4" -> "Passage Four";
            default -> "Passage";
        };
    }
}
