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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

/**
 * Plugins from <a href="https://hangar.papermc.io">Hangar</a>.
 *
 * <p>The query pins two things down. {@code platform=VELOCITY} is obvious. {@code channel=Release}
 * is not, and matters: without it Hangar answers with whatever is newest, which for an actively
 * developed plugin is a snapshot — ViaVersion answers {@code 5.12.0-SNAPSHOT+1053} unfiltered and
 * {@code 5.11.0} filtered. A proxy that quietly updates itself onto development builds is not what
 * anybody asked for.</p>
 */
final class HangarSource implements PluginSource {

  private static final String API = "https://hangar.papermc.io/api/v1/projects/";

  private final JsonApi api;

  HangarSource(final HttpClient httpClient) {
    this.api = new JsonApi(httpClient);
  }

  @Override
  public String name() {
    return "hangar";
  }

  @Override
  public PluginRelease resolve(final String slug) throws PluginUpdateException {
    final String url = API + URLEncoder.encode(slug, StandardCharsets.UTF_8)
        + "/versions?limit=1&platform=VELOCITY&channel=Release";
    final JsonElement body;
    try {
      body = api.get(url);
    } catch (JsonApi.NotFoundException e) {
      throw new PluginUpdateException("Hangar has no project by that name. Check the spelling "
          + "against the project's page on hangar.papermc.io.");
    }
    return parse(slug, body);
  }

  /** Split out from the request so the shape of a real answer can be tested without the network. */
  static PluginRelease parse(final String slug, final JsonElement body)
      throws PluginUpdateException {
    if (!body.isJsonObject()) {
      throw new PluginUpdateException("Hangar's answer was not the JSON object expected.");
    }
    final JsonElement result = body.getAsJsonObject().get("result");
    if (result == null || !result.isJsonArray() || result.getAsJsonArray().isEmpty()) {
      // The project exists — a missing one is a 404 — so this is a plugin that publishes nothing
      // for Velocity, or nothing on the Release channel.
      throw new PluginUpdateException("Hangar knows the project but publishes no Velocity release "
          + "of it. It may be a Paper-only plugin, or have only snapshot builds.");
    }

    final JsonArray versions = result.getAsJsonArray();
    final JsonObject version = versions.get(0).getAsJsonObject();
    final JsonObject velocity =
        JsonApi.object(JsonApi.object(version, "downloads"), "VELOCITY");
    if (velocity == null) {
      throw new PluginUpdateException("Hangar's latest release has no Velocity download.");
    }

    final JsonObject file = JsonApi.object(velocity, "fileInfo");
    final String downloadUrl = JsonApi.string(velocity, "downloadUrl");
    if (file == null || downloadUrl == null) {
      // Hangar lets a project list a link to somewhere else instead of hosting the file. Geyser
      // and Floodgate are published that way: no file name, no version to speak of and no
      // checksum, so there is nothing here to install or to compare against.
      throw new PluginUpdateException("this project does not host its file on Hangar, it only "
          + "links elsewhere, so there is no version or checksum to work from. If this is Geyser "
          + "or Floodgate, list it under 'geyser' instead of 'hangar'.");
    }

    final String fileName = JsonApi.string(file, "name");
    final String sha256 = JsonApi.string(file, "sha256Hash");
    if (fileName == null) {
      throw new PluginUpdateException("Hangar's answer was missing the file name.");
    }
    final String versionName = JsonApi.string(version, "name");

    return new PluginRelease("hangar", slug, versionName == null ? "unknown" : versionName,
        fileName,
        sha256 == null ? null : new PluginRelease.Checksum("SHA-256", sha256),
        downloadUrl);
  }
}
