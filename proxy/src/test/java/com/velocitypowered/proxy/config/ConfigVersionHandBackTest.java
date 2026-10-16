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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fork once wrote its own numbers into upstream's config-version, including 2.9, and upstream
 * has since used 2.9 for real. Handing the key back must undo the first without undoing the second,
 * or upstream's ping-passthrough migration runs again on its own output.
 */
class ConfigVersionHandBackTest {

  @TempDir
  Path directory;

  private CommentedConfig readTwice(final String head) throws IOException {
    final Path secret = directory.resolve("forwarding.secret");
    Files.writeString(secret, "test-secret", StandardCharsets.UTF_8);
    final Path file = directory.resolve("hybridauth.toml");
    Files.writeString(file, """
        %s
        bind = "0.0.0.0:25565"
        forwarding-secret-file = "%s"

        [offline-auth]
        enabled = false

        [servers]
        lobby = "127.0.0.1:30066"
        try = ["lobby"]

        [forced-hosts]
        """.formatted(head, secret.toString().replace("\\", "\\\\")), StandardCharsets.UTF_8);

    // A second start is where walking the version back would have broken: upstream's migration
    // would read its own table as the old string setting.
    VelocityConfiguration.read(file);
    VelocityConfiguration.read(file);

    try (CommentedFileConfig config = CommentedFileConfig.of(file)) {
      config.load();
      return CommentedConfig.copy(config);
    }
  }

  @Test
  void forkWrittenNumberIsHandedBackAndUpstreamMigrationStillRuns() throws IOException {
    final CommentedConfig config = readTwice("""
        config-version = "3.2"
        ping-passthrough = "ALL"
        """);
    assertEquals("2.9", config.get("config-version"));
    assertEquals(Boolean.TRUE, config.get("ping-passthrough.favicon"));
  }

  @Test
  void forkWritten29WithTheOldSettingIsMigrated() throws IOException {
    final CommentedConfig config = readTwice("""
        config-version = "2.9"
        ping-passthrough = "MODS"
        """);
    assertEquals("2.9", config.get("config-version"));
    assertEquals(Boolean.TRUE, config.get("ping-passthrough.modinfo"));
    assertEquals(Boolean.FALSE, config.get("ping-passthrough.favicon"));
  }

  @Test
  void upstreamVelocity29IsLeftAlone() throws IOException {
    final CommentedConfig config = readTwice("""
        config-version = "2.9"

        [ping-passthrough]
        version = true
        players = false
        description = false
        favicon = false
        modinfo = false
        """);
    assertEquals("2.9", config.get("config-version"));
    assertEquals(Boolean.TRUE, config.get("ping-passthrough.version"));
    assertTrue(config.contains("hybridauth-config-version"));
  }
}
