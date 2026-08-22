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

package com.velocitypowered.proxy.plugin.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which jars in plugins/ belong to which plugin, and what they get called. */
class PluginUpdaterTest {

  private static PluginRelease release(final String version, final String fileName) {
    return new PluginRelease("test", "id", version, fileName, null, "https://example.invalid");
  }

  @Test
  void versionedNameIsLeftAlone() {
    assertEquals("ViaVersion-5.11.0.jar",
        release("5.11.0", "ViaVersion-5.11.0.jar").installedFileName());
  }

  @Test
  void nameWithoutVersionGetsOne() {
    // Every build of Geyser is published as the same file name. Nothing breaks without this --
    // freshness comes from the checksum -- but a directory listing that cannot say which build is
    // installed is worse than one that can.
    assertEquals("Geyser-Velocity-2.11.2-1230.jar",
        release("2.11.2-1230", "Geyser-Velocity.jar").installedFileName());
    assertEquals("floodgate-velocity-2.2.5-140.jar",
        release("2.2.5-140", "floodgate-velocity.jar").installedFileName());
  }

  @Test
  void nameThatSaysItsVersionDifferentlyIsLeftAlone() {
    // The reason the test is "has a digit" rather than "contains the version": Hangar's build
    // 5.12.0-SNAPSHOT+1053 ships this file, and appending would produce nonsense.
    assertEquals("ViaVersion-5.12.0-SNAPSHOT.jar",
        release("5.12.0-SNAPSHOT+1053", "ViaVersion-5.12.0-SNAPSHOT.jar").installedFileName());
  }

  @Test
  void charactersNotAllowedInFileNamesAreReplaced() {
    assertEquals("Thing-1.0-build-7.jar",
        release("1.0+build/7", "Thing.jar").installedFileName());
  }

  @Test
  void prunerStillRecognisesTheRenamedJar() {
    // The rename would be a bug if the old Geyser-Velocity.jar survived alongside the new one.
    final PluginRelease geyser = release("2.11.2-1230", "Geyser-Velocity.jar");

    assertTrue(PluginUpdater.isJarOf(geyser.baseName(), "Geyser-Velocity.jar"));
    assertTrue(PluginUpdater.isJarOf(geyser.baseName(), geyser.installedFileName()));
  }

  @Test
  void versionedJarBelongsToItsPlugin() {
    assertTrue(PluginUpdater.isJarOf("ViaVersion", "ViaVersion-5.11.0.jar"));
    assertTrue(PluginUpdater.isJarOf("ViaVersion", "ViaVersion-5.12.0-SNAPSHOT.jar"));
  }

  @Test
  void unversionedJarBelongsToItToo() {
    // Somebody who downloaded it by hand and dropped in ViaVersion.jar. Left behind, it loads
    // alongside the new one and the duplicate plugin id stops the proxy.
    assertTrue(PluginUpdater.isJarOf("ViaVersion", "ViaVersion.jar"));
  }

  @Test
  void caseDoesNotMatter() {
    assertTrue(PluginUpdater.isJarOf("ViaVersion", "viaversion-5.11.0.JAR"));
    assertTrue(PluginUpdater.isJarOf("viaversion", "ViaVersion-5.11.0.jar"));
  }

  @Test
  void differentPluginWithTheSamePrefixIsNotClaimed() {
    // The dash is the whole reason this is not startsWith: deleting somebody's ViaVersionAddon
    // while updating ViaVersion would be a nasty surprise.
    assertFalse(PluginUpdater.isJarOf("ViaVersion", "ViaVersionAddon-1.0.jar"));
    assertFalse(PluginUpdater.isJarOf("Via", "ViaVersion-5.11.0.jar"));
  }

  @Test
  void nonJarsAreIgnored() {
    assertFalse(PluginUpdater.isJarOf("ViaVersion", "ViaVersion-5.11.0.jar.part"));
    assertFalse(PluginUpdater.isJarOf("ViaVersion", "ViaVersion"));
    assertFalse(PluginUpdater.isJarOf("ViaVersion", "ViaVersion-5.11.0.zip"));
  }
}
