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

  /**
   * Hangar's channels, most stable first.
   *
   * <p>Taken in order, and the newest build on the first channel that has any is the one
   * installed. Asking for a channel matters: unfiltered, Hangar answers with whatever is newest,
   * which for an actively developed plugin is a snapshot — ViaVersion answers
   * {@code 5.12.0-SNAPSHOT+1053} that way and {@code 5.11.0} filtered to Release.</p>
   *
   * <p>Falling down the list matters just as much: Simple Voice Chat publishes Velocity builds
   * only on Alpha and Beta, and stopping at Release would mean the proxy could not install a
   * plugin that exists and works.</p>
   */
  private static final String[] CHANNELS_BY_STABILITY = {"Release", "Beta", "Alpha", "Snapshot"};

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
    final String project = URLEncoder.encode(slug, StandardCharsets.UTF_8);
    PluginUpdateException lastFailure = null;

    for (final String channel : CHANNELS_BY_STABILITY) {
      final JsonElement body;
      try {
        body = api.get(API + project + "/versions?limit=1&platform=VELOCITY&channel=" + channel);
      } catch (JsonApi.NotFoundException e) {
        throw new PluginUpdateException("Hangar has no project by that name. Check the spelling "
            + "against the project's page on hangar.papermc.io.");
      }
      try {
        return parse(slug, body, channel);
      } catch (EmptyChannelException e) {
        continue;
      } catch (PluginUpdateException e) {
        // A real problem with this build rather than an empty channel -- an external link, say.
        // Remember it, but keep looking: a lower channel may be perfectly installable.
        lastFailure = e;
      }
    }

    throw lastFailure != null ? lastFailure
        : new PluginUpdateException("Hangar knows the project but publishes no Velocity build of "
            + "it on any channel. It may be a Paper-only plugin.");
  }

  /** Thrown when a channel simply has nothing, which is not a failure worth reporting. */
  static final class EmptyChannelException extends PluginUpdateException {
    private static final long serialVersionUID = 1L;

    EmptyChannelException() {
      super("this channel has no Velocity build.");
    }
  }

  /** Split out from the request so the shape of a real answer can be tested without the network. */
  static PluginRelease parse(final String slug, final JsonElement body)
      throws PluginUpdateException {
    return parse(slug, body, "Release");
  }

  static PluginRelease parse(final String slug, final JsonElement body, final String channel)
      throws PluginUpdateException {
    if (!body.isJsonObject()) {
      throw new PluginUpdateException("Hangar's answer was not the JSON object expected.");
    }
    final JsonElement result = body.getAsJsonObject().get("result");
    if (result == null || !result.isJsonArray() || result.getAsJsonArray().isEmpty()) {
      throw new EmptyChannelException();
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

    // The channel goes in the version string when it is not Release, so the log says what it
    // installed rather than leaving a beta looking like a stable build.
    final String label = versionName == null ? "unknown" : versionName;
    return new PluginRelease("hangar", slug,
        "Release".equals(channel) ? label : label + " (" + channel + ")",
        fileName,
        sha256 == null ? null : new PluginRelease.Checksum("SHA-256", sha256),
        downloadUrl);
  }
}
