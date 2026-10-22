package com.xiongx9527.askdata.ai;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 embedding 模型（ONNX + WordPiece）单测。
 *
 * <p>覆盖三件事：</p>
 * <ol>
 *   <li><b>分词正确性</b>：WordPiece 切词、CJK 单字切分、标点切分、续接前缀（{@code ##}）、
 *       词表外 {@code [UNK]} 与 HuggingFace {@code BertTokenizer} 语义一致；</li>
 *   <li><b>编码</b>：{@code [CLS]}/{@code [SEP]} 包裹、批量 padding 与 attention mask；</li>
 *   <li><b>降级</b>：模型目录缺失 / 权重缺失时 {@link LocalEmbeddingModel#embed(String)} 返回
 *       {@code null} 且<b>不抛异常</b>（问数主链路的可用性前提）。</li>
 * </ol>
 *
 * <p>分词用例使用本测试自带的固定词表（{@link #VOCAB}），不依赖未入库的模型权重：
 * 期望值由该词表唯一确定，因此可在任意环境（含 CI）稳定执行，不再跳过。</p>
 *
 * <p>推理用例依赖部署机上的真实权重（{@code -Daskdata.embedding.model-path=...}）；
 * 权重缺失时不断言“跳过”，而是断言文档化的降级契约（不可用 + 给出原因 + 返回 null），
 * 避免出现不做任何断言的空跑用例。</p>
 */
class LocalEmbeddingModelTest {

    /** 模型目录（可用系统属性 {@code -Daskdata.embedding.model-path=...} 覆盖）。 */
    private static final String MODEL_PATH =
            System.getProperty("askdata.embedding.model-path", "/opt/askdata/models");

    /**
     * 分词契约词表：顺序即 id（{@code [PAD]} 固定为 0，便于断言 padding 位）。
     * 每个条目对应下方断言中的一个期望 token。
     */
    private static final List<String> VOCAB = Arrays.asList(
            "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",
            "hello", "world",
            "订", "单", "金", "额", "统", "计",
            "中", "文", "english", "混", "合", "123",
            "tab", "new", "line",
            "c", "+", "&", "java",
            "em", "##o", "##ji", "test",
            "re", "##pe", "##at", "##word");

    private static Path vocabFile;

    @BeforeAll
    static void writeVocab() throws Exception {
        Path dir = Files.createTempDirectory("askdata-embedding-vocab");
        vocabFile = dir.resolve("vocab.txt");
        Files.write(vocabFile, VOCAB, StandardCharsets.UTF_8);
    }

    private BertWordPieceTokenizer tokenizer() throws Exception {
        return BertWordPieceTokenizer.fromVocabFile(vocabFile, true, true);
    }

    // ------------------------------------------------------------------
    // 分词（期望值由 VOCAB 唯一确定，与 HF BertTokenizer 语义一致）
    // ------------------------------------------------------------------

    @Test
    void tokenizerMatchesHuggingFaceBertTokenizer() throws Exception {
        BertWordPieceTokenizer tokenizer = tokenizer();
        assertEquals(Arrays.asList("hello", "world"), tokenizer.tokenize("Hello World"));
        assertEquals(Arrays.asList("订", "单", "金", "额", "统", "计"), tokenizer.tokenize("订单金额统计"));
        assertEquals(Arrays.asList("中", "文", "english", "混", "合", "123"),
                tokenizer.tokenize("中文English混合123"));
        assertEquals(Arrays.asList("tab", "new", "line"), tokenizer.tokenize("tab\tnew\nline"));
        assertEquals(Arrays.asList("c", "+", "+", "&", "java"), tokenizer.tokenize("C++ & Java"));
        assertEquals(Arrays.asList("em", "##o", "##ji", "[UNK]", "test"), tokenizer.tokenize("emoji 😀 test"));
        assertEquals(Arrays.asList("re", "##pe", "##at", "##word", "##word", "##word"),
                tokenizer.tokenize("repeatwordwordword"));
    }

    @Test
    void tokenizerProducesClsSepAndUnknownForEmpty() throws Exception {
        BertWordPieceTokenizer tokenizer = tokenizer();
        assertTrue(tokenizer.tokenize("").isEmpty());
        assertTrue(tokenizer.tokenize("   ").isEmpty());
        // 词表外整词 → [UNK]
        assertEquals(Arrays.asList("[UNK]"), tokenizer.tokenize("Ⅻ"));
    }

    @Test
    void encodingWrapsWithClsAndSepAndMasksPadding() throws Exception {
        BertWordPieceTokenizer tokenizer = tokenizer();
        BertWordPieceTokenizer.Encoding encoding = tokenizer.encode("hello world", 16);
        assertEquals(4, encoding.inputIds.length);
        assertEquals(tokenizer.idOf("[CLS]"), encoding.inputIds[0]);
        assertEquals(tokenizer.idOf("[SEP]"), encoding.inputIds[3]);
        for (long mask : encoding.attentionMask) {
            assertEquals(1L, mask);
        }
        for (long type : encoding.tokenTypeIds) {
            assertEquals(0L, type);
        }
    }

    @Test
    void batchEncodingPadsToLongestWithZeroMask() throws Exception {
        BertWordPieceTokenizer tokenizer = tokenizer();
        BertWordPieceTokenizer.Encoding encoding = tokenizer.encodeBatch(
                Arrays.asList("hello", "hello world"), 16, 0);
        // 展平后的张量形状是 [batch, width] = [2, 4]（hello=3 位，hello world=4 位）
        assertEquals(8, encoding.attentionMask.length);
        // 第一条占 [0,4)：有效 3 位，第 4 位是 padding → attention_mask = 0
        assertEquals(1L, encoding.attentionMask[0]);
        assertEquals(1L, encoding.attentionMask[1]);
        assertEquals(1L, encoding.attentionMask[2]);
        assertEquals(0L, encoding.attentionMask[3]);
        // padding 位的 input_ids 填 padId（词表中 [PAD] 位于 0）
        assertEquals(0L, encoding.inputIds[3]);
        // 第二条占 [4,8)：4 位全有效
        for (int i = 4; i < 8; i++) {
            assertEquals(1L, encoding.attentionMask[i]);
        }
    }

    // ------------------------------------------------------------------
    // 降级（验收关键：模型不可用时不得抛错）
    // ------------------------------------------------------------------

    @Test
    void missingModelDirectoryDegradesWithoutThrowing() {
        LocalEmbeddingModel model = new LocalEmbeddingModel("/tmp/askdata-no-such-model-dir", 128);
        assertFalse(model.available());
        assertNotNull(model.unavailableReason());
        assertNull(model.embed("订单金额统计"));
        assertNull(model.embedAll(Arrays.asList("a", "b")));
    }

    @Test
    void missingWeightFileDegradesWithoutThrowing() throws Exception {
        Path base = Files.createTempDirectory("askdata-embedding-missing");
        Files.createDirectories(base.resolve(LocalEmbeddingModel.MODEL_SUBDIR).resolve("onnx"));
        LocalEmbeddingModel model = new LocalEmbeddingModel(base.toString(), 128);
        assertFalse(model.available());
        assertNull(model.embed("订单金额统计"));
    }

    @Test
    void emptyTextReturnsNullWithoutLoadingModel() {
        LocalEmbeddingModel model = new LocalEmbeddingModel("/tmp/askdata-no-such-model-dir", 128);
        assertNull(model.embed(null));
        assertNull(model.embed("  "));
    }

    // ------------------------------------------------------------------
    // 推理契约（有权重时校验维度/归一化与语义区分；无权重时校验降级契约）
    // ------------------------------------------------------------------

    @Test
    void embeddingIs768DimensionalAndNormalizedOrDegradesExplicitly() {
        LocalEmbeddingModel model = new LocalEmbeddingModel(MODEL_PATH, 128);
        if (!model.available()) {
            assertNotNull(model.unavailableReason(), "模型不可用时必须给出可观测原因");
            assertNull(model.embed("订单金额统计"), "模型不可用时必须返回 null 而不是抛错或伪造向量");
            return;
        }
        double[] vector = model.embed("订单金额统计");
        assertNotNull(vector);
        assertEquals(LocalEmbeddingModel.EMBEDDING_DIMENSION, vector.length);
        double norm = 0D;
        for (double value : vector) {
            norm += value * value;
        }
        assertEquals(1D, Math.sqrt(norm), 1e-4);
    }

    @Test
    void semanticallyRelatedTextsScoreHigherOrDegradesExplicitly() {
        LocalEmbeddingModel model = new LocalEmbeddingModel(MODEL_PATH, 128);
        if (!model.available()) {
            assertNotNull(model.unavailableReason(), "模型不可用时必须给出可观测原因");
            assertNull(model.embedAll(Arrays.asList("订单金额统计", "hello world")),
                    "模型不可用时批量接口必须返回 null");
            return;
        }
        List<String> texts = Arrays.asList("订单金额统计", "今天的销售额是多少", "hello world");
        List<double[]> vectors = model.embedAll(texts);
        assertNotNull(vectors);
        assertEquals(3, vectors.size());
        double related = cosine(vectors.get(0), vectors.get(1));
        double unrelated = cosine(vectors.get(0), vectors.get(2));
        assertTrue(related > unrelated,
                "相关文本余弦相似度应更高：related=" + related + " unrelated=" + unrelated);
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0D;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}

