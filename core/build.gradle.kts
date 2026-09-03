plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The rules two Pickwicks have to agree on, in one place.
//
// The config merge, the stamper and the sync decision are pure Kotlin — no
// Android, no disk, no clock — so the phone, the TV and the Docker hub can all
// run the *same* implementation rather than three that drift. A second
// implementation of tombstone causality is exactly the failure
// `.claude/skills/pickwick-sync` warns about, and its symptom is a family
// losing a channel.
//
// Plain JVM on purpose: adding the Android plugin here would stop the hub
// depending on it.

kotlin {
    jvmToolchain(17)
}

dependencies {
    // compileOnly, because Android already ships org.json in the platform and a
    // second copy on a device would be a duplicate-class build failure. Every
    // non-Android consumer (the hub, and this module's own tests) supplies it.
    compileOnly(libs.json)

    testImplementation(libs.json)
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
