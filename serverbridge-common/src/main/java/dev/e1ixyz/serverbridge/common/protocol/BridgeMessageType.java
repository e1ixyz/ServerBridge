package dev.e1ixyz.serverbridge.common.protocol;

import java.util.HashMap;
import java.util.Map;

public enum BridgeMessageType {
  CHAT_BROADCAST(1),
  PRIVATE_MESSAGE(2),
  PRIVATE_REPLY(3),
  TPA_REQUEST(4),
  TPAHERE_REQUEST(5),
  TP_ACCEPT(6),
  TP_DENY(7),
  TP_DIRECT(8),
  TPHERE_DIRECT(9),
  HOME(10),
  HOMES(11),
  TPA_ALL(12),
  TP_CANCEL(13),
  PLAYER_STATE_SYNC(14),
  MSG_TOGGLE(15),
  IGNORE_PLAYER(16),
  STASH_OPEN(17),
  STASH_CLOSE(18),
  STASH_DEPOSIT(19),
  STASH_WITHDRAW(20),
  STASH_LOGS(21),
  STASH_RESET(22),
  STASH_TOGGLE(23),
  EXECUTE_PLAYER_COMMAND(101),
  TELEPORT_TO_PLAYER(102),
  NETWORK_PLAYER_SNAPSHOT(103),
  STASH_SYNC(104);

  private static final Map<Integer, BridgeMessageType> BY_ID = new HashMap<>();

  static {
    for (BridgeMessageType type : values()) {
      BY_ID.put(type.id, type);
    }
  }

  private final int id;

  BridgeMessageType(int id) {
    this.id = id;
  }

  public int id() {
    return id;
  }

  public static BridgeMessageType byId(int id) {
    BridgeMessageType type = BY_ID.get(id);
    if (type == null) {
      throw new IllegalArgumentException("Unknown bridge message id: " + id);
    }
    return type;
  }
}
