package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.eq;
import static dev.patodois.rewritelink.LinkTests.test;

import java.math.BigInteger;

/**
 * Regression tests for robustness fixes: inputs and calls that used to cost too much, fail silently or reach the peer
 * as values the wire cannot carry. Each test fails on the code before its fix.
 */
final class HardeningTests {
    private HardeningTests() {}

    static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    static void run() {
        test("json: an integer literal longer than 309 digits is refused by its length, as before", () -> {
            // Lengths around the boundary: the same accept/reject set as computing the BigInteger and asking whether it
            // is finite as a double.
            for (int digits = 300; digits <= 320; digits++) {
                for (String body : new String[] {repeat('9', digits), "1" + repeat('0', digits - 1), "17976931348623157" + repeat('0', Math.max(0, digits - 17))}) {
                    if (body.length() != digits) continue;
                    for (String t : new String[] {body, "-" + body}) {
                        boolean finite = !Double.isInfinite(new BigInteger(t).doubleValue());
                        boolean accepted;
                        try {
                            Object v = Json.parse(t);
                            accepted = true;
                            eq(v, new BigInteger(t), "value of a " + digits + "-digit integer");
                        } catch (Json.JsonException e) {
                            accepted = false;
                            eq(e.getMessage(), "number out of range", "message for a " + digits + "-digit integer");
                        }
                        eq(accepted, finite, "a " + digits + "-digit integer starting " + t.substring(0, 3) + " accepted");
                    }
                }
            }
        });

        test("json: a 65500-digit integer in a control line is refused in bounded time", () -> {
            // Building the BigInteger is quadratic in the digits: such a line took about 45 ms per parse before the length
            // check, on a recent laptop, and well under a millisecond after it. Twenty parses per round, best of three
            // rounds, must stay under 200 ms: ten times the old cost of one parse, a hundred times the new cost of twenty.
            String line = "{\"type\":\"hello\",\"x\":" + repeat('9', 65500) + "}";
            check(line.length() < Wire.MAX_LINE, "the line fits a control line");
            long best = Long.MAX_VALUE;
            for (int round = 0; round < 3; round++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < 20; i++) {
                    try {
                        Control.parse(line);
                        throw new AssertionError("a 65500-digit integer was accepted");
                    } catch (Wire.WireException expected) {
                        check(expected.getMessage().contains("number out of range"), "message: " + expected.getMessage());
                    }
                }
                best = Math.min(best, (System.nanoTime() - t0) / 1_000_000L);
            }
            System.out.println("     twenty parses of a 65500-digit integer: " + best + " ms (best of three)");
            check(best < 200, "twenty parses of a 65500-digit integer took " + best + " ms");
        });
    }
}
