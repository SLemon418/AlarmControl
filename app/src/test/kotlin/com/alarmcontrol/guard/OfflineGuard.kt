package com.alarmcontrol.guard

/**
 * The automated offline guard (CLAUDE.md §3): the shipped app must declare no `INTERNET` permission
 * and carry no networking library. These checks are pure and deterministic so they can be unit-tested
 * with injected inputs; the enforcing tests feed them the real merged manifest and the real classpath.
 */
object OfflineGuard {
    /** Permissions that would allow data egress — banned outright. */
    val FORBIDDEN_PERMISSIONS = setOf("android.permission.INTERNET")

    /**
     * Networking libraries banned by §3, keyed by a canonical client class that is only loadable when
     * the library is on the classpath.
     *
     * `android.permission.ACCESS_NETWORK_STATE` is intentionally NOT treated as a violation: it is
     * read-only (it cannot move data) and WorkManager declares it. Likewise `okio` (DataStore's file
     * I/O) is not a networking client and is not listed here.
     */
    val FORBIDDEN_NETWORK_CLASSES: Map<String, String> =
        mapOf(
            "okhttp3.OkHttpClient" to "OkHttp",
            "retrofit2.Retrofit" to "Retrofit",
            "io.ktor.client.HttpClient" to "Ktor client",
            "io.grpc.ManagedChannel" to "gRPC",
            "com.android.volley.RequestQueue" to "Volley",
            "com.apollographql.apollo3.ApolloClient" to "Apollo",
            "com.google.firebase.FirebaseApp" to "Firebase",
        )

    /** The forbidden permissions present in [requested] (e.g. the merged manifest's uses-permissions). */
    fun forbiddenPermissions(requested: List<String>): List<String> = requested.filter { it in FORBIDDEN_PERMISSIONS }

    /** Labels of the forbidden networking libraries that [classExists] reports as present. */
    fun forbiddenNetworkLibraries(classExists: (String) -> Boolean): List<String> =
        FORBIDDEN_NETWORK_CLASSES.filterKeys(classExists).values.toList()

    /** Whether [className] is loadable on the current classpath (without running static initializers). */
    fun classExists(className: String): Boolean =
        runCatching {
            Class.forName(className, false, OfflineGuard::class.java.classLoader)
        }.isSuccess
}
