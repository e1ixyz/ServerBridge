# ServerBridge — Handoff

Two-part Minecraft plugin set (Velocity proxy + Paper backends) that bridges
EssentialsX-style social/teleport/home features and a per-player network stash
across a multi-SMP network. EssentialsX stays the in-game implementation; this
just routes things across servers.

_Last updated 2026-06-29._

## What it is

Three Maven modules under one parent (`dev.e1ixyz:serverbridge-parent:0.1.0`):

- **`serverbridge-proxy`** — Velocity plugin. Owns cross-server state: teleport
  requests, PM/reply routing, global chat fanout, Essentials home lookup across
  managed backends, and per-player network-stash contents + daily usage.
- **`serverbridge-paper`** — Paper plugin on every backend. Intercepts
  command/chat input before EssentialsX and forwards structured requests to the
  proxy over the `serverbridge:main` plugin-messaging channel; renders the
  `/stash` GUI.
- **`serverbridge-common`** — the wire protocol (`BridgeProtocol`,
  `BridgeMessageType`) shared by both.

Tied to the separate **`ServerManager`** plugin for backend working-dir discovery
and offline-server handoff (`connectPlayerWhenReady`). Falls back to parsing
`plugins/servermanager/config.yml` if the API isn't present.

## Run it

Build (from repo root):

```sh
mvn clean -DskipTests package
```

Produces shaded jars:
- `serverbridge-proxy/target/serverbridge-proxy-0.1.0.jar`
- `serverbridge-paper/target/serverbridge-paper-0.1.0.jar`

Deploy: proxy jar → Velocity `plugins/`; paper jar → each backend's `plugins/`.
On the production VPS (see `~/Desktop/vps/mc-network-handoff.md`): proxy lives at
`/opt/minecraft/velocity/plugins`, backends at `/opt/minecraft/<server>/plugins`.

There is no test suite. Validate with ad-hoc checks against the compiled classes
+ shaded `snakeyaml` / `slf4j-api` from `~/.m2` on the classpath (this is how the
config round-trip and corrupt-config fallback were verified this session).

### Versions (all current as of this writing)
- Compile target: **Java 17** (`maven.compiler.release` in `pom.xml`); runs on the
  VPS's Java 21/25. Bumping to 21 is optional alignment, not needed.
- **Paper API 1.21.11**, plugin `api-version: "1.21"`.
- **Velocity API 3.4.0** — the latest *stable* (3.5.0 is a snapshot, 4.0.0 dev;
  there is no 5.0.0). A 3.4.0-API plugin runs fine on the VPS's 3.5.0-snapshot proxy.
- `snakeyaml 2.2` (shaded + relocated to `dev.e1ixyz.serverbridge.proxy.lib.snakeyaml`
  in the proxy only), `adventure 4.17.0`.

## File map

- `serverbridge-proxy/.../ServerBridgeProxyPlugin.java` — proxy entry point;
  decodes inbound bridge packets and dispatches all the cross-server handlers (~1.5k lines).
- `.../ProxyConfig.java` — config bean + `loadOrCreate`/`save` (SnakeYAML).
- `.../NetworkStashStore.java` — per-player stash slots, daily usage, audit logs;
  persists to `network-stash.yml` via hand-built maps.
- `.../SocialPreferencesStore.java` — msgtoggle/ignore state → `social-preferences.yml`.
- `.../HomeService.java` — reads EssentialsX userdata across backends.
- `.../ServerManagerAccessor.java` — reflective bridge to the ServerManager plugin.
- `serverbridge-paper/.../ServerBridgePaperPlugin.java` — command/chat interception,
  the `/stash` inventory GUI, and proxy→backend packet handling (~1.6k lines).
- `serverbridge-common/.../protocol/` — `BridgeProtocol` (encode/decode), `BridgeMessageType` (ids).

## Current state

Working: PM/reply, msgtoggle/ignore, TPA/TPAHERE/direct-tp, cross-server `/home`
+ paginated `/homes`, global chat fanout, join/leave announcements, network tab
completion, and the per-player network stash (`/stash`, `/stashlog`,
`/stashreset`, `/stashtoggle`). Build is green.

### Fixed this session (bug sweep)
- **`HomeService` thread-safety** — was sharing one non-thread-safe SnakeYAML
  `Yaml` instance across concurrent `/home` lookups; now creates one per read.
- **`passthroughOnce` token leak** (Paper) — `performCommand` doesn't fire
  `PlayerCommandPreprocessEvent` on standard Paper, so the bypass token leaked and
  the player's *next* manual `/home`-style command was wrongly skipped; now cleared
  right after `performCommand`.
- **Config-load hardening** — a malformed `config.yml` no longer kills the proxy
  plugin on init; it's backed up to `config.yml.broken-<ts>` and defaults restored.
- **`BridgeProtocol` length cap** — `readString`/`readByteArray` now reject
  declared lengths > 8 MB before allocating (cheap DoS guard on a trusted-but-buggy
  backend).

## Gotchas

- **`target/` is committed to git** — compiled `.class` files and shaded jars are
  tracked, so a rebuild shows up as a large diff. Don't be alarmed; that's the
  existing pattern (the VPS runs these committed jars).
- **`ProxyConfig.save` is only called by `/stashtoggle`** and rewrites the whole
  `config.yml` via SnakeYAML `dumpAsMap`. This *does* round-trip cleanly (verified
  — `dumpAsMap` emits untagged maps, not class-tagged YAML), but it loses comments/
  formatting in the file. Expected, not a bug.
- **Known unfixed race (P5):** an in-flight `/stash` deposit can lose the input
  item if the player quits or the server crashes between deposit-send and the
  proxy ack (`cleanupStashView(..., returnInput=false)` while `awaitingResponse`).
  A proper fix needs a deposit ack/replay; left out of the sweep on purpose.
- The Paper plugin intercepts commands by **alias string match** in
  `PlayerCommandPreprocessEvent`, not by registering them — only the four `stash*`
  commands are registered in `plugin.yml`. EssentialsX must own the actual
  `/msg`, `/tpa`, `/home`, etc.
- ServerManager path resolution assumes the proxy layout
  `<proxy>/plugins/serverbridgeproxy/` to walk back to the proxy root.

## Where to look first / next

1. `ServerBridgeProxyPlugin.onPluginMessage` — the switch that routes every bridge
   packet; the clearest map of what the proxy does.
2. `ServerBridgePaperPlugin.onPlayerCommandPreprocess` — the mirror on the backend.
3. `BridgeMessageType` — the full packet vocabulary in one place.

Likely next tasks: the P5 deposit-ack fix; in-game validation of the home-routing
and concurrency fixes after deploy; optional Java 21 toolchain bump.
