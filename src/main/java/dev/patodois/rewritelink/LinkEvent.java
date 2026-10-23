package dev.patodois.rewritelink;

import java.net.InetSocketAddress;
import java.util.List;

import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Wire.Header;

/** What {@link Link#poll()} reports. */
public sealed interface LinkEvent {

    /** The other side of an established session. */
    record PeerInfo(String game, Role role, int protocol, int session, InetSocketAddress udpAddr,
                    long heartbeatMs, long timeoutMs) {}

    /** The handshake completed. */
    record Connected(PeerInfo peer) implements LinkEvent {}

    /** A session ended: bye, close, protocol error or watchdog. Focus is back with the host. */
    record Lost(String reason) implements LinkEvent {}

    /** A connection closed before the handshake completed. */
    record HandshakeFailed(String reason) implements LinkEvent {}

    record Camera(Header header, Wire.Camera camera) implements LinkEvent {}

    /** Poses newer than the last one of their entity. */
    record Poses(Header header, List<Wire.Pose> poses) implements LinkEvent {}

    /** A complete collision snapshot, all chunks reassembled. */
    record Collision(int query, float[] center, float radius, List<float[]> triangles) implements LinkEvent {}

    record Frame(Header header, Wire.FrameReady frame) implements LinkEvent {}

    record Spawn(Control.Spawn spawn) implements LinkEvent {}

    record Despawn(long entity) implements LinkEvent {}

    /** Guest: the host moved the focus. Host: the guest asked for it. */
    record Focus(Role focus, boolean requested) implements LinkEvent {}

    record FrameChannel(Control.FrameChannel channel) implements LinkEvent {}
}
