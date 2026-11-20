# Agent guide

link-java implements rewrite-bridge's passthrough link protocol for Java game mods and plugins.
It supplies host and guest roles for protocol v1, contract revision 8, and is bundled by minecraft-link.

## Toolchain, build and CI

- The Gradle wrapper (Gradle 9) needs a JDK 17 or newer to run; it compiles with `javac --release 8` and the jar runs
  on Java 8+. Where the default `java` is older, point `JAVA_HOME` at a JDK 17+ for every `./gradlew` command.
- CI runs the Gradle wrapper with Temurin JDK 21 on Linux, macOS and Windows, then the compiled tests on a Java 8
  runtime (Zulu 8), and replays the fuzz corpus with `./gradlew -p fuzz test` on Linux.
- The library has no dependencies or native code.
- Exact CI verification command:

```sh
JAVA_HOME=/path/to/jdk-17-or-newer ./gradlew --no-daemon check
```

`check` runs `linkTests` and `linkTestsPreferIpv6`, the plain test runner under both JVM configurations.
The README builds the jar and runs the checks with `./gradlew build --no-daemon`.
Do not rely on Gradle's `test` task alone: it is disabled because the tests do not use JUnit.

To run only the tests whose name contains a text (Gradle does not pass system properties to the runner), build with
`./gradlew compileTestJava`, then from the repository root:
`java -Drbl.testFilter=TEXT -cp build/classes/java/main:build/classes/java/test dev.patodois.rewritelink.LinkTests`.

Gate/workflow regression tests in CI (the `pip install` needs the network; with PyYAML already installed,
`python3 -m unittest discover -s tools -p 'test_*.py'` runs them directly):

```sh
python3 -m venv .workflow-tests
.workflow-tests/bin/python -m pip install -r tools/requirements-test.txt
.workflow-tests/bin/python -m unittest discover -s tools -p 'test_*.py'
```

## Code map

- `src/main/java/dev/patodois/rewritelink/`: public library package.
- `Link`, `LinkConfig`, `LinkEvent`, `LinkRunner`: sessions, their settings, what `poll()` reports, a polling thread.
- `Control`, `Wire`, `Json`, `Quat`: control lines, datagrams, the JSON reader, quaternion helpers.
- `PixelFile`, `Tokens`, `Endpoint`: pixel I/O and authentication.
- `LinkDemo`: standalone command-line host/guest.
- `src/test/java/`, `tests/vectors/link/`: plain runners, interop peer and shared vectors; `RevisionEightTests` holds
  one group of tests per revision 8 rule.
- `PIXEL-IO.md`, `fuzz/`: pixel-reader rules and fuzzing support.
- `tools/`: the public-tree gate (`check_public_tree.py`), workflow checks and their Python tests.

## Project rules

- Preserve Java 8 API compatibility, including library calls, through `--release 8`.
- Bind and connect only to `127.0.0.1`; `Link.poll()` must not wait for the network or the peer.
- Revision 8 bounds are reference values the shared vectors assume (`Link.MAX_CANDIDATES`, `MAX_ACCEPTS_PER_POLL`,
  `MAX_CLOSING`, `MAX_DATAGRAMS_PER_POLL`, `MAX_DATAGRAMS_WITHOUT_SESSION`); keep them unless the contract changes.
- Never disconnect a connected `DatagramChannel`; with UDP port 0 a session gets a new socket instead
  (`Link.udpPort()` may change between sessions).
- Texts sent or reported for a refused line are fixed and quote nothing from it; peer text for people goes through
  `Link.forPeople`.
- Follow rewrite-bridge's contract; vectors come from it and are pinned by digest.
- Present with `readLatest`, close obsolete readers and discard results from replaced channels.
- Shared-vector tests can use sparse files up to 1 GiB, and one test a sparse file of just over 2 GiB;
  `RBL_VECTORS_SKIP_LARGE=1` skips them.
- Tests need no game files; game integration claims are limited to what the README records.

## Public files

- Never commit game files, extracted assets, renders, decompiled code or secrets.
- The public-tree gate needs Python 3.9+ and Git; run it from the repository root:
  `python3 tools/check_public_tree.py` (checks Git's index, including staged new files).
- CI checks committed changes with `python3 tools/check_public_tree.py --range BASE..HEAD`.

## Contributing, license and AI

- Open an issue to discuss a problem or send a pull request with the change.
- Before a pull request, run the applicable CI commands above and the public-tree gate.
- License: MIT OR Apache-2.0, at your option; see `LICENSE-MIT` and `LICENSE-APACHE`.

The code was written with AI coding agents (Anthropic's Claude and OpenAI's GPT models) under the maintainer's direction.
AI-assisted contributions are welcome when their use is disclosed and the changes are reviewed.
