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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Downloads and updates the plugins listed under {@code [plugins] hangar} before they are loaded.
 *
 * <p>Runs once, at start-up, immediately before {@code loadPlugins()}: by the time the plugin
 * loader reads {@code plugins/}, every jar it is meant to find is there. All of them are fetched
 * at once and the start-up waits for the last one.</p>
 *
 * <p>Whether a plugin is up to date is decided by the SHA-256 Hangar publishes, not by the file
 * name. Names are ambiguous — Hangar's build {@code 5.12.0-SNAPSHOT+1053} ships a file called
 * {@code ViaVersion-5.12.0-SNAPSHOT.jar} — and the same digest verifies the download afterwards,
 * so a truncated or corrupted file is never put in place.</p>
 *
 * <p>A failure is only fatal when it would leave the proxy without a plugin: if the jar is already
 * on disk, a Hangar outage is a warning and the installed version is used. If it is not, start-up
 * stops, because carrying on means running without a plugin somebody listed on purpose.</p>
 */
public final class PluginUpdater {

  private static final Logger logger = LogManager.getLogger(PluginUpdater.class);

  private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

  private final HttpClient httpClient;
  private final HangarClient hangar;
  private final Path pluginDirectory;

  /**
   * Creates an updater.
   *
   * @param httpClient the proxy's HTTP client
   * @param pluginDirectory the directory plugins are loaded from
   */
  public PluginUpdater(final HttpClient httpClient, final Path pluginDirectory) {
    this.httpClient = httpClient;
    this.hangar = new HangarClient(httpClient);
    this.pluginDirectory = pluginDirectory;
  }

  /**
   * Brings every listed plugin up to date, and blocks until they all are.
   *
   * @param slugs the plugin names from the configuration
   * @throws PluginUpdateException if a plugin is missing and could not be downloaded
   */
  public void updateAll(final List<String> slugs) throws PluginUpdateException {
    if (slugs.isEmpty()) {
      return;
    }

    logger.info("Checking {} plugin(s) for updates...", slugs.size());
    try {
      Files.createDirectories(pluginDirectory);
    } catch (IOException e) {
      throw new PluginUpdateException("could not create the plugins directory", e);
    }

    // One thread per plugin, capped: this is network-bound and short-lived, and holding up
    // start-up any longer than the slowest download would be the whole point missed.
    final ExecutorService pool =
        Executors.newFixedThreadPool(Math.min(slugs.size(), 8), runnable -> {
          final Thread thread = new Thread(runnable, "Plugin Updater");
          thread.setDaemon(true);
          return thread;
        });

    final List<CompletableFuture<String>> futures = new ArrayList<>(slugs.size());
    try {
      for (final String slug : slugs) {
        futures.add(CompletableFuture.supplyAsync(() -> updateOne(slug), pool));
      }
      // join() rather than a timeout: every request already has its own, so this cannot hang
      // longer than the slowest of them.
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } finally {
      pool.shutdown();
    }

    final List<String> fatal = new ArrayList<>();
    for (final CompletableFuture<String> future : futures) {
      final String failure = future.join();
      if (failure != null) {
        fatal.add(failure);
      }
    }

    if (!fatal.isEmpty()) {
      throw new PluginUpdateException(String.join("; ", fatal));
    }
  }

  /** Returns null when the plugin ended up usable, or a message when start-up must stop. */
  private String updateOne(final String slug) {
    try {
      final HangarRelease release = hangar.latestRelease(slug);
      install(slug, release);
      return null;
    } catch (PluginUpdateException | IOException e) {
      final List<Path> installed = existingJars(slug);
      if (installed.isEmpty()) {
        logger.error("Could not install {}: {}", slug, sentence(e.getMessage()));
        return slug + " (" + e.getMessage() + ")";
      }
      logger.warn("Could not check {} for updates: {} Continuing with the installed {}.",
          slug, sentence(e.getMessage()), installed.get(0).getFileName());
      return null;
    }
  }

  /** Ends a reason with a full stop, since some of them already carry one and some do not. */
  private static String sentence(final String message) {
    if (message == null || message.isEmpty()) {
      return "no reason given.";
    }
    return message.endsWith(".") ? message : message + ".";
  }

  private void install(final String slug, final HangarRelease release)
      throws PluginUpdateException, IOException {
    final Path target = pluginDirectory.resolve(release.fileName());
    if (Files.exists(target) && release.sha256().equalsIgnoreCase(sha256Of(target))) {
      // Still prune: an older jar dropped in by hand is not made harmless by us being up to date.
      // The plugin loader refuses the second one it sees for an id, and which of the two it reads
      // first is not something anybody controls.
      pruneOtherJars(release.baseName(), target);
      logger.info("{} {} is up to date.", slug, release.version());
      return;
    }

    logger.info("Updating {} to {}...", slug, release.version());
    final Path part = pluginDirectory.resolve(release.fileName() + ".part");
    try {
      download(release.downloadUrl(), part);

      final String actual = sha256Of(part);
      if (!release.sha256().equalsIgnoreCase(actual)) {
        throw new PluginUpdateException("the downloaded file did not match the checksum Hangar "
            + "published for it, so it has been discarded");
      }

      // Only once the replacement is known good.
      pruneOtherJars(release.baseName(), target);
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
      logger.info("{} {} installed.", slug, release.version());
    } finally {
      Files.deleteIfExists(part);
    }
  }

  private void download(final String url, final Path destination)
      throws PluginUpdateException, IOException {
    final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "HybridVelocity")
        .timeout(DOWNLOAD_TIMEOUT)
        .GET()
        .build();
    try {
      final HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw new PluginUpdateException("the download answered " + response.statusCode() + ".");
      }
      try (InputStream in = response.body()) {
        Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PluginUpdateException("interrupted while downloading it", e);
    }
  }

  /**
   * Deletes every other jar of this plugin, so only {@code keep} is left for the loader to find.
   *
   * <p>Two jars claiming one plugin id make the loader refuse the second one it happens to read,
   * and nothing decides which that is. Being out of date is recoverable; loading the wrong copy
   * silently is not.</p>
   */
  private void pruneOtherJars(final String baseName, final Path keep) throws IOException {
    for (final Path old : existingJars(baseName)) {
      if (!old.equals(keep)) {
        Files.deleteIfExists(old);
        logger.info("Removed {}, which claims the same plugin as {}",
            old.getFileName(), keep.getFileName());
      }
    }
  }

  /** Every jar in the plugin directory that belongs to this plugin. */
  private List<Path> existingJars(final String name) {
    if (!Files.isDirectory(pluginDirectory)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(pluginDirectory)) {
      return entries.filter(path -> isJarOf(name, path.getFileName().toString())).toList();
    } catch (IOException e) {
      logger.debug("Could not list {}", pluginDirectory, e);
      return List.of();
    }
  }

  /**
   * Whether a file in the plugin directory is a jar of the named plugin.
   *
   * <p>Matches {@code Name.jar} and {@code Name-<anything>.jar}, ignoring case. The dash is what
   * keeps {@code ViaVersion} from claiming {@code ViaVersionAddon-1.0.jar}.</p>
   *
   * @param name the plugin's base name
   * @param fileName the file name to test
   * @return whether the file is a jar of that plugin
   */
  static boolean isJarOf(final String name, final String fileName) {
    final String lower = fileName.toLowerCase(Locale.ROOT);
    if (!lower.endsWith(".jar")) {
      return false;
    }
    final String stem = lower.substring(0, lower.length() - ".jar".length());
    final String prefix = name.toLowerCase(Locale.ROOT);
    return stem.equals(prefix) || stem.startsWith(prefix + "-");
  }

  private static String sha256Of(final Path path) throws IOException {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is required of every JVM", e);
    }
    try (InputStream in = Files.newInputStream(path)) {
      final byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
