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

import com.velocitypowered.api.event.Subscribe;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A listener shaped the way a real plugin's is, which is the case the tests in
 * {@code com.velocitypowered.proxy.event} do not cover.
 *
 * <p>Those listeners are package-private but live in the same package as the event manager, so the
 * proxy's own {@code MethodHandles.Lookup} reaches them whatever it is used for. A plugin is in a
 * different package entirely, and its listener class is frequently not public — this one is
 * deliberately both, so that generating its handler exercises the access path a plugin actually
 * takes.</p>
 */
final class ExternalPackageListener {

  private final AtomicInteger called = new AtomicInteger();

  @Subscribe
  void onEvent(final ExternalPackageEvent event) {
    called.incrementAndGet();
  }

  int timesCalled() {
    return called.get();
  }
}
