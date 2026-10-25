package dev.patodois.rewritelink;

import java.util.function.Consumer;

/**
 * Drives a {@link Link} from a daemon thread: polls it every few milliseconds, hands each event
 * to a listener and calls an optional per-tick callback (for heartbeats of game state, such as
 * publishing a camera). The listener and the tick run on the runner thread.
 */
public final class LinkRunner implements AutoCloseable {
    private final Link link;
    private final Consumer<LinkEvent> listener;
    private final Runnable tick;
    private final long periodMs;
    private final Thread thread;
    private volatile boolean stop;

    public LinkRunner(Link link, Consumer<LinkEvent> listener, Runnable tick, long periodMs) {
        this.link = link;
        this.listener = listener;
        this.tick = tick;
        this.periodMs = periodMs;
        this.thread = new Thread(this::loop, "rewrite-link");
        this.thread.setDaemon(true);
    }

    public Link link() {
        return link;
    }

    public void start() {
        thread.start();
    }

    private void loop() {
        while (!stop) {
            try {
                for (LinkEvent e : link.poll()) listener.accept(e);
                if (tick != null) tick.run();
            } catch (RuntimeException e) {
                // A broken listener must not kill the link thread.
                e.printStackTrace();
            }
            try {
                Thread.sleep(periodMs);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Stops the thread, then says bye and closes the link (half-close and drain).
     *
     * <p>The thread is asked to stop with a flag and waited for; it is <b>not</b> interrupted. On Java 8 every NIO channel
     * operation, even on a non-blocking channel, closes the channel and throws {@code ClosedByInterruptException} when the
     * calling thread's interrupt status is set, so an interrupt that landed inside {@code poll()} or the per-tick callback
     * closed the control socket, and the bye that follows was never sent (the peer saw "connection closed"; Java 8 is what
     * Minecraft 1.7.10 and 1.12.2 run on). The thread sleeps at most one period between two checks of the flag.
     */
    @Override
    public void close() {
        stop = true;
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        link.close();
    }
}
