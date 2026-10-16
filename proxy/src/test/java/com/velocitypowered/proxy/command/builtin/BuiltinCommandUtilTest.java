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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Permission nodes across the renames.
 *
 * <p>This is the part of a rename that goes wrong quietly. Nobody gets an error: a command simply
 * stops working, or starts working for somebody it was taken from, and there is nothing to connect
 * either to the version they upgraded.</p>
 */
class BuiltinCommandUtilTest {

  private static final String NODE = "reload";
  private static final String SERVER = "server.lobby";

  private static CommandSource sourceWith(final Map<String, Tristate> permissions) {
    final CommandSource source = mock(CommandSource.class);
    when(source.getPermissionValue(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation ->
            permissions.getOrDefault(invocation.getArgument(0), Tristate.UNDEFINED));
    return source;
  }

  @Test
  void currentNodeGrants() {
    assertTrue(BuiltinCommandUtil.isAllowed(
        sourceWith(Map.of("hybridauth.command." + NODE, Tristate.TRUE)), NODE));
  }

  @Test
  void upstreamNodeStillGrants() {
    // Somebody who granted these on stock Velocity must not have to grant them again.
    assertTrue(BuiltinCommandUtil.isAllowed(
        sourceWith(Map.of("velocity.command." + NODE, Tristate.TRUE)), NODE));
  }

  @Test
  void legacyPrefixIsTheUpstreamLiteral() {
    // Out of date on purpose: a search and replace of a project rename must never touch it, or
    // every permission set up for stock Velocity silently stops counting.
    assertArrayEquals(new String[]{"velocity.command."},
        BuiltinCommandUtil.LEGACY_PERMISSION_PREFIXES);
  }

  @Test
  void droppedForkPrefixNoLongerCounts() {
    // hybridvelocity.command.* was dropped in 1.3.1; the release notes say to move to
    // hybridauth.command.*.
    assertFalse(BuiltinCommandUtil.isAllowed(
        sourceWith(Map.of("hybridvelocity.command." + NODE, Tristate.TRUE)), NODE));
    assertTrue(BuiltinCommandUtil.isNotDenied(
        sourceWith(Map.of("hybridvelocity.command." + SERVER, Tristate.FALSE)), SERVER));
  }

  @Test
  void nothingGrantedMeansNotAllowed() {
    assertFalse(BuiltinCommandUtil.isAllowed(sourceWith(Map.of()), NODE));
  }

  @Test
  void explicitDenyOnTheCurrentNodeWinsOverAnOlderGrant() {
    assertFalse(BuiltinCommandUtil.isAllowed(sourceWith(Map.of(
        "hybridauth.command." + NODE, Tristate.FALSE,
        "velocity.command." + NODE, Tristate.TRUE)), NODE));
  }

  @Test
  void defaultAllowCommandsAreAllowedWithNoPermissionsAtAll() {
    assertTrue(BuiltinCommandUtil.isNotDenied(sourceWith(Map.of()), SERVER));
  }

  @Test
  void eitherNodeSayingNoDeniesWhenTheCurrentOneIsUnset() {
    // An operator who wrote velocity.command.server.lobby false on stock Velocity expects it to
    // keep blocking, as long as they have not set the current node since.
    for (final String prefix : new String[]{"hybridauth.command.", "velocity.command."}) {
      assertFalse(BuiltinCommandUtil.isNotDenied(
          sourceWith(Map.of(prefix + SERVER, Tristate.FALSE)), SERVER), prefix);
    }
  }

  @Test
  void grantOnTheCurrentNodeWinsOverDenyOnAnOlderOne() {
    // The LuckPerms report: hybridauth.command.server.lobby true did nothing while anything still
    // resolved velocity.command.server.lobby to false.
    assertTrue(BuiltinCommandUtil.isNotDenied(sourceWith(Map.of(
        "hybridauth.command." + SERVER, Tristate.TRUE,
        "velocity.command." + SERVER, Tristate.FALSE)), SERVER));
  }

  @Test
  void bothHelpersAgreeOnWhichNodeDecides() {
    for (final Tristate current : Tristate.values()) {
      for (final Tristate legacy : Tristate.values()) {
        final CommandSource source = sourceWith(Map.of(
            "hybridauth.command." + NODE, current, "velocity.command." + NODE, legacy));
        final Tristate deciding = current != Tristate.UNDEFINED ? current : legacy;
        final String description = current + "/" + legacy;
        assertEquals(deciding == Tristate.TRUE,
            BuiltinCommandUtil.isAllowed(source, NODE), description);
        assertEquals(deciding != Tristate.FALSE,
            BuiltinCommandUtil.isNotDenied(source, NODE), description);
      }
    }
  }
}
