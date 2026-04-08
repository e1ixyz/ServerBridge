package dev.e1ixyz.serverbridge.proxy;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class NetworkStashStore {
  private final Path path;
  private final Logger logger;
  private final List<byte[]> slots = new ArrayList<>();
  private final ConcurrentMap<UUID, PlayerUsage> usageByPlayer = new ConcurrentHashMap<>();

  private NetworkStashStore(Path path, Logger logger) {
    this.path = path;
    this.logger = logger;
  }

  public static NetworkStashStore loadOrCreate(Path path, Logger logger) throws IOException {
    if (!Files.exists(path)) {
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      Files.writeString(path, "stash:\n  slots: {}\nplayers: {}\n", StandardCharsets.UTF_8);
      logger.info("Wrote default ServerBridge network stash store to {}", path.toAbsolutePath());
    }

    NetworkStashStore store = new NetworkStashStore(path, logger);
    store.load();
    return store;
  }

  public synchronized StashSnapshot snapshot(UUID playerUuid, ZoneId zoneId, int configuredSlotCount) {
    int slotCount = ensureSlotCount(configuredSlotCount);
    LocalDate today = LocalDate.now(zoneId);
    PlayerUsage usage = usageByPlayer.get(playerUuid);
    return new StashSnapshot(copySlots(slotCount), canDeposit(usage, today), canWithdraw(usage, today));
  }

  public synchronized DepositResult deposit(UUID playerUuid, ZoneId zoneId, int configuredSlotCount, byte[] itemBytes)
      throws IOException {
    int slotCount = ensureSlotCount(configuredSlotCount);
    if (itemBytes == null || itemBytes.length == 0) {
      return new DepositResult(StashActionStatus.INVALID_ITEM, snapshot(playerUuid, zoneId, slotCount));
    }

    LocalDate today = LocalDate.now(zoneId);
    PlayerUsage usage = usageByPlayer.computeIfAbsent(playerUuid, ignored -> new PlayerUsage());
    if (!canDeposit(usage, today)) {
      return new DepositResult(StashActionStatus.DEPOSIT_ALREADY_USED, snapshot(playerUuid, zoneId, slotCount));
    }

    int emptySlot = findFirstEmptySlot(slotCount);
    if (emptySlot < 0) {
      return new DepositResult(StashActionStatus.STASH_FULL, snapshot(playerUuid, zoneId, slotCount));
    }

    slots.set(emptySlot, copy(itemBytes));
    usage.lastDepositDay = today;
    pruneUsage(playerUuid, usage);
    save();
    return new DepositResult(StashActionStatus.SUCCESS, snapshot(playerUuid, zoneId, slotCount));
  }

  public synchronized WithdrawResult withdraw(UUID playerUuid, ZoneId zoneId, int configuredSlotCount, int slot)
      throws IOException {
    int slotCount = ensureSlotCount(configuredSlotCount);
    if (slot < 0 || slot >= slotCount) {
      return new WithdrawResult(StashActionStatus.INVALID_SLOT, null, snapshot(playerUuid, zoneId, slotCount));
    }

    LocalDate today = LocalDate.now(zoneId);
    PlayerUsage usage = usageByPlayer.computeIfAbsent(playerUuid, ignored -> new PlayerUsage());
    if (!canWithdraw(usage, today)) {
      return new WithdrawResult(StashActionStatus.WITHDRAW_ALREADY_USED, null, snapshot(playerUuid, zoneId, slotCount));
    }

    byte[] itemBytes = slots.get(slot);
    if (itemBytes == null || itemBytes.length == 0) {
      return new WithdrawResult(StashActionStatus.EMPTY_SLOT, null, snapshot(playerUuid, zoneId, slotCount));
    }

    slots.set(slot, null);
    usage.lastWithdrawDay = today;
    pruneUsage(playerUuid, usage);
    save();
    return new WithdrawResult(StashActionStatus.SUCCESS, copy(itemBytes), snapshot(playerUuid, zoneId, slotCount));
  }

  private void load() throws IOException {
    slots.clear();
    usageByPlayer.clear();
    Yaml yaml = new Yaml();
    try (InputStream input = Files.newInputStream(path)) {
      Object rootObject = yaml.load(input);
      if (!(rootObject instanceof Map<?, ?> root)) {
        return;
      }

      Object stashObject = root.get("stash");
      if (stashObject instanceof Map<?, ?> stash) {
        Object slotsObject = stash.get("slots");
        if (slotsObject instanceof Map<?, ?> slotMap) {
          loadSlots(slotMap);
        }
      }

      Object playersObject = root.get("players");
      if (playersObject instanceof Map<?, ?> players) {
        loadPlayers(players);
      }
    }
  }

  private void loadSlots(Map<?, ?> slotMap) {
    int highestSlot = -1;
    Map<Integer, byte[]> loaded = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : slotMap.entrySet()) {
      Integer slot = parseSlot(entry.getKey());
      if (slot == null || slot < 0) {
        continue;
      }
      byte[] itemBytes = parseItemBytes(entry.getValue(), slot);
      if (itemBytes == null) {
        continue;
      }
      loaded.put(slot, itemBytes);
      highestSlot = Math.max(highestSlot, slot);
    }

    for (int index = 0; index <= highestSlot; index++) {
      slots.add(copy(loaded.get(index)));
    }
  }

  private void loadPlayers(Map<?, ?> players) {
    for (Map.Entry<?, ?> entry : players.entrySet()) {
      UUID playerUuid = parseUuid(entry.getKey());
      if (playerUuid == null || !(entry.getValue() instanceof Map<?, ?> values)) {
        continue;
      }

      PlayerUsage usage = new PlayerUsage();
      usage.lastDepositDay = parseDate(values.get("lastDepositDay"), "lastDepositDay", playerUuid);
      usage.lastWithdrawDay = parseDate(values.get("lastWithdrawDay"), "lastWithdrawDay", playerUuid);
      if (!usage.isDefault()) {
        usageByPlayer.put(playerUuid, usage);
      }
    }
  }

  private synchronized void save() throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();

    Map<String, Object> stash = new LinkedHashMap<>();
    Map<String, Object> slotMap = new LinkedHashMap<>();
    for (int slot = 0; slot < slots.size(); slot++) {
      byte[] itemBytes = slots.get(slot);
      if (itemBytes != null && itemBytes.length > 0) {
        slotMap.put(Integer.toString(slot), Base64.getEncoder().encodeToString(itemBytes));
      }
    }
    stash.put("slots", slotMap);
    root.put("stash", stash);

    Map<String, Object> players = new LinkedHashMap<>();
    List<Map.Entry<UUID, PlayerUsage>> entries = new ArrayList<>(usageByPlayer.entrySet());
    entries.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));
    for (Map.Entry<UUID, PlayerUsage> entry : entries) {
      PlayerUsage usage = entry.getValue();
      if (usage.isDefault()) {
        continue;
      }

      Map<String, Object> values = new LinkedHashMap<>();
      if (usage.lastDepositDay != null) {
        values.put("lastDepositDay", usage.lastDepositDay.toString());
      }
      if (usage.lastWithdrawDay != null) {
        values.put("lastWithdrawDay", usage.lastWithdrawDay.toString());
      }
      players.put(entry.getKey().toString(), values);
    }
    root.put("players", players);

    Files.writeString(path, new Yaml().dump(root), StandardCharsets.UTF_8);
  }

  private int ensureSlotCount(int configuredSlotCount) {
    int normalized = Math.max(9, configuredSlotCount);
    while (slots.size() < normalized) {
      slots.add(null);
    }
    return slots.size();
  }

  private List<byte[]> copySlots(int slotCount) {
    List<byte[]> snapshot = new ArrayList<>(slotCount);
    for (int index = 0; index < slotCount; index++) {
      snapshot.add(copy(slots.get(index)));
    }
    return snapshot;
  }

  private int findFirstEmptySlot(int slotCount) {
    for (int slot = 0; slot < slotCount; slot++) {
      byte[] itemBytes = slots.get(slot);
      if (itemBytes == null || itemBytes.length == 0) {
        return slot;
      }
    }
    return -1;
  }

  private boolean canDeposit(PlayerUsage usage, LocalDate today) {
    return usage == null || usage.lastDepositDay == null || !usage.lastDepositDay.equals(today);
  }

  private boolean canWithdraw(PlayerUsage usage, LocalDate today) {
    return usage == null || usage.lastWithdrawDay == null || !usage.lastWithdrawDay.equals(today);
  }

  private void pruneUsage(UUID playerUuid, PlayerUsage usage) {
    if (usage.isDefault()) {
      usageByPlayer.remove(playerUuid);
    }
  }

  private Integer parseSlot(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      return Integer.parseInt(String.valueOf(raw));
    } catch (NumberFormatException ex) {
      logger.warn("Ignoring invalid stash slot '{}' in {}", raw, path.toAbsolutePath());
      return null;
    }
  }

  private byte[] parseItemBytes(Object raw, int slot) {
    if (raw == null) {
      return null;
    }
    try {
      return Base64.getDecoder().decode(String.valueOf(raw));
    } catch (IllegalArgumentException ex) {
      logger.warn("Ignoring invalid stash item payload for slot {} in {}", slot, path.toAbsolutePath());
      return null;
    }
  }

  private UUID parseUuid(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (IllegalArgumentException ex) {
      logger.warn("Ignoring invalid stash player UUID '{}' in {}", raw, path.toAbsolutePath());
      return null;
    }
  }

  private LocalDate parseDate(Object raw, String field, UUID playerUuid) {
    if (raw == null) {
      return null;
    }
    try {
      return LocalDate.parse(String.valueOf(raw));
    } catch (Exception ex) {
      logger.warn("Ignoring invalid {} '{}' for {} in {}", field, raw, playerUuid, path.toAbsolutePath());
      return null;
    }
  }

  private static byte[] copy(byte[] value) {
    return value == null ? null : Arrays.copyOf(value, value.length);
  }

  private static final class PlayerUsage {
    private volatile LocalDate lastDepositDay;
    private volatile LocalDate lastWithdrawDay;

    private boolean isDefault() {
      return lastDepositDay == null && lastWithdrawDay == null;
    }
  }

  public enum StashActionStatus {
    SUCCESS,
    DEPOSIT_ALREADY_USED,
    WITHDRAW_ALREADY_USED,
    STASH_FULL,
    EMPTY_SLOT,
    INVALID_SLOT,
    INVALID_ITEM
  }

  public record StashSnapshot(List<byte[]> slots, boolean depositAvailable, boolean withdrawAvailable) {
  }

  public record DepositResult(StashActionStatus status, StashSnapshot snapshot) {
  }

  public record WithdrawResult(StashActionStatus status, byte[] withdrawnItem, StashSnapshot snapshot) {
  }
}
