package cp;

import java.lang.reflect.Array;
import java.util.Map;

/** Small JSON writer: never serializes arbitrary objects or non-finite numbers. */
final class Json {
    private Json() { }
    static String encode(Object v) {
        StringBuilder b = new StringBuilder(); append(b, v); return b.toString();
    }
    private static void append(StringBuilder b, Object v) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String) { quote(b, (String) v); return; }
        if (v instanceof Boolean) { b.append(v); return; }
        if (v instanceof Number) {
            double n = ((Number) v).doubleValue();
            b.append(Double.isNaN(n) || Double.isInfinite(n) ? "null" : v.toString()); return;
        }
        if (v instanceof Map) {
            b.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(','); first = false;
                quote(b, (String) e.getKey()); b.append(':'); append(b, e.getValue());
            }
            b.append('}'); return;
        }
        if (v.getClass().isArray()) {
            b.append('[');
            for (int i = 0; i < Array.getLength(v); i++) {
                if (i != 0) b.append(','); append(b, Array.get(v, i));
            }
            b.append(']'); return;
        }
        throw new IllegalArgumentException("unsupported_value_type");
    }
    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 32 || Character.isSurrogate(c)) {
                        String h = Integer.toHexString(c); b.append("\\u");
                        for (int j = h.length(); j < 4; j++) b.append('0'); b.append(h);
                    } else b.append(c);
            }
        }
        b.append('"');
    }
    /** Defensive bounded content copy; vendor objects are never retained. */
    static Object copy(Object v) {
        return copy(v, 0, new int[]{512, 8192});
    }
    private static Object copy(Object v, int depth, int[] budget) {
        if (--budget[0] < 0) throw new IllegalArgumentException("value_too_large");
        if (v == null || v instanceof String || v instanceof Boolean
                || v instanceof Byte || v instanceof Short || v instanceof Integer
                || v instanceof Long || v instanceof Float || v instanceof Double) {
            if (v instanceof Float || v instanceof Double) {
                double number = ((Number) v).doubleValue();
                if (Double.isNaN(number) || Double.isInfinite(number))
                    throw new IllegalArgumentException("non_finite_value");
            }
            if (v instanceof String) {
                int length = ((String) v).length(); budget[1] -= length;
                if (length > 4096 || budget[1] < 0) throw new IllegalArgumentException("value_too_large");
            }
            return v;
        }
        if (v.getClass().isArray() && depth < 2) {
            int n = Array.getLength(v);
            if (n > 256) throw new IllegalArgumentException("value_too_large");
            Object[] result = new Object[n];
            for (int i = 0; i < n; i++) result[i] = copy(Array.get(v, i), depth + 1, budget);
            return result;
        }
        throw new IllegalArgumentException("unsupported_value_type");
    }
}
