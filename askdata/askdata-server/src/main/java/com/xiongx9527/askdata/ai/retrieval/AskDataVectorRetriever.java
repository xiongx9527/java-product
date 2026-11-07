package com.xiongx9527.askdata.ai.retrieval;

import com.xiongx9527.askdata.ai.Embedder;
import com.xiongx9527.fw.plus.ai.base.retrieval.DocumentRetriever;
import com.xiongx9527.fw.plus.ai.base.retrieval.Retrieved;
import com.xiongx9527.fw.plus.ai.base.retrieval.RetrievalQuery;
import com.xiongx9527.fw.plus.ai.base.store.vector.Document;
import com.xiongx9527.fw.plus.ai.base.store.vector.ScoredDocument;
import com.xiongx9527.fw.plus.ai.base.store.vector.VectorSearchRequest;
import com.xiongx9527.fw.plus.ai.base.store.vector.VectorSearchResult;
import com.xiongx9527.fw.plus.ai.base.store.vector.VectorStorePort;

import java.util.ArrayList;
import java.util.List;

/**
 * 产品向量召回器：产品 embedding 算向量 + 框架 {@link VectorStorePort} 最近邻检索，
 * 把命中转成框架 {@link Retrieved}（仅含 id 与元数据，内容由调用方按 id 回查 DB）。
 *
 * <p>阈值过滤与 TopN 截断不在此处做，交给框架 {@code RetrievalPipeline}
 * （{@code ThresholdRetriever} + {@code TopNRetriever}），与 Dify 语义一致。</p>
 */
public class AskDataVectorRetriever implements DocumentRetriever {

    private final Embedder embedder;

    private final VectorStorePort store;

    public AskDataVectorRetriever(Embedder embedder, VectorStorePort store) {
        this.embedder = embedder;
        this.store = store;
    }

    @Override
    public List<Retrieved<Document>> retrieve(RetrievalQuery query) {
        List<Retrieved<Document>> result = new ArrayList<Retrieved<Document>>();
        if (query == null || query.getText() == null || query.getText().trim().isEmpty() || store == null) {
            return result;
        }
        double[] vector = embedder.embed(query.getText());
        if (vector == null) {
            return result;
        }
        VectorSearchResult search = store.search(VectorSearchRequest.builder()
                .collection(query.getCollection())
                .vector(vector)
                .topK(query.getTopK())
                .candidateIds(query.getCandidateIds())
                .build());
        if (search == null || search.isEmpty()) {
            return result;
        }
        String source = query.getCollection();
        for (ScoredDocument hit : search.getHits()) {
            if (hit == null || hit.getDocument() == null) {
                continue;
            }
            Document doc = Document.builder()
                    .id(hit.getDocument().getId())
                    .metadata(hit.getDocument().getMetadata())
                    .build();
            result.add(Retrieved.of(doc, hit.getScore(), source));
        }
        return result;
    }
}
