package dev.e1ixyz.serverbridge.proxy;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProxyConfig {
  public String prefix = "<dark_aqua>[ServerBridge] </dark_aqua>";
  public boolean globalChat = true;
  public boolean privateMessages = true;
  public boolean teleports = true;
  public boolean homes = true;
  public String essentialsUserdataPath = "plugins/Essentials/userdata";
  public int teleportRequestTimeoutSeconds = 120;
  public ServerManagerCompatibility serverManagerCompatibility = new ServerManagerCompatibility();
  public Messages messages = new Messages();

  public static final class Messages {
    public String privateMessagingDisabled = "<red>Private messaging is disabled.</red>";
    public String playerNotFound = "<red>Player not found: <target></red>";
    public String cannotMessageSelf = "<red>You cannot message yourself.</red>";
    public String privateMessageSent = "<gray>to <light_purple><target></light_purple><gray>: <white><message></white></gray>";
    public String privateMessageReceived = "<gray>from <light_purple><player></light_purple><gray>: <white><message></white></gray>";
    public String nobodyMessagedRecently = "<red>Nobody has messaged you recently.</red>";
    public String privateReplyTargetOffline = "<red>That player is no longer online.</red>";
    public String networkTeleportsDisabled = "<red>Network teleports are disabled.</red>";
    public String cannotTargetSelf = "<red>You cannot target yourself.</red>";
    public String teleportRequestAlreadyPending = "<red>You already have a pending teleport request with <target>.</red>";
    public String teleportRequestSent = "<green>Teleport request sent to <target>.</green>";
    public String teleportRequestReceived = "<light_purple><player></light_purple><gray> wants to teleport to you. Use /tpaccept or /tpdeny.</gray>";
    public String teleportHereRequestSent = "<green>Teleport-here request sent to <target>.</green>";
    public String teleportHereRequestReceived = "<light_purple><player></light_purple><gray> wants you to teleport to them. Use /tpaccept or /tpdeny.</gray>";
    public String teleportAllNoTargets = "<red>There are no other online players to request.</red>";
    public String teleportHereRequestSentCount = "<green>Teleport-here request sent to <count> player(s).</green>";
    public String noPendingRequestForTarget = "<red>You do not have a pending request for <target>.</red>";
    public String noOutstandingTeleportRequests = "<red>You do not have any outstanding teleport requests.</red>";
    public String cancelledTeleportWithTarget = "<yellow>Cancelled teleport request(s) involving <target>.</yellow>";
    public String cancelledTeleportCount = "<yellow>Cancelled <count> outstanding teleport request(s).</yellow>";
    public String noMatchingTeleportRequest = "<red>You do not have a matching teleport request.</red>";
    public String teleportRequesterOffline = "<red>That player is no longer online.</red>";
    public String deniedTeleportRequest = "<yellow>Denied teleport request from <player>.</yellow>";
    public String teleportDeniedNotice = "<red><player> denied your teleport request.</red>";
    public String acceptedTeleportRequest = "<green>Accepted teleport request from <player>.</green>";
    public String teleportAcceptedNotice = "<green><player> accepted your teleport request.</green>";
    public String acceptedTeleportHereRequest = "<green>Accepted teleport-here request from <player>.</green>";
    public String teleportHereAcceptedNotice = "<green><player> accepted your teleport-here request.</green>";
    public String noPendingTeleportRequests = "<red>You do not have any pending teleport requests.</red>";
    public String deniedTeleportCount = "<yellow>Denied <count> teleport request(s).</yellow>";
    public String multipleTeleportHereConflict = "<red>You have multiple /tpahere requests. Accept them individually to avoid conflicting teleports.</red>";
    public String noValidTeleportRequests = "<red>No pending teleport requests were still valid.</red>";
    public String acceptedTeleportCount = "<green>Accepted <count> teleport request(s).</green>";
    public String directTeleportingTo = "<green>Teleporting to <target>...</green>";
    public String directTeleportingTargetToYou = "<green>Teleporting <target> to you...</green>";
    public String directTeleportBeingMoved = "<green>You are being teleported to <player>.</green>";
    public String homesDisabled = "<red>Network homes are disabled.</red>";
    public String homeServiceUnavailable = "<red>Home service is not available.</red>";
    public String noHomesFound = "<red>No Essentials homes were found across the managed servers.</red>";
    public String homeMissing = "<red>No home named '<home>' was found on the network.</red>";
    public String homeAmbiguous = "<yellow>That home exists on multiple servers. Pick one:</yellow>";
    public String homeListHeader = "<gold>Network homes:</gold>";
    public String homeListEntry = "<dark_gray>- </dark_gray><aqua><home></aqua><gray> [<server>]</gray>";
    public String homeListHover = "<yellow>Teleport to this home</yellow>";
    public String targetServerNotRegistered = "<red>Target server is not registered with Velocity: <server></red>";
    public String homeSendingToServer = "<green>Sending you to <server> for /home <home>...</green>";
    public String teleportTargetUnavailable = "<red>Target server is unavailable right now.</red>";
    public String failedDispatchCommand = "<red>Failed to dispatch command to the backend.</red>";
    public String failedDispatchTeleport = "<red>Failed to dispatch the teleport to the backend.</red>";
    public String failedConnectToServer = "<red>Failed to connect to <server>.</red>";
    public String crossServerActionTimedOut = "<red>Timed out waiting to finish the cross-server action.</red>";
    public String joinAnnouncement = "<yellow><user> joined <server></yellow>";
    public String leaveAnnouncement = "<yellow><user> left <server></yellow>";
  }

  public static final class ServerManagerCompatibility {
    public boolean enabled = true;
    public boolean requireEnabledFlag = true;
  }

  public static ProxyConfig loadOrCreate(Path path, Logger logger) throws IOException {
    if (!Files.exists(path)) {
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      Files.writeString(path, defaultYaml(), StandardCharsets.UTF_8);
      logger.info("Wrote default ServerBridge proxy config to {}", path.toAbsolutePath());
    }

    Yaml yaml = new Yaml();
    try (InputStream input = Files.newInputStream(path)) {
      ProxyConfig config = yaml.loadAs(input, ProxyConfig.class);
      if (config == null) {
        config = new ProxyConfig();
      }
      if (config.prefix == null) {
        config.prefix = "";
      }
      if (config.essentialsUserdataPath == null || config.essentialsUserdataPath.isBlank()) {
        config.essentialsUserdataPath = "plugins/Essentials/userdata";
      }
      if (config.teleportRequestTimeoutSeconds <= 0) {
        config.teleportRequestTimeoutSeconds = 120;
      }
      if (config.serverManagerCompatibility == null) {
        config.serverManagerCompatibility = new ServerManagerCompatibility();
      }
      if (config.messages == null) {
        config.messages = new Messages();
      }
      return config;
    }
  }

  private static String defaultYaml() {
    return """
        globalChat: true
        privateMessages: true
        teleports: true
        homes: true
        essentialsUserdataPath: "plugins/Essentials/userdata"
        teleportRequestTimeoutSeconds: 120
        prefix: "<dark_aqua>[ServerBridge] </dark_aqua>"
        serverManagerCompatibility:
          enabled: true
          requireEnabledFlag: true
        messages:
          privateMessagingDisabled: "<red>Private messaging is disabled.</red>"
          playerNotFound: "<red>Player not found: <target></red>"
          cannotMessageSelf: "<red>You cannot message yourself.</red>"
          privateMessageSent: "<gray>to <light_purple><target></light_purple><gray>: <white><message></white></gray>"
          privateMessageReceived: "<gray>from <light_purple><player></light_purple><gray>: <white><message></white></gray>"
          nobodyMessagedRecently: "<red>Nobody has messaged you recently.</red>"
          privateReplyTargetOffline: "<red>That player is no longer online.</red>"
          networkTeleportsDisabled: "<red>Network teleports are disabled.</red>"
          cannotTargetSelf: "<red>You cannot target yourself.</red>"
          teleportRequestAlreadyPending: "<red>You already have a pending teleport request with <target>.</red>"
          teleportRequestSent: "<green>Teleport request sent to <target>.</green>"
          teleportRequestReceived: "<light_purple><player></light_purple><gray> wants to teleport to you. Use /tpaccept or /tpdeny.</gray>"
          teleportHereRequestSent: "<green>Teleport-here request sent to <target>.</green>"
          teleportHereRequestReceived: "<light_purple><player></light_purple><gray> wants you to teleport to them. Use /tpaccept or /tpdeny.</gray>"
          teleportAllNoTargets: "<red>There are no other online players to request.</red>"
          teleportHereRequestSentCount: "<green>Teleport-here request sent to <count> player(s).</green>"
          noPendingRequestForTarget: "<red>You do not have a pending request for <target>.</red>"
          noOutstandingTeleportRequests: "<red>You do not have any outstanding teleport requests.</red>"
          cancelledTeleportWithTarget: "<yellow>Cancelled teleport request(s) involving <target>.</yellow>"
          cancelledTeleportCount: "<yellow>Cancelled <count> outstanding teleport request(s).</yellow>"
          noMatchingTeleportRequest: "<red>You do not have a matching teleport request.</red>"
          teleportRequesterOffline: "<red>That player is no longer online.</red>"
          deniedTeleportRequest: "<yellow>Denied teleport request from <player>.</yellow>"
          teleportDeniedNotice: "<red><player> denied your teleport request.</red>"
          acceptedTeleportRequest: "<green>Accepted teleport request from <player>.</green>"
          teleportAcceptedNotice: "<green><player> accepted your teleport request.</green>"
          acceptedTeleportHereRequest: "<green>Accepted teleport-here request from <player>.</green>"
          teleportHereAcceptedNotice: "<green><player> accepted your teleport-here request.</green>"
          noPendingTeleportRequests: "<red>You do not have any pending teleport requests.</red>"
          deniedTeleportCount: "<yellow>Denied <count> teleport request(s).</yellow>"
          multipleTeleportHereConflict: "<red>You have multiple /tpahere requests. Accept them individually to avoid conflicting teleports.</red>"
          noValidTeleportRequests: "<red>No pending teleport requests were still valid.</red>"
          acceptedTeleportCount: "<green>Accepted <count> teleport request(s).</green>"
          directTeleportingTo: "<green>Teleporting to <target>...</green>"
          directTeleportingTargetToYou: "<green>Teleporting <target> to you...</green>"
          directTeleportBeingMoved: "<green>You are being teleported to <player>.</green>"
          homesDisabled: "<red>Network homes are disabled.</red>"
          homeServiceUnavailable: "<red>Home service is not available.</red>"
          noHomesFound: "<red>No Essentials homes were found across the managed servers.</red>"
          homeMissing: "<red>No home named '<home>' was found on the network.</red>"
          homeAmbiguous: "<yellow>That home exists on multiple servers. Pick one:</yellow>"
          homeListHeader: "<gold>Network homes:</gold>"
          homeListEntry: "<dark_gray>- </dark_gray><aqua><home></aqua><gray> [<server>]</gray>"
          homeListHover: "<yellow>Teleport to this home</yellow>"
          targetServerNotRegistered: "<red>Target server is not registered with Velocity: <server></red>"
          homeSendingToServer: "<green>Sending you to <server> for /home <home>...</green>"
          teleportTargetUnavailable: "<red>Target server is unavailable right now.</red>"
          failedDispatchCommand: "<red>Failed to dispatch command to the backend.</red>"
          failedDispatchTeleport: "<red>Failed to dispatch the teleport to the backend.</red>"
          failedConnectToServer: "<red>Failed to connect to <server>.</red>"
          crossServerActionTimedOut: "<red>Timed out waiting to finish the cross-server action.</red>"
          joinAnnouncement: "<yellow><user> joined <server></yellow>"
          leaveAnnouncement: "<yellow><user> left <server></yellow>"
        """;
  }
}
