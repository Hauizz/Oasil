package com.example.ai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题目解析：把文档正文切成一道道题，并判断题型。
 *
 * 题型（前端据此渲染不同答题区）：
 *   choice 选择题 / judge 判断题 / blank 填空题 / short 简答题
 *
 * 识别策略（中文试卷常见排版）：
 *   1. 先认题型分节行，例如「一、选择题」「二、判断题（每题1分）」，其后题目沿用该题型；
 *   2. 再认题号起始行：1. / 1、/ 1）/ （1）/ 第1题；
 *   3. 题号后紧跟的行若形如「A.」「B、」「（C）」则作为选项；
 *   4. 没有分节信息时按内容推断：有≥2个选项→选择题（选项是√/×→判断题）；
 *      含下划线或空括号→填空题；含「简答/问答/论述/名词解释」→简答题；其余默认简答。
 *   5. 遇到「参考答案 / 标准答案」等分节即停止，避免把答案当题目。
 */
@Service
public class QuestionParser {

    /** 一个选项：label = A/B/C…，text = 选项内容 */
    public record Option(String label, String text) {
    }

    /** 一道题 */
    public record Question(int number, String type, String stem, List<Option> options, int blanks) {
    }

    /** 解析结果 */
    public record Result(List<Question> questions, String warning) {
    }

    /** 单份文档最多解析多少题，防止异常文本撑爆页面 */
    private static final int MAX_QUESTIONS = 500;

    /** 题型分节行：一、选择题 / 2. 判断题（每题1分，共10分） */
    private static final Pattern SECTION = Pattern.compile(
            "^\\s*(?:[一二三四五六七八九十]+|\\d{1,2})\\s*[、.．)）]\\s*[【\\[（(]?\\s*"
                    + "(单选题|多选题|不定项选择题|选择题|判断题|填空题|简答题|问答题|论述题|名词解释)"
                    + "\\s*[】\\]）)]?\\s*(?:[（(][^)）]{0,40}[)）])?\\s*$");

    /** 只有题型名的分节行 */
    private static final Pattern SECTION_BARE = Pattern.compile(
            "^\\s*[【\\[（(]?\\s*"
                    + "(单选题|多选题|不定项选择题|选择题|判断题|填空题|简答题|问答题|论述题|名词解释)"
                    + "\\s*[】\\]）)]?\\s*(?:[（(][^)）]{0,40}[)）])?\\s*$");

    /** 参考答案分节，见到就停止 */
    private static final Pattern ANSWER_SECTION = Pattern.compile(
            "^\\s*(参考答案|标准答案|答案与解析|答案及解析|试题答案|评分标准|答案要点|正确答案)\\s*[:：]?\\s*$");

    /** 题号起始：1. / 1、/ 1）/ 第1题（group: 1=题号 2=分隔符 3=题面） */
    private static final Pattern QSTART = Pattern.compile(
            "^\\s*(?:第\\s*)?(\\d{1,3})\\s*([、.．,，)）])\\s*(.*)$");

    /** 题号起始：（1）/ (1) */
    private static final Pattern QSTART2 = Pattern.compile(
            "^\\s*[（(]\\s*(\\d{1,3})\\s*[)）]\\s*(.*)$");

    /** 选项：A. / A、/ A）/ A： */
    private static final Pattern OPTION = Pattern.compile(
            "^\\s*([A-Ha-h])\\s*[、.．)）:：]\\s*(.*)$");

    /** 选项：（A）/ (A) */
    private static final Pattern OPTION2 = Pattern.compile(
            "^\\s*[（(]\\s*([A-Ha-h])\\s*[)）]\\s*(.*)$");

    public Result parse(String text) {
        if (text == null || text.isBlank()) {
            return new Result(List.of(), "文件里没有提取到文字内容，无法出题。");
        }

        List<Block> blocks = new ArrayList<>();
        Block current = null;
        String sectionType = null;

        for (String rawLine : text.split("\n")) {
            String line = rawLine.replace('\t', ' ').trim();
            if (line.isEmpty()) {
                continue;
            }

            if (ANSWER_SECTION.matcher(line).matches()) {
                break; // 后面的都是答案，不再当作题目
            }

            String sec = sectionTypeOf(line);
            if (sec != null) {
                sectionType = sec;
                current = null;
                continue;
            }

            Matcher q = QSTART.matcher(line);
            boolean matched = q.matches();
            if (matched && (".".equals(q.group(2)) || "．".equals(q.group(2)))
                    && q.group(3) != null && q.group(3).matches("^\\d.*")) {
                matched = false; // 「3.5 米」这类小数，不是题号
            }
            if (!matched) {
                q = QSTART2.matcher(line);
                matched = q.matches();
            }
            if (matched) {
                String rest = q.group(q.groupCount()) == null ? "" : q.group(q.groupCount()).trim();
                current = new Block(sectionType);
                current.stem.append(rest);
                blocks.add(current);
                continue;
            }

            Matcher o = OPTION.matcher(line);
            if (!o.matches()) {
                o = OPTION2.matcher(line);
            }
            if (o.matches() && current != null && !current.stem.isEmpty()) {
                current.options.add(new Option(o.group(1).toUpperCase(), o.group(2).trim()));
                current.lastIsOptionLine = true;
                continue;
            }

            if (current == null) {
                // 没有编号的独立问句，也当成一道题
                if (looksLikeQuestion(line)) {
                    current = new Block(sectionType);
                    current.stem.append(line);
                    blocks.add(current);
                }
                continue;
            }

            // 续行：优先接到最后一个选项后面，否则接到题干
            if (!current.options.isEmpty() && current.lastIsOptionLine) {
                Option last = current.options.remove(current.options.size() - 1);
                current.options.add(new Option(last.label(), (last.text() + line).trim()));
            } else {
                current.stem.append(line);
            }
            current.lastIsOptionLine = false;
        }

        // ---------- 转成题目 ----------
        List<Question> questions = new ArrayList<>();
        for (Block b : blocks) {
            String stem = cleanStem(b.stem.toString());
            if (stem.isEmpty()) {
                continue;
            }
            String type = b.type != null ? b.type : inferType(stem, b.options);
            List<Option> options = b.options;
            if ("judge".equals(type)) {
                options = List.of(new Option("A", "√"), new Option("B", "×"));
            }
            if ("choice".equals(type) && options.size() < 2) {
                type = inferType(stem, options); // 分节说是选择题但没解析出选项，按内容回退
            }
            int blanks = "blank".equals(type) ? countBlanks(stem) : 0;
            questions.add(new Question(questions.size() + 1, type, stem, options, blanks));
            if (questions.size() >= MAX_QUESTIONS) {
                break;
            }
        }

        String warning = "";
        if (questions.isEmpty()) {
            warning = "没有从文件里识别出题目。请确认文档里的题目带有题号（如 1. / 1、/ （1）），"
                    + "或先按「一、选择题」「二、判断题」这样的题型分节排版。";
        }
        return new Result(questions, warning);
    }

    /* ==================== 内部 ==================== */

    private static final class Block {
        final String type;
        final StringBuilder stem = new StringBuilder();
        final List<Option> options = new ArrayList<>();
        boolean lastIsOptionLine = false;

        Block(String type) {
            this.type = type;
        }
    }

    private String sectionTypeOf(String line) {
        if (line.length() > 40) {
            return null;
        }
        Matcher m = SECTION.matcher(line);
        if (!m.matches()) {
            m = SECTION_BARE.matcher(line);
            if (!m.matches()) {
                return null;
            }
        }
        return toType(m.group(1));
    }

    private String toType(String keyword) {
        switch (keyword) {
            case "单选题":
            case "多选题":
            case "不定项选择题":
            case "选择题":
                return "choice";
            case "判断题":
                return "judge";
            case "填空题":
                return "blank";
            case "简答题":
            case "问答题":
            case "论述题":
            case "名词解释":
                return "short";
            default:
                return null;
        }
    }

    private String inferType(String stem, List<Option> options) {
        if (options.size() >= 2) {
            boolean allJudge = options.stream().allMatch(o ->
                    o.text().matches("^[√×✓✗对错是否正确错误TFtf]+$"));
            return allJudge ? "judge" : "choice";
        }
        if (stem.contains("判断")) {
            return "judge";
        }
        if (stem.contains("填空") || stem.contains("____") || stem.contains("＿＿")
                || stem.matches(".*[（(]\\s{0,8}[)）].*")) {
            return "blank";
        }
        if (stem.contains("简答") || stem.contains("问答") || stem.contains("论述")
                || stem.contains("名词解释") || stem.contains("简述") || stem.contains("请说明")
                || stem.contains("试述") || stem.contains("阐述")) {
            return "short";
        }
        return "short";
    }

    /** 数一下有几个空（下划线或空括号） */
    private int countBlanks(String stem) {
        int n = 0;
        Matcher m = Pattern.compile("_{2,}|＿{2,}|[（(]\\s{1,8}[)）]").matcher(stem);
        while (m.find()) {
            n++;
        }
        return Math.max(1, Math.min(n, 10));
    }

    private boolean looksLikeQuestion(String line) {
        if (line.length() < 4 || line.length() > 300) {
            return false;
        }
        return line.endsWith("？") || line.endsWith("?")
                || line.matches(".*[（(]\\s*[)）]\\s*[。.．]?$")
                || line.contains("____");
    }

    /** 清掉题干里的题号残留、多余空格 */
    private String cleanStem(String s) {
        String r = s.replaceAll("\\s{2,}", " ").trim();
        r = r.replaceAll("^[、.．)）:：]+", "").trim();
        return r;
    }
}
