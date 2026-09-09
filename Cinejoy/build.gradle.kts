version = 4

cloudstream {
    description = "Cinejoy - Watch Free Movies & TV Shows"
    language = "en"
    authors = listOf("Cinejoy")
    status = 2
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
}

val chicoryConfig by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation("com.dylibso.chicory:runtime:1.0.0")
    implementation("com.dylibso.chicory:wasm:1.0.0")
    chicoryConfig("com.dylibso.chicory:runtime:1.0.0")
    chicoryConfig("com.dylibso.chicory:wasm:1.0.0")
}

val extractDependencies by tasks.registering(Copy::class) {
    dependsOn("compileDebugKotlin")
    from(chicoryConfig.map { zipTree(it) })
    into(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("compileDex") {
    dependsOn(extractDependencies)
}



