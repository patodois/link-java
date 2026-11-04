package dev.patodois.rewritelink;

import java.nio.charset.StandardCharsets;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import dev.patodois.rewritelink.Wire.WireException;

/**
 * Fuzzes the decoder of the control channel (CONTRACT.md 8): {@link Control#parse} and the JSON reader under it,
 * on arbitrary text and on hello lines that carry a token. Allowed outcome: a message, or a {@link WireException}.
 */
class ControlFuzz {
    /** A token no other string in the harness contains. */
    static final String MARK = "SECRET☃";

    @FuzzTest(maxDuration = "30m")
    void parseLine(byte[] data) throws Exception {
        String line = Guard.strictUtf8(data);
        if (line == null || line.length() >= Wire.MAX_LINE + 2) return;
        check(line, null);
    }

    /** Hello lines with a token (and the fields around it corrupted): the token must not reach any message. */
    @FuzzTest(maxDuration = "30m")
    void parseHelloWithToken(FuzzedDataProvider d) throws Exception {
        String token = MARK + d.consumeString(40);
        StringBuilder b = new StringBuilder("{");
        String[][] fields = {
            {"type", "\"hello\""}, {"role", "\"guest\""}, {"game", "\"g\""}, {"protocol", "1"}, {"min_protocol", "1"},
            {"udp_port", "5"}, {"heartbeat_ms", "250"}, {"timeout_ms", "2000"},
        };
        boolean first = true;
        for (String[] f : fields) {
            int act = d.consumeInt(0, 9);
            if (act == 0) continue; // missing
            String value = act == 1 ? d.consumeString(12) : act == 2 ? "-1" : act == 3 ? "4294967296" : act == 4 ? "1.5" : f[1];
            if (!first) b.append(',');
            first = false;
            b.append('"').append(f[0]).append("\":").append(value);
        }
        if (!first) b.append(',');
        int tokenForm = d.consumeInt(0, 4);
        String tokenValue = tokenForm == 0 ? Json.quote(token) : tokenForm == 1 ? "\"" + token.replace("☃", "\\u2603") + "\""
                : tokenForm == 2 ? Json.quote(token) + d.consumeString(6) : tokenForm == 3 ? "[" + Json.quote(token) + "]" : Json.quote(token + "\\u0000");
        b.append("\"token\":").append(tokenValue);
        if (d.consumeBoolean()) b.append(',').append(d.consumeRemainingAsString());
        b.append('}');
        check(b.toString(), token);
    }

    private static void check(String line, String secret) throws Exception {
        final Control[] out = new Control[1];
        try {
            Guard.within("Control.parse", 250, 128L << 20, () -> out[0] = Control.parse(line));
        } catch (WireException e) {
            if (Guard.leaks(e.getMessage(), secret)) throw new AssertionError("token in a WireException message: " + e.getMessage());
            return;
        }
        Control c = out[0];
        if (c == null) throw new AssertionError("parse returned null");
        String shown = c.toString();
        if (Guard.leaks(shown, secret)) throw new AssertionError("token in toString: " + shown);
        if (c instanceof Control.Hello && ((Control.Hello) c).token() != null && !shown.contains("token=<redacted>")) {
            throw new AssertionError("a hello with a token shows it: " + shown);
        }
        // What the library writes, it reads back as the same message.
        String again = c.toLine();
        if (!again.endsWith("\n") || again.indexOf('\n') != again.length() - 1) throw new AssertionError("toLine is not one line");
        if (again.getBytes(StandardCharsets.UTF_8).length > 4 * Wire.MAX_LINE) throw new AssertionError("toLine is huge");
        Control back;
        try {
            back = Control.parse(again);
        } catch (WireException e) {
            if (again.length() >= Wire.MAX_LINE) return; // an escape can make a line longer than the one it came from
            throw new AssertionError("toLine of a parsed message does not parse: " + again, e);
        }
        if (!back.equals(c)) throw new AssertionError("round trip changed the message: " + c + " -> " + back);
    }
}
