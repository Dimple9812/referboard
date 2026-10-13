package com.referboard.analyzer.scoring;

import com.referboard.ai.EmbeddingClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-analysis memo over an {@link EmbeddingClient}: re-scoring a resume with one bullet changed (to measure a
 * suggestion's impact) embeds only that bullet. Not thread-safe; one instance per analysis.
 */
public final class CachingEmbeddings implements EmbeddingClient {

    private final EmbeddingClient delegate;
    private final Map<String, float[]> cache = new HashMap<>();

    public CachingEmbeddings(EmbeddingClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<String> missing = texts.stream().filter(t -> !cache.containsKey(t)).distinct().toList();
        if (!missing.isEmpty()) {
            List<float[]> vectors = delegate.embedAll(missing);
            for (int i = 0; i < missing.size(); i++) {
                cache.put(missing.get(i), vectors.get(i));
            }
        }
        List<float[]> out = new ArrayList<>(texts.size());
        texts.forEach(t -> out.add(cache.get(t)));
        return out;
    }
}
