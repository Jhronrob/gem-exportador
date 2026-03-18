plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

kotlin {
    jvm()
    sourceSets {
        val jvmMain by getting  {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":shared"))
                implementation(project(":server"))  // Servidor embutido
                implementation("net.java.dev.jna:jna-platform:5.14.0")
                implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
            }
        }
    }
}

val appVersion: String by project.extra { project.property("app.version").toString() }
val appName: String by project.extra { project.property("app.name").toString() }

compose.desktop {
    application {
        mainClass = "MainKt"

        // Habilita hot reload para desenvolvimento
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")

        // Passa env file customizado via propriedade Gradle: -PgemDesktopEnvFile=.env.desktop-viewer
        val gemDesktopEnvFile = project.findProperty("gemDesktopEnvFile")?.toString()
        if (gemDesktopEnvFile != null) {
            jvmArgs("-DGemDesktopEnvFile=$gemDesktopEnvFile")
        }

        nativeDistributions {
            packageName = appName
            packageVersion = appVersion
            description = "Exportador de desenhos Autodesk Inventor"
            vendor = "JhonRob"
            includeAllModules = true

            windows {
                iconFile.set(project.file("src/jvmMain/resources/favicon.ico"))
                menuGroup = appName
                upgradeUuid = "8F3B1A2C-5D4E-6F7A-8B9C-0D1E2F3A4B5C"
                shortcut = true
                dirChooser = true
                menu = true
            }
        }
    }
}

// Task para gerar arquivo de versão acessível em runtime
tasks.register("generateVersionFile") {
    doLast {
        val versionFile = file("src/jvmMain/resources/version.txt")
        versionFile.parentFile.mkdirs()
        versionFile.writeText(appVersion)
    }
}

tasks.named("jvmProcessResources") {
    dependsOn("generateVersionFile")
}

// Injeta javaw.exe do JDK no runtime do app (jlink remove com --strip-native-commands)
tasks.register<Copy>("injectJavaw") {
    dependsOn("createDistributable")
    val javaHome = System.getProperty("java.home")
    from(file("$javaHome/bin/javaw.exe"))
    into(file("build/compose/binaries/main/app/$appName/runtime/bin"))
}

// Constroi instalador NSIS
tasks.register<Exec>("buildNsisInstaller") {
    dependsOn("injectJavaw")
    val nsisScript = file("installer.nsi")
    val appDir = file("build/compose/binaries/main/app/$appName")
    val outputDir = file("build/compose/binaries/main/nsis")
    val iconFile = file("src/jvmMain/resources/favicon.ico")

    doFirst {
        outputDir.mkdirs()
    }

    // Procura makensis.exe no PATH ou em locais comuns
    val nsisLocations = listOf(
        "C:\\Program Files (x86)\\NSIS\\makensis.exe",
        "C:\\Program Files\\NSIS\\makensis.exe"
    )
    val makensisPath = nsisLocations.firstOrNull { File(it).exists() } ?: "makensis"

    commandLine(
        makensisPath,
        "/DAPP_VERSION=$appVersion",
        "/DAPP_NAME=$appName",
        "/DAPP_DIR=${appDir.absolutePath}",
        "/DOUTPUT_DIR=${outputDir.absolutePath}",
        "/DICON_FILE=${iconFile.absolutePath}",
        nsisScript.absolutePath
    )
}
