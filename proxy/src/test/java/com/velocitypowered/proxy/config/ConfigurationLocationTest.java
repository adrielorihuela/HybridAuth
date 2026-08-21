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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Moving an existing installation into config/ without losing anything. */
class ConfigurationLocationTest {

  @TempDir
  Path dir;

  @Test
  void configurationIsReadFromTheConfigDirectory() {
    assertEquals(dir.resolve("config").resolve("hybridvelocity.toml"),
        ConfigurationLocation.resolve(dir));
  }

  @Test
  void upstreamsFileIsMovedIn() throws IOException {
    Files.writeString(dir.resolve("velocity.toml"), "motd = \"mine\"");

    final Path resolved = ConfigurationLocation.resolve(dir);

    assertEquals(dir.resolve("config").resolve("hybridvelocity.toml"), resolved);
    assertEquals("motd = \"mine\"", Files.readString(resolved));
    assertFalse(Files.exists(dir.resolve("velocity.toml")));
  }

  @Test
  void aForkConfigurationLeftInTheRootIsMovedIn() throws IOException {
    Files.writeString(dir.resolve("hybridvelocity.toml"), "motd = \"mine\"");

    final Path resolved = ConfigurationLocation.resolve(dir);

    assertEquals(dir.resolve("config").resolve("hybridvelocity.toml"), resolved);
    assertEquals("motd = \"mine\"", Files.readString(resolved));
    assertFalse(Files.exists(dir.resolve("hybridvelocity.toml")));
  }

  @Test
  void theForkFileWinsOverUpstreams() throws IOException {
    Files.writeString(dir.resolve("hybridvelocity.toml"), "motd = \"fork\"");
    Files.writeString(dir.resolve("velocity.toml"), "motd = \"upstream\"");

    assertEquals("motd = \"fork\"", Files.readString(ConfigurationLocation.resolve(dir)));
  }

  @Test
  void anAlreadyMigratedInstallationIsLeftAlone() throws IOException {
    final Path existing = dir.resolve("config").resolve("hybridvelocity.toml");
    Files.createDirectories(existing.getParent());
    Files.writeString(existing, "motd = \"current\"");
    Files.writeString(dir.resolve("velocity.toml"), "motd = \"stale\"");

    assertEquals("motd = \"current\"", Files.readString(ConfigurationLocation.resolve(dir)));
    // The stale one is not consulted, and not deleted either - that is the operator's to do.
    assertTrue(Files.exists(dir.resolve("velocity.toml")));
  }

  @Test
  void theSecretIsMovedAndNotRegenerated() throws IOException {
    // The one that matters most. A regenerated secret is not an error anywhere: the proxy starts
    // happily and every backend using modern forwarding starts rejecting players instead.
    Files.writeString(dir.resolve("forwarding.secret"), "the-secret-my-backends-know");

    final Path resolved = ConfigurationLocation.resolveForwardingSecret(dir, null);

    assertEquals(dir.resolve("config").resolve("forwarding.secret"), resolved);
    assertEquals("the-secret-my-backends-know", Files.readString(resolved));
    assertFalse(Files.exists(dir.resolve("forwarding.secret")));
  }

  @Test
  void anExistingSecretInConfigIsUsedAsIs() throws IOException {
    final Path existing = dir.resolve("config").resolve("forwarding.secret");
    Files.createDirectories(existing.getParent());
    Files.writeString(existing, "current");
    Files.writeString(dir.resolve("forwarding.secret"), "stale");

    assertEquals("current",
        Files.readString(ConfigurationLocation.resolveForwardingSecret(dir, null)));
  }

  @Test
  void aRelativeSettingIsResolvedInsideConfig() {
    assertEquals(dir.resolve("config").resolve("secrets").resolve("mine.txt"),
        ConfigurationLocation.resolveForwardingSecret(dir, "secrets/mine.txt"));
  }

  @Test
  void anAbsoluteSettingIsHonouredUntouched() {
    // Somebody who pointed this at a specific place meant it - a mounted secret, say.
    final Path absolute = dir.resolve("elsewhere").resolve("forwarding.secret").toAbsolutePath();

    assertEquals(absolute,
        ConfigurationLocation.resolveForwardingSecret(dir, absolute.toString()));
  }

  @Test
  void nothingIsCreatedWhenThereIsNothingToMove() {
    final Path resolved = ConfigurationLocation.resolveForwardingSecret(dir, null);

    assertEquals(dir.resolve("config").resolve("forwarding.secret"), resolved);
    assertFalse(Files.exists(resolved));
  }
}
