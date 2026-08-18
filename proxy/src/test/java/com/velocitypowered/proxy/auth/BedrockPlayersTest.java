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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.util.UuidUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.api.Test;

class BedrockPlayersTest {

  private static final UUID UNLINKED_BEDROCK = new UUID(0L, 1234567890L);

  @Test
  void anUnlinkedBedrockUuidIsRecognisedWithoutFloodgate() {
    assertTrue(new BedrockPlayers(noPlugins()).isBedrock(UNLINKED_BEDROCK));
  }

  @Test
  void anOfflineJavaUuidIsNotMistakenForBedrock() {
    // Offline Java UUIDs are type 3, so their version bits sit in the half Floodgate leaves at
    // zero. The two shapes cannot collide.
    assertFalse(BedrockPlayers.hasBedrockUuidShape(UuidUtils.generateOfflinePlayerUuid("Steve")));
    assertFalse(new BedrockPlayers(noPlugins())
        .isBedrock(UuidUtils.generateOfflinePlayerUuid("Steve")));
  }

  @Test
  void aPremiumUuidIsNotMistakenForBedrock() {
    assertFalse(new BedrockPlayers(noPlugins()).isBedrock(UUID.randomUUID()));
  }

  @Test
  void aMissingUuidIsNotBedrock() {
    assertFalse(new BedrockPlayers(noPlugins()).isBedrock(null));
    assertFalse(BedrockPlayers.hasBedrockUuidShape(null));
  }

  @Test
  void floodgatesApiIsReachedThroughThePluginsClassLoader() throws Exception {
    final PluginLikeClassLoader loader = new PluginLikeClassLoader();
    final Class<?> pluginsCopy = loader.loadClass(FLOODGATE_API);
    // If this were the same class, the lookup could have gone through the proxy's own loader and
    // the test would prove nothing about reaching into a plugin.
    assertNotSame(FloodgateApi.class, pluginsCopy);

    final BedrockPlayers players = new BedrockPlayers(
        withFloodgate(pluginsCopy.getDeclaredConstructor().newInstance()));

    // A Java-shaped UUID the fallback would reject: only the API can answer this one, which is the
    // case of a Bedrock player who has linked a Java account.
    assertFalse(BedrockPlayers.hasBedrockUuidShape(FloodgateApi.LINKED_JAVA_PLAYER));
    assertTrue(players.isBedrock(FloodgateApi.LINKED_JAVA_PLAYER));
    assertFalse(players.isBedrock(UUID.randomUUID()));
    // This fake only claims the linked account, so here the API says no while the shape says
    // yes. An unlinked player must stay exempt regardless.
    assertTrue(players.isBedrock(UNLINKED_BEDROCK));
  }

  @Test
  void anUnusableFloodgateFallsBackInsteadOfThrowing() {
    // A plugin under the right id whose instance has no FloodgateApi behind it at all.
    final BedrockPlayers players = new BedrockPlayers(withFloodgate("not really floodgate"));

    assertTrue(players.isBedrock(UNLINKED_BEDROCK));
    assertFalse(players.isBedrock(UUID.randomUUID()));
  }

  @Test
  void aFloodgateThatExposesNoInstanceFallsBack() {
    final PluginContainer container = mock(PluginContainer.class);
    doReturn(Optional.empty()).when(container).getInstance();
    final PluginManager manager = mock(PluginManager.class);
    when(manager.getPlugin("floodgate")).thenReturn(Optional.of(container));

    final BedrockPlayers players = new BedrockPlayers(manager);

    assertTrue(players.isBedrock(UNLINKED_BEDROCK));
    assertFalse(players.isBedrock(UUID.randomUUID()));
  }

  private static final String FLOODGATE_API = "org.geysermc.floodgate.api.FloodgateApi";

  private static PluginManager noPlugins() {
    final PluginManager manager = mock(PluginManager.class);
    when(manager.getPlugin("floodgate")).thenReturn(Optional.empty());
    return manager;
  }

  private static PluginManager withFloodgate(final Object instance) {
    final PluginContainer container = mock(PluginContainer.class);
    doReturn(Optional.of(instance)).when(container).getInstance();
    final PluginManager manager = mock(PluginManager.class);
    when(manager.getPlugin("floodgate")).thenReturn(Optional.of(container));
    return manager;
  }

  /**
   * Defines its own copy of the fake API, the way a plugin's class loader holds classes the proxy's
   * loader cannot see. Everything else is delegated to the parent, as {@code PluginClassLoader}
   * does.
   */
  private static final class PluginLikeClassLoader extends ClassLoader {

    PluginLikeClassLoader() {
      super(BedrockPlayersTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
      if (!FLOODGATE_API.equals(name)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        final Class<?> already = findLoadedClass(name);
        if (already != null) {
          return already;
        }
        final byte[] bytes = read(name);
        final Class<?> defined = defineClass(name, bytes, 0, bytes.length);
        if (resolve) {
          resolveClass(defined);
        }
        return defined;
      }
    }

    private byte[] read(final String name) throws ClassNotFoundException {
      final String resource = name.replace('.', '/') + ".class";
      try (InputStream in = getParent().getResourceAsStream(resource)) {
        if (in == null) {
          throw new ClassNotFoundException(name);
        }
        return in.readAllBytes();
      } catch (IOException e) {
        throw new ClassNotFoundException(name, e);
      }
    }
  }
}
