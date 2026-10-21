package com.xiongx9527.askdata.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Paths;
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
 *   <li><b>分词正确性</b>：WordPiece 切词与 HuggingFace {@code BertTokenizer} 逐 token 一致
 *       （参考值取自 SQLBot 运行实例 {@code shibing624_text2vec-base-chinese} 的实际分词输出）；</li>
 *   <li><b>降级</b>：模型目录缺失 / 权重缺失时 {@link LocalEmbeddingModel#embed(String)} 返回
 *       {@code null} 且<b>不抛异常</b>（问数主链路的可用性前提）；</li>
 *   <li><b>推理</b>：模型目录存在且权重有效时，向量维度为 768 且 L2 范数为 1
 *       （模型文件不入 git，故仅在部署机上有权重时执行，见 {@code @EnabledIf}）。</li>
 * </ol>
 */
class LocalEmbeddingModelTest {

    /** 模型目录（可用系统属性 {@code -Daskdata.embedding.model-path=...} 覆盖）。 */
    private static final String MODEL_PATH =
            System.getProperty("askdata.embedding.model-path", "/opt/askdata/models");

    // ------------------------------------------------------------------
    // 分词（对照 HuggingFace BertTokenizer 的实际输出）
    // ------------------------------------------------------------------

    private BertWordPieceTokenizer tokenizer() throws Exception {
        java.nio.file.Path vocab = java.nio.file.Paths.get(System.getProperty("askdata.embedding.vocab",
                "/opt/askdata/models/embedding/shibing624_text2vec-base-chinese/onnx/vocab.txt"));
        // vocab ships with the model weights (not in git). Tokenizer comparison
        // depends on the real vocab; skip when the model is not deployed
        // (degrade path is covered by the tests below).
        Assumptions.assumeTrue(java.nio.file.Files.exists(vocab),
                "vocab missing (model not deployed), skip tokenizer comparison");
        return BertWordPieceTokenizer.fromVocabFile(vocab, true, true);
    }

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
        // padding 位的 input_ids 填 padId
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
        java.nio.file.Path base = java.nio.file.Files.createTempDirectory("askdata-embedding-missing");
        java.nio.file.Files.createDirectories(base.resolve(LocalEmbeddingModel.MODEL_SUBDIR).resolve("onnx"));
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
    // 推理（需要部署机上的真实权重；无权重时跳过）
    // ------------------------------------------------------------------

    @Test
    void embeddingDimensionIs768AndNormalized() {
        LocalEmbeddingModel model = new LocalEmbeddingModel(MODEL_PATH, 128);
        if (!model.available()) {
            // 部署机未提供模型：降级路径已在上面的用例覆盖
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
    void semanticallyRelatedTextsHaveHigherCosineThanUnrelated() {
        LocalEmbeddingModel model = new LocalEmbeddingModel(MODEL_PATH, 128);
        if (!model.available()) {
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
