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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.config.VelocityConfiguration.PortalsConfig;
import com.velocitypowered.proxy.config.VelocityConfiguration.PortalsConfig.Dimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalsConfigTest {

  @TempDir
  Path directory;

  private VelocityConfiguration configurationWith(final String portals) throws IOException {
    final Path secret = directory.resolve("forwarding.secret");
    Files.writeString(secret, "test-secret", StandardCharsets.UTF_8);

    final Path config = directory.resolve("hybridauth.toml");
    Files.writeString(config, """
        config-version = "3.2"
        bind = "0.0.0.0:25565"
        online-mode = true
        forwarding-secret-file = "%s"

        [offline-auth]
        enabled = false

        [servers]
        Lobby = "127.0.0.1:30066"
        survival = "127.0.0.1:30067"
        try = ["Lobby"]
        comandos = []

        [forced-hosts]

        %s
        """.formatted(secret.toString().replace("\\", "\\\\"), portals), StandardCharsets.UTF_8);

    return VelocityConfiguration.read(config);
  }

  @Test
  void missingSectionRedirectsNothing() {
    assertEquals(Optional.empty(),
        PortalsConfig.fromConfig(null).target("lobby", Dimension.NETHER));
  }

  @Test
  void portalTargetsAreReadPerDimensionIgnoringOriginCase() throws IOException {
    final VelocityConfiguration configuration = configurationWith("""
        [portales]
        Lobby = { nether = "survival" }
        """);
    assertTrue(configuration.validate());
    final PortalsConfig portals = configuration.getPortalsConfig();
    assertEquals(Optional.of("survival"), portals.target("lobby", Dimension.NETHER));
    assertEquals(Optional.of("survival"), portals.target("LOBBY", Dimension.NETHER));
    assertEquals(Optional.empty(), portals.target("lobby", Dimension.END));
    assertEquals(Optional.empty(), portals.target("survival", Dimension.NETHER));
  }

  @Test
  void portalToUnknownServerFailsValidation() throws IOException {
    assertFalse(configurationWith("""
        [portales]
        lobby = { end = "skyblock" }
        """).validate());
  }

  @Test
  void portalFromUnknownServerFailsValidation() throws IOException {
    assertFalse(configurationWith("""
        [portales]
        skyblock = { end = "survival" }
        """).validate());
  }

  @Test
  void portalToItselfFailsValidation() throws IOException {
    assertFalse(configurationWith("""
        [portales]
        lobby = { nether = "Lobby" }
        """).validate());
  }
}
