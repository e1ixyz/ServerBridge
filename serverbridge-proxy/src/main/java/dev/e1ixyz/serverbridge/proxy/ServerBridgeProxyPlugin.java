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
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
  private static final Component PREFIX = Component.text("[ServerBridge] ", NamedTextColor.DARK_AQUA);
  private static final int FALLBACK_CONNECT_RETRY_ATTEMPTS = 5;

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDir;
  private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL);
  private final ConcurrentMap<UUID, UUID> lastPrivatePartner = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ConcurrentMap<UUID, TeleportRequest>> requestsByTarget = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, PendingServerAction> pendingActions = new ConcurrentHashMap<>();

  private ProxyConfig config;
  private HomeService homeService;
  private ServerManagerAccessor serverManagerAccessor;

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
      serverManagerAccessor = new ServerManagerAccessor(proxy, dataDir, logger);
      homeService = new HomeService(config, serverManagerAccessor, logger);
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
        case HOMES -> handleHomes(player);
        default -> logger.warn("Ignoring unsupported proxy-bound bridge packet {}", decoded.type());
      }
    } catch (Exception ex) {
      logger.error("Failed to process bridge payload from {}", player.getUsername(), ex);
    }
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    Player player = event.getPlayer();
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
    UUID uuid = event.getPlayer().getUniqueId();
    pendingActions.remove(uuid);
    lastPrivatePartner.remove(uuid);
    requestsByTarget.remove(uuid);
    requestsByTarget.values().forEach(map -> map.remove(uuid));
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

  private void handlePrivateMessage(Player sender, String targetName, String message) {
    if (config == null || !config.privateMessages) {
      message(sender, text("Private messaging is disabled.", NamedTextColor.RED));
      return;
    }
    Player target = resolveOnlinePlayer(targetName);
    if (target == null) {
      message(sender, text("Player not found: " + targetName, NamedTextColor.RED));
      return;
    }
    if (sender.getUniqueId().equals(target.getUniqueId())) {
      message(sender, text("You cannot message yourself.", NamedTextColor.RED));
      return;
    }

    Component body = Component.text(message, NamedTextColor.WHITE);
    sender.sendMessage(prefix(Component.text("to ", NamedTextColor.GRAY)
        .append(Component.text(target.getUsername(), NamedTextColor.LIGHT_PURPLE))
        .append(Component.text(": ", NamedTextColor.GRAY))
        .append(body)));
    target.sendMessage(prefix(Component.text("from ", NamedTextColor.GRAY)
        .append(Component.text(sender.getUsername(), NamedTextColor.LIGHT_PURPLE))
        .append(Component.text(": ", NamedTextColor.GRAY))
        .append(body)));

    lastPrivatePartner.put(sender.getUniqueId(), target.getUniqueId());
    lastPrivatePartner.put(target.getUniqueId(), sender.getUniqueId());
  }

  private void handlePrivateReply(Player sender, String message) {
    UUID targetUuid = lastPrivatePartner.get(sender.getUniqueId());
    if (targetUuid == null) {
      message(sender, text("Nobody has messaged you recently.", NamedTextColor.RED));
      return;
    }
    Player target = proxy.getPlayer(targetUuid).orElse(null);
    if (target == null) {
      message(sender, text("That player is no longer online.", NamedTextColor.RED));
      return;
    }
    handlePrivateMessage(sender, target.getUsername(), message);
  }

  private void handleTeleportRequest(Player requester, String targetName, TeleportMode mode) {
    if (config == null || !config.teleports) {
      message(requester, text("Network teleports are disabled.", NamedTextColor.RED));
      return;
    }

    Player target = resolveOnlinePlayer(targetName);
    if (target == null) {
      message(requester, text("Player not found: " + targetName, NamedTextColor.RED));
      return;
    }
    if (requester.getUniqueId().equals(target.getUniqueId())) {
      message(requester, text("You cannot target yourself.", NamedTextColor.RED));
      return;
    }

    pruneExpiredRequests(target.getUniqueId());
    ConcurrentMap<UUID, TeleportRequest> requests = requestsByTarget.computeIfAbsent(target.getUniqueId(), ignored -> new ConcurrentHashMap<>());
    if (requests.containsKey(requester.getUniqueId())) {
      message(requester, text("You already have a pending teleport request with " + target.getUsername() + ".", NamedTextColor.RED));
      return;
    }

    TeleportRequest request = new TeleportRequest(requester.getUniqueId(), target.getUniqueId(), mode, System.currentTimeMillis());
    requests.put(requester.getUniqueId(), request);

    if (mode == TeleportMode.TPA) {
      message(requester, text("Teleport request sent to " + target.getUsername() + ".", NamedTextColor.GREEN));
      target.sendMessage(prefix(Component.text(requester.getUsername(), NamedTextColor.LIGHT_PURPLE)
          .append(Component.text(" wants to teleport to you. Use /tpaccept or /tpdeny.", NamedTextColor.GRAY))));
      return;
    }

    message(requester, text("Teleport-here request sent to " + target.getUsername() + ".", NamedTextColor.GREEN));
    target.sendMessage(prefix(Component.text(requester.getUsername(), NamedTextColor.LIGHT_PURPLE)
        .append(Component.text(" wants you to teleport to them. Use /tpaccept or /tpdeny.", NamedTextColor.GRAY))));
  }

  private void handleTeleportAllRequest(Player requester) {
    if (config == null || !config.teleports) {
      message(requester, text("Network teleports are disabled.", NamedTextColor.RED));
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
      target.sendMessage(prefix(Component.text(requester.getUsername(), NamedTextColor.LIGHT_PURPLE)
          .append(Component.text(" wants you to teleport to them. Use /tpaccept or /tpdeny.", NamedTextColor.GRAY))));
      sent++;
    }

    if (sent == 0) {
      message(requester, text("There are no other online players to request.", NamedTextColor.RED));
      return;
    }

    message(requester, text("Teleport-here request sent to " + sent + " player(s).", NamedTextColor.GREEN));
  }

  private void handleTeleportCancel(Player requester, String targetName) {
    if (config == null || !config.teleports) {
      message(requester, text("Network teleports are disabled.", NamedTextColor.RED));
      return;
    }

    int removed = cancelOutgoingRequests(requester, targetName);
    if (removed <= 0) {
      if (targetName != null && !targetName.isBlank()) {
        message(requester, text("You do not have a pending request for " + targetName + ".", NamedTextColor.RED));
      } else {
        message(requester, text("You do not have any outstanding teleport requests.", NamedTextColor.RED));
      }
      return;
    }

    if (targetName != null && !targetName.isBlank()) {
      message(requester, text("Cancelled teleport request(s) involving " + targetName + ".", NamedTextColor.YELLOW));
    } else {
      message(requester, text("Cancelled " + removed + " outstanding teleport request(s).", NamedTextColor.YELLOW));
    }
  }

  private void handleTeleportResponse(Player responder, String requesterName, boolean accept) {
    if (config == null || !config.teleports) {
      message(responder, text("Network teleports are disabled.", NamedTextColor.RED));
      return;
    }

    pruneExpiredRequests(responder.getUniqueId());
    if ("*".equals(requesterName)) {
      handleTeleportResponseAll(responder, accept);
      return;
    }
    TeleportRequest request = findRequestForTarget(responder.getUniqueId(), requesterName);
    if (request == null) {
      message(responder, text("You do not have a matching teleport request.", NamedTextColor.RED));
      return;
    }

    Player requester = proxy.getPlayer(request.requester()).orElse(null);
    if (requester == null) {
      removeRequest(request);
      message(responder, text("That player is no longer online.", NamedTextColor.RED));
      return;
    }

    removeRequest(request);
    if (!accept) {
      message(responder, text("Denied teleport request from " + requester.getUsername() + ".", NamedTextColor.YELLOW));
      message(requester, text(responder.getUsername() + " denied your teleport request.", NamedTextColor.RED));
      return;
    }

    if (request.mode() == TeleportMode.TPA) {
      message(responder, text("Accepted teleport request from " + requester.getUsername() + ".", NamedTextColor.GREEN));
      message(requester, text(responder.getUsername() + " accepted your teleport request.", NamedTextColor.GREEN));
      beginTeleportExecution(requester, responder);
      return;
    }

    message(responder, text("Accepted teleport-here request from " + requester.getUsername() + ".", NamedTextColor.GREEN));
    message(requester, text(responder.getUsername() + " accepted your teleport-here request.", NamedTextColor.GREEN));
    beginTeleportExecution(responder, requester);
  }

  private void handleTeleportResponseAll(Player responder, boolean accept) {
    Map<UUID, TeleportRequest> requests = requestsByTarget.get(responder.getUniqueId());
    if (requests == null || requests.isEmpty()) {
      message(responder, text("You do not have any pending teleport requests.", NamedTextColor.RED));
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
          message(requester, text(responder.getUsername() + " denied your teleport request.", NamedTextColor.RED));
        }
        denied++;
      }
      message(responder, text("Denied " + denied + " teleport request(s).", NamedTextColor.YELLOW));
      return;
    }

    long tpHereCount = activeRequests.stream().filter(request -> request.mode() == TeleportMode.TPA_HERE).count();
    if (tpHereCount > 1) {
      message(responder, text("You have multiple /tpahere requests. Accept them individually to avoid conflicting teleports.", NamedTextColor.RED));
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
        message(requester, text(responder.getUsername() + " accepted your teleport request.", NamedTextColor.GREEN));
        accepted++;
        beginTeleportExecution(requester, responder);
        continue;
      }

      message(requester, text(responder.getUsername() + " accepted your teleport-here request.", NamedTextColor.GREEN));
      accepted++;
      beginTeleportExecution(responder, requester);
    }

    if (accepted == 0) {
      message(responder, text("No pending teleport requests were still valid.", NamedTextColor.RED));
      return;
    }
    message(responder, text("Accepted " + accepted + " teleport request(s).", NamedTextColor.GREEN));
  }

  private void handleDirectTeleport(Player actor, String targetName, TeleportMode mode) {
    if (config == null || !config.teleports) {
      message(actor, text("Network teleports are disabled.", NamedTextColor.RED));
      return;
    }

    Player target = resolveOnlinePlayer(targetName);
    if (target == null) {
      message(actor, text("Player not found: " + targetName, NamedTextColor.RED));
      return;
    }
    if (actor.getUniqueId().equals(target.getUniqueId())) {
      message(actor, text("You cannot target yourself.", NamedTextColor.RED));
      return;
    }

    if (mode == TeleportMode.TP) {
      message(actor, text("Teleporting to " + target.getUsername() + "...", NamedTextColor.GREEN));
      beginTeleportExecution(actor, target);
      return;
    }

    message(actor, text("Teleporting " + target.getUsername() + " to you...", NamedTextColor.GREEN));
    message(target, text("You are being teleported to " + actor.getUsername() + ".", NamedTextColor.GREEN));
    beginTeleportExecution(target, actor);
  }

  private void handleHome(Player player, String requestedHome) {
    if (config == null || !config.homes) {
      message(player, text("Network homes are disabled.", NamedTextColor.RED));
      return;
    }

    if (homeService == null) {
      message(player, text("Home service is not available.", NamedTextColor.RED));
      return;
    }

    List<HomeService.HomeEntry> homes = homeService.listHomes(player.getUniqueId(), player.getUsername());
    if (homes.isEmpty()) {
      message(player, text("No Essentials homes were found across the managed servers.", NamedTextColor.RED));
      return;
    }

    if (requestedHome == null || requestedHome.isBlank()) {
      if (homes.size() == 1) {
        executeHome(player, homes.get(0));
        return;
      }
      sendHomeList(player, homes, true);
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
      case MISSING -> message(player, text("No home named '" + requestedHome + "' was found on the network.", NamedTextColor.RED));
      case AMBIGUOUS -> {
        message(player, text("That home exists on multiple servers. Pick one:", NamedTextColor.YELLOW));
        sendHomeList(player, lookup.candidates(), false);
      }
    }
  }

  private void handleHomes(Player player) {
    if (config == null || !config.homes) {
      message(player, text("Network homes are disabled.", NamedTextColor.RED));
      return;
    }
    List<HomeService.HomeEntry> homes = homeService.listHomes(player.getUniqueId(), player.getUsername());
    if (homes.isEmpty()) {
      message(player, text("No Essentials homes were found across the managed servers.", NamedTextColor.RED));
      return;
    }
    sendHomeList(player, homes, true);
  }

  private void executeHome(Player player, HomeService.HomeEntry home) {
    String currentServer = currentServerName(player);
    if (currentServer != null && currentServer.equalsIgnoreCase(home.server())) {
      sendExecutePlayerCommand(player, player, "home " + home.name());
      return;
    }

    RegisteredServer server = proxy.getServer(home.server()).orElse(null);
    if (server == null) {
      message(player, text("Target server is not registered with Velocity: " + home.server(), NamedTextColor.RED));
      return;
    }

    message(player, text("Sending you to " + home.server() + " for /home " + home.name() + "...", NamedTextColor.GREEN));
    startCrossServerAction(player, server, PendingServerAction.command(home.server(), "home " + home.name()));
  }

  private void beginTeleportExecution(Player movingPlayer, Player anchorPlayer) {
    String anchorServer = currentServerName(anchorPlayer);
    if (anchorServer == null) {
      message(movingPlayer, text("Target server is unavailable right now.", NamedTextColor.RED));
      return;
    }

    String movingServer = currentServerName(movingPlayer);
    if (movingServer != null && movingServer.equalsIgnoreCase(anchorServer)) {
      sendTeleportToPlayer(movingPlayer, movingPlayer, anchorPlayer.getUniqueId());
      return;
    }

    RegisteredServer targetServer = proxy.getServer(anchorServer).orElse(null);
    if (targetServer == null) {
      message(movingPlayer, text("Target server is not registered with Velocity: " + anchorServer, NamedTextColor.RED));
      return;
    }

    startCrossServerAction(movingPlayer, targetServer,
        PendingServerAction.teleport(anchorServer, anchorPlayer.getUniqueId()));
  }

  private void sendHomeList(Player player, List<HomeService.HomeEntry> homes, boolean includeHeader) {
    List<HomeService.HomeEntry> sorted = new ArrayList<>(homes);
    String currentServer = currentServerName(player);
    sorted.sort(Comparator.comparing((HomeService.HomeEntry entry) -> {
      if (currentServer == null) {
        return 1;
      }
      return entry.server().equalsIgnoreCase(currentServer) ? 0 : 1;
    }).thenComparing(HomeService.HomeEntry::server, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(HomeService.HomeEntry::name, String.CASE_INSENSITIVE_ORDER));

    if (includeHeader) {
      message(player, text("Network homes:", NamedTextColor.GOLD));
    }

    for (HomeService.HomeEntry home : sorted) {
      TextComponent line = Component.text(" - ", NamedTextColor.DARK_GRAY)
          .append(Component.text(home.name(), NamedTextColor.AQUA))
          .append(Component.text(" [" + home.server() + "]", NamedTextColor.GRAY))
          .clickEvent(ClickEvent.runCommand("/home " + home.server() + ":" + home.name()))
          .hoverEvent(HoverEvent.showText(Component.text("Teleport to this home", NamedTextColor.YELLOW)));
      player.sendMessage(line);
    }
  }

  private boolean sendExecutePlayerCommand(Player recipient, Player commandTarget, String command) {
    try {
      byte[] packet = BridgeProtocol.encode(BridgeMessageType.EXECUTE_PLAYER_COMMAND, out -> {
        BridgeProtocol.writeUuid(out, commandTarget.getUniqueId());
        BridgeProtocol.writeString(out, command);
      });
      if (!sendToBackend(recipient, packet)) {
        message(recipient, text("Failed to dispatch command to the backend.", NamedTextColor.RED));
        return false;
      }
      return true;
    } catch (IOException ex) {
      logger.error("Failed to encode player command bridge packet", ex);
      message(recipient, text("Failed to dispatch command to the backend.", NamedTextColor.RED));
      return false;
    }
  }

  private boolean sendTeleportToPlayer(Player recipient, Player movingPlayer, UUID targetUuid) {
    try {
      byte[] packet = BridgeProtocol.encode(BridgeMessageType.TELEPORT_TO_PLAYER, out -> {
        BridgeProtocol.writeUuid(out, movingPlayer.getUniqueId());
        BridgeProtocol.writeUuid(out, targetUuid);
      });
      if (!sendToBackend(recipient, packet)) {
        message(recipient, text("Failed to dispatch the teleport to the backend.", NamedTextColor.RED));
        return false;
      }
      return true;
    } catch (IOException ex) {
      logger.error("Failed to encode teleport bridge packet", ex);
      message(recipient, text("Failed to dispatch the teleport to the backend.", NamedTextColor.RED));
      return false;
    }
  }

  private boolean sendToBackend(Player player, byte[] payload) {
    return player.getCurrentServer().map(connection -> connection.sendPluginMessage(channel, payload)).orElse(false);
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
          message(player, text("Failed to connect to " + action.targetServer() + ".", NamedTextColor.RED));
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
        result.getReasonComponent().ifPresentOrElse(
            player::sendMessage,
            () -> message(player, text("Failed to connect to " + action.targetServer() + ".", NamedTextColor.RED))
        );
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
          message(player, text("Timed out waiting to finish the cross-server action.", NamedTextColor.RED));
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
        message(player, text("Timed out waiting to finish the cross-server action.", NamedTextColor.RED));
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

  private void message(Player player, Component message) {
    player.sendMessage(prefix(message));
  }

  private Component prefix(Component message) {
    return PREFIX.append(message);
  }

  private Component text(String text, NamedTextColor color) {
    return Component.text(text, color);
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
}
