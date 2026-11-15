plugins { id("com.android.library") }
// Terminal emulation engine from Terminal Emulator for Android / Termux (Apache License 2.0, see NOTICE).
android {
    namespace = "com.termux.emulator"
    compileSdk = 35
    // Java 8 language level like upstream; also avoids needing jlink from the JDK.
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    defaultConfig {
        minSdk = 29
    }
    // libtermux.so (src/main/jni/termux.c) is built by core/build-android.sh into the app's jniLibs:
    // ndk-build can't cope with spaces in the project path.
}
dependencies { implementation("androidx.annotation:annotation:1.8.2") }

tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(project.extensions.getByType<JavaToolchainService>().compilerFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}
