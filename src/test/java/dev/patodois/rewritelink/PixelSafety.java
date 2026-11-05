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
        TruncateAfterSize(FileChannel ch, Path path) { this.ch = ch; this.path = path; }
        @Override public long size() throws IOException {
            long size = ch.size();
            if (armed) {
                armed = false;
                try (FileChannel writer = FileChannel.open(path, StandardOpenOption.WRITE)) { writer.truncate(0); }
                System.out.println("truncated after successful size()");
            }
            return size;
        }
        @Override public int read(ByteBuffer b) throws IOException { return ch.read(b); }
        @Override public int read(ByteBuffer b, long p) throws IOException { return ch.read(b,p); }
        @Override public long read(ByteBuffer[] b,int o,int l) throws IOException { return ch.read(b,o,l); }
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
    static void bench() throws Exception {
        int[][] sizes={{1280,720},{1920,1080},{3840,2160},{8192,8192},{16384,1},{16384,4096}};
        for(int[] size:sizes) for(boolean dep:new boolean[]{false,true}) {
            int width=size[0],height=size[1],pixels=width*height,n=pixels>10000000?5:30;
            Path path=Files.createTempDirectory("bench").resolve("f.frames");
            byte[] px=new byte[pixels*4]; Arrays.fill(px,(byte)7);
            float[] depth=dep?new float[pixels]:null; if(depth!=null) Arrays.fill(depth,2f);
            try(PixelFile.Writer w=new PixelFile.Writer(path,width,height,1,dep)) {
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
                    System.out.printf(Locale.ROOT,"java,%d,%d,%s,%d,%.6f,%.6f%n",width,height,dep,n,times[n/2],times[n*95/100]);
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("child")) child();
        else if(args.length>0 && args[0].equals("bench")) bench();
        else { run(); if (LinkTests.failures() != 0) System.exit(1); }
    }
}
