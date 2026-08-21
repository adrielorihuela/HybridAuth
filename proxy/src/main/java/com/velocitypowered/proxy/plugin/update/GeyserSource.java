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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

/**
 * Geyser and Floodgate, from GeyserMC's own download service.
 *
 * <p>They are listed on Hangar too, but only as a link somewhere else: no file name, no checksum,
 * and a version named literally "Geyser". This API gives all three, which is why they get a source
 * of their own rather than going through Hangar.</p>
 *
 * <p>{@code versions/latest/builds/latest} answers with a redirect to the real build, so the HTTP
 * client has to be one that follows redirects — without that there is no JSON to parse at all.</p>
 */
final class GeyserSource implements PluginSource {

  private static final String API = "https://download.geysermc.org/v2/projects/";

  private final JsonApi api;

  GeyserSource(final HttpClient httpClient) {
    this.api = new JsonApi(httpClient);
  }

  @Override
  public String name() {
    return "geyser";
  }

  @Override
  public PluginRelease resolve(final String project) throws PluginUpdateException {
    final String url = API + URLEncoder.encode(project, StandardCharsets.UTF_8)
        + "/versions/latest/builds/latest";
    final JsonElement body;
    try {
      body = api.get(url);
    } catch (JsonApi.NotFoundException e) {
      throw new PluginUpdateException("GeyserMC publishes no project called " + project
          + ". The ones that exist are geyser and floodgate.");
    }
    return parse(project, body, url);
  }

  /** Split out from the request so a real answer can be tested without the network. */
  static PluginRelease parse(final String project, final JsonElement body, final String buildUrl)
      throws PluginUpdateException {
    if (!body.isJsonObject()) {
      throw new PluginUpdateException("GeyserMC answered with something other than the JSON object "
          + "expected. This API redirects, so a client that does not follow redirects lands here.");
    }
    final JsonObject build = body.getAsJsonObject();

    final JsonObject velocity = JsonApi.object(JsonApi.object(build, "downloads"), "velocity");
    if (velocity == null) {
      throw new PluginUpdateException("the latest GeyserMC build of " + project + " has no "
          + "Velocity download.");
    }
    final String fileName = JsonApi.string(velocity, "name");
    final String sha256 = JsonApi.string(velocity, "sha256");
    if (fileName == null) {
      throw new PluginUpdateException("GeyserMC left out the file name.");
    }

    final String version = JsonApi.string(build, "version");
    final JsonElement buildNumber = build.get("build");
    final String fullVersion;
    if (version == null) {
      fullVersion = "unknown";
    } else if (buildNumber == null || buildNumber.isJsonNull()) {
      fullVersion = version;
    } else {
      fullVersion = version + "-" + buildNumber.getAsString();
    }

    return new PluginRelease("geyser", project, fullVersion, fileName,
        sha256 == null ? null : new PluginRelease.Checksum("SHA-256", sha256),
        buildUrl + "/downloads/velocity");
  }
}
