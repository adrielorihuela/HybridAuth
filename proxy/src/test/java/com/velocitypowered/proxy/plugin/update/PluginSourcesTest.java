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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Reading each source's answer, from a response really returned by its API.
 *
 * <p>The failures matter as much as the successes here: every one of them is something an operator
 * will hit by typing a name slightly wrong, and the message is the only help they get.</p>
 */
class PluginSourcesTest {

  // ---- Hangar ----

  @Test
  void hangarAnswerIsRead() throws Exception {
    final PluginRelease release =
        HangarSource.parse("ViaVersion", resource("hangar-viaversion.json"));

    assertEquals("hangar", release.source());
    assertEquals("5.11.0", release.version());
    assertEquals("ViaVersion-5.11.0.jar", release.fileName());
    assertEquals("ViaVersion", release.baseName());
    assertNotNull(release.checksum());
    assertEquals("SHA-256", release.checksum().algorithm());
    assertEquals("89db76c8e3e674238f5eee2bb7a9e9a2beeba0760bbd1b86494778e8a5a52f70",
        release.checksum().hex());
    assertTrue(release.downloadUrl().startsWith("https://"));
  }

  @Test
  void hangarExternalLinkSendsYouToTheRightList() throws Exception {
    // Geyser's real Hangar entry: fileInfo null, downloadUrl null, version named "Geyser". There
    // is nothing to install or compare, and the message has to say where to go instead rather
    // than complaining about a missing field.
    final PluginUpdateException e = assertThrows(PluginUpdateException.class,
        () -> HangarSource.parse("geyser", resource("hangar-geyser-external.json")));

    assertTrue(e.getMessage().contains("geyser"), e.getMessage());
    assertTrue(e.getMessage().contains("links elsewhere"), e.getMessage());
  }

  @Test
  void hangarChannelWithNothingInItIsNotFailure() throws Exception {
    // Simple Voice Chat's real answer for channel=Release: empty. It is not an error, it is a cue
    // to try Beta, so it gets its own exception type rather than a message to show anybody.
    assertThrows(HangarSource.EmptyChannelException.class,
        () -> HangarSource.parse("SimpleVoiceChat", resource("hangar-svc-release-empty.json")));
  }

  @Test
  void hangarLabelsBuildThatIsNotRelease() throws Exception {
    final PluginRelease release =
        HangarSource.parse("SimpleVoiceChat", resource("hangar-svc-beta.json"), "Beta");

    // The channel is in the version string so a beta does not read as a stable build in the log.
    assertTrue(release.version().endsWith("(Beta)"), release.version());
    assertEquals("voicechat-velocity-2.5.28.jar", release.fileName());
  }

  @Test
  void hangarReleaseIsNotLabelled() throws Exception {
    assertEquals("5.11.0", HangarSource.parse("ViaVersion",
        resource("hangar-viaversion.json"), "Release").version());
  }

  // ---- Modrinth ----

  @Test
  void modrinthAnswerIsRead() throws Exception {
    final PluginRelease release =
        ModrinthSource.parse("ViaBackwards", resource("modrinth-viabackwards.json"));

    // The captured list is six betas followed by one release. Picking the first entry would give
    // 5.12.0-SNAPSHOT+621, which is exactly what must not happen.
    assertEquals("5.11.0", release.version());
    assertEquals("ViaBackwards-5.11.0.jar", release.fileName());
    assertEquals("SHA-512", release.checksum().algorithm());
    assertTrue(release.downloadUrl().startsWith("https://"));
  }

  @Test
  void modrinthFallsDownToTheNewestBetaWhenThereIsNoRelease() throws Exception {
    // Simple Voice Chat publishes no Velocity release at all: eight alphas and five betas. Beta
    // beats alpha even though the newest build overall is an alpha, because the ladder is by
    // stability first. That means 2.5.28 rather than 2.6.18 -- older, and the safer of the two.
    final PluginRelease release = ModrinthSource.parse("simple-voice-chat", resource(
        "modrinth-svc.json"));

    assertEquals("velocity-2.5.28 (beta)", release.version());
    assertEquals("voicechat-velocity-2.5.28.jar", release.fileName());
  }

  @Test
  void modrinthPrefersRealReleaseOverNewerBeta() throws Exception {
    // The other half of the same rule: ViaBackwards lists six betas before its release, and the
    // release is the one to take.
    assertEquals("5.11.0",
        ModrinthSource.parse("ViaBackwards", resource("modrinth-viabackwards.json")).version());
  }

  @Test
  void modrinthTakesAlphaOnlyWhenNothingElseExists() throws Exception {
    final PluginRelease release = ModrinthSource.parse("x", JsonParser.parseString(
        "[{\"version_type\":\"alpha\",\"version_number\":\"1.0\",\"files\":"
        + "[{\"primary\":true,\"filename\":\"a.jar\",\"url\":\"https://x/a.jar\","
        + "\"hashes\":{\"sha512\":\"ff\"}}]}]"));

    assertEquals("1.0 (alpha)", release.version());
  }

  @Test
  void modrinthProjectWithNoVelocityBuildSaysSo() {
    final PluginUpdateException e = assertThrows(PluginUpdateException.class,
        () -> ModrinthSource.parse("x", JsonParser.parseString("[]")));

    assertTrue(e.getMessage().contains("no Velocity build"), e.getMessage());
  }

  // ---- GitHub ----

  @Test
  void githubAnswerIsRead() throws Exception {
    final PluginRelease release =
        GitHubSource.parse("ViaVersion/ViaRewind", null, resource("github-viarewind.json"));

    assertEquals("github", release.source());
    assertEquals("4.1.3", release.version());
    assertEquals("ViaRewind-4.1.3.jar", release.fileName());
    // The API's asset URL, not the browser one: only this path honours an Authorization header,
    // and the browser path answers 404 for a repository the API serves happily.
    assertTrue(release.downloadUrl().startsWith("https://api.github.com/"), release.downloadUrl());
    assertEquals("SHA-256", release.checksum().algorithm());
    assertEquals("2d5970d22b4711c9ab2800932326c7b08acdace25ed7c6bbb8f6ea81054962b4",
        release.checksum().hex());
  }

  @Test
  void githubSkipsSourcesAndJavadocJars() throws Exception {
    final JsonElement body = JsonParser.parseString("""
        {"tag_name":"v2.0","assets":[
          {"name":"Thing-2.0-sources.jar","url":"https://x/s.jar"},
          {"name":"Thing-2.0-javadoc.jar","url":"https://x/j.jar"},
          {"name":"Thing-2.0.zip","url":"https://x/z.zip"},
          {"name":"Thing-2.0.jar","url":"https://x/t.jar"}]}""");

    final PluginRelease release = GitHubSource.parse("o/Thing", null, body);

    assertEquals("Thing-2.0.jar", release.fileName());
    // A leading v is stripped from the tag, as everybody writes releases that way.
    assertEquals("2.0", release.version());
    // No digest published: the file name is all there is, and that is not pretended otherwise.
    assertNull(release.checksum());
  }

  @Test
  void githubPatternChoosesBetweenJars() throws Exception {
    final JsonElement body = JsonParser.parseString("""
        {"tag_name":"1.0","assets":[
          {"name":"Thing-bukkit-1.0.jar","url":"https://x/b.jar"},
          {"name":"Thing-velocity-1.0.jar","url":"https://x/v.jar"}]}""");

    assertEquals("Thing-velocity-1.0.jar",
        GitHubSource.parse("o/Thing", "velocity", body).fileName());
    assertEquals("Thing-bukkit-1.0.jar",
        GitHubSource.parse("o/Thing", null, body).fileName());
  }

  @Test
  void githubReleaseWithNoJarSaysSo() {
    final PluginUpdateException e = assertThrows(PluginUpdateException.class,
        () -> GitHubSource.parse("o/Thing", null, JsonParser.parseString(
            "{\"tag_name\":\"1.0\",\"assets\":[{\"name\":\"notes.txt\","
            + "\"browser_download_url\":\"https://x/n.txt\"}]}")));

    assertTrue(e.getMessage().contains("no .jar"), e.getMessage());
  }

  @Test
  void githubSendsItsAuthorizationToTheDownloadToo() throws Exception {
    final PluginRelease release = GitHubSource.parse("o/Thing", null,
        JsonParser.parseString("{\"tag_name\":\"1.0\",\"assets\":[{\"name\":\"a.jar\","
            + "\"url\":\"https://api.github.com/x\"}]}"),
        java.util.Map.of("Accept", "application/octet-stream",
            "Authorization", "Bearer secret"));

    assertEquals("Bearer secret", release.downloadHeaders().get("Authorization"));
    assertEquals("application/octet-stream", release.downloadHeaders().get("Accept"));
  }

  @Test
  void githubEntriesMustNameAnOwner() {
    // The most likely mistake, since every other source takes a bare name.
    final PluginUpdateException e =
        assertThrows(PluginUpdateException.class, () -> GitHubSource.repositoryOf("ViaRewind"));

    assertTrue(e.getMessage().contains("owner/repo"), e.getMessage());
    assertThrows(PluginUpdateException.class, () -> GitHubSource.repositoryOf("a/b/c"));
    assertThrows(PluginUpdateException.class, () -> GitHubSource.repositoryOf("/repo"));
  }

  @Test
  void githubEntriesSplitIntoRepositoryAndPattern() throws Exception {
    assertEquals("ViaVersion/ViaRewind", GitHubSource.repositoryOf("ViaVersion/ViaRewind"));
    assertNull(GitHubSource.patternOf("ViaVersion/ViaRewind"));
    assertEquals("o/r", GitHubSource.repositoryOf("o/r:velocity"));
    assertEquals("velocity", GitHubSource.patternOf("o/r:velocity"));
  }

  // ---- GeyserMC ----

  @Test
  void geyserAnswerIsRead() throws Exception {
    final PluginRelease release = GeyserSource.parse(
        "floodgate", resource("geyser-floodgate.json"), "https://example.invalid/build");

    assertEquals("geyser", release.source());
    // Version and build together, as the shell script does: a build number is the only thing that
    // moves between two releases of the same version.
    assertTrue(release.version().matches("\\d+\\.\\d+\\.\\d+-\\d+"), release.version());
    assertEquals("floodgate-velocity.jar", release.fileName());
    assertEquals("SHA-256", release.checksum().algorithm());
    assertEquals("https://example.invalid/build/downloads/velocity", release.downloadUrl());
  }

  @Test
  void geyserWithoutFollowedRedirectsSaysWhatWentWrong() {
    // builds/latest answers 302. A client that does not follow it hands back a body that is not
    // an object, and the message says so rather than leaving somebody guessing.
    final PluginUpdateException e = assertThrows(PluginUpdateException.class,
        () -> GeyserSource.parse("geyser", JsonParser.parseString("\"\""), "https://x"));

    assertTrue(e.getMessage().contains("redirect"), e.getMessage());
  }

  // ---- LuckPerms ----

  @Test
  void luckpermsAnswerIsRead() throws Exception {
    final PluginRelease release = LuckPermsSource.parse(resource("luckperms.json"));

    assertEquals("luckperms", release.source());
    assertTrue(release.fileName().startsWith("LuckPerms-Velocity-"), release.fileName());
    assertTrue(release.fileName().endsWith(".jar"), release.fileName());
    assertEquals("LuckPerms", release.baseName());
    // The one source that publishes no checksum at all. Nothing may invent one.
    assertNull(release.checksum());
  }

  @Test
  void luckpermsWithoutAvelocityBuildSaysSo() {
    final PluginUpdateException e = assertThrows(PluginUpdateException.class,
        () -> LuckPermsSource.parse(JsonParser.parseString(
            "{\"version\":\"5.0\",\"downloads\":{\"bukkit\":\"https://x/a.jar\"}}")));

    assertTrue(e.getMessage().contains("no Velocity build"), e.getMessage());
  }

  private static JsonElement resource(final String name) throws IOException {
    try (InputStream in = PluginSourcesTest.class.getResourceAsStream("/sources/" + name)) {
      if (in == null) {
        throw new IOException("missing test resource " + name);
      }
      return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
