package com.referboard.ai;

import java.util.List;

/**
 * Text → unit-length vector. Vectors from different {@link #model()}s are not comparable, so every stored
 * embedding records its model id and matching only compares like with like.
 */
public interface EmbeddingClient {

    String model();

    int dimensions();

    float[] embed(String text);

    List<float[]> embedAll(List<String> texts);

    /** Cosine similarity of two unit vectors (dot product), clamped to [-1, 1]. */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return Math.max(-1, Math.min(1, dot));
    }
}
