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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The `comandos` list is validated against `[servers]`, and getting that comparison wrong stops the
 * proxy from starting at all — `validate()` returning false is fatal.
 */
class ServerCommandsValidationTest {

  @TempDir
  Path directory;

  private VelocityConfiguration configurationWith(final String servers) throws IOException {
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
        %s

        [forced-hosts]
        """.formatted(secret.toString().replace("\\", "\\\\"), servers), StandardCharsets.UTF_8);

    return VelocityConfiguration.read(config);
  }

  @Test
  void serverCommandDifferingOnlyInCaseIsAccepted() throws IOException {
    // Server names are case-insensitive everywhere else — ServerMap keys them lowercased — so
    // comparing exactly here meant the shipped default of comandos = ["lobby"] refused to start
    // for anyone whose server was named `Lobby`.
    assertTrue(configurationWith("""
        Lobby = "127.0.0.1:30066"
        try = ["Lobby"]
        comandos = ["lobby"]
        """).validate());
  }

  @Test
  void exactlyMatchingServerCommandIsAccepted() throws IOException {
    assertTrue(configurationWith("""
        lobby = "127.0.0.1:30066"
        try = ["lobby"]
        comandos = ["lobby"]
        """).validate());
  }

  @Test
  void serverCommandWithNoServerAtAllIsRejected() throws IOException {
    assertFalse(configurationWith("""
        lobby = "127.0.0.1:30066"
        try = ["lobby"]
        comandos = ["survival"]
        """).validate());
  }
}
