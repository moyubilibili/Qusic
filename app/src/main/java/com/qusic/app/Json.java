package com.qusic.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（零依赖）。
 *
 * <p>只做「解析成 Map / List / String / Double / Boolean / null」这一件事，
 * 足够解析网易云返回的嵌套结构。不追求性能，够用且不引第三方库。
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    /**
     * 把一个字符串安全地转成 JSON 字符串字面量（含首尾引号）。
     *
     * <p>拼 JSON 请求体时必须用它，否则用户输入里的引号、换行、反斜杠
     * 会把结构破坏掉（既能造成请求失败，也是注入面）。
     */
    public static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static Object parse(String text) {
        if (text == null) return null;
        Json j = new Json(text);
        j.ws();
        try {
            return j.value();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 便捷取值：按路径逐层取（数字段用 String 也可以） */
    @SuppressWarnings("unchecked")
    public static Object path(Object root, Object... keys) {
        Object cur = root;
        for (Object k : keys) {
            if (cur == null) return null;
            if (k instanceof Integer) {
                if (!(cur instanceof List)) return null;
                List<Object> l = (List<Object>) cur;
                int idx = (Integer) k;
                if (idx < 0 || idx >= l.size()) return null;
                cur = l.get(idx);
            } else {
                if (!(cur instanceof Map)) return null;
                cur = ((Map<String, Object>) cur).get(String.valueOf(k));
            }
        }
        return cur;
    }

    public static String str(Object root, Object... keys) {
        Object v = path(root, keys);
        return v == null ? null : String.valueOf(v);
    }

    public static long lng(Object root, Object... keys) {
        Object v = path(root, keys);
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Throwable t) { return 0; }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object root, Object... keys) {
        Object v = path(root, keys);
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    // ── 递归下降 ────────────────────────────────────────────────────────────
    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private Object value() {
        ws();
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return str();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return num();
        }
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;   // {
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (i < s.length()) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') break;
            String k = str();
            ws();
            if (i < s.length() && s.charAt(i) == ':') i++;
            m.put(k, value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == '}') { i++; break; }
            break;
        }
        return m;
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++;   // [
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (i < s.length()) {
            l.add(value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == ']') { i++; break; }
            break;
        }
        return l;
    }

    private String str() {
        StringBuilder sb = new StringBuilder();
        i++;   // 开引号
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (Throwable ignored) {}
                            i += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object num() {
        int st = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                    || c == 'e' || c == 'E') i++;
            else break;
        }
        if (i == st) { i++; return null; }
        String t = s.substring(st, i);
        try {
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                return Long.parseLong(t);
            }
            return Double.parseDouble(t);
        } catch (Throwable e) {
            return null;
        }
    }
}
