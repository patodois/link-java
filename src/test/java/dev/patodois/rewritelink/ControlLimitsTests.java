package dev.patodois.rewritelink;

import java.lang.reflect.*;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static dev.patodois.rewritelink.LinkTests.*;

/** Control limits are checked before queue mutation or network writes. */
final class ControlLimitsTests {
    private static String repeat(char c,int n) {char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
    @SuppressWarnings("try") // Explicit repeated close is the lifecycle regression under test.
    static void run() {
        test("internal control limits: oversized hello fails visibly", () -> {
            LinkConfig hc=new LinkConfig("host");hc.controlPort=0;
            try(Link host=Link.host(hc)) {
                LinkConfig gc=new LinkConfig(repeat('x',Wire.MAX_LINE));gc.controlPort=host.controlPort();
                try(Link guest=Link.guest(gc)) {
                    check(until(2000,()->{host.poll();return guest.poll().stream().anyMatch(e->e instanceof LinkEvent.HandshakeFailed);}),"oversized hello must report a handshake failure");
                    eq(guest.linesSent,0L,"discarded hello not counted");
                }
            }
        });
        test("internal control limits: oversized host reply fails visibly", () -> {
            LinkConfig hc=new LinkConfig(repeat('x',Wire.MAX_LINE));hc.controlPort=0;
            try(Link host=Link.host(hc)) {
                LinkConfig gc=new LinkConfig("guest");gc.controlPort=host.controlPort();
                try(Link guest=Link.guest(gc)) {
                    check(until(2000,()->{guest.poll();return host.poll().stream().anyMatch(e->e instanceof LinkEvent.HandshakeFailed);}),"oversized host hello must report failure");
                    check(!host.isConnected() && host.lastError().contains("hello"),"host retains local diagnostic");
                    eq(host.linesSent,1L,"only the short error bye is counted");
                }
            }
        });
        test("internal control limits: discarded bye is not counted", () -> {
            try(java.nio.channels.ServerSocketChannel server=java.nio.channels.ServerSocketChannel.open()) {
                server.bind(new java.net.InetSocketAddress("127.0.0.1",0));
                try(SocketChannel channel=SocketChannel.open(server.getLocalAddress());SocketChannel remote=server.accept();Link guest=Link.guest(new LinkConfig("guest"))) {
                    check(remote.isConnected(),"loopback peer connected");
                    Class<?> type=Class.forName(Link.class.getName()+"$Ctl");Constructor<?> ctor=type.getDeclaredConstructor(SocketChannel.class);ctor.setAccessible(true);
                    Method retire=Link.class.getDeclaredMethod("retire",type,String.class,long.class);retire.setAccessible(true);
                    retire.invoke(guest,ctor.newInstance(channel),repeat('x',Wire.MAX_LINE),System.nanoTime()/1000000);
                    eq(guest.linesSent,0L,"discarded bye not counted");
                }
            }
        });
        test("lifecycle: a closed guest never reconnects on later polls", () -> {
            try(java.nio.channels.ServerSocketChannel server=java.nio.channels.ServerSocketChannel.open()) {
                server.bind(new java.net.InetSocketAddress("127.0.0.1",0));server.configureBlocking(false);
                LinkConfig cfg=new LinkConfig("guest");cfg.controlPort=((java.net.InetSocketAddress)server.getLocalAddress()).getPort();
                try(Link guest=Link.guest(cfg)) {
                    guest.close();guest.close();
                    for(int i=0;i<10;i++) {check(guest.poll().isEmpty(),"closed poll has no events");Thread.sleep(2);}
                    try(SocketChannel unexpected=server.accept()) {check(unexpected==null,"poll after close opened a TCP connection");}
                    check(!guest.isConnected() && guest.udpPort()==0,"closed link remains disconnected");
                    expectWireError(()->guest.despawn(1),"closed sends are refused");
                }
            }
        });
        test("control limits: UTF-8 bytes including newline bound the queue", () -> {
            Class<?> type=Class.forName(Link.class.getName()+"$Ctl");
            Constructor<?> ctor=type.getDeclaredConstructor(SocketChannel.class);ctor.setAccessible(true);
            Method queue=type.getDeclaredMethod("queue",Control.class);queue.setAccessible(true);
            Field len=type.getDeclaredField("wlen");len.setAccessible(true);
            Field buf=type.getDeclaredField("wbuf");buf.setAccessible(true);
            int overhead=new Control.Spawn(1,"p","",null).toLine().getBytes(StandardCharsets.UTF_8).length;
            try(SocketChannel channel=SocketChannel.open()) {
                Object ctl=ctor.newInstance(channel);
                Control exact=new Control.Spawn(1,"p",repeat('a',Wire.MAX_LINE-overhead),null);
                queue.invoke(ctl,exact);
                eq(len.getInt(ctl),Wire.MAX_LINE,"exact limit accepted");
                byte[] saved=Arrays.copyOf((byte[])buf.get(ctl),len.getInt(ctl));
                for(String asset:new String[]{repeat('a',Wire.MAX_LINE),repeat('\u00e9',Wire.MAX_LINE/2)}) {
                    queue.invoke(ctl,new Control.Spawn(2,"p",asset,null));
                    eq(len.getInt(ctl),Wire.MAX_LINE,"oversized queue must remain unchanged");
                    check(Arrays.equals(saved,Arrays.copyOf((byte[])buf.get(ctl),len.getInt(ctl))),"pending bytes preserved");
                }
            }
        });
        test("control limits: public spawn rejects oversized lines without ending the session", () -> {
            LinkConfig hc=new LinkConfig("host");hc.controlPort=0;hc.timeoutMs=30000;hc.heartbeatMs=10000;
            try(Link host=Link.host(hc)) {
                LinkConfig gc=new LinkConfig("guest");gc.controlPort=host.controlPort();gc.timeoutMs=30000;gc.heartbeatMs=10000;
                try(Link guest=Link.guest(gc)) {
                    check(until(3000,()->{host.poll();guest.poll();return host.isConnected()&&guest.isConnected();}),"connected");
                    long sent=guest.linesSent;
                    boolean refused=false;
                    try {guest.spawn(new Control.Spawn(1,"p",repeat('a',Wire.MAX_LINE),null));}
                    catch(Wire.WireException e) {refused=true;}
                    check(refused,"oversized public call must fail locally");
                    eq(guest.linesSent,sent,"rejected line is not counted as sent");
                    check(guest.isConnected(),"local rejection preserves the session");
                    guest.spawn(new Control.Spawn(2,"p","valid",null));
                    check(until(1000,()->host.poll().stream().anyMatch(e->e instanceof LinkEvent.Spawn)),"next valid control is delivered");
                }
            }
        });
    }
    public static void main(String[] args) {run();if(failures()!=0)System.exit(1);}
}
