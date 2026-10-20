extension {
    name = "extensions/speaktochat.rve"
}

android {
    namespace = "app.revanced.extension.sony.soundconnect"
}

// The extension is plain Java. AGP adds the Kotlin standard library by default, and merging it
// into the app would mix it with the app's own copy, so keep it out of the extension DEX.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains", module = "annotations")
}
