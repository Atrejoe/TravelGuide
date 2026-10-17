// Plugins are applied per module. Loading the Kotlin plugin here would put it in a parent
// classloader that cannot see the Android Gradle plugin, which is only resolved when the app
// module is included (see settings.gradle.kts).
