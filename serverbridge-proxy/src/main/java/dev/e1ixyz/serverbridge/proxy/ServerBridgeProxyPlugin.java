package dev.e1ixyz.serverbridge.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.e1ixyz.serverbridge.common.protocol.BridgeMessageType;
import dev.e1ixyz.serverbridge.common.protocol.BridgeProtocol;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Plugin(
    id = "serverbridgeproxy",
    name = "ServerBridgeProxy",
    version = "0.1.0",
    authors = {"e1ixyz"}
)
public final class ServerBridgeProxyPlugin {
  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private static final int FALLBACK_CONNECT_RETRY_ATTEMPTS = 5;
  private static final Duration NETWORK_PLAYER_SNAPSHOT_DELAY = Duration.ofMillis(250);
  private static final String HOMES_PAGE_KEYWORD = "page";
  private static final byte STASH_ACTION_OPEN = 0;
  private static final byte STASH_ACTION_DEPOSIT = 1;
  private static final byte STASH_ACTION_WITHDRAW = 2;

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDir;
  private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL);
  private final ConcurrentMap<UUID, UUID> lastPrivatePartner = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ConcurrentMap<UUID, TeleportRequest>> requestsByTarget = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, PendingServerAction> pendingActions = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, JoinLeaveState> joinLeaveStates = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, String> knownServers = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, UUID> stashSessions = new ConcurrentHashMap<>();

  private ProxyConfig config;
  private HomeService homeService;
  private SocialPreferencesStore socialPreferencesStore;
  private NetworkStashStore networkStashStore;
  private ServerManagerAccessor serverManagerAccessor;
  private ZoneId networkStashZoneId = ZoneId.of("America/New_York");

  @Inject
  public ServerBridgeProxyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDir = dataDir;
  }

  @Subscribe
  public void onInit(ProxyInitializeEvent event) {
    try {
      Files.createDirectories(dataDir);
      config = ProxyConfig.loadOrCreate(dataDir.resolve("config.yml"), logger);
      socialPreferencesStore = SocialPreferencesStore.loadOrCreate(dataDir.resolve("social-preferences.yml"), logger);
      networkStashStore = NetworkStashStore.loadOrCreate(dataDir.resolve("network-stash.yml"), logger);
      serverManagerAccessor = new ServerManagerAccessor(proxy, dataDir, logger);
      homeService = new HomeService(config, serverManagerAccessor, logger);
      networkStashZoneId = resolveNetworkStashZoneId(config);
      proxy.getChannelRegistrar().register(channel);
      logger.info("ServerBridge proxy initialized on channel {}", channel.getId());
    } catch (Exception ex) {
      logger.error("Failed to initialize ServerBridge proxy", ex);
    }
  }

  @Subscribe
  public void onShutdown(ProxyShutdownEvent event) {
    proxy.getChannelRegistrar().unregister(channel);
    requestsByTarget.clear();
    pendingActions.clear();
    lastPrivatePartner.clear();
    joinLeaveStates.clear();
    knownServers.clear();
    stashSessions.clear();
  }

  @Subscribe
  public void onPluginMessage(PluginMessageEvent event) {
    if (!channel.equals(event.getIdentifier())) {
      return;
    }
    event.setResult(PluginMessageEvent.ForwardResult.handled());

    if (!(event.getSource() instanceof ServerConnection connection)) {
      return;
    }

    Player player = connection.getPlayer();
    try {
      BridgeProtocol.DecodedMessage decoded = BridgeProtocol.decode(event.getData());
      if (!BridgeProtocol.readUuid(decoded.in()).equals(player.getUniqueId())) {
        logger.warn("Rejected forged bridge payload from {}", player.getUsername());
        return;
      }

      switch (decoded.type()) {
        case CHAT_BROADCAST -> handleGlobalChat(connection, player, BridgeProtocol.readString(decoded.in()));
        case PRIVATE_MESSAGE -> handlePrivateMessage(player, BridgeProtocol.readString(decoded.in()), BridgeProtocol.readString(decoded.in()));
        case PRIVATE_REPLY -> handlePrivateReply(player, BridgeProtocol.readString(decoded.in()));
        case TPA_REQUEST -> handleTeleportRequest(player, BridgeProtocol.readString(decoded.in()), TeleportMode.TPA);
        case TPA_ALL -> handleTeleportAllRequest(player);
        case TP_CANCEL -> handleTeleportCancel(player, BridgeProtocol.readNullableString(decoded.in()));
        case TPAHERE_REQUEST -> handleTeleportRequest(player, BridgeProtocol.readString(decoded.in()), TeleportMode.TPA_HERE);
        case TP_ACCEPT -> handleTeleportResponse(player, BridgeProtocol.readNullableString(decoded.in()), true);
        case TP_DENY -> handleTeleportResponse(player, BridgeProtocol.readNullableString(decoded.in()), false);
        case TP_DIRECT -> handleDirectTeleport(player, BridgeProtocol.readString(decoded.in()), TeleportMode.TP);
        case TPHERE_DIRECT -> handleDirectTeleport(player, BridgeProtocol.readString(decoded.in()), TeleportMode.TP_HERE);
        case HOME -> handleHome(player, BridgeProtocol.readNullableString(decoded.in()));
        case HOMES -> handleHomes(player, decoded.in().readInt());
        case PLAYER_STATE_SYNC -> handlePlayerStateSync(
            connection,
            player,
            decoded.in().readBoolean(),
            decoded.in().readBoolean(),
            decoded.in().readBoolean()
        );
        case MSG_TOGGLE -> handleMessageToggle(player, BridgeProtocol.readNullableString(decoded.in()));
        case IGNORE_PLAYER -> handleIgnore(player, BridgeProtocol.readString(decoded.in()), decoded.in().readBoolean());
        case STASH_OPEN -> handleStashOpen(player);
        case STASH_CLOSE -> handleStashClose(player, BridgeProtocol.readUuid(decoded.in()));
        case STASH_DEPOSIT -> handleStashDeposit(
            player,
            BridgeProtocol.readUuid(decoded.in()),
            BridgeProtocol.readByteArray(decoded.in())
        );
        case STASH_WITHDRAW -> handleStashWithdraw(
            player,
            BridgeProtocol.readUuid(decoded.in()),
            decoded.in().readInt()
        );
        default -> logger.warn("Ignoring unsupported proxy-bound bridge packet {}", decoded.type());
      }
    } catch (Exception ex) {
      logger.error("Failed to process bridge payload from {}", player.getUsername(), ex);
    }
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    Player player = event.getPlayer();
    knownServers.put(player.getUniqueId(), event.getServer().getServerInfo().getName());
    scheduleNetworkPlayerSnapshot();

    PendingServerAction action = pendingActions.get(player.getUniqueId());
    if (action == null) {
      return;
    }
    String currentServer = event.getServer().getServerInfo().getName();
    if (!action.targetServer().equalsIgnoreCase(currentServer)) {
      return;
    }

    dispatchPendingAction(player.getUniqueId(), action, 0);
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();
    String lastKnownServer = knownServers.remove(uuid);
    if (lastKnownServer == null) {
      lastKnownServer = currentServerName(player);
    }
    JoinLeaveState joinLeaveState = joinLeaveStates.remove(uuid);
    boolean broadcastLeave = joinLeaveState != null
        && joinLeaveState.enabled()
        && !joinLeaveState.silentLeave()
        && lastKnownServer != null
        && !lastKnownServer.isBlank();
    pendingActions.remove(uuid);
    lastPrivatePartner.remove(uuid);
    requestsByTarget.remove(uuid);
    requestsByTarget.values().forEach(map -> map.remove(uuid));
    stashSessions.remove(uuid);

    if (broadcastLeave) {
      ProxyConfig.Messages messages = messagesConfig();
      broadcast(render(messages.leaveAnnouncement,
          "user", player.getUsername(),
          "player", player.getUsername(),
          "server", lastKnownServer));
    }
    scheduleNetworkPlayerSnapshot();
  }

  private void handleGlobalChat(ServerConnection sourceConnection, Player sender, String componentJson) {
    if (config == null || !config.globalChat) {
      return;
    }
    Component component = GsonComponentSerializer.gson().deserialize(componentJson);
    String sourceServer = sourceConnection.getServerInfo().getName();
    for (Player player : proxy.getAllPlayers()) {
      String playerServer = currentServerName(player);
      if (playerServer != null && playerServer.equalsIgnoreCase(sourceServer)) {
        continue;
      }
      player.sendMessage(component);
    }
  }

  private void handlePlayerStateSync(ServerConnection connection, Player player, boolean joinLeaveEnabled,
                                     boolean silentJoin, boolean silentLeave) {
    ProxyConfig.Messages messages = messagesConfig();
    String serverName = connection.getServerInfo().getName();
    knownServers.put(player.getUniqueId(), serverName);
    JoinLeaveState previous = joinLeaveStates.put(
        player.getUniqueId(),
        new JoinLeaveState(joinLeaveEnabled, silentJoin, silentLeave)
    );
    if (previous == null && joinLeaveEnabled && !silentJoin) {
      broadcast(render(messages.joinAnnouncement,
          "user", player.getUsername(),
          "player", player.getUsername(),
          "server", serverName));
    }
    scheduleNetworkPlayerSnapshot();
  }

  private void handlePrivateMessage(Player sender, String targetName, String message) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.privateMessages) {
      message(sender, messages.privateMessagingDisabled);
      return;
    }
    Player target = resolveOnlinePlayer(targetName);
    if (target == null) {
      message(sender, messages.playerNotFound, "target", targetName);
      return;
    }
    if (sender.getUniqueId().equals(target.getUniqueId())) {
      message(sender, messages.cannotMessageSelf);
      return;
    }
    if (socialPreferencesStore != null && socialPreferencesStore.areMessagesDisabled(target.getUniqueId())) {
      message(sender, messages.targetMessagesDisabled, "target", target.getUsername());
      return;
    }
    if (socialPreferencesStore != null && socialPreferencesStore.isIgnoring(target.getUniqueId(), sender.getUniqueId())) {
      message(sender, messages.targetIgnoringYou, "target", target.getUsername());
      return;
    }

    message(sender, messages.privateMessageSent, "target", target.getUsername(), "message", message);
    message(target, messages.privateMessageReceived, "player", sender.getUsername(), "message", message);

    lastPrivatePartner.put(sender.getUniqueId(), target.getUniqueId());
    lastPrivatePartner.put(target.getUniqueId(), sender.getUniqueId());
  }

  private void handlePrivateReply(Player sender, String message) {
    ProxyConfig.Messages messages = messagesConfig();
    UUID targetUuid = lastPrivatePartner.get(sender.getUniqueId());
    if (targetUuid == null) {
      message(sender, messages.nobodyMessagedRecently);
      return;
    }
    Player target = proxy.getPlayer(targetUuid).orElse(null);
    if (target == null) {
      message(sender, messages.privateReplyTargetOffline);
      return;
    }
    handlePrivateMessage(sender, target.getUsername(), message);
  }

  private void handleTeleportRequest(Player requester, String targetName, TeleportMode mode) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.teleports) {
      message(requester, messages.networkTeleportsDisabled);
      return;
    }

    Player target = resolveOnlinePlayer(targetName);
    if (target == null) {
      message(requester, messages.playerNotFound, "target", targetName);
      return;
    }
    if (requester.getUniqueId().equals(target.getUniqueId())) {
      message(requester, messages.cannotTargetSelf);
      return;
    }

    pruneExpiredRequests(target.getUniqueId());
    ConcurrentMap<UUID, TeleportRequest> requests = requestsByTarget.computeIfAbsent(target.getUniqueId(), ignored -> new ConcurrentHashMap<>());
    if (requests.containsKey(requester.getUniqueId())) {
      message(requester, messages.teleportRequestAlreadyPending, "target", target.getUsername());
      return;
    }

    TeleportRequest request = new TeleportRequest(requester.getUniqueId(), target.getUniqueId(), mode, System.currentTimeMillis());
    requests.put(requester.getUniqueId(), request);

    if (mode == TeleportMode.TPA) {
      message(requester, messages.teleportRequestSent, "target", target.getUsername());
      message(target, messages.teleportRequestReceived, "player", requester.getUsername());
      return;
    }

    message(requester, messages.teleportHereRequestSent, "target", target.getUsername());
    message(target, messages.teleportHereRequestReceived, "player", requester.getUsername());
  }

  private void handleTeleportAllRequest(Player requester) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.teleports) {
      message(requester, messages.networkTeleportsDisabled);
      return;
    }

    int sent = 0;
    for (Player target : proxy.getAllPlayers()) {
      if (target.getUniqueId().equals(requester.getUniqueId())) {
        continue;
      }
      pruneExpiredRequests(target.getUniqueId());
      ConcurrentMap<UUID, TeleportRequest> requests = requestsByTarget.computeIfAbsent(target.getUniqueId(), ignored -> new ConcurrentHashMap<>());
      if (requests.containsKey(requester.getUniqueId())) {
        continue;
      }
      TeleportRequest request = new TeleportRequest(requester.getUniqueId(), target.getUniqueId(), TeleportMode.TPA_HERE, System.currentTimeMillis());
      requests.put(requester.getUniqueId(), request);
      message(target, messages.teleportHereRequestReceived, "player", requester.getUsername());
      sent++;
    }

    if (sent == 0) {
      message(requester, messages.teleportAllNoTargets);
      return;
    }

    message(requester, messages.teleportHereRequestSentCount, "count", Integer.toString(sent));
  }

  private void handleTeleportCancel(Player requester, String targetName) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.teleports) {
      message(requester, messages.networkTeleportsDisabled);
      return;
    }

    int removed = cancelOutgoingRequests(requester, targetName);
    if (removed <= 0) {
      if (targetName != null && !targetName.isBlank()) {
        message(requester, messages.noPendingRequestForTarget, "target", targetName);
      } else {
        message(requester, messages.noOutstandingTeleportRequests);
      }
      return;
    }

    if (targetName != null && !targetName.isBlank()) {
      message(requester, messages.cancelledTeleportWithTarget, "target", targetName);
    } else {
      message(requester, messages.cancelledTeleportCount, "count", Integer.toString(removed));
    }
  }

  private void handleTeleportResponse(Player responder, String requesterName, boolean accept) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.teleports) {
      message(responder, messages.networkTeleportsDisabled);
      return;
    }

    pruneExpiredRequests(responder.getUniqueId());
    if ("*".equals(requesterName)) {
      handleTeleportResponseAll(responder, accept);
      return;
    }
    TeleportRequest request = findRequestForTarget(responder.getUniqueId(), requesterName);
    if (request == null) {
      message(responder, messages.noMatchingTeleportRequest);
      return;
    }

    Player requester = proxy.getPlayer(request.requester()).orElse(null);
    if (requester == null) {
      removeRequest(request);
      message(responder, messages.teleportRequesterOffline);
      return;
    }

    removeRequest(request);
    if (!accept) {
      message(responder, messages.deniedTeleportRequest, "player", requester.getUsername());
      message(requester, messages.teleportDeniedNotice, "player", responder.getUsername());
      return;
    }

    if (request.mode() == TeleportMode.TPA) {
      message(responder, messages.acceptedTeleportRequest, "player", requester.getUsername());
      message(requester, messages.teleportAcceptedNotice, "player", responder.getUsername());
      beginTeleportExecution(requester, responder);
      return;
    }

    message(responder, messages.acceptedTeleportHereRequest, "player", requester.getUsername());
    message(requester, messages.teleportHereAcceptedNotice, "player", responder.getUsername());
    beginTeleportExecution(responder, requester);
  }

  private void handleTeleportResponseAll(Player responder, boolean accept) {
    ProxyConfig.Messages messages = messagesConfig();
    Map<UUID, TeleportRequest> requests = requestsByTarget.get(responder.getUniqueId());
    if (requests == null || requests.isEmpty()) {
      message(responder, messages.noPendingTeleportRequests);
      return;
    }

    List<TeleportRequest> activeRequests = requests.values().stream()
        .sorted(Comparator.comparingLong(TeleportRequest::createdAt))
        .toList();
    if (!accept) {
      int denied = 0;
      for (TeleportRequest request : activeRequests) {
        Player requester = proxy.getPlayer(request.requester()).orElse(null);
        removeRequest(request);
        if (requester != null) {
          message(requester, messages.teleportDeniedNotice, "player", responder.getUsername());
        }
        denied++;
      }
      message(responder, messages.deniedTeleportCount, "count", Integer.toString(denied));
      return;
    }

    long tpHereCount = activeRequests.stream().filter(request -> request.mode() == TeleportMode.TPA_HERE).count();
    if (tpHereCount > 1) {
      message(responder, messages.multipleTeleportHereConflict);
      return;
    }

    int accepted = 0;
    for (TeleportRequest request : activeRequests) {
      Player requester = proxy.getPlayer(request.requester()).orElse(null);
      removeRequest(request);
      if (requester == null) {
        continue;
      }

      if (request.mode() == TeleportMode.TPA) {
        message(requester, messages.teleportAcceptedNotice, "player", responder.getUsername());
        accepted++;
        beginTeleportExecution(requester, responder);
        continue;
      }

      message(requester, messages.teleportHereAcceptedNotice, "player", responder.getUsername());
      accepted++;
      beginTeleportExecution(responder, requester);
    }

    if (accepted == 0) {
      message(responder, messages.noValidTeleportRequests);
      return;
    }
    message(responder, messages.acceptedTeleportCount, "count", Integer.toString(accepted));
  }

  private void handleDirectTeleport(Player actor, String targetName, TeleportMode mode) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.teleports) {
      message(actor, messages.networkTeleportsDisabled);
      return;
    }

    Player target = resolveOnlinePlayer(targetName);
    if (target == null) {
      message(actor, messages.playerNotFound, "target", targetName);
      return;
    }
    if (actor.getUniqueId().equals(target.getUniqueId())) {
      message(actor, messages.cannotTargetSelf);
      return;
    }

    if (mode == TeleportMode.TP) {
      message(actor, messages.directTeleportingTo, "target", target.getUsername());
      beginTeleportExecution(actor, target);
      return;
    }

    message(actor, messages.directTeleportingTargetToYou, "target", target.getUsername());
    message(target, messages.directTeleportBeingMoved, "player", actor.getUsername());
    beginTeleportExecution(target, actor);
  }

  private void handleHome(Player player, String requestedHome) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.homes) {
      message(player, messages.homesDisabled);
      return;
    }

    if (homeService == null) {
      message(player, messages.homeServiceUnavailable);
      return;
    }

    List<HomeService.HomeEntry> homes = homeService.listHomes(player.getUniqueId(), player.getUsername());
    if (homes.isEmpty()) {
      message(player, messages.noHomesFound);
      return;
    }

    if (requestedHome == null || requestedHome.isBlank()) {
      if (homes.size() == 1) {
        executeHome(player, homes.get(0));
        return;
      }
      sendHomeList(player, homes, 1, true, true);
      return;
    }

    HomeService.HomeEntry explicitHome = resolveQualifiedHome(homes, requestedHome);
    if (explicitHome != null) {
      executeHome(player, explicitHome);
      return;
    }

    HomeService.HomeLookup lookup = homeService.lookup(
        player.getUniqueId(),
        player.getUsername(),
        currentServerName(player),
        requestedHome
    );

    switch (lookup.status()) {
      case FOUND -> executeHome(player, lookup.match());
      case MISSING -> message(player, messages.homeMissing, "home", requestedHome);
      case AMBIGUOUS -> {
        message(player, messages.homeAmbiguous);
        sendHomeList(player, lookup.candidates(), 1, false, false);
      }
    }
  }

  private void handleHomes(Player player, int requestedPage) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.homes) {
      message(player, messages.homesDisabled);
      return;
    }
    List<HomeService.HomeEntry> homes = homeService.listHomes(player.getUniqueId(), player.getUsername());
    if (homes.isEmpty()) {
      message(player, messages.noHomesFound);
      return;
    }
    sendHomeList(player, homes, requestedPage <= 0 ? 1 : requestedPage, true, true);
  }

  private void handleMessageToggle(Player player, String requestedState) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.privateMessages) {
      message(player, messages.privateMessagingDisabled);
      return;
    }
    if (socialPreferencesStore == null) {
      message(player, messages.socialSettingsUpdateFailed);
      return;
    }

    try {
      boolean disabled;
      if (requestedState == null || requestedState.isBlank()) {
        disabled = socialPreferencesStore.toggleMessagesDisabled(player.getUniqueId());
      } else if ("on".equalsIgnoreCase(requestedState)) {
        disabled = socialPreferencesStore.setMessagesDisabled(player.getUniqueId(), false);
      } else if ("off".equalsIgnoreCase(requestedState)) {
        disabled = socialPreferencesStore.setMessagesDisabled(player.getUniqueId(), true);
      } else {
        message(player, messages.socialSettingsUpdateFailed);
        return;
      }

      if (disabled) {
        message(player, messages.privateMessagesMuted);
      } else {
        message(player, messages.privateMessagesEnabled);
      }
    } catch (IOException ex) {
      logger.error("Failed to update message toggle for {}", player.getUsername(), ex);
      message(player, messages.socialSettingsUpdateFailed);
    }
  }

  private void handleIgnore(Player player, String targetName, boolean forceRemove) {
    ProxyConfig.Messages messages = messagesConfig();
    if (config == null || !config.privateMessages) {
      message(player, messages.privateMessagingDisabled);
      return;
    }
    if (socialPreferencesStore == null) {
      message(player, messages.socialSettingsUpdateFailed);
      return;
    }

    if (targetName == null || targetName.isBlank()) {
      if (forceRemove) {
        message(player, messages.socialSettingsUpdateFailed);
        return;
      }
      sendIgnoreList(player);
      return;
    }

    if (player.getUsername().equalsIgnoreCase(targetName)) {
      message(player, messages.cannotIgnoreSelf);
      return;
    }

    SocialPreferencesStore.IgnoredEntry storedEntry = socialPreferencesStore.resolveIgnoredByName(player.getUniqueId(), targetName);
    Player target = resolveOnlinePlayer(targetName);

    try {
      if (forceRemove) {
        SocialPreferencesStore.IgnoreResult result;
        if (target != null) {
          result = socialPreferencesStore.removeIgnore(player.getUniqueId(), target.getUniqueId());
        } else if (storedEntry != null) {
          result = socialPreferencesStore.removeIgnore(player.getUniqueId(), storedEntry.uuid());
        } else {
          message(player, messages.notIgnoringTarget, "target", targetName);
          return;
        }

        if (result.action() == SocialPreferencesStore.IgnoreAction.REMOVED && result.entry() != null) {
          message(player, messages.ignoreRemoved, "target", result.entry().name());
        } else {
          message(player, messages.notIgnoringTarget, "target", targetName);
        }
        return;
      }

      if (target != null && target.getUniqueId().equals(player.getUniqueId())) {
        message(player, messages.cannotIgnoreSelf);
        return;
      }

      if (target != null) {
        SocialPreferencesStore.IgnoreResult result = socialPreferencesStore.toggleIgnore(
            player.getUniqueId(),
            target.getUniqueId(),
            target.getUsername()
        );
        if (result.action() == SocialPreferencesStore.IgnoreAction.ADDED) {
          message(player, messages.ignoreAdded, "target", target.getUsername());
        } else {
          message(player, messages.ignoreRemoved, "target", result.entry() == null ? target.getUsername() : result.entry().name());
        }
        return;
      }

      if (storedEntry != null) {
        SocialPreferencesStore.IgnoreResult result = socialPreferencesStore.removeIgnore(player.getUniqueId(), storedEntry.uuid());
        if (result.action() == SocialPreferencesStore.IgnoreAction.REMOVED) {
          message(player, messages.ignoreRemoved, "target", storedEntry.name());
          return;
        }
      }

      message(player, messages.playerNotFound, "target", targetName);
    } catch (IOException ex) {
      logger.error("Failed to update ignore state for {}", player.getUsername(), ex);
      message(player, messages.socialSettingsUpdateFailed);
    }
  }

  private void handleStashOpen(Player player) {
    ProxyConfig.Messages messages = messagesConfig();
    if (!networkStashEnabled()) {
      message(player, messages.stashDisabled);
      return;
    }
    if (networkStashStore == null) {
      message(player, messages.stashSaveFailed);
      return;
    }

    UUID sessionId = UUID.randomUUID();
    stashSessions.put(player.getUniqueId(), sessionId);
    NetworkStashStore.StashSnapshot snapshot = networkStashStore.snapshot(
        player.getUniqueId(),
        networkStashZoneId,
        configuredStashSlots()
    );
    sendStashSync(player, sessionId, STASH_ACTION_OPEN, true, null, snapshot);
  }

  private void handleStashClose(Player player, UUID sessionId) {
    if (sessionId == null) {
      return;
    }
    stashSessions.remove(player.getUniqueId(), sessionId);
  }

  private void handleStashDeposit(Player player, UUID sessionId, byte[] itemBytes) {
    ProxyConfig.Messages messages = messagesConfig();
    if (!networkStashEnabled()) {
      message(player, messages.stashDisabled);
      return;
    }
    if (networkStashStore == null) {
      message(player, messages.stashSaveFailed);
      return;
    }
    if (!hasActiveStashSession(player.getUniqueId(), sessionId)) {
      message(player, messages.stashSessionExpired);
      return;
    }

    try {
      NetworkStashStore.DepositResult result = networkStashStore.deposit(
          player.getUniqueId(),
          networkStashZoneId,
          configuredStashSlots(),
          itemBytes
      );
      if (result.status() != NetworkStashStore.StashActionStatus.SUCCESS) {
        message(player, stashStatusTemplate(result.status()));
      }
      sendStashSync(player, sessionId, STASH_ACTION_DEPOSIT,
          result.status() == NetworkStashStore.StashActionStatus.SUCCESS,
          null,
          result.snapshot());
    } catch (IOException ex) {
      logger.error("Failed to update network stash deposit for {}", player.getUsername(), ex);
      message(player, messages.stashSaveFailed);
    }
  }

  private void handleStashWithdraw(Player player, UUID sessionId, int slot) {
    ProxyConfig.Messages messages = messagesConfig();
    if (!networkStashEnabled()) {
      message(player, messages.stashDisabled);
      return;
    }
    if (networkStashStore == null) {
      message(player, messages.stashSaveFailed);
      return;
    }
    if (!hasActiveStashSession(player.getUniqueId(), sessionId)) {
      message(player, messages.stashSessionExpired);
      return;
    }

    try {
      NetworkStashStore.WithdrawResult result = networkStashStore.withdraw(
          player.getUniqueId(),
          networkStashZoneId,
          configuredStashSlots(),
          slot
      );
      if (result.status() != NetworkStashStore.StashActionStatus.SUCCESS) {
        message(player, stashStatusTemplate(result.status()));
      }
      sendStashSync(player, sessionId, STASH_ACTION_WITHDRAW,
          result.status() == NetworkStashStore.StashActionStatus.SUCCESS,
          result.withdrawnItem(),
          result.snapshot());
    } catch (IOException ex) {
      logger.error("Failed to update network stash withdraw for {}", player.getUsername(), ex);
      message(player, messages.stashSaveFailed);
    }
  }

  private void executeHome(Player player, HomeService.HomeEntry home) {
    ProxyConfig.Messages messages = messagesConfig();
    String currentServer = currentServerName(player);
    if (currentServer != null && currentServer.equalsIgnoreCase(home.server())) {
      sendExecutePlayerCommand(player, player, "home " + home.name());
      return;
    }

    RegisteredServer server = proxy.getServer(home.server()).orElse(null);
    if (server == null) {
      message(player, messages.targetServerNotRegistered, "server", home.server());
      return;
    }

    message(player, messages.homeSendingToServer, "server", home.server(), "home", home.name());
    startCrossServerAction(player, server, PendingServerAction.command(home.server(), "home " + home.name()));
  }

  private void beginTeleportExecution(Player movingPlayer, Player anchorPlayer) {
    ProxyConfig.Messages messages = messagesConfig();
    String anchorServer = currentServerName(anchorPlayer);
    if (anchorServer == null) {
      message(movingPlayer, messages.teleportTargetUnavailable);
      return;
    }

    String movingServer = currentServerName(movingPlayer);
    if (movingServer != null && movingServer.equalsIgnoreCase(anchorServer)) {
      sendTeleportToPlayer(movingPlayer, movingPlayer, anchorPlayer.getUniqueId());
      return;
    }

    RegisteredServer targetServer = proxy.getServer(anchorServer).orElse(null);
    if (targetServer == null) {
      message(movingPlayer, messages.targetServerNotRegistered, "server", anchorServer);
      return;
    }

    startCrossServerAction(movingPlayer, targetServer,
        PendingServerAction.teleport(anchorServer, anchorPlayer.getUniqueId()));
  }

  private void sendHomeList(Player player, List<HomeService.HomeEntry> homes, int requestedPage,
                            boolean includeHeader, boolean paginate) {
    ProxyConfig.Messages messages = messagesConfig();
    List<HomeService.HomeEntry> sorted = new ArrayList<>(homes);
    String currentServer = currentServerName(player);
    sorted.sort(Comparator.comparing((HomeService.HomeEntry entry) -> {
      if (currentServer == null) {
        return 1;
      }
      return entry.server().equalsIgnoreCase(currentServer) ? 0 : 1;
    }).thenComparing(HomeService.HomeEntry::server, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(HomeService.HomeEntry::name, String.CASE_INSENSITIVE_ORDER));

    int totalPages = 1;
    int page = 1;
    List<HomeService.HomeEntry> visibleHomes = sorted;
    if (paginate) {
      int pageSize = config != null && config.homesPerPage > 0 ? config.homesPerPage : 8;
      totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) pageSize));
      page = Math.max(1, Math.min(requestedPage, totalPages));
      if (requestedPage != page) {
        message(player, messages.homeInvalidPage,
            "page", Integer.toString(requestedPage),
            "pages", Integer.toString(totalPages));
      }
      int fromIndex = (page - 1) * pageSize;
      int toIndex = Math.min(sorted.size(), fromIndex + pageSize);
      visibleHomes = sorted.subList(fromIndex, toIndex);
    }

    if (includeHeader) {
      message(player, messages.homeListHeader, "page", Integer.toString(page), "pages", Integer.toString(totalPages));
    }

    for (HomeService.HomeEntry home : visibleHomes) {
      Component line = prefixed(messages.homeListEntry, "home", home.name(), "server", home.server())
          .clickEvent(ClickEvent.runCommand("/home " + home.server() + ":" + home.name()))
          .hoverEvent(HoverEvent.showText(render(messages.homeListHover, "home", home.name(), "server", home.server())));
      player.sendMessage(line);
    }

    if (paginate && totalPages > 1) {
      player.sendMessage(buildHomePaginationLine(messages, page, totalPages));
    }
  }

  private void sendIgnoreList(Player player) {
    ProxyConfig.Messages messages = messagesConfig();
    List<SocialPreferencesStore.IgnoredEntry> ignored = socialPreferencesStore == null
        ? List.of()
        : socialPreferencesStore.listIgnored(player.getUniqueId());
    if (ignored.isEmpty()) {
      message(player, messages.ignoreListEmpty);
      return;
    }

    message(player, messages.ignoreListHeader);
    for (SocialPreferencesStore.IgnoredEntry entry : ignored) {
      message(player, messages.ignoreListEntry, "target", entry.name());
    }
  }

  private Component buildHomePaginationLine(ProxyConfig.Messages messages, int page, int totalPages) {
    Component line = prefixed(messages.homePageIndicator,
        "page", Integer.toString(page),
        "pages", Integer.toString(totalPages));
    if (page > 1) {
      line = line.append(Component.space()).append(render(messages.homePagePrevious,
              "page", Integer.toString(page - 1),
              "pages", Integer.toString(totalPages))
          .clickEvent(ClickEvent.runCommand("/homes " + HOMES_PAGE_KEYWORD + " " + (page - 1)))
          .hoverEvent(HoverEvent.showText(render(messages.homePagePreviousHover, "page", Integer.toString(page - 1)))));
    }
    if (page < totalPages) {
      line = line.append(Component.space()).append(render(messages.homePageNext,
              "page", Integer.toString(page + 1),
              "pages", Integer.toString(totalPages))
          .clickEvent(ClickEvent.runCommand("/homes " + HOMES_PAGE_KEYWORD + " " + (page + 1)))
          .hoverEvent(HoverEvent.showText(render(messages.homePageNextHover, "page", Integer.toString(page + 1)))));
    }
    return line;
  }

  private boolean sendExecutePlayerCommand(Player recipient, Player commandTarget, String command) {
    ProxyConfig.Messages messages = messagesConfig();
    try {
      byte[] packet = BridgeProtocol.encode(BridgeMessageType.EXECUTE_PLAYER_COMMAND, out -> {
        BridgeProtocol.writeUuid(out, commandTarget.getUniqueId());
        BridgeProtocol.writeString(out, command);
      });
      if (!sendToBackend(recipient, packet)) {
        message(recipient, messages.failedDispatchCommand);
        return false;
      }
      return true;
    } catch (IOException ex) {
      logger.error("Failed to encode player command bridge packet", ex);
      message(recipient, messages.failedDispatchCommand);
      return false;
    }
  }

  private boolean sendTeleportToPlayer(Player recipient, Player movingPlayer, UUID targetUuid) {
    ProxyConfig.Messages messages = messagesConfig();
    try {
      byte[] packet = BridgeProtocol.encode(BridgeMessageType.TELEPORT_TO_PLAYER, out -> {
        BridgeProtocol.writeUuid(out, movingPlayer.getUniqueId());
        BridgeProtocol.writeUuid(out, targetUuid);
      });
      if (!sendToBackend(recipient, packet)) {
        message(recipient, messages.failedDispatchTeleport);
        return false;
      }
      return true;
    } catch (IOException ex) {
      logger.error("Failed to encode teleport bridge packet", ex);
      message(recipient, messages.failedDispatchTeleport);
      return false;
    }
  }

  private boolean sendToBackend(Player player, byte[] payload) {
    return player.getCurrentServer().map(connection -> connection.sendPluginMessage(channel, payload)).orElse(false);
  }

  private void scheduleNetworkPlayerSnapshot() {
    proxy.getScheduler().buildTask(this, this::broadcastNetworkPlayerSnapshot)
        .delay(NETWORK_PLAYER_SNAPSHOT_DELAY)
        .schedule();
  }

  private void broadcastNetworkPlayerSnapshot() {
    List<NetworkPlayerSnapshotEntry> snapshot = new ArrayList<>();
    Map<String, Player> representatives = new LinkedHashMap<>();
    for (Player player : proxy.getAllPlayers()) {
      String server = currentServerName(player);
      if (server == null || server.isBlank()) {
        continue;
      }
      snapshot.add(new NetworkPlayerSnapshotEntry(player.getUsername(), server));
      representatives.putIfAbsent(server, player);
    }
    snapshot.sort(Comparator.comparing(NetworkPlayerSnapshotEntry::username, String.CASE_INSENSITIVE_ORDER));
    for (Player representative : representatives.values()) {
      sendNetworkPlayerSnapshot(representative, snapshot);
    }
  }

  private void sendNetworkPlayerSnapshot(Player representative, List<NetworkPlayerSnapshotEntry> snapshot) {
    try {
      byte[] packet = BridgeProtocol.encode(BridgeMessageType.NETWORK_PLAYER_SNAPSHOT, out -> {
        out.writeInt(snapshot.size());
        for (NetworkPlayerSnapshotEntry entry : snapshot) {
          BridgeProtocol.writeString(out, entry.username());
          BridgeProtocol.writeString(out, entry.server());
        }
      });
      sendToBackend(representative, packet);
    } catch (IOException ex) {
      logger.error("Failed to encode network player snapshot bridge packet", ex);
    }
  }

  private void sendStashSync(Player player, UUID sessionId, byte action, boolean success,
                             byte[] withdrawnItem, NetworkStashStore.StashSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    try {
      byte[] packet = BridgeProtocol.encode(BridgeMessageType.STASH_SYNC, out -> {
        BridgeProtocol.writeUuid(out, sessionId);
        out.writeByte(action);
        out.writeBoolean(success);
        BridgeProtocol.writeNullableByteArray(out, withdrawnItem);
        out.writeInt(snapshot.slots().size());
        for (byte[] slot : snapshot.slots()) {
          BridgeProtocol.writeNullableByteArray(out, slot);
        }
        BridgeProtocol.writeString(out, networkStashZoneId.getId());
        out.writeBoolean(snapshot.depositAvailable());
        out.writeBoolean(snapshot.withdrawAvailable());
      });
      if (!sendToBackend(player, packet)) {
        message(player, messagesConfig().stashSaveFailed);
      }
    } catch (IOException ex) {
      logger.error("Failed to encode stash sync bridge packet", ex);
      message(player, messagesConfig().stashSaveFailed);
    }
  }

  private void startCrossServerAction(Player player, RegisteredServer targetServer, PendingServerAction action) {
    if (tryServerManagerCompatibility(player, action)) {
      return;
    }
    UUID playerUuid = player.getUniqueId();
    pendingActions.put(playerUuid, action);
    attemptFallbackConnect(playerUuid, targetServer, action, 0);
  }

  private boolean tryServerManagerCompatibility(Player player, PendingServerAction action) {
    if (config == null || serverManagerAccessor == null || config.serverManagerCompatibility == null) {
      return false;
    }
    if (!config.serverManagerCompatibility.enabled) {
      return false;
    }
    if (config.serverManagerCompatibility.requireEnabledFlag && !serverManagerAccessor.isCompatibilityEnabled()) {
      return false;
    }

    CompletableFuture<Boolean> future = serverManagerAccessor.connectPlayerWhenReady(
        player,
        action.targetServer(),
        () -> dispatchQueuedAction(player.getUniqueId(), action, 0)
    );
    if (future == null) {
      return false;
    }

    pendingActions.remove(player.getUniqueId());
    future.whenComplete((success, error) -> {
      if (proxy.getPlayer(player.getUniqueId()).isEmpty()) {
        return;
      }
      if (error != null) {
        logger.warn("ServerManager compatibility handoff failed for {} -> {}",
            player.getUsername(), action.targetServer(), error);
      } else if (!Boolean.TRUE.equals(success)) {
        logger.info("ServerManager compatibility handoff did not complete for {} -> {}",
            player.getUsername(), action.targetServer());
      }
    });
    return true;
  }

  private void attemptFallbackConnect(UUID playerUuid, RegisteredServer targetServer, PendingServerAction action, int attempt) {
    ProxyConfig.Messages messages = messagesConfig();
    Player player = proxy.getPlayer(playerUuid).orElse(null);
    if (player == null) {
      pendingActions.remove(playerUuid, action);
      return;
    }

    player.createConnectionRequest(targetServer).connect().whenComplete((result, error) -> {
      if (proxy.getPlayer(playerUuid).isEmpty()) {
        pendingActions.remove(playerUuid, action);
        return;
      }
      if (error != null) {
        if (attempt < FALLBACK_CONNECT_RETRY_ATTEMPTS) {
          retryFallbackConnect(playerUuid, action, attempt + 1);
          return;
        }
        if (pendingActions.remove(playerUuid, action)) {
          message(player, messages.failedConnectToServer, "server", action.targetServer());
        }
        logger.warn("Fallback cross-server connect failed for {} -> {}",
            player.getUsername(), action.targetServer(), error);
        return;
      }
      if (result == null || result.isSuccessful()) {
        return;
      }
      if (result.getStatus() == com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.SERVER_DISCONNECTED
          && attempt < FALLBACK_CONNECT_RETRY_ATTEMPTS) {
        retryFallbackConnect(playerUuid, action, attempt + 1);
        return;
      }
      if (pendingActions.remove(playerUuid, action)) {
        message(player, messages.failedConnectToServer, "server", action.targetServer());
      }
    });
  }

  private void retryFallbackConnect(UUID playerUuid, PendingServerAction action, int nextAttempt) {
    proxy.getScheduler().buildTask(this, () -> {
      if (pendingActions.get(playerUuid) != action) {
        return;
      }
      RegisteredServer retryTarget = proxy.getServer(action.targetServer()).orElse(null);
      if (retryTarget == null) {
        pendingActions.remove(playerUuid, action);
        return;
      }
      attemptFallbackConnect(playerUuid, retryTarget, action, nextAttempt);
    }).delay(Duration.ofSeconds(2)).schedule();
  }

  private void dispatchPendingAction(UUID playerUuid, PendingServerAction action, int attempt) {
    dispatchAction(playerUuid, action, attempt, true);
  }

  private void dispatchQueuedAction(UUID playerUuid, PendingServerAction action, int attempt) {
    dispatchAction(playerUuid, action, attempt, false);
  }

  private void dispatchAction(UUID playerUuid, PendingServerAction action, int attempt, boolean tracked) {
    ProxyConfig.Messages messages = messagesConfig();
    Duration delay = attempt == 0 ? Duration.ofMillis(500) : Duration.ofSeconds(1);
    proxy.getScheduler().buildTask(this, () -> {
      if (tracked) {
        PendingServerAction current = pendingActions.get(playerUuid);
        if (current == null || current != action) {
          return;
        }
      }

      Player player = proxy.getPlayer(playerUuid).orElse(null);
      if (player == null) {
        if (tracked) {
          pendingActions.remove(playerUuid, action);
        }
        return;
      }

      String currentServer = currentServerName(player);
      if (currentServer == null || !action.targetServer().equalsIgnoreCase(currentServer)) {
        if (attempt < 5) {
          dispatchAction(playerUuid, action, attempt + 1, tracked);
        } else {
          if (tracked) {
            pendingActions.remove(playerUuid, action);
          }
          message(player, messages.crossServerActionTimedOut);
        }
        return;
      }

      boolean sent;
      if (action.command() != null) {
        sent = sendExecutePlayerCommand(player, player, action.command());
      } else {
        sent = sendTeleportToPlayer(player, player, action.targetPlayer());
      }

      if (sent) {
        if (tracked) {
          pendingActions.remove(playerUuid, action);
        }
      } else if (attempt < 5) {
        dispatchAction(playerUuid, action, attempt + 1, tracked);
      } else {
        if (tracked) {
          pendingActions.remove(playerUuid, action);
        }
        message(player, messages.crossServerActionTimedOut);
      }
    }).delay(delay).schedule();
  }

  private void pruneExpiredRequests(UUID targetUuid) {
    if (config == null) {
      return;
    }
    Map<UUID, TeleportRequest> requests = requestsByTarget.get(targetUuid);
    if (requests == null || requests.isEmpty()) {
      return;
    }
    long oldestAllowed = System.currentTimeMillis() - Duration.ofSeconds(config.teleportRequestTimeoutSeconds).toMillis();
    requests.values().removeIf(request -> request.createdAt() < oldestAllowed);
    if (requests.isEmpty()) {
      requestsByTarget.remove(targetUuid, requests);
    }
  }

  private TeleportRequest findRequestForTarget(UUID targetUuid, String requesterName) {
    Map<UUID, TeleportRequest> requests = requestsByTarget.get(targetUuid);
    if (requests == null || requests.isEmpty()) {
      return null;
    }

    if (requesterName != null && !requesterName.isBlank()) {
      for (TeleportRequest request : requests.values()) {
        Optional<Player> requester = proxy.getPlayer(request.requester());
        if (requester.isPresent() && requester.get().getUsername().equalsIgnoreCase(requesterName)) {
          return request;
        }
      }
      return null;
    }

    return requests.values().stream()
        .max(Comparator.comparingLong(TeleportRequest::createdAt))
        .orElse(null);
  }

  private void removeRequest(TeleportRequest request) {
    Map<UUID, TeleportRequest> requests = requestsByTarget.get(request.target());
    if (requests == null) {
      return;
    }
    requests.remove(request.requester());
    if (requests.isEmpty()) {
      requestsByTarget.remove(request.target(), requests);
    }
  }

  private int cancelOutgoingRequests(Player requester, String targetName) {
    int removed = 0;
    String loweredTargetName = targetName == null ? null : targetName.toLowerCase(java.util.Locale.ROOT);
    for (Map.Entry<UUID, ConcurrentMap<UUID, TeleportRequest>> entry : requestsByTarget.entrySet()) {
      UUID targetUuid = entry.getKey();
      ConcurrentMap<UUID, TeleportRequest> requests = entry.getValue();
      TeleportRequest request = requests.get(requester.getUniqueId());
      if (request == null) {
        continue;
      }

      if (loweredTargetName != null && !loweredTargetName.isBlank()) {
        Player target = proxy.getPlayer(targetUuid).orElse(null);
        if (target == null || !target.getUsername().toLowerCase(java.util.Locale.ROOT).equals(loweredTargetName)) {
          continue;
        }
      }

      requests.remove(requester.getUniqueId());
      if (requests.isEmpty()) {
        requestsByTarget.remove(targetUuid, requests);
      }
      removed++;
    }
    return removed;
  }

  private Player resolveOnlinePlayer(String username) {
    if (username == null || username.isBlank()) {
      return null;
    }
    return proxy.getPlayer(username).orElse(null);
  }

  private String currentServerName(Player player) {
    return player.getCurrentServer()
        .map(ServerConnection::getServerInfo)
        .map(info -> info.getName())
        .orElse(null);
  }

  private HomeService.HomeEntry resolveQualifiedHome(List<HomeService.HomeEntry> homes, String requestedHome) {
    int colon = requestedHome.indexOf(':');
    if (colon <= 0 || colon >= requestedHome.length() - 1) {
      return null;
    }
    String requestedServer = requestedHome.substring(0, colon);
    String requestedName = requestedHome.substring(colon + 1);
    for (HomeService.HomeEntry home : homes) {
      if (home.server().equalsIgnoreCase(requestedServer) && home.name().equalsIgnoreCase(requestedName)) {
        return home;
      }
    }
    return null;
  }

  private ProxyConfig.Messages messagesConfig() {
    return config != null && config.messages != null ? config.messages : new ProxyConfig.Messages();
  }

  private boolean networkStashEnabled() {
    return config != null && config.networkStash != null && config.networkStash.enabled;
  }

  private int configuredStashSlots() {
    if (config == null || config.networkStash == null) {
      return 27;
    }
    return Math.max(9, config.networkStash.slots);
  }

  private ZoneId resolveNetworkStashZoneId(ProxyConfig currentConfig) {
    String configured = currentConfig != null
        && currentConfig.networkStash != null
        && currentConfig.networkStash.timezone != null
        && !currentConfig.networkStash.timezone.isBlank()
        ? currentConfig.networkStash.timezone
        : "America/New_York";
    try {
      return ZoneId.of(configured);
    } catch (DateTimeException ex) {
      logger.warn("Invalid network stash timezone '{}', falling back to America/New_York", configured);
      return ZoneId.of("America/New_York");
    }
  }

  private boolean hasActiveStashSession(UUID playerUuid, UUID sessionId) {
    if (sessionId == null) {
      return false;
    }
    UUID activeSession = stashSessions.get(playerUuid);
    return sessionId.equals(activeSession);
  }

  private String stashStatusTemplate(NetworkStashStore.StashActionStatus status) {
    ProxyConfig.Messages messages = messagesConfig();
    return switch (status) {
      case DEPOSIT_ALREADY_USED -> messages.stashDepositUsed;
      case WITHDRAW_ALREADY_USED -> messages.stashWithdrawUsed;
      case STASH_FULL -> messages.stashFull;
      case EMPTY_SLOT -> messages.stashEmptySlot;
      case INVALID_SLOT, INVALID_ITEM -> messages.stashInvalidAction;
      case SUCCESS -> "";
    };
  }

  private void broadcast(Component component) {
    for (Player player : proxy.getAllPlayers()) {
      player.sendMessage(component);
    }
  }

  private void message(Player player, String template, String... placeholders) {
    player.sendMessage(prefixed(template, placeholders));
  }

  private Component prefixed(String template, String... placeholders) {
    String prefix = config != null && config.prefix != null ? config.prefix : "";
    return MINI.deserialize(prefix + (template == null ? "" : template), placeholders(placeholders));
  }

  private Component render(String template, String... placeholders) {
    return MINI.deserialize(template == null ? "" : template, placeholders(placeholders));
  }

  private TagResolver[] placeholders(String... placeholders) {
    if (placeholders == null || placeholders.length == 0) {
      return new TagResolver[0];
    }
    List<TagResolver> resolvers = new ArrayList<>();
    for (int i = 0; i + 1 < placeholders.length; i += 2) {
      resolvers.add(Placeholder.unparsed(placeholders[i], placeholders[i + 1] == null ? "" : placeholders[i + 1]));
    }
    return resolvers.toArray(TagResolver[]::new);
  }

  private record TeleportRequest(UUID requester, UUID target, TeleportMode mode, long createdAt) {
  }

  private enum TeleportMode {
    TPA,
    TPA_HERE,
    TP,
    TP_HERE
  }

  private record PendingServerAction(String targetServer, UUID targetPlayer, String command) {
    private static PendingServerAction teleport(String targetServer, UUID targetPlayer) {
      return new PendingServerAction(targetServer, Objects.requireNonNull(targetPlayer, "targetPlayer"), null);
    }

    private static PendingServerAction command(String targetServer, String command) {
      return new PendingServerAction(targetServer, null, Objects.requireNonNull(command, "command"));
    }
  }

  private record NetworkPlayerSnapshotEntry(String username, String server) {
  }

  private record JoinLeaveState(boolean enabled, boolean silentJoin, boolean silentLeave) {
  }
}
