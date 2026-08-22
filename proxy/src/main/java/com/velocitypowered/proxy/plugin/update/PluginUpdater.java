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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Downloads and updates the plugins listed under {@code [plugins]} before they are loaded.
 *
 * <p>Runs once, at start-up, immediately before {@code loadPlugins()}: by the time the plugin
 * loader reads {@code plugins/}, every jar it is meant to find is there. All of them are fetched
 * at once and the start-up waits for the last one.</p>
 *
 * <p>Whether a plugin is up to date is decided by the checksum its source publishes, not by the
 * file name. Names are ambiguous — Hangar's build {@code 5.12.0-SNAPSHOT+1053} ships a file called
 * {@code ViaVersion-5.12.0-SNAPSHOT.jar} — and the same digest verifies the download afterwards,
 * so a truncated or corrupted file is never put in place. LuckPerms publishes no checksum at all,
 * and there the file name is what there is; that is said out loud in the log rather than left to
 * look like a verification that happened.</p>
 *
 * <p>A failure is only fatal when it would leave the proxy without a plugin: if the jar is already
 * on disk, an outage is a warning and the installed version is used. If it is not, start-up stops,
 * because carrying on means running without a plugin somebody listed on purpose.</p>
 */
public final class PluginUpdater {

  private static final Logger logger = LogManager.getLogger(PluginUpdater.class);

  private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

  /**
   * One line of the configuration: which source, and what to ask it for.
   *
   * @param source the name of the source, as written in the configuration
   * @param spec the plugin identifier within it
   */
  public record Wanted(String source, String spec) {
  }

  private final HttpClient httpClient;
  private final Path pluginDirectory;
  private final Map<String, PluginSource> sources;

  /**
   * Creates an updater.
   *
   * @param pluginDirectory the directory plugins are loaded from
   * @param githubToken a GitHub token to raise its rate limit with, or {@code null} for none
   */
  public PluginUpdater(final Path pluginDirectory, final @Nullable String githubToken) {
    this(defaultHttpClient(), pluginDirectory, githubToken);
  }

  /**
   * A client of its own, rather than the proxy's.
   *
   * <p>Every one of these APIs redirects: GeyserMC answers {@code builds/latest} with a 302 to the
   * real build, and GitHub's download links point at another host entirely. The proxy's client is
   * built with the default {@code NEVER} policy, and it is shared with Mojang session
   * authentication, which is not somewhere to go changing redirect behaviour for the convenience
   * of downloading plugins.</p>
   */
  private static HttpClient defaultHttpClient() {
    return HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build();
  }

  PluginUpdater(final HttpClient httpClient, final Path pluginDirectory,
      final @Nullable String githubToken) {
    this.httpClient = httpClient;
    this.pluginDirectory = pluginDirectory;
    this.sources = Map.of(
        "hangar", new HangarSource(httpClient),
        "modrinth", new ModrinthSource(httpClient),
        "github", new GitHubSource(httpClient, githubToken),
        "geyser", new GeyserSource(httpClient),
        "luckperms", new LuckPermsSource(httpClient));
  }

  /**
   * Brings every listed plugin up to date, and blocks until they all are.
   *
   * @param wanted what the configuration asks for
   * @throws PluginUpdateException if a plugin is missing and could not be downloaded
   */
  public void updateAll(final List<Wanted> wanted) throws PluginUpdateException {
    if (wanted.isEmpty()) {
      return;
    }

    logger.info("Checking {} plugin(s) for updates...", wanted.size());
    try {
      Files.createDirectories(pluginDirectory);
    } catch (IOException e) {
      throw new PluginUpdateException("could not create the plugins directory", e);
    }

    // One thread per plugin, capped: this is network-bound and short-lived, and holding up
    // start-up any longer than the slowest download would be the whole point missed.
    final ExecutorService pool =
        Executors.newFixedThreadPool(Math.min(wanted.size(), 8), runnable -> {
          final Thread thread = new Thread(runnable, "Plugin Updater");
          thread.setDaemon(true);
          return thread;
        });

    final List<CompletableFuture<String>> futures = new ArrayList<>(wanted.size());
    try {
      for (final Wanted one : wanted) {
        futures.add(CompletableFuture.supplyAsync(() -> updateOne(one), pool));
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
  private String updateOne(final Wanted wanted) {
    final String label = wanted.spec();
    try {
      final PluginSource source = sources.get(wanted.source());
      if (source == null) {
        throw new PluginUpdateException("there is no plugin source called " + wanted.source()
            + ". The ones there are: " + String.join(", ", sources.keySet().stream().sorted()
            .toList()) + ".");
      }
      install(label, source.resolve(wanted.spec()));
      return null;
    } catch (PluginUpdateException | IOException e) {
      final List<Path> installed = existingJars(lastSegment(label));
      if (installed.isEmpty()) {
        logger.error("Could not install {}: {}", label, sentence(e.getMessage()));
        return label + " (" + e.getMessage() + ")";
      }
      logger.warn("Could not check {} for updates: {} Continuing with the installed {}.",
          label, sentence(e.getMessage()), installed.get(0).getFileName());
      return null;
    }
  }

  /** Ends a reason with a full stop, since some of them already carry one and some do not. */
  private static String sentence(final @Nullable String message) {
    if (message == null || message.isEmpty()) {
      return "no reason given.";
    }
    return message.endsWith(".") ? message : message + ".";
  }

  /**
   * The part of an entry that could name a jar on disk.
   *
   * <p>For {@code ViaVersion/ViaRewind} that is {@code ViaRewind}; for everything else it is the
   * entry itself. Only used to guess whether something is already installed when the source could
   * not be reached, so a near miss costs a warning rather than a wrong decision.</p>
   *
   * @param spec the configuration entry
   * @return the part that might match a file name
   */
  static String lastSegment(final String spec) {
    final int slash = spec.lastIndexOf('/');
    final String withoutOwner = slash == -1 ? spec : spec.substring(slash + 1);
    final int colon = withoutOwner.indexOf(':');
    return colon == -1 ? withoutOwner : withoutOwner.substring(0, colon);
  }

  private void install(final String label, final PluginRelease release)
      throws PluginUpdateException, IOException {
    final Path target = pluginDirectory.resolve(release.installedFileName());
    if (Files.exists(target) && isCurrent(target, release)) {
      // Still prune: an older jar dropped in by hand is not made harmless by us being up to date.
      // The plugin loader refuses the second one it sees for an id, and which of the two it reads
      // first is not something anybody controls.
      pruneOtherJars(release.baseName(), target);
      logger.info("{} {} is up to date.", label, release.version());
      return;
    }

    logger.info("Updating {} to {} from {}...", label, release.version(), release.source());
    final Path part = pluginDirectory.resolve(release.installedFileName() + ".part");
    try {
      download(release.downloadUrl(), part);

      final PluginRelease.Checksum checksum = release.checksum();
      if (checksum != null) {
        final String actual = digestOf(part, checksum.algorithm());
        if (!checksum.hex().equalsIgnoreCase(actual)) {
          throw new PluginUpdateException("the downloaded file did not match the checksum "
              + release.source() + " published for it, so it has been discarded");
        }
      } else {
        logger.info("{} publishes no checksum, so {} could not be verified.",
            release.source(), release.installedFileName());
      }

      // Only once the replacement is known good.
      pruneOtherJars(release.baseName(), target);
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
      logger.info("{} {} installed.", label, release.version());
    } finally {
      Files.deleteIfExists(part);
    }
  }

  /**
   * Whether the file already on disk is the release the source is offering.
   *
   * <p>With a checksum this is exact. Without one — LuckPerms publishes none — the answer is that
   * a file of that name is there, which is weaker but not meaningless: the version is part of the
   * name, so a newer release asks for a different file.</p>
   */
  private boolean isCurrent(final Path target, final PluginRelease release) throws IOException {
    final PluginRelease.Checksum checksum = release.checksum();
    if (checksum == null) {
      return true;
    }
    return checksum.hex().equalsIgnoreCase(digestOf(target, checksum.algorithm()));
  }

  private void download(final String url, final Path destination)
      throws PluginUpdateException, IOException {
    final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "HybridAuth")
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
   * <p>Two jars claiming one plugin id make the loader refuse whichever it reads second, and
   * nothing decides which that is. Being out of date is recoverable; loading the wrong copy
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

  private static String digestOf(final Path path, final String algorithm) throws IOException {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("this JVM has no " + algorithm + " implementation", e);
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
