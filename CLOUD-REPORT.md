# CLOUD-REPORT: `--endpoint-file` for LinkDemo

## What changed
- `LinkDemo.java`: new option `--endpoint-file <path>`. It only sets `LinkConfig.endpointFile`; the library does the rest.
  Host: the library generates the token, requires it, writes the file (0600) and removes it on close. Guest: the library reads port
  and token from the file before each attempt (so `--port` is ignored). Usage text, class Javadoc and the guest's "connecting" log line updated.
  Without the option the code path is the same as before (`endpointFile` stays null).
- `README.md`: LinkDemo commands and a short paragraph about the option.
- `DemoTests.java` (called from `LinkTests.main`, so `check` runs it, also in the IPv6 pass): LinkDemo host and guest as subprocesses
  (`java.home` + `java.class.path`), host on `--port 0`.
  1. right token via the endpoint file: both sides log `connected`, file is `rw-------` (POSIX only);
  2. guest without token (`--port` from the file): host logs `handshake failed: unauthorized`, guest logs `unauthorized`, never connected;
  3. guest with a wrong token (a second file written with `Endpoint.write`): same;
  4. no option at all: still links up.

## How the tests fail without the change
With `LinkDemo.java` reverted, tests 1 to 3 fail ("the host wrote the endpoint file": the option is ignored, no file appears);
test 4 passes, as intended (75 passed, 3 failed). With the change: 78 passed, 0 failed.

## API gaps
None needed: `LinkConfig.token`/`endpointFile`, `Endpoint.read/write` and the `HandshakeFailed` event were enough. One note: the
guest's `unauthorized` only reaches it as a `HandshakeFailed` reason text (checked with `contains`), and the host's reason is the
exact string `unauthorized`; there is no typed reason code.

## Left out
- No `--token <value>` option (not asked; a token on the command line shows in the process list).
- Windows: the 0600 check is skipped there, as in the library. I could only run on Linux; the macOS/Windows CI jobs are untested by me.
- I did not read CONTRACT.md of rewrite-bridge (outside this session's repository scope); I relied on the library's docs and tests for 6.2.
