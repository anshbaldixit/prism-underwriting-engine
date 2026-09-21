package dev.anshdixit.prism.ai;

/** pgvector literal encoding: {@code [0.1,0.2,...]}. Kept in one place so every native query agrees. */
public final class VectorFormat {

    private VectorFormat() {
    }

    public static String toLiteral(float[] v) {
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
