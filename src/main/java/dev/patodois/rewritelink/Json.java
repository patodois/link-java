package dev.patodois.rewritelink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON reader and string writer, enough for the control lines. Objects become
 * {@code Map<String,Object>}, arrays {@code List<Object>}, integers {@code Long} (or
 * {@code java.math.BigInteger} when they do not fit), other numbers {@code Double}.
 *
 * <p>The reader accepts exactly what the Rust side's {@code serde_json} accepts, because a control
 * line that one implementation takes and the other refuses is a way to make them disagree: numbers
 * follow the JSON grammar (no leading zeros, no {@code 1.}, no {@code +1}), {@code -0} is the
 * float negative zero (so it is no integer), a number beyond the range of a double is an error,
 * a unicode escape takes exactly four hex digits and surrogate escapes must come as a valid pair, a
 * NUL escape (backslash, {@code u}, four zeros) is an error in any string, key or value (CONTRACT 8,
 * revision 5: it is refused wherever it is, also in an unknown field and in a value that a later
 * duplicate key overrides), and
 * arrays and objects may nest {@link #MAX_NESTING} deep.
 */
final class Json {
    private Json() {}

    /** serde_json's recursion limit: 128 nested arrays or objects fail, 127 do not. */
    static final int MAX_NESTING = 127;

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
            if (i >= s.length()) throw new JsonException("unexpected end");
            char c = s.charAt(i);
            if ((c == '{' || c == '[') && depth >= MAX_NESTING) throw new JsonException("nested too deeply");
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

        boolean digit() {
            return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
        }

        /** number = [-] (0 | 1-9 digits) [. digits] [(e|E) [+|-] digits], as in RFC 8259. */
        Object number() throws JsonException {
            int start = i;
            boolean integer = true;
            if (s.charAt(i) == '-') i++;
            if (!digit()) throw new JsonException("bad number");
            if (s.charAt(i) == '0') {
                i++; // a leading zero ends the integer part: "01" leaves the 1 behind, which no caller accepts
            } else {
                while (digit()) i++;
            }
            if (i < s.length() && s.charAt(i) == '.') {
                integer = false;
                i++;
                if (!digit()) throw new JsonException("bad number");
                while (digit()) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                integer = false;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (!digit()) throw new JsonException("bad number");
                while (digit()) i++;
            }
            String t = s.substring(start, i);
            if (integer) {
                if (t.equals("-0")) return Double.valueOf(-0.0); // serde_json: a float, so not an integer field
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException e) {
                    java.math.BigInteger big = new java.math.BigInteger(t);
                    if (Double.isInfinite(big.doubleValue())) throw new JsonException("number out of range");
                    return big;
                }
            }
            double d = Double.parseDouble(t);
            if (Double.isInfinite(d)) throw new JsonException("number out of range");
            return d;
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
                    case 'u': {
                        char u = hex4();
                        // CONTRACT 8, "Syntax of a line", 4: U+0000 is an error in any string, key or value (a
                        // consumer with NUL-terminated strings, like the C one, could not hold it).
                        if (u == 0) throw new JsonException("U+0000 in a string");
                        if (Character.isHighSurrogate(u)) {
                            // It must be followed by an escaped low surrogate.
                            if (i + 1 >= s.length() || s.charAt(i) != '\\' || s.charAt(i + 1) != 'u') {
                                throw new JsonException("lone leading surrogate in hex escape");
                            }
                            i += 2;
                            char low = hex4();
                            if (!Character.isLowSurrogate(low)) throw new JsonException("lone leading surrogate in hex escape");
                            b.append(u).append(low);
                        } else if (Character.isLowSurrogate(u)) {
                            throw new JsonException("lone trailing surrogate in hex escape");
                        } else {
                            b.append(u);
                        }
                        break;
                    }
                    default:
                        throw new JsonException("bad escape");
                }
            }
        }

        /** Exactly four hex digits, ASCII only ({@code Character.digit} would take other scripts' digits). */
        char hex4() throws JsonException {
            if (i + 4 > s.length()) throw new JsonException("bad \\u escape");
            int v = 0;
            for (int k = 0; k < 4; k++) {
                char c = s.charAt(i + k);
                int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                if (d < 0) throw new JsonException("bad \\u escape");
                v = v * 16 + d;
            }
            i += 4;
            return (char) v;
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
