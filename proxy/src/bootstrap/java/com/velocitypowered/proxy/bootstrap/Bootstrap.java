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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The jar's entry point, and the only class in it that an old JVM can load.
 *
 * <p>Everything else is compiled for Java 25. Starting the proxy on anything older used to fail
 * with a bare {@code UnsupportedClassVersionError} naming an internal class, which tells an
 * operator nothing. This class is compiled for Java 8 instead, so it loads anywhere, checks the
 * running version and explains the problem before handing over.</p>
 *
 * <p>It must therefore not use anything newer than Java 8 — no {@code Runtime.version()}, no
 * {@code var}, no text blocks — and it reaches the real entry point by reflection so that the
 * Java 25 classes are not linked until the check has passed.</p>
 */
public final class Bootstrap {

  /** The Java feature version the rest of the jar is compiled for. */
  private static final int REQUIRED_JAVA = 25;

  private static final String MAIN_CLASS = "com.velocitypowered.proxy.Velocity";

  private Bootstrap() {
  }

  /**
   * Checks the Java version, then starts the proxy.
   *
   * @param args the command line arguments
   * @throws Exception if the proxy fails to start
   */
  public static void main(final String[] args) throws Exception {
    final int running = featureVersion(System.getProperty("java.specification.version"));
    if (!isSupported(running)) {
      System.err.println("HybridVelocity needs Java " + REQUIRED_JAVA
          + " or newer, but this is Java " + running + ".");
      System.err.println();
      System.err.println("Install a Java " + REQUIRED_JAVA
          + " runtime and start the proxy with it. If several are installed, point at the right");
      System.err.println("one directly, for example:");
      System.err.println("  /path/to/jdk-" + REQUIRED_JAVA + "/bin/java -jar "
          + "HybridVelocity-<version>.jar");
      System.exit(1);
      return;
    }

    // Reflection, so the Java 25 classes are not linked while this method is being verified.
    final Method main = Class.forName(MAIN_CLASS).getMethod("main", String[].class);
    try {
      main.invoke(null, (Object) args);
    } catch (InvocationTargetException e) {
      // Without this, every startup failure surfaces as an InvocationTargetException wrapping the
      // real one, which buries the actual cause under this class's frames.
      final Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw cause instanceof Exception ? (Exception) cause : e;
    }
  }

  /**
   * Parses a {@code java.specification.version} value into a feature version.
   *
   * <p>That property is read rather than {@link Runtime#version()}, which only exists from Java 9
   * and would itself fail on the very versions this is here to catch. It reads {@code "1.8"} on
   * Java 8 and the plain number from Java 9 onwards.</p>
   *
   * <p>Package-private so it can be tested; an unrecognised value yields {@code -1}, which the
   * caller treats as "cannot tell" and lets through rather than refusing to start.</p>
   *
   * @param specificationVersion the property value, possibly {@code null}
   * @return the feature version, or {@code -1} if it cannot be determined
   */
  static int featureVersion(final String specificationVersion) {
    if (specificationVersion == null) {
      return -1;
    }
    String version = specificationVersion;
    if (version.startsWith("1.")) {
      version = version.substring(2);
    }
    final int dot = version.indexOf('.');
    if (dot != -1) {
      version = version.substring(0, dot);
    }
    try {
      return Integer.parseInt(version);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Whether the proxy can run on this Java version.
   *
   * @param featureVersion the running feature version, or {@code -1} if unknown
   * @return {@code false} only when the version is known and too old
   */
  static boolean isSupported(final int featureVersion) {
    return featureVersion <= 0 || featureVersion >= REQUIRED_JAVA;
  }
}
