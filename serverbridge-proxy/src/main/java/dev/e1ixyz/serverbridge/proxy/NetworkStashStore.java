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
  private static final int MAX_LOG_ENTRIES = 2000;

  private final Path path;
  private final Logger logger;
  private final List<StoredItem> slots = new ArrayList<>();
  private final ConcurrentMap<UUID, PlayerUsage> usageByPlayer = new ConcurrentHashMap<>();
  private final List<LogEntry> logs = new ArrayList<>();

  private NetworkStashStore(Path path, Logger logger) {
    this.path = path;
    this.logger = logger;
  }

  public static NetworkStashStore loadOrCreate(Path path, Logger logger) throws IOException {
    if (!Files.exists(path)) {
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      Files.writeString(path, "stash:\n  slots: {}\nplayers: {}\nlogs: []\n", StandardCharsets.UTF_8);
      logger.info("Wrote default ServerBridge network stash store to {}", path.toAbsolutePath());
    }

    NetworkStashStore store = new NetworkStashStore(path, logger);
    store.load();
    return store;
  }

  public synchronized StashSnapshot snapshot(UUID playerUuid, String playerName, ZoneId zoneId, int configuredSlotCount) {
    int slotCount = ensureSlotCount(configuredSlotCount);
    LocalDate today = LocalDate.now(zoneId);
    PlayerUsage usage = usageByPlayer.get(playerUuid);
    if (usage != null && playerName != null && !playerName.isBlank()) {
      usage.lastKnownName = sanitizeName(playerName, playerUuid);
    }
    return new StashSnapshot(copySlots(slotCount), canDeposit(usage, today), canWithdraw(usage, today));
  }

  public synchronized DepositResult deposit(UUID playerUuid, String playerName, ZoneId zoneId, int configuredSlotCount,
                                            byte[] itemBytes, String itemSummary) throws IOException {
    int slotCount = ensureSlotCount(configuredSlotCount);
    if (itemBytes == null || itemBytes.length == 0) {
      return new DepositResult(StashActionStatus.INVALID_ITEM, snapshot(playerUuid, playerName, zoneId, slotCount));
    }

    LocalDate today = LocalDate.now(zoneId);
    PlayerUsage usage = usageByPlayer.computeIfAbsent(playerUuid, ignored -> new PlayerUsage());
    usage.lastKnownName = sanitizeName(playerName, playerUuid);
    if (!canDeposit(usage, today)) {
      return new DepositResult(StashActionStatus.DEPOSIT_ALREADY_USED, snapshot(playerUuid, usage.lastKnownName, zoneId, slotCount));
    }

    int emptySlot = findFirstEmptySlot(slotCount);
    if (emptySlot < 0) {
      return new DepositResult(StashActionStatus.STASH_FULL, snapshot(playerUuid, usage.lastKnownName, zoneId, slotCount));
    }

    String summary = sanitizeSummary(itemSummary);
    slots.set(emptySlot, new StoredItem(copyBytes(itemBytes), summary));
    usage.lastDepositDay = today;
    addLog(new LogEntry(System.currentTimeMillis(), playerUuid, usage.lastKnownName, LogAction.DEPOSIT, summary, playerUuid, usage.lastKnownName));
    pruneUsage(playerUuid, usage);
    save();
    return new DepositResult(StashActionStatus.SUCCESS, snapshot(playerUuid, usage.lastKnownName, zoneId, slotCount));
  }

  public synchronized WithdrawResult withdraw(UUID playerUuid, String playerName, ZoneId zoneId, int configuredSlotCount, int slot)
      throws IOException {
    int slotCount = ensureSlotCount(configuredSlotCount);
    if (slot < 0 || slot >= slotCount) {
      return new WithdrawResult(StashActionStatus.INVALID_SLOT, null, snapshot(playerUuid, playerName, zoneId, slotCount));
    }

    LocalDate today = LocalDate.now(zoneId);
    PlayerUsage usage = usageByPlayer.computeIfAbsent(playerUuid, ignored -> new PlayerUsage());
    usage.lastKnownName = sanitizeName(playerName, playerUuid);
    if (!canWithdraw(usage, today)) {
      return new WithdrawResult(StashActionStatus.WITHDRAW_ALREADY_USED, null, snapshot(playerUuid, usage.lastKnownName, zoneId, slotCount));
    }

    StoredItem storedItem = slots.get(slot);
    if (storedItem == null || storedItem.bytes() == null || storedItem.bytes().length == 0) {
      return new WithdrawResult(StashActionStatus.EMPTY_SLOT, null, snapshot(playerUuid, usage.lastKnownName, zoneId, slotCount));
    }

    slots.set(slot, null);
    usage.lastWithdrawDay = today;
    addLog(new LogEntry(System.currentTimeMillis(), playerUuid, usage.lastKnownName, LogAction.WITHDRAW,
        sanitizeSummary(storedItem.summary()), playerUuid, usage.lastKnownName));
    pruneUsage(playerUuid, usage);
    save();
    return new WithdrawResult(StashActionStatus.SUCCESS, copyBytes(storedItem.bytes()), snapshot(playerUuid, usage.lastKnownName, zoneId, slotCount));
  }

  public synchronized LogPage listLogs(String filterPlayerName, int requestedPage, int pageSize) {
    String normalizedFilter = filterPlayerName == null || filterPlayerName.isBlank() ? null : filterPlayerName.trim();
    List<LogEntry> matching = new ArrayList<>();
    for (LogEntry entry : logs) {
      if (normalizedFilter == null || entry.playerName().equalsIgnoreCase(normalizedFilter)) {
        matching.add(entry);
      }
    }
    matching.sort(Comparator.comparingLong(LogEntry::timestamp).reversed());

    int safePageSize = Math.max(1, pageSize);
    int totalEntries = matching.size();
    int totalPages = Math.max(1, (int) Math.ceil(totalEntries / (double) safePageSize));
    int page = Math.max(1, Math.min(requestedPage, totalPages));
    int fromIndex = Math.min(totalEntries, (page - 1) * safePageSize);
    int toIndex = Math.min(totalEntries, fromIndex + safePageSize);
    return new LogPage(matching.subList(fromIndex, toIndex), page, totalPages, totalEntries, normalizedFilter);
  }

  public synchronized TrackedPlayer resolveTrackedPlayer(String targetName) {
    if (targetName == null || targetName.isBlank()) {
      return null;
    }

    for (Map.Entry<UUID, PlayerUsage> entry : usageByPlayer.entrySet()) {
      PlayerUsage usage = entry.getValue();
      if (usage.lastKnownName != null && usage.lastKnownName.equalsIgnoreCase(targetName)) {
        return new TrackedPlayer(entry.getKey(), usage.lastKnownName);
      }
    }

    for (LogEntry entry : logs) {
      if (entry.playerName().equalsIgnoreCase(targetName)) {
        return new TrackedPlayer(entry.playerUuid(), entry.playerName());
      }
    }
    return null;
  }

  public synchronized ResetResult resetUsage(UUID targetUuid, String targetName, ResetScope scope,
                                             UUID actorUuid, String actorName) throws IOException {
    PlayerUsage usage = usageByPlayer.computeIfAbsent(targetUuid, ignored -> new PlayerUsage());
    usage.lastKnownName = sanitizeName(targetName, targetUuid);

    boolean changed = false;
    if (scope == ResetScope.ALL || scope == ResetScope.DEPOSIT) {
      changed |= usage.lastDepositDay != null;
      usage.lastDepositDay = null;
    }
    if (scope == ResetScope.ALL || scope == ResetScope.WITHDRAW) {
      changed |= usage.lastWithdrawDay != null;
      usage.lastWithdrawDay = null;
    }

    addLog(new LogEntry(System.currentTimeMillis(), targetUuid, usage.lastKnownName, LogAction.RESET,
        scope.name().toLowerCase(), actorUuid, sanitizeName(actorName, actorUuid)));
    pruneUsage(targetUuid, usage);
    save();
    return new ResetResult(usage.lastKnownName, changed);
  }

  private void load() throws IOException {
    slots.clear();
    usageByPlayer.clear();
    logs.clear();
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

      Object logsObject = root.get("logs");
      if (logsObject instanceof List<?> logEntries) {
        loadLogs(logEntries);
      }
    }
  }

  private void loadSlots(Map<?, ?> slotMap) {
    int highestSlot = -1;
    Map<Integer, StoredItem> loaded = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : slotMap.entrySet()) {
      Integer slot = parseSlot(entry.getKey());
      if (slot == null || slot < 0) {
        continue;
      }
      StoredItem stored = parseStoredItem(entry.getValue(), slot);
      if (stored == null || stored.bytes() == null || stored.bytes().length == 0) {
        continue;
      }
      loaded.put(slot, stored);
      highestSlot = Math.max(highestSlot, slot);
    }

    for (int index = 0; index <= highestSlot; index++) {
      StoredItem stored = loaded.get(index);
      slots.add(stored == null ? null : stored.copy());
    }
  }

  private void loadPlayers(Map<?, ?> players) {
    for (Map.Entry<?, ?> entry : players.entrySet()) {
      UUID playerUuid = parseUuid(entry.getKey());
      if (playerUuid == null || !(entry.getValue() instanceof Map<?, ?> values)) {
        continue;
      }

      PlayerUsage usage = new PlayerUsage();
      usage.lastKnownName = sanitizeName(values.get("name"), playerUuid);
      usage.lastDepositDay = parseDate(values.get("lastDepositDay"), "lastDepositDay", playerUuid);
      usage.lastWithdrawDay = parseDate(values.get("lastWithdrawDay"), "lastWithdrawDay", playerUuid);
      if (!usage.isDefault()) {
        usageByPlayer.put(playerUuid, usage);
      }
    }
  }

  private void loadLogs(List<?> logEntries) {
    for (Object logEntry : logEntries) {
      if (!(logEntry instanceof Map<?, ?> values)) {
        continue;
      }
      UUID playerUuid = parseUuid(values.get("playerUuid"));
      String playerName = playerUuid == null ? null : sanitizeName(values.get("playerName"), playerUuid);
      LogAction action = parseLogAction(values.get("action"));
      Long timestamp = parseLong(values.get("timestamp"));
      if (playerUuid == null || playerName == null || action == null || timestamp == null) {
        continue;
      }
      UUID actorUuid = parseUuid(values.get("actorUuid"));
      String actorName = actorUuid == null ? sanitizeOptionalName(values.get("actorName")) : sanitizeName(values.get("actorName"), actorUuid);
      String detail = sanitizeOptionalName(values.get("detail"));
      logs.add(new LogEntry(timestamp, playerUuid, playerName, action, detail, actorUuid, actorName));
    }
    trimLogs();
  }

  private synchronized void save() throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();

    Map<String, Object> stash = new LinkedHashMap<>();
    Map<String, Object> slotMap = new LinkedHashMap<>();
    for (int slot = 0; slot < slots.size(); slot++) {
      StoredItem item = slots.get(slot);
      if (item != null && item.bytes() != null && item.bytes().length > 0) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("data", Base64.getEncoder().encodeToString(item.bytes()));
        if (item.summary() != null && !item.summary().isBlank()) {
          stored.put("summary", item.summary());
        }
        slotMap.put(Integer.toString(slot), stored);
      }
    }
    stash.put("slots", slotMap);
    root.put("stash", stash);

    Map<String, Object> players = new LinkedHashMap<>();
    List<Map.Entry<UUID, PlayerUsage>> usageEntries = new ArrayList<>(usageByPlayer.entrySet());
    usageEntries.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));
    for (Map.Entry<UUID, PlayerUsage> entry : usageEntries) {
      PlayerUsage usage = entry.getValue();
      if (usage.isDefault()) {
        continue;
      }

      Map<String, Object> values = new LinkedHashMap<>();
      if (usage.lastKnownName != null && !usage.lastKnownName.isBlank()) {
        values.put("name", usage.lastKnownName);
      }
      if (usage.lastDepositDay != null) {
        values.put("lastDepositDay", usage.lastDepositDay.toString());
      }
      if (usage.lastWithdrawDay != null) {
        values.put("lastWithdrawDay", usage.lastWithdrawDay.toString());
      }
      players.put(entry.getKey().toString(), values);
    }
    root.put("players", players);

    List<Object> serializedLogs = new ArrayList<>();
    List<LogEntry> logEntries = new ArrayList<>(logs);
    logEntries.sort(Comparator.comparingLong(LogEntry::timestamp));
    for (LogEntry entry : logEntries) {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("timestamp", entry.timestamp());
      values.put("playerUuid", entry.playerUuid().toString());
      values.put("playerName", entry.playerName());
      values.put("action", entry.action().name());
      if (entry.detail() != null && !entry.detail().isBlank()) {
        values.put("detail", entry.detail());
      }
      if (entry.actorUuid() != null) {
        values.put("actorUuid", entry.actorUuid().toString());
      }
      if (entry.actorName() != null && !entry.actorName().isBlank()) {
        values.put("actorName", entry.actorName());
      }
      serializedLogs.add(values);
    }
    root.put("logs", serializedLogs);

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
      StoredItem item = slots.get(index);
      snapshot.add(item == null ? null : copyBytes(item.bytes()));
    }
    return snapshot;
  }

  private int findFirstEmptySlot(int slotCount) {
    for (int slot = 0; slot < slotCount; slot++) {
      StoredItem item = slots.get(slot);
      if (item == null || item.bytes() == null || item.bytes().length == 0) {
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

  private void addLog(LogEntry entry) {
    logs.add(entry);
    trimLogs();
  }

  private void trimLogs() {
    while (logs.size() > MAX_LOG_ENTRIES) {
      logs.remove(0);
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

  private StoredItem parseStoredItem(Object raw, int slot) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Map<?, ?> values) {
      byte[] itemBytes = parseItemBytes(values.get("data"), slot);
      if (itemBytes == null) {
        return null;
      }
      return new StoredItem(itemBytes, sanitizeSummary(values.get("summary")));
    }

    byte[] legacyBytes = parseItemBytes(raw, slot);
    return legacyBytes == null ? null : new StoredItem(legacyBytes, null);
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

  private LogAction parseLogAction(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      return LogAction.valueOf(String.valueOf(raw).trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      logger.warn("Ignoring invalid stash log action '{}' in {}", raw, path.toAbsolutePath());
      return null;
    }
  }

  private Long parseLong(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      return Long.parseLong(String.valueOf(raw));
    } catch (NumberFormatException ex) {
      logger.warn("Ignoring invalid numeric stash value '{}' in {}", raw, path.toAbsolutePath());
      return null;
    }
  }

  private static String sanitizeName(Object raw, UUID fallbackUuid) {
    String name = sanitizeOptionalName(raw);
    return name == null ? fallbackUuid.toString() : name;
  }

  private static String sanitizeOptionalName(Object raw) {
    if (raw == null) {
      return null;
    }
    String value = String.valueOf(raw).trim();
    return value.isEmpty() ? null : value;
  }

  private static String sanitizeSummary(Object raw) {
    String value = sanitizeOptionalName(raw);
    return value == null ? "unknown item" : value;
  }

  private static byte[] copyBytes(byte[] value) {
    return value == null ? null : Arrays.copyOf(value, value.length);
  }

  private static final class PlayerUsage {
    private volatile String lastKnownName;
    private volatile LocalDate lastDepositDay;
    private volatile LocalDate lastWithdrawDay;

    private boolean isDefault() {
      return lastDepositDay == null && lastWithdrawDay == null;
    }
  }

  private record StoredItem(byte[] bytes, String summary) {
    private StoredItem copy() {
      return new StoredItem(copyBytes(bytes), summary);
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

  public enum ResetScope {
    DEPOSIT,
    WITHDRAW,
    ALL
  }

  public enum LogAction {
    DEPOSIT,
    WITHDRAW,
    RESET
  }

  public record StashSnapshot(List<byte[]> slots, boolean depositAvailable, boolean withdrawAvailable) {
  }

  public record DepositResult(StashActionStatus status, StashSnapshot snapshot) {
  }

  public record WithdrawResult(StashActionStatus status, byte[] withdrawnItem, StashSnapshot snapshot) {
  }

  public record LogEntry(long timestamp, UUID playerUuid, String playerName, LogAction action,
                         String detail, UUID actorUuid, String actorName) {
  }

  public record LogPage(List<LogEntry> entries, int page, int totalPages, int totalEntries, String filterPlayerName) {
  }

  public record TrackedPlayer(UUID uuid, String name) {
  }

  public record ResetResult(String targetName, boolean changed) {
  }
}
