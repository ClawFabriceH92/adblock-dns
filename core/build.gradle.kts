import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Moteur de filtrage en Kotlin pur : aucune dépendance Android, testable sur n'importe quelle JVM.
// N'utiliser ici que des API Java disponibles sur Android 10 (API 29), minSdk de l'application.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}

tasks.test {
    // Les tests qui téléchargent de vraies listes ou interrogent de vrais résolveurs DoH
    // ne tournent que sur demande : ./gradlew :core:test -PnetworkTests=true
    systemProperty("networkTests", providers.gradleProperty("networkTests").getOrElse("false"))
}

// Écrit le classpath de test dans un fichier : utilisé par tools/test_tun_linux.py qui lance
// le banc d'essai TUN directement avec « java » (le descripteur TUN est son entrée/sortie standard).
tasks.register("writeTestClasspath") {
    val classpath = sourceSets.test.get().runtimeClasspath
    val output = layout.buildDirectory.file("test-classpath.txt")
    inputs.files(classpath)
    outputs.file(output)
    doLast {
        output.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator))
    }
}
