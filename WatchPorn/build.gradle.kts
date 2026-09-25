version = 1

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    authors = listOf("kraptor", "dhamarar")
    language = "en"
    description = "WatchPorn - High quality porn streams from major sites and categories."
    status = 2
    tvTypes = listOf(
        "NSFW"
    )
    iconUrl = "https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://watchporn.to/&size=32"
}
