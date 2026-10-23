package dev.patodois.rewritelink;

import java.net.InetSocketAddress;
import java.util.List;

import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Wire.Header;

/** What {@link Link#poll()} reports. */
public interface LinkEvent {

    /** The other side of an established session. */
    final class PeerInfo {
        private final String game;
        private final Role role;
        private final int protocol;
        private final int session;
        private final InetSocketAddress udpAddr;
        private final long heartbeatMs;
        private final long timeoutMs;

        public PeerInfo(String game, Role role, int protocol, int session, InetSocketAddress udpAddr, long heartbeatMs, long timeoutMs) {
            this.game = game;
            this.role = role;
            this.protocol = protocol;
            this.session = session;
            this.udpAddr = udpAddr;
            this.heartbeatMs = heartbeatMs;
            this.timeoutMs = timeoutMs;
        }

        public String game() {
            return game;
        }

        public Role role() {
            return role;
        }

        public int protocol() {
            return protocol;
        }

        public int session() {
            return session;
        }

        public InetSocketAddress udpAddr() {
            return udpAddr;
        }

        public long heartbeatMs() {
            return heartbeatMs;
        }

        public long timeoutMs() {
            return timeoutMs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PeerInfo)) return false;
            PeerInfo that = (PeerInfo) o;
            return java.util.Objects.equals(game, that.game)
                    && java.util.Objects.equals(role, that.role)
                    && protocol == that.protocol
                    && session == that.session
                    && java.util.Objects.equals(udpAddr, that.udpAddr)
                    && heartbeatMs == that.heartbeatMs
                    && timeoutMs == that.timeoutMs;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(game);
            h = 31 * h + java.util.Objects.hashCode(role);
            h = 31 * h + Integer.hashCode(protocol);
            h = 31 * h + Integer.hashCode(session);
            h = 31 * h + java.util.Objects.hashCode(udpAddr);
            h = 31 * h + Long.hashCode(heartbeatMs);
            h = 31 * h + Long.hashCode(timeoutMs);
            return h;
        }

        @Override
        public String toString() {
            return "PeerInfo[game=" + game + ", role=" + role + ", protocol=" + protocol + ", session=" + session + ", udpAddr=" + udpAddr + ", heartbeatMs=" + heartbeatMs + ", timeoutMs=" + timeoutMs + "]";
        }
    }

    /** The handshake completed. */
    final class Connected implements LinkEvent {
        private final PeerInfo peer;

        public Connected(PeerInfo peer) {
            this.peer = peer;
        }

        public PeerInfo peer() {
            return peer;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Connected)) return false;
            Connected that = (Connected) o;
            return java.util.Objects.equals(peer, that.peer);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(peer);
            return h;
        }

        @Override
        public String toString() {
            return "Connected[peer=" + peer + "]";
        }
    }

    /** A session ended: bye, close, protocol error or watchdog. Focus is back with the host. */
    final class Lost implements LinkEvent {
        private final String reason;

        public Lost(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Lost)) return false;
            Lost that = (Lost) o;
            return java.util.Objects.equals(reason, that.reason);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(reason);
            return h;
        }

        @Override
        public String toString() {
            return "Lost[reason=" + reason + "]";
        }
    }

    /** A connection closed before the handshake completed. */
    final class HandshakeFailed implements LinkEvent {
        private final String reason;

        public HandshakeFailed(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HandshakeFailed)) return false;
            HandshakeFailed that = (HandshakeFailed) o;
            return java.util.Objects.equals(reason, that.reason);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(reason);
            return h;
        }

        @Override
        public String toString() {
            return "HandshakeFailed[reason=" + reason + "]";
        }
    }

    final class Camera implements LinkEvent {
        private final Header header;
        private final Wire.Camera camera;

        public Camera(Header header, Wire.Camera camera) {
            this.header = header;
            this.camera = camera;
        }

        public Header header() {
            return header;
        }

        public Wire.Camera camera() {
            return camera;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Camera)) return false;
            Camera that = (Camera) o;
            return java.util.Objects.equals(header, that.header)
                    && java.util.Objects.equals(camera, that.camera);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(header);
            h = 31 * h + java.util.Objects.hashCode(camera);
            return h;
        }

        @Override
        public String toString() {
            return "Camera[header=" + header + ", camera=" + camera + "]";
        }
    }

    /** Poses newer than the last one of their entity. */
    final class Poses implements LinkEvent {
        private final Header header;
        private final List<Wire.Pose> poses;

        public Poses(Header header, List<Wire.Pose> poses) {
            this.header = header;
            this.poses = poses;
        }

        public Header header() {
            return header;
        }

        public List<Wire.Pose> poses() {
            return poses;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Poses)) return false;
            Poses that = (Poses) o;
            return java.util.Objects.equals(header, that.header)
                    && java.util.Objects.equals(poses, that.poses);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(header);
            h = 31 * h + java.util.Objects.hashCode(poses);
            return h;
        }

        @Override
        public String toString() {
            return "Poses[header=" + header + ", poses=" + poses + "]";
        }
    }

    /** A complete collision snapshot, all chunks reassembled. */
    final class Collision implements LinkEvent {
        private final int query;
        private final float[] center;
        private final float radius;
        private final List<float[]> triangles;

        public Collision(int query, float[] center, float radius, List<float[]> triangles) {
            this.query = query;
            this.center = center;
            this.radius = radius;
            this.triangles = triangles;
        }

        public int query() {
            return query;
        }

        public float[] center() {
            return center;
        }

        public float radius() {
            return radius;
        }

        public List<float[]> triangles() {
            return triangles;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Collision)) return false;
            Collision that = (Collision) o;
            return query == that.query
                    && java.util.Objects.equals(center, that.center)
                    && Float.compare(radius, that.radius) == 0
                    && java.util.Objects.equals(triangles, that.triangles);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Integer.hashCode(query);
            h = 31 * h + java.util.Objects.hashCode(center);
            h = 31 * h + Float.hashCode(radius);
            h = 31 * h + java.util.Objects.hashCode(triangles);
            return h;
        }

        @Override
        public String toString() {
            return "Collision[query=" + query + ", center=" + center + ", radius=" + radius + ", triangles=" + triangles + "]";
        }
    }

    final class Frame implements LinkEvent {
        private final Header header;
        private final Wire.FrameReady frame;

        public Frame(Header header, Wire.FrameReady frame) {
            this.header = header;
            this.frame = frame;
        }

        public Header header() {
            return header;
        }

        public Wire.FrameReady frame() {
            return frame;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Frame)) return false;
            Frame that = (Frame) o;
            return java.util.Objects.equals(header, that.header)
                    && java.util.Objects.equals(frame, that.frame);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(header);
            h = 31 * h + java.util.Objects.hashCode(frame);
            return h;
        }

        @Override
        public String toString() {
            return "Frame[header=" + header + ", frame=" + frame + "]";
        }
    }

    final class Spawn implements LinkEvent {
        private final Control.Spawn spawn;

        public Spawn(Control.Spawn spawn) {
            this.spawn = spawn;
        }

        public Control.Spawn spawn() {
            return spawn;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Spawn)) return false;
            Spawn that = (Spawn) o;
            return java.util.Objects.equals(spawn, that.spawn);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(spawn);
            return h;
        }

        @Override
        public String toString() {
            return "Spawn[spawn=" + spawn + "]";
        }
    }

    final class Despawn implements LinkEvent {
        private final long entity;

        public Despawn(long entity) {
            this.entity = entity;
        }

        public long entity() {
            return entity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Despawn)) return false;
            Despawn that = (Despawn) o;
            return entity == that.entity;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Long.hashCode(entity);
            return h;
        }

        @Override
        public String toString() {
            return "Despawn[entity=" + entity + "]";
        }
    }

    /** Guest: the host moved the focus. Host: the guest asked for it. */
    final class Focus implements LinkEvent {
        private final Role focus;
        private final boolean requested;

        public Focus(Role focus, boolean requested) {
            this.focus = focus;
            this.requested = requested;
        }

        public Role focus() {
            return focus;
        }

        public boolean requested() {
            return requested;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Focus)) return false;
            Focus that = (Focus) o;
            return java.util.Objects.equals(focus, that.focus)
                    && requested == that.requested;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(focus);
            h = 31 * h + Boolean.hashCode(requested);
            return h;
        }

        @Override
        public String toString() {
            return "Focus[focus=" + focus + ", requested=" + requested + "]";
        }
    }

    final class FrameChannel implements LinkEvent {
        private final Control.FrameChannel channel;

        public FrameChannel(Control.FrameChannel channel) {
            this.channel = channel;
        }

        public Control.FrameChannel channel() {
            return channel;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FrameChannel)) return false;
            FrameChannel that = (FrameChannel) o;
            return java.util.Objects.equals(channel, that.channel);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(channel);
            return h;
        }

        @Override
        public String toString() {
            return "FrameChannel[channel=" + channel + "]";
        }
    }
}
