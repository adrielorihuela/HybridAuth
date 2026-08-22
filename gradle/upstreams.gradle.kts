// One command for the procedure in docs/development/updating-upstreams.md.
//
// That page exists because most of what it says is a mistake somebody could make twice: the fork is
// *merged* from upstream and never rebased, the subtree pull has to repeat --prefix and --squash
// exactly or it stops being able to find its own import, and vendored files carry patches a
// careless conflict resolution would swallow. The tasks below do the safe thing by construction and
// print the rest at the moment it is needed, rather than expecting it to have been read first.
//
// They deliberately stop short of pushing. An update that has not been compiled and started is not
// something to publish.

val velocityRemote = "upstream"
val velocityUrl = "https://github.com/PaperMC/Velocity.git"
val velocityBranch = "dev/4.0.0"

val limboRemote = "limbo-upstream"
val limboUrl = "https://github.com/Nan1t/NanoLimbo.git"
val limboBranch = "main"
val limboPrefix = "limbo/upstream"

// Captured here rather than inside doLast so the tasks never reach for the Project at execution
// time, which is what the configuration cache forbids.
val repoDir = rootProject.layout.projectDirectory.asFile

/** Runs git with its output on the console, and returns the exit code. */
fun git(vararg args: String): Int =
    ProcessBuilder(listOf("git") + args).directory(repoDir).inheritIO().start().waitFor()

/** Runs git quietly and returns its trimmed standard output, or an empty string if it failed. */
fun gitOutput(vararg args: String): String {
  val process = ProcessBuilder(listOf("git") + args).directory(repoDir).start()
  val out = process.inputStream.bufferedReader().readText().trim()
  process.errorStream.readAllBytes()
  return if (process.waitFor() == 0) out else ""
}

fun run(vararg args: String) {
  if (git(*args) != 0) {
    throw GradleException("git " + args.joinToString(" ") + " failed. Nothing has been pushed; the "
        + "working tree is as git left it.")
  }
}

/**
 * Refuses to touch the repository unless it is in a state where a merge is safe.
 *
 * Every one of these is a way to lose work, or to leave the history in a shape the next update
 * cannot cope with, so they abort rather than warn.
 */
fun preflight() {
  if (gitOutput("rev-parse", "--is-inside-work-tree") != "true") {
    throw GradleException("This is not a git repository, or git is not on the PATH.")
  }
  if (File(gitOutput("rev-parse", "--absolute-git-dir"), "MERGE_HEAD").exists()) {
    throw GradleException("A merge is already in progress. Finish it - resolve the conflicts, "
        + "'git add' them and 'git commit' - or 'git merge --abort', then run this again.")
  }
  if (gitOutput("status", "--porcelain").isNotEmpty()) {
    throw GradleException("The working tree has uncommitted changes. Merging on top of them is "
        + "how they get lost. Commit or stash them first.")
  }
  val branch = gitOutput("rev-parse", "--abbrev-ref", "HEAD")
  if (branch != "main") {
    throw GradleException("On branch '" + branch + "', not 'main'. The upstream merges and the "
        + "limbo/upstream subtree both belong to main's history; updating anywhere else produces a "
        + "history the next update cannot follow. Switch to main first.")
  }
}

/** Adds a remote if it is missing. An existing one is left alone: a different URL may be on purpose. */
fun ensureRemote(name: String, url: String) {
  val existing = gitOutput("remote", "get-url", name)
  when {
    existing.isEmpty() -> {
      run("remote", "add", name, url)
      println("Added remote '" + name + "' -> " + url)
    }
    existing == url -> println("Remote '" + name + "' is already " + url)
    else -> println("Remote '" + name + "' points at " + existing + ", not " + url
        + ". Leaving it alone - if that is not deliberate: git remote set-url " + name + " " + url)
  }
}

val conflictAdvice = """
    Resolve them by keeping BOTH sides: take upstream's change and re-apply the fork's on top.
    Never resolve one by dropping the fork's code - docs/development/ explains what each of these
    exists for, and several of them encode a bug that was expensive to find.

    The files that conflict are almost always the same ones:
      VelocityServer.java ................ starts and stops the authentication server, registers
                                           the shortcut commands, reclaims the auth commands
      VelocityConfiguration.java ......... the OfflineAuthConfig record, 'comandos', validate()
      default-velocity.toml .............. the [offline-auth] section and 'comandos'
      InitialLoginSessionHandler.java .... the hybrid offline profile on HTTP 204
      command/builtin/*.java ............. the InternalServers filter in the server listings
      build.gradle.kts, libs.versions.toml  the fork jar naming, bcrypt, SQLite, :limbo

    When you are done:     git add <files> && git commit
    To back out entirely:  git merge --abort
""".trimIndent()

tasks.register("addUpstreams") {
  group = "upstream"
  description = "Adds the Velocity and NanoLimbo remotes, which a fresh clone does not have."
  doLast {
    ensureRemote(velocityRemote, velocityUrl)
    ensureRemote(limboRemote, limboUrl)
  }
}

tasks.register("updateVelocity") {
  group = "upstream"
  description = "Merges the latest upstream Velocity into main. Merges, never rebases."
  dependsOn("addUpstreams")
  doLast {
    preflight()
    run("fetch", velocityRemote)

    val target = velocityRemote + "/" + velocityBranch
    val incoming = gitOutput("rev-list", "--count", "HEAD.." + target)
    if (incoming == "0") {
      println("Velocity is already up to date with " + target + ".")
      return@doLast
    }
    println(incoming + " upstream commit(s) to merge from " + target + ":")
    git("log", "--oneline", "--no-decorate", "-20", "HEAD.." + target)

    if (git("merge", "--no-edit", target) != 0) {
      val conflicts = gitOutput("diff", "--name-only", "--diff-filter=U")
      throw GradleException(buildString {
        appendLine("The merge stopped on conflicts. It has been left in progress on purpose - "
            + "aborting it for you would throw away a resolution you may be halfway through.")
        appendLine()
        if (conflicts.isNotEmpty()) {
          appendLine("Conflicted:")
          conflicts.lines().forEach { appendLine("  " + it) }
          appendLine()
        }
        append(conflictAdvice)
      })
    }
    println("Merged " + target + ". Build and start the proxy before pushing.")
  }
}

tasks.register("updateAuthServer") {
  group = "upstream"
  description = "Pulls the NanoLimbo subtree at limbo/upstream."
  dependsOn("addUpstreams")
  doLast {
    preflight()
    run("fetch", limboRemote)

    // --prefix and --squash must match the original import exactly. 'git subtree pull' finds the
    // previous import by searching history for the git-subtree-dir/git-subtree-split trailers it
    // wrote, so a mismatch means it does not find them and re-imports the whole tree as a conflict.
    if (git("subtree", "pull", "--prefix=" + limboPrefix, limboRemote, limboBranch, "--squash")
        != 0) {
      val conflicts = gitOutput("diff", "--name-only", "--diff-filter=U")
      throw GradleException(buildString {
        appendLine("The subtree pull stopped, and anything it started has been left in place.")
        appendLine()
        if (conflicts.isNotEmpty()) {
          appendLine("Conflicted:")
          conflicts.lines().forEach { appendLine("  " + it) }
          appendLine()
        }
        appendLine("Files under " + limboPrefix + " that carry local patches are marked in the "
            + "source with 'HybridAuth patch:'. If one of them is in the list, keep the patch "
            + "and re-apply it on top of upstream's change - never resolve by dropping it. "
            + "docs/development/auth-server.md says what each one is for.")
        appendLine()
        appendLine("If git reported that 'subtree' is not a git command, this git installation "
            + "does not ship it. Git for Windows does, as do most distributions' git packages.")
      })
    }

    println()
    println("Subtree updated. Three things do not happen by themselves:")
    println("  1. Check the 'HybridAuth patch:' markers under " + limboPrefix + " survived.")
    println("  2. Bump LIMBO_VERSION in limbo/src/main/java/ua/nanit/limbo/BuildConfig.java.")
    println("  3. Compare upstream's libs.versions.toml with ours - the subtree compiles against")
    println("     our versions, and Netty especially must agree on both sides.")
    println("Also worth a look: whether upstream's settings.yml gained options worth exposing in")
    println("proxy/src/main/resources/auth-server-settings.yml. That template is ours.")
  }
}

tasks.register("updateUpstreams") {
  group = "upstream"
  description = "Updates both upstreams: merges Velocity, then pulls the NanoLimbo subtree."
  dependsOn("updateVelocity", "updateAuthServer")
  doLast {
    println()
    println("Both upstreams are up to date locally. Nothing has been pushed.")
    println()
    println("Next:")
    println("  ./gradlew build")
    println("  ./gradlew :velocity-proxy:runShadow   # and watch for:")
    println("      [auth]: Server started on /127.0.0.1:30065")
    println()
    println("A Velocity update can break these without the proxy failing to start:")
    println("  - StateRegistry: new protocol versions move packet IDs, and the authentication")
    println("    server has its own copy of the protocol in the subtree.")
    println("  - The event API: the whole authentication gate is built from events, so a changed")
    println("    signature disables it quietly.")
    println("  - New getAllServers() call sites list the authentication server. Add them to")
    println("    server/InternalServers.java.")
  }
}

// The subtree pull rewrites limbo/upstream, so it must not run while Velocity's merge is mid-flight.
tasks.named("updateAuthServer") { mustRunAfter("updateVelocity") }
