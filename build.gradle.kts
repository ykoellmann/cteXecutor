plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.ykoellmann.ctexecutor"

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

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "242"
        }

        // Change notes will be read from PluginInfo.kt at task execution time
        changeNotes = providers.fileContents(
            layout.projectDirectory.file("src/main/kotlin/com/ykoellmann/ctexecutor/PluginInfo.kt")
        ).asText.map { content ->
            val changeNotesPattern = """const\s+val\s+CHANGE_NOTES\s*=\s*"{3}([\s\S]*?)"{3}""".toRegex()
            changeNotesPattern.find(content)?.groupValues?.get(1)?.trim() ?: ""
        }

        // Description will be read from PluginInfo.kt at task execution time
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
}