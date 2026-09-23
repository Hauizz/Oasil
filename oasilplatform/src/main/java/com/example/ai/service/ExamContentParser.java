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
 * 四六级试卷正文解析：把 Word / PDF 提取出来的整卷文本，切成结构化的「写作 / 听力 / 阅读 / 翻译」。
 *
 * 四六级试卷的标准排版（Word 版正文里非常规整）：
 *   Part I   Writing (30 minutes)          Directions: ...
 *   Part II  Listening Comprehension       Section A / B / C，每节 Directions + 若干题（只有题号 + A)-D) 选项）
 *   Part III Reading Comprehension         Section A 选词填空（26-35，词库 A)-O)）
 *                                          Section B 段落匹配（36-45，段落 A)-O)，题干无选项）
 *                                          Section C 仔细阅读（Passage One/Two，46-55，选项 A)-D)）
 *   Part IV  Translation                    Directions: ... + 中文段落
 *
 * 解析策略：
 *   - 先按 Part I/II/III/IV 切大块；
 *   - 再按 Section A/B/C 切小节；
 *   - 题号用「Questions X to Y are based on…」声明的区间 + 递增校验来识别，避免把正文里的数字当题号；
 *   - 选项支持一行多个（如「A) …    C) …」这种双栏排版）。
 */
@Service
public class ExamContentParser {

    public record Option(String label, String text) {
    }

    /** 一道题：number 题号；stem 题干（听力题没有题干，为空）；options 选项；group「Questions X and Y…」的分组说明 */
    public record Question(int number, String stem, List<Option> options, String group) {
    }

    /** 阅读里的一个「文章 + 对应题目」单元（选词填空 1 篇、段落匹配 1 篇、仔细阅读每篇各 1 个） */
    public record ReadingBlock(String title, String text, List<String> wordBank, List<Question> questions) {
    }

    /** 阅读小节：Section A / B / C */
    public record ReadingSection(String name, String kind, String directions, List<ReadingBlock> blocks) {
    }

    /** 听力小节：Section A / B / C */
    public record ListeningSection(String name, String directions, List<Question> questions) {
    }

    /** 整卷解析结果 */
    public record Content(String source, String quality, String warning, String notice,
                          String writingDirections,
                          List<ListeningSection> listening,
                          List<ReadingSection> reading,
                          String translationDirections,
                          String translationText,
                          List<String> skipped) {
    }

    /* ==================== 正则 ==================== */
    private static final Pattern PART = Pattern.compile("(?i)^\\s*part\\s+(?:iv|iii|ii|i|[1-4])\\b");
    private static final Pattern SECTION = Pattern.compile("(?i)^\\s*section\\s+([abc])\\b");
    private static final Pattern PASSAGE = Pattern.compile("(?i)^\\s*passage\\s+(one|two|three|four|1|2|3|4)\\b");
    private static final Pattern QGROUP = Pattern.compile("(?i)^\\s*questions\\s+(\\d{1,3})\\s+(?:and|to)\\s+(\\d{1,3})\\b");
    private static final Pattern QNUM = Pattern.compile("^\\s*(\\d{1,3})\\s*[.)]\\s*(.*)$");
    private static final Pattern OPT = Pattern.compile("([A-Z])\\)");
    private static final Pattern PARA_LABEL = Pattern.compile("^\\s*([A-Z])\\)\\s*(.*)$");
    private static final Pattern DIRECTIONS = Pattern.compile("(?i)^\\s*directions\\s*[:：]?\\s*");

    /** 入口：解析整卷文本 */
    public Content parse(String text, String source) {
        try {
            if (text == null || text.isBlank()) {
                return empty(source, "试卷正文为空，无法识别题目。");
            }
            List<String> lines = cleanLines(text);

            // 按「Part」小节标题（Writing / Listening / Reading / Translation）定位分界，
            // 不依赖罗马数字——部分真题的罗马数字标错（如 Reading 也写成 Part II）。
            int wIdx = -1, lIdx = -1, rIdx = -1, tIdx = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!PART.matcher(line).find()) {
                    continue;
                }
                String low = line.toLowerCase(Locale.ROOT);
                if (wIdx < 0 && low.contains("writing")) {
                    wIdx = i;
                } else if (lIdx < 0 && low.contains("listening")) {
                    lIdx = i;
                } else if (rIdx < 0 && low.contains("reading")) {
                    rIdx = i;
                } else if (tIdx < 0 && low.contains("translation")) {
                    tIdx = i;
                }
            }

            int[] marks = {wIdx, lIdx, rIdx, tIdx};
            String writing = wIdx >= 0 ? join(slice(lines, wIdx + 1, firstAfter(marks, wIdx, lines.size()))) : "";
            List<String> listeningLines = lIdx >= 0
                    ? withSectionMark(slice(lines, lIdx + 1, firstAfter(marks, lIdx, lines.size())), lines.get(lIdx))
                    : List.of();
            List<String> readingLines = rIdx >= 0
                    ? withSectionMark(slice(lines, rIdx + 1, firstAfter(marks, rIdx, lines.size())), lines.get(rIdx))
                    : List.of();
            List<String> translationLines = tIdx >= 0 ? slice(lines, tIdx + 1, lines.size()) : List.of();

            String writingDirections = stripDirections(writing);

            List<ListeningSection> listening = parseListening(listeningLines);
            List<ReadingSection> reading = parseReading(readingLines);

            String translationDirections = "";
            String translationText = "";
            if (!translationLines.isEmpty()) {
                StringBuilder dir = new StringBuilder();
                StringBuilder body = new StringBuilder();
                boolean inBody = false;
                for (String raw : translationLines) {
                    String line = raw.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (!inBody && hasCjk(line)) {
                        inBody = true;
                    }
                    if (inBody) {
                        appendSmart(body, line);
                    } else {
                        if (dir.length() > 0) {
                            dir.append(' ');
                        }
                        dir.append(DIRECTIONS.matcher(line).replaceFirst(""));
                    }
                }
                translationDirections = dir.toString().trim();
                translationText = body.toString().trim();
            }

            int listenQ = listening.stream().mapToInt(s -> s.questions().size()).sum();
            int readQ = reading.stream().flatMap(s -> s.blocks().stream())
                    .mapToInt(b -> b.questions().size()).sum();
            boolean hasWriting = !writingDirections.isBlank();
            boolean hasTranslation = !translationText.isBlank();

            // 试卷里的「温馨提示 / 说明」往往写着某部分与其它套重复、不再重复给出
            String notice = extractNotice(lines);

            // 哪些部分是「本套没有给出的」——前端据此整段跳过（例如听力与第1套重复就直接不进听力环节）
            List<String> skipped = new ArrayList<>();
            if (!hasWriting) {
                skipped.add("writing");
            }
            if (listening.isEmpty()) {
                skipped.add("listening");
            }
            if (reading.isEmpty()) {
                skipped.add("reading");
            }
            if (!hasTranslation) {
                skipped.add("translation");
            }

            String quality;
            String warning;
            if (listenQ == 0 && readQ == 0 && !hasWriting && !hasTranslation) {
                quality = "none";
                warning = "没有从试卷里识别出题目（可能是扫描版 PDF，或排版与标准四六级试卷差异较大）。";
            } else if (listenQ == 0 || reading.isEmpty() || readQ == 0 || skipped.size() > 0) {
                quality = "partial";
                warning = skipped.isEmpty() ? ""
                        : "本套试卷的 Word 版没有包含「" + skipNames(skipped) + "」，已自动跳过这些部分。";
            } else {
                quality = "ok";
                warning = "";
            }

            return new Content(source, quality, warning, notice, writingDirections,
                    listening, reading, translationDirections, translationText, skipped);
        } catch (Exception e) {
            return empty(source, "试卷解析失败：" + e.getMessage());
        }
    }

    private Content empty(String source, String warning) {
        return new Content(source, "none", warning, "", "", List.of(), List.of(), "", "",
                List.of("writing", "listening", "reading", "translation"));
    }

    /** 取试卷里的「温馨提示 / 说明 / 提示」等备注文本 */
    private String extractNotice(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("温馨提示") || line.startsWith("说明")
                    || line.startsWith("提示") || line.startsWith("注：") || line.startsWith("注意：")) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(line);
                if (sb.length() > 240) {
                    break;
                }
            }
        }
        return sb.toString().trim();
    }

    private String skipNames(List<String> skipped) {
        StringBuilder sb = new StringBuilder();
        for (String s : skipped) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(switch (s) {
                case "writing" -> "写作";
                case "listening" -> "听力";
                case "reading" -> "阅读";
                case "translation" -> "翻译";
                default -> s;
            });
        }
        return sb.toString();
    }

    /* ==================== 听力 ==================== */
    private List<ListeningSection> parseListening(List<String> lines) {
        List<ListeningSection> out = new ArrayList<>();
        Map<String, List<String>> secs = splitSections(lines);
        for (Map.Entry<String, List<String>> e : secs.entrySet()) {
            List<String> body = e.getValue();
            int dirEnd = directionsEndIndex(body);
            String directions = stripDirections(join(body.subList(0, Math.min(dirEnd, body.size()))));
            List<String> content = body.subList(Math.min(dirEnd, body.size()), body.size());
            List<Question> qs = parseQuestions(content);
            if (!qs.isEmpty()) {
                out.add(new ListeningSection(e.getKey(), directions, qs));
            }
        }
        return out;
    }

    /* ==================== 阅读 ==================== */
    private List<ReadingSection> parseReading(List<String> lines) {
        List<ReadingSection> out = new ArrayList<>();
        Map<String, List<String>> secs = splitSections(lines);
        for (Map.Entry<String, List<String>> e : secs.entrySet()) {
            String name = e.getKey();
            List<String> body = e.getValue();
            int dirEnd = directionsEndIndex(body);
            String directions = stripDirections(join(body.subList(0, Math.min(dirEnd, body.size()))));
            List<String> content = body.subList(Math.min(dirEnd, body.size()), body.size());
            List<ReadingBlock> blocks = new ArrayList<>();

            if (name.endsWith("A")) {
                blocks.add(parseBanked(content));
            } else if (name.endsWith("B")) {
                blocks.add(parseMatching(content));
            } else {
                blocks.addAll(parseCareful(content));
            }

            blocks.removeIf(b -> b.questions().isEmpty() && (b.text() == null || b.text().isBlank()));
            if (!blocks.isEmpty()) {
                String kind = name.endsWith("A") ? "banked" : name.endsWith("B") ? "matching" : "careful";
                out.add(new ReadingSection(name, kind, directions, blocks));
            }
        }
        return out;
    }

    /** Section A 选词填空：文章 + 词库 + 题号区间（答案填字母） */
    private ReadingBlock parseBanked(List<String> body) {
        int[] range = rangeOf(body);
        List<String> passageLines = new ArrayList<>();
        List<String> bankLines = new ArrayList<>();
        boolean bankStarted = false;

        for (String raw : body) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (DIRECTIONS.matcher(line).find() || QGROUP.matcher(line).find()) {
                continue;
            }
            if (!bankStarted && looksLikeBank(line)) {
                bankStarted = true;
            }
            if (bankStarted) {
                bankLines.add(line);
            } else {
                passageLines.add(line);
            }
        }

        List<String> bank = new ArrayList<>();
        for (String line : bankLines) {
            for (Option o : splitOptions(line)) {
                String t = o.text().trim();
                if (!t.isEmpty()) {
                    bank.add(o.label() + ") " + t);
                }
            }
        }

        List<Question> qs = new ArrayList<>();
        if (range != null) {
            for (int n = range[0]; n <= range[1]; n++) {
                qs.add(new Question(n, "", List.of(), null));
            }
        } else {
            // 没有 Questions X to Y 声明时，从文章里扫空白编号
            for (int n : blankNumbers(String.join(" ", passageLines))) {
                qs.add(new Question(n, "", List.of(), null));
            }
        }

        String title = range != null ? "选词填空（第 " + range[0] + " - " + range[1] + " 题）" : "选词填空";
        return new ReadingBlock(title, reflow(passageLines), bank, qs);
    }

    /** Section B 段落匹配：标题 + 段落（A-P）+ 陈述句（题号，答案填段落字母） */
    private ReadingBlock parseMatching(List<String> body) {
        int[] range = rangeOf(body);
        String title = "";
        StringBuilder passage = new StringBuilder();
        List<String> statements = new ArrayList<>();
        boolean inStatements = false;
        int lastNum = -1;

        for (String raw : body) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (DIRECTIONS.matcher(line).find() || QGROUP.matcher(line).find()) {
                continue;
            }
            Matcher q = QNUM.matcher(line);
            if (q.matches()) {
                int num = Integer.parseInt(q.group(1));
                if (isQuestionNumber(num, range, lastNum)) {
                    inStatements = true;
                    lastNum = num;
                    statements.add(line.replaceFirst("^\\s*\\d{1,3}\\s*[.)]\\s*", ""));
                    continue;
                }
            }
            if (inStatements) {
                if (!statements.isEmpty()) {
                    statements.set(statements.size() - 1, statements.get(statements.size() - 1) + " " + line);
                }
                continue;
            }
            Matcher p = PARA_LABEL.matcher(line);
            if (p.matches()) {
                if (passage.length() > 0) {
                    passage.append("\n\n");
                }
                passage.append(p.group(1)).append(") ").append(p.group(2).trim());
            } else if (passage.length() == 0 && title.isEmpty()) {
                title = line;
            } else {
                passage.append(' ').append(line);
            }
        }

        List<Question> qs = new ArrayList<>();
        int base = range != null ? range[0] : 36;
        for (int i = 0; i < statements.size(); i++) {
            qs.add(new Question(base + i, statements.get(i), List.of(), null));
        }

        // 校验 / 兜底：
        //   - Directions 里声明了「ten statements」，若数出来的条数不符（题号被排版吃掉、或一行挤了两条），
        //     就改用「末尾 N 行」重建；
        //   - 完全没有题号时同样走末尾重建。
        int expect = expectedStatements(body);
        if (qs.size() != expect) {
            TailSplit ts = tailSplit(body);
            if (ts != null && ts.questions().size() == expect) {
                passage = new StringBuilder(ts.head());
                qs = ts.questions();
            } else if (qs.isEmpty()) {
                qs = List.of();
            }
        }

        return new ReadingBlock(title.isBlank() ? "段落匹配" : title, passage.toString().trim(), List.of(), qs);
    }

    /** 末尾陈述句的拆分结果 */
    private record TailSplit(String head, List<Question> questions) {
    }

    /** Directions 里声明的陈述句数量（默认 10） */
    private int expectedStatements(List<String> body) {
        Pattern p = Pattern.compile(
                "(?i)\\b(ten|nine|eight|seven|six|five|four|three|two|\\d{1,2})\\s+statements?\\b");
        for (String line : body) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                return numberWord(m.group(1));
            }
        }
        return 10;
    }

    private int numberWord(String w) {
        return switch (w.toLowerCase(Locale.ROOT)) {
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            default -> {
                try {
                    yield Integer.parseInt(w);
                } catch (NumberFormatException e) {
                    yield 10;
                }
            }
        };
    }

    /** 取正文末尾 N 行当作没有编号的陈述句（N 来自 Directions 声明），前面部分作为文章 */
    private TailSplit tailSplit(List<String> body) {
        int n = expectedStatements(body);
        List<String> lines = new ArrayList<>();
        for (String raw : body) {
            String line = raw.trim();
            if (line.isEmpty() || DIRECTIONS.matcher(line).find() || QGROUP.matcher(line).find()
                    || PARA_LABEL.matcher(line).matches()) {
                continue;
            }
            lines.add(line);
        }
        if (n < 3 || lines.size() < n + 1) {
            return null;
        }
        List<String> tail = lines.subList(lines.size() - n, lines.size());
        for (String s : tail) {
            if (!endsSentence(s)) {
                return null;
            }
        }
        List<Question> out = new ArrayList<>();
        for (int i = 0; i < tail.size(); i++) {
            out.add(new Question(36 + i, tail.get(i), List.of(), null));
        }
        return new TailSplit(String.join(" ", lines.subList(0, lines.size() - n)), out);
    }

    /** Section C 仔细阅读：可能有多篇 Passage，每篇「文章 + 题目」一个块 */
    private List<ReadingBlock> parseCareful(List<String> body) {
        List<ReadingBlock> blocks = new ArrayList<>();
        String curTitle = null;
        List<String> cur = new ArrayList<>();
        for (String raw : body) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher p = PASSAGE.matcher(line);
            if (p.find()) {
                if (curTitle != null) {
                    blocks.add(carefulBlock(curTitle, cur));
                }
                curTitle = normalizePassageName(p.group(1));
                cur = new ArrayList<>();
                continue;
            }
            if (curTitle == null) {
                curTitle = "仔细阅读";
            }
            cur.add(line);
        }
        if (curTitle != null) {
            blocks.add(carefulBlock(curTitle, cur));
        }

        List<ReadingBlock> out = new ArrayList<>();
        for (ReadingBlock b : blocks) {
            if (!b.questions().isEmpty()) {
                out.add(b);
            }
        }
        return out;
    }

    private ReadingBlock carefulBlock(String title, List<String> body) {
        int[] range = rangeOf(body);
        List<String> cleaned = new ArrayList<>();
        for (String raw : body) {
            String line = raw.trim();
            if (line.isEmpty() || DIRECTIONS.matcher(line).find() || QGROUP.matcher(line).find()) {
                continue;
            }
            cleaned.add(line);
        }

        List<String> passageLines = new ArrayList<>();
        List<String> questionLines = new ArrayList<>();
        boolean inQuestions = false;
        int lastNum = -1;
        for (String line : cleaned) {
            if (!inQuestions) {
                Matcher q = QNUM.matcher(line);
                if (q.matches() && isQuestionNumber(Integer.parseInt(q.group(1)), range, lastNum)) {
                    inQuestions = true;
                }
            }
            if (inQuestions) {
                Matcher q = QNUM.matcher(line);
                if (q.matches()) {
                    lastNum = Integer.parseInt(q.group(1));
                }
                questionLines.add(line);
            } else {
                passageLines.add(line);
            }
        }

        List<Question> qs = parseQuestions(questionLines);
        String passage = reflow(passageLines);
        if (qs.isEmpty()) {
            // 兜底：有的 Word 导出把题号和选项标签都丢了，只能按
            // 「以 ? 结尾的句子 = 题干，紧随其后的 4 句 = A)-D)」从末尾重建
            Rebuilt rb = rebuildTailQuestions(cleaned, range);
            if (rb != null) {
                passage = rb.passage();
                qs = rb.questions();
            }
        }
        return new ReadingBlock(title, passage, List.of(), qs);
    }

    /** 末尾重建出来的「文章 + 题目」 */
    private record Rebuilt(String passage, List<Question> questions) {
    }

    /**
     * 从正文末尾按「题干(? ) + 4 个选项句」为一组，反向重建题目。
     * K 由「Questions X to Y」决定；校验每一组第一句以 ? 结尾、后 4 句不以 ? 结尾。
     */
    private Rebuilt rebuildTailQuestions(List<String> lines, int[] range) {
        List<String> sentences = new ArrayList<>();
        List<Integer> srcLine = new ArrayList<>();
        for (int li = 0; li < lines.size(); li++) {
            for (String s : splitSentences(lines.get(li))) {
                sentences.add(s);
                srcLine.add(li);
            }
        }
        int k = range != null ? (range[1] - range[0] + 1) : 5;
        int need = k * 5;
        if (k <= 0 || sentences.size() < need) {
            return null;
        }
        for (int start = sentences.size() - need; start >= 0; start--) {
            if (!matchesQuestionPattern(sentences, start, k)) {
                continue;
            }
            List<Question> qs = new ArrayList<>();
            int base = range != null ? range[0] : 1;
            for (int j = 0; j < k; j++) {
                int at = start + j * 5;
                List<Option> opts = new ArrayList<>();
                for (int c = 0; c < 4; c++) {
                    opts.add(new Option(CHOICE_LABELS[c], sentences.get(at + 1 + c)));
                }
                qs.add(new Question(base + j, sentences.get(at), opts, null));
            }
            return new Rebuilt(joinSentencesByLine(sentences, srcLine, start), qs);
        }
        return null;
    }

    /** 把前半部分的句子还原成文章：同一行的句子用空格相接，换行处断段 */
    private String joinSentencesByLine(List<String> sentences, List<Integer> srcLine, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (sb.length() > 0) {
                boolean newLine = !srcLine.get(i).equals(srcLine.get(i - 1));
                sb.append(newLine ? "\n\n" : " ");
            }
            sb.append(sentences.get(i));
        }
        return sb.toString().trim();
    }

    private static final String[] CHOICE_LABELS = {"A", "B", "C", "D"};

    private boolean matchesQuestionPattern(List<String> sentences, int start, int k) {
        for (int j = 0; j < k; j++) {
            int at = start + j * 5;
            if (at + 4 >= sentences.size()) {
                return false;
            }
            if (!sentences.get(at).endsWith("?")) {
                return false;
            }
            for (int c = 1; c <= 4; c++) {
                String opt = sentences.get(at + c);
                if (opt.endsWith("?") || opt.length() < 3) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 按句末标点把一行切成句子（短碎片会并到上一句） */
    private List<String> splitSentences(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            cur.append(c);
            if (c == '?' || c == '!' || c == '.' || c == '。' || c == '？' || c == '！') {
                if (i + 1 >= line.length() || Character.isWhitespace(line.charAt(i + 1))) {
                    String s = cur.toString().trim();
                    cur.setLength(0);
                    if (!s.isEmpty()) {
                        if (s.length() < 3 && !out.isEmpty()) {
                            out.set(out.size() - 1, out.get(out.size() - 1) + " " + s);
                        } else {
                            out.add(s);
                        }
                    }
                }
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            if (out.isEmpty()) {
                out.add(tail);
            } else {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + tail);
            }
        }
        return out;
    }

    /* ==================== 题目 / 选项 ==================== */
    /**
     * 解析「题号 + 选项」区块（听力整节、仔细阅读题目部分通用）。
     * 兼容几种排版：
     *   1. 标准：每题「1. A) … B) … C) … D) …」
     *   2. 缺题号：只有「Questions X and Y are based on…」，后面直接跟着若干组选项（每组以 A) 开头）
     *   3. 缺选项标签：只有第一个选项带「A)」，后面 B/C/D 是没标签的独立句子
     */
    private List<Question> parseQuestions(List<String> body) {
        List<QB> qbs = new ArrayList<>();
        QB cur = null;
        String group = null;
        int[] range = null;
        int lastNum = -1;
        int nextAuto = -1;
        boolean started = false;

        for (String raw : body) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher g = QGROUP.matcher(line);
            if (g.find()) {
                group = line;
                range = new int[]{Integer.parseInt(g.group(1)), Integer.parseInt(g.group(2))};
                nextAuto = range[0];
                cur = null;
                started = true;
                continue;
            }
            Matcher q = QNUM.matcher(line);
            if (q.matches() && isQuestionNumber(Integer.parseInt(q.group(1)), range, lastNum)) {
                int num = Integer.parseInt(q.group(1));
                started = true;
                lastNum = num;
                nextAuto = num + 1;
                cur = new QB(num, group);
                qbs.add(cur);
                String rest = q.group(2).trim();
                if (!rest.isEmpty()) {
                    addFragments(cur, rest);
                }
                continue;
            }
            if (!started) {
                continue;
            }
            List<Option> frags = splitOptions(line);
            if (cur == null) {
                if (!frags.isEmpty()) {
                    cur = startNumberless(qbs, group, range, nextAuto, lastNum);
                    nextAuto = cur.number + 1;
                    lastNum = cur.number;
                    addFragments(cur, line);
                }
                continue;
            }
            if (startsNumberlessQuestion(cur, frags)) {
                cur = startNumberless(qbs, group, range, nextAuto, lastNum);
                nextAuto = cur.number + 1;
                lastNum = cur.number;
                addFragments(cur, line);
                continue;
            }
            addFragments(cur, line);
        }

        List<Question> out = new ArrayList<>();
        for (QB b : qbs) {
            Question qq = b.build();
            if (qq.options().size() >= 2 || !qq.stem().isEmpty()) {
                out.add(qq);
            }
        }
        return out;
    }

    /** 出现没有题号的新题时，按分组的区间自动编号（并避开已用过的号） */
    private QB startNumberless(List<QB> qbs, String group, int[] range, int nextAuto, int lastNum) {
        int num;
        if (nextAuto > 0 && (range == null || nextAuto <= range[1])) {
            num = nextAuto;
        } else if (range != null) {
            num = range[1] + 1;
        } else {
            num = lastNum > 0 ? lastNum + 1 : 1;
        }
        while (containsNumber(qbs, num)) {
            num++;
        }
        QB qb = new QB(num, group);
        qbs.add(qb);
        return qb;
    }

    private boolean containsNumber(List<QB> qbs, int num) {
        for (QB b : qbs) {
            if (b.number == num) {
                return true;
            }
        }
        return false;
    }

    /** 当前题已经有一组选项，又冒出以 A) 开头的新选项组 → 这是一道没有题号的新题 */
    private boolean startsNumberlessQuestion(QB cur, List<Option> frags) {
        if (frags.isEmpty() || cur.options.size() < 2) {
            return false;
        }
        if (!"A".equals(frags.get(0).label())) {
            return false;
        }
        for (Option o : cur.options) {
            if ("A".equals(o.label())) {
                return true;
            }
        }
        return false;
    }

    private static final class QB {
        final int number;
        final String group;
        final StringBuilder stem = new StringBuilder();
        final List<Option> options = new ArrayList<>();

        QB(int number, String group) {
            this.number = number;
            this.group = group;
        }

        Question build() {
            return new Question(number, stem.toString().trim(), List.copyOf(options), group);
        }
    }

    /** 把一行文本里出现的所有「X) …」拆成选项；没有选项标签时接到题干/上一个选项后面 */
    private void addFragments(QB qb, String line) {
        List<Option> frags = splitOptions(line);
        if (frags.isEmpty()) {
            // 无标签选项：上一选项是一句完整的话、且本行以大写开头 → 当作下一个字母的选项
            if (!qb.options.isEmpty() && qb.options.size() < 4
                    && isCompleteOption(qb.options.get(qb.options.size() - 1))
                    && startsUpper(line)) {
                qb.options.add(new Option(nextLabel(qb.options), line.trim()));
                return;
            }
            if (!qb.options.isEmpty()) {
                Option last = qb.options.remove(qb.options.size() - 1);
                qb.options.add(new Option(last.label(), (last.text() + " " + line).trim()));
            } else {
                if (qb.stem.length() > 0) {
                    qb.stem.append(' ');
                }
                qb.stem.append(line);
            }
            return;
        }
        for (Option o : frags) {
            // 同一个字母再来一次（换行续写）时合并到已有选项
            int existing = -1;
            for (int i = 0; i < qb.options.size(); i++) {
                if (qb.options.get(i).label().equals(o.label())) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0 && !o.text().isEmpty()) {
                Option old = qb.options.get(existing);
                qb.options.set(existing, new Option(old.label(), (old.text() + " " + o.text()).trim()));
            } else {
                qb.options.add(o);
            }
        }
    }

    /** 选项文本是否是「一句完整的话」 */
    private boolean isCompleteOption(Option o) {
        String t = o.text().trim();
        if (t.length() < 6) {
            return false;
        }
        char c = t.charAt(t.length() - 1);
        return c == '.' || c == '?' || c == '!' || c == '”' || c == '"' || c == '。';
    }

    /** 选项里还没用过的下一个字母 */
    private String nextLabel(List<Option> opts) {
        for (String l : new String[]{"A", "B", "C", "D"}) {
            boolean used = false;
            for (Option o : opts) {
                if (o.label().equals(l)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return l;
            }
        }
        return "D";
    }

    /** 一行可能含多个选项（双栏排版），按「X)」标签切分 */
    private List<Option> splitOptions(String s) {
        List<Option> out = new ArrayList<>();
        Matcher m = OPT.matcher(s);
        List<int[]> spans = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        while (m.find()) {
            int i = m.start();
            if (i > 0 && !Character.isWhitespace(s.charAt(i - 1)) && s.charAt(i - 1) != '.') {
                continue; // 必须是独立出现的标签，避免命中单词里的 A)
            }
            spans.add(new int[]{m.start(), m.end()});
            labels.add(m.group(1));
        }
        for (int k = 0; k < spans.size(); k++) {
            int textStart = spans.get(k)[1];
            int textEnd = (k + 1 < spans.size()) ? spans.get(k + 1)[0] : s.length();
            if (textEnd < textStart) {
                textEnd = textStart;
            }
            String text = s.substring(textStart, textEnd).trim();
            out.add(new Option(labels.get(k), text));
        }
        return out;
    }

    /* ==================== 通用小工具 ==================== */

    /** 把文本切成行：去 tab、压缩连续空格、去掉首尾空白、修正罗马数字与「0)」标签 */
    private List<String> cleanLines(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.replace('\t', ' ').replace("\r", "").split("\n")) {
            String line = raw.replaceAll("[ ]{2,}", " ").trim();
            // 「Part Ⅱ」这类用了罗马数字字符的，统一成 ASCII
            line = line.replace("Ⅳ", "IV").replace("Ⅲ", "III")
                    .replace("Ⅱ", "II").replace("Ⅰ", "I");
            // 词库里的「O)」常被排成数字零「0)」，统一成字母
            line = line.replaceAll("(?<![0-9A-Za-z])0\\)", "O)");
            // 页眉页脚（如「19 ·2025年6月四级真题(第三套) ·」）不要混进正文
            if (isPageFooter(line)) {
                continue;
            }
            // 强调标记只给「解析」页用，题目页统一去掉（填空题的空已经转成下划线字符）
            line = DocumentTextExtractor.stripEmphasis(line);
            out.add(line);
        }
        return out;
    }

    /** 短行且同时含「真题」与「第X套」的，基本就是页眉页脚标注 */
    private boolean isPageFooter(String line) {
        if (line.isEmpty() || line.length() > 46 || !line.contains("真题")) {
            return false;
        }
        if (!Pattern.compile("第\\s*[一二三四五六123456]\\s*套").matcher(line).find()) {
            return false;
        }
        // 正常题目/文章不会出现这么短的带「真题(第X套)」的行
        return !line.endsWith("。") && !line.endsWith("?") && !line.endsWith("？");
    }

    private List<String> slice(List<String> lines, int from, int to) {
        if (from < 0 || from >= lines.size()) {
            return List.of();
        }
        int end = Math.max(from, Math.min(to, lines.size()));
        return lines.subList(from, end);
    }

    /**
     * Part 标题行里若还带着「Section X」（有的 Word 把 Part、题型名、时间、Section 挤在一行），
     * 补一条 Section 标记行到切片开头，避免整节被丢掉。
     */
    private List<String> withSectionMark(List<String> body, String partHeaderLine) {
        if (partHeaderLine == null) {
            return body;
        }
        Matcher m = Pattern.compile("(?i)\\bsection\\s*[〔(\\[（]?\\s*([a-c])\\b").matcher(partHeaderLine);
        if (!m.find()) {
            return body;
        }
        List<String> out = new ArrayList<>();
        out.add("Section " + m.group(1).toUpperCase(Locale.ROOT));
        out.addAll(body);
        return out;
    }

    /** 在 marks 里找比 from 大的最小下标（都没有则返回 fallback） */
    private int firstAfter(int[] marks, int from, int fallback) {
        int best = fallback;
        for (int m : marks) {
            if (m > from && m < best) {
                best = m;
            }
        }
        return best;
    }

    private String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (l.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(l);
        }
        return sb.toString();
    }

    /** 按 Section A/B/C 切分（找不到 Section 标记时整块当作一节） */
    private Map<String, List<String>> splitSections(List<String> lines) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String cur = null;
        for (String line : lines) {
            Matcher m = SECTION.matcher(line);
            if (m.find()) {
                cur = "Section " + m.group(1).toUpperCase();
                out.putIfAbsent(cur, new ArrayList<>());
                continue;
            }
            if (cur == null) {
                continue;
            }
            out.get(cur).add(line);
        }
        if (out.isEmpty()) {
            out.put("Section A", new ArrayList<>(lines));
        }
        return out;
    }

    /** 取一节的 Directions 文本（到第一个 Questions 声明 / 第一道题为止） */
    private String directionsOf(List<String> body) {
        int end = directionsEndIndex(body);
        return stripDirections(join(body.subList(0, Math.min(end, body.size()))));
    }

    /**
     * Directions 段落结束的位置（返回下标，不含）。
     * 判据：从「Directions:」开始的整段，结束于
     *   - 句中含「Answer Sheet」且以句末标点收尾的那一行（四六级 Directions 固定以它结尾），或
     *   - 以句末标点收尾、且下一非空行是「内容单元」开头（Questions/Passage/Section/题号/段落标签）的那一行。
     * 找不到 Directions 时返回 0（整块都是内容）。
     */
    private int directionsEndIndex(List<String> body) {
        int start = -1;
        for (int i = 0; i < body.size(); i++) {
            if (DIRECTIONS.matcher(body.get(i).trim()).find()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return 0;
        }
        for (int i = start; i < body.size(); i++) {
            String line = body.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.contains("Answer Sheet") && endsSentence(line)) {
                return i + 1;
            }
            if (endsSentence(line)) {
                String next = nextNonEmpty(body, i + 1);
                if (next != null && isUnitStart(next)) {
                    return i + 1;
                }
            }
        }
        return start + 1;
    }

    private String nextNonEmpty(List<String> body, int from) {
        for (int i = from; i < body.size(); i++) {
            String s = body.get(i).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /** 是否是「内容单元」的开头（题目分组 / 文章 / 小节 / 题号 / 段落标签） */
    private boolean isUnitStart(String line) {
        return QGROUP.matcher(line).find()
                || PASSAGE.matcher(line).find()
                || SECTION.matcher(line).find()
                || QNUM.matcher(line).matches()
                || PARA_LABEL.matcher(line).matches();
    }

    /** 「Questions X to Y are based on…」声明的题号区间 */
    private int[] rangeOf(List<String> body) {
        for (String line : body) {
            Matcher g = QGROUP.matcher(line.trim());
            if (g.find()) {
                return new int[]{Integer.parseInt(g.group(1)), Integer.parseInt(g.group(2))};
            }
        }
        return null;
    }

    /** 题号是否可信：落在声明区间内，或正好比上一题大 1（无声明时限制在 1-60 起） */
    private boolean isQuestionNumber(int num, int[] range, int lastNum) {
        if (range != null && num >= range[0] && num <= range[1]) {
            return true;
        }
        if (lastNum < 0) {
            return num >= 1 && num <= 60;
        }
        return num == lastNum + 1;
    }

    /** 一行是否是词库行（含 ≥1 个「X) 单词」，且各段都是单个单词） */
    private boolean looksLikeBank(String line) {
        List<Option> frags = splitOptions(line);
        if (frags.isEmpty()) {
            return false;
        }
        for (Option o : frags) {
            String t = o.text().trim();
            if (t.isEmpty() || t.contains(" ") || t.length() > 24) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从文章正文里扫出空白编号（Section A 缺少 Questions 声明时的兜底）。
     * 空的编号有两种写法：下划线夹住的「___26___」和裸数字「… did not 35 cause …」；
     * 正文里还会有别的数字（如「29 percent」），所以取出现顺序里最长的一段连续编号。
     */
    private List<Integer> blankNumbers(String passage) {
        List<Integer> cands = new ArrayList<>();
        Matcher m = Pattern.compile(
                "_{2,}\\s*(\\d{1,3})\\s*_{2,}|(?<![0-9A-Za-z])(\\d{1,3})(?![0-9A-Za-z])").matcher(passage);
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            cands.add(Integer.parseInt(g));
        }

        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < cands.size(); i++) {
            int len = 1;
            int prev = cands.get(i);
            for (int j = i + 1; j < cands.size(); j++) {
                int n = cands.get(j);
                if (n == prev + 1) {
                    prev = n;
                    len++;
                }
            }
            if (len > bestLen) {
                bestLen = len;
                bestStart = i;
            }
        }

        List<Integer> out = new ArrayList<>();
        if (bestStart < 0 || bestLen < 3) {
            return out;
        }
        out.add(cands.get(bestStart));
        for (int j = bestStart + 1; j < cands.size() && out.size() < bestLen; j++) {
            if (cands.get(j) == out.get(out.size() - 1) + 1) {
                out.add(cands.get(j));
            }
        }
        return out;
    }

    /**
     * 文章段落还原。
     *
     * 真题 Word 有两种来源，必须区别对待：
     *  A) 正常排版的 Word：一行就是一个自然段（段长差异很大，常见 200~600 字）；
     *  B) 由 PDF 转出来的 Word：一个自然段被拆成多行，每行长度都逼近版心宽度（60~95 字）。
     * 先统计「有多少行的长度接近最长行」来区分：
     *  - 多数行都接近最长行 → 折行文本：满行与下一行用空格接起来，遇到短行就断段；
     *  - 否则 → 每行本来就是一段，直接用空行隔开。
     */
    private String reflow(List<String> lines) {
        List<String> ls = new ArrayList<>();
        for (String raw : lines) {
            String s = raw.trim();
            if (!s.isEmpty()) {
                ls.add(s);
            }
        }
        if (ls.isEmpty()) {
            return "";
        }
        // 版心宽度用「行长的高分位」估计，避免个别超长段落把判定带偏
        int[] sorted = new int[ls.size()];
        for (int i = 0; i < ls.size(); i++) {
            sorted[i] = ls.get(i).length();
        }
        java.util.Arrays.sort(sorted);
        int width = sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * 0.8))];
        double full = width * 0.85;
        int near = 0;
        for (String s : ls) {
            if (s.length() >= full) {
                near++;
            }
        }
        // 高分位长度本身就远超正常版心（>150）时，说明这些行本来就是完整段落，不用合并
        boolean wrapped = ls.size() >= 3 && width <= 150 && near * 100 / ls.size() >= 55;
        if (!wrapped) {
            return String.join("\n\n", ls);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            if (sb.length() > 0) {
                boolean ownParagraph = isOwnParagraph(ls.get(i - 1).length(), width)
                        || isOwnParagraph(ls.get(i).length(), width);
                // 上一行是「满行」才当作同段续行，否则另起一段
                boolean prevFull = ls.get(i - 1).length() >= full;
                sb.append(!ownParagraph && prevFull ? " " : "\n\n");
            }
            sb.append(ls.get(i));
        }
        return sb.toString();
    }

    /** 行长远超版心宽度（如 200+ 字）时，这一行本身就是完整段落 */
    private boolean isOwnParagraph(int len, int width) {
        return len > width * 1.6;
    }

    /** 拼接两段文本：中英相邻时补空格，中文相接时直接连（避免中文里被塞进空格） */
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

    private boolean endsSentence(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '?' || c == '!' || c == '”' || c == '"' || c == '。' || c == '？' || c == '！';
    }

    private boolean startsUpper(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        return Character.isUpperCase(c) || Character.isDigit(c) || c == '“' || c == '"' || c == '（';
    }

    private boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private String stripDirections(String text) {
        if (text == null) {
            return "";
        }
        return DIRECTIONS.matcher(text.trim()).replaceFirst("").trim();
    }

    private String normalizePassageName(String raw) {
        String s = raw.toLowerCase();
        return switch (s) {
            case "one", "1" -> "Passage One";
            case "two", "2" -> "Passage Two";
            case "three", "3" -> "Passage Three";
            case "four", "4" -> "Passage Four";
            default -> "Passage";
        };
    }
}
