package dev.patodois.rewritelink;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
    private static final int MAX_DIM = 1 << 15;

    private static final VarHandle LOCK = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** One frame copied out of a slot. {@code depth} is null when the file has no usable depth plane. */
    public record Frame(long frame, long timeUs, int cameraSeq, int width, int height, byte[] rgba, float[] depth) {}

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
        private final ByteBuffer map;
        public final int slots, width, height, stride;
        public final long slotSize;
        public final int depthFormat;
        private final int depthStride;
        private final long depthOffset;

        private Reader(FileChannel channel, ByteBuffer map, int slots, int width, int height, int stride, long slotSize, int depthFormat,
                       int depthStride, long depthOffset) {
            this.channel = channel;
            this.map = map;
            this.slots = slots;
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.slotSize = slotSize;
            this.depthFormat = depthFormat;
            this.depthStride = depthStride;
            this.depthOffset = depthOffset;
        }

        /** True when the file carries a depth plane this reader understands (linear f32 metres). */
        public boolean hasDepth() {
            return depthFormat == DEPTH_LINEAR_F32;
        }

        /**
         * Opens and checks the file as section 9.6 says. A {@code depth_format} the reader does not
         * know is not an error: the file is read as colour only.
         */
        public static Reader open(Path path) throws IOException {
            if (!path.isAbsolute()) throw new FormatException("the pixel file path is not absolute: " + path);
            if (!Files.isRegularFile(path)) throw new FormatException("not a regular file: " + path);
            FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
            try {
                long size = ch.size();
                if (size < HEADER_LEN) throw new FormatException("pixel file shorter than its header");
                if (size > Integer.MAX_VALUE) throw new FormatException("pixel file larger than 2 GiB");
                ByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN);
                byte[] magic = new byte[8];
                m.get(0, magic);
                if (!"RBFRAME".equals(new String(magic, 0, 7, java.nio.charset.StandardCharsets.US_ASCII)) || magic[7] != 0) {
                    throw new FormatException("bad pixel file magic");
                }
                int version = m.getInt(8);
                int slots = m.getInt(12), w = m.getInt(16), h = m.getInt(20), stride = m.getInt(24), format = m.getInt(28);
                long slotSize = m.getLong(32);
                if (version != 1) throw new FormatException("pixel file version " + version);
                if (slots < 1 || slots > 16) throw new FormatException("slots " + slots);
                if (w < 1 || h < 1 || w > MAX_DIM || h > MAX_DIM) throw new FormatException("size " + w + "x" + h);
                if (format != FORMAT_RGBA8) throw new FormatException("pixel format " + format);
                if (stride != w * 4) throw new FormatException("stride " + stride + " is not width * 4");
                long colourEnd = SLOT_HEADER_LEN + (long) stride * h;
                if (slotSize % 64 != 0 || slotSize < colourEnd) throw new FormatException("slot_size " + slotSize);
                if (HEADER_LEN + slots * slotSize > size) throw new FormatException("file is shorter than its slots");
                int depthFormat = m.getInt(40);
                int depthStride = m.getInt(44);
                long depthOffset = m.getLong(48);
                if (depthFormat == DEPTH_LINEAR_F32) {
                    if (depthStride != w * 4) throw new FormatException("depth_stride " + depthStride);
                    if (depthOffset % 64 != 0 || depthOffset < colourEnd) throw new FormatException("depth_offset " + depthOffset);
                    if (depthOffset + (long) depthStride * h > slotSize) throw new FormatException("depth plane outside its slot");
                } else if (depthFormat != DEPTH_NONE) {
                    depthFormat = DEPTH_NONE; // unknown format: colour only
                }
                return new Reader(ch, m, slots, w, h, stride, slotSize, depthFormat, depthStride, depthOffset);
            } catch (IOException | RuntimeException e) {
                ch.close();
                throw e;
            }
        }

        /**
         * Copies slot {@code slot} out if it holds frame {@code frame} and was not written while
         * copying; null otherwise (skip the frame and wait for the next one).
         */
        public Frame read(long frame, int slot, boolean wantDepth) {
            if (slot < 0 || slot >= slots) return null;
            int base = (int) (HEADER_LEN + (long) slot * slotSize);
            long l1 = (long) LOCK.getAcquire(map, base);
            if ((l1 & 1) != 0) return null;
            long f = map.getLong(base + 8), timeUs = map.getLong(base + 16);
            int camSeq = map.getInt(base + 24), w = map.getInt(base + 28), h = map.getInt(base + 32);
            if (f != frame || w < 1 || h < 1 || w > width || h > height) return null;
            byte[] rgba = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                map.get(base + SLOT_HEADER_LEN + y * stride, rgba, y * w * 4, w * 4);
            }
            float[] depth = null;
            if (wantDepth && hasDepth()) {
                depth = new float[w * h];
                for (int y = 0; y < h; y++) {
                    int row = (int) (base + depthOffset + (long) y * depthStride);
                    for (int x = 0; x < w; x++) depth[y * w + x] = map.getFloat(row + x * 4);
                }
            }
            VarHandle.acquireFence();
            long l2 = (long) LOCK.getVolatile(map, base);
            if (l2 != l1 || map.getLong(base + 8) != frame) return null; // overwritten while copying
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

    /** Creates and fills a pixel file (the sending side). Deletes the file on {@link #close()}. */
    public static final class Writer implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final java.nio.MappedByteBuffer map;
        public final int slots, width, height, stride;
        public final long slotSize;
        private final boolean depth;
        private final long depthOffset;
        private long frameNo;

        public Writer(Path path, int width, int height, int slots, boolean withDepth) throws IOException {
            if (slots < 1 || slots > 16 || width < 1 || height < 1) throw new IllegalArgumentException("bad pixel file size");
            this.path = path;
            this.width = width;
            this.height = height;
            this.slots = slots;
            this.stride = width * 4;
            this.depth = withDepth;
            long colourEnd = SLOT_HEADER_LEN + (long) stride * height;
            this.depthOffset = withDepth ? roundUp64(colourEnd) : 0;
            this.slotSize = roundUp64(withDepth ? depthOffset + (long) width * 4 * height : colourEnd);
            long total = HEADER_LEN + slots * slotSize;
            this.channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
            this.map = channel.map(FileChannel.MapMode.READ_WRITE, 0, total);
            map.order(ByteOrder.LITTLE_ENDIAN);
            map.put(0, new byte[] {'R', 'B', 'F', 'R', 'A', 'M', 'E', 0});
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

        /** Writes the next frame (the first is number 1) and returns its number; its slot is {@code frame % slots}. */
        public long write(byte[] rgba, float[] linearDepth, int w, int h, int cameraSeq, long timeUs) {
            long frame = ++frameNo;
            int slot = (int) (frame % slots);
            int base = (int) (HEADER_LEN + slot * slotSize);
            long lock = (long) LOCK.getVolatile(map, base);
            LOCK.setRelease(map, base, lock + 1);
            VarHandle.releaseFence();
            map.putLong(base + 8, frame).putLong(base + 16, timeUs).putInt(base + 24, cameraSeq).putInt(base + 28, w).putInt(base + 32, h);
            for (int y = 0; y < h; y++) map.put(base + SLOT_HEADER_LEN + y * stride, rgba, y * w * 4, w * 4);
            if (depth) {
                for (int y = 0; y < h; y++) {
                    int row = (int) (base + depthOffset + (long) y * width * 4);
                    for (int x = 0; x < w; x++) map.putFloat(row + x * 4, linearDepth == null ? Float.POSITIVE_INFINITY : linearDepth[y * w + x]);
                }
            }
            LOCK.setRelease(map, base, lock + 2);
            return frame;
        }

        @Override
        public void close() {
            try {
                channel.close();
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
