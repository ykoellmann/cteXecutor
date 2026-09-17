plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("org.jetbrains.changelog") version "2.2.1"
}

group = "com.ykoellmann.ctexecutor"

// Read version from PluginInfo.kt
version = file("src/main/kotlin/com/ykoellmann/ctexecutor/PluginInfo.kt")
    .readText()
    .let { content ->
        """const\s+val\s+VERSION\s*=\s*"([^"]+)"""".toRegex()
            .find(content)?.groupValues?.get(1) ?: "1.0.0"
    }

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("DB", "2026.1.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.database")
        // bundledPlugin() allein reicht fuer den `test`-Task nicht: die IntelliJ-Platform-Gradle-Plugin-
        // Test-Sandbox laedt Bundled Plugins nur, wenn sie zusaetzlich ueber testBundledPlugin() als
        // Test-Abhaengigkeit deklariert sind. Ohne das registriert FileTypeManager in BasePlatformTestCase
        // keinen SQL-FileType, .sql-Testdateien werden als PlainTextFileType geparst (kein echtes SQL-PSI).
        // com.intellij.database haengt selbst von com.intellij.modules.json (Modul intellij.json.backend)
        // ab - ohne dessen Test-Bundling meldet der PluginManager "has module dependency
        // 'intellij.json.backend' which cannot be loaded or missing" und ueberspringt das DB-Plugin komplett.
        testBundledPlugin("com.intellij.database")
        testBundledPlugin("com.intellij.modules.json")
    }
    // TestFrameworkType.Platform bringt das Platform-Test-Framework mit, aber nicht JUnit selbst.
    // BasePlatformTestCase/UsefulTestCase erben von junit.framework.TestCase (JUnit 3 API) -
    // ohne diese Zeile fehlt das auf dem Testklassenpfad ("Cannot access junit.framework.TestCase").
    testImplementation("junit:junit:4.13.2")
}

changelog {
    version = project.version.toString()
    groups = listOf("feat", "fix", "break")
    repositoryUrl = "https://github.com/ykoellmann/ctexecutor"
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "252"
        }

        changeNotes = provider {
            changelog.renderItem(
                changelog.getOrNull(project.version.toString()) ?: changelog.getUnreleased(),
                org.jetbrains.changelog.Changelog.OutputType.HTML
            )
        }

        description = providers.fileContents(
            layout.projectDirectory.file("src/main/kotlin/com/ykoellmann/ctexecutor/PluginInfo.kt")
        ).asText.map { content ->
            val descriptionPattern = """const\s+val\s+DESCRIPTION\s*=\s*"{3}([\s\S]*?)"{3}""".toRegex()
            descriptionPattern.find(content)?.groupValues?.get(1)?.trim() ?: """
                Execute and manage Common Table Expressions (CTEs) easily in DataGrip and IntelliJ-based IDEs.
                Highlights CTEs and allows selective execution of composed queries.
                Efficient, intuitive, and developer-friendly.
            """.trimIndent()
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    signPlugin {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }

    publishPlugin {
        token = System.getenv("PUBLISH_TOKEN")
    }
}