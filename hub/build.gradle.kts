plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The always-on peer.
//
// Not a new architecture: it speaks the same routes a TV speaks and takes part
// in the same merge, using the *same* `:core` code the app does. What it adds
// is that it never sleeps — so a config change reaches the TV without a parent's
// phone being home and awake, which is the single biggest limitation of the
// LAN-only design.
//
// A household that never runs this sees no change at all. That is a hard rule:
// every route here already exists, and nothing in :app may come to depend on
// the hub being present.

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.pickwick.hub.MainKt")
}

dependencies {
    // The merge, the stamper, the serializers — shared verbatim with the app so
    // there is one implementation of the rules rather than two that drift.
    implementation(project(":core"))
    // Android supplies org.json in the platform; here it has to be brought.
    implementation(libs.json)

    testImplementation(libs.json)
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
