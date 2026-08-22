import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer
import io.papermc.fill.model.BuildChannel

plugins {
    application
    id("velocity-init-manifest")
    alias(libs.plugins.shadow)
    alias(libs.plugins.fill)
}

application {
    mainClass.set("com.velocitypowered.proxy.bootstrap.Bootstrap")
}

// A one-class source set compiled for Java 8 so that it loads on any JVM. Everything else targets
// Java 25, so without this an operator on an older runtime gets a bare UnsupportedClassVersionError
// naming an internal class. See Bootstrap.java.
val bootstrap: SourceSet by sourceSets.creating

tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
    options.release = 8
    // `release 8` is obsolete and javac says so; that is the point of this source set.
    options.compilerArgs.add("-Xlint:-options")
}

sourceSets.test {
    compileClasspath += bootstrap.output
    runtimeClasspath += bootstrap.output
}

// Name and version of this fork, used for the distributable JAR and the version the proxy
// reports at runtime. The project version stays on the upstream Velocity API version so that
// plugins depending on velocity-api are unaffected.
val forkName: String by project
val forkVersion: String by project

tasks {
    withType<Checkstyle> {
        exclude("**/com/velocitypowered/proxy/protocol/packet/**")
    }

    jar {
        // The thin jar: HybridAuth-<forkVersion>-thin.jar. It is not runnable and exists only
        // as an input to shadowJar; the classifier keeps it from being mistaken for the real one.
        archiveBaseName.set(forkName)
        archiveVersion.set(forkVersion)
        archiveClassifier.set("thin")

        from(bootstrap.output)

        manifest {
            attributes["Main-Class"] = "com.velocitypowered.proxy.bootstrap.Bootstrap"
            attributes["Implementation-Title"] = forkName
            attributes["Implementation-Vendor"] = "HybridAuth Contributors"
            attributes["Multi-Release"] = "true"
        }
    }

    shadowJar {
        // The runnable distributable: HybridAuth-<forkVersion>.jar
        archiveBaseName.set(forkName)
        archiveVersion.set(forkVersion)
        archiveClassifier.set("")

        // shadowJar builds from the main source set, not from the jar task, so the Java 8
        // bootstrap has to be added here as well or the entry point is missing from the jar.
        from(bootstrap.output)

        manifest {
            attributes["Main-Class"] = "com.velocitypowered.proxy.bootstrap.Bootstrap"
            attributes["Implementation-Title"] = forkName
            attributes["Implementation-Vendor"] = "HybridAuth Contributors"
            attributes["Implementation-Version"] = forkVersion
        }

        filesMatching("META-INF/org/apache/logging/log4j/core/config/plugins/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        transform(Log4j2PluginsCacheFileTransformer::class.java)

        // Exclude Checker Framework annotations
        exclude("org/checkerframework/checker/**")

        relocate("org.bstats", "com.velocitypowered.proxy.bstats")

        // Include Configurate 3
        val configurateBuildTask = project(":deprecated-configurate3").tasks.named("shadowJar")
        dependsOn(configurateBuildTask)
        from(zipTree(configurateBuildTask.map { it.outputs.files.singleFile }))
    }

    runShadow {
        workingDir = file("run").also(File::mkdirs)
        standardInput = System.`in`
    }
    named<JavaExec>("run") {
        workingDir = file("run").also(File::mkdirs)
        standardInput = System.`in` // Doesn't work?
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Alog4j.graalvm.groupId=${project.group}",
                "-Alog4j.graalvm.artifactId=${project.name}"
            )
        )
    }
}

val projectVersion = version as String
fill {
    project("velocity")

    build {
        channel = BuildChannel.STABLE
        versionFamily("4.0.0")
        version(projectVersion)

        if (versionFamily.get().split(".")[0] != projectVersion.split(".")[0]) {
            throw IllegalArgumentException("Version family does not match project version")
        }

        downloads {
            register("server:default") {
                file = tasks.shadowJar.flatMap { it.archiveFile }
                nameResolver.set { project, _, version, build -> "$project-$version-$build.jar" }
            }
        }
    }
}

dependencies {
    implementation(project(":velocity-api"))
    implementation(project(":velocity-native"))
    implementation(project(":limbo"))

    implementation(libs.bundles.log4j)
    implementation(libs.kyori.ansi)
    implementation(libs.netty.codec)
    implementation(libs.netty.codec.haproxy)
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.netty.transport.native.epoll)
    implementation(variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") })
    implementation(variantOf(libs.netty.transport.native.epoll) { classifier("linux-aarch_64") })
    implementation(libs.netty.transport.native.iouring)
    implementation(variantOf(libs.netty.transport.native.iouring) { classifier("linux-x86_64") })
    implementation(variantOf(libs.netty.transport.native.iouring) { classifier("linux-aarch_64") })
    implementation(libs.netty.transport.native.kqueue)
    implementation(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-x86_64") })
    implementation(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-aarch_64") })

    implementation(libs.bcrypt)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jopt)
    implementation(libs.terminalconsoleappender)
    runtimeOnly(libs.jline)
    runtimeOnly(libs.disruptor)
    implementation(libs.fastutil)
    implementation(platform(libs.adventure.bom))
    implementation(libs.adventure.text.serializer.json.legacy.impl)
    implementation(libs.completablefutures)
    implementation(libs.nightconfig)
    implementation(libs.bstats)
    implementation(libs.lmbda)
    implementation(libs.asm)
    implementation(libs.bundles.flare)
    compileOnly(libs.spotbugs.annotations)
    compileOnly(libs.auto.service.annotations)
    testImplementation(libs.mockito)

    annotationProcessor(libs.auto.service)
    annotationProcessor(libs.log4j.core)
}
