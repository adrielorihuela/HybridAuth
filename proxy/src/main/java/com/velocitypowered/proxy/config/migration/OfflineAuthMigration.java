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

package com.velocitypowered.proxy.config.migration;

import static com.velocitypowered.proxy.config.VelocityConfiguration.OfflineAuthConfig.DEFAULT;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Configuration migration for the offline authentication options, added by HybridAuth.
 *
 * <p>These options have moved twice — an {@code [offline-auth]} section, then three top-level
 * keys, and now back to the section with shorter names. Whatever the operator had chosen is carried
 * across from whichever shape their file is in; only genuinely absent keys get a default.</p>
 */
public final class OfflineAuthMigration implements ConfigurationMigration {

  /** The key this fork counts its own migrations with. See {@link #shouldMigrate}. */
  public static final String FORK_VERSION_KEY = "hybridauth-config-version";

  /** What this fork's migrations are up to. */
  private static final double FORK_VERSION = 1.0;

  /**
   * The upstream number this fork's configurations were really at when it wrote its own.
   *
   * <p>Used to undo a mistake: this migration used to write {@code config-version = "3.2"}, which
   * is upstream's key and upstream's numbering. Upstream is at 2.8, so 3.2 sat on four numbers
   * that are not ours to spend — and every upstream migration is gated on {@code <} a number, so
   * a Velocity release adding one at 2.9 would have found 3.2 already there and skipped it
   * silently, on every HybridAuth installation at once.</p>
   */
  private static final String UPSTREAM_VERSION = "2.8";

  @Override
  public boolean shouldMigrate(CommentedFileConfig config) {
    // Counted on our own key. config-version belongs to upstream and is left to upstream.
    return config.getOptional(FORK_VERSION_KEY)
        .map(value -> Double.parseDouble(value.toString()) < FORK_VERSION)
        .orElse(true);
  }

  @Override
  public void migrate(CommentedFileConfig config, Logger logger) {
    // Carry over whatever this config already had, under any of the names these options have been
    // through. Each read is type-checked: `offline-auth` was a section before it was a boolean and
    // is a section again now, so an untyped get would hand back a sub-config here.
    final Boolean enabled = firstOf(config, Boolean.class, "offline-auth.enabled", "offline-auth");
    final Integer port = firstOf(config, Integer.class,
        "offline-auth.server-port", "auth-server-port", "offline-auth.limbo-port");
    final String name = firstOf(config, String.class,
        "offline-auth.server-name", "auth-server-name");

    config.remove("offline-auth");
    config.remove("auth-server-name");
    config.remove("auth-server-port");

    config.set("offline-auth.enabled", enabled != null ? enabled : DEFAULT.enabled());
    config.set("offline-auth.server-name", name != null ? name : DEFAULT.serverName());
    config.set("offline-auth.server-port", port != null ? port : DEFAULT.serverPort());

    config.setComment("offline-auth.enabled", """
        Should players that Mojang could not authenticate be asked to register a password?
        They wait on an authentication server that runs inside this proxy - there is nothing extra
        to install - and only reach your real servers once they register or log in.
        Premium players are unaffected.
        Passwords are stored in auth/player-passwords.db. Do not delete that file: it is the only
        copy, and losing it lets anyone claim a name by registering it first.""");

    config.setComment("offline-auth.server-name", """
        The name the authentication server is registered under. It is hidden from /server, /glist,
        /send and tab completion, but plugins can still see it - ViaVersion needs to, to learn its
        protocol version. It must not match any server in [servers].""");

    config.setComment("offline-auth.server-port", """
        Port the authentication server listens on, bound to 127.0.0.1 only, so it is unreachable
        from outside this machine. It needs a port of its own: two listeners cannot share one, and
        a server with no socket could not be pinged by ViaVersion.
        Set 0 to let the system pick a free port on each start.""");

    // Hand upstream's key back, if this installation is carrying a number we should never have
    // written into it. Only while our own key is absent, so this cannot walk a legitimately newer
    // configuration backwards.
    //
    // The fork wrote 2.9, 3.0 and 3.2 at various points, and upstream has since reached 2.9 for
    // real (PingPassthroughMigration), so the number alone no longer tells the two apart. What does
    // is the shape: upstream's 2.9 turns `ping-passthrough` into a table, and the fork never did.
    // Walking a real 2.9 back would make that migration run again on its own output.
    final double upstreamVersion = configVersion(config);
    if (upstreamVersion > 2.8 && upstreamVersion <= 3.2
        && !(config.get("ping-passthrough") instanceof UnmodifiableConfig)) {
      config.set("config-version", UPSTREAM_VERSION);
    }

    config.set(FORK_VERSION_KEY, String.valueOf(FORK_VERSION));
    config.setComment(FORK_VERSION_KEY, """
        Which of this fork's own configuration migrations have run. Do not change it.
        Kept apart from config-version, which is upstream Velocity's and counts upstream's
        migrations - a fork writing its own numbers into that key makes later upstream migrations
        think they have already run.""");
  }

  /**
   * Returns the first of these paths holding a value of the expected type, or {@code null}.
   *
   * @param config the configuration to read
   * @param type the type the value must have
   * @param paths the paths to try, in order
   * @param <T> the value type
   * @return the first matching value, or {@code null} if none matched
   */
  private static <T> @Nullable T firstOf(
      final CommentedFileConfig config, final Class<T> type, final String... paths) {
    for (final String path : paths) {
      final Object value = config.get(path);
      if (type.isInstance(value)) {
        return type.cast(value);
      }
    }
    return null;
  }
}
