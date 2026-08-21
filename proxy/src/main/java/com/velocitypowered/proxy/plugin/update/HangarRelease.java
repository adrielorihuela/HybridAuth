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

/**
 * One published plugin build on Hangar, as much of it as installing needs.
 *
 * @param version the Hangar version name, for the log only
 * @param fileName the file name Hangar publishes it under
 * @param sha256 the hex digest Hangar publishes for it
 * @param downloadUrl where to fetch it from
 */
public record HangarRelease(String version, String fileName, String sha256, String downloadUrl) {

  /**
   * The name the plugin's jars go by on disk: the file name without its version suffix.
   *
   * <p>Hangar's version name and the version inside the file name are not always the same thing —
   * build {@code 5.12.0-SNAPSHOT+1053} ships {@code ViaVersion-5.12.0-SNAPSHOT.jar} — so this is
   * derived from the file name rather than assembled from the version.</p>
   *
   * @return the base name, without a version or the {@code .jar} suffix
   */
  public String baseName() {
    String name = fileName;
    if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
      name = name.substring(0, name.length() - ".jar".length());
    }
    final int dash = name.indexOf('-');
    return dash > 0 ? name.substring(0, dash) : name;
  }
}
