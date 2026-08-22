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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The one GET-and-parse-JSON every source needs, and the small decisions that go with it.
 *
 * <p>A 404 is separated from every other failure because it is the one an operator can act on: it
 * means the name in the configuration is wrong, rather than that something is broken.</p>
 */
final class JsonApi {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String USER_AGENT = "HybridAuth";

  private final HttpClient httpClient;

  JsonApi(final HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  /** Thrown for a 404, so a caller can say "no such plugin" rather than "something went wrong". */
  static final class NotFoundException extends Exception {
    private static final long serialVersionUID = 1L;
  }

  JsonElement get(final String url) throws PluginUpdateException, NotFoundException {
    return get(url, Map.of());
  }

  /**
   * Fetches a URL and parses the body as JSON.
   *
   * @param url the URL to fetch
   * @param headers extra request headers, such as an authorization
   * @return the parsed body
   */
  JsonElement get(final String url, final Map<String, String> headers)
      throws PluginUpdateException, NotFoundException {
    final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .timeout(TIMEOUT)
        .GET();
    headers.forEach(request::header);

    final HttpResponse<String> response;
    try {
      response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new PluginUpdateException("could not reach " + hostOf(url)
          + " (" + e.getMessage() + ")", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PluginUpdateException("interrupted while asking " + hostOf(url) + " about it", e);
    }

    if (response.statusCode() == 404) {
      throw new NotFoundException();
    }
    if (response.statusCode() != 200) {
      throw new PluginUpdateException(hostOf(url) + " answered " + response.statusCode() + ".");
    }
    return parse(response.body());
  }

  static JsonElement parse(final String body) throws PluginUpdateException {
    try {
      return JsonParser.parseString(body);
    } catch (RuntimeException e) {
      throw new PluginUpdateException("the answer was not JSON.", e);
    }
  }

  /** Reads a string member, or null when it is missing or JSON null. */
  static @Nullable String string(final @Nullable JsonObject object, final String member) {
    if (object == null) {
      return null;
    }
    final JsonElement element = object.get(member);
    if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
      return null;
    }
    final String value = element.getAsString();
    return value.isEmpty() ? null : value;
  }

  /** Reads an object member, or null when it is missing or JSON null. */
  static @Nullable JsonObject object(final @Nullable JsonObject parent, final String member) {
    if (parent == null) {
      return null;
    }
    final JsonElement element = parent.get(member);
    return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
  }

  private static String hostOf(final String url) {
    try {
      return URI.create(url).getHost();
    } catch (RuntimeException e) {
      return "the server";
    }
  }
}
