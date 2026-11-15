package dev.semcode.intellij.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 数据库位置策略：一律 `<project>/.idea/semcode.db`（随工程走、天然被
ignore），并承担旧位置 `<project>/.semcode.db` 的一次性迁移。
 */
object SemcodeProjectStore {

    fun databaseDir(project: Project): java.io.File {
        val root = java.io.File(project.basePath ?: return legacyDir(project))
        val ideaDir = java.io.File(root, ".idea")
        if (!ideaDir.isDirectory) return legacyDir(project)

        val target = java.io.File(ideaDir, "semcode.db")
        migrateLegacy(root, target, project)
        return target
    }

    private fun legacyDir(project: Project): java.io.File =
        java.io.File(java.io.File(project.basePath ?: "."), ".semcode.db")

    private fun migrateLegacy(root: java.io.File, target: java.io.File, project: Project) {
        if (target.exists()) return
        val legacy = java.io.File(root, ".semcode.db")
        if (!legacy.isDirectory) return
        try {
            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Semcode")
                .createNotification(
                    "Semcode",
                    "Index moved to .idea/semcode.db (was .semcode.db in the project root).",
                    NotificationType.INFORMATION,
                )
                .notify(project)
        } catch (e: Exception) {
            // 迁移失败不阻塞：继续用空库，用户可重新索引
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Semcode")
                .createNotification(
                    "Semcode",
                    "Could not move legacy index .semcode.db into .idea: ${e.message}. " +
                        "Re-index the project to build a fresh index.",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
    }
}
