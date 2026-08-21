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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Reading Hangar's answer, including the shapes that mean "no". */
class HangarClientTest {

  @Test
  void realAnswerIsRead() throws Exception {
    final HangarRelease release = HangarClient.parse(resource("viaversion-release.json"));

    assertEquals("5.11.0", release.version());
    assertEquals("ViaVersion-5.11.0.jar", release.fileName());
    assertEquals("89db76c8e3e674238f5eee2bb7a9e9a2beeba0760bbd1b86494778e8a5a52f70",
        release.sha256());
    assertTrue(release.downloadUrl().startsWith("https://"));
  }

  @Test
  void theBaseNameComesFromTheFileName() throws Exception {
    // Not from the version: Hangar's 5.12.0-SNAPSHOT+1053 ships ViaVersion-5.12.0-SNAPSHOT.jar,
    // so assembling the name from the version would not match anything on disk.
    assertEquals("ViaVersion", HangarClient.parse(resource("viaversion-release.json")).baseName());
    assertEquals("ViaVersion",
        new HangarRelease("5.12.0-SNAPSHOT+1053", "ViaVersion-5.12.0-SNAPSHOT.jar", "", "")
            .baseName());
    assertEquals("Plugin", new HangarRelease("1", "Plugin.jar", "", "").baseName());
  }

  @Test
  void projectWithNoVelocityReleaseSaysSo() {
    // What Hangar returns for a project it knows that publishes nothing for Velocity, or nothing
    // on the Release channel. A missing project is a 404 instead, handled before parsing.
    final PluginUpdateException e = assertThrows(PluginUpdateException.class,
        () -> HangarClient.parse("{\"result\":[],\"pagination\":{}}"));

    assertTrue(e.getMessage().contains("no Velocity release"), e.getMessage());
  }

  @Test
  void answerWithoutVelocityDownloadSaysSo() {
    final PluginUpdateException e = assertThrows(PluginUpdateException.class,
        () -> HangarClient.parse(
            "{\"result\":[{\"name\":\"1.0\",\"downloads\":{\"PAPER\":{}}}]}"));

    assertTrue(e.getMessage().contains("no Velocity download"), e.getMessage());
  }

  @Test
  void answerMissingTheChecksumIsRejected() {
    final PluginUpdateException e = assertThrows(PluginUpdateException.class,
        () -> HangarClient.parse("{\"result\":[{\"name\":\"1.0\",\"downloads\":{\"VELOCITY\":"
            + "{\"downloadUrl\":\"https://example.invalid/a.jar\",\"fileInfo\":"
            + "{\"name\":\"a.jar\"}}}}]}"));

    assertTrue(e.getMessage().contains("checksum"), e.getMessage());
  }

  @Test
  void rubbishIsRejectedRatherThanThrowingSomethingUnhelpful() {
    assertThrows(PluginUpdateException.class, () -> HangarClient.parse("not json at all"));
    assertThrows(PluginUpdateException.class, () -> HangarClient.parse("[]"));
  }

  private static String resource(final String name) throws IOException {
    try (InputStream in =
        HangarClientTest.class.getResourceAsStream("/hangar/" + name)) {
      if (in == null) {
        throw new IOException("missing test resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
