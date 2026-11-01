# CLOUD-REPORT: fuzzing and hardening of link-java

Branch `claude/fuzz-link-java-5b6igk`. Nothing was pushed anywhere else, no pull request, issue, tag, release or publication was made.

## What I did

* Added `fuzz/`: a Gradle build of its own (Jazzer 0.30.0 with its JUnit 5 integration, JDK 21 toolchain). It is not in the root
  `settings.gradle`, so `./gradlew check`, the published jar, JitPack and CI never build or run it. It uses the library through
  `includeBuild('..')`. `build.gradle`, `settings.gradle`, `jitpack.yml` and the CI workflow of the library are unchanged; the library
  is still Java 8 (`--release 8`) with no runtime dependency. `fuzz/README.md` says how to run it.
* 12 fuzz targets in 6 classes (`fuzz/src/test/java/dev/patodois/rewritelink/`):

  | Target | Feeds |
  | --- | --- |
  | `ControlFuzz#parseLine` | arbitrary bytes as one control line (`Control.parse`, `Json`), plus a round trip `parse -> toLine -> parse` |
  | `ControlFuzz#parseHelloWithToken` | hello lines with a marker token and corrupted fields; the token must not appear in a `WireException` message or a `toString()` |
  | `WireFuzz#decodeRaw`, `#decodeBody` | arbitrary datagrams / a valid header and a fuzzed body (counts patched to 0, 102, 103, 226, 227, 4096, 4097, 2^31...); anything that decodes must encode and decode to the same message |
  | `EndpointFuzz#readRaw`, `#readShaped` | arbitrary and almost-right endpoint files through `Endpoint.read` (token must not be in a message; what is read can be written and read back) |
  | `EndpointFuzz#construct` | `new Endpoint`, `Endpoint.write`, `Tokens.equal` against `Arrays.equals` / `String.equals` |
  | `PixelFileFuzz#open` | arbitrary pixel files through `PixelFile.Reader.open` and `read` (not asked for explicitly: it is the one other decoder of peer bytes) |
  | `HandshakeFuzz#hostRaw` | arbitrary control bytes at a live `Link.host` over real loopback sockets and the real `poll()`, host with no token, with a 64-hex token, with a 256-byte multi-byte UTF-8 token and with a one-character token |
  | `HandshakeFuzz#hostHello` | a well-formed hello whose token is right, nearly right (prefix, suffix, one character changed, case, reversed, doubled, empty, absent, `\u`-escaped spelling) or random, then fuzzed lines. Oracle: the host reaches `Connected` exactly when the hello carries a token equal (`String.equals`) to the host's |
  | `HandshakeFuzz#guest` | a fake host saying fuzzed things to a live `Link.guest`, with and without a token |
  | `DatagramFuzz#datagrams` | a fake guest does the handshake, spawns 0 to 4 entities, then sends fuzzed datagrams (all kinds, sessions, sequence numbers, collision chunks with counts and indexes) to a live host: header filter, entity table, staleness, collision reassembly |

  Checks beyond "no uncaught exception except the documented ones" (`WireException`, `IOException`, `IllegalArgumentException` from the
  constructors): a per-call time and allocation budget (`Guard`), no hang (every live-link iteration must reach the end of the
  connection within seconds), everything the link writes to a peer is a line that parses, the token is in no event, no bye, no line
  sent to the peer, no `toString()` and no exception message (the guest's own hello legitimately carries it), and a host with a token
  accepts a hello only with exactly that token.
* The JUnit tests of the library are its own runner (`LinkTests`, no JUnit, no dependency by design). The regression test of the bug
  below is in that runner (`src/test/java/dev/patodois/rewritelink/FuzzFindingTests.java`, called from `LinkTests`), in the repository's
  style, not JUnit, so that the library gains no dependency. The Jazzer module replays its committed corpus as JUnit tests
  (`./gradlew -p fuzz test`, 2775 inputs, all pass).

## Bugs

### 1. `Control.parse`: quadratic time in the number of trailing CR/LF (fixed)

* Input shape: any control line of up to 65535 bytes that ends with a long run of `\r` (or `\n`), for example `{"type":"bye"}` followed by
  65000 times `\r` and one `\n`. The 64 KiB line limit is checked after the stripping, and the reader of the control channel
  (`Ctl.readLines`) hands over lines of up to 65536 bytes.
* Failure: `Control.parse` removed the line endings one character at a time with `line.substring(0, length - 1)`, copying the line each
  time: about 2 GB of copying and allocation for one line. Measured: 396 ms (warm JIT, regression test) to 3.5 s (cold JVM) for one line.
  `Link.poll()` handles up to four such lines per call and is meant to be called on the game thread, so a peer that can write to the
  control port (before the token check: the line is parsed before the hello is looked at) can stall the host's thread for seconds per poll
  as long as it keeps sending.
* Fix: find the end of the line in one pass, then one `substring` (`Control.java`, same behaviour: all trailing CR and LF are still removed).
* Test: `FuzzFindingTests`, "fuzz: Control.parse strips a long run of trailing CR and LF in linear time" (65436 CRs and the same with LFs;
  best of three runs under 100 ms; also checks that mixed endings and no ending still parse). Reverted-fix check: with the original
  `Control.java` the test fails (`parsing a line with 65436 trailing line endings took 396 ms`), with the fix it passes
  (75 tests, 0 failed).
* Honesty note: I found this by reading `Control.parse`, before any fuzzing result, and confirmed it with a small program. Jazzer did **not**
  find it on its own in an 8-minute run (the mutator does not easily build 65000 identical bytes). I added the line as a hand-made seed
  (`ControlFuzzInputs/parseLine/trailing-cr-flood`) and checked that the harness reports it: against the library as it was at the start
  of the task (`100bf95`), `ControlFuzz#parseLine` fails within 60 seconds of fuzzing with `Control.parse allocated 330585448 bytes
  (budget 134217728)`; against the fix it does not.

No other crash, uncaught exception, hang, unbounded allocation, accepted wrong token or token leak was found by the 12 targets.

## Fuzzing time

Every target ran once, for exactly 30 minutes (`@FuzzTest(maxDuration = "30m")`; Jazzer reports `Done N runs in 1801 second(s)`), on the
library with the fix. Before that every target also ran for about 8 minutes (and a few for 1 to 2 minutes) on the library as it was
at the start while I was debugging the harness; those runs are not counted below. Four to five JVMs ran at the same time on a 4-core machine.

| Target | Runs in the 30-minute run | Result |
| --- | ---: | --- |
| `ControlFuzz#parseLine` | 9,575,099 | clean |
| `ControlFuzz#parseHelloWithToken` | 45,073,487 | clean |
| `WireFuzz#decodeRaw` | 115,701,942 | clean |
| `WireFuzz#decodeBody` | 103,713,992 | clean |
| `EndpointFuzz#readRaw` | 4,484,736 | clean (a first attempt stopped after 348 s on a stack overflow in my own harness, see below; it was restarted for the full 30 minutes) |
| `EndpointFuzz#readShaped` | 3,110,258 | clean |
| `EndpointFuzz#construct` | 6,977,453 | clean |
| `PixelFileFuzz#open` | 389,197 | clean |
| `HandshakeFuzz#hostRaw` | 14,829,367 | clean |
| `HandshakeFuzz#hostHello` | 6,391,038 | clean |
| `HandshakeFuzz#guest` | 3,297,785 | clean |
| `DatagramFuzz#datagrams` | 4,127,023 | clean |

Total: 12 x 30 minutes = 6 hours of fuzzing (plus the exploration runs).

Oracle checks (that the harness can fail): (a) with `Tokens.equal` changed to compare only the first 8 bytes, `HandshakeFuzz#hostHello`
failed within 90 seconds with "a host with a token accepted a hello without it"; the change was reverted. (b) The CR/LF flood above.

## Problems of my own harness that I fixed (not library bugs)

* Allocation of the first calls (class loading) tripped the allocation budget: the first 3000 measured calls are skipped and an
  over-budget call is repeated once before it counts.
* Jazzer's instrumentation multiplies allocation by about 20 (a 63 KB line that allocates 1.1 MB uninstrumented measured 22.9 MB), so the
  allocation budget of `Control.parse` is 128 MiB and the time budget 250 ms. The thread allocation counter also undercounts huge
  arrays (the 2 GB of the flood showed up as 67 MB), so the budgets catch gross problems only.
* Jazzer's SSRF detector reported the harness's own loopback connections: `BugDetectors.allowNetworkConnections()` is called in the network targets.
* `HandshakeFuzz#hostHello` expected a NUL escape in a token to be accepted by a host with no token; a NUL escape is a protocol error (`Json`, CONTRACT 8 as quoted in the code).
* `EndpointFuzz` used a regular expression to find the token in the fuzzed file; it overflowed the stack on long input. Replaced by a scan.
* Classpath: the harness runs against the library jar, which must be rebuilt (`./gradlew jar`) after a change.

## Corpus

`fuzz/src/test/resources/dev/patodois/rewritelink/<Class>Inputs/<target>/`: the corpus of each 30-minute run reduced with libFuzzer's
`-merge=1` (the `cmin` equivalent; through a throwaway adapter class, not committed), plus a few hand-made seeds (one valid line per control
message, one valid datagram per kind built with the library's own encoder, two endpoint files, the CR flood). 2763 files, 737 KB in total.
Synthetic inputs only; no game files or data.

## What I left out, and why

* **Quadratic `BigInteger` parse in `Json`**: an integer of about 65000 digits takes about 130 ms to parse (`new BigInteger(String)`),
  per line, only to be refused as out of range. Any integer of more than 309 digits is out of range of a double, so the parse could be
  skipped, but the cost is bounded (about 130 ms for the longest line) and I could not write a timing test that fails reliably without the
  change, so I left the code alone. Noted here so that the owner can decide.
* **Collision reassembly memory**: one collision snapshot may hold up to 4096 chunks of 226 triangles (the documented limit, 32 MiB on the wire);
  the receiver keeps them as `float[9]` objects, tens of MB of heap, and only for one snapshot at a time. Documented limit, not changed.
* **Pixel file reads**: a read may allocate up to 256 MiB of colour and 256 MiB of depth for a legal, sparse file (documented in `Wire.MAX_FRAME_PIXELS`). Not changed.
* **Resource exhaustion by many control connections** (a loop in `pollConnect` accepts all pending connections and queues a `Closing` for each
  extra one) and a peer that connects and stays silent until `timeoutMs`: not fuzzed, no fix; the token does not help against these.
* **Timing side channels** of `Tokens.equal` and of the surrounding `onHello` were not measured, only the result of the comparison.
* **`Link` fed through its UDP socket only after a handshake with the token on**: `DatagramFuzz` uses a host with no token (the datagram path is the same).
* **CONTRACT.md** is in the `rewrite-bridge` repository, which this session could not read. Behaviour was judged from the code, its comments
  (which quote the contract), the README and `tests/vectors`. I did not compare with the Rust side.
* The `maxDuration` of the targets is a constant of the annotation (`30m`), so `-Pfuzz` runs all 12 targets one after the other (6 hours); I found no
  documented property to shorten it. `fuzz/README.md` shows how to run one target in its own JVM.
* The repository has no formatter or linter other than `javac -Xlint:all` in the build; I ran what CI runs: `./gradlew check` (the tests twice, 75 passed, 0 failed) and `python3 tools/check_public_tree.py --range origin/main..HEAD` (passes). The gate refused the new `.java` files of `fuzz/` as foreign source, so `.public-tree.cfg` gained `allow_source fuzz/src/test/java/**` (the gate's own directive for source files that are ours); the script is unchanged.

## Uncertain

* Whether the contract wants *all* trailing CR/LF stripped, or exactly one line ending. The library stripped all of them before my change and does the same now.
* The regression test is time based (under 100 ms, best of three) because the bug is a time/allocation one; on a machine more than about 100 times faster than this one the
  unfixed code could pass it, and a heavily loaded machine could make the fixed code fail it once in a long while.
