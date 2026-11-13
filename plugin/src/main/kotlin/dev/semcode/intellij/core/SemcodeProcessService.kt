package dev.semcode.intellij.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.semcode.intellij.settings.SemcodeSettings
import dev.semcode.intellij.settings.semcodeExe
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * semcode 进程门面。
 *
 * 集成约束（来源 dev/02-deveco-plugin.md，Windows 实测）：
 * 1. 索引器必须以工程根为 CWD、`-s .` 调用（相对路径 scope bug 绕开）
 * 2. 查询必须显式 `--git-repo <projectRoot>`
 * 3. 索引严格两步：`semcode-index -s .` 后 `semcode-index -s . --vectors`
 */
@Service(Service.Level.PROJECT)
class SemcodeProcessService(private val project: Project) {

    private val log = Logger.getInstance(SemcodeProcessService::class.java)

    data class Binaries(val index: File, val query: File, val mcp: File)

    fun resolveBinaries(): Binaries? {
        val settings = SemcodeSettings.getInstance()
        val dir = File(settings.binariesDir)
        if (!hasAll(dir)) {
            return null
        }
        return Binaries(semcodeExe(dir, "semcode-index"), semcodeExe(dir, "semcode"), semcodeExe(dir, "semcode-mcp"))
    }

    private fun hasAll(dir: File): Boolean =
        semcodeExe(dir, "semcode-index").isFile &&
            semcodeExe(dir, "semcode").isFile &&
            semcodeExe(dir, "semcode-mcp").isFile

    /**
     * 链式跑两步索引。每步输出行回调 [onLine]（进度展示），全部成功返回 true。
     */
    fun runIndexChain(projectRoot: File, onLine: (String) -> Unit): Boolean {
        val bins = resolveBinaries() ?: run {
            onLine("ERROR: semcode binaries not found — set them in Settings | Tools | Semcode")
            return false
        }
        // semcode 默认扩展只有 c,h,rs,zig；C/C++ 全家（cpp/hpp/cc…）与 py 需显式开。
        // TODO(1.3): 改为从 semcode 查询支持列表（数据驱动，ArkTS 接入时零改动）
        val extensions = "c,h,cc,cpp,cxx,c++,hh,hpp,hxx,h++,rs,py,zig"
        val steps = listOf(
            "semcode-index (source)" to listOf(bins.index.absolutePath, "-s", ".", "-e", extensions),
            "semcode-index (vectors)" to listOf(bins.index.absolutePath, "-s", ".", "--vectors"),
        )
        for ((label, command) in steps) {
            onLine(">>> $label")
            val ok = runProcess(command, projectRoot) { line -> onLine(line) }
            if (!ok) {
                onLine("ERROR: step failed: $label")
                return false
            }
        }
        return true
    }

    /**
     * 单次查询（脚手架版：`semcode -q "<cmd>"` 文本输出）。
     * 1.2 阶段替换为 semcode-mcp JSON-RPC 客户端。
     */
    fun runQuery(projectRoot: File, queryCommand: String, onLine: (String) -> Unit): Boolean {
        val bins = resolveBinaries() ?: run {
            onLine("ERROR: semcode binaries not found")
            return false
        }
        val modelDir = SemcodeSettings.getInstance().modelDir
        val command = buildList {
            add(bins.query.absolutePath)
            add("--database")
            add(projectRoot.absolutePath)
            add("--git-repo")
            add(projectRoot.absolutePath)
            if (modelDir.isNotBlank()) {
                add("--model-path")
                add(modelDir)
            }
            add("-q")
            add(queryCommand)
        }
        return runProcess(command, projectRoot, onLine)
    }

    private fun runProcess(command: List<String>, workDir: File, onLine: (String) -> Unit): Boolean {
        return try {
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    output.appendLine(line)
                    onLine(line)
                }
            }
            val code = process.waitFor()
            if (code != 0) {
                log.warn("process exited $code: ${command.first()} — last output: ${output.takeLast(500)}")
            }
            code == 0
        } catch (e: Exception) {
            log.warn("failed to run ${command.first()}", e)
            onLine("ERROR: ${e.message}")
            false
        }
    }

    companion object {
        fun getInstance(project: Project): SemcodeProcessService =
            project.getService(SemcodeProcessService::class.java)
    }
}
