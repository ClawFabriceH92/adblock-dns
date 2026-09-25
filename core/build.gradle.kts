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
    // Client HTTP/2 pour DNS-over-HTTPS : Quad9 n'accepte plus le HTTP/1.1 depuis le 15/12/2025.
    implementation(libs.okhttp)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}

tasks.test {
    // Les tests qui téléchargent de vraies listes ou interrogent de vrais résolveurs DoH
    // ne tournent que sur demande : ./gradlew :core:test -PnetworkTests=true
    systemProperty("networkTests", providers.gradleProperty("networkTests").getOrElse("false"))
    testLogging {
        // Messages d'erreur complets et sorties des tests réseau lisibles dans le journal de la CI.
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
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
