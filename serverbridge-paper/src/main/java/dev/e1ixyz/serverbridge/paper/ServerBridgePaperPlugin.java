package dev.e1ixyz.serverbridge.paper;

import dev.e1ixyz.serverbridge.common.protocol.BridgeMessageType;
import dev.e1ixyz.serverbridge.common.protocol.BridgeProtocol;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ServerBridgePaperPlugin extends JavaPlugin implements Listener, PluginMessageListener {
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

  @Override
  public void onEnable() {
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
          player.sendMessage(error("Usage: /" + invocation.label() + " <player> <message>"));
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
          player.sendMessage(error("Usage: /" + invocation.label() + " <message>"));
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
      player.sendMessage(error("Failed to send bridge request: " + ex.getMessage()));
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
        default -> getLogger().warning("Ignoring unsupported backend-bound bridge packet " + decoded.type());
      }
    } catch (Exception ex) {
      getLogger().warning("Failed to process inbound proxy bridge packet: " + ex.getMessage());
    }
  }

  private void requireTarget(Player player, PlayerCommandPreprocessEvent event, CommandInvocation invocation, BridgeMessageType type) throws IOException {
    if (invocation.args().length < 1) {
      player.sendMessage(error("Usage: /" + invocation.label() + " <player>"));
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

  private Component error(String message) {
    return Component.text("[ServerBridge] ", NamedTextColor.DARK_AQUA)
        .append(Component.text(message, NamedTextColor.RED));
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
}
