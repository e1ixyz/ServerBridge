package dev.e1ixyz.serverbridge.proxy;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class HomeService {
  public enum LookupStatus {
    FOUND,
    MISSING,
    AMBIGUOUS
  }

  public record HomeEntry(String server, String name) {
  }

  public record HomeLookup(LookupStatus status, HomeEntry match, List<HomeEntry> candidates) {
  }

  private final ProxyConfig config;
  private final ServerManagerAccessor serverManagerAccessor;
  private final Logger logger;
  private final Yaml yaml = new Yaml();

  public HomeService(ProxyConfig config, ServerManagerAccessor serverManagerAccessor, Logger logger) {
    this.config = config;
    this.serverManagerAccessor = serverManagerAccessor;
    this.logger = logger;
  }

  public List<HomeEntry> listHomes(UUID uuid, String username) {
    Map<String, Path> servers = serverManagerAccessor.resolveManagedServerWorkingDirectories();
    List<HomeEntry> homes = new ArrayList<>();

    for (Map.Entry<String, Path> entry : servers.entrySet()) {
      Path userFile = resolveUserFile(entry.getValue(), uuid, username);
      if (userFile == null) {
        continue;
      }
      homes.addAll(readHomes(entry.getKey(), userFile));
    }

    homes.sort(Comparator.comparing(HomeEntry::server, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(HomeEntry::name, String.CASE_INSENSITIVE_ORDER));
    return homes;
  }

  public HomeLookup lookup(UUID uuid, String username, String currentServer, String homeName) {
    Objects.requireNonNull(homeName, "homeName");
    List<HomeEntry> matches = listHomes(uuid, username).stream()
        .filter(entry -> entry.name().equalsIgnoreCase(homeName))
        .toList();

    if (matches.isEmpty()) {
      return new HomeLookup(LookupStatus.MISSING, null, List.of());
    }
    if (matches.size() == 1) {
      return new HomeLookup(LookupStatus.FOUND, matches.get(0), matches);
    }

    if (currentServer != null) {
      for (HomeEntry match : matches) {
        if (match.server().equalsIgnoreCase(currentServer)) {
          return new HomeLookup(LookupStatus.FOUND, match, matches);
        }
      }
    }

    return new HomeLookup(LookupStatus.AMBIGUOUS, null, matches);
  }

  private Path resolveUserFile(Path workingDir, UUID uuid, String username) {
    Path dataDir = workingDir.resolve(config.essentialsUserdataPath).normalize();
    if (!Files.isDirectory(dataDir)) {
      return null;
    }

    List<String> fileNames = new ArrayList<>();
    fileNames.add(uuid.toString() + ".yml");
    if (username != null && !username.isBlank()) {
      fileNames.add(username + ".yml");
      fileNames.add(username.toLowerCase(Locale.ROOT) + ".yml");
    }

    for (String fileName : fileNames) {
      Path candidate = dataDir.resolve(fileName);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }

    try (var files = Files.list(dataDir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> {
            String fileName = path.getFileName().toString();
            for (String candidate : fileNames) {
              if (fileName.equalsIgnoreCase(candidate)) {
                return true;
              }
            }
            return false;
          })
          .findFirst()
          .orElse(null);
    } catch (Exception ex) {
      logger.warn("Failed to search Essentials userdata directory {}", dataDir.toAbsolutePath(), ex);
      return null;
    }
  }

  private List<HomeEntry> readHomes(String server, Path userFile) {
    try (InputStream input = Files.newInputStream(userFile)) {
      Object loaded = yaml.load(input);
      if (!(loaded instanceof Map<?, ?> root)) {
        return List.of();
      }
      Object homesObj = root.get("homes");
      if (!(homesObj instanceof Map<?, ?> homesMap)) {
        return List.of();
      }

      List<HomeEntry> homes = new ArrayList<>();
      for (Object key : homesMap.keySet()) {
        if (key instanceof String homeName && !homeName.isBlank()) {
          homes.add(new HomeEntry(server, homeName));
        }
      }
      return homes;
    } catch (Exception ex) {
      logger.warn("Failed to read Essentials homes from {}", userFile.toAbsolutePath(), ex);
      return List.of();
    }
  }
}
