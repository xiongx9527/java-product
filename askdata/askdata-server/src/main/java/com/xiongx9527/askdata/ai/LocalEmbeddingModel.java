package com.xiongx9527.askdata.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.xiongx9527.askdata.common.i18n.I18n;

/**
 * 本地 embedding 模型（对齐 SQLBot {@code apps/ai_model/embedding.py} 的 EmbeddingModelCache）。
 *
 * <p>默认模型为 {@code shibing624/text2vec-base-chinese}（ONNX，768 维，句向量已归一化），
 * 模型文件随构建产物交付到<b>可配置目录</b>（默认 {@code /opt/askdata/models}），
 * <b>不进 git</b>（793MB）。目录结构：</p>
 * <pre>
 * {model-path}/embedding/shibing624_text2vec-base-chinese/
 *   onnx/model.onnx        ← ONNX 权重
 *   vocab.txt              ← WordPiece 词表
 *   tokenizer_config.json  ← （可选）do_lower_case / model_max_length
 *   config.json            ← （可选）hidden_size
 * </pre>
 *
 * <p><b>降级（验收关键）</b>：模型目录缺失、权重加载失败或推理异常时，
 * 本类保持「不可用」状态，{@link #embed(String)} / {@link #embedAll(List)} 返回 {@code null}，
 * 调用方（向量召回）据此降级为词法召回，<b>不抛异常</b>——问数主链路仍能返回结果。</p>
 *
 * <p><b>优先级</b>：远程 embedding 模型（{@code ai_model.model_type=1}）配置时优先使用远程；
 * 未配置时回落到本类（对齐 spec「本地 embedding 模型」场景）。</p>
 */
@Component
public class LocalEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingModel.class);

    /** 默认模型目录（对齐 SQLBot settings.LOCAL_MODEL_PATH）。 */
    public static final String DEFAULT_MODEL_PATH = "/opt/askdata/models";

    /** 模型子目录（对齐 SQLBot 的 {@code {LOCAL_MODEL_PATH}/embedding/<model>}）。 */
    public static final String MODEL_SUBDIR = "embedding/shibing624_text2vec-base-chinese";

    /** 默认最大序列长度（对齐 sentence_bert_config.json 的 max_seq_length=128）。 */
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 128;

    /** 期望的向量维度（text2vec-base-chinese）。 */
    public static final int EMBEDDING_DIMENSION = 768;

    private final String modelPath;

    private final int maxSequenceLength;

    /** 懒加载的会话与分词器（首次使用时初始化；失败保持 null 并永久降级）。 */
    private volatile OrtSession session;

    private volatile BertWordPieceTokenizer tokenizer;

    private volatile boolean unavailable;

    private volatile String unavailableReason;

    /** Spring 装配入口（模型目录可配：{@code askdata.embedding.model-path}，默认 {@code /opt/askdata/models}）。 */
    @Autowired
    public LocalEmbeddingModel(@Value("${askdata.embedding.model-path:" + DEFAULT_MODEL_PATH + "}") String modelPath) {
        this(modelPath, DEFAULT_MAX_SEQUENCE_LENGTH);
    }

    /** 构造（显式模型目录与最大序列长度）。 */
    public LocalEmbeddingModel(String modelPath, int maxSequenceLength) {
        this.modelPath = modelPath == null || modelPath.trim().isEmpty() ? DEFAULT_MODEL_PATH : modelPath.trim();
        this.maxSequenceLength = maxSequenceLength <= 0 ? DEFAULT_MAX_SEQUENCE_LENGTH : maxSequenceLength;
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /**
     * 单条文本的向量。
     *
     * @return 归一化向量（768 维）；模型不可用或推理失败时返回 {@code null}（不抛错）
     */
    public double[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        List<double[]> vectors = embedAll(java.util.Collections.singletonList(text));
        return vectors == null || vectors.isEmpty() ? null : vectors.get(0);
    }

    /**
     * 批量文本的向量。
     *
     * @return 归一化向量列表（与入参同序）；模型不可用或推理失败时返回 {@code null}（不抛错）
     */
    public List<double[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        if (!ensureLoaded()) {
            return null;
        }
        try {
            return run(texts);
        } catch (Exception e) {
            log.warn("本地 embedding 推理失败（降级为词法召回）: {}", e.getMessage());
            return null;
        }
    }

    /** 模型是否可用（已成功加载或加载成功过）。 */
    public boolean available() {
        return ensureLoaded();
    }

    /** 不可用原因（可用时为 null）。 */
    public String unavailableReason() {
        ensureLoaded();
        return unavailableReason;
    }

    /** 模型目录（诊断用）。 */
    public String modelPath() {
        return modelPath;
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 懒加载模型（线程安全）。
     *
     * @return true 表示模型可用
     */
    private boolean ensureLoaded() {
        if (unavailable) {
            return false;
        }
        if (session != null && tokenizer != null) {
            return true;
        }
        synchronized (this) {
            if (unavailable) {
                return false;
            }
            if (session != null && tokenizer != null) {
                return true;
            }
            try {
                load();
            } catch (Exception e) {
                unavailable = true;
                unavailableReason = e.getMessage() == null ? e.toString() : e.getMessage();
                log.info("本地 embedding 模型不可用（降级为词法召回）：{}（目录 {}）",
                        unavailableReason, modelPath);
            }
        }
        return session != null && tokenizer != null;
    }

    /** 加载 ONNX 会话与分词器。 */
    private void load() throws OrtException, IOException {
        Path base = Paths.get(modelPath).resolve(MODEL_SUBDIR);
        Path onnxDir = base.resolve("onnx");
        Path modelFile = onnxDir.resolve("model.onnx");
        if (!Files.isRegularFile(modelFile)) {
            // 兼容直接把权重放在模型根目录的部署形态
            Path alt = base.resolve("model.onnx");
            if (Files.isRegularFile(alt)) {
                modelFile = alt;
                onnxDir = base;
            } else {
                throw new IOException(I18n.msg("embedding.model.not.found", modelFile.toString()));
            }
        }
        Path vocabFile = onnxDir.resolve("vocab.txt");
        if (!Files.isRegularFile(vocabFile)) {
            vocabFile = base.resolve("vocab.txt");
        }
        if (!Files.isRegularFile(vocabFile)) {
            throw new IOException(I18n.msg("embedding.vocab.not.found", vocabFile.toString()));
        }
        boolean doLowerCase = readBoolean(base.resolve("tokenizer_config.json"), "do_lower_case", true);
        boolean stripAccents = readBoolean(base.resolve("tokenizer_config.json"), "strip_accents", true);
        BertWordPieceTokenizer loaded = BertWordPieceTokenizer.fromVocabFile(vocabFile, doLowerCase, stripAccents);
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, Math.min(4,
                Runtime.getRuntime().availableProcessors())));
        OrtSession opened = environment.createSession(modelFile.toString(), options);
        this.tokenizer = loaded;
        this.session = opened;
        log.info("本地 embedding 模型已加载：{}（词表 {}，最大长度 {}）", modelFile, loaded.vocabularySize(),
                maxSequenceLength);
    }

    /** 从 tokenizer_config.json 读布尔配置（读不到用缺省值）。 */
    private static boolean readBoolean(Path configFile, String key, boolean defaultValue) {
        if (!Files.isRegularFile(configFile)) {
            return defaultValue;
        }
        try {
            String text = new String(Files.readAllBytes(configFile), java.nio.charset.StandardCharsets.UTF_8);
            String marker = "\"" + key + "\"";
            int at = text.indexOf(marker);
            if (at < 0) {
                return defaultValue;
            }
            int colon = text.indexOf(':', at + marker.length());
            if (colon < 0) {
                return defaultValue;
            }
            int end = colon + 1;
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            if (end >= text.length()) {
                return defaultValue;
            }
            if (text.charAt(end) == 't') {
                return true;
            }
            if (text.charAt(end) == 'f') {
                return false;
            }
            return defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // ------------------------------------------------------------------
    // 推理
    // ------------------------------------------------------------------

    /** 执行推理并返回归一化向量。 */
    private List<double[]> run(List<String> texts) throws OrtException {
        BertWordPieceTokenizer currentTokenizer = tokenizer;
        OrtSession currentSession = session;
        if (currentTokenizer == null || currentSession == null) {
            return null;
        }
        int padId = currentTokenizer.idOf("[PAD]");
        if (padId < 0) {
            padId = 0;
        }
        BertWordPieceTokenizer.Encoding encoding = currentTokenizer.encodeBatch(texts, maxSequenceLength, padId);
        int batch = texts.size();
        int length = encoding.inputIds.length / Math.max(1, batch);
        long[] shape = new long[]{batch, length};
        java.util.Map<String, OnnxTensor> inputs = new java.util.LinkedHashMap<>();
        try (OnnxTensor ids = OnnxTensor.createTensor(environment(), LongBuffer.wrap(encoding.inputIds), shape);
             OnnxTensor mask = OnnxTensor.createTensor(environment(), LongBuffer.wrap(encoding.attentionMask), shape);
             OnnxTensor types = OnnxTensor.createTensor(environment(), LongBuffer.wrap(encoding.tokenTypeIds), shape)) {
            inputs.put("input_ids", ids);
            inputs.put("attention_mask", mask);
            inputs.put("token_type_ids", types);
            // 该 ONNX 导出的输出是未命名的（single unnamed output），
            // 故用 run(inputs) 而非 run(inputs, pinnedOutputs)——后者按「输出名」钉选，
            // 对未命名输出会抛 "Unexpected number of requestedOutputs & pinnedOutputs"。
            OrtSession.Result result = currentSession.run(inputs);
            try {
                Object output = result.get(0).getValue();
                if (!(output instanceof float[][][])) {
                    return null;
                }
                float[][][] hidden = (float[][][]) output;
                List<double[]> vectors = new ArrayList<>(batch);
                for (int b = 0; b < batch; b++) {
                    vectors.add(poolAndNormalize(hidden[b], encoding, b, length));
                }
                return vectors;
            } finally {
                result.close();
            }
        }
    }

    /**
     * 均值池化 + L2 归一化（对齐 sentence-transformers 的 {@code 1_Pooling} 配置
     * {@code pooling_mode_mean_tokens=true}，以及 SQLBot 的 {@code normalize_embeddings=True}）。
     *
     * @param hidden  该条样本的逐 token 隐层向量（长度 = length）
     * @param encoding 批量编码结果（展平为 {@code batch * length}）
     * @param batchIndex 该条样本在批量中的下标（用于定位 attention_mask 段）
     * @param length 单条序列长度
     */
    private static double[] poolAndNormalize(float[][] hidden, BertWordPieceTokenizer.Encoding encoding,
                                             int batchIndex, int length) {
        int dim = hidden.length > 0 && hidden[0] != null && hidden[0].length > 0 ? hidden[0].length : 0;
        double[] pooled = new double[dim];
        int count = 0;
        int base = batchIndex * length;
        for (int t = 0; t < length; t++) {
            int maskIndex = base + t;
            if (maskIndex >= encoding.attentionMask.length) {
                break;
            }
            if (encoding.attentionMask[maskIndex] == 0L) {
                continue;
            }
            float[] tokenVector = hidden[t];
            if (tokenVector == null) {
                continue;
            }
            for (int d = 0; d < dim; d++) {
                pooled[d] += tokenVector[d];
            }
            count++;
        }
        if (count == 0) {
            return pooled;
        }
        double norm = 0D;
        for (int d = 0; d < dim; d++) {
            pooled[d] = pooled[d] / count;
            norm += pooled[d] * pooled[d];
        }
        norm = Math.sqrt(norm);
        if (norm > 0D) {
            for (int d = 0; d < dim; d++) {
                pooled[d] = pooled[d] / norm;
            }
        }
        return pooled;
    }

    private static OrtEnvironment environment() {
        return OrtEnvironment.getEnvironment();
    }
}
