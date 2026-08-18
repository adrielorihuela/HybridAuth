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

package org.geysermc.floodgate.api;

import java.util.UUID;

/**
 * Stands in for Floodgate's real API so the reflective lookup in
 * {@code com.velocitypowered.proxy.auth.BedrockPlayers} can be exercised without the plugin.
 *
 * <p>It is only ever used through a class loader that defines its own copy, mimicking a plugin.
 * The name and shape match the real thing: a static {@code getInstance} and an
 * {@code isFloodgatePlayer(UUID)}.</p>
 */
public final class FloodgateApi {

  /**
   * The UUID this fake claims as a Bedrock player. It has a perfectly ordinary Java shape, so a
   * test that sees it recognised knows the answer came from here and not from the UUID fallback —
   * which is exactly the case of a player who has linked a Java account.
   */
  public static final UUID LINKED_JAVA_PLAYER =
      UUID.fromString("11111111-2222-3333-4444-555555555555");

  private static final FloodgateApi INSTANCE = new FloodgateApi();

  public static FloodgateApi getInstance() {
    return INSTANCE;
  }

  public boolean isFloodgatePlayer(final UUID uuid) {
    return LINKED_JAVA_PLAYER.equals(uuid);
  }
}
