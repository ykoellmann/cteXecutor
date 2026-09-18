package com.ykoellmann.ctexecutor.notification

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.ykoellmann.ctexecutor.PluginInfo
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@State(
    name = "CteExecutorUpdateState",
    storages = [Storage("cteExecutorPlugin.xml")]
)
class UpdateNotificationService : PersistentStateComponent<UpdateNotificationService.State> {

    data class State(
        var lastShownVersion: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): UpdateNotificationService {
            return service()
        }

        fun showUpdateNotificationIfNeeded(project: Project?) {
            val service = getInstance()

            // Nur anzeigen, wenn die Notification-Version noch nicht angezeigt wurde
            if (service.state.lastShownVersion != PluginInfo.NOTIFICATION_VERSION) {
                showUpdateNotification(project)
                service.state.lastShownVersion = PluginInfo.NOTIFICATION_VERSION
            }
        }

        private fun showUpdateNotification(project: Project?) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("cteExecutor.notifications")
                .createNotification(PluginInfo.NOTIFICATION_CONTENT, NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimple("Rate cteXecutor on the Marketplace") {
                    BrowserUtil.browse(PluginInfo.MARKETPLACE_REVIEWS_URL)
                })
                .notify(project)
        }
    }
}