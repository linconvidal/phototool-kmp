import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kim)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.uiTestJunit4)
    testImplementation(libs.ktor.server.test.host)
}

kotlin { jvmToolchain(21) }

// One production UI: package the same Compose app compiled to Wasm, never the legacy HTML client.
tasks.processResources {
    dependsOn(":webApp:wasmJsBrowserDistribution")
    exclude("web/**")
    from(project(":webApp").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) {
        into("compose-web")
    }
}
tasks.withType<Test>().configureEach { systemProperty("java.awt.headless", "true") }

tasks.register<JavaExec>("composeWebCandidate") {
    group = "verification"
    description = "Serve shared Compose Web on loopback with a new copied synthetic fixture; no sync or writes"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("br.com.lincon.phototool.desktop.ComposeWebCandidate")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("java.awt.headless", "true")
}

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
