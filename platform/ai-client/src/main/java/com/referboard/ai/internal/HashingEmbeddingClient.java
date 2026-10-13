package com.referboard.ai.internal;

import com.referboard.ai.EmbeddingClient;
import com.referboard.skills.SkillTaxonomy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline embedding: signed feature hashing of word unigrams/bigrams plus canonical skills (weighted ×3), then
 * L2-normalised. No semantics beyond shared vocabulary, but deterministic, free, and good enough for local dev
 * and tests. Model id {@code hashing-v1:<dims>} keeps these vectors apart from real ones.
 */
public class HashingEmbeddingClient implements EmbeddingClient {

    private static final double SKILL_WEIGHT = 3.0;

    private final int dimensions;
    private final SkillTaxonomy skills;

    public HashingEmbeddingClient(int dimensions, SkillTaxonomy skills) {
        this.dimensions = dimensions;
        this.skills = skills;
    }

    @Override
    public String model() {
        return "hashing-v1:" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        double[] acc = new double[dimensions];
        if (text != null && !text.isBlank()) {
            String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}+#.]+");
            String previous = null;
            for (String raw : words) {
                String w = raw.replaceAll("^\\.+|\\.+$", "");
                if (w.length() < 2) {
                    previous = null;
                    continue;
                }
                add(acc, "u:" + w, 1.0);
                if (previous != null) {
                    add(acc, "b:" + previous + " " + w, 0.5);
                }
                previous = w;
            }
            for (String skill : skills.extract(text)) {
                add(acc, "s:" + skill, SKILL_WEIGHT);
            }
        }
        double norm = 0;
        for (double v : acc) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        float[] out = new float[dimensions];
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) {
                out[i] = (float) (acc[i] / norm);
            }
        }
        return out;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        texts.forEach(t -> out.add(embed(t)));
        return out;
    }

    private void add(double[] acc, String feature, double weight) {
        int h = murmurMix(feature);
        int index = Math.floorMod(h, dimensions);
        double sign = ((h >>> 31) & 1) == 0 ? 1 : -1;
        acc[index] += sign * weight;
    }

    /** FNV-1a then a murmur3 finalizer: cheap, well-spread, stable across JVMs (unlike String.hashCode seeds). */
    private static int murmurMix(String s) {
        int h = 0x811c9dc5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b;
            h *= 0x01000193;
        }
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
