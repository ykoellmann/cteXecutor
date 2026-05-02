plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.3.0"
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
        create("DB", "2025.1.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.database")
    }
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
            sinceBuild = "243"
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

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    signPlugin {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }

    publishPlugin {
        token = System.getenv("PUBLISH_TOKEN")
    }
}