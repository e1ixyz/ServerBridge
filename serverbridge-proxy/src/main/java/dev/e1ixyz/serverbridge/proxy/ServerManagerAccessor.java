package dev.e1ixyz.serverbridge.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ServerManagerAccessor {
  private final ProxyServer proxy;
  private final Path bridgeDataDir;
  private final Logger logger;

  public ServerManagerAccessor(ProxyServer proxy, Path bridgeDataDir, Logger logger) {
    this.proxy = proxy;
    this.bridgeDataDir = bridgeDataDir;
    this.logger = logger;
  }

  public Map<String, Path> resolveManagedServerWorkingDirectories() {
    Map<String, Path> viaPlugin = resolveViaPluginApi();
    if (!viaPlugin.isEmpty()) {
      return viaPlugin;
    }
    return resolveViaConfigFile();
  }

  public boolean isCompatibilityEnabled() {
    return resolveServerManagerInstance()
        .map(this::readCompatibilityEnabled)
        .orElse(false);
  }

  public CompletableFuture<Boolean> ensureServerReady(String server) {
    return resolveServerManagerInstance()
        .map(instance -> invokeBooleanFuture(instance, "ensureServerReady",
            new Class<?>[]{String.class}, server))
        .orElse(null);
  }

  public CompletableFuture<Boolean> connectPlayerWhenReady(Player player, String server, Runnable afterConnect) {
    return resolveServerManagerInstance()
        .map(instance -> invokeBooleanFuture(instance, "connectPlayerWhenReady",
            new Class<?>[]{Player.class, String.class, Runnable.class}, player, server, afterConnect))
        .orElse(null);
  }

  private Map<String, Path> resolveViaPluginApi() {
    return resolveServerManagerInstance()
        .map(this::resolveViaInstance)
        .orElseGet(Collections::emptyMap);
  }

  private Optional<Object> resolveServerManagerInstance() {
    return proxy.getPluginManager().getPlugin("servermanager")
        .flatMap(container -> container.getInstance());
  }

  private Map<String, Path> resolveViaInstance(Object instance) {
    try {
      Method method = instance.getClass().getMethod("resolveManagedServerWorkingDirectories");
      Object result = method.invoke(instance);
      if (!(result instanceof Map<?, ?> map)) {
        return Collections.emptyMap();
      }

      Map<String, Path> resolved = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key) || key.isBlank() || entry.getValue() == null) {
          continue;
        }
        Path path = coercePath(entry.getValue());
        if (path != null) {
          resolved.put(key, path);
        }
      }
      return resolved;
    } catch (ReflectiveOperationException ex) {
      logger.debug("ServerManager plugin API not available yet, falling back to config parsing.", ex);
      return Collections.emptyMap();
    }
  }

  private Map<String, Path> resolveViaConfigFile() {
    Path serverManagerConfig = bridgeDataDir.getParent().resolve("servermanager").resolve("config.yml");
    if (!Files.exists(serverManagerConfig)) {
      logger.warn("ServerManager config not found at {}", serverManagerConfig.toAbsolutePath());
      return Collections.emptyMap();
    }

    Yaml yaml = new Yaml();
    try (InputStream input = Files.newInputStream(serverManagerConfig)) {
      Object loaded = yaml.load(input);
      if (!(loaded instanceof Map<?, ?> root)) {
        return Collections.emptyMap();
      }
      Object serversObj = root.get("servers");
      if (!(serversObj instanceof Map<?, ?> servers)) {
        return Collections.emptyMap();
      }

      Map<String, Path> resolved = new LinkedHashMap<>();
      Path proxyRoot = resolveProxyRoot();
      for (Map.Entry<?, ?> entry : servers.entrySet()) {
        if (!(entry.getKey() instanceof String serverName) || !(entry.getValue() instanceof Map<?, ?> serverCfg)) {
          continue;
        }
        Object workingDirValue = serverCfg.get("workingDir");
        if (!(workingDirValue instanceof String workingDirString) || workingDirString.isBlank()) {
          continue;
        }
        Path workingDir = Path.of(workingDirString);
        if (!workingDir.isAbsolute()) {
          workingDir = proxyRoot.resolve(workingDir).normalize();
        } else {
          workingDir = workingDir.normalize();
        }
        resolved.put(serverName, workingDir);
      }
      return resolved;
    } catch (Exception ex) {
      logger.error("Failed to parse ServerManager config from {}", serverManagerConfig.toAbsolutePath(), ex);
      return Collections.emptyMap();
    }
  }

  private Path coercePath(Object value) {
    if (value instanceof Path path) {
      return path.toAbsolutePath().normalize();
    }
    if (value instanceof String stringValue && !stringValue.isBlank()) {
      Path path = Path.of(stringValue);
      if (!path.isAbsolute()) {
        path = resolveProxyRoot().resolve(path).normalize();
      } else {
        path = path.normalize();
      }
      return path;
    }
    return null;
  }

  private boolean readCompatibilityEnabled(Object instance) {
    try {
      Method method = instance.getClass().getMethod("isServerBridgeCompatibilityEnabled");
      Object result = method.invoke(instance);
      return result instanceof Boolean enabled && enabled;
    } catch (ReflectiveOperationException ex) {
      logger.debug("ServerManager compatibility flag API not available.", ex);
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  private CompletableFuture<Boolean> invokeBooleanFuture(Object instance, String methodName,
                                                         Class<?>[] parameterTypes, Object... args) {
    try {
      Method method = instance.getClass().getMethod(methodName, parameterTypes);
      Object result = method.invoke(instance, args);
      if (result instanceof CompletableFuture<?> future) {
        return (CompletableFuture<Boolean>) future;
      }
      return CompletableFuture.completedFuture(Boolean.FALSE);
    } catch (ReflectiveOperationException ex) {
      logger.debug("ServerManager method {} not available.", methodName, ex);
      return null;
    }
  }

  private Path resolveProxyRoot() {
    Path pluginsDir = bridgeDataDir.getParent();
    if (pluginsDir != null && pluginsDir.getParent() != null) {
      return pluginsDir.getParent().toAbsolutePath().normalize();
    }
    return Path.of("").toAbsolutePath().normalize();
  }
}
