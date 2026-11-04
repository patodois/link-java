package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.eq;
import static dev.patodois.rewritelink.LinkTests.test;

/**
 * Regression tests for what the Jazzer targets in {@code fuzz/} found. Each one fails on the code
 * the fuzzing was run against and passes on the fix.
 */
final class FuzzFindingTests {
    private FuzzFindingTests() {}

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    static void run() {
        test("fuzz: Control.parse strips a long run of trailing CR and LF in linear time", () -> {
            // A line of 65000 CR characters cost 0.3 to 3.5 seconds: each one was removed by copying the whole line. On a
            // fast machine that run still fits in 100 ms, so the test uses four times the longest line the socket reader
            // passes on (Wire.MAX_LINE): the old code copies sixteen times as much and takes well over a second, the fixed
            // code well under a millisecond. The best of three runs must stay under 100 ms.
            int run = 4 * Wire.MAX_LINE;
            for (char c : new char[] {'\r', '\n'}) {
                String line = "{\"type\":\"bye\",\"reason\":\"x\"}" + repeat(c, run);
                long best = Long.MAX_VALUE;
                for (int i = 0; i < 3; i++) {
                    long t0 = System.nanoTime();
                    Control msg = Control.parse(line);
                    best = Math.min(best, (System.nanoTime() - t0) / 1_000_000L);
                    eq(msg, new Control.Bye("x"), "the line parses to the same message");
                }
                check(best < 100, "parsing a line with " + run + " trailing line endings took " + best + " ms");
            }
            eq(Control.parse("{\"type\":\"bye\"}\r\n\r\n"), new Control.Bye(""), "mixed endings are still stripped");
            eq(Control.parse("{\"type\":\"bye\"}"), new Control.Bye(""), "no ending");
        });
    }
}
