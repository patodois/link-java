# link-java

A pure-Java implementation of the **rewrite-bridge passthrough link protocol v1**, so that a game
with a Java runtime (a Minecraft mod, a Hytale plugin, a Java server) can be a **host** or a
**guest** next to any other game or rewrite that speaks it. The protocol is defined by
`docs/CONTRACT.md` in the `rewrite-bridge` repository (revision 4); this library is one
implementation of it, tested against the Rust reference peers (`fake_host`, `fake_guest`), which come with
the [`rewrite-bridge`](https://crates.io/crates/rewrite-bridge) crate:
`cargo install rewrite-bridge --version 0.2.1 --features link --examples`.

- No dependencies, no native code, nothing from any game. Package: `dev.patodois.rewritelink`.
- **Java 8 or newer**: compiled with `javac --release 8`, so it loads on Minecraft 1.7.10 and 1.12.2
  (Java 8), 1.20.1 (Java 17) and 26.x (Java 21 and up). Nothing newer than Java 8 is used, not even
  the library calls: records are small final classes with the same accessor names (`foo()`),
  `equals`, `hashCode` and `toString`; `Control`, `LinkEvent` and `Wire.Datagram` are plain interfaces
  (not `sealed`), so a `switch` over them needs a `default`; time stamps come from `Instant.now()`,
  which has microsecond resolution on Java 9 and later and millisecond resolution on Java 8.
- Everything binds and connects to `127.0.0.1` only.
- Non-blocking: `Link.poll()` never blocks, `Link` methods are synchronized so a game thread may
  send while a helper thread polls (`LinkRunner` is that helper thread).

## What is in it

| Class | Role |
|---|---|
| `Wire` | Binary datagrams (header, Heartbeat, Camera, Pose, Collision, Frame): encode, decode, chunking |
| `Control` | TCP JSON-line messages (`hello`, `spawn`, `despawn`, `input`, `frame_channel`, `bye`) |
| `Json` | A small strict JSON reader/writer for the control lines |
| `Link` | One side of a session: handshake, version negotiation, heartbeats, the monotonic watchdog, stale/duplicate/foreign datagram rules, bye + half-close + drain (CONTRACT 6.1) without blocking |
| `LinkRunner` | A daemon thread that polls a `Link` and hands events to a listener |
| `LinkEvent`, `LinkConfig` | What `poll()` reports, and the settings |
| `Quat` | Quaternion helpers in the link's convention |
| `PixelFile` | The pixel file: `Reader` (read-only mapping, seqlock, depth plane checks) and `Writer` |
| `LinkDemo` | A command-line stand-in host or guest, mirroring the Rust `fake_host` / `fake_guest` |

`PixelFile` is the shared-memory pixel channel of CONTRACT 9.6 (revision 3): a `Reader` that maps the
file read-only, validates it, and copies a slot with the seqlock (colour and the optional linear depth
plane), and a `Writer` for the sending side. `frame_channel` and `Frame` are parsed by `Link` as usual.

The seqlock has to work without `VarHandle` and without `sun.misc.Unsafe`, which Java 8 does not offer
or this library does not want. (`PixelFile.Writer.close()` is the one place that reaches for Unsafe, by
reflection and only to unmap the file; see "Contract revision 4" below.) The `lock` word is accessed through a `LongBuffer` view of the mapping
(one aligned 64-bit access on every platform; `ByteBuffer.getLong` can be assembled byte by byte on a
Java 8 that does not know the CPU), and the contract's release and acquire fences are a full fence made
from a volatile read-modify-write (`AtomicInteger.incrementAndGet`), which Java 8 specifies as a total
order and HotSpot compiles to a real barrier that the JIT also respects for the mapped memory. The
long comment at the top of `PixelFile.java` has the details, and `PixelFileTests` runs a writer thread
against a reader thread (two slots, so the writer keeps lapping the reader) to check that a torn copy is
never accepted.

## Contract revision 4

What the code review of both implementations changed, in the order of the contract (section 12 of
`CONTRACT.md` has the reasons; the protocol stays version 1):

- **`Link.sendCollision` queues.** It returns the query number at once, and each `poll()` sends at most
  32 collision datagrams (`Wire.COLLISION_PER_POLL`), so keep polling after a big snapshot. A snapshot
  queued while an older one still has chunks waiting replaces them. Receivers ask for a 4 MiB UDP buffer.
- **Poses need a `spawn`.** A receiver keeps pose state only for entities the peer spawned (at most
  1024 live ones, `Wire.MAX_ENTITIES`); poses of other ids are dropped and counted in
  `Link.unknownEntity`, spawns beyond the table in `Link.spawnIgnored`. A host that publishes poses of its
  own entities must `spawn` them first.
- **`heartbeat_ms` is 10 to 10000.** A hello outside it fails the handshake; `LinkConfig.heartbeatMs` is
  clamped to the range when the link is built.
- **`Link.announceFrames(...)`** sends the `frame_channel` line, for a `Control.FrameChannel` or straight
  from a `PixelFile.Writer`. A new `frame_channel` resets the frame staleness of the receiving link.
- **`PixelFile.Reader.open`** refuses a relative path, a symlink or anything that is not a regular file,
  `width` or `height` above 16384, a `stride` other than `width * 4`, and sizes that overflow. `read`
  returns null (`isGone()`) once the file is shorter than the mapping, instead of faulting.
  Java cannot open a FIFO without blocking, so the type is checked before the open.
- **`PixelFile.Writer`** creates its file with `CREATE_NEW`, validates its arguments
  (`IllegalArgumentException`, nothing written, no frame number used), always leaves the slot lock even,
  and refuses writes after `close()`. `close()` unmaps the buffer before deleting the file, through
  `sun.misc.Unsafe.invokeCleaner` (Java 9+) or the buffer's cleaner (Java 8), both by reflection. Where
  neither works the file outlives the writer on Windows. JDK 24 and later print one `WARNING` about
  `sun.misc.Unsafe::invokeCleaner` on stderr at the first close; `--sun-misc-unsafe-memory-access=allow`
  silences it.
- **`Json`** accepts exactly what the Rust side's `serde_json` accepts: no leading zeros, unicode escapes
  of exactly four hex digits with valid surrogate pairs, 127 levels of nesting.
- **`equals` / `hashCode`** of `Wire.Camera`, `Wire.Pose`, `Wire.CollisionChunk` and
  `LinkEvent.Collision` (and so `Wire.Decoded`) compare array contents. `Wire.encode` refuses a
  `chunk_index` that is not below `chunk_count`, and frame 0.
- Datagrams are accepted from exactly `127.0.0.1:<the peer's udp_port>`.

## Use it in your project

No account and no release upload is needed: [JitPack](https://jitpack.io) builds a git tag of this repository on the first
request and serves it as a Maven artifact (`jitpack.yml` asks it for JDK 21, which Gradle 9 needs). The coordinates are

```
com.github.patodois:link-java:<tag>        e.g. com.github.patodois:link-java:v0.1.0
```

where `<tag>` is a tag of this repository (see the [releases and tags](https://github.com/patodois/link-java/tags)); `main-SNAPSHOT`
gives the latest commit of `main`. The jar has no dependencies and runs on Java 8 or newer.

Gradle (Groovy; in `settings.gradle` or in the `repositories` block of `build.gradle`):

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.patodois:link-java:v0.1.0'
}
```

Gradle (Kotlin):

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.patodois:link-java:v0.1.0")
}
```

Maven:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.patodois</groupId>
  <artifactId>link-java</artifactId>
  <version>v0.1.0</version>
</dependency>
```

A Minecraft mod has to carry the library inside its own jar (jar-in-jar or `jarJar`, as
[minecraft-link](https://github.com/patodois/minecraft-link) does), because the loader does not resolve Maven dependencies.

### A host and a guest in about ten lines each

Both sides poll once per frame or tick; `poll()` never blocks. The host is the game you play (it sends the camera), the guest
publishes an entity that the host draws. Everything runs on `127.0.0.1`, port 47800 by default.

```java
import dev.patodois.rewritelink.*;
import java.util.Collections;

// Host: listen, send the camera every frame, read the guest's poses
try (Link link = Link.host(new LinkConfig("My Game"))) {
    while (running) {
        for (LinkEvent e : link.poll()) {
            if (e instanceof LinkEvent.Poses) draw(((LinkEvent.Poses) e).poses());
        }
        if (link.isConnected()) link.sendCamera(Wire.Camera.lookAt(eye, target, 1.0f, 0.1f, 1000f, 1280, 720));
    }
}

// Guest: connect, spawn one entity, and answer every camera with its pose
try (Link link = Link.guest(new LinkConfig("My Rewrite"))) {
    while (running) {
        for (LinkEvent e : link.poll()) {
            if (e instanceof LinkEvent.Connected) link.spawn(new Control.Spawn(1, "My Rewrite", "demo/cube", "Cube"));
            if (e instanceof LinkEvent.Camera) link.sendPoses(Collections.singletonList(Wire.Pose.identity(1, Wire.nowUs())));
        }
    }
}
```

`running`, `draw`, `eye` and `target` are yours. `LinkRunner` polls on a helper thread when you would rather not poll yourself, and
`LinkDemo` (`./gradlew demo`) is a longer, runnable version of both sides.

## Build and test

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25     # any JDK 9+ to build (javac --release 8); the jar runs on Java 8+
./gradlew build --no-daemon                       # build/libs/link-java-<version>.jar, and the tests
./gradlew linkTests --no-daemon                   # just the tests
./gradlew demo --no-daemon --args="guest --port 47800 --orbit"
./gradlew demo --no-daemon --args="guest --port 47800 --frames"        # renders a disc with depth into a pixel file
./gradlew demo --no-daemon --args="host --port 47800 --big-collision 400"  # a 3.2 MB collision snapshot each second
```

The tests are a plain runner (`LinkTests`, no JUnit): they encode and decode the contract's worked
example byte for byte, check every layout offset and length, reject malformed input (plus a
20,000-case fuzz), and run live sessions in one process (handshake, state flow, stale drops,
`busy`, bad hello, watchdog, clean bye with half-close, foreign addresses), then `ReviewTests` (one
regression test per finding of the revision 4 review) and `PixelFileTests`
(layout, depth plane, refusals, and the writer-against-reader contention test). They are compiled
for Java 8 too and can run on a Java 8 runtime:

```sh
java -cp build/classes/java/main:build/classes/java/test dev.patodois.rewritelink.LinkTests
# a longer contention soak: add -Dlinkjava.contentionMs=20000
```

Consumers either depend on the jar or compile `src/main/java` together with their own sources
(`hytale-link` does the latter, `minecraft-link` includes this project as a Gradle composite build
and bundles it into its mod jar).

## History

This code was written and tested inside `hytale-link` (package `dev.patodois.hytalelink.link`), and
moved here unchanged apart from the package name and the runner thread's name. It was then
ported to Java 8 without changing what it does (see "What is in it").

## License

MIT OR Apache-2.0, at your option: see `LICENSE-MIT` and `LICENSE-APACHE`.
