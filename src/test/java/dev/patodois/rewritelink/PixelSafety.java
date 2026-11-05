package dev.patodois.rewritelink;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;

/** Child-process regression at the size/access boundary and a warm-cache reader benchmark. */
public final class PixelSafety {
    private static final class TruncateAfterSize extends FileChannel {
        final FileChannel ch;
        final Path path;
        boolean armed = true;
        long planeAt = -1;
        int reads;
        boolean onSize = true;
        TruncateAfterSize(FileChannel ch, Path path) { this.ch = ch; this.path = path; }
        @Override public long size() throws IOException {
            long size = ch.size();
            if (armed && onSize) {
                armed = false;
                try (FileChannel writer = FileChannel.open(path, StandardOpenOption.WRITE)) { writer.truncate(0); }
                System.out.println("truncated after successful size()");
            }
            return size;
        }
        @Override public int read(ByteBuffer b) throws IOException { return ch.read(b); }
        @Override public int read(ByteBuffer b, long p) throws IOException {
            reads++;
            if (armed && p == planeAt) {
                armed=false;
                try(FileChannel w=FileChannel.open(path,StandardOpenOption.WRITE)) { w.truncate(p+64); }
            }
            return ch.read(b,p);
        }
        @Override public long read(ByteBuffer[] b,int o,int l) throws IOException {
            reads++;
            if(armed && ch.position()==planeAt) {armed=false;try(FileChannel w=FileChannel.open(path,StandardOpenOption.WRITE)){w.truncate(planeAt+64);}}
            return ch.read(b,o,l);
        }
        @Override public int write(ByteBuffer b) throws IOException { return ch.write(b); }
        @Override public int write(ByteBuffer b,long p) throws IOException { return ch.write(b,p); }
        @Override public long write(ByteBuffer[] b,int o,int l) throws IOException { return ch.write(b,o,l); }
        @Override public long position() throws IOException { return ch.position(); }
        @Override public FileChannel position(long p) throws IOException { ch.position(p); return this; }
        @Override public FileChannel truncate(long n) throws IOException { ch.truncate(n); return this; }
        @Override public void force(boolean m) throws IOException { ch.force(m); }
        @Override public long transferTo(long p,long n,WritableByteChannel c) throws IOException { return ch.transferTo(p,n,c); }
        @Override public long transferFrom(ReadableByteChannel c,long p,long n) throws IOException { return ch.transferFrom(c,p,n); }
        @Override public MappedByteBuffer map(MapMode m,long p,long n) throws IOException { return ch.map(m,p,n); }
        @Override public FileLock lock(long p,long n,boolean s) throws IOException { return ch.lock(p,n,s); }
        @Override public FileLock tryLock(long p,long n,boolean s) throws IOException { return ch.tryLock(p,n,s); }
        @Override protected void implCloseChannel() throws IOException { ch.close(); }
    }
    static void child() throws Exception {
        Path path = Files.createTempDirectory("truncate").resolve("f.frames");
        try (PixelFile.Writer w = new PixelFile.Writer(path,64,64,1,false)) {
            w.write(new byte[64*64*4], null,64,64,0,1);
            try (PixelFile.Reader r = PixelFile.Reader.open(path)) {
                Field f = PixelFile.Reader.class.getDeclaredField("channel"); f.setAccessible(true);
                f.set(r, new TruncateAfterSize((FileChannel)f.get(r),path));
                if (r.read(new Wire.FrameReady(1,0,0,64,64),false) != null) throw new AssertionError("truncated frame delivered");
            }
        }
    }
    static void run() {
        apiTests();
        LinkTests.test("truncation after size cannot terminate the reader", () -> {
            if (System.getProperty("os.name").startsWith("Windows")) { System.out.println("Windows semantics differ; Unix truncation race skipped"); return; }
            String java = Paths.get(System.getProperty("java.home"),"bin","java").toString();
            Process p = new ProcessBuilder(java, "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"), "-cp",System.getProperty("java.class.path"),PixelSafety.class.getName(),"child").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))) {
                String line; while ((line = r.readLine()) != null) System.out.println(line);
            }
            int exit = p.waitFor(); System.out.println("truncate child exit="+exit);
            LinkTests.check(exit == 0, "reader survived the size/access race");
        });
    }
    static void readerClose() throws Exception {
        String root = System.getenv("RBL_VECTORS_DIR");
        Path path = Paths.get(root, "pixelfiles", "colour_4x2_3slots.bin");
        try (PixelFile.Reader r = PixelFile.Reader.open(path)) {
            if (r.read(new Wire.FrameReady(1,1,101,4,2),false)==null) throw new AssertionError("fixture read failed");
        }
    }
    static void apiTests() {
        LinkTests.test("truncation inside either plane cannot produce a frame", () -> {
            if(System.getProperty("os.name").startsWith("Windows")) return;
            for(boolean depth:new boolean[]{false,true}) {
                Path path=Files.createTempDirectory("plane").resolve("f.frames");
                try(PixelFile.Writer w=new PixelFile.Writer(path,16,8,1,true)) {
                    w.write(new byte[128],new float[32],8,4,0,1);
                    try(PixelFile.Reader r=PixelFile.Reader.open(path)) {
                        Field field=PixelFile.Reader.class.getDeclaredField("channel");field.setAccessible(true);
                        TruncateAfterSize ch=new TruncateAfterSize((FileChannel)field.get(r),path);
                        ch.onSize=false;ch.planeAt=depth?640:128;field.set(r,ch);
                        LinkTests.check(r.read(new Wire.FrameReady(1,0,0,8,4),depth)==null,"partial plane must be rejected: depth="+depth);
                    }
                }
            }
        });
        LinkTests.test("cropped planes use bounded vector reads", () -> {
            if(System.getProperty("os.name").startsWith("Windows")) return;
            Path path=Files.createTempDirectory("gather").resolve("f.frames");
            try(PixelFile.Writer w=new PixelFile.Writer(path,16,8,1,true)) {
                byte[] px=new byte[128];Arrays.fill(px,(byte)7);float[] depth=new float[32];Arrays.fill(depth,2f);
                w.write(px,depth,8,4,0,1);
                try(PixelFile.Reader r=PixelFile.Reader.open(path)) {
                    Field field=PixelFile.Reader.class.getDeclaredField("channel");field.setAccessible(true);
                    TruncateAfterSize ch=new TruncateAfterSize((FileChannel)field.get(r),path);ch.onSize=false;ch.armed=false;field.set(r,ch);
                    PixelFile.Frame got=r.read(new Wire.FrameReady(1,0,0,8,4),true);
                    LinkTests.check(got!=null && Arrays.equals(got.rgba(),px) && Arrays.equals(got.depth(),depth),"cropped rows retain their values");
                    LinkTests.check(ch.reads==4,"header, two planes and final identity: "+ch.reads);
                }
            }
        });
        LinkTests.test("reader close on Unix does not invoke Unsafe", () -> {
            if (System.getProperty("os.name").startsWith("Windows")) return;
            String java = Paths.get(System.getProperty("java.home"),"bin","java").toString();
            Process p = new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),PixelSafety.class.getName(),"reader-close").redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))) { String line; while((line=r.readLine())!=null) out.append(line).append('\n'); }
            int exit=p.waitFor();
            LinkTests.check(exit==0 && out.indexOf("Unsafe")<0,"reader close must be silent: "+out);
        });
        LinkTests.test("presentation state belongs to each file and accepts a null announcement as a miss", () -> {
            Path dir=Files.createTempDirectory("two-files");
            try(PixelFile.Writer a=new PixelFile.Writer(dir.resolve("a.frames"),2,2,3,false);
                PixelFile.Writer b=new PixelFile.Writer(dir.resolve("b.frames"),3,2,3,true)) {
                for(int i=0;i<5;i++) a.write(new byte[16],null,2,2,0,1);
                b.write(new byte[24],new float[6],3,2,0,1);
                try(PixelFile.Reader ar=PixelFile.Reader.open(a.path());PixelFile.Reader br=PixelFile.Reader.open(b.path())) {
                    Wire.FrameReady old=new Wire.FrameReady(5,2,0,2,2), first=new Wire.FrameReady(1,1,0,3,2);
                    LinkTests.check(ar.readLatest(old,false)!=null,"file A frame 5");
                    LinkTests.check(br.readLatest(old,true)==null,"old A announcement cannot match B");
                    PixelFile.Frame got=br.readLatest(first,false);
                    LinkTests.check(got!=null && got.depth()==null,"new file frame 1 without depth");
                    LinkTests.check(br.readLatest(first,true)==null && br.staleReads()==1,"duplicate counted");
                    long before=br.skippedOtherFrame;
                    LinkTests.check(br.readLatest(null,false)==null && br.read((Wire.FrameReady)null,true)==null && br.skippedOtherFrame==before,"null is a miss without counters");
                }
            }
        });
    }

    static void bench() throws Exception {
        int[][] sizes={{1280,720,1280,720},{1920,1080,1920,1080},{3840,2160,3840,2160},{8192,8192,8192,8192},{16384,1,16384,1},{16384,4096,16384,4096},{1920,1080,1280,720},{3840,2160,1920,1080},{16384,4096,8192,4096},{16384,4096,1,4096}};
        for(int[] size:sizes) for(boolean dep:new boolean[]{false,true}) {
            int fw=size[0],fh=size[1],width=size[2],height=size[3],pixels=width*height,n=pixels>10000000?5:30;
            if(System.getenv("RBL_BENCH_CROPPED_ONLY")!=null && width==fw) continue;
            Path path=Files.createTempDirectory("bench").resolve("f.frames");
            byte[] px=new byte[pixels*4]; Arrays.fill(px,(byte)7);
            float[] depth=dep?new float[pixels]:null; if(depth!=null) Arrays.fill(depth,2f);
            try(PixelFile.Writer w=new PixelFile.Writer(path,fw,fh,1,dep)) {
                w.write(px,depth,width,height,0,1);
                try(PixelFile.Reader r=PixelFile.Reader.open(path)) {
                    Wire.FrameReady ready=new Wire.FrameReady(1,0,0,width,height);
                    double[] times=new double[n];
                    for(int j=0;j<n+2;j++) {
                        System.gc();
                        long start=System.nanoTime();
                        PixelFile.Frame f=r.read(ready,dep);
                        double ms=(System.nanoTime()-start)/1e6;
                        if(f==null || f.rgba()[0]!=7) throw new AssertionError("bad benchmark frame");
                        if(j>=2) times[j-2]=ms;
                    }
                    Arrays.sort(times);
                    System.out.printf(Locale.ROOT,"java,%d,%d,%d,%d,%s,%d,%.6f,%.6f,%.6f%n",fw,fh,width,height,dep,n,times[n/10],times[n/2],times[n*95/100]);
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("child")) child();
        else if(args.length>0 && args[0].equals("bench")) bench();
        else if(args.length>0 && args[0].equals("reader-close")) readerClose();
        else if(args.length>0 && args[0].equals("api")) { apiTests(); if(LinkTests.failures()!=0) System.exit(1); }
        else { run(); if (LinkTests.failures() != 0) System.exit(1); }
    }
}
