/*
 * Copyright (C) 2019-2021 Velocity Contributors
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

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.server.InternalServers;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

class BuiltinCommandUtil {

  /** Prefix of this fork's permission nodes. */
  static final String PERMISSION_PREFIX = "hybridvelocity.command.";

  /**
   * Prefix these nodes used before the fork was renamed.
   *
   * <p>Still honoured as a fallback so that renaming did not silently revoke access that operators
   * had already granted in their permissions plugin. It can be dropped in a future major version,
   * once nobody is running a configuration that predates the rename.</p>
   */
  static final String LEGACY_PERMISSION_PREFIX = "velocity.command.";

  private BuiltinCommandUtil() {
    throw new AssertionError();
  }

  static List<RegisteredServer> sortedServerList(ProxyServer proxy) {
    // filter() already hands back a fresh mutable list, so there is nothing to copy.
    List<RegisteredServer> servers = InternalServers.filter(proxy.getAllServers());
    servers.sort(Comparator.comparing(RegisteredServer::getServerInfo));
    return Collections.unmodifiableList(servers);
  }

  /**
   * Whether a privileged command is allowed — granted only if the permission says so explicitly.
   *
   * <p>Used by the commands only an administrator should have. The legacy node is consulted only
   * when the current one is unset, so an explicit deny on the current node always wins.</p>
   *
   * @param source the command source
   * @param suffix the node after the prefix, for example {@code "reload"}
   * @return whether the source may run the command
   */
  static boolean isAllowed(final CommandSource source, final String suffix) {
    final Tristate current = source.getPermissionValue(PERMISSION_PREFIX + suffix);
    if (current != Tristate.UNDEFINED) {
      return current == Tristate.TRUE;
    }
    return source.getPermissionValue(LEGACY_PERMISSION_PREFIX + suffix) == Tristate.TRUE;
  }

  /**
   * Whether a command everyone should have is allowed — denied only if a permission says so.
   *
   * <p>Used by {@code /server} and the per-server shortcut commands. <strong>Either</strong> node
   * saying no is enough to deny: an operator who wrote {@code -velocity.command.server.lobby} before
   * the rename still expects it to block, and treating the two nodes as a plain OR would silently
   * stop honouring it.</p>
   *
   * @param source the command source
   * @param suffix the node after the prefix, for example {@code "server.lobby"}
   * @return whether the source may run the command
   */
  static boolean isNotDenied(final CommandSource source, final String suffix) {
    return source.getPermissionValue(PERMISSION_PREFIX + suffix) != Tristate.FALSE
        && source.getPermissionValue(LEGACY_PERMISSION_PREFIX + suffix) != Tristate.FALSE;
  }
}
