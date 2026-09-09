version = 2

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

dependencies {
    implementation("com.dylibso.chicory:runtime:1.0.0")
    implementation("com.dylibso.chicory:wasm:1.0.0")
}

