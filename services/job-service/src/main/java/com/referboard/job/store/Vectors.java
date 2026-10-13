package com.referboard.job.store;

/** pgvector text literal helpers ({@code [0.1,0.2,...]}). */
public final class Vectors {

    private Vectors() {
    }

    public static String literal(float[] v) {
        if (v == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
