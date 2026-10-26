package dev.patodois.rewritelink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;

/**
 * The shared-memory pixel file of CONTRACT.md section 9.6 (revision 3, with the optional depth
 * plane): a {@link Reader} for the receiving side (maps the file read-only) and a {@link Writer}
 * for the sending side. Both use the seqlock of the contract: the writer makes the slot's
 * {@code lock} odd while it writes and even afterwards, the reader copies and checks that the lock did
 * not change.
 */
public final class PixelFile {
    private PixelFile() {}

    public static final int HEADER_LEN = 64;
    public static final int SLOT_HEADER_LEN = 64;
    public static final int FORMAT_RGBA8 = 1;
    public static final int DEPTH_NONE = 0;
    public static final int DEPTH_LINEAR_F32 = 1;
    /** Largest width or height (CONTRACT.md 9.6); a colour plane of 16384 x 16384 is 1 GiB. */
    private static final int MAX_DIM = Wire.MAX_FRAME_DIM;

    // ------------------------------------------------------------------ the seqlock on Java 8
    //
    // CONTRACT 9.6 asks for atomic 64-bit access to `lock` with acquire/release ordering and for
    // release/acquire fences around the slot data. Java 9 has VarHandle for that; this library
    // targets Java 8 (Minecraft 1.7.10 and 1.12.2), where there is no VarHandle, no public fence
    // API and (by choice) no sun.misc.Unsafe. What Java 8 does give us, used here:
    //
    //  * Atomicity of the 64-bit word: a LongBuffer *view* of the mapping. Its get(i) and put(i, v)
    //    are a single aligned 64-bit access of the mapped memory on every platform. ByteBuffer.getLong
    //    is not used for `lock`: on a platform whose JDK 8 does not list it as "unaligned capable"
    //    it assembles the value byte by byte, which could tear. Every slot, and so every `lock`,
    //    starts at a multiple of 64 bytes from the (page aligned) start of the mapping.
    //
    //  * Ordering: a full fence, built from the one thing Java 8 specifies as a total order,
    //    a volatile read-modify-write (AtomicInteger.incrementAndGet). The JIT treats it as a barrier for
    //    all memory, including the mapped memory (HotSpot: MemBarRelease + the atomic + MemBarAcquire;
    //    x86 `lock xadd`, AArch64 an acquire/release exclusive pair), and the CPU executes a real
    //    barrier. A full fence is stronger than the release fence, acquire fence and release store
    //    the contract names, so every ordering the contract asks for holds:
    //      writer: store lock+1; FENCE; slot data; FENCE; store lock+2; FENCE
    //      reader: l1 = lock; FENCE; copy slot data; FENCE; l2 = lock
    //    The first writer fence keeps the odd store ahead of the data stores, the second keeps the
    //    data stores ahead of the even store (the release store), the third pushes the even store out
    //    before the Frame datagram is sent. The reader's first fence is the acquire of `lock`, the second
    //    the acquire fence of step 3. On Java 8 the cost, two or three atomic operations per frame, is
    //    nothing next to copying the pixels.
    //
    // The same code is used on newer JVMs: the fence is an ordinary atomic there too. The tests run a
    // writer and a reader thread against each other (PixelFileTests) on Java 8 and on 25.

    private static final AtomicInteger FENCE = new AtomicInteger();

    /** A full memory fence (see the note above). */
    static void fence() {
        FENCE.incrementAndGet();
    }

    /** One frame copied out of a slot. {@code depth} is null when the file has no usable depth plane. */
    public static final class Frame {
        private final long frame;
        private final long timeUs;
        private final int cameraSeq;
        private final int width;
        private final int height;
        private final byte[] rgba;
        private final float[] depth;

        public Frame(long frame, long timeUs, int cameraSeq, int width, int height, byte[] rgba, float[] depth) {
            this.frame = frame;
            this.timeUs = timeUs;
            this.cameraSeq = cameraSeq;
            this.width = width;
            this.height = height;
            this.rgba = rgba;
            this.depth = depth;
        }

        public long frame() {
            return frame;
        }

        public long timeUs() {
            return timeUs;
        }

        public int cameraSeq() {
            return cameraSeq;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public byte[] rgba() {
            return rgba;
        }

        public float[] depth() {
            return depth;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Frame)) return false;
            Frame that = (Frame) o;
            return frame == that.frame && timeUs == that.timeUs && cameraSeq == that.cameraSeq
                    && width == that.width && height == that.height
                    && Objects.equals(rgba, that.rgba) && Objects.equals(depth, that.depth);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Long.hashCode(frame);
            h = 31 * h + Long.hashCode(timeUs);
            h = 31 * h + Integer.hashCode(cameraSeq);
            h = 31 * h + Integer.hashCode(width);
            h = 31 * h + Integer.hashCode(height);
            h = 31 * h + Objects.hashCode(rgba);
            h = 31 * h + Objects.hashCode(depth);
            return h;
        }

        @Override
        public String toString() {
            return "Frame[frame=" + frame + ", timeUs=" + timeUs + ", cameraSeq=" + cameraSeq + ", width=" + width
                    + ", height=" + height + ", rgba=" + rgba + ", depth=" + depth + "]";
        }
    }

    /** A file that is not a valid pixel file (wrong magic, impossible sizes...). */
    public static final class FormatException extends IOException {
        private static final long serialVersionUID = 1L;

        public FormatException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ reader

    /** Maps a pixel file read-only. Not thread-safe: use one reader per thread. */
    public static final class Reader implements AutoCloseable {
        private final FileChannel channel;
        private final long mapped; // the file length that was mapped
        private final ByteBuffer map;
        private final LongBuffer locks; // the same mapping as 64-bit words: slot lock i is word (slot start) / 8
        public final int slots, width, height, stride;
        public final long slotSize;
        public final int depthFormat;
        private final int depthStride;
        private final long depthOffset;

        // Diagnostics for the tests: why read() returned null. Not synchronized.
        long skippedBusy, skippedOtherFrame, skippedOverwritten, skippedGone;

        private Reader(FileChannel channel, long mapped, ByteBuffer map, int slots, int width, int height, int stride, long slotSize, int depthFormat,
                       int depthStride, long depthOffset) {
            this.channel = channel;
            this.mapped = mapped;
            this.map = map;
            this.locks = map.asLongBuffer();
            this.slots = slots;
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.slotSize = slotSize;
            this.depthFormat = depthFormat;
            this.depthStride = depthStride;
            this.depthOffset = depthOffset;
        }

        /**
         * True when the file is now shorter than the mapping made of it (the peer truncated it), or
         * the reader is closed. {@link #read} then returns null without touching the mapping, which
         * would fault. This narrows the window but cannot close it: a truncation between the check
         * and the copy still faults (as an {@link InternalError} on HotSpot). A correct sender never
         * truncates a pixel file; this guards against a broken or hostile one.
         */
        public boolean isGone() {
            try {
                return channel.size() < mapped;
            } catch (IOException e) {
                return true;
            }
        }

        /** True when the file carries a depth plane this reader understands (linear f32 metres). */
        public boolean hasDepth() {
            return depthFormat == DEPTH_LINEAR_F32;
        }

        /**
         * Opens and checks the file as section 9.6 says: the path must be absolute and name a regular
         * file (a symlink, FIFO, device or directory is refused), {@code width} and {@code height}
         * are 1 to 16384 with {@code width * height} at most 67108864, {@code stride} is {@code width * 4},
         * and all size arithmetic is checked
         * for overflow. A {@code depth_format} the reader does not know is not an error: the file
         * is read as colour only.
         *
         * <p>The type is checked before the file is opened, because opening a FIFO for reading
         * waits for a writer for ever and Java has no non-blocking open; a peer that swaps the file
         * for a FIFO between the check and the open can still stall this call (the contract asks for
         * {@code O_NONBLOCK} where the language has it).
         */
        public static Reader open(Path path) throws IOException {
            if (!path.isAbsolute()) throw new FormatException("the pixel file path is not absolute: " + path);
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) throw new FormatException("not a regular file: " + path);
            FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
            try {
                long size = ch.size();
                if (size < HEADER_LEN) throw new FormatException("pixel file shorter than its header");
                if (size > Integer.MAX_VALUE) throw new FormatException("pixel file larger than 2 GiB");
                ByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN);
                byte[] magic = new byte[8];
                ByteBuffer head = m.duplicate();
                head.get(magic); // relative get from position 0: the absolute bulk get is Java 13
                if (!"RBFRAME".equals(new String(magic, 0, 7, StandardCharsets.US_ASCII)) || magic[7] != 0) {
                    throw new FormatException("bad pixel file magic");
                }
                int version = m.getInt(8);
                int slots = m.getInt(12), w = m.getInt(16), h = m.getInt(20), stride = m.getInt(24), format = m.getInt(28);
                long slotSize = m.getLong(32);
                if (version != 1) throw new FormatException("pixel file version " + version);
                if (slots < 1 || slots > 16) throw new FormatException("slots " + slots);
                if (w < 1 || h < 1 || w > MAX_DIM || h > MAX_DIM) throw new FormatException("size " + w + "x" + h);
                if ((long) w * h > Wire.MAX_FRAME_PIXELS) throw new FormatException("size " + w + "x" + h + " is over " + Wire.MAX_FRAME_PIXELS + " pixels");
                if (format != FORMAT_RGBA8) throw new FormatException("pixel format " + format);
                if (stride != w * 4) throw new FormatException("stride " + stride + " is not width * 4");
                int depthFormat = m.getInt(40);
                int depthStride = m.getInt(44);
                long depthOffset = m.getLong(48);
                try {
                    // The sizes come from the file, so none of them is trusted: a slot_size picked so
                    // that slots * slot_size wraps would pass an unchecked length test.
                    long colourEnd = Math.addExact((long) SLOT_HEADER_LEN, Math.multiplyExact((long) stride, (long) h));
                    if (slotSize % 64 != 0 || slotSize < colourEnd) throw new FormatException("slot_size " + slotSize);
                    long need = Math.addExact((long) HEADER_LEN, Math.multiplyExact((long) slots, slotSize));
                    if (need > size) throw new FormatException("file is shorter than its slots");
                    if (depthFormat == DEPTH_LINEAR_F32) {
                        if (depthStride != w * 4) throw new FormatException("depth_stride " + depthStride);
                        if (depthOffset % 64 != 0 || depthOffset < colourEnd) throw new FormatException("depth_offset " + depthOffset);
                        long planeEnd = Math.addExact(depthOffset, Math.multiplyExact((long) depthStride, (long) h));
                        if (planeEnd > slotSize) throw new FormatException("depth plane outside its slot");
                    } else if (depthFormat != DEPTH_NONE) {
                        depthFormat = DEPTH_NONE; // unknown format: colour only
                    }
                } catch (ArithmeticException overflow) {
                    throw new FormatException("the sizes in the pixel file header overflow");
                }
                return new Reader(ch, size, m, slots, w, h, stride, slotSize, depthFormat, depthStride, depthOffset);
            } catch (IOException | RuntimeException e) {
                ch.close();
                throw e;
            }
        }

        /**
         * Copies the slot a {@code Frame} datagram announces: {@link #read(long, int, int, int, boolean)} with the
         * datagram's own {@code frame}, {@code slot}, {@code width} and {@code height}. This is the call a
         * receiver makes for every {@link LinkEvent.Frame}.
         */
        public Frame read(Wire.FrameReady ready, boolean wantDepth) {
            return read(ready.frame(), ready.slot(), ready.width(), ready.height(), wantDepth);
        }

        /**
         * Copies slot {@code slot} out if it holds frame {@code frame} and was not written while
         * copying; null otherwise (skip the frame and wait for the next one). {@code width} and
         * {@code height} are the ones the {@code Frame} datagram announced (CONTRACT.md 9.6, revision 5):
         * the frame is skipped, before any pixel is copied, if the datagram's size is 0 or above the
         * file's, if the slot header's size is 0 or above the file's, or if the slot header's size is not
         * the datagram's. The slot need not be {@code frame % slots}: a slot the writer has numbered
         * otherwise is read like any other.
         */
        public Frame read(long frame, int slot, int width, int height, boolean wantDepth) {
            if (width < 1 || height < 1 || width > this.width || height > this.height) return null;
            return readSlot(frame, slot, width, height, wantDepth);
        }

        /**
         * Like {@link #read(long, int, int, int, boolean)} but without the datagram's size: the size is
         * whatever the slot header says (it is still checked against the file's). Prefer the overloads
         * that take the size of the {@code Frame} datagram, which also skip a frame whose slot header and
         * datagram disagree.
         */
        public Frame read(long frame, int slot, boolean wantDepth) {
            return readSlot(frame, slot, 0, 0, wantDepth);
        }

        /** {@code dw} and {@code dh} are the datagram's size, or 0 when the caller has none. */
        private Frame readSlot(long frame, int slot, int dw, int dh, boolean wantDepth) {
            if (slot < 0 || slot >= slots) return null;
            if (isGone()) {
                skippedGone++;
                return null;
            }
            int base = (int) (HEADER_LEN + (long) slot * slotSize);
            long l1 = locks.get(base >> 3); // step 1: l1 = lock, then the acquire (see "the seqlock on Java 8")
            fence();
            if ((l1 & 1) != 0) {
                skippedBusy++;
                return null;
            }
            long f = map.getLong(base + 8), timeUs = map.getLong(base + 16);
            int camSeq = map.getInt(base + 24), w = map.getInt(base + 28), h = map.getInt(base + 32);
            if (f != frame || w < 1 || h < 1 || w > width || h > height || (dw != 0 && (w != dw || h != dh))) {
                skippedOtherFrame++;
                return null;
            }
            byte[] rgba = new byte[w * h * 4];
            ByteBuffer src = map.duplicate(); // a private position, so read() keeps no shared state
            for (int y = 0; y < h; y++) {
                src.position(base + SLOT_HEADER_LEN + y * stride);
                src.get(rgba, y * w * 4, w * 4);
            }
            float[] depth = null;
            if (wantDepth && hasDepth()) {
                depth = new float[w * h];
                for (int y = 0; y < h; y++) {
                    int row = (int) (base + depthOffset + (long) y * depthStride);
                    for (int x = 0; x < w; x++) depth[y * w + x] = map.getFloat(row + x * 4);
                }
            }
            fence(); // step 3: the acquire fence, then l2
            long l2 = locks.get(base >> 3);
            if (l2 != l1 || map.getLong(base + 8) != frame) {
                skippedOverwritten++;
                return null; // overwritten while copying
            }
            return new Frame(f, timeUs, camSeq, w, h, rgba, depth);
        }

        @Override
        public void close() {
            try {
                channel.close(); // the mapping stays valid until it is garbage collected; nothing is written through it
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    // ------------------------------------------------------------------ writer

    /**
     * Creates and fills a pixel file (the sending side). The file is created exclusively
     * ({@code CREATE_NEW}: the path must not exist), is never truncated or resized, and is deleted by
     * {@link #close()}. Writers are thread-safe, but one thread should do the writing.
     *
     * <p><b>Windows:</b> a mapped file cannot be deleted while this process maps it. {@link #close()}
     * therefore unmaps the buffer first, which Java offers no public way to do; it uses
     * {@code sun.misc.Unsafe.invokeCleaner} on Java 9 and later and the buffer's internal cleaner on
     * Java 8, both by reflection. On a JVM where neither works the mapping lives until garbage
     * collection, the delete fails quietly, and the file outlives the writer on Windows (the temp
     * directory's cleanup then removes it). Elsewhere the delete always works. JDK 24 and later print
     * one warning about {@code sun.misc.Unsafe::invokeCleaner} on stderr at the first close
     * ({@code --sun-misc-unsafe-memory-access=allow} silences it).
     */
    public static final class Writer implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final java.nio.MappedByteBuffer map;
        private final LongBuffer locks;
        public final int slots, width, height, stride;
        public final long slotSize;
        private final boolean depth;
        private final long depthOffset;
        private long frameNo;
        private boolean closed;
        private boolean unmapped;

        public Writer(Path path, int width, int height, int slots, boolean withDepth) throws IOException {
            if (slots < 1 || slots > 16 || width < 1 || height < 1) throw new IllegalArgumentException("bad pixel file size");
            if (width > MAX_DIM || height > MAX_DIM) throw new IllegalArgumentException("width and height are at most " + MAX_DIM);
            if ((long) width * height > Wire.MAX_FRAME_PIXELS) {
                throw new IllegalArgumentException("width * height is at most " + Wire.MAX_FRAME_PIXELS + " pixels");
            }
            this.path = path;
            this.width = width;
            this.height = height;
            this.slots = slots;
            this.stride = width * 4;
            this.depth = withDepth;
            long colourEnd = SLOT_HEADER_LEN + (long) stride * height;
            this.depthOffset = withDepth ? roundUp64(colourEnd) : 0;
            this.slotSize = roundUp64(withDepth ? depthOffset + (long) width * 4 * height : colourEnd);
            long total;
            try {
                total = Math.addExact((long) HEADER_LEN, Math.multiplyExact((long) slots, slotSize));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("the pixel file size overflows");
            }
            if (total > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("a pixel file of " + total + " bytes is over the 2 GiB a Java mapping holds");
            }
            this.channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
            java.nio.MappedByteBuffer m;
            try {
                m = channel.map(FileChannel.MapMode.READ_WRITE, 0, total);
            } catch (IOException | RuntimeException e) {
                channel.close();
                Files.deleteIfExists(path);
                throw e;
            }
            this.map = m;
            map.order(ByteOrder.LITTLE_ENDIAN);
            this.locks = map.asLongBuffer(); // after order(): the view has the same byte order
            map.duplicate().put(new byte[] {'R', 'B', 'F', 'R', 'A', 'M', 'E', 0}); // relative put at 0: the absolute bulk put is Java 13
            map.putInt(8, 1).putInt(12, slots).putInt(16, width).putInt(20, height).putInt(24, stride).putInt(28, FORMAT_RGBA8);
            map.putLong(32, slotSize);
            map.putInt(40, withDepth ? DEPTH_LINEAR_F32 : DEPTH_NONE).putInt(44, withDepth ? width * 4 : 0).putLong(48, depthOffset);
        }

        private static long roundUp64(long v) {
            return (v + 63) & ~63L;
        }

        public Path path() {
            return path;
        }

        /**
         * Writes the next frame (the first is number 1) and returns its number; its slot is
         * {@code frame % slots}.
         *
         * @param rgba tightly packed rows, exactly {@code w * h * 4} bytes
         * @param linearDepth {@code w * h} values in the same row order, or null for "nothing here"
         *        everywhere ({@code +inf}); must be null in a file without a depth plane
         * @throws IllegalArgumentException if {@code w} or {@code h} is below 1 or above the file's,
         *         or an array has the wrong length. Nothing is written and no frame number is used.
         * @throws IllegalStateException after {@link #close()}
         */
        public synchronized long write(byte[] rgba, float[] linearDepth, int w, int h, int cameraSeq, long timeUs) {
            if (closed) throw new IllegalStateException("the pixel file writer is closed");
            if (w < 1 || h < 1 || w > width || h > height) {
                throw new IllegalArgumentException("a " + w + "x" + h + " frame does not fit a " + width + "x" + height + " pixel file");
            }
            if (rgba == null || rgba.length != (long) w * h * 4) {
                throw new IllegalArgumentException("rgba length is not width * height * 4 (" + (long) w * h * 4 + ")");
            }
            if (linearDepth != null) {
                if (!depth) throw new IllegalArgumentException("the pixel file has no depth plane");
                if (linearDepth.length != w * h) throw new IllegalArgumentException("depth length is not width * height (" + w * h + ")");
            }
            long frame = ++frameNo;
            int slot = (int) (frame % slots);
            int base = (int) (HEADER_LEN + slot * slotSize);
            int word = base >> 3;
            long lock = locks.get(word); // only this writer changes it
            locks.put(word, lock + 1); // step 1: odd, then the release fence
            fence();
            boolean complete = false;
            try {
                map.putLong(base + 8, frame).putLong(base + 16, timeUs).putInt(base + 24, cameraSeq).putInt(base + 28, w).putInt(base + 32, h);
                ByteBuffer dst = map.duplicate(); // a private position for the bulk copies
                for (int y = 0; y < h; y++) {
                    dst.position(base + SLOT_HEADER_LEN + y * stride);
                    dst.put(rgba, y * w * 4, w * 4);
                }
                if (depth) {
                    for (int y = 0; y < h; y++) {
                        int row = (int) (base + depthOffset + (long) y * width * 4);
                        for (int x = 0; x < w; x++) map.putFloat(row + x * 4, linearDepth == null ? Float.POSITIVE_INFINITY : linearDepth[y * w + x]);
                    }
                }
                complete = true;
            } finally {
                // Whatever happened, the lock ends even; a slot that was not completed claims no frame.
                if (!complete) map.putLong(base + 8, 0L);
                fence(); // the data stores before the even store (release)
                locks.put(word, lock + 2); // step 3: even again
                fence(); // out before the caller sends the Frame datagram
            }
            return frame;
        }

        /** Unmaps the file (see the class comment), closes it and deletes it. Closing twice is harmless. */
        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            unmapped = unmap(map);
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // On Windows with a mapping that could not be released: the file outlives the writer.
            }
        }

        /** Whether {@link #close()} managed to unmap the buffer (always false before it is called). */
        public synchronized boolean wasUnmapped() {
            return unmapped;
        }
    }

    /**
     * Releases a mapped buffer now instead of at garbage collection, which Windows needs before it
     * deletes the file. Java has no public API for it: Java 9 and later have
     * {@code sun.misc.Unsafe.invokeCleaner(ByteBuffer)}, Java 8 has
     * {@code ((sun.nio.ch.DirectBuffer) buffer).cleaner().clean()}. Both are reached by reflection, so
     * this compiles everywhere and quietly returns false where neither exists. The buffer must not be
     * used afterwards. Pass the original mapped buffer: a duplicate or slice is refused.
     */
    static boolean unmap(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) return false;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            java.lang.reflect.Method invokeCleaner;
            try {
                invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class); // Java 9+
            } catch (NoSuchMethodException java8) {
                invokeCleaner = null;
            }
            if (invokeCleaner != null) {
                invokeCleaner.invoke(unsafe, buffer);
                return true;
            }
            java.lang.reflect.Method cleanerOf = buffer.getClass().getMethod("cleaner"); // Java 8
            cleanerOf.setAccessible(true);
            Object cleaner = cleanerOf.invoke(buffer);
            if (cleaner == null) return false;
            java.lang.reflect.Method clean = cleaner.getClass().getMethod("clean");
            clean.setAccessible(true);
            clean.invoke(cleaner);
            return true;
        } catch (Throwable t) { // reflection failures, a refused duplicate, a JVM without these classes
            return false;
        }
    }
}
