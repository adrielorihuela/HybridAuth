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
 * Somewhere plugins are published, and a way to ask it what the current build of one is.
 *
 * <p>Each implementation owns one API and the shape of its answers. What they have in common is
 * only what {@link PluginRelease} carries, which is deliberately the least a download needs — the
 * sources disagree about almost everything else, including whether a checksum exists.</p>
 */
public interface PluginSource {

  /** The name of this source, as it appears in the configuration and in the log. */
  String name();

  /**
   * Looks up the current build of a plugin.
   *
   * @param spec the entry from the configuration
   * @return the release to install
   * @throws PluginUpdateException if the source cannot be reached or does not publish it
   */
  PluginRelease resolve(String spec) throws PluginUpdateException;
}
