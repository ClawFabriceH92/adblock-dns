// Tous les plugins sont déclarés ici (apply false) pour que chaque module partage le même
// classpath de build, comme le recommande le projet nowinandroid.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
