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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks Hangar what the current Velocity release of a plugin is.
 *
 * <p>The query pins two things down. {@code platform=VELOCITY} is obvious. {@code channel=Release}
 * is not, and matters: without it Hangar answers with whatever is newest, which for an actively
 * developed plugin is a snapshot — ViaVersion answers {@code 5.12.0-SNAPSHOT+1053} unfiltered and
 * {@code 5.11.0} filtered. A proxy that quietly updates itself onto development builds is not what
 * anybody asked for.</p>
 */
final class HangarClient {

  private static final String API = "https://hangar.papermc.io/api/v1/projects/";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient httpClient;

  HangarClient(final HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  /**
   * Looks up the latest Velocity release of a plugin.
   *
   * @param slug the plugin's name on Hangar; case does not matter
   * @return the release to install
   * @throws PluginUpdateException if Hangar cannot be reached, does not know the plugin, or
   *     publishes no Velocity release of it
   */
  HangarRelease latestRelease(final String slug) throws PluginUpdateException {
    final String url = API + URLEncoder.encode(slug, StandardCharsets.UTF_8)
        + "/versions?limit=1&platform=VELOCITY&channel=Release";

    final HttpResponse<String> response;
    try {
      response = httpClient.send(
          HttpRequest.newBuilder(URI.create(url))
              .header("User-Agent", "HybridVelocity")
              .timeout(TIMEOUT)
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new PluginUpdateException("could not reach Hangar (" + e.getMessage() + ")", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PluginUpdateException("interrupted while asking Hangar about it", e);
    }

    if (response.statusCode() == 404) {
      throw new PluginUpdateException("Hangar has no project by that name. Check the spelling "
          + "against the project's page on hangar.papermc.io.");
    }
    if (response.statusCode() != 200) {
      throw new PluginUpdateException("Hangar answered " + response.statusCode() + ".");
    }

    return parse(response.body());
  }

  /** Split out from the request so the shape of a real answer can be tested without the network. */
  static HangarRelease parse(final String body) throws PluginUpdateException {
    final JsonObject root;
    try {
      root = JsonParser.parseString(body).getAsJsonObject();
    } catch (RuntimeException e) {
      throw new PluginUpdateException("Hangar's answer was not the JSON object expected.", e);
    }

    final JsonElement result = root.get("result");
    if (result == null || !result.isJsonArray() || result.getAsJsonArray().isEmpty()) {
      // The project exists - a missing one is a 404 - so this is a plugin that publishes nothing
      // for Velocity, or nothing on the Release channel.
      throw new PluginUpdateException("Hangar knows the project but publishes no Velocity release "
          + "of it. It may be a Paper-only plugin, or have only snapshot builds.");
    }

    final JsonObject version = result.getAsJsonArray().get(0).getAsJsonObject();
    final JsonElement downloads = version.get("downloads");
    if (downloads == null || !downloads.isJsonObject()
        || !downloads.getAsJsonObject().has("VELOCITY")) {
      throw new PluginUpdateException("Hangar's latest release has no Velocity download.");
    }
    final JsonObject velocity = downloads.getAsJsonObject().getAsJsonObject("VELOCITY");

    final JsonElement fileInfo = velocity.get("fileInfo");
    if (fileInfo == null || !fileInfo.isJsonObject()) {
      throw new PluginUpdateException("Hangar's latest release describes no file.");
    }
    final JsonObject file = fileInfo.getAsJsonObject();

    final String downloadUrl = string(velocity, "downloadUrl") != null
        ? string(velocity, "downloadUrl")
        : string(velocity, "externalUrl");
    final String fileName = string(file, "name");
    final String sha256 = string(file, "sha256Hash");
    final String versionName = string(version, "name");

    if (downloadUrl == null || fileName == null || sha256 == null) {
      throw new PluginUpdateException("Hangar's answer was missing the download URL, the file "
          + "name or the checksum.");
    }
    return new HangarRelease(versionName == null ? "unknown" : versionName,
        fileName, sha256, downloadUrl);
  }

  private static String string(final JsonObject object, final String member) {
    final JsonElement element = object.get(member);
    return element == null || element.isJsonNull() ? null : element.getAsString();
  }
}
