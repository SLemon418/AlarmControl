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
}
