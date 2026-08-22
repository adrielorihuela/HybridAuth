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

import java.util.Locale;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One published build of a plugin, as much of it as installing needs.
 *
 * @param source where it came from, for the log
 * @param id the plugin's identifier within that source
 * @param version the published version, for the log
 * @param fileName the file name it is published under
 * @param checksum what the source published to verify it by, or {@code null} if it publishes none
 * @param downloadUrl where to fetch it from
 * @param downloadHeaders headers the download needs, such as an authorization
 */
public record PluginRelease(String source, String id, String version, String fileName,
    @Nullable Checksum checksum, String downloadUrl, Map<String, String> downloadHeaders) {

  public PluginRelease {
    downloadHeaders = Map.copyOf(downloadHeaders);
  }

  /**
   * A release from a source whose download needs nothing but the URL, which is most of them.
   *
   * @param source where it came from
   * @param id the plugin's identifier within that source
   * @param version the published version
   * @param fileName the file name it is published under
   * @param checksum what the source published to verify it by, or {@code null}
   * @param downloadUrl where to fetch it from
   */
  public PluginRelease(final String source, final String id, final String version,
      final String fileName, final @Nullable Checksum checksum, final String downloadUrl) {
    this(source, id, version, fileName, checksum, downloadUrl, Map.of());
  }

  /**
   * A digest, with the algorithm it was computed with.
   *
   * <p>Which algorithm depends entirely on the source: Hangar, GitHub and GeyserMC publish
   * SHA-256, Modrinth publishes SHA-512 and SHA-1 but no SHA-256, and LuckPerms publishes none at
   * all. So it travels with its algorithm rather than being assumed.</p>
   *
   * @param algorithm a {@link java.security.MessageDigest} algorithm name
   * @param hex the expected digest, hex encoded
   */
  public record Checksum(String algorithm, String hex) {
  }

  /**
   * The name the plugin's jars go by on disk: the file name without its version suffix.
   *
   * <p>Derived from the file name rather than assembled from the version, because the two do not
   * always agree — Hangar's build {@code 5.12.0-SNAPSHOT+1053} ships
   * {@code ViaVersion-5.12.0-SNAPSHOT.jar}.</p>
   *
   * @return the base name, without a version or the {@code .jar} suffix
   */
  public String baseName() {
    String name = fileName;
    if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
      name = name.substring(0, name.length() - ".jar".length());
    }
    final int dash = name.indexOf('-');
    return dash > 0 ? name.substring(0, dash) : name;
  }
}
