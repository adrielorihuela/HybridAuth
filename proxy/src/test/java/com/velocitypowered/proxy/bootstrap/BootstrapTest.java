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

package com.velocitypowered.proxy.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BootstrapTest {

  @Test
  void modernVersionsParseAsThemselves() {
    assertEquals(25, Bootstrap.featureVersion("25"));
    assertEquals(21, Bootstrap.featureVersion("21"));
    assertEquals(17, Bootstrap.featureVersion("17"));
  }

  @Test
  void javaEightUsesTheOldOneDotForm() {
    assertEquals(8, Bootstrap.featureVersion("1.8"));
  }

  @Test
  void trailingComponentsAreIgnored() {
    assertEquals(25, Bootstrap.featureVersion("25.0.2"));
  }

  @Test
  void unrecognisedValuesAreReportedAsUnknown() {
    assertEquals(-1, Bootstrap.featureVersion(null));
    assertEquals(-1, Bootstrap.featureVersion("banana"));
    assertEquals(-1, Bootstrap.featureVersion(""));
  }

  @Test
  void olderRuntimesAreRefused() {
    assertFalse(Bootstrap.isSupported(8));
    assertFalse(Bootstrap.isSupported(17));
    assertFalse(Bootstrap.isSupported(24));
  }

  @Test
  void javaTwentyFiveAndNewerAreAccepted() {
    assertTrue(Bootstrap.isSupported(25));
    assertTrue(Bootstrap.isSupported(30));
  }

  @Test
  void anUnknownVersionIsLetThrough() {
    // Refusing to start because the version could not be read would be worse than trying.
    assertTrue(Bootstrap.isSupported(-1));
  }
}
