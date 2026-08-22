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

import com.velocitypowered.proxy.plugin.update.PluginRelease.Checksum;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which jars in plugins/ belong to which plugin. */
class PluginUpdaterTest {

  @TempDir
  Path dir;

  private PluginRelease release(final Checksum checksum) {
    return new PluginRelease("test", "id", "1.0", "Thing-1.0.jar", checksum,
        "https://example.invalid");
  }

  /** The digest of "installed", so a test can state an expectation rather than compute one. */
  private static final String INSTALLED_SHA256 =
      "8b4b6b2a0b9d0f16b1f1c4b9bbf0c8a9e0f1b6ee39ba1d4e5b7b7c7e0ea3a1a5";

  @Test
  void matchingChecksumMeansUpToDate() throws IOException {
    final Path jar = dir.resolve("Thing-1.0.jar");
    Files.writeString(jar, "installed");
    final String actual = digest(jar);

    assertTrue(new PluginUpdater(dir, null)
        .isCurrent("Thing", jar, release(new Checksum("SHA-256", actual))));
  }

  @Test
  void differentChecksumMeansOutOfDateEvenThoughTheNameMatches() throws IOException {
    // The whole point. The file is sitting there under exactly the name the source published, and
    // that counts for nothing: a rebuild under the same name, or a corrupted file, is caught here
    // and nowhere else.
    final Path jar = dir.resolve("Thing-1.0.jar");
    Files.writeString(jar, "something else entirely");

    assertFalse(new PluginUpdater(dir, null)
        .isCurrent("Thing", jar, release(new Checksum("SHA-256", INSTALLED_SHA256))));
  }

  @Test
  void caseOfTheHexDigestDoesNotMatter() throws IOException {
    final Path jar = dir.resolve("Thing-1.0.jar");
    Files.writeString(jar, "installed");

    assertTrue(new PluginUpdater(dir, null).isCurrent("Thing", jar,
        release(new Checksum("SHA-256", digest(jar).toUpperCase(java.util.Locale.ROOT)))));
  }

  @Test
  void sourceWithoutChecksumFallsBackToTheName() throws IOException {
    // LuckPerms, and only LuckPerms among the sources here. Kept because there is no alternative,
    // not because the name is good enough.
    final Path jar = dir.resolve("Thing-1.0.jar");
    Files.writeString(jar, "anything at all");

    assertTrue(new PluginUpdater(dir, null).isCurrent("Thing", jar, release(null)));
  }

  private static String digest(final Path path) throws IOException {
    try {
      final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of().formatHex(md.digest(Files.readAllBytes(path)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
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
