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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.velocitypowered.proxy.config.VelocityConfiguration.PluginsConfig;
import com.velocitypowered.proxy.plugin.update.PluginUpdater.Wanted;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading the {@code [plugins]} section. */
class PluginsConfigTest {

  @Test
  void absentSectionYieldsTheDefault() {
    assertSame(PluginsConfig.DEFAULT, PluginsConfig.fromConfig(null));
  }

  @Test
  void nothingIsWantedByDefault() {
    // An installation that has not been told to fetch anything must make no requests at all.
    assertTrue(PluginsConfig.DEFAULT.wanted().isEmpty());
  }

  @Test
  void everyListIsRead() {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("hangar", List.of("ViaVersion"));
    config.set("modrinth", List.of("ViaBackwards"));
    config.set("github", List.of("ViaVersion/ViaRewind"));
    config.set("geyser", List.of("geyser", "floodgate"));
    config.set("luckperms", true);

    assertEquals(List.of(
        new Wanted("hangar", "ViaVersion"),
        new Wanted("modrinth", "ViaBackwards"),
        new Wanted("github", "ViaVersion/ViaRewind"),
        new Wanted("geyser", "geyser"),
        new Wanted("geyser", "floodgate"),
        new Wanted("luckperms", "LuckPerms")),
        PluginsConfig.fromConfig(config).wanted());
  }

  @Test
  void luckpermsIsOffUnlessAskedFor() {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("luckperms", false);

    assertTrue(PluginsConfig.fromConfig(config).wanted().isEmpty());
  }

  @Test
  void blankEntriesAreDropped() {
    // What a half-edited list looks like. Asking a source about "" only produces a confusing
    // error several steps later.
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("hangar", List.of("ViaVersion", "", "  "));

    assertEquals(List.of(new Wanted("hangar", "ViaVersion")),
        PluginsConfig.fromConfig(config).wanted());
  }

  @Test
  void surroundingSpaceIsIgnored() {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("modrinth", List.of("  ViaBackwards  "));

    assertEquals(List.of(new Wanted("modrinth", "ViaBackwards")),
        PluginsConfig.fromConfig(config).wanted());
  }
}
