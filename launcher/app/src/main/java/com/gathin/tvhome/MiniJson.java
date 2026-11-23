package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the launcher's small files and the wallpaper sources' replies, in plain Java so the parsing
 * is unit-tested on the JVM (Android's org.json is a stub there): objects as Maps, arrays as Lists, strings, numbers
 * as Double, true/false as Boolean, null. Anything malformed throws IllegalArgumentException; {@link #parse} turns
 * that into null.
 */
final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    /** The value in {@code text}, or null when it is missing or not JSON. Never throws. */
    static Object parse(String text) {
        if (text == null) return null;
        try {
            MiniJson r = new MiniJson(text);
            Object v = r.value();
            r.space();
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- reading helpers for the parsed tree ------------------------------------------------------------

    static Map<?, ?> obj(Object o, String key) {
        Object v = o instanceof Map ? ((Map<?, ?>) o).get(key) : null;
        return v instanceof Map ? (Map<?, ?>) v : null;
    }

    static List<?> list(Object o, String key) {
        Object v = o instanceof Map ? ((Map<?, ?>) o).get(key) : null;
        return v instanceof List ? (List<?>) v : null;
    }

    static String str(Object o, String key) {
        Object v = o instanceof Map ? ((Map<?, ?>) o).get(key) : null;
        return v instanceof String ? (String) v : v instanceof Double ? num((Double) v) : null;
    }

    static double number(Object o, String key, double fallback) {
        Object v = o instanceof Map ? ((Map<?, ?>) o).get(key) : null;
        if (v instanceof Double) return (Double) v;
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String num(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ---- parser -----------------------------------------------------------------------------------------

    private Object value() {
        space();
        if (i >= s.length()) throw new IllegalArgumentException("end");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new IllegalArgumentException("value at " + i);
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("number");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new HashMap<>();
        i++;
        space();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            m.put(key, value());
            space();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("object");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        space();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(value());
            space();
            char c = next();
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("array");
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new IllegalArgumentException("escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: b.append(e);
            }
        }
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : 0;
    }

    private char next() {
        if (i >= s.length()) throw new IllegalArgumentException("end");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("expected " + c);
    }
}
