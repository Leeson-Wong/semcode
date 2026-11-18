package dev.semcode.intellij.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * agent 自动接线（用户 2026-11-16 拍板零配置）：索引就绪后把 semcode 的
 * MCP server 条目合并进项目 .mcp.json，Claude Code 打开项目即用，路径全部
 * 由插件生成（指向解析出的 semcode-mcp 与 .idea/semcode.db）。
 *
 * 合并语义：已存在的其他 server 条目原样保留；semcode 条目每次覆盖为最新
 * 路径（二进制升级/项目移动后自动修正）。文件不存在则新建。
 */
object SemcodeAgentConfig {
    private val log = Logger.getInstance(SemcodeAgentConfig::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** @return 写入/更新了条目返回 true（调用方据此提示一次）。 */
    fun ensure(project: Project, service: SemcodeProcessService): Boolean {
        val root = project.basePath ?: return false
        val bins = service.resolveBinaries() ?: return false
        // 工作区项目写 --workspace（一个 server 管全部仓库）；单仓写 -d/--git-repo。
        val args = if (service.workspaceRepos().isNotEmpty()) {
            listOf("--workspace", root)
        } else {
            listOf("-d", service.databaseDir().absolutePath, "--git-repo", root)
        }
        val entry = JsonObject().apply {
            addProperty("command", bins.mcp.absolutePath)
            add(
                "args",
                com.google.gson.JsonArray().apply { args.forEach { add(it) } },
            )
            // Claude Code 拉起的实例不经插件，资源约束必须随条目走：
            // 限核 + 低优先级（2026-11-18 实测：无 env 的实例在空库上
            // 触发后台自动索引，回到全核裸跑）。
            add(
                "env",
                JsonObject().apply {
                    addProperty("SEMCODE_WORKERS", service.workerLimit().toString())
                    addProperty("SEMCODE_LOW_PRIORITY", "1")
                },
            )
        }
        return try {
            val file = File(root, ".mcp.json")
            val root2 = if (file.isFile) {
                JsonParser.parseString(file.readText()).takeIf { it.isJsonObject }?.asJsonObject
                    ?: JsonObject()
            } else {
                JsonObject()
            }
            val servers = root2.getAsJsonObject("mcpServers") ?: JsonObject().also { root2.add("mcpServers", it) }
            if (servers.get("semcode") == entry) {
                return false
            }
            servers.add("semcode", entry)
            file.writeText(gson.toJson(root2) + "\n")
            true
        } catch (e: Exception) {
            log.warn("failed to write .mcp.json", e)
            false
        }
    }
}
