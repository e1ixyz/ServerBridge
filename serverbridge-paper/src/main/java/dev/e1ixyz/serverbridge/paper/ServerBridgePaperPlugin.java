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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
  private static final String DEFAULT_USAGE_HOMES_PAGE = "<red>Usage: /<command> [page <number>]</red>";
  private static final String DEFAULT_USAGE_STASH = "<red>Usage: /<command></red>";
  private static final String DEFAULT_BRIDGE_REQUEST_FAILED = "<red>Failed to send bridge request: <reason></red>";
  private static final String DEFAULT_STASH_DISABLED = "<red>The network stash is disabled on this server.</red>";
  private static final String DEFAULT_STASH_TITLE = "<dark_aqua>Network Stash</dark_aqua>";
  private static final String DEFAULT_STASH_NO_DEPOSIT_ITEM = "<red>Place one stack in the deposit slot first.</red>";
  private static final String DEFAULT_STASH_NO_WITHDRAW_SPACE = "<red>Clear inventory space before withdrawing that stack.</red>";
  private static final String DEFAULT_STASH_ACTION_PENDING = "<yellow>Please wait for the previous stash action to finish.</yellow>";
  private static final String DEFAULT_STASH_OVERFLOW_DROPPED = "<yellow>Your inventory filled up, so the withdrawn stack was dropped at your feet.</yellow>";
  private static final String DEFAULT_STASH_RETURNED_INPUT = "<yellow>The stash input item was returned to your inventory.</yellow>";
  private static final String DEFAULT_STASH_DROPPED_INPUT = "<yellow>Your inventory was full, so the stash input item was dropped at your feet.</yellow>";
  private static final String DEFAULT_STASH_DEPOSIT_INPUT_NAME = "<gold>Place Stack Here</gold>";
  private static final List<String> DEFAULT_STASH_DEPOSIT_INPUT_LORE = List.of(
      "<gray>Click this slot while holding the stack",
      "<gray>you want to add to the shared stash."
  );
  private static final String DEFAULT_STASH_DEPOSIT_GUIDE_NAME = "<yellow>Deposit Guide</yellow>";
  private static final List<String> DEFAULT_STASH_DEPOSIT_GUIDE_LORE = List.of(
      "<gray>1. Put one stack in the slot on the left.",
      "<gray>2. Click Confirm Deposit."
  );
  private static final String DEFAULT_STASH_DEPOSIT_CONFIRM_NAME = "<green>Confirm Deposit</green>";
  private static final List<String> DEFAULT_STASH_DEPOSIT_CONFIRM_LORE = List.of(
      "<gray>Consumes one stack from the input slot.",
      "<gray>You can deposit only once per real day."
  );
  private static final String DEFAULT_STASH_DEPOSIT_AVAILABLE_NAME = "<green>Deposit Available</green>";
  private static final List<String> DEFAULT_STASH_DEPOSIT_AVAILABLE_LORE = List.of(
      "<gray>You can still deposit one stack today."
  );
  private static final String DEFAULT_STASH_DEPOSIT_USED_NAME = "<red>Deposit Used</red>";
  private static final List<String> DEFAULT_STASH_DEPOSIT_USED_LORE = List.of(
      "<gray>You already deposited one stack today."
  );
  private static final String DEFAULT_STASH_WITHDRAW_AVAILABLE_NAME = "<green>Withdraw Available</green>";
  private static final List<String> DEFAULT_STASH_WITHDRAW_AVAILABLE_LORE = List.of(
      "<gray>You can still withdraw one stack today."
  );
  private static final String DEFAULT_STASH_WITHDRAW_USED_NAME = "<red>Withdraw Used</red>";
  private static final List<String> DEFAULT_STASH_WITHDRAW_USED_LORE = List.of(
      "<gray>You already withdrew one stack today."
  );
  private static final String DEFAULT_STASH_SUMMARY_NAME = "<aqua>Shared Proxy Stash</aqua>";
  private static final List<String> DEFAULT_STASH_SUMMARY_LORE = List.of(
      "<gray>Used slots: <used>/<total>",
      "<gray>Deposit today: <deposit_status>",
      "<gray>Withdraw today: <withdraw_status>",
      "<gray>Resets at midnight <timezone>"
  );
  private static final String DEFAULT_STASH_FILLER_NAME = " ";
  private static final String ESSENTIALS_SILENT_JOIN_PERMISSION = "essentials.silentjoin";
  private static final String ESSENTIALS_SILENT_QUIT_PERMISSION = "essentials.silentquit";
  private static final byte STASH_ACTION_OPEN = 0;
  private static final byte STASH_ACTION_DEPOSIT = 1;
  private static final byte STASH_ACTION_WITHDRAW = 2;
  private static final Set<String> MSGTOGGLE_ALIASES = Set.of("msgtoggle", "emsgtoggle");
  private static final Set<String> IGNORE_ALIASES = Set.of("ignore", "eignore");
  private static final Set<String> UNIGNORE_ALIASES = Set.of(
      "unignore", "eunignore", "delignore", "edelignore", "remignore", "eremignore", "rmignore", "ermignore"
  );
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
  private static final Set<String> STASH_ALIASES = Set.of("stash", "networkstash", "networkec", "nec");

  private final ConcurrentMap<UUID, String> passthroughOnce = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, NetworkPlayer> networkPlayers = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, StashView> stashViews = new ConcurrentHashMap<>();
  private NamespacedKey guiItemMarkerKey;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    reloadConfig();
    guiItemMarkerKey = new NamespacedKey(this, "gui-item");
    getServer().getPluginManager().registerEvents(this, this);
    getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeProtocol.CHANNEL);
    getServer().getMessenger().registerIncomingPluginChannel(this, BridgeProtocol.CHANNEL, this);
    getLogger().info("ServerBridge paper plugin enabled on channel " + BridgeProtocol.CHANNEL);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("This command can only be used by players.");
      return true;
    }
    if (!STASH_ALIASES.contains(command.getName().toLowerCase(Locale.ROOT))
        && !"stash".equalsIgnoreCase(command.getName())) {
      return false;
    }
    return handleStashCommand(player, label, args);
  }

  @Override
  public void onDisable() {
    getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    getServer().getMessenger().unregisterIncomingPluginChannel(this);
    passthroughOnce.clear();
    networkPlayers.clear();
    stashViews.clear();
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
    StashView view = stashViews.get(event.getPlayer().getUniqueId());
    cleanupStashView(event.getPlayer(), true, view != null && !view.awaitingResponse());
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

      if (MSGTOGGLE_ALIASES.contains(invocation.baseCommand())) {
        if (invocation.args().length > 1) {
          return;
        }
        if (invocation.args().length == 1 && !isOnOff(invocation.args()[0])) {
          return;
        }
        event.setCancelled(true);
        send(player, BridgeMessageType.MSG_TOGGLE, out -> BridgeProtocol.writeNullableString(out, firstArg(invocation)));
        return;
      }

      if (IGNORE_ALIASES.contains(invocation.baseCommand())) {
        if (invocation.args().length > 1) {
          player.sendMessage(localMessage("messages.usagePlayerTarget", DEFAULT_USAGE_PLAYER_TARGET, "command", invocation.label()));
          event.setCancelled(true);
          return;
        }
        event.setCancelled(true);
        send(player, BridgeMessageType.IGNORE_PLAYER, out -> {
          BridgeProtocol.writeString(out, firstArg(invocation) == null ? "" : firstArg(invocation));
          out.writeBoolean(false);
        });
        return;
      }

      if (UNIGNORE_ALIASES.contains(invocation.baseCommand())) {
        if (invocation.args().length < 1 || invocation.args().length > 1) {
          player.sendMessage(localMessage("messages.usagePlayerTarget", DEFAULT_USAGE_PLAYER_TARGET, "command", invocation.label()));
          event.setCancelled(true);
          return;
        }
        event.setCancelled(true);
        send(player, BridgeMessageType.IGNORE_PLAYER, out -> {
          BridgeProtocol.writeString(out, invocation.args()[0]);
          out.writeBoolean(true);
        });
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

      if (STASH_ALIASES.contains(invocation.baseCommand())) {
        event.setCancelled(true);
        handleStashCommand(player, invocation.label(), invocation.args());
        return;
      }

      if (HOME_ALIASES.contains(invocation.baseCommand())) {
        event.setCancelled(true);
        send(player, BridgeMessageType.HOME, out -> BridgeProtocol.writeNullableString(out, firstArg(invocation)));
        return;
      }

      if (HOMES_ALIASES.contains(invocation.baseCommand())) {
        if (invocation.args().length >= 1 && !isHomesPageRequest(invocation)) {
          event.setCancelled(true);
          send(player, BridgeMessageType.HOME, out -> BridgeProtocol.writeNullableString(out, firstArg(invocation)));
          return;
        }
        int page = parseHomesPage(invocation);
        if (page < 1) {
          player.sendMessage(localMessage("messages.usageHomesPage", DEFAULT_USAGE_HOMES_PAGE, "command", invocation.label()));
          event.setCancelled(true);
          return;
        }
        event.setCancelled(true);
        send(player, BridgeMessageType.HOMES, out -> out.writeInt(page));
      }
    } catch (IOException ex) {
      player.sendMessage(localMessage("messages.bridgeRequestFailed", DEFAULT_BRIDGE_REQUEST_FAILED, "reason", ex.getMessage()));
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    StashView view = stashViews.get(player.getUniqueId());
    if (view == null || event.getView().getTopInventory() != view.inventory()) {
      return;
    }

    int rawSlot = event.getRawSlot();
    if (rawSlot < 0) {
      return;
    }
    if (rawSlot >= view.inventory().getSize()) {
      if (event.isShiftClick()) {
        event.setCancelled(true);
      }
      return;
    }

    if (rawSlot < view.stashSlots()) {
      event.setCancelled(true);
      handleStashWithdrawClick(player, view, rawSlot);
      return;
    }

    if (rawSlot == view.depositInputSlot()) {
      event.setCancelled(true);
      handleDepositInputClick(player, view, event);
      return;
    }

    event.setCancelled(true);
    if (rawSlot == view.depositConfirmSlot()) {
      handleStashDepositClick(player, view);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    StashView view = stashViews.get(player.getUniqueId());
    if (view == null || event.getView().getTopInventory() != view.inventory()) {
      return;
    }
    for (int rawSlot : event.getRawSlots()) {
      if (rawSlot < view.inventory().getSize()) {
        event.setCancelled(true);
        return;
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onInventoryClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) {
      return;
    }
    StashView view = stashViews.get(player.getUniqueId());
    if (view == null || event.getInventory() != view.inventory()) {
      return;
    }
    if (view.awaitingResponse()) {
      view.closed(true);
      return;
    }
    cleanupStashView(player, true, true);
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
        case STASH_SYNC -> handleStashSync(sourcePlayer, decoded);
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

  private boolean handleStashCommand(Player player, String label, String[] args) {
    if (!stashEnabled()) {
      player.sendMessage(localMessage("messages.stashDisabled", DEFAULT_STASH_DISABLED));
      return true;
    }
    if (args.length != 0) {
      player.sendMessage(localMessage("messages.usageStash", DEFAULT_USAGE_STASH, "command", label == null ? "stash" : label));
      return true;
    }
    try {
      send(player, BridgeMessageType.STASH_OPEN, out -> {
      });
    } catch (IOException ex) {
      player.sendMessage(localMessage("messages.bridgeRequestFailed", DEFAULT_BRIDGE_REQUEST_FAILED, "reason", ex.getMessage()));
    }
    return true;
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

  private void handleStashSync(Player sourcePlayer, BridgeProtocol.DecodedMessage decoded) throws IOException {
    if (sourcePlayer == null) {
      return;
    }

    UUID sessionId = BridgeProtocol.readUuid(decoded.in());
    byte action = decoded.in().readByte();
    boolean success = decoded.in().readBoolean();
    byte[] withdrawnItem = BridgeProtocol.readNullableByteArray(decoded.in());
    int slotCount = decoded.in().readInt();
    if (slotCount <= 0 || slotCount % 9 != 0 || slotCount > 45) {
      throw new IOException("Invalid stash slot count: " + slotCount);
    }

    List<ItemStack> stashItems = new ArrayList<>(slotCount);
    for (int slot = 0; slot < slotCount; slot++) {
      stashItems.add(deserializeItem(BridgeProtocol.readNullableByteArray(decoded.in())));
    }

    String timezoneLabel = BridgeProtocol.readString(decoded.in());
    boolean depositAvailable = decoded.in().readBoolean();
    boolean withdrawAvailable = decoded.in().readBoolean();

    Bukkit.getScheduler().runTask(this, () -> applyStashSync(
        sourcePlayer.getUniqueId(),
        sessionId,
        action,
        success,
        withdrawnItem,
        stashItems,
        timezoneLabel,
        depositAvailable,
        withdrawAvailable
    ));
  }

  private void applyStashSync(UUID playerUuid, UUID sessionId, byte action, boolean success,
                              byte[] withdrawnItemBytes, List<ItemStack> stashItems, String timezoneLabel,
                              boolean depositAvailable, boolean withdrawAvailable) {
    Player player = Bukkit.getPlayer(playerUuid);
    if (player == null) {
      return;
    }

    StashView view = prepareStashView(player, sessionId, stashItems.size());
    view.depositAvailable(depositAvailable);
    view.withdrawAvailable(withdrawAvailable);
    view.awaitingResponse(false);

    refreshStashView(view, stashItems, timezoneLabel);

    if (action == STASH_ACTION_DEPOSIT) {
      ItemStack pendingDeposit = view.pendingDepositItem();
      view.pendingDepositItem(null);
      if (!success && !isEmpty(pendingDeposit)) {
        if (view.closed()) {
          returnInputItem(player, pendingDeposit, true);
        } else {
          renderDepositInputSlot(view, pendingDeposit);
        }
      }
    }

    if (action == STASH_ACTION_WITHDRAW && success) {
      ItemStack withdrawnItem = deserializeItem(withdrawnItemBytes);
      if (!isEmpty(withdrawnItem)) {
        giveOrDrop(player, withdrawnItem, "messages.stashOverflowDropped", DEFAULT_STASH_OVERFLOW_DROPPED);
      }
    }

    if (view.closed()) {
      cleanupStashView(player, true, true);
      return;
    }

    if (action == STASH_ACTION_OPEN || player.getOpenInventory().getTopInventory() != view.inventory()) {
      player.openInventory(view.inventory());
    }
  }

  private StashView prepareStashView(Player player, UUID sessionId, int stashSlots) {
    StashView existing = stashViews.get(player.getUniqueId());
    if (existing != null && existing.sessionId().equals(sessionId) && existing.stashSlots() == stashSlots) {
      return existing;
    }

    if (existing != null) {
      if (!isEmpty(existing.pendingDepositItem())) {
        returnInputItem(player, existing.pendingDepositItem(), false);
      }
      ItemStack inputItem = currentDepositInput(existing);
      if (!isEmpty(inputItem)) {
        returnInputItem(player, inputItem, false);
      }
    }

    StashInventoryHolder holder = new StashInventoryHolder(player.getUniqueId());
    Inventory inventory = Bukkit.createInventory(holder, stashSlots + 9,
        configuredComponent("stash.title", DEFAULT_STASH_TITLE));
    holder.inventory(inventory);
    StashView view = new StashView(
        sessionId,
        inventory,
        stashSlots,
        stashSlots,
        stashSlots + 1,
        stashSlots + 2,
        stashSlots + 3,
        stashSlots + 4,
        stashSlots + 5
    );
    stashViews.put(player.getUniqueId(), view);
    return view;
  }

  private void refreshStashView(StashView view, List<ItemStack> stashItems, String timezoneLabel) {
    ItemStack depositInput = currentDepositInput(view);
    int usedSlots = 0;
    for (int slot = 0; slot < view.stashSlots(); slot++) {
      ItemStack stack = slot < stashItems.size() ? stashItems.get(slot) : null;
      if (!isEmpty(stack)) {
        usedSlots++;
      }
      view.inventory().setItem(slot, isEmpty(stack) ? null : stack.clone());
    }

    for (int slot = view.stashSlots(); slot < view.inventory().getSize(); slot++) {
      view.inventory().setItem(slot, guiItem(
          slot == view.depositConfirmSlot() ? Material.LIME_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE,
          "stash.fillerName",
          DEFAULT_STASH_FILLER_NAME,
          null,
          List.of()
      ));
    }

    renderDepositInputSlot(view, depositInput);
    view.inventory().setItem(view.depositInfoSlot(), guiItem(
        Material.ARROW,
        "stash.depositGuideName",
        DEFAULT_STASH_DEPOSIT_GUIDE_NAME,
        "stash.depositGuideLore",
        DEFAULT_STASH_DEPOSIT_GUIDE_LORE
    ));
    view.inventory().setItem(view.depositConfirmSlot(), guiItem(
        Material.LIME_CONCRETE,
        "stash.depositConfirmName",
        DEFAULT_STASH_DEPOSIT_CONFIRM_NAME,
        "stash.depositConfirmLore",
        DEFAULT_STASH_DEPOSIT_CONFIRM_LORE
    ));
    view.inventory().setItem(view.depositStatusSlot(), view.depositAvailable()
        ? guiItem(
        Material.EMERALD,
        "stash.depositAvailableName",
        DEFAULT_STASH_DEPOSIT_AVAILABLE_NAME,
        "stash.depositAvailableLore",
        DEFAULT_STASH_DEPOSIT_AVAILABLE_LORE
    )
        : guiItem(
        Material.REDSTONE,
        "stash.depositUsedName",
        DEFAULT_STASH_DEPOSIT_USED_NAME,
        "stash.depositUsedLore",
        DEFAULT_STASH_DEPOSIT_USED_LORE
    ));
    view.inventory().setItem(view.withdrawStatusSlot(), view.withdrawAvailable()
        ? guiItem(
        Material.ENDER_PEARL,
        "stash.withdrawAvailableName",
        DEFAULT_STASH_WITHDRAW_AVAILABLE_NAME,
        "stash.withdrawAvailableLore",
        DEFAULT_STASH_WITHDRAW_AVAILABLE_LORE
    )
        : guiItem(
        Material.BARRIER,
        "stash.withdrawUsedName",
        DEFAULT_STASH_WITHDRAW_USED_NAME,
        "stash.withdrawUsedLore",
        DEFAULT_STASH_WITHDRAW_USED_LORE
    ));
    view.inventory().setItem(view.summarySlot(), guiItem(
        Material.BOOK,
        "stash.summaryName",
        DEFAULT_STASH_SUMMARY_NAME,
        "stash.summaryLore",
        DEFAULT_STASH_SUMMARY_LORE,
        "used", Integer.toString(usedSlots),
        "total", Integer.toString(view.stashSlots()),
        "deposit_status", view.depositAvailable() ? "available" : "used",
        "withdraw_status", view.withdrawAvailable() ? "available" : "used",
        "timezone", timezoneLabel == null || timezoneLabel.isBlank() ? "America/New_York" : timezoneLabel
    ));
  }

  private void handleStashDepositClick(Player player, StashView view) {
    if (view.awaitingResponse()) {
      player.sendMessage(localMessage("messages.stashActionPending", DEFAULT_STASH_ACTION_PENDING));
      return;
    }

    ItemStack input = currentDepositInput(view);
    if (isEmpty(input)) {
      player.sendMessage(localMessage("messages.stashNoDepositItem", DEFAULT_STASH_NO_DEPOSIT_ITEM));
      return;
    }

    try {
      byte[] payload = input.serializeAsBytes();
      view.awaitingResponse(true);
      view.pendingDepositItem(input.clone());
      renderDepositInputSlot(view, null);
      send(player, BridgeMessageType.STASH_DEPOSIT, out -> {
        BridgeProtocol.writeUuid(out, view.sessionId());
        BridgeProtocol.writeByteArray(out, payload);
      });
    } catch (IOException ex) {
      view.awaitingResponse(false);
      view.pendingDepositItem(null);
      renderDepositInputSlot(view, input);
      player.sendMessage(localMessage("messages.bridgeRequestFailed", DEFAULT_BRIDGE_REQUEST_FAILED, "reason", ex.getMessage()));
    }
  }

  private void handleStashWithdrawClick(Player player, StashView view, int slot) {
    if (view.awaitingResponse()) {
      player.sendMessage(localMessage("messages.stashActionPending", DEFAULT_STASH_ACTION_PENDING));
      return;
    }

    ItemStack stack = view.inventory().getItem(slot);
    if (isEmpty(stack)) {
      return;
    }
    if (!canFitInInventory(player, stack)) {
      player.sendMessage(localMessage("messages.stashNoWithdrawSpace", DEFAULT_STASH_NO_WITHDRAW_SPACE));
      return;
    }

    try {
      view.awaitingResponse(true);
      send(player, BridgeMessageType.STASH_WITHDRAW, out -> {
        BridgeProtocol.writeUuid(out, view.sessionId());
        out.writeInt(slot);
      });
    } catch (IOException ex) {
      view.awaitingResponse(false);
      player.sendMessage(localMessage("messages.bridgeRequestFailed", DEFAULT_BRIDGE_REQUEST_FAILED, "reason", ex.getMessage()));
    }
  }

  private void cleanupStashView(Player player, boolean notifyProxy, boolean returnInput) {
    if (player == null) {
      return;
    }
    StashView view = stashViews.remove(player.getUniqueId());
    if (view == null) {
      return;
    }

    if (notifyProxy) {
      sendStashClose(player, view.sessionId());
    }

    if (!returnInput) {
      return;
    }

    if (!isEmpty(view.pendingDepositItem())) {
      returnInputItem(player, view.pendingDepositItem(), true);
      view.pendingDepositItem(null);
    }

    ItemStack input = currentDepositInput(view);
    if (!isEmpty(input)) {
      renderDepositInputSlot(view, null);
      returnInputItem(player, input, true);
    }
  }

  private void sendStashClose(Player player, UUID sessionId) {
    try {
      send(player, BridgeMessageType.STASH_CLOSE, out -> BridgeProtocol.writeUuid(out, sessionId));
    } catch (IOException ex) {
      getLogger().warning("Failed to close stash session for " + player.getName() + ": " + ex.getMessage());
    }
  }

  private void handleDepositInputClick(Player player, StashView view, InventoryClickEvent event) {
    if (view.awaitingResponse()) {
      player.sendMessage(localMessage("messages.stashActionPending", DEFAULT_STASH_ACTION_PENDING));
      return;
    }
    if (event.isShiftClick()) {
      return;
    }
    ClickType click = event.getClick();
    InventoryAction action = event.getAction();
    if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND || click == ClickType.DOUBLE_CLICK) {
      return;
    }
    if (action == InventoryAction.NOTHING) {
      return;
    }

    ItemStack currentInput = currentDepositInput(view);
    ItemStack cursor = event.getCursor();

    if (isEmpty(cursor)) {
      if (isEmpty(currentInput)) {
        return;
      }
      player.setItemOnCursor(currentInput.clone());
      renderDepositInputSlot(view, null);
      return;
    }

    if (isEmpty(currentInput)) {
      renderDepositInputSlot(view, cursor.clone());
      player.setItemOnCursor(null);
      return;
    }

    renderDepositInputSlot(view, cursor.clone());
    player.setItemOnCursor(currentInput.clone());
  }

  private boolean canFitInInventory(Player player, ItemStack stack) {
    if (isEmpty(stack)) {
      return true;
    }
    ItemStack[] contents = Arrays.copyOf(player.getInventory().getStorageContents(),
        player.getInventory().getStorageContents().length);
    int remaining = stack.getAmount();
    for (int slot = 0; slot < contents.length; slot++) {
      ItemStack existing = contents[slot];
      if (isEmpty(existing)) {
        return true;
      }
      if (!existing.isSimilar(stack)) {
        continue;
      }
      remaining -= Math.max(0, existing.getMaxStackSize() - existing.getAmount());
      if (remaining <= 0) {
        return true;
      }
    }
    return false;
  }

  private void giveOrDrop(Player player, ItemStack stack, String overflowPath, String overflowFallback) {
    if (isEmpty(stack)) {
      return;
    }
    Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
    if (!leftover.isEmpty()) {
      for (ItemStack value : leftover.values()) {
        if (!isEmpty(value)) {
          player.getWorld().dropItemNaturally(player.getLocation(), value);
        }
      }
      player.sendMessage(localMessage(overflowPath, overflowFallback));
    }
  }

  private void returnInputItem(Player player, ItemStack stack, boolean notify) {
    if (isEmpty(stack)) {
      return;
    }
    Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
    if (leftover.isEmpty()) {
      if (notify) {
        player.sendMessage(localMessage("messages.stashReturnedInput", DEFAULT_STASH_RETURNED_INPUT));
      }
      return;
    }

    for (ItemStack value : leftover.values()) {
      if (!isEmpty(value)) {
        player.getWorld().dropItemNaturally(player.getLocation(), value);
      }
    }
    if (notify) {
      player.sendMessage(localMessage("messages.stashDroppedInput", DEFAULT_STASH_DROPPED_INPUT));
    }
  }

  private ItemStack deserializeItem(byte[] itemBytes) {
    if (itemBytes == null || itemBytes.length == 0) {
      return null;
    }
    try {
      return ItemStack.deserializeBytes(itemBytes);
    } catch (Exception ex) {
      getLogger().warning("Failed to deserialize stash item payload: " + ex.getMessage());
      return null;
    }
  }

  private boolean isEmpty(ItemStack stack) {
    return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
  }

  private ItemStack currentDepositInput(StashView view) {
    ItemStack stack = view.inventory().getItem(view.depositInputSlot());
    return isDepositPlaceholder(stack) ? null : stack;
  }

  private void renderDepositInputSlot(StashView view, ItemStack stack) {
    if (isEmpty(stack)) {
      view.inventory().setItem(view.depositInputSlot(), depositPlaceholderItem());
      return;
    }
    view.inventory().setItem(view.depositInputSlot(), stack.clone());
  }

  private ItemStack depositPlaceholderItem() {
    return guiMarkedItem(
        Material.YELLOW_STAINED_GLASS_PANE,
        "stash.depositInputName",
        DEFAULT_STASH_DEPOSIT_INPUT_NAME,
        "stash.depositInputLore",
        DEFAULT_STASH_DEPOSIT_INPUT_LORE,
        "deposit-placeholder"
    );
  }

  private boolean isDepositPlaceholder(ItemStack stack) {
    if (isEmpty(stack) || guiItemMarkerKey == null || !stack.hasItemMeta()) {
      return false;
    }
    String marker = stack.getItemMeta().getPersistentDataContainer().get(guiItemMarkerKey, PersistentDataType.STRING);
    return "deposit-placeholder".equals(marker);
  }

  private ItemStack guiItem(Material material, String namePath, String fallbackName,
                            String lorePath, List<String> fallbackLore, String... placeholders) {
    ItemStack item = new ItemStack(material);
    item.editMeta(meta -> applyGuiMeta(meta, namePath, fallbackName, lorePath, fallbackLore, null, placeholders));
    return item;
  }

  private ItemStack guiMarkedItem(Material material, String namePath, String fallbackName,
                                  String lorePath, List<String> fallbackLore, String marker, String... placeholders) {
    ItemStack item = new ItemStack(material);
    item.editMeta(meta -> applyGuiMeta(meta, namePath, fallbackName, lorePath, fallbackLore, marker, placeholders));
    return item;
  }

  private void applyGuiMeta(ItemMeta meta, String namePath, String fallbackName,
                            String lorePath, List<String> fallbackLore, String marker, String... placeholders) {
    meta.displayName(configuredComponent(namePath, fallbackName, placeholders));
    List<Component> lore = configuredLore(lorePath, fallbackLore, placeholders);
    if (!lore.isEmpty()) {
      meta.lore(lore);
    }
    if (marker != null && guiItemMarkerKey != null) {
      meta.getPersistentDataContainer().set(guiItemMarkerKey, PersistentDataType.STRING, marker);
    }
    meta.addItemFlags(ItemFlag.values());
  }

  private Component configuredComponent(String path, String fallback, String... placeholders) {
    String value = path == null ? fallback : getConfig().getString(path, fallback);
    return MINI.deserialize(value == null ? "" : value, placeholders(placeholders));
  }

  private List<Component> configuredLore(String path, List<String> fallback, String... placeholders) {
    List<String> lines = path == null ? List.of() : getConfig().getStringList(path);
    if ((lines == null || lines.isEmpty()) && fallback != null) {
      lines = fallback;
    }
    if (lines == null || lines.isEmpty()) {
      return List.of();
    }
    List<Component> lore = new ArrayList<>(lines.size());
    for (String line : lines) {
      lore.add(MINI.deserialize(line == null ? "" : line, placeholders(placeholders)));
    }
    return lore;
  }

  private boolean networkPlayerCompletionsEnabled() {
    return getConfig().getBoolean("networkPlayerCompletions", true);
  }

  private boolean stashEnabled() {
    return getConfig().getBoolean("stash.enabled", true);
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
        || IGNORE_ALIASES.contains(baseCommand)
        || UNIGNORE_ALIASES.contains(baseCommand)
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

  private boolean isOnOff(String value) {
    return "on".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value);
  }

  private boolean isHomesPageRequest(CommandInvocation invocation) {
    return invocation.args().length >= 1 && "page".equalsIgnoreCase(invocation.args()[0]);
  }

  private int parseHomesPage(CommandInvocation invocation) {
    if (invocation.args().length == 0) {
      return 1;
    }
    if (!isHomesPageRequest(invocation) || invocation.args().length != 2) {
      return -1;
    }
    try {
      return Integer.parseInt(invocation.args()[1]);
    } catch (NumberFormatException ex) {
      return -1;
    }
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

  private static final class StashInventoryHolder implements InventoryHolder {
    private final UUID playerUuid;
    private Inventory inventory;

    private StashInventoryHolder(UUID playerUuid) {
      this.playerUuid = playerUuid;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }

    private void inventory(Inventory inventory) {
      this.inventory = inventory;
    }

    @SuppressWarnings("unused")
    private UUID playerUuid() {
      return playerUuid;
    }
  }

  private static final class StashView {
    private final UUID sessionId;
    private final Inventory inventory;
    private final int stashSlots;
    private final int depositInputSlot;
    private final int depositConfirmSlot;
    private final int depositInfoSlot;
    private final int depositStatusSlot;
    private final int summarySlot;
    private final int withdrawStatusSlot;
    private volatile boolean depositAvailable;
    private volatile boolean withdrawAvailable;
    private volatile boolean awaitingResponse;
    private volatile boolean closed;
    private volatile ItemStack pendingDepositItem;

    private StashView(UUID sessionId, Inventory inventory, int stashSlots, int depositInputSlot,
                      int depositConfirmSlot, int depositInfoSlot, int depositStatusSlot,
                      int summarySlot, int withdrawStatusSlot) {
      this.sessionId = sessionId;
      this.inventory = inventory;
      this.stashSlots = stashSlots;
      this.depositInputSlot = depositInputSlot;
      this.depositConfirmSlot = depositConfirmSlot;
      this.depositInfoSlot = depositInfoSlot;
      this.depositStatusSlot = depositStatusSlot;
      this.summarySlot = summarySlot;
      this.withdrawStatusSlot = withdrawStatusSlot;
    }

    private UUID sessionId() {
      return sessionId;
    }

    private Inventory inventory() {
      return inventory;
    }

    private int stashSlots() {
      return stashSlots;
    }

    private int depositInputSlot() {
      return depositInputSlot;
    }

    private int depositConfirmSlot() {
      return depositConfirmSlot;
    }

    private int depositInfoSlot() {
      return depositInfoSlot;
    }

    private int depositStatusSlot() {
      return depositStatusSlot;
    }

    private int summarySlot() {
      return summarySlot;
    }

    private int withdrawStatusSlot() {
      return withdrawStatusSlot;
    }

    private boolean depositAvailable() {
      return depositAvailable;
    }

    private void depositAvailable(boolean depositAvailable) {
      this.depositAvailable = depositAvailable;
    }

    private boolean withdrawAvailable() {
      return withdrawAvailable;
    }

    private void withdrawAvailable(boolean withdrawAvailable) {
      this.withdrawAvailable = withdrawAvailable;
    }

    private boolean awaitingResponse() {
      return awaitingResponse;
    }

    private void awaitingResponse(boolean awaitingResponse) {
      this.awaitingResponse = awaitingResponse;
    }

    private boolean closed() {
      return closed;
    }

    private void closed(boolean closed) {
      this.closed = closed;
    }

    private ItemStack pendingDepositItem() {
      return pendingDepositItem;
    }

    private void pendingDepositItem(ItemStack pendingDepositItem) {
      this.pendingDepositItem = pendingDepositItem;
    }
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
