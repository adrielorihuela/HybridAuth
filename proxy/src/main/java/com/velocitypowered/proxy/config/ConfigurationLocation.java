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

package com.velocitypowered.proxy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Where this fork keeps its configuration: a {@code config/} directory next to the jar.
 *
 * <p>The file inside it is named after the fork rather than after upstream Velocity. An
 * installation that still has upstream's {@code velocity.toml}, or this fork's earlier
 * {@code hybridvelocity.toml} in the working directory, is moved on first start — without that,
 * updating the jar would silently start the proxy on stock defaults and lose every configured
 * server, the forwarding mode and the MOTD.</p>
 *
 * <p>The same applies to {@code forwarding.secret}, and there it matters more: a proxy that cannot
 * find its secret generates a new one and carries on, and the only symptom is that every backend
 * using modern forwarding starts rejecting players. So the secret is <em>moved</em> and never
 * regenerated while an old one still exists.</p>
 *
 * <p>Every method takes the directory to work from. Production passes the working directory; tests
 * pass a temporary one, which is the only way to exercise migrations that move real files.</p>
 */
public final class ConfigurationLocation {

  private static final Logger logger = LogManager.getLogger(ConfigurationLocation.class);

  /** The directory holding the configuration. */
  public static final String DIRECTORY = "config";

  /** The configuration file this fork reads. */
  public static final String FILE_NAME = "hybridvelocity.toml";

  /** The name upstream Velocity uses, migrated away from on first start. */
  public static final String LEGACY_FILE_NAME = "velocity.toml";

  /** The default name of the forwarding secret file, inside {@link #DIRECTORY}. */
  public static final String SECRET_FILE_NAME = "forwarding.secret";

  private ConfigurationLocation() {
  }

  /** The directory the proxy was started in, which is what the no-argument overloads work from. */
  private static Path workingDirectory() {
    return Path.of("");
  }

  /**
   * Returns the path to the configuration, relative to the working directory.
   *
   * @return the path to read the configuration from
   */
  public static Path resolve() {
    return resolve(workingDirectory());
  }

  /**
   * Returns the path to the configuration, moving an older one into place if one is found.
   *
   * @param baseDir the directory the proxy is running in
   * @return the path to read the configuration from
   */
  public static Path resolve(final Path baseDir) {
    final Path path = baseDir.resolve(DIRECTORY).resolve(FILE_NAME);
    if (Files.exists(path)) {
      return path;
    }

    // In order: this fork's previous location, then upstream's name. Both are "the configuration
    // this installation has been using", and losing either means starting on stock defaults.
    for (final String legacyName : new String[]{FILE_NAME, LEGACY_FILE_NAME}) {
      final Path legacy = baseDir.resolve(legacyName);
      if (!Files.exists(legacy)) {
        continue;
      }
      if (move(legacy, path)) {
        logger.info("Moved {} to {}{}{}; your configuration was kept as it was.",
            legacyName, DIRECTORY, java.io.File.separator, FILE_NAME);
        return path;
      }
      logger.error("Could not move {} into {}{}. Reading it where it is — move it yourself to "
          + "silence this.", legacyName, DIRECTORY, java.io.File.separator);
      return legacy;
    }

    return path;
  }

  /**
   * Returns the path of the forwarding secret file, relative to the working directory.
   *
   * @param configured the {@code forwarding-secret-file} setting, or {@code null} if unset
   * @return the path the secret should be read from and written to
   */
  public static Path resolveForwardingSecret(final String configured) {
    return resolveForwardingSecret(workingDirectory(), configured);
  }

  /**
   * Returns the path of the forwarding secret file, moving an older one into place if one is found.
   *
   * <p>An absolute setting is honoured untouched: an operator who pointed this somewhere specific
   * meant it. A relative one is resolved inside {@link #DIRECTORY}.</p>
   *
   * @param baseDir the directory the proxy is running in
   * @param configured the {@code forwarding-secret-file} setting, or {@code null} if unset
   * @return the path the secret should be read from and written to
   */
  public static Path resolveForwardingSecret(final Path baseDir, final String configured) {
    final String name = configured == null || configured.isBlank()
        ? SECRET_FILE_NAME : configured;
    final Path asGiven = Path.of(name);
    if (asGiven.isAbsolute()) {
      return asGiven;
    }

    final Path path = baseDir.resolve(DIRECTORY).resolve(asGiven);
    if (Files.exists(path)) {
      return path;
    }

    // Never regenerate over an existing secret. A new random one silently breaks modern forwarding
    // on every backend, and the proxy would report nothing but players being kicked.
    final Path legacy = baseDir.resolve(asGiven);
    if (Files.exists(legacy) && !legacy.equals(path)) {
      if (move(legacy, path)) {
        logger.info("Moved {} into {}{}; the secret itself is unchanged.",
            name, DIRECTORY, java.io.File.separator);
      } else {
        logger.error("Could not move {} into {}{}. Using it where it is, so that the secret your "
            + "backends already know keeps working.", name, DIRECTORY, java.io.File.separator);
        return legacy;
      }
    }
    return path;
  }

  /**
   * Resolves a file named by a setting, relative to {@link #DIRECTORY}.
   *
   * <p>Same rule as the forwarding secret, minus the migration: an absolute setting is used as
   * given, a relative one lives in {@code config/}. Nothing is created — a file that is not there
   * means the setting is unused.</p>
   *
   * @param baseDir the directory the proxy is running in
   * @param configured the setting
   * @return where to look for it
   */
  public static Path resolveConfigFile(final Path baseDir, final String configured) {
    final Path asGiven = Path.of(configured);
    return asGiven.isAbsolute() ? asGiven : baseDir.resolve(DIRECTORY).resolve(asGiven);
  }

  /**
   * Resolves a file named by a setting, relative to the working directory.
   *
   * @param configured the setting
   * @return where to look for it
   */
  public static Path resolveConfigFile(final String configured) {
    return resolveConfigFile(workingDirectory(), configured);
  }

  /** Moves a file, creating the destination directory. Returns whether it worked. */
  private static boolean move(final Path from, final Path to) {
    try {
      final Path parent = to.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicFailed) {
        // Not every filesystem can move atomically, and a move across devices never can.
        Files.move(from, to);
      }
      return true;
    } catch (IOException e) {
      logger.debug("Could not move {} to {}", from, to, e);
      return false;
    }
  }
}
