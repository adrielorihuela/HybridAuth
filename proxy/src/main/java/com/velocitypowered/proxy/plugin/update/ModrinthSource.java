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
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Plugins from <a href="https://modrinth.com">Modrinth</a>.
 *
 * <p>Filtered to the {@code velocity} loader and nothing else. Modrinth can also filter by
 * Minecraft version, which is the right thing for a backend server and the wrong thing here: the
 * proxy has no single game version, and Velocity plugins are not published pinned to one.</p>
 *
 * <p>Modrinth publishes SHA-512 and SHA-1 for each file, but not SHA-256, which is why a checksum
 * carries its algorithm around rather than being assumed.</p>
 */
final class ModrinthSource implements PluginSource {

  private static final String API = "https://api.modrinth.com/v2/project/";
  private static final String VELOCITY_LOADER = "?loaders=%5B%22velocity%22%5D";

  /**
   * Modrinth's release types, most stable first.
   *
   * <p>Taken in order, and the newest build of the first type that has any is the one installed.
   * Some plugins never publish a release — Simple Voice Chat's Velocity builds are all betas and
   * alphas — and refusing those outright would mean the proxy could not install a plugin that
   * exists and works.</p>
   */
  private static final String[] TYPES_BY_STABILITY = {"release", "beta", "alpha"};

  private final JsonApi api;

  ModrinthSource(final HttpClient httpClient) {
    this.api = new JsonApi(httpClient);
  }

  @Override
  public String name() {
    return "modrinth";
  }

  @Override
  public PluginRelease resolve(final String slug) throws PluginUpdateException {
    final String url = API + URLEncoder.encode(slug, StandardCharsets.UTF_8)
        + "/version" + VELOCITY_LOADER;
    final JsonElement body;
    try {
      body = api.get(url);
    } catch (JsonApi.NotFoundException e) {
      throw new PluginUpdateException("Modrinth has no project by that name. The name is the one "
          + "in the project URL, which is not always the same as its title.");
    }
    return parse(slug, body);
  }

  /** Split out from the request so a real answer can be tested without the network. */
  static PluginRelease parse(final String slug, final JsonElement body)
      throws PluginUpdateException {
    if (!body.isJsonArray()) {
      throw new PluginUpdateException("Modrinth answered with something other than the JSON array "
          + "expected.");
    }
    final JsonArray versions = body.getAsJsonArray();
    if (versions.isEmpty()) {
      throw new PluginUpdateException("Modrinth knows the project but publishes no Velocity build "
          + "of it.");
    }

    // Newest-first but with the types mixed together, so the stable one is rarely the first
    // entry: ViaBackwards lists six betas before the release the proxy should install.
    JsonObject version = null;
    String type = null;
    for (final String candidate : TYPES_BY_STABILITY) {
      version = newestOfType(versions, candidate);
      if (version != null) {
        type = candidate;
        break;
      }
    }
    if (version == null) {
      throw new PluginUpdateException("Modrinth has Velocity builds of this project but none of a "
          + "type this understands.");
    }

    final JsonElement files = version.get("files");
    if (files == null || !files.isJsonArray() || files.getAsJsonArray().isEmpty()) {
      throw new PluginUpdateException("the latest Velocity build on Modrinth has no files.");
    }

    final JsonObject file = primary(files.getAsJsonArray());
    final String fileName = JsonApi.string(file, "filename");
    final String url = JsonApi.string(file, "url");
    if (fileName == null || url == null) {
      throw new PluginUpdateException("Modrinth left out the file name or its URL.");
    }

    final JsonObject hashes = JsonApi.object(file, "hashes");
    final String sha512 = JsonApi.string(hashes, "sha512");
    final String sha1 = JsonApi.string(hashes, "sha1");
    final PluginRelease.Checksum checksum;
    if (sha512 != null) {
      checksum = new PluginRelease.Checksum("SHA-512", sha512);
    } else if (sha1 != null) {
      checksum = new PluginRelease.Checksum("SHA-1", sha1);
    } else {
      checksum = null;
    }

    final String versionNumber = JsonApi.string(version, "version_number");
    // The type goes in the version string when it is not a release, so the log says what it
    // installed rather than leaving a beta looking like a stable build.
    final String label = versionNumber == null ? "unknown" : versionNumber;
    return new PluginRelease("modrinth", slug,
        "release".equals(type) ? label : label + " (" + type + ")",
        fileName, checksum, url);
  }

  /** The newest version of one type, or null if the project publishes none of it. */
  private static @Nullable JsonObject newestOfType(final JsonArray versions, final String type) {
    for (final JsonElement element : versions) {
      if (!element.isJsonObject()) {
        continue;
      }
      final JsonObject version = element.getAsJsonObject();
      if (type.equals(JsonApi.string(version, "version_type"))) {
        return version;
      }
    }
    return null;
  }

  /** The file marked primary, or the first one when none is. */
  private static @Nullable JsonObject primary(final JsonArray files) {
    JsonObject first = null;
    for (final JsonElement element : files) {
      if (!element.isJsonObject()) {
        continue;
      }
      final JsonObject file = element.getAsJsonObject();
      if (first == null) {
        first = file;
      }
      final JsonElement isPrimary = file.get("primary");
      if (isPrimary != null && isPrimary.isJsonPrimitive() && isPrimary.getAsBoolean()) {
        return file;
      }
    }
    return first;
  }
}
