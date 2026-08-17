import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kim)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.uiTestJunit4)
}

kotlin { jvmToolchain(21) }
tasks.withType<Test>().configureEach { systemProperty("java.awt.headless", "true") }

tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Synchronize and verify a copied library without opening a window"
    dependsOn(tasks.named("classes"))
    mainClass.set("br.com.lincon.phototool.desktop.MainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    doFirst {
        val requested = args.orEmpty().filterNot { it == "--smoke" || it == "--read-only" }
        setArgs(listOf("--smoke", "--read-only") + requested)
    }
}

compose.desktop {
    application {
        mainClass = "br.com.lincon.phototool.desktop.MainKt"
        jvmArgs += listOf("-Xmx1024M", "-Djava.awt.headless=false")
        nativeDistributions {
            modules("java.sql")
            targetFormats(TargetFormat.Deb)
            packageName = "phototool-kmp"
            packageVersion = providers.gradleProperty("app.versionName").get()
            description = "Native desktop photo curation"
            vendor = "Lincon Vidal"
            linux {
                iconFile.set(project.file("src/main/resources/icons/phototool-app-icon.png"))
            }
        }
    }
}
