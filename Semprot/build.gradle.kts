version = 4

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    authors     = listOf("dhamarar")
    language    = "id"
    description = "Semprot - Akses video sub-forum IGO dan Live Show (memerlukan login akun di Settings plugin)"
    status      = 4 // Beta
    tvTypes     = listOf("NSFW")
    iconUrl     = "https://t0.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://www.semprot.com/&size=128"
}
