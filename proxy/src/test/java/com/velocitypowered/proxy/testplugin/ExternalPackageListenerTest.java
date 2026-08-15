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

package com.velocitypowered.proxy.testplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.testutil.FakePluginManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Registers a listener shaped the way a plugin's is — different package, non-public class and
 * method — which is the shape the event tests in {@code com.velocitypowered.proxy.event} cannot
 * exercise, because their listeners share a package with the event manager.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalPackageListenerTest {

  private final FakePluginManager pluginManager = new FakePluginManager();
  private final VelocityEventManager eventManager = new VelocityEventManager(pluginManager);

  @AfterAll
  void shutdown() throws Exception {
    pluginManager.shutdown();
  }

  @Test
  void pluginShapedListenerCanBeRegisteredAndCalled() throws Exception {
    final ExternalPackageListener listener = new ExternalPackageListener();

    eventManager.register(FakePluginManager.PLUGIN_A, listener);
    eventManager.fire(new ExternalPackageEvent()).get();

    assertEquals(1, listener.timesCalled(),
        "a listener in another package must still receive events");
  }
}
