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
import java.net.http.HttpClient;

/**
 * LuckPerms, from its own metadata service.
 *
 * <p>A switch rather than a list, because there is exactly one LuckPerms and on a proxy the
 * platform is always {@code velocity}.</p>
 *
 * <p>It is the one source here that publishes <em>no</em> checksum, so a download cannot be
 * verified and "is it current?" falls back to whether the expected file name is already on disk.
 * The version is in that name, so that is a real answer rather than a guess.</p>
 */
final class LuckPermsSource implements PluginSource {

  private static final String METADATA = "https://metadata.luckperms.net/data/all";

  private final JsonApi api;

  LuckPermsSource(final HttpClient httpClient) {
    this.api = new JsonApi(httpClient);
  }

  @Override
  public String name() {
    return "luckperms";
  }

  @Override
  public PluginRelease resolve(final String ignored) throws PluginUpdateException {
    final JsonElement body;
    try {
      body = api.get(METADATA);
    } catch (JsonApi.NotFoundException e) {
      throw new PluginUpdateException("metadata.luckperms.net did not answer with its build list.");
    }
    return parse(body);
  }

  /** Split out from the request so a real answer can be tested without the network. */
  static PluginRelease parse(final JsonElement body) throws PluginUpdateException {
    if (!body.isJsonObject()) {
      throw new PluginUpdateException("LuckPerms answered with something other than the JSON "
          + "object expected.");
    }
    final JsonObject metadata = body.getAsJsonObject();

    final String version = JsonApi.string(metadata, "version");
    final String url = JsonApi.string(JsonApi.object(metadata, "downloads"), "velocity");
    if (version == null || url == null) {
      throw new PluginUpdateException("LuckPerms publishes no Velocity build right now.");
    }

    // The URL ends in the file name: .../1664/velocity/LuckPerms-Velocity-5.5.77.jar
    final int slash = url.lastIndexOf('/');
    final String fileName = slash == -1 || slash == url.length() - 1
        ? "LuckPerms-Velocity-" + version + ".jar"
        : url.substring(slash + 1);

    return new PluginRelease("luckperms", "LuckPerms", version, fileName, null, url);
  }
}
