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

package com.velocitypowered.proxy.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.UuidUtils;
import com.velocitypowered.proxy.VelocityServer;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Who the gate holds, and who it must let straight through. */
class OfflineAuthGateTest {

  @Test
  void offlinePlayerIsHeldUntilTheyAuthenticate() {
    final OfflineAuthGate gate = gate();
    final Player player = player(false, UuidUtils.generateOfflinePlayerUuid("Steve"));

    assertTrue(gate.isSubjectToAuth(player));
    assertTrue(gate.isGated(player));
  }

  @Test
  void premiumPlayerIsNeverAsked() {
    final OfflineAuthGate gate = gate();
    final Player player = player(true, UUID.randomUUID());

    assertFalse(gate.isSubjectToAuth(player));
    assertFalse(gate.isGated(player));
  }

  @Test
  void bedrockPlayerIsNeverAsked() {
    // They reach the proxy in offline mode, because Floodgate forces it in PreLoginEvent — which
    // is exactly why they used to end up on the authentication server. Geyser and Floodgate have
    // already verified them against Xbox Live.
    final OfflineAuthGate gate = gate();
    final Player player = player(false, new UUID(0L, 2535413324011341L));

    assertFalse(gate.isSubjectToAuth(player));
    assertFalse(gate.isGated(player));
  }

  private static OfflineAuthGate gate() {
    final VelocityServer server = mock(VelocityServer.class);
    final PluginManager plugins = mock(PluginManager.class);
    when(plugins.getPlugin("floodgate")).thenReturn(Optional.empty());
    when(server.getPluginManager()).thenReturn(plugins);
    final OfflineAuthManager manager = mock(OfflineAuthManager.class);
    return new OfflineAuthGate(server, manager);
  }

  private static Player player(final boolean onlineMode, final UUID uuid) {
    final Player player = mock(Player.class);
    when(player.isOnlineMode()).thenReturn(onlineMode);
    when(player.getUniqueId()).thenReturn(uuid);
    return player;
  }
}
