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

package com.velocitypowered.proxy.command.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Who may use {@code /server} and a per-server shortcut, as a permissions plugin such as LuckPerms
 * would answer for each node.
 *
 * <p>The rule under test: the current {@code hybridauth.command.} node decides whenever it is set,
 * the legacy {@code velocity.command.} node decides when it is not, and the command is allowed when
 * neither is set. An operator who grants the current node must get the command even if something
 * else — a default group, a wildcard — happens to deny the legacy one.</p>
 */
class ServerPermissionTest {

  private static final String CURRENT = "hybridauth.command.";
  private static final String LEGACY = "velocity.command.";

  /** {@code /server}, which checks {@code <prefix>server}. */
  private static CommandNode<CommandSource> serverCommand() {
    return ServerCommand.create(mock(ProxyServer.class)).getNode();
  }

  /** {@code /Lobby}, which checks {@code <prefix>server.lobby}. */
  private static CommandNode<CommandSource> shortcutCommand() {
    return ServerShortcutCommand.create(mock(ProxyServer.class), "Lobby").getNode();
  }

  private static Player player(final String suffix, final Tristate current,
      final Tristate legacy) {
    final Map<String, Tristate> nodes = new HashMap<>();
    nodes.put(CURRENT + suffix, current);
    nodes.put(LEGACY + suffix, legacy);
    final Player player = mock(Player.class);
    when(player.getPermissionValue(anyString())).thenAnswer(invocation ->
        nodes.getOrDefault(invocation.<String>getArgument(0), Tristate.UNDEFINED));
    return player;
  }

  private static void assertAccess(final boolean expected, final Tristate current,
      final Tristate legacy) {
    final String description = "hybridauth=" + current + ", velocity=" + legacy;
    assertEquals(expected,
        serverCommand().canUse(player("server", current, legacy)), "/server, " + description);
    assertEquals(expected,
        shortcutCommand().canUse(player("server.lobby", current, legacy)),
        "/Lobby, " + description);
  }

  @Test
  void onlyCurrentNodeGranted() {
    assertAccess(true, Tristate.TRUE, Tristate.UNDEFINED);
  }

  @Test
  void onlyCurrentNodeDenied() {
    assertAccess(false, Tristate.FALSE, Tristate.UNDEFINED);
  }

  @Test
  void onlyLegacyNodeGranted() {
    assertAccess(true, Tristate.UNDEFINED, Tristate.TRUE);
  }

  @Test
  void onlyLegacyNodeDenied() {
    assertAccess(false, Tristate.UNDEFINED, Tristate.FALSE);
  }

  @Test
  void bothNodesGranted() {
    assertAccess(true, Tristate.TRUE, Tristate.TRUE);
  }

  @Test
  void currentGrantWinsOverLegacyDeny() {
    // The reported bug: a LuckPerms grant on hybridauth.command.* was ignored whenever anything
    // resolved velocity.command.* to false for the same player.
    assertAccess(true, Tristate.TRUE, Tristate.FALSE);
  }

  @Test
  void currentDenyWinsOverLegacyGrant() {
    assertAccess(false, Tristate.FALSE, Tristate.TRUE);
  }

  @Test
  void neitherNodeSetAllowsByDefault() {
    assertAccess(true, Tristate.UNDEFINED, Tristate.UNDEFINED);
  }

  @Test
  void consoleNeverGetsThePlayerCommands() {
    final CommandSource console = mock(CommandSource.class);
    when(console.getPermissionValue(anyString())).thenReturn(Tristate.TRUE);
    assertEquals(false, serverCommand().canUse(console));
    assertEquals(false, shortcutCommand().canUse(console));
  }
}
