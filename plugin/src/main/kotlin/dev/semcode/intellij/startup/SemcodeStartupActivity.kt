package dev.semcode.intellij.startup

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.semcode.intellij.core.SemcodeAgentConfig
import dev.semcode.intellij.core.SemcodeProcessService

/**
 * 打开项目即就绪（用户 2026-11-16 拍板默认自动索引，面板有可视化）：
 * 库不存在 → 后台自动跑索引链（进度进 ToolWindow 共享日志，通知栏可感知）；
 * 库已存在 → 只做 agent 接线兜底（.mcp.json 幂等合并）。
 * 手动入口保留：Tools 菜单与工具窗 Rebuild。
 */
class SemcodeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = SemcodeProcessService.getInstance(project)
        if (service.resolveBinaries() == null) {
            return
        }
        if (service.databaseDir().isDirectory && service.databaseDir().resolve("_versions").isDirectory) {
            // 库在：保证 agent 接线最新；捆绑的嵌入模型若换代（0.7.0 换过一次），
            // 旧向量在新空间里是噪声，只补跑向量步骤自愈。
            SemcodeAgentConfig.ensure(project, service)
            ApplicationManager.getApplication().invokeLater {
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Semcode vectors refresh", true) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        service.refreshVectorsIfModelChanged { line -> indicator.text = line.take(120) }
                    }
                })
            }
            return
        }
        ApplicationManager.getApplication().invokeLater {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Semcode indexing", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val ok = service.runIndexChain { line -> indicator.text = line.take(120) }
                    if (ok) {
                        notify(project, "Semcode index ready — search in the Semcode tool window; Claude Code entry written to .mcp.json")
                    }
                }
            })
        }
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Semcode")
            .createNotification("Semcode", message, NotificationType.INFORMATION)
            .notify(project)
    }
}
