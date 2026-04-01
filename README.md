# ServerBridge

`ServerBridge` is a two-part plugin set for a Velocity network with Paper backends.

It bridges selected EssentialsX player features across multiple SMP servers while keeping EssentialsX as the actual in-game implementation on each backend.

The current implementation is built around your existing `ServerManager` plugin and expects `ServerManager` to control backend boot-up and auto-connect behavior when a player targets an offline SMP.

## What It Does

- Intercepts EssentialsX-style private message commands on Paper and routes them across the whole proxy network.
- Intercepts EssentialsX-style teleport request and direct teleport commands on Paper and resolves them across servers.
- Mirrors formatted chat across servers by forwarding the already-rendered Paper/LPC chat component to the proxy, then broadcasting it to players on other servers.
- Adds proxy-wide player-name tab completion for target-player command arguments such as `/msg`, `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tp`, and `/tphere`.
- Broadcasts proxy-wide join/leave announcements from the proxy while letting a backend Paper permission decide whether a player should participate.
- Reads a player's existing EssentialsX homes from each managed backend and exposes them as one network-wide home list.
- For a home on another server, sends the player to that backend first and then runs the normal EssentialsX `/home <name>` command on that backend.
- Uses `ServerManager`'s managed server list to find backend working directories and Essentials userdata files.

## Important Behavior

This plugin does **not** replace EssentialsX home storage.

Players should keep using EssentialsX normally for home creation and deletion:

- `/sethome`
- `/delhome`
- any EssentialsX home-management flow you already use

`ServerBridge` only reads those preexisting EssentialsX userdata files and bridges `/home` and `/homes`.

## Architecture

There are two plugins:

- `serverbridge-proxy`
  Runs on Velocity.
  Owns cross-server state for:
  - teleport requests
  - PM/reply routing
  - global chat fanout
  - Essentials home lookup across managed servers

- `serverbridge-paper`
  Runs on every Paper backend.
  Intercepts command/chat input before EssentialsX handles it and forwards structured requests to the proxy over a plugin messaging channel.

There is also a small `serverbridge-common` module containing the bridge protocol.

## ServerManager Integration

`ServerBridge` is intentionally tied to `ServerManager`.

The proxy plugin uses `ServerManager` to discover managed backend working directories, and it can now use an explicit compatibility API for offline-server handoff:

- [ServerManagerPlugin.java](/Users/elimcgehee/Desktop/INDEV/MC/ServerManager/src/main/java/dev/e1ixyz/servermanager/ServerManagerPlugin.java)

That explicit path is enabled only when both plugins opt in:

- `ServerBridge` proxy config:
  - `serverManagerCompatibility.enabled: true`
- `ServerManager` config:
  - `compatibility.serverBridge.enabled: true`

When both are enabled, `ServerBridge` uses `ServerManager.connectPlayerWhenReady(...)` instead of a raw Velocity connection request.

When either side disables compatibility, `ServerBridge` falls back to the older generic flow:

- issue a normal Velocity connection request
- let `ServerManager` intercept `/server`-style startup/connect behavior
- retry the final cross-server action after the player lands

This matters most for homes:

1. The proxy finds the target home in Essentials userdata for the destination SMP.
2. If the destination SMP is offline, `ServerBridge` asks `ServerManager` to start it and connect the player when ready.
3. `ServerManager` waits for ping-readiness, delivers the player, and then runs the queued bridge post-connect action.
4. After the player lands on the target backend, `ServerBridge` runs the normal EssentialsX `/home <name>` command there.

This means the intended flow is:

- `/home base` on SMP A
- proxy sees `base` belongs to SMP B
- `ServerManager` starts SMP B if needed
- player is connected to SMP B
- EssentialsX on SMP B performs the actual `/home base`

## Command Coverage

The bridge currently intercepts these EssentialsX command families and their aliases from the official EssentialsX `plugin.yml`.

### Private messages

- `msg`
- `w`
- `m`
- `t`
- `pm`
- `emsg`
- `epm`
- `tell`
- `etell`
- `whisper`
- `ewhisper`

### Reply

- `r`
- `er`
- `reply`
- `ereply`

### Teleport request commands

- `tpa`
- `call`
- `ecall`
- `etpa`
- `tpask`
- `etpask`

- `tpaall`
- `etpaall`

- `tpacancel`
- `etpacancel`

- `tpahere`
- `etpahere`

- `tpaccept`
- `etpaccept`
- `tpyes`
- `etpyes`

- `tpdeny`
- `etpdeny`
- `tpno`
- `etpno`

### Direct teleport commands

- `tp`
- `tele`
- `etele`
- `teleport`
- `eteleport`
- `etp`
- `tp2p`
- `etp2p`

- `tphere`
- `s`
- `etphere`

### Homes

- `home`
- `ehome`

- `homes`
- `ehomes`

## Current Teleport Semantics

- `tpa` and `tpahere` work across servers.
- `tpaccept` and `tpdeny` support:
  - no argument: most recent request
  - player argument: specific requester
  - `*`: deny-all works; accept-all works unless there are multiple pending `tpahere` requests for the same player, in which case the player is told to accept those individually.
- `tpaall` sends a `tpahere`-style request to every other online player on the network.
- `tpacancel` cancels the requester's outstanding network teleport requests.
- `tp` and `tphere` perform direct cross-server teleports without a request/accept flow.

## Homes

Homes are read from each backend's Essentials userdata directory:

- default relative path: `plugins/Essentials/userdata`

The proxy config lets you change that path if your Essentials data lives elsewhere.

If a player has duplicate home names on multiple servers:

- `/home homeName` prefers the current server if that home exists there
- otherwise the player gets a clickable list
- clicking a list entry runs `/home server:homeName`

That server-qualified form is only a bridge convenience. The actual teleport still happens through EssentialsX on the destination backend.

## Configuration

Proxy config is written to `plugins/serverbridgeproxy/config.yml`.

```yaml
prefix: "<dark_aqua>[ServerBridge] </dark_aqua>"
globalChat: true
privateMessages: true
teleports: true
homes: true
essentialsUserdataPath: "plugins/Essentials/userdata"
teleportRequestTimeoutSeconds: 120
serverManagerCompatibility:
  enabled: true
  requireEnabledFlag: true
messages:
  privateMessagingDisabled: "<red>Private messaging is disabled.</red>"
  playerNotFound: "<red>Player not found: <target></red>"
  teleportRequestSent: "<green>Teleport request sent to <target>.</green>"
  homeSendingToServer: "<green>Sending you to <server> for /home <home>...</green>"
  joinAnnouncement: "<yellow><user> joined <server></yellow>"
  leaveAnnouncement: "<yellow><user> left <server></yellow>"
```

- `prefix` is prepended to ServerBridge-generated proxy messages. Set it to `""` to disable it.
- `globalChat`, `privateMessages`, `teleports`, `homes` toggle the network bridge features.
- `essentialsUserdataPath` is resolved inside each managed backend working directory exposed by `ServerManager`.
- `teleportRequestTimeoutSeconds` controls network teleport request expiry.
- `serverManagerCompatibility.enabled` tells the proxy plugin to prefer the explicit ServerManager API.
- `serverManagerCompatibility.requireEnabledFlag` means the proxy only uses that API when `ServerManager` also has `compatibility.serverBridge.enabled: true`.
- `messages.*` contains the proxy-side in-game text for PMs, teleport flows, home flows, connection failures, and timeout notices.

Paper config is written to `plugins/ServerBridgePaper/config.yml`.

```yaml
prefix: "<dark_aqua>[ServerBridge] </dark_aqua>"
networkPlayerCompletions: true
joinLeaveAnnouncements:
  enabled: true
  suppressLocalMessages: true
  silentJoinPermission: "serverbridge.silentjoin"
  silentLeavePermission: "serverbridge.silentleave"
messages:
  usagePlayerMessage: "<red>Usage: /<command> <player> <message></red>"
  usageReplyMessage: "<red>Usage: /<command> <message></red>"
  usagePlayerTarget: "<red>Usage: /<command> <player></red>"
  bridgeRequestFailed: "<red>Failed to send bridge request: <reason></red>"
```

- The Paper config covers local backend-side ServerBridge messages such as command usage errors and bridge send failures.
- `networkPlayerCompletions` enables cross-server player suggestions for target-player command arguments.
- Proxy-wide join/leave announcements are on by default for everyone when `joinLeaveAnnouncements.enabled` is true.
- `joinLeaveAnnouncements.silentJoinPermission` and `joinLeaveAnnouncements.silentLeavePermission` are checked on the backend Paper server, not on Velocity.
- The default silent permission nodes are `serverbridge.silentjoin` and `serverbridge.silentleave`, both declared with `default: false`.
- `joinLeaveAnnouncements.suppressLocalMessages` suppresses the backend's normal join/quit message so the network-wide announcement flow does not duplicate it.
- The proxy config covers the cross-network messages players actually receive during PM, teleport, and home flows.
- The proxy `messages.joinAnnouncement` and `messages.leaveAnnouncement` templates default to vanilla-style yellow text and are not prefixed automatically.
- Both configs use MiniMessage formatting.

## What This Does Not Do

This build does **not** yet bridge every teleport-related EssentialsX command.

Notably out of scope right now:

- `/back`
- `/otp`
- `/tpo`
- `/tpohere`
- `/tppos`
- `/tpr`
- `/top`
- `/bottom`
- warp/spawn/back-style cross-server location history

Those commands need more state than this first bridge has today, especially when one SMP is offline.

## Building

From this project root:

```bash
mvn clean -DskipTests package
```

Artifacts:

- [serverbridge-proxy-0.1.0.jar](/Users/elimcgehee/Desktop/INDEV/MC/ServerBridge/serverbridge-proxy/target/serverbridge-proxy-0.1.0.jar)
- [serverbridge-paper-0.1.0.jar](/Users/elimcgehee/Desktop/INDEV/MC/ServerBridge/serverbridge-paper/target/serverbridge-paper-0.1.0.jar)

The patched `ServerManager` artifact is built separately in the `ServerManager` project:

- [servermanager-0.1.0.jar](/Users/elimcgehee/Desktop/INDEV/MC/ServerManager/target/servermanager-0.1.0.jar)

## Installation

On Velocity:

- install the patched `ServerManager`
- install `serverbridge-proxy`

On every Paper backend:

- install `serverbridge-paper`
- keep EssentialsX installed normally
- keep LPC installed normally if you want LuckPerms prefix formatting in chat

On Velocity, to enable the stronger offline-server handoff path, also set this in `plugins/servermanager/config.yml`:

```yaml
compatibility:
  serverBridge:
    enabled: true
```

## Proxy Config

The proxy plugin writes:

- `plugins/serverbridgeproxy/config.yml`

Current config fields:

```yaml
prefix: "<dark_aqua>[ServerBridge] </dark_aqua>"
globalChat: true
privateMessages: true
teleports: true
homes: true
essentialsUserdataPath: "plugins/Essentials/userdata"
teleportRequestTimeoutSeconds: 120
serverManagerCompatibility:
  enabled: true
  requireEnabledFlag: true
messages:
  # full default message set is generated into the file
```

The backend plugin writes:

- `plugins/ServerBridgePaper/config.yml`

Its `prefix` and `messages.*` entries control Paper-side usage/error messages before a request reaches the proxy. It also owns:

- `networkPlayerCompletions`
- `joinLeaveAnnouncements.enabled`
- `joinLeaveAnnouncements.silentJoinPermission`
- `joinLeaveAnnouncements.silentLeavePermission`
- `joinLeaveAnnouncements.suppressLocalMessages`

## Notes

- Global chat forwards the rendered chat component from the source server, so remote players see the same formatting that LPC produced on the source backend.
- Target-player command completions on Paper are built from a proxy-fed network player snapshot, so players on other backends appear in suggestions.
- Proxy join/leave announcements are emitted for everyone by default, except players who currently have the configured backend silent permission for that event.
- The bridge relies on plugin messaging, so the Paper plugin talks to the proxy through connected players.
- The bridge is intended for a network where players are typically already on one SMP and may target another SMP that is offline; `ServerManager` is expected to do the actual backend boot and eventual player send.
