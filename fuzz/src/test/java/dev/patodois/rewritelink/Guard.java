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
        long id = Thread.currentThread().getId();
        long a0 = MX.getThreadAllocatedBytes(id);
        long t0 = System.nanoTime();
        try {
            body.run();
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            long bytes = MX.getThreadAllocatedBytes(id) - a0;
            if (measure && ms > maxMs) throw new AssertionError(what + " took " + ms + " ms (budget " + maxMs + " ms)");
            if (measure && bytes > maxBytes) {
                // A one-off (a class loaded, a lazily built table) is not a finding: run the same call again; the bodies
                // given here are pure, and a call that really allocates that much does it again.
                long a1 = MX.getThreadAllocatedBytes(id);
                try {
                    body.run();
                } catch (Exception ignored) {
                    // the same outcome as the first time
                }
                long again = MX.getThreadAllocatedBytes(id) - a1;
                if (again > maxBytes) throw new AssertionError(what + " allocated " + again + " bytes (budget " + maxBytes + ")");
            }
        }
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
