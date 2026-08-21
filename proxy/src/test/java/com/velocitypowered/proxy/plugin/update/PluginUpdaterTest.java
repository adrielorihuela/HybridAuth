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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which jars in plugins/ belong to which plugin. */
class PluginUpdaterTest {

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
