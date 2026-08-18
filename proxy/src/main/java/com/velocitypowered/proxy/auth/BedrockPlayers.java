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

package com.velocitypowered.proxy.auth;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Recognises players who arrive through GeyserMC, and whose identity Floodgate has already verified
 * against Xbox Live.
 *
 * <p>They must not be asked to register a password: they have authenticated once already, with
 * something the proxy cannot improve on. This is not configurable, because there is no sensible
 * reason to ask them twice.</p>
 *
 * <p>Two ways of telling, in order:</p>
 *
 * <ol>
 *   <li><strong>Floodgate's own API.</strong> Authoritative, and the only one that recognises a
 *       player who has <em>linked</em> a Java account — they keep their real Java UUID, so nothing
 *       about their identity looks like Bedrock. Reached by reflection through the plugin's own
 *       class loader, because the proxy cannot depend on a plugin at compile time and its own class
 *       loader cannot see plugin classes: {@code PluginClassLoader} has the proxy's loader as its
 *       parent, and delegation runs child to parent, never the other way.</li>
 *   <li><strong>The shape of the UUID.</strong> Floodgate builds an unlinked Bedrock player's UUID
 *       from their XUID with the top 64 bits left at zero. Java's offline UUIDs come from
 *       {@code UuidUtils.generateOfflinePlayerUuid}, which produces type 3 UUIDs carrying version
 *       bits in that half, so it can never be zero and the two cannot be confused.</li>
 * </ol>
 *
 * <p>Every reflective failure falls back to the UUID check and none of them throw. Getting this
 * wrong must not be able to stop somebody logging in.</p>
 */
public final class BedrockPlayers {

  private static final Logger logger = LogManager.getLogger(BedrockPlayers.class);

  private static final String FLOODGATE_PLUGIN_ID = "floodgate";
  private static final String FLOODGATE_API_CLASS = "org.geysermc.floodgate.api.FloodgateApi";

  private final PluginManager pluginManager;

  /** Resolved once, lazily: {@code null} until looked up, empty if Floodgate is unusable. */
  private volatile @Nullable Optional<FloodgateApiHandle> floodgate;

  public BedrockPlayers(final PluginManager pluginManager) {
    this.pluginManager = pluginManager;
  }

  /**
   * Whether this player has already been authenticated elsewhere and must not be asked again.
   *
   * @param uuid the player's UUID
   * @return {@code true} for players arriving through GeyserMC
   */
  public boolean isBedrock(final UUID uuid) {
    if (uuid == null) {
      return false;
    }

    final FloodgateApiHandle handle = resolveFloodgate().orElse(null);
    if (handle != null) {
      final Boolean answer = handle.isFloodgatePlayer(uuid);
      if (answer != null) {
        return answer;
      }
    }
    return hasBedrockUuidShape(uuid);
  }

  /**
   * Whether the UUID has the shape Floodgate gives an unlinked Bedrock player.
   *
   * @param uuid the player's UUID
   * @return {@code true} if the top 64 bits are zero
   */
  static boolean hasBedrockUuidShape(final @Nullable UUID uuid) {
    return uuid != null && uuid.getMostSignificantBits() == 0;
  }

  private Optional<FloodgateApiHandle> resolveFloodgate() {
    Optional<FloodgateApiHandle> resolved = this.floodgate;
    if (resolved != null) {
      return resolved;
    }

    synchronized (this) {
      resolved = this.floodgate;
      if (resolved != null) {
        return resolved;
      }
      resolved = lookUpFloodgate();
      this.floodgate = resolved;
      return resolved;
    }
  }

  private Optional<FloodgateApiHandle> lookUpFloodgate() {
    final Optional<PluginContainer> plugin = pluginManager.getPlugin(FLOODGATE_PLUGIN_ID);
    if (plugin.isEmpty()) {
      return Optional.empty();
    }

    final Object instance = plugin.get().getInstance().orElse(null);
    if (instance == null) {
      logger.debug("Floodgate is installed but exposes no instance; falling back to the UUID "
          + "shape to recognise Bedrock players.");
      return Optional.empty();
    }

    try {
      final Class<?> api = Class.forName(
          FLOODGATE_API_CLASS, false, instance.getClass().getClassLoader());
      final Object apiInstance = api.getMethod("getInstance").invoke(null);
      if (apiInstance == null) {
        return Optional.empty();
      }
      final Method isFloodgatePlayer = api.getMethod("isFloodgatePlayer", UUID.class);
      logger.info("Floodgate detected; Bedrock players will skip offline authentication.");
      return Optional.of(new FloodgateApiHandle(apiInstance, isFloodgatePlayer));
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      logger.warn("Floodgate is installed but its API could not be reached. Bedrock players will "
          + "still skip authentication when their UUID has the usual Floodgate shape, but a player "
          + "who linked a Java account may be asked to register.", e);
      return Optional.empty();
    }
  }

  /** A resolved handle on Floodgate's API, so the reflection happens once rather than per call. */
  private static final class FloodgateApiHandle {

    private final Object apiInstance;
    private final Method isFloodgatePlayer;

    private FloodgateApiHandle(final Object apiInstance, final Method isFloodgatePlayer) {
      this.apiInstance = apiInstance;
      this.isFloodgatePlayer = isFloodgatePlayer;
    }

    private @Nullable Boolean isFloodgatePlayer(final UUID uuid) {
      try {
        final Object answer = isFloodgatePlayer.invoke(apiInstance, uuid);
        return answer instanceof Boolean ? (Boolean) answer : null;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
        logger.debug("Floodgate's API rejected a lookup; falling back to the UUID shape.", e);
        return null;
      }
    }
  }
}
