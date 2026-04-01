package dev.e1ixyz.serverbridge.paper;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import dev.e1ixyz.serverbridge.common.protocol.BridgeMessageType;
import dev.e1ixyz.serverbridge.common.protocol.BridgeProtocol;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ServerBridgePaperPlugin extends JavaPlugin implements Listener, PluginMessageListener {
  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private static final String DEFAULT_PREFIX = "<dark_aqua>[ServerBridge] </dark_aqua>";
  private static final String DEFAULT_USAGE_PLAYER_MESSAGE = "<red>Usage: /<command> <player> <message></red>";
  private static final String DEFAULT_USAGE_REPLY_MESSAGE = "<red>Usage: /<command> <message></red>";
  private static final String DEFAULT_USAGE_PLAYER_TARGET = "<red>Usage: /<command> <player></red>";
  private static final String DEFAULT_BRIDGE_REQUEST_FAILED = "<red>Failed to send bridge request: <reason></red>";
  private static final String ESSENTIALS_SILENT_JOIN_PERMISSION = "essentials.silentjoin";
  private static final String ESSENTIALS_SILENT_QUIT_PERMISSION = "essentials.silentquit";
  private static final Set<String> MSG_ALIASES = Set.of(
      "msg", "w", "m", "t", "pm", "emsg", "epm", "tell", "etell", "whisper", "ewhisper"
  );
  private static final Set<String> REPLY_ALIASES = Set.of("r", "er", "reply", "ereply");
  private static final Set<String> TPA_ALIASES = Set.of("tpa", "call", "ecall", "etpa", "tpask", "etpask");
  private static final Set<String> TPA_ALL_ALIASES = Set.of("tpaall", "etpaall");
  private static final Set<String> TPACANCEL_ALIASES = Set.of("tpacancel", "etpacancel");
  private static final Set<String> TPAHERE_ALIASES = Set.of("tpahere", "etpahere");
  private static final Set<String> TP_ACCEPT_ALIASES = Set.of("tpaccept", "etpaccept", "tpyes", "etpyes");
  private static final Set<String> TP_DENY_ALIASES = Set.of("tpdeny", "etpdeny", "tpno", "etpno");
  private static final Set<String> TP_ALIASES = Set.of("tp", "tele", "etele", "teleport", "eteleport", "etp", "tp2p", "etp2p");
  private static final Set<String> TPHERE_ALIASES = Set.of("tphere", "s", "etphere");
  private static final Set<String> HOME_ALIASES = Set.of("home", "ehome");
  private static final Set<String> HOMES_ALIASES = Set.of("homes", "ehomes", "listhomes");

  private final ConcurrentMap<UUID, String> passthroughOnce = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, NetworkPlayer> networkPlayers = new ConcurrentHashMap<>();

  @Override
  public void onEnable() {
    saveDefaultConfig();
    reloadConfig();
    getServer().getPluginManager().registerEvents(this, this);
    getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeProtocol.CHANNEL);
    getServer().getMessenger().registerIncomingPluginChannel(this, BridgeProtocol.CHANNEL, this);
    getLogger().info("ServerBridge paper plugin enabled on channel " + BridgeProtocol.CHANNEL);
  }

  @Override
  public void onDisable() {
    getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    getServer().getMessenger().unregisterIncomingPluginChannel(this);
    passthroughOnce.clear();
    networkPlayers.clear();
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPlayerJoin(PlayerJoinEvent event) {
    if (shouldSuppressLocalJoinLeaveMessages()) {
      event.joinMessage(null);
    }
    Player player = event.getPlayer();
    Bukkit.getScheduler().runTask(this, () -> syncPlayerState(player));
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPlayerQuit(PlayerQuitEvent event) {
    if (shouldSuppressLocalJoinLeaveMessages()) {
      event.quitMessage(null);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onAsyncChat(AsyncChatEvent event) {
    Player player = event.getPlayer();
    Component rendered = event.renderer().render(player, player.displayName(), event.message(), player);
    String serialized = GsonComponentSerializer.gson().serialize(rendered);
    Bukkit.getScheduler().runTask(this, () -> {
      try {
        send(player, BridgeMessageType.CHAT_BROADCAST, out -> BridgeProtocol.writeString(out, serialized));
      } catch (IOException ex) {
        getLogger().warning("Failed to forward global chat: " + ex.getMessage());
      }
    });
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
    if (!networkPlayerCompletionsEnabled() || !event.isCommand() || !(event.getSender() instanceof Player player)) {
      return;
    }

    TabCompletionContext context = TabCompletionContext.parse(event.getBuffer());
    if (context == null || context.argumentIndex() != 0 || !supportsNetworkPlayerCompletion(context.baseCommand())) {
      return;
    }

    List<String> completions = buildPlayerCompletions(player, context.baseCommand(), context.currentToken());
    if (completions.isEmpty()) {
      return;
    }

    event.setCompletions(completions);
    event.setHandled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
    Player player = event.getPlayer();
    CommandInvocation invocation = CommandInvocation.parse(event.getMessage());
    if (invocation == null) {
      return;
    }

    String bypass = passthroughOnce.get(player.getUniqueId());
    if (bypass != null && bypass.equalsIgnoreCase(invocation.baseCommand())) {
      passthroughOnce.remove(player.getUniqueId(), bypass);
      return;
    }

    try {
      if (MSG_ALIASES.contains(invocation.baseCommand())) {
        if (invocation.args().length < 2) {
          player.sendMessage(localMessage("messages.usagePlayerMessage", DEFAULT_USAGE_PLAYER_MESSAGE, "command", invocation.label()));
          event.setCancelled(true);
          return;
        }
        event.setCancelled(true);
        send(player, BridgeMessageType.PRIVATE_MESSAGE, out -> {
          BridgeProtocol.writeString(out, invocation.args()[0]);
          BridgeProtocol.writeString(out, join(invocation.args(), 1));
        });
        return;
      }

      if (REPLY_ALIASES.contains(invocation.baseCommand())) {
        if (invocation.args().length < 1) {
          player.sendMessage(localMessage("messages.usageReplyMessage", DEFAULT_USAGE_REPLY_MESSAGE, "command", invocation.label()));
          event.setCancelled(true);
          return;
        }
        event.setCancelled(true);
        send(player, BridgeMessageType.PRIVATE_REPLY, out -> BridgeProtocol.writeString(out, join(invocation.args(), 0)));
        return;
      }

      if (TPA_ALIASES.contains(invocation.baseCommand())) {
        requireTarget(player, event, invocation, BridgeMessageType.TPA_REQUEST);
        return;
      }

      if (TPA_ALL_ALIASES.contains(invocation.baseCommand())) {
        event.setCancelled(true);
        send(player, BridgeMessageType.TPA_ALL, out -> {
        });
        return;
      }

      if (TPACANCEL_ALIASES.contains(invocation.baseCommand())) {
        event.setCancelled(true);
        send(player, BridgeMessageType.TP_CANCEL, out -> BridgeProtocol.writeNullableString(out, firstArg(invocation)));
        return;
      }

      if (TPAHERE_ALIASES.contains(invocation.baseCommand())) {
        requireTarget(player, event, invocation, BridgeMessageType.TPAHERE_REQUEST);
        return;
      }

      if (TP_ACCEPT_ALIASES.contains(invocation.baseCommand())) {
        event.setCancelled(true);
        send(player, BridgeMessageType.TP_ACCEPT, out -> BridgeProtocol.writeNullableString(out, firstArg(invocation)));
        return;
      }

      if (TP_DENY_ALIASES.contains(invocation.baseCommand())) {
        event.setCancelled(true);
        send(player, BridgeMessageType.TP_DENY, out -> BridgeProtocol.writeNullableString(out, firstArg(invocation)));
        return;
      }

      if (TP_ALIASES.contains(invocation.baseCommand())) {
        requireTarget(player, event, invocation, BridgeMessageType.TP_DIRECT);
        return;
      }

      if (TPHERE_ALIASES.contains(invocation.baseCommand())) {
        requireTarget(player, event, invocation, BridgeMessageType.TPHERE_DIRECT);
        return;
      }

      if (HOME_ALIASES.contains(invocation.baseCommand())) {
        event.setCancelled(true);
        send(player, BridgeMessageType.HOME, out -> BridgeProtocol.writeNullableString(out, firstArg(invocation)));
        return;
      }

      if (HOMES_ALIASES.contains(invocation.baseCommand())) {
        event.setCancelled(true);
        send(player, BridgeMessageType.HOMES, out -> {
        });
      }
    } catch (IOException ex) {
      player.sendMessage(localMessage("messages.bridgeRequestFailed", DEFAULT_BRIDGE_REQUEST_FAILED, "reason", ex.getMessage()));
      event.setCancelled(true);
    }
  }

  @Override
  public void onPluginMessageReceived(String channel, Player sourcePlayer, byte[] message) {
    if (!BridgeProtocol.CHANNEL.equals(channel)) {
      return;
    }

    try {
      BridgeProtocol.DecodedMessage decoded = BridgeProtocol.decode(message);
      switch (decoded.type()) {
        case EXECUTE_PLAYER_COMMAND -> {
          UUID playerUuid = BridgeProtocol.readUuid(decoded.in());
          String command = BridgeProtocol.readString(decoded.in());
          runPlayerCommand(playerUuid, command, 0);
        }
        case TELEPORT_TO_PLAYER -> {
          UUID movingPlayer = BridgeProtocol.readUuid(decoded.in());
          UUID targetPlayer = BridgeProtocol.readUuid(decoded.in());
          runTeleport(movingPlayer, targetPlayer, 0);
        }
        case NETWORK_PLAYER_SNAPSHOT -> updateNetworkPlayers(decoded.in().readInt(), decoded);
        default -> getLogger().warning("Ignoring unsupported backend-bound bridge packet " + decoded.type());
      }
    } catch (Exception ex) {
      getLogger().warning("Failed to process inbound proxy bridge packet: " + ex.getMessage());
    }
  }

  private void requireTarget(Player player, PlayerCommandPreprocessEvent event, CommandInvocation invocation, BridgeMessageType type) throws IOException {
    if (invocation.args().length < 1) {
      player.sendMessage(localMessage("messages.usagePlayerTarget", DEFAULT_USAGE_PLAYER_TARGET, "command", invocation.label()));
      event.setCancelled(true);
      return;
    }
    event.setCancelled(true);
    send(player, type, out -> BridgeProtocol.writeString(out, invocation.args()[0]));
  }

  private void send(Player player, BridgeMessageType type, BridgeProtocol.PacketWriter writer) throws IOException {
    byte[] payload = BridgeProtocol.encode(type, out -> {
      BridgeProtocol.writeUuid(out, player.getUniqueId());
      writer.write(out);
    });
    player.sendPluginMessage(this, BridgeProtocol.CHANNEL, payload);
  }

  private void runPlayerCommand(UUID playerUuid, String command, int attempt) {
    Player player = Bukkit.getPlayer(playerUuid);
    if (player == null) {
      if (attempt < 5) {
        Bukkit.getScheduler().runTaskLater(this, () -> runPlayerCommand(playerUuid, command, attempt + 1), 10L);
      }
      return;
    }

    passthroughOnce.put(playerUuid, baseCommand(command));
    player.performCommand(command);
  }

  private void runTeleport(UUID movingPlayerUuid, UUID targetPlayerUuid, int attempt) {
    Player moving = Bukkit.getPlayer(movingPlayerUuid);
    Player target = Bukkit.getPlayer(targetPlayerUuid);
    if (moving == null || target == null) {
      if (attempt < 5) {
        Bukkit.getScheduler().runTaskLater(this, () -> runTeleport(movingPlayerUuid, targetPlayerUuid, attempt + 1), 10L);
      }
      return;
    }

    if (!moving.getWorld().equals(target.getWorld())) {
      moving.teleport(target.getLocation());
      return;
    }
    moving.teleport(target);
  }

  private void syncPlayerState(Player player) {
    if (player == null || !player.isOnline()) {
      return;
    }
    try {
      send(player, BridgeMessageType.PLAYER_STATE_SYNC, out -> {
        out.writeBoolean(joinLeaveAnnouncementsEnabled());
        out.writeBoolean(hasSilentJoinPermission(player));
        out.writeBoolean(hasSilentLeavePermission(player));
      });
    } catch (IOException ex) {
      getLogger().warning("Failed to sync player state for " + player.getName() + ": " + ex.getMessage());
    }
  }

  private void updateNetworkPlayers(int count, BridgeProtocol.DecodedMessage decoded) throws IOException {
    if (count < 0) {
      throw new IOException("Negative network player snapshot size: " + count);
    }
    networkPlayers.clear();
    for (int index = 0; index < count; index++) {
      String username = BridgeProtocol.readString(decoded.in());
      String server = BridgeProtocol.readString(decoded.in());
      networkPlayers.put(username.toLowerCase(Locale.ROOT), new NetworkPlayer(username, server));
    }
  }

  private boolean networkPlayerCompletionsEnabled() {
    return getConfig().getBoolean("networkPlayerCompletions", true);
  }

  private boolean joinLeaveAnnouncementsEnabled() {
    return getConfig().getBoolean("joinLeaveAnnouncements.enabled", true);
  }

  private boolean hasSilentJoinPermission(Player player) {
    return player.hasPermission(ESSENTIALS_SILENT_JOIN_PERMISSION);
  }

  private boolean hasSilentLeavePermission(Player player) {
    return player.hasPermission(ESSENTIALS_SILENT_QUIT_PERMISSION);
  }

  private boolean shouldSuppressLocalJoinLeaveMessages() {
    return joinLeaveAnnouncementsEnabled()
        && getConfig().getBoolean("joinLeaveAnnouncements.suppressLocalMessages", true);
  }

  private boolean supportsNetworkPlayerCompletion(String baseCommand) {
    return MSG_ALIASES.contains(baseCommand)
        || TPA_ALIASES.contains(baseCommand)
        || TPACANCEL_ALIASES.contains(baseCommand)
        || TPAHERE_ALIASES.contains(baseCommand)
        || TP_ACCEPT_ALIASES.contains(baseCommand)
        || TP_DENY_ALIASES.contains(baseCommand)
        || TP_ALIASES.contains(baseCommand)
        || TPHERE_ALIASES.contains(baseCommand);
  }

  private List<String> buildPlayerCompletions(Player sender, String baseCommand, String token) {
    String loweredToken = token == null ? "" : token.toLowerCase(Locale.ROOT);
    LinkedHashSet<String> suggestions = new LinkedHashSet<>();
    if ((TP_ACCEPT_ALIASES.contains(baseCommand) || TP_DENY_ALIASES.contains(baseCommand))
        && "*".startsWith(loweredToken)) {
      suggestions.add("*");
    }
    for (Player online : Bukkit.getOnlinePlayers()) {
      addPlayerCompletion(suggestions, sender, online.getName(), loweredToken);
    }
    for (NetworkPlayer networkPlayer : networkPlayers.values()) {
      addPlayerCompletion(suggestions, sender, networkPlayer.username(), loweredToken);
    }

    List<String> names = new ArrayList<>();
    for (String suggestion : suggestions) {
      if (!"*".equals(suggestion)) {
        names.add(suggestion);
      }
    }
    names.sort(String.CASE_INSENSITIVE_ORDER);

    List<String> ordered = new ArrayList<>();
    if (suggestions.contains("*")) {
      ordered.add("*");
    }
    ordered.addAll(names);
    return ordered;
  }

  private void addPlayerCompletion(Set<String> suggestions, Player sender, String candidate, String loweredToken) {
    if (candidate == null || candidate.isBlank() || candidate.equalsIgnoreCase(sender.getName())) {
      return;
    }
    if (!loweredToken.isEmpty() && !candidate.toLowerCase(Locale.ROOT).startsWith(loweredToken)) {
      return;
    }
    suggestions.add(candidate);
  }

  private Component localMessage(String path, String fallback, String... placeholders) {
    String prefix = getConfig().getString("prefix", DEFAULT_PREFIX);
    String message = getConfig().getString(path, fallback);
    return MINI.deserialize((prefix == null ? "" : prefix) + (message == null ? "" : message), placeholders(placeholders));
  }

  private TagResolver[] placeholders(String... placeholders) {
    if (placeholders == null || placeholders.length == 0) {
      return new TagResolver[0];
    }
    TagResolver[] resolvers = new TagResolver[placeholders.length / 2];
    int index = 0;
    for (int i = 0; i + 1 < placeholders.length; i += 2) {
      resolvers[index++] = Placeholder.unparsed(placeholders[i], placeholders[i + 1] == null ? "" : placeholders[i + 1]);
    }
    if (index == resolvers.length) {
      return resolvers;
    }
    TagResolver[] trimmed = new TagResolver[index];
    System.arraycopy(resolvers, 0, trimmed, 0, index);
    return trimmed;
  }

  private String firstArg(CommandInvocation invocation) {
    return invocation.args().length == 0 ? null : invocation.args()[0];
  }

  private String join(String[] values, int startIndex) {
    StringBuilder builder = new StringBuilder();
    for (int i = startIndex; i < values.length; i++) {
      if (builder.length() > 0) {
        builder.append(' ');
      }
      builder.append(values[i]);
    }
    return builder.toString();
  }

  private String baseCommand(String command) {
    String trimmed = command == null ? "" : command.trim();
    if (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    int space = trimmed.indexOf(' ');
    String label = space >= 0 ? trimmed.substring(0, space) : trimmed;
    int colon = label.lastIndexOf(':');
    if (colon >= 0) {
      label = label.substring(colon + 1);
    }
    return label.toLowerCase(Locale.ROOT);
  }

  private record TabCompletionContext(String baseCommand, int argumentIndex, String currentToken) {
    private static TabCompletionContext parse(String raw) {
      if (raw == null || raw.isBlank() || !raw.startsWith("/")) {
        return null;
      }
      String body = raw.substring(1);
      if (body.isBlank()) {
        return null;
      }
      boolean trailingSpace = body.endsWith(" ");
      String trimmed = body.trim();
      if (trimmed.isEmpty()) {
        return null;
      }
      String[] tokens = trimmed.split("\\s+");
      if (tokens.length == 0) {
        return null;
      }

      String baseCommand = tokens[0];
      int colon = baseCommand.lastIndexOf(':');
      if (colon >= 0) {
        baseCommand = baseCommand.substring(colon + 1);
      }

      int providedArgs = Math.max(0, tokens.length - 1);
      if (!trailingSpace && providedArgs == 0) {
        return null;
      }

      int argumentIndex = trailingSpace ? providedArgs : providedArgs - 1;
      String currentToken = trailingSpace ? "" : tokens[tokens.length - 1];
      return new TabCompletionContext(baseCommand.toLowerCase(Locale.ROOT), argumentIndex, currentToken);
    }
  }

  private record CommandInvocation(String label, String baseCommand, String[] args) {
    private static CommandInvocation parse(String raw) {
      if (raw == null || raw.isBlank() || !raw.startsWith("/")) {
        return null;
      }
      String body = raw.substring(1).trim();
      if (body.isEmpty()) {
        return null;
      }
      String[] tokens = body.split("\\s+");
      if (tokens.length == 0) {
        return null;
      }
      String label = tokens[0];
      String base = label;
      int colon = base.lastIndexOf(':');
      if (colon >= 0) {
        base = base.substring(colon + 1);
      }
      String[] args = new String[Math.max(0, tokens.length - 1)];
      if (args.length > 0) {
        System.arraycopy(tokens, 1, args, 0, args.length);
      }
      return new CommandInvocation(label, base.toLowerCase(Locale.ROOT), args);
    }
  }

  private record NetworkPlayer(String username, String server) {
  }
}
