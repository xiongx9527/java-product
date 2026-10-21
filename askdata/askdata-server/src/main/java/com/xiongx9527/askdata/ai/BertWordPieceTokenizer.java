package com.xiongx9527.askdata.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT WordPiece 分词器（纯 Java 实现，对齐 HuggingFace {@code BertTokenizer}）。
 *
 * <p>用于 {@link LocalEmbeddingModel} 的本地 ONNX 推理：模型目录只提供
 * {@code vocab.txt}（词表）与 {@code tokenizer_config.json}（特殊符号与标志），
 * 不提供可离线解析的 {@code tokenizer.json} 时，用本类按 BERT 规范切词。</p>
 *
 * <p><b>切词流程</b>（对齐 {@code BertTokenizer} 的 {@code BasicTokenizer} + {@code WordpieceTokenizer}）：</p>
 * <ol>
 *   <li>清理：去掉不可见控制字符，把 {@code \t}/{@code \n}/{@code \r} 归一为空格；</li>
 *   <li>按空白切分并去掉空片段；</li>
 *   <li>标点切分：每个标点单独成词（{@code CJK} 字符亦单独成词）；</li>
 *   <li>大小写折叠（{@code do_lower_case=true}，用 {@link java.text.Normalizer} 的 NFKD
 *       去组合记号，等价 HF 的 {@code strip_accents}）；</li>
 *   <li>WordPiece 贪心最长匹配：词首取最长前缀，其后取 {@code ##} 前缀的最长匹配，
 *       匹配不上则整词记为 {@code [UNK]}。</li>
 * </ol>
 *
 * <p>特殊符号：{@code [CLS]} / {@code [SEP]} / {@code [PAD]} / {@code [UNK]} / {@code [MASK]}，
 * 词表中的 id 即行号（0-based）。</p>
 */
public final class BertWordPieceTokenizer {

    /** 词表：token → id。 */
    private final Map<String, Integer> vocabulary;

    /** 特殊 token → id。 */
    private final Map<String, Integer> specialTokens = new HashMap<>();

    /** 是否折叠大小写。 */
    private final boolean doLowerCase;

    /** 是否把 CJK 字符单独成词。 */
    private final boolean tokenizeChineseChars = true;

    /** 是否去掉组合记号（等价 HF 的 strip_accents）。 */
    private final boolean stripAccents;

    private BertWordPieceTokenizer(Map<String, Integer> vocabulary, boolean doLowerCase, boolean stripAccents) {
        this.vocabulary = vocabulary;
        this.doLowerCase = doLowerCase;
        this.stripAccents = stripAccents;
        for (String name : new String[]{"[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"}) {
            Integer id = vocabulary.get(name);
            if (id != null) {
                specialTokens.put(name, id);
            }
        }
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 从 {@code vocab.txt} 加载分词器。
     *
     * @param vocabFile    词表文件（每行一个 token，行号即 id）
     * @param doLowerCase  是否折叠大小写
     * @param stripAccents 是否去掉组合记号
     */
    public static BertWordPieceTokenizer fromVocabFile(Path vocabFile, boolean doLowerCase, boolean stripAccents)
            throws IOException {
        Map<String, Integer> vocabulary = new HashMap<>(32768);
        int id = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(vocabFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 词表文件是 UTF-8 且不使用引号转义；直接按行取（保留行内空格）
                vocabulary.put(line, id++);
            }
        }
        return new BertWordPieceTokenizer(vocabulary, doLowerCase, stripAccents);
    }

    /** 从输入流加载（词表内容为 UTF-8 文本，每行一个 token）。 */
    public static BertWordPieceTokenizer fromVocabStream(InputStream inputStream, boolean doLowerCase,
                                                        boolean stripAccents) throws IOException {
        Map<String, Integer> vocabulary = new HashMap<>(32768);
        int id = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                vocabulary.put(line, id++);
            }
        }
        return new BertWordPieceTokenizer(vocabulary, doLowerCase, stripAccents);
    }

    // ------------------------------------------------------------------
    // 编码
    // ------------------------------------------------------------------

    /** 编码结果（token id 序列）。 */
    public static final class Encoding {

        public final long[] inputIds;

        public final long[] attentionMask;

        public final long[] tokenTypeIds;

        public Encoding(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
            this.tokenTypeIds = tokenTypeIds;
        }
    }

    /**
     * 单条文本编码（{@code [CLS] tokens [SEP]}，不 padding）。
     *
     * @param text     文本
     * @param maxLength 最大序列长度（含特殊符号）
     */
    public Encoding encode(String text, int maxLength) {
        List<String> tokens = tokenize(text);
        int cls = idOf("[CLS]");
        int sep = idOf("[SEP]");
        List<Integer> ids = new ArrayList<>();
        ids.add(cls);
        for (String token : tokens) {
            if (ids.size() >= maxLength - 1) {
                break;
            }
            ids.add(vocabulary.get(token));
        }
        ids.add(sep);
        long[] inputIds = new long[ids.size()];
        long[] mask = new long[ids.size()];
        long[] types = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            inputIds[i] = ids.get(i);
            mask[i] = 1L;
            types[i] = 0L;
        }
        return new Encoding(inputIds, mask, types);
    }

    /**
     * 批量编码并 padding 到同一长度（对齐 HF 的 {@code padding=True}）。
     *
     * @param texts     文本数组
     * @param maxLength 单条最大长度（含特殊符号）
     * @param padId     padding token id
     */
    public Encoding encodeBatch(List<String> texts, int maxLength, int padId) {
        List<long[]> idList = new ArrayList<>(texts.size());
        int max = 0;
        for (String text : texts) {
            long[] ids = encode(text, maxLength).inputIds;
            idList.add(ids);
            max = Math.max(max, ids.length);
        }
        // 张量形状为 [batch, max]，故展平后的总长是 batch * max（不是 max）。
        int width = maxList(max);
        int total = idList.size() * width;
        long[] inputIds = new long[total];
        long[] mask = new long[total];
        long[] types = new long[total];
        for (int i = 0; i < idList.size(); i++) {
            long[] ids = idList.get(i);
            int offset = i * width;
            System.arraycopy(ids, 0, inputIds, offset, ids.length);
            // 尾部用 padId 补齐（attention_mask 标 0，模型据此忽略 padding 位）
            for (int j = ids.length; j < width; j++) {
                inputIds[offset + j] = padId;
            }
            for (int j = 0; j < ids.length; j++) {
                mask[offset + j] = 1L;
            }
        }
        return new Encoding(inputIds, mask, types);
    }

    private static int maxList(int max) {
        return max <= 0 ? 1 : max;
    }

    // ------------------------------------------------------------------
    // 切词
    // ------------------------------------------------------------------

    /**
     * 切词（不含特殊符号）。
     *
     * @param text 文本
     * @return token 列表（可能含 {@code [UNK]}）
     */
    public List<String> tokenize(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        String cleaned = clean(text);
        if (cleaned.isEmpty()) {
            return result;
        }
        for (String word : whitespaceTokenize(cleaned)) {
            String normalized = normalize(word);
            if (normalized.isEmpty()) {
                continue;
            }
            for (String piece : punctuationSplit(normalized)) {
                List<String> subtokens = wordpiece(piece);
                result.addAll(subtokens);
            }
        }
        return result;
    }

    /** 清理控制字符、归一空白（对齐 HF {@code _clean_text}）。 */
    private static String clean(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else if (c == 0 || c == 0xFFFD || isControl(c)) {
                // 丢弃不可见控制字符
                continue;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isControl(char c) {
        if (c >= 0x00 && c <= 0x1F) {
            return true;
        }
        if (c >= 0x7F && c <= 0x9F) {
            return true;
        }
        return false;
    }

    /** 空白切分（对齐 HF {@code whitespace_tokenize}）。 */
    private static List<String> whitespaceTokenize(String text) {
        List<String> result = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (start >= 0) {
                    result.add(text.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) {
            result.add(text.substring(start));
        }
        return result;
    }

    /** 大小写折叠 + 去组合记号（等价 HF {@code lower()} 后 {@code _run_strip_accents}）。 */
    private String normalize(String word) {
        String result = word;
        if (doLowerCase) {
            result = result.toLowerCase(java.util.Locale.ROOT);
        }
        if (stripAccents) {
            // HF 用 NFD（不是 NFKD）：全角括号/全角数字等兼容字符不被分解
            String normalized = java.text.Normalizer.normalize(result, java.text.Normalizer.Form.NFD);
            StringBuilder sb = new StringBuilder(normalized.length());
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                if (Character.getType(c) != Character.NON_SPACING_MARK) {
                    sb.append(c);
                }
            }
            result = sb.toString();
        }
        return result;
    }

    /**
     * 标点切分（对齐 HF {@code _run_split_on_punc}）：
     * 每个标点字符单独成词；CJK 字符也单独成词。
     */
    private List<String> punctuationSplit(String word) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (isPunctuation(c) || (tokenizeChineseChars && isCjk(c))) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                result.add(String.valueOf(c));
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /** 标点判定（对齐 HF {@code _is_punctuation}：ASCII 符号 + Unicode P* 类别）。 */
    private static boolean isPunctuation(char c) {
        if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }

    /** CJK 字符判定（对齐 HF {@code _is_chinese_char} 的三个 Unicode 区块）。 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x20000 && c <= 0x2A6DF)
                || (c >= 0x2A700 && c <= 0x2B73F)
                || (c >= 0x2B740 && c <= 0x2B81F)
                || (c >= 0x2B820 && c <= 0x2CEAF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0x2F800 && c <= 0x2FA1F);
    }

    /**
     * WordPiece 贪心最长匹配（对齐 HF {@code WordpieceTokenizer}）。
     *
     * <p>词首片段不加 {@code ##}，其余片段加 {@code ##} 前缀；
     * 任一片段匹配不上则整词记为 {@code [UNK]}。</p>
     */
    private List<String> wordpiece(String word) {
        if (word.isEmpty()) {
            return new ArrayList<>();
        }
        if (vocabulary.containsKey(word)) {
            List<String> single = new ArrayList<>(1);
            single.add(word);
            return single;
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        boolean isBad = false;
        while (start < word.length()) {
            int end = word.length();
            String match = null;
            while (start < end) {
                String candidate = word.substring(start, end);
                if (start > 0) {
                    candidate = "##" + candidate;
                }
                if (vocabulary.containsKey(candidate)) {
                    match = candidate;
                    break;
                }
                end--;
            }
            if (match == null) {
                isBad = true;
                break;
            }
            pieces.add(match);
            start = end;
        }
        if (isBad) {
            List<String> unk = new ArrayList<>(1);
            unk.add("[UNK]");
            return unk;
        }
        return pieces;
    }

    /** 特殊 token id（不存在返回 -1）。 */
    public int idOf(String token) {
        Integer id = specialTokens.get(token);
        if (id != null) {
            return id;
        }
        Integer direct = vocabulary.get(token);
        return direct == null ? -1 : direct;
    }

    /** 词表大小。 */
    public int vocabularySize() {
        return vocabulary.size();
    }
}
