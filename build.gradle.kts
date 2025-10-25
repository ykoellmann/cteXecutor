plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.ykoellmann.ctexecutor"
version = "2.0.0"

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

// Read version and change notes from PluginInfo.kt
fun readPluginInfo(): Map<String, String> {
    val pluginInfoFile = file("src/main/kotlin/com/ykoellmann/ctexecutor/PluginInfo.kt")
    if (!pluginInfoFile.exists()) {
        return mapOf("version" to version.toString(), "changeNotes" to "", "description" to "")
    }

    val content = pluginInfoFile.readText()

    val versionPattern = """const\s+val\s+VERSION\s*=\s*"([^"]+)"""".toRegex()
    val extractedVersion = versionPattern.find(content)?.groupValues?.get(1) ?: version.toString()

    val changeNotesPattern = """const\s+val\s+CHANGE_NOTES\s*=\s*"{3}([\s\S]*?)"{3}""".toRegex()
    val changeNotes = changeNotesPattern.find(content)?.groupValues?.get(1)?.trim() ?: ""

    val descriptionPattern = """const\s+val\s+DESCRIPTION\s*=\s*"{3}([\s\S]*?)"{3}""".toRegex()
    val description = descriptionPattern.find(content)?.groupValues?.get(1)?.trim() ?: ""

    return mapOf(
        "version" to extractedVersion,
        "changeNotes" to changeNotes,
        "description" to description
    )
}

val pluginInfo = readPluginInfo()

intellijPlatform {
    pluginConfiguration {
        version = pluginInfo["version"]

        ideaVersion {
            sinceBuild = "242"
        }

        // Automatically patch change notes from PluginInfo.kt
        changeNotes = pluginInfo["changeNotes"] ?: ""

        // Automatically patch description from PluginInfo.kt
        description = pluginInfo["description"] ?: """
            Execute and manage Common Table Expressions (CTEs) easily in DataGrip and IntelliJ-based IDEs.
            Highlights CTEs and allows selective execution of composed queries.
            Efficient, intuitive, and developer-friendly.
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }

    // Print info when building
    named("patchPluginXml") {
        doFirst {
            println("📦 Building cteXecutor ${pluginInfo["version"]}")
            println("   Version will be automatically patched into plugin.xml")
        }
    }
}