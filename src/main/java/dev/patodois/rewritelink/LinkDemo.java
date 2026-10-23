package dev.patodois.rewritelink;

import java.util.List;

import dev.patodois.rewritelink.Control.Role;

/**
 * Command-line peer used for the interop tests against the Rust {@code fake_host} and
 * {@code fake_guest} examples. It plays either role with the same behaviour as those examples.
 *
 * <pre>
 *   java -cp build/classes/java/main dev.patodois.rewritelink.LinkDemo guest [--port 47800] [--seconds 6]
 *   java -cp build/classes/java/main dev.patodois.rewritelink.LinkDemo host  [--port 47800] [--seconds 6]
 * </pre>
 */
public final class LinkDemo {
    private LinkDemo() {}

    private static String arg(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return def;
    }

    private static long start;
    /** Guest: instead of floating 3 m ahead of the camera, circle it at 8 m (shows movement and heading). */
    private static boolean orbit;

    private static void log(String role, String msg) {
        System.out.printf("[java-%s %6.2fs] %s%n", role, (System.nanoTime() - start) / 1e9, msg);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !(args[0].equals("host") || args[0].equals("guest"))) {
            System.err.println("usage: LinkDemo host|guest [--port N] [--seconds S]");
            System.exit(2);
        }
        boolean host = args[0].equals("host");
        int port = Integer.parseInt(arg(args, "--port", String.valueOf(Wire.DEFAULT_CONTROL_PORT)));
        double seconds = Double.parseDouble(arg(args, "--seconds", "0"));
        orbit = java.util.Arrays.asList(args).contains("--orbit");
        start = System.nanoTime();
        LinkConfig cfg = new LinkConfig(host ? "Java Host" : "Java Guest");
        cfg.controlPort = port;
        String name = host ? "host" : "guest";
        try (Link link = host ? Link.host(cfg) : Link.guest(cfg)) {
            if (host) log(name, "listening on 127.0.0.1:" + link.controlPort());
            else log(name, "connecting to 127.0.0.1:" + port);
            if (host) runHost(link, seconds);
            else runGuest(link, seconds);
            log(name, "done");
        }
    }

    private static void runHost(Link link, double seconds) throws Exception {
        int cameras = 0, poseMsgs = 0, poses = 0;
        float[] lastPose = null;
        double nextReport = 1, nextCollision = 0;
        Double giveBackAt = null;
        while (true) {
            double t = (System.nanoTime() - start) / 1e9;
            if (seconds > 0 && t > seconds) break;
            for (LinkEvent e : link.poll()) {
                switch (e) {
                    case LinkEvent.Connected c -> {
                        log("host", String.format("connected: guest \"%s\", protocol v%d, session 0x%08x, guest udp %d",
                                c.peer().game(), c.peer().protocol(), c.peer().session(), c.peer().udpAddr().getPort()));
                        nextCollision = t;
                    }
                    case LinkEvent.Lost l -> log("host", "LOST the guest: " + l.reason());
                    case LinkEvent.HandshakeFailed h -> log("host", "handshake failed: " + h.reason());
                    case LinkEvent.Spawn s -> log("host", "guest spawned entity " + s.spawn().entity() + " = "
                            + s.spawn().provider() + " asset \"" + s.spawn().asset() + "\"");
                    case LinkEvent.Despawn d -> log("host", "guest despawned entity " + d.entity());
                    case LinkEvent.Poses p -> {
                        poseMsgs++;
                        poses += p.poses().size();
                        lastPose = p.poses().get(p.poses().size() - 1).translation();
                    }
                    case LinkEvent.Focus f when f.requested() -> {
                        log("host", "guest asks for input focus; granting it to the " + f.focus().wire);
                        link.setFocus(f.focus());
                        giveBackAt = t + 2;
                    }
                    default -> { }
                }
            }
            if (link.isConnected()) {
                double a = 0.5 * t;
                float[] eye = {(float) (10 * Math.cos(a)), 3, (float) (10 * Math.sin(a))};
                Wire.Camera cam = Wire.Camera.lookAt(eye, new float[] {0, 1, 0}, 1.0f, 0.1f, 1000f, 1280, 720);
                link.sendCamera(cam);
                cameras++;
                if (t >= nextCollision) {
                    nextCollision = t + 1;
                    float cx = Math.round(eye[0]), cz = Math.round(eye[2]);
                    List<float[]> tris = new java.util.ArrayList<>();
                    for (int i = -10; i < 10; i++) {
                        for (int j = -10; j < 10; j++) {
                            float x = cx + i, z = cz + j;
                            tris.add(new float[] {x, 0, z, x, 0, z + 1, x + 1, 0, z + 1});
                            tris.add(new float[] {x, 0, z, x + 1, 0, z + 1, x + 1, 0, z});
                        }
                    }
                    link.sendCollision(new float[] {cx, 0, cz}, 10, tris);
                }
                if (giveBackAt != null && t >= giveBackAt) {
                    giveBackAt = null;
                    log("host", "taking input focus back");
                    link.setFocus(Role.HOST);
                }
            }
            if (t >= nextReport) {
                nextReport += 1;
                if (link.isConnected()) {
                    log("host", String.format("sent %d cameras; got %d poses in %d datagrams; last pose %s; malformed %d stale %d foreign %d",
                            cameras, poses, poseMsgs, lastPose == null ? "none" : java.util.Arrays.toString(lastPose),
                            link.malformed, link.stale, link.foreign));
                }
                cameras = poseMsgs = poses = 0;
            }
            Thread.sleep(16);
        }
    }

    private static void runGuest(Link link, double seconds) throws Exception {
        final long cube = 1;
        int cameras = 0, posesSent = 0;
        double nextReport = 1;
        Double askFocusAt = null;
        float[] lastPos = null;
        long lastT = 0;
        while (true) {
            double t = (System.nanoTime() - start) / 1e9;
            if (seconds > 0 && t > seconds) break;
            for (LinkEvent e : link.poll()) {
                switch (e) {
                    case LinkEvent.Connected c -> {
                        log("guest", String.format("connected: host \"%s\", protocol v%d, session 0x%08x",
                                c.peer().game(), c.peer().protocol(), c.peer().session()));
                        link.spawn(new Control.Spawn(cube, "Java Guest", "demo/cube", "Cube"));
                        askFocusAt = t + 2;
                        lastPos = null;
                    }
                    case LinkEvent.Lost l -> log("guest", "LOST the host: " + l.reason());
                    case LinkEvent.HandshakeFailed h -> log("guest", "handshake failed: " + h.reason());
                    case LinkEvent.Camera c -> {
                        cameras++;
                        float[] eye = c.camera().eye(), fwd = c.camera().forward();
                        float[] pos = {eye[0] + 3 * fwd[0], eye[1] + 3 * fwd[1], eye[2] + 3 * fwd[2]};
                        long now = Wire.nowUs();
                        Wire.Pose pose = Wire.Pose.identity(cube, now);
                        if (orbit) {
                            double a = 0.6 * t;
                            pos = new float[] {eye[0] + (float) (8 * Math.cos(a)), eye[1], eye[2] + (float) (8 * Math.sin(a))};
                            // The front (+Z) points along the direction of travel.
                            float[] heading = {(float) -Math.sin(a), 0, (float) Math.cos(a)};
                            System.arraycopy(Quat.fromTo(new float[] {0, 0, 1}, heading), 0, pose.rotation(), 0, 4);
                        }
                        System.arraycopy(pos, 0, pose.translation(), 0, 3);
                        if (lastPos != null) {
                            float dt = (now - lastT) * 1e-6f;
                            if (dt > 0) for (int i = 0; i < 3; i++) pose.velocity()[i] = (pos[i] - lastPos[i]) / dt;
                        }
                        lastPos = pos;
                        lastT = now;
                        link.sendPoses(List.of(pose));
                        posesSent++;
                    }
                    case LinkEvent.Collision c -> log("guest", "collision query " + Integer.toUnsignedLong(c.query())
                            + ": " + c.triangles().size() + " triangles within " + c.radius() + " m of "
                            + java.util.Arrays.toString(c.center()));
                    case LinkEvent.Focus f -> log("guest", "host says input focus is now with the " + f.focus().wire);
                    default -> { }
                }
            }
            if (askFocusAt != null && t >= askFocusAt) {
                askFocusAt = null;
                log("guest", "asking the host for input focus");
                link.setFocus(Role.GUEST);
            }
            if (t >= nextReport) {
                nextReport += 1;
                if (link.isConnected()) {
                    log("guest", "got " + cameras + " cameras; sent " + posesSent + " poses; focus with the "
                            + link.focus().wire);
                }
                cameras = posesSent = 0;
            }
            Thread.sleep(4);
        }
    }
}
