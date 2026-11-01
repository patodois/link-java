package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.eq;
import static dev.patodois.rewritelink.LinkTests.test;

/**
 * Regression tests for what the Jazzer targets in {@code fuzz/} found (see CLOUD-REPORT.md). Each one fails on the code
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
            // A line of 65000 CR characters cost 0.3 to 3.5 seconds: each one was removed by copying the whole line. The
            // fixed code takes well under a millisecond; the best of three runs must stay under 100 ms.
            for (char c : new char[] {'\r', '\n'}) {
                String line = "{\"type\":\"bye\",\"reason\":\"x\"}" + repeat(c, Wire.MAX_LINE - 100);
                long best = Long.MAX_VALUE;
                for (int i = 0; i < 3; i++) {
                    long t0 = System.nanoTime();
                    Control msg = Control.parse(line);
                    best = Math.min(best, (System.nanoTime() - t0) / 1_000_000L);
                    eq(msg, new Control.Bye("x"), "the line parses to the same message");
                }
                check(best < 100, "parsing a line with " + (Wire.MAX_LINE - 100) + " trailing line endings took " + best + " ms");
            }
            eq(Control.parse("{\"type\":\"bye\"}\r\n\r\n"), new Control.Bye(""), "mixed endings are still stripped");
            eq(Control.parse("{\"type\":\"bye\"}"), new Control.Bye(""), "no ending");
        });
    }
}
