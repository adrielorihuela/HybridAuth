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

package com.velocitypowered.proxy.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.ConfigFormat;
import com.electronwill.nightconfig.core.io.ConfigParser;
import com.electronwill.nightconfig.core.io.ConfigWriter;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlVersion;
import java.util.Map;
import java.util.function.Supplier;

/**
 * TOML as night-config reads it, but at TOML 1.1 rather than its default of 1.0.
 *
 * <p>1.1 lets an inline table span several lines, which is how {@code [portales]} is written:
 * one server per entry, one dimension per line. 1.0 rejects that outright with "Invalid bare
 * key". Every 1.0 document is also valid 1.1, so existing configurations read exactly as before.
 * Only the parser differs; writing is night-config's own.</p>
 */
final class Toml11Format implements ConfigFormat<CommentedConfig> {

  static final Toml11Format INSTANCE = new Toml11Format();

  private final TomlFormat toml = TomlFormat.instance();

  private Toml11Format() {
  }

  @Override
  public ConfigWriter createWriter() {
    return toml.createWriter();
  }

  @Override
  public ConfigParser<CommentedConfig> createParser() {
    final TomlParser parser = toml.createParser();
    parser.setTomlVersion(TomlVersion.v1_1);
    return parser;
  }

  @Override
  public CommentedConfig createConfig(final Supplier<Map<String, Object>> mapCreator) {
    return toml.createConfig(mapCreator);
  }

  @Override
  public boolean supportsComments() {
    return toml.supportsComments();
  }

  @Override
  public boolean supportsType(final Class<?> type) {
    return toml.supportsType(type);
  }
}
