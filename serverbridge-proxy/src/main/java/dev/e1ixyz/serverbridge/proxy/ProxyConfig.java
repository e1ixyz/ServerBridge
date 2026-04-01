package dev.e1ixyz.serverbridge.proxy;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProxyConfig {
  public boolean globalChat = true;
  public boolean privateMessages = true;
  public boolean teleports = true;
  public boolean homes = true;
  public String essentialsUserdataPath = "plugins/Essentials/userdata";
  public int teleportRequestTimeoutSeconds = 120;
  public ServerManagerCompatibility serverManagerCompatibility = new ServerManagerCompatibility();

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
      if (config.essentialsUserdataPath == null || config.essentialsUserdataPath.isBlank()) {
        config.essentialsUserdataPath = "plugins/Essentials/userdata";
      }
      if (config.teleportRequestTimeoutSeconds <= 0) {
        config.teleportRequestTimeoutSeconds = 120;
      }
      if (config.serverManagerCompatibility == null) {
        config.serverManagerCompatibility = new ServerManagerCompatibility();
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
        serverManagerCompatibility:
          enabled: true
          requireEnabledFlag: true
        """;
  }
}
