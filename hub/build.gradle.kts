plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The always-on peer.
//
// Not a new architecture: it speaks the same routes a TV speaks and takes part
// in the same merge, using the *same* `:core` code the app does. What it adds
// is that it never sleeps, so two parents reconcile without both being home.
//
// It does NOT yet feed the TVs. A TV has no way to join a hub — the join
// screen exists only in the phone settings — and a TV never initiates sync in
// the first place. Until that lands the hub is a rendezvous between parents,
// not a server for the televisions. See "Next up" in docs/FORK-NOTES.md.
//
// A household that never runs this sees no change at all. That is a hard rule:
// every route here already exists, and nothing in :app may come to depend on
// the hub being present.

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.yosemitekids.hub.MainKt")
}

dependencies {
    // The merge, the stamper, the serializers — shared verbatim with the app so
    // there is one implementation of the rules rather than two that drift.
    implementation(project(":core"))
    // The crawler and the search index: the hub builds the index the phone
    // used to build alone (docs/PLAN-crawl.md).
    implementation(project(":crawl"))
    // Android supplies org.json in the platform; here it has to be brought.
    implementation(libs.json)

    testImplementation(libs.json)
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
