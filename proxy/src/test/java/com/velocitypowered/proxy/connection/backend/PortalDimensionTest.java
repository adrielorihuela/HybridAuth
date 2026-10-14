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
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import org.junit.jupiter.api.Test;

class PortalDimensionTest {

  private static Dimension dimensionOf(final RespawnPacket packet) {
    return BackendPlaySessionHandler.portalDimension(packet);
  }

  private static RespawnPacket respawn(final int dimension, final DimensionInfo info) {
    return new RespawnPacket(dimension, 0, (short) 0, (short) 0, "", (byte) 0, info, (short) 0,
        null, null, 0, 0);
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
}
