package dev.semcode.intellij.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.semcode.intellij.settings.SemcodeSettings
import dev.semcode.intellij.settings.semcodeExe
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * semcode 进程门面：索引走 CLI 两步链（CWD=工程根 + `-s .`，dev/02 约束 1、3），
 * 查询走常驻 semcode-mcp JSON-RPC 子进程（库位置 .idea/semcode.db）。
 */
@Service(Service.Level.PROJECT)
class SemcodeProcessService(private val project: Project) : Disposable {

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

    fun projectRoot(): File? = project.basePath?.let { File(it) }

    /** 索引库目录：<project>/.idea/semcode.db（含旧位置迁移）。 */
    fun databaseDir(): File = SemcodeProjectStore.databaseDir(project)

    /**
     * 链式跑两步索引。每步输出行回调 [onLine]（进度展示），全部成功返回 true。
     */
    fun runIndexChain(onLine: (String) -> Unit): Boolean {
        val root = projectRoot() ?: run {
            onLine("ERROR: project has no base path")
            return false
        }
        val bins = resolveBinaries() ?: run {
            onLine("ERROR: semcode binaries not found — set them in Settings | Tools | Semcode")
            return false
        }
        val db = databaseDir()
        // semcode 默认扩展只有 c,h,rs,zig；C/C++ 全家（cpp/hpp/cc…）、py、java、ets 需显式开。
        // TODO(v1.4): 改为从 semcode 查询支持列表（数据驱动，加语言时零改动）
        val extensions = "c,h,cc,cpp,cxx,c++,hh,hpp,hxx,h++,rs,py,zig,java,ets"
        val steps = listOf(
            "semcode-index (source)" to listOf(
                bins.index.absolutePath, "-s", ".", "-d", db.absolutePath, "-e", extensions,
            ),
            "semcode-index (vectors)" to listOf(
                bins.index.absolutePath, "-s", ".", "-d", db.absolutePath, "--vectors",
            ),
        )
        for ((label, command) in steps) {
            onLine(">>> $label")
            val ok = runProcess(command, root) { line -> onLine(line) }
            if (!ok) {
                onLine("ERROR: step failed: $label")
                return false
            }
        }
        return true
    }

    /** 查询用常驻 MCP 子进程（懒启动，进程死亡自动重启）。 */
    fun mcpClient(): SemcodeMcpClient? {
        val root = projectRoot() ?: return null
        val bins = resolveBinaries() ?: return null
        if (client == null) {
            val db = databaseDir()
            client = SemcodeMcpClient(
                listOf(
                    bins.mcp.absolutePath,
                    "-d", db.absolutePath,
                    "--git-repo", root.absolutePath,
                    "--lazy",
                ),
                root,
            )
        }
        return client
    }

    private var client: SemcodeMcpClient? = null

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

    override fun dispose() {
        client?.teardown()
        client = null
    }

    companion object {
        fun getInstance(project: Project): SemcodeProcessService =
            project.getService(SemcodeProcessService::class.java)
    }
}
