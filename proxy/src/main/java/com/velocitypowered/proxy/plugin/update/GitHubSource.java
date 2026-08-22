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
import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Plugins from a GitHub project's latest release.
 *
 * <p>Entries are {@code owner/repo}, optionally {@code owner/repo:regex} to choose between several
 * jars. The owner cannot be left out: nothing in the API can guess it.</p>
 *
 * <p>GitHub allows sixty unauthenticated requests an hour per address, which is plenty for a few
 * plugins at start-up but not for a machine that restarts constantly. A token raises that to five
 * thousand. It comes from the file named by {@code github-token-file}, or from a
 * {@code GITHUB_TOKEN} environment variable, which wins when both are set.</p>
 *
 * <p>The token is kept in a file of its own rather than in the configuration, the same way the
 * forwarding secret is: configurations get pasted into issues and screenshots, and a token in one
 * is a credential handed to whoever reads it.</p>
 */
final class GitHubSource implements PluginSource {

  private static final String API = "https://api.github.com/repos/";

  /** Attached to sources and javadoc jars, which are never the plugin. */
  private static final Pattern NOT_A_PLUGIN = Pattern.compile("sources|javadoc",
      Pattern.CASE_INSENSITIVE);

  private final JsonApi api;
  private final @Nullable String token;

  GitHubSource(final HttpClient httpClient, final @Nullable String token) {
    this.api = new JsonApi(httpClient);
    final String fromEnvironment = System.getenv("GITHUB_TOKEN");
    final String chosen = fromEnvironment != null && !fromEnvironment.isBlank()
        ? fromEnvironment : token;
    this.token = chosen == null || chosen.isBlank() ? null : chosen.trim();
  }

  @Override
  public String name() {
    return "github";
  }

  @Override
  public PluginRelease resolve(final String spec) throws PluginUpdateException {
    final String repo = repositoryOf(spec);
    final String assetPattern = patternOf(spec);

    final Map<String, String> headers = new HashMap<>();
    headers.put("Accept", "application/vnd.github+json");
    if (token != null) {
      headers.put("Authorization", "Bearer " + token);
    }

    final JsonElement body;
    try {
      body = api.get(API + repo + "/releases/latest", headers);
    } catch (JsonApi.NotFoundException e) {
      throw new PluginUpdateException("GitHub has no repository '" + repo + "', or it has no "
          + "published releases.");
    }

    // The download carries the same authorization as the lookup. Without it a private repository
    // cannot be reached at all, and neither can a public one belonging to an account GitHub has
    // flagged: github.com paths answer 404 to everybody, token or not, while the API keeps
    // serving the file.
    final Map<String, String> downloadHeaders = new HashMap<>();
    downloadHeaders.put("Accept", "application/octet-stream");
    if (token != null) {
      downloadHeaders.put("Authorization", "Bearer " + token);
    }
    return parse(repo, assetPattern, body, downloadHeaders);
  }

  /**
   * The {@code owner/repo} part of an entry.
   *
   * @param spec the configuration entry
   * @return the repository
   * @throws PluginUpdateException if it is not in {@code owner/repo} form
   */
  static String repositoryOf(final String spec) throws PluginUpdateException {
    final int colon = spec.indexOf(':');
    final String repo = colon == -1 ? spec : spec.substring(0, colon);
    final int slash = repo.indexOf('/');
    if (slash <= 0 || slash == repo.length() - 1 || repo.indexOf('/', slash + 1) != -1) {
      throw new PluginUpdateException("'" + spec + "' is not a GitHub repository. Write it as "
          + "owner/repo, for example ViaVersion/ViaVersion — the owner cannot be guessed.");
    }
    return repo;
  }

  /** The optional {@code :regex} part of an entry, or null. */
  static @Nullable String patternOf(final String spec) {
    final int colon = spec.indexOf(':');
    if (colon == -1 || colon == spec.length() - 1) {
      return null;
    }
    return spec.substring(colon + 1);
  }

  /** Split out from the request so asset selection can be tested without the network. */
  static PluginRelease parse(final String repo, final @Nullable String assetPattern,
      final JsonElement body) throws PluginUpdateException {
    return parse(repo, assetPattern, body, Map.of());
  }

  static PluginRelease parse(final String repo, final @Nullable String assetPattern,
      final JsonElement body, final Map<String, String> downloadHeaders)
      throws PluginUpdateException {
    if (!body.isJsonObject()) {
      throw new PluginUpdateException("GitHub's answer was not the JSON object expected.");
    }
    final JsonObject release = body.getAsJsonObject();
    final String tag = JsonApi.string(release, "tag_name");

    final JsonElement assets = release.get("assets");
    if (assets == null || !assets.isJsonArray()) {
      throw new PluginUpdateException("GitHub's latest release lists no files.");
    }

    final Pattern chooser;
    try {
      chooser = assetPattern == null ? null : Pattern.compile(assetPattern);
    } catch (PatternSyntaxException e) {
      throw new PluginUpdateException("'" + assetPattern + "' is not a valid regular expression.",
          e);
    }

    for (final JsonElement element : (JsonArray) assets) {
      if (!element.isJsonObject()) {
        continue;
      }
      final JsonObject asset = element.getAsJsonObject();
      final String fileName = JsonApi.string(asset, "name");
      // The API's own asset URL, not browser_download_url: this one honours the Authorization
      // header, and the browser path does not.
      final String url = JsonApi.string(asset, "url");
      if (fileName == null || url == null
          || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")
          || NOT_A_PLUGIN.matcher(fileName).find()
          || (chooser != null && !chooser.matcher(fileName).find())) {
        continue;
      }

      // Newer GitHub releases carry "digest": "sha256:<hex>". Older ones carry nothing, and then
      // the file name is all there is to go on.
      final String digest = JsonApi.string(asset, "digest");
      final PluginRelease.Checksum checksum = digest != null && digest.startsWith("sha256:")
          ? new PluginRelease.Checksum("SHA-256", digest.substring("sha256:".length()))
          : null;

      final String version = tag == null ? "unknown"
          : tag.startsWith("v") ? tag.substring(1) : tag;
      return new PluginRelease("github", repo, version, fileName, checksum, url,
          downloadHeaders);
    }

    throw new PluginUpdateException(assetPattern == null
        ? "GitHub's latest release of " + repo + " has no .jar attached to it."
        : "no .jar in GitHub's latest release of " + repo + " matches '" + assetPattern + "'.");
  }
}
