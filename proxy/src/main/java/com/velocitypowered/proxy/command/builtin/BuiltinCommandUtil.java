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
   * Whether a privileged command is allowed — granted only if a permission says so explicitly.
   *
   * <p>Used by the commands only an administrator should have. See {@link #resolve} for which node
   * decides.</p>
   *
   * @param source the command source
   * @param suffix the node after the prefix, for example {@code "reload"}
   * @return whether the source may run the command
   */
  static boolean isAllowed(final CommandSource source, final String suffix) {
    return resolve(source, suffix) == Tristate.TRUE;
  }

  /**
   * Whether a command everyone should have is allowed — denied only if a permission says so.
   *
   * <p>Used by {@code /server} and the per-server shortcut commands. See {@link #resolve} for
   * which node decides: a grant on the current node therefore wins over a deny on a legacy one,
   * which is what an operator who set {@code hybridauth.command.server.lobby true} expects, and a
   * legacy deny still blocks as long as the current node is left unset.</p>
   *
   * @param source the command source
   * @param suffix the node after the prefix, for example {@code "server.lobby"}
   * @return whether the source may run the command
   */
  static boolean isNotDenied(final CommandSource source, final String suffix) {
    return resolve(source, suffix) != Tristate.FALSE;
  }

  /**
   * The value of the first node that is set, current prefix first, then each legacy prefix.
   *
   * <p>Both helpers share this, so the two kinds of command can never disagree about which node
   * decides. Only the default differs: {@link Tristate#UNDEFINED} allows a command everyone has
   * and refuses a privileged one.</p>
   *
   * <p>Earlier versions let <em>any</em> node saying no deny a default-allowed command. With
   * LuckPerms that made the current node impossible to use on its own: a default group, a
   * {@code velocity.command.*} wildcard or a leftover {@code velocity.command.server false}
   * resolved the legacy node to false, and the operator's grant on the current node was ignored
   * without a trace.</p>
   *
   * @param source the command source
   * @param suffix the node after the prefix
   * @return the deciding value, or {@link Tristate#UNDEFINED} if no node is set
   */
  private static Tristate resolve(final CommandSource source, final String suffix) {
    final Tristate current = source.getPermissionValue(PERMISSION_PREFIX + suffix);
    if (current != Tristate.UNDEFINED) {
      return current;
    }
    for (final String legacy : LEGACY_PERMISSION_PREFIXES) {
      final Tristate value = source.getPermissionValue(legacy + suffix);
      if (value != Tristate.UNDEFINED) {
        return value;
      }
    }
    return Tristate.UNDEFINED;
  }
}
