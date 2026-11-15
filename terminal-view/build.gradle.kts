plugins { id("com.android.library") }
// Terminal view from Terminal Emulator for Android / Termux (Apache License 2.0, see NOTICE).
android {
    namespace = "com.termux.view"
    compileSdk = 35
    // Java 8 language level like upstream; also avoids needing jlink from the JDK.
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    defaultConfig { minSdk = 29 }
}
dependencies {
    implementation("androidx.annotation:annotation:1.8.2")
    api(project(":terminal-emulator"))
}

tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(project.extensions.getByType<JavaToolchainService>().compilerFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}
