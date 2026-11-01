package dev.patodois.rewritelink;

import java.lang.management.ManagementFactory;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

/**
 * Shared helpers of the fuzz targets: a time and allocation budget around a call (Jazzer reports a crash, a timeout
 * after twenty minutes and an out-of-memory error, but not a call that is merely far too slow or allocates far too
 * much), and strict UTF-8 decoding as the control channel does it.
 */
final class Guard {
    private Guard() {}

    private static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** The first calls load classes and warm up the JIT, and allocate a lot for it: they are not measured. */
    private static final int WARM_UP_CALLS = 3000;
    private static int calls;

    interface Body {
        void run() throws Exception;
    }

    /**
     * Runs {@code body} on this thread and fails if it took more than {@code maxMs} or allocated more than
     * {@code maxBytes} on this thread. Exceptions pass through unchanged.
     */
    static void within(String what, long maxMs, long maxBytes, Body body) throws Exception {
        final boolean measure = ++calls > WARM_UP_CALLS;
        Throwable thrown = null;
        long[] m = new long[2];
        try {
            m = timed(body);
        } catch (Throwable t) {
            thrown = t;
            m = lastMeasure;
        }
        if (measure && (m[0] > maxMs || m[1] > maxBytes)) {
            // A one-off (a class loaded, a GC pause, another JVM busy on the same core) is not a finding: run the same call
            // again; the bodies given here are pure, and a call that really is that slow or that big does it again.
            try {
                timed(body);
            } catch (Throwable ignored) {
                // the same outcome as the first time
            }
            long[] again = lastMeasure;
            if (again[0] > maxMs) throw new AssertionError(what + " took " + again[0] + " ms (budget " + maxMs + " ms)");
            if (again[1] > maxBytes) throw new AssertionError(what + " allocated " + again[1] + " bytes (budget " + maxBytes + ")");
        }
        if (thrown instanceof Exception) throw (Exception) thrown;
        if (thrown instanceof Error) throw (Error) thrown;
    }

    /** {milliseconds, bytes allocated} of the last call of {@link #timed}, also when it threw. */
    private static long[] lastMeasure = new long[2];

    private static long[] timed(Body body) throws Exception {
        long id = Thread.currentThread().getId();
        long a0 = MX.getThreadAllocatedBytes(id);
        long t0 = System.nanoTime();
        try {
            body.run();
        } finally {
            lastMeasure = new long[] {(System.nanoTime() - t0) / 1_000_000L, MX.getThreadAllocatedBytes(id) - a0};
        }
        return lastMeasure;
    }

    /** The bytes as a control line would see them, or null when they are not UTF-8 (the link refuses those before parsing). */
    static String strictUtf8(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Whether {@code haystack} contains {@code needle}, for a needle long enough to be meaningful. */
    static boolean leaks(String haystack, String needle) {
        return haystack != null && needle != null && needle.length() >= 8 && haystack.contains(needle);
    }
}
