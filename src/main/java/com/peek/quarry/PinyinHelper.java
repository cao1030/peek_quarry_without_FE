package com.peek.quarry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 汉字 → 拼音，给扫描 GUI 的搜索框做拼音匹配（对标 mod「通用拼音搜索」）。
 *
 * <p>支持：全拼（{@code shitou} → 石头）、首字母缩写（{@code st} → 石头）、
 * 多音字任一读音（{@code yinhang} / {@code yh} → 银行）、ü 的 {@code lv} 和 {@code lu} 两种写法。
 * 原有的中文子串和英文名匹配也一并保留。</p>
 *
 * <p>数据是 {@code assets/peek_quarry/pinyin.txt}，由 {@code tools/gen_pinyin_table.py}
 * 从 Unihan 的 kHanyuPinyin / kMandarin 生成。**按需懒加载** —— 只有客户端第一次搜索时才会读，
 * 服务端永远不会加载到它。读不到就退化成纯文本匹配，不会崩。</p>
 */
public final class PinyinHelper {

    private static final String RESOURCE = "/assets/peek_quarry/pinyin.txt";

    /**
     * 一个名字最多展开多少种拼音组合。
     * 多音字是逐字做笛卡尔积的，不设上限的话长名字会炸；正常方块名只有 0-2 个多音字，
     * 展开出来通常就是 1-6 种，远达不到这个数。
     */
    private static final int MAX_COMBINATIONS = 64;

    /** 拼音表；null 表示还没试过加载。空 map 表示加载失败。 */
    private static Map<Character, String[]> table;

    private PinyinHelper() {}

    /** 只有测试/排错用得上：强制下次重新加载。 */
    public static synchronized void reset() {
        table = null;
    }

    public static synchronized boolean isAvailable() {
        return !loadTable().isEmpty();
    }

    private static Map<Character, String[]> loadTable() {
        if (table != null) {
            return table;
        }
        Map<Character, String[]> map = new LinkedHashMap<Character, String[]>();
        InputStream in = null;
        try {
            in = PinyinHelper.class.getResourceAsStream(RESOURCE);
            if (in == null) {
                if (PeekQuarry.logger != null) {
                    PeekQuarry.logger.warn("[{}] 找不到拼音表 {}，退回纯文本搜索", PeekQuarry.NAME, RESOURCE);
                }
                return table = map;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab >= line.length() - 1) {
                    continue;
                }
                map.put(Character.valueOf(line.charAt(0)), line.substring(tab + 1).split(","));
            }
            reader.close();
            if (PeekQuarry.logger != null) {
                PeekQuarry.logger.info("[{}] 拼音表已加载：{} 个字", PeekQuarry.NAME, map.size());
            }
        } catch (Throwable t) {
            if (PeekQuarry.logger != null) {
                PeekQuarry.logger.warn("[{}] 读取拼音表失败，退回纯文本搜索", PeekQuarry.NAME, t);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 关不掉也无所谓
                }
            }
        }
        return table = map;
    }

    /**
     * 为一个显示名构建「搜索索引」：原文 + 所有全拼组合 + 所有首字母组合，
     * 用换行分隔（搜索框是单行的，查询里不可能有换行，所以各段之间不会串味）。
     *
     * <p>结果由调用方（{@link BlockCount}）缓存，不要每帧调。</p>
     */
    public static String buildSearchText(String displayName) {
        String plain = stripFormatting(displayName).toLowerCase();
        Map<Character, String[]> pinyin = loadTable();
        if (pinyin.isEmpty() || plain.isEmpty()) {
            return plain;
        }

        List<String[]> full = new ArrayList<String[]>(plain.length());
        List<String[]> initials = new ArrayList<String[]>(plain.length());

        for (int i = 0; i < plain.length(); i++) {
            char ch = plain.charAt(i);
            String[] readings = pinyin.get(Character.valueOf(ch));
            if (readings == null || readings.length == 0) {
                // 不是汉字（ASCII、符号、表里没有的字）：整块原样当一段
                String literal = String.valueOf(ch);
                full.add(new String[] { literal });
                initials.add(new String[] { literal });
            } else {
                full.add(readings);
                String[] heads = new String[readings.length];
                for (int k = 0; k < readings.length; k++) {
                    heads[k] = readings[k].substring(0, 1);
                }
                initials.add(heads);
            }
        }

        StringBuilder sb = new StringBuilder(plain);
        appendCombinations(sb, full);
        appendCombinations(sb, initials);
        return sb.toString();
    }

    private static void appendCombinations(StringBuilder sb, List<String[]> options) {
        int n = options.size();
        int[] pick = new int[n];

        Set<String> out = new HashSet<String>();
        out.add(build(options, pick));   // 基准：每个字都用第一个读音

        // 有多个读音的位置。多音字的读音顺序不可靠（Unihan 的 kHanyuPinyin 按
        // 《汉语大字典》页码排，会把生僻音放前面，比如 草 -> zào 在 cǎo 前），
        // 所以必须把替换组合也展开。
        int flexibleCount = 0;
        for (int i = 0; i < n; i++) {
            if (options.get(i).length > 1) {
                flexibleCount++;
            }
        }
        int[] flexible = new int[flexibleCount];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (options.get(i).length > 1) {
                flexible[k++] = i;
            }
        }

        // 关键：按「偏离基准的字数」从 1 递增来枚举，而不是按字典序暴力展平。
        // 预算被截断时，留下的是只改了一个字读音的组合 —— 那才是真正可能命中的那些。
        for (int depth = 1; depth <= flexibleCount; depth++) {
            choosePositions(flexible, 0, new int[depth], 0, options, pick, out);
            if (out.size() >= MAX_COMBINATIONS) {
                break;
            }
        }

        for (String combo : out) {
            sb.append('\n').append(combo);
        }
    }

    /** 从 flexible 里挑 depth 个位置。 */
    private static void choosePositions(int[] flexible, int start, int[] chosen, int at,
                                        List<String[]> options, int[] pick, Set<String> out) {
        if (out.size() >= MAX_COMBINATIONS) {
            return;
        }
        if (at == chosen.length) {
            assignReadings(chosen, 0, options, pick, out);
            return;
        }
        for (int i = start; i <= flexible.length - (chosen.length - at); i++) {
            chosen[at] = flexible[i];
            choosePositions(flexible, i + 1, chosen, at + 1, options, pick, out);
            if (out.size() >= MAX_COMBINATIONS) {
                return;
            }
        }
    }

    /** 给挑出来的位置逐个换成非首个读音。 */
    private static void assignReadings(int[] chosen, int idx, List<String[]> options,
                                       int[] pick, Set<String> out) {
        if (out.size() >= MAX_COMBINATIONS) {
            return;
        }
        if (idx == chosen.length) {
            out.add(build(options, pick));
            return;
        }
        int pos = chosen[idx];
        String[] choices = options.get(pos);
        for (int i = 1; i < choices.length; i++) {
            pick[pos] = i;
            assignReadings(chosen, idx + 1, options, pick, out);
            if (out.size() >= MAX_COMBINATIONS) {
                return;
            }
        }
        pick[pos] = 0;
    }

    private static String build(List<String[]> options, int[] pick) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            sb.append(options.get(i)[pick[i]]);
        }
        return sb.toString();
    }

    /** 去掉 {@code §} 颜色/格式代码 —— 显示名里可能带。 */
    private static String stripFormatting(String text) {
        if (text == null) {
            return "";
        }
        if (text.indexOf('\u00a7') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
