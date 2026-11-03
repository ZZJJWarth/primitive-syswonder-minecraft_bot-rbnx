# Camera clock validation, 2026-10-05

Source baseline: Minecraft primitive `d0b50e5`, Robonix `8642befd`, plus current
uncommitted implementation. The existing Chronos gRPC work and user changes to
the route and deployment remain intact. No commit, installation into the game,
real Minecraft connection, movement or deployment launch was performed.

## Checks performed

- Forge `./gradlew build --offline` with JDK 17: passed.
- Standalone Java capture-clock bracket/session checks: passed.
- Current-source rbnx/codegen rebuilt; package proto/MCP/ROS IDL regenerated:
  passed, 94 generated Python modules validated.
- Full bot Python suite: 129 tests passed at that stage; subsequent driver and
  scoring tests also passed in the 19-test clock subset.
- Robonix `cargo test --workspace --all-targets`: passed, including existing
  Chronos gRPC/process tests.
- Robonix `cargo fmt --all -- --check` and workspace clippy with warnings denied:
  passed.
- Standalone probe build/format/clippy: passed.
- Fresh Python stubs -> rebuilt Atlas on an ephemeral loopback port:
  declare/query/connect preserved the timing descriptor and optional zero delay;
  both existing clock-status contracts were available. Process cleaned up.

The 16-second synthetic Mod integration passed with paused game time, four
seconds of stopped emission, configured device reset and a simulated Mod session
restart. The real collector suppressed repeated frames and fed only the raw
observation schema into a real Linux Chronos runtime. Chronos reached Tracking
in three generations, degraded through Holdover/Unsynced during stopped input,
and recovered. Reference verification stayed false. Integer consumer conversion
matched Chronos; every fifth frame was held out from fitting.

## Independent errors from the last synthetic run

| Metric | Result |
| --- | --- |
| Held-out frames | 24 |
| Held-out frames with a model | 13 (54.2% coverage; acquisition/reset gaps included) |
| Mean signed error | +1.408 ms |
| Absolute p50 | 1.340 ms |
| Absolute p95/p99/maximum | 2.431 ms |
| Maximum capture reference evidence uncertainty | 1.002 ms |
| Converted frames within model validity | 13 |
| Converted frames with unbounded uncertainty | 0 |

The three source generations contributed 7, 2 and 4 converted held-out frames.
Observed policy rejections were sampling-interval rejections, whose exact details
were recorded in conversion JSONL. The synthetic test's explicit 50 ms maximum
error limit passed. These numbers include millisecond capture reference
quantization and loopback/pipe latency; they do not measure UTC accuracy or real
Minecraft image-pipeline performance.

Reproduce with the commands in [the test instructions](chronos-camera-test.md).
Live Minecraft input, gRPC publication of fitted camera models, and sim-time
mapping effects remain separate validations. The production gRPC executable
still needs an observation adapter; this implementation adds no Chronos RPC.
