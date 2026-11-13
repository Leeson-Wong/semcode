package dev.semcode.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.semcode.intellij.core.SemcodeProcessService

/**
 * Tools | Index Project with Semcode
 * 后台链式执行 semcode-index（源码）→ semcode-index --vectors（向量）。
 * 集成约束：CWD=工程根 + `-s .`（dev/02-deveco-plugin.md 约束 1、3）。
 */
class IndexProjectAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = project.basePath ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Semcode indexing", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val service = SemcodeProcessService.getInstance(project)
                val lines = java.util.Collections.synchronizedList(mutableListOf<String>())

                val ok = service.runIndexChain(java.io.File(root)) { line ->
                    lines.add(line)
                    // 进度文本滚动显示最近一行输出
                    indicator.text = line.take(120)
                }

                if (ok) {
                    indicator.text = "Semcode indexing complete"
                    notify(project, true, "Indexing complete (${lines.size} output lines)")
                } else {
                    notify(project, false, "Indexing failed — see idea.log for details. " +
                        "Last output: ${lines.lastOrNull { !it.startsWith(">>>") } ?: "none"}")
                }
            }
        })
    }

    private fun notify(project: Project, success: Boolean, message: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val notification = com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("Semcode")
                .createNotification(
                    "Semcode",
                    message,
                    if (success) com.intellij.notification.NotificationType.INFORMATION
                    else com.intellij.notification.NotificationType.ERROR
                )
            com.intellij.notification.Notifications.Bus.notify(notification, project)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
