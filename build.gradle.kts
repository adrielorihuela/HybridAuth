plugins {
    `java-library`
    id("velocity-checkstyle") apply false
    id("velocity-spotless") apply false
}

// Fork-specific: ./gradlew updateUpstreams. Kept in its own file so this upstream-owned script
// stays a one-line diff to resolve when upstream changes it.
apply(from = "gradle/upstreams.gradle.kts")

subprojects {
    apply<JavaLibraryPlugin>()

    apply(plugin = "velocity-checkstyle")
    apply(plugin = "velocity-spotless")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        testImplementation(rootProject.libs.junit)
    }

    testing.suites.named<JvmTestSuite>("test") {
        useJUnitJupiter()
        targets.all {
            testTask.configure {
                reports.junitXml.required = true
            }
        }
    }
}
