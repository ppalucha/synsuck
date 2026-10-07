// Dev-only CLI for exercising librus-client against a real account. Not part of the app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "pl.synsuck.probe.MainKt"
}

dependencies {
    implementation(project(":librus-client"))
}
