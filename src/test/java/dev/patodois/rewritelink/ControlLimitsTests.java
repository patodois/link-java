package dev.patodois.rewritelink;

import java.lang.reflect.*;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static dev.patodois.rewritelink.LinkTests.*;

/** Control limits are checked before queue mutation or network writes. */
final class ControlLimitsTests {
    private static String repeat(char c,int n) {char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
    static void run() {
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
