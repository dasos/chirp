plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core is a pure Kotlin/JVM module on purpose — no Android dependencies. This
// keeps the session/state/contract logic unit-testable on the JVM and reusable
// by a future Wear OS module (Phase 2) that depends on :core.
kotlin {
    jvmToolchain(17)
}

dependencies {
    // Exposed as `api` so :app (and the future :wear) see coroutines/serialization types.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
