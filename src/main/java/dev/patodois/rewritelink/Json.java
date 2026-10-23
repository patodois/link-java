package dev.patodois.rewritelink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON reader and string writer, enough for the control lines. Objects become
 * {@code Map<String,Object>}, arrays {@code List<Object>}, integers {@code Long} (or
 * {@code java.math.BigInteger} when they do not fit), other numbers {@code Double}.
 */
final class Json {
    private Json() {}

    static final class JsonException extends Exception {
        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message);
        }
    }

    static Object parse(String s) throws JsonException {
        Reader r = new Reader(s);
        r.ws();
        Object v = r.value(0);
        r.ws();
        if (r.i != s.length()) throw new JsonException("trailing characters");
        return v;
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object value(int depth) throws JsonException {
            if (depth > 32) throw new JsonException("nested too deeply");
            if (i >= s.length()) throw new JsonException("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw new JsonException("unexpected character '" + c + "'");
            }
        }

        void literal(String lit) throws JsonException {
            if (!s.startsWith(lit, i)) throw new JsonException("bad literal");
            i += lit.length();
        }

        Object number() throws JsonException {
            int start = i;
            boolean integer = true;
            if (s.charAt(i) == '-') i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integer = false;
                    i++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, i);
            try {
                if (integer) {
                    try {
                        return Long.parseLong(t);
                    } catch (NumberFormatException e) {
                        return new java.math.BigInteger(t);
                    }
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw new JsonException("bad number");
            }
        }

        String string() throws JsonException {
            i++; // opening quote
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new JsonException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c < 0x20) throw new JsonException("control character in string");
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) throw new JsonException("unterminated escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                        b.append('"');
                        break;
                    case '\\':
                        b.append('\\');
                        break;
                    case '/':
                        b.append('/');
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) throw new JsonException("bad \\u escape");
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("bad \\u escape");
                        }
                        i += 4;
                        break;
                    default:
                        throw new JsonException("bad escape");
                }
            }
        }

        List<Object> array(int depth) throws JsonException {
            i++;
            List<Object> out = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return out;
            }
            while (true) {
                ws();
                out.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw new JsonException("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return out;
                if (c != ',') throw new JsonException("expected , or ]");
            }
        }

        Map<String, Object> object(int depth) throws JsonException {
            i++;
            Map<String, Object> out = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return out;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw new JsonException("expected key");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i++) != ':') throw new JsonException("expected :");
                ws();
                out.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) throw new JsonException("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return out;
                if (c != ',') throw new JsonException("expected , or }");
            }
        }
    }
}
