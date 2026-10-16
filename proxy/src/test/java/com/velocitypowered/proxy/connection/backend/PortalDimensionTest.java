/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.config.VelocityConfiguration.PortalsConfig.Dimension;
import com.velocitypowered.proxy.connection.registry.DimensionInfo;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.StateRegistry.PacketRegistry.ProtocolRegistry;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

class PortalDimensionTest {

  private static Dimension dimensionOf(final RespawnPacket packet) {
    return BackendPlaySessionHandler.portalDimension(packet);
  }

  private static RespawnPacket respawn(final int dimension, final DimensionInfo info) {
    return new RespawnPacket(dimension, 0, (short) 0, (short) 0, "", (byte) 0, info, (short) 0,
        CompoundBinaryTag.empty(), null, 0, 0);
  }

  private static RespawnPacket modern(final String levelName) {
    return respawn(0, new DimensionInfo("", levelName, false, false,
        ProtocolVersion.MINECRAFT_1_21_4));
  }

  @Test
  void vanillaNetherAndEndKeysAreRecognised() {
    assertEquals(Dimension.NETHER, dimensionOf(modern("minecraft:the_nether")));
    assertEquals(Dimension.END, dimensionOf(modern("minecraft:the_end")));
  }

  @Test
  void pluginWorldsNamedAfterTheirDimensionAreRecognised() {
    assertEquals(Dimension.NETHER, dimensionOf(modern("minecraft:lobby_nether")));
    assertEquals(Dimension.END, dimensionOf(modern("minecraft:Lobby_The_End")));
  }

  @Test
  void overworldDoesNotRedirect() {
    assertNull(dimensionOf(modern("minecraft:overworld")));
    assertNull(dimensionOf(modern("minecraft:world")));
  }

  @Test
  void worldKeyInRegistrySlotIsUsedWhenThereIsNoLevelName() {
    // 1.16.2 to 1.18.2 send the world key where other versions send the dimension type.
    assertEquals(Dimension.NETHER, dimensionOf(respawn(0, new DimensionInfo(
        "minecraft:the_nether", null, false, false, ProtocolVersion.MINECRAFT_1_17))));
  }

  @Test
  void numericDimensionsBefore116AreRecognised() {
    assertEquals(Dimension.NETHER, dimensionOf(respawn(-1, null)));
    assertEquals(Dimension.END, dimensionOf(respawn(1, null)));
    assertNull(dimensionOf(respawn(0, null)));
  }

  private static ProtocolRegistry play(final ProtocolVersion version) {
    return StateRegistry.PLAY.getProtocolRegistry(ProtocolUtils.Direction.CLIENTBOUND, version);
  }

  /** The packet as a backend sends it and handleUnknown receives it: id first, then the body. */
  private static ByteBuf onTheWire(final RespawnPacket packet, final ProtocolVersion version) {
    final ByteBuf buf = Unpooled.buffer();
    ProtocolUtils.writeVarInt(buf, play(version).getPacketId(packet));
    packet.encode(buf, ProtocolUtils.Direction.CLIENTBOUND, version);
    return buf;
  }

  private static RespawnPacket modernAt(final String levelName, final ProtocolVersion version) {
    return respawn(0, new DimensionInfo("", levelName, false, false, version));
  }

  @Test
  void backendRespawnIsNotDecodedSoItMustBeReadRaw() {
    // Why portals are caught in handleUnknown rather than handle(RespawnPacket): the decoder
    // forwards this packet without building it. If this ever fails, upstream started decoding
    // it, and handle(RespawnPacket) has become the path that runs.
    for (final ProtocolVersion version : new ProtocolVersion[] {
        ProtocolVersion.MINECRAFT_1_8, ProtocolVersion.MINECRAFT_1_17,
        ProtocolVersion.MINECRAFT_1_21_4, ProtocolVersion.MAXIMUM_VERSION}) {
      final int id = play(version).getPacketId(new RespawnPacket());
      assertNull(play(version).createPacket(id), version.toString());
    }
  }

  @Test
  void endRespawnIsReadFromTheRawPacket() {
    for (final ProtocolVersion version : new ProtocolVersion[] {
        ProtocolVersion.MINECRAFT_1_21_4, ProtocolVersion.MAXIMUM_VERSION}) {
      final ByteBuf buf = onTheWire(modernAt("minecraft:the_end", version), version);
      assertEquals(Dimension.END, BackendPlaySessionHandler.portalDimension(buf, version),
          version.toString());
    }
  }

  @Test
  void readingTheRawPacketLeavesItUntouched() {
    final ProtocolVersion version = ProtocolVersion.MAXIMUM_VERSION;
    final ByteBuf buf = onTheWire(modernAt("minecraft:the_nether", version), version);
    final int readable = buf.readableBytes();
    assertEquals(Dimension.NETHER, BackendPlaySessionHandler.portalDimension(buf, version));
    assertEquals(0, buf.readerIndex());
    assertEquals(readable, buf.readableBytes());
  }

  @Test
  void olderVersionsAreReadFromTheRawPacket() {
    final ProtocolVersion v117 = ProtocolVersion.MINECRAFT_1_17;
    assertEquals(Dimension.NETHER, BackendPlaySessionHandler.portalDimension(onTheWire(
        respawn(0, new DimensionInfo("minecraft:the_nether", null, false, false, v117)), v117),
        v117));
    final ProtocolVersion v18 = ProtocolVersion.MINECRAFT_1_8;
    assertEquals(Dimension.END,
        BackendPlaySessionHandler.portalDimension(onTheWire(respawn(1, null), v18), v18));
  }

  @Test
  void overworldAndOtherPacketsAreIgnored() {
    final ProtocolVersion version = ProtocolVersion.MAXIMUM_VERSION;
    assertNull(BackendPlaySessionHandler.portalDimension(
        onTheWire(modernAt("minecraft:overworld", version), version), version));
    final ByteBuf other = Unpooled.buffer();
    ProtocolUtils.writeVarInt(other, play(version).getPacketId(new RespawnPacket()) + 1);
    other.writeBytes(new byte[] {1, 2, 3});
    assertNull(BackendPlaySessionHandler.portalDimension(other, version));
  }

  @Test
  void truncatedRespawnIsIgnoredRatherThanThrown() {
    final ProtocolVersion version = ProtocolVersion.MAXIMUM_VERSION;
    final ByteBuf truncated = Unpooled.buffer();
    ProtocolUtils.writeVarInt(truncated, play(version).getPacketId(new RespawnPacket()));
    truncated.writeByte(5);
    assertNull(BackendPlaySessionHandler.portalDimension(truncated, version));
  }
}
