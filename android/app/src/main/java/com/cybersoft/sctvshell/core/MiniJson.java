package com.cybersoft.sctvshell.core;

import java.util.*;

/** A small JSON reader and writer (objects, arrays, strings, numbers, booleans, null), so the update logic has no Android dependency. */
public final class MiniJson {
    private final String s; private int i;
    private MiniJson(String s) { this.s = s; }
    public static Object parse(String text) { MiniJson p = new MiniJson(text); Object v = p.value(); p.ws(); if (p.i != p.s.length()) throw new IllegalArgumentException("trailing text at " + p.i); return v; }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private Object value() {
        ws(); if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(i);
        if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (s.charAt(i) == '}') { i++; return m; }
            while (true) { ws(); String k = str(); ws(); expect(':'); m.put(k, value()); ws(); if (s.charAt(i) == ',') { i++; continue; } expect('}'); return m; } }
        if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(i) == ']') { i++; return l; }
            while (true) { l.add(value()); ws(); if (s.charAt(i) == ',') { i++; continue; } expect(']'); return l; } }
        if (c == '"') return str();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new IllegalArgumentException("bad value at " + i);
        return Double.parseDouble(s.substring(st, i));
    }
    private void expect(char c) { if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i); i++; }
    private String str() {
        expect('"'); StringBuilder b = new StringBuilder();
        while (true) { char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') { char e = s.charAt(i++);
                switch (e) { case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break; case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break; default: b.append(e); } }
            else b.append(c); }
    }

    public static String stringify(Object v) { StringBuilder b = new StringBuilder(); write(b, v); return b.toString(); }
    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof String) { b.append('"'); for (char c : ((String) v).toCharArray()) { if (c == '"' || c == '\\') b.append('\\').append(c); else if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); } b.append('"'); }
        else if (v instanceof Map) { b.append('{'); boolean f = true; for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) { if (!f) b.append(','); f = false; write(b, e.getKey()); b.append(':'); write(b, e.getValue()); } b.append('}'); }
        else if (v instanceof Collection) { b.append('['); boolean f = true; for (Object o : (Collection<Object>) v) { if (!f) b.append(','); f = false; write(b, o); } b.append(']'); }
        else if (v instanceof Double && ((Double) v) == Math.floor((Double) v) && !Double.isInfinite((Double) v)) b.append(((Double) v).longValue());
        else b.append(v.toString());
    }
}
