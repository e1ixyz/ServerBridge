package dev.e1ixyz.serverbridge.proxy;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SocialPreferencesStore {
  private final Path path;
  private final Logger logger;
  private final ConcurrentMap<UUID, PlayerPreferences> preferences = new ConcurrentHashMap<>();

  private SocialPreferencesStore(Path path, Logger logger) {
    this.path = path;
    this.logger = logger;
  }

  public static SocialPreferencesStore loadOrCreate(Path path, Logger logger) throws IOException {
    if (!Files.exists(path)) {
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      Files.writeString(path, "players: {}\n", StandardCharsets.UTF_8);
      logger.info("Wrote default ServerBridge social preferences to {}", path.toAbsolutePath());
    }

    SocialPreferencesStore store = new SocialPreferencesStore(path, logger);
    store.load();
    return store;
  }

  public boolean areMessagesDisabled(UUID playerUuid) {
    PlayerPreferences preferences = this.preferences.get(playerUuid);
    return preferences != null && preferences.messagesDisabled;
  }

  public synchronized boolean toggleMessagesDisabled(UUID playerUuid) throws IOException {
    return setMessagesDisabled(playerUuid, !areMessagesDisabled(playerUuid));
  }

  public synchronized boolean setMessagesDisabled(UUID playerUuid, boolean disabled) throws IOException {
    PlayerPreferences preferences = this.preferences.computeIfAbsent(playerUuid, ignored -> new PlayerPreferences());
    preferences.messagesDisabled = disabled;
    prune(playerUuid, preferences);
    save();
    return disabled;
  }

  public boolean isIgnoring(UUID playerUuid, UUID targetUuid) {
    PlayerPreferences preferences = this.preferences.get(playerUuid);
    return preferences != null && preferences.ignoredPlayers.containsKey(targetUuid);
  }

  public synchronized IgnoreResult toggleIgnore(UUID playerUuid, UUID targetUuid, String targetName) throws IOException {
    PlayerPreferences preferences = this.preferences.computeIfAbsent(playerUuid, ignored -> new PlayerPreferences());
    String existingName = preferences.ignoredPlayers.get(targetUuid);
    if (existingName != null) {
      preferences.ignoredPlayers.remove(targetUuid);
      prune(playerUuid, preferences);
      save();
      return new IgnoreResult(IgnoreAction.REMOVED, new IgnoredEntry(targetUuid, existingName));
    }

    preferences.ignoredPlayers.put(targetUuid, sanitizeName(targetName, targetUuid));
    save();
    return new IgnoreResult(IgnoreAction.ADDED, new IgnoredEntry(targetUuid, sanitizeName(targetName, targetUuid)));
  }

  public synchronized IgnoreResult removeIgnore(UUID playerUuid, UUID targetUuid) throws IOException {
    PlayerPreferences preferences = this.preferences.get(playerUuid);
    if (preferences == null) {
      return new IgnoreResult(IgnoreAction.NOT_IGNORED, null);
    }

    String removedName = preferences.ignoredPlayers.remove(targetUuid);
    if (removedName == null) {
      return new IgnoreResult(IgnoreAction.NOT_IGNORED, null);
    }

    prune(playerUuid, preferences);
    save();
    return new IgnoreResult(IgnoreAction.REMOVED, new IgnoredEntry(targetUuid, removedName));
  }

  public IgnoredEntry resolveIgnoredByName(UUID playerUuid, String targetName) {
    if (targetName == null || targetName.isBlank()) {
      return null;
    }
    PlayerPreferences preferences = this.preferences.get(playerUuid);
    if (preferences == null) {
      return null;
    }

    for (Map.Entry<UUID, String> entry : preferences.ignoredPlayers.entrySet()) {
      if (entry.getValue().equalsIgnoreCase(targetName)) {
        return new IgnoredEntry(entry.getKey(), entry.getValue());
      }
    }
    return null;
  }

  public List<IgnoredEntry> listIgnored(UUID playerUuid) {
    PlayerPreferences preferences = this.preferences.get(playerUuid);
    if (preferences == null || preferences.ignoredPlayers.isEmpty()) {
      return List.of();
    }

    List<IgnoredEntry> ignored = new ArrayList<>();
    for (Map.Entry<UUID, String> entry : preferences.ignoredPlayers.entrySet()) {
      ignored.add(new IgnoredEntry(entry.getKey(), entry.getValue()));
    }
    ignored.sort(Comparator.comparing(IgnoredEntry::name, String.CASE_INSENSITIVE_ORDER));
    return ignored;
  }

  private void load() throws IOException {
    preferences.clear();
    Yaml yaml = new Yaml();
    try (InputStream input = Files.newInputStream(path)) {
      Object rootObject = yaml.load(input);
      if (!(rootObject instanceof Map<?, ?> root)) {
        return;
      }

      Object playersObject = root.get("players");
      if (!(playersObject instanceof Map<?, ?> players)) {
        return;
      }

      for (Map.Entry<?, ?> playerEntry : players.entrySet()) {
        UUID playerUuid = parseUuid(playerEntry.getKey());
        if (playerUuid == null || !(playerEntry.getValue() instanceof Map<?, ?> playerValues)) {
          continue;
        }

        PlayerPreferences preferences = new PlayerPreferences();
        Object disabledObject = playerValues.get("messagesDisabled");
        preferences.messagesDisabled = disabledObject instanceof Boolean disabled && disabled;

        Object ignoresObject = playerValues.get("ignores");
        if (ignoresObject instanceof Map<?, ?> ignores) {
          for (Map.Entry<?, ?> ignoreEntry : ignores.entrySet()) {
            UUID ignoredUuid = parseUuid(ignoreEntry.getKey());
            if (ignoredUuid == null) {
              continue;
            }
            preferences.ignoredPlayers.put(ignoredUuid, sanitizeName(ignoreEntry.getValue(), ignoredUuid));
          }
        }

        if (!preferences.isDefault()) {
          this.preferences.put(playerUuid, preferences);
        }
      }
    }
  }

  private synchronized void save() throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();
    Map<String, Object> players = new LinkedHashMap<>();

    List<Map.Entry<UUID, PlayerPreferences>> entries = new ArrayList<>(preferences.entrySet());
    entries.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));
    for (Map.Entry<UUID, PlayerPreferences> entry : entries) {
      PlayerPreferences preferences = entry.getValue();
      if (preferences.isDefault()) {
        continue;
      }

      Map<String, Object> player = new LinkedHashMap<>();
      if (preferences.messagesDisabled) {
        player.put("messagesDisabled", true);
      }

      if (!preferences.ignoredPlayers.isEmpty()) {
        Map<String, Object> ignores = new LinkedHashMap<>();
        List<Map.Entry<UUID, String>> ignoredEntries = new ArrayList<>(preferences.ignoredPlayers.entrySet());
        ignoredEntries.sort(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER));
        for (Map.Entry<UUID, String> ignoredEntry : ignoredEntries) {
          ignores.put(ignoredEntry.getKey().toString(), ignoredEntry.getValue());
        }
        player.put("ignores", ignores);
      }

      players.put(entry.getKey().toString(), player);
    }

    root.put("players", players);
    Files.writeString(path, new Yaml().dump(root), StandardCharsets.UTF_8);
  }

  private void prune(UUID playerUuid, PlayerPreferences preferences) {
    if (preferences.isDefault()) {
      this.preferences.remove(playerUuid);
    }
  }

  private UUID parseUuid(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException ignored) {
      logger.warn("Ignoring invalid UUID '{}' in social preferences {}", value, path.toAbsolutePath());
      return null;
    }
  }

  private static String sanitizeName(Object value, UUID fallbackUuid) {
    String name = value == null ? "" : String.valueOf(value).trim();
    return name.isEmpty() ? fallbackUuid.toString() : name;
  }

  private static final class PlayerPreferences {
    private volatile boolean messagesDisabled;
    private final ConcurrentMap<UUID, String> ignoredPlayers = new ConcurrentHashMap<>();

    private boolean isDefault() {
      return !messagesDisabled && ignoredPlayers.isEmpty();
    }
  }

  public enum IgnoreAction {
    ADDED,
    REMOVED,
    NOT_IGNORED
  }

  public record IgnoreResult(IgnoreAction action, IgnoredEntry entry) {
  }

  public record IgnoredEntry(UUID uuid, String name) {
  }
}
