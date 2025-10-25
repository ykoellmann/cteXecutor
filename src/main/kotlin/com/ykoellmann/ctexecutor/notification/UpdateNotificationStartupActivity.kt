package com.ykoellmann.ctexecutor.notification

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class UpdateNotificationStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        UpdateNotificationService.showUpdateNotificationIfNeeded(project)
    }
}