pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
// Lets Gradle download a JDK for the two Java terminal libraries when the system Java has no compiler.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Ripple"
include(":ripple", ":shared", ":terminal-emulator", ":terminal-view")
