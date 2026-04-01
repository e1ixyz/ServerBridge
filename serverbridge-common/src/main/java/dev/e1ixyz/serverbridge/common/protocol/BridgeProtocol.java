package dev.e1ixyz.serverbridge.common.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class BridgeProtocol {
  public static final String CHANNEL = "serverbridge:main";

  private BridgeProtocol() {
  }

  @FunctionalInterface
  public interface PacketWriter {
    void write(DataOutput out) throws IOException;
  }

  public record DecodedMessage(BridgeMessageType type, DataInputStream in) {
  }

  public static byte[] encode(BridgeMessageType type, PacketWriter writer) throws IOException {
    Objects.requireNonNull(type, "type");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(type.id());
      if (writer != null) {
        writer.write(out);
      }
    }
    return bytes.toByteArray();
  }

  public static DecodedMessage decode(byte[] payload) throws IOException {
    Objects.requireNonNull(payload, "payload");
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
    BridgeMessageType type = BridgeMessageType.byId(in.readUnsignedByte());
    return new DecodedMessage(type, in);
  }

  public static void writeUuid(DataOutput out, UUID uuid) throws IOException {
    out.writeLong(uuid.getMostSignificantBits());
    out.writeLong(uuid.getLeastSignificantBits());
  }

  public static UUID readUuid(DataInput in) throws IOException {
    return new UUID(in.readLong(), in.readLong());
  }

  public static void writeString(DataOutput out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  public static String readString(DataInput in) throws IOException {
    int length = in.readInt();
    if (length < 0) {
      throw new IOException("Negative string length: " + length);
    }
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  public static void writeNullableString(DataOutput out, String value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) {
      writeString(out, value);
    }
  }

  public static String readNullableString(DataInput in) throws IOException {
    return in.readBoolean() ? readString(in) : null;
  }
}
