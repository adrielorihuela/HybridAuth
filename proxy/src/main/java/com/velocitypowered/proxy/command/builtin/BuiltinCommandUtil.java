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
  static final String PERMISSION_PREFIX = "hybridauth.command.";

  /**
   * Prefixes these nodes have used before, still honoured as fallbacks.
   *
   * <p>A rename that silently revokes access an operator has already granted is a rename that
   * breaks their server, and they would have no way to connect the two events. So every prefix
   * this project has used keeps working: {@code hybridvelocity.} from the first rename and
   * {@code velocity.} from upstream. They are literals on purpose — a name that existed in the
   * past must never be renamed along with the current one, or the fallback stops finding
   * anything. They can be dropped in a future major version, once nobody is
   * running a permissions setup that predates them.</p>
   */
  static final String[] LEGACY_PERMISSION_PREFIXES = {
      "hybridvelocity.command.", "velocity.command."};

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
    for (final String legacy : LEGACY_PERMISSION_PREFIXES) {
      final Tristate value = source.getPermissionValue(legacy + suffix);
      if (value != Tristate.UNDEFINED) {
        return value == Tristate.TRUE;
      }
    }
    return false;
  }

  /**
   * Whether a command everyone should have is allowed — denied only if a permission says so.
   *
   * <p>Used by {@code /server} and the per-server shortcut commands. <strong>Any</strong> node
   * saying no is enough to deny: an operator who wrote {@code -velocity.command.server.lobby}
   * before a rename still expects it to block, and treating the nodes as a plain OR would silently
   * stop honouring it.</p>
   *
   * @param source the command source
   * @param suffix the node after the prefix, for example {@code "server.lobby"}
   * @return whether the source may run the command
   */
  static boolean isNotDenied(final CommandSource source, final String suffix) {
    if (source.getPermissionValue(PERMISSION_PREFIX + suffix) == Tristate.FALSE) {
      return false;
    }
    for (final String legacy : LEGACY_PERMISSION_PREFIXES) {
      if (source.getPermissionValue(legacy + suffix) == Tristate.FALSE) {
        return false;
      }
    }
    return true;
  }
}
