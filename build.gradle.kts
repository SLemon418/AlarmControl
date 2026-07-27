import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// Root build file. Plugins are declared here (apply false) and applied per-module so the
// version catalog stays the single source of truth.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

// AGP lint's Kotlin analysis session is not safe when separate Android modules analyze in parallel
// (it can fail with a duplicated BuiltinsVirtualFileProvider). Keep the rest of Gradle parallel,
// but serialize only lint analysis tasks through this zero-state shared service.
abstract class AndroidLintAnalysisLock : BuildService<BuildServiceParameters.None>, AutoCloseable {
    override fun close() = Unit
}

val androidLintAnalysisLock =
    gradle.sharedServices.registerIfAbsent(
        "androidLintAnalysisLock",
        AndroidLintAnalysisLock::class,
    ) {
        maxParallelUsages.set(1)
    }

val verifyCiActionPins by tasks.registering {
    group = "verification"
    description = "Fails when a remote GitHub Action or container is not immutably pinned."

    val githubDirectory = layout.projectDirectory.dir(".github")
    inputs.dir(githubDirectory)

    doLast {
        val usesPattern = Regex("""(?m)^\s*(?:-\s*)?uses:\s+([^\s#]+)""")
        val immutableAction = Regex("""[^@\s]+@[0-9a-fA-F]{40}""")
        val immutableContainer = Regex("""docker://[^@\s]+@sha256:[0-9a-fA-F]{64}""")
        val yamlFiles =
            githubDirectory.asFile
                .walkTopDown()
                .filter { file -> file.isFile && file.extension in setOf("yml", "yaml") }
                .toList()
        check(yamlFiles.any { file -> file.parentFile.name == "workflows" }) {
            "No GitHub Actions workflows were found"
        }
        val unpinned =
            yamlFiles.flatMap { yaml ->
                usesPattern
                    .findAll(yaml.readText())
                    .map { match -> match.groupValues[1] }
                    .filterNot { action ->
                        action.startsWith("./") ||
                            immutableContainer.matches(action) ||
                            immutableAction.matches(action)
                    }.map { action -> "${yaml.relativeTo(githubDirectory.asFile)}:$action" }
                    .toList()
            }
        check(unpinned.isEmpty()) {
            "GitHub Actions must use 40-character commit SHAs and containers must use SHA-256 digests: " +
                unpinned.joinToString()
        }
    }
}

// Code quality is applied to every module from here so the rules are uniform and live in one place.
// Both plugins auto-wire into `check` (and therefore `build`), so `./gradlew build` enforces them.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        parallel = true
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        filter { exclude { entry -> entry.file.path.contains("/build/") } }
    }

    tasks.configureEach {
        if (name.startsWith("lintAnalyze") || name.startsWith("lintVitalAnalyze")) {
            usesService(androidLintAnalysisLock)
        }
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyCiActionPins"))
    }
}
