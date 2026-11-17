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

    /** 插件只需要索引器与 MCP server；交互式 semcode.exe 是纯 CLI，不捆不查。 */
    data class Binaries(val index: File, val mcp: File)

    /**
     * 二进制解析链：Settings 里的目录（开发期覆盖，指向 target/release）
     * → 插件捆绑的 <plugin>/bin/（0.6.0 起随 zip 分发，外部用户零配置）。
     */
    fun resolveBinaries(): Binaries? {
        val settings = SemcodeSettings.getInstance()
        if (settings.binariesDir.isNotBlank()) {
            val dir = File(settings.binariesDir)
            if (hasAll(dir)) {
                return Binaries(
                    semcodeExe(dir, "semcode-index"),
                    semcodeExe(dir, "semcode-mcp"),
                )
            }
        }
        val bundled = bundledBinariesDir() ?: return null
        return Binaries(
            semcodeExe(bundled, "semcode-index"),
            semcodeExe(bundled, "semcode-mcp"),
        )
    }

    private fun hasAll(dir: File): Boolean =
        semcodeExe(dir, "semcode-index").isFile &&
            semcodeExe(dir, "semcode-mcp").isFile

    /**
     * zip 内 semcode-intellij/bin/。插件根见 [pluginDir]——protectionDomain
     * 在 IDEA 2026.3 的类加载器下定位不到插件目录（2026-11-17 实测报
     * binaries not found），DevEco 平台又未暴露 PluginDescriptor，资源
     * 定位是唯一两条路都走得通的。
     */
    private fun bundledBinariesDir(): File? =
        pluginDir()?.resolve("bin")?.toFile()?.takeIf { hasAll(it) }

    /**
     * 随插件 zip 分发的 model2vec 目录（zip 内 semcode-intellij/model/）。
     * 存在即用——向量步骤和 MCP 都拿它当 --model-path，用户零配置；
     * 缺失（开发期沙箱未带模型）返回 null，semcode 回退自己的缓存目录。
     */
    fun bundledModelDir(): File? =
        pluginDir()?.resolve("model")?.toFile()?.takeIf { File(it, "model.safetensors").isFile }

    /**
     * 插件安装根 <plugins>/semcode-intellij/。通过类加载器解析本插件 jar
     * 内的 plugin.xml 资源反推——资源 URL 对任何类加载器（IDEA 2026.3、
     * DevEco 243、沙箱 runIde）都成立，jar 再上一级就是插件根。
     */
    private fun pluginDir(): java.nio.file.Path? {
        val res = javaClass.classLoader.getResource("META-INF/plugin.xml") ?: return null
        // 形如 jar:file:/E:/.../lib/semcode-intellij.jar!/META-INF/plugin.xml
        val jar = runCatching {
            File(java.net.URI(res.file.substringBefore('!')))
        }.getOrNull() ?: return null
        // jar 位于 <plugin>/lib/ 下；确认父目录确有 lib，避免误判到别处
        return jar.parentFile?.parentFile?.toPath()?.takeIf { root ->
            root.resolve("lib").toFile().isDirectory
        }
    }

    fun projectRoot(): File? = project.basePath?.let { File(it) }

    /** 索引库目录：<project>/.idea/semcode.db（含旧位置迁移）。 */
    fun databaseDir(): File = SemcodeProjectStore.databaseDir(project)

    /** 索引运行态与最近输出（面板可视化 + 启动自动索引共用一份）。 */
    @Volatile
    var indexRunning: Boolean = false
        private set

    val indexLog = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val indexListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** 订阅索引开始/结束（true=开始）。ToolWindow 据此切换进度展示。 */
    fun addIndexListener(listener: (Boolean) -> Unit) {
        indexListeners.add(listener)
    }

    /**
     * 链式跑两步索引。每步输出行回调 [onLine]（进度展示）并进入共享日志，
     * 全部成功返回 true。重入保护：已在跑则拒绝并返回 false。
     */
    fun runIndexChain(onLine: (String) -> Unit): Boolean {
        if (indexRunning) {
            onLine("ERROR: indexing already in progress")
            return false
        }
        indexRunning = true
        indexLog.clear()
        indexListeners.forEach { it(true) }
        try {
            val ok = runIndexChainInner(onLine)
            if (ok) {
                SemcodeAgentConfig.ensure(project, this)
            }
            return ok
        } finally {
            indexRunning = false
            indexListeners.forEach { it(false) }
        }
    }

    /**
     * 工作区检测（0.9.0）：项目根本身不是 git 仓库但其下（最多三层，
     * 与 semcode-mcp --workspace 的扫描规则一致：跳过 .git/.idea/
     * node_modules/build/target）有 git 仓库 → 按工作区模式逐仓索引，
     * MCP 以 --workspace 启动。单仓项目返回空表。
     */
    @Volatile
    private var cachedWorkspaceRepos: List<File>? = null

    fun workspaceRepos(): List<File> {
        cachedWorkspaceRepos?.let { return it }
        val root = projectRoot() ?: return emptyList<File>().also { cachedWorkspaceRepos = it }
        if (File(root, ".git").exists()) {
            cachedWorkspaceRepos = emptyList()
            return cachedWorkspaceRepos!!
        }
        val out = mutableListOf<File>()
        fun scan(dir: File, depth: Int) {
            val entries = dir.listFiles() ?: return
            for (e in entries) {
                if (File(e, ".git").exists()) {
                    out.add(e)
                } else if (depth > 0 && e.isDirectory &&
                    e.name !in setOf(".git", ".idea", "node_modules", "build", "target")
                ) {
                    scan(e, depth - 1)
                }
            }
        }
        scan(root, 2)
        out.sortBy { it.name }
        cachedWorkspaceRepos = out
        return out
    }

    /** 工作区各仓的库根：<project>/.idea/semcode.dbs/（每仓一目录，内含 .semcode.db）。 */
    fun workspaceDbRoot(): File? =
        projectRoot()?.let { File(it, ".idea/semcode.dbs") }

    private fun runIndexChainInner(onLine: (String) -> Unit): Boolean {
        val root = projectRoot() ?: run {
            onLine("ERROR: project has no base path")
            return false
        }
        // 工作区模式：根不是 git 但其下有仓库（如 E:\C42）→ 逐仓索引，
        // 库放 .idea/semcode.dbs/<repo>/，与 semcode-mcp --workspace 的
        // 约定一致。
        val repos = workspaceRepos()
        if (repos.isNotEmpty()) {
            return runWorkspaceIndex(repos, onLine)
        }
        // semcode 按 git commit 索引，非 git 项目必然失败（2026-11-17 实测，
        // 索引器的原始报错在通知里只剩 "step failed"）。在这里说明，而不是
        // 让 exe 跑一遍再失败——Rebuild 按钮、菜单、自动索引都走这条链。
        // 常见情形：把装着多个仓库的工作区文件夹当项目打开（如 E:\C42），
        // 列出里面的仓库指路；在外层 git init 只会得到看不见嵌套仓库的空壳。
        if (!File(root, ".git").exists()) {
            onLine("ERROR: this project is not a git repository — semcode indexes by git commit.")
            val nested = root.listFiles()
                ?.filter { it.isDirectory && File(it, ".git").exists() }
                ?.take(5)
                .orEmpty()
            if (nested.isNotEmpty()) {
                onLine("       This folder contains git repositories — open one of them as the project:")
                nested.forEach { onLine("         ${it.absolutePath}") }
            } else {
                onLine("       Open a git project, or run 'git init' here first.")
            }
            return false
        }
        val bins = resolveBinaries() ?: run {
            onLine("ERROR: semcode binaries not found — set them in Settings | Tools | Semcode")
            return false
        }
        val db = databaseDir()
        // semcode 0.5.0 起默认索引全部支持语言（跨语言仓库无需 -e）；模型随
        // 插件分发，向量步骤直传 --model-path，无需用户配置任何路径。
        val modelArgs = bundledModelDir()?.let { listOf("--model-path", it.absolutePath) } ?: emptyList()
        val steps = listOf(
            "semcode-index (source)" to listOf(
                bins.index.absolutePath, "-s", ".", "-d", db.absolutePath,
            ),
            "semcode-index (vectors)" to listOf(
                bins.index.absolutePath, "-s", ".", "-d", db.absolutePath, "--vectors",
            ) + modelArgs,
        )
        for ((label, command) in steps) {
            onLine(">>> $label")
            if (!runProcess(command, root) { line ->
                    onLine(line)
                    indexLog.add(line.take(500))
                    if (indexLog.size > 400) {
                        indexLog.removeAt(0)
                    }
                }
            ) {
                onLine("ERROR: step failed: $label")
                return false
            }
        }
        writeModelMarker()
        return true
    }

    /**
     * 工作区索引：逐仓跑两步链，库在 .idea/semcode.dbs/<repo>/（CLI 会
     * 在其内写 .semcode.db，与 semcode-mcp --workspace 的读取约定一致）。
     * 一个仓库失败即停——半途继续会让 status 里留着静默缺失的仓库。
     */
    private fun runWorkspaceIndex(repos: List<File>, onLine: (String) -> Unit): Boolean {
        val bins = resolveBinaries() ?: run {
            onLine("ERROR: semcode binaries not found — set them in Settings | Tools | Semcode")
            return false
        }
        val dbRoot = workspaceDbRoot() ?: return false
        val modelArgs = bundledModelDir()?.let { listOf("--model-path", it.absolutePath) } ?: emptyList()
        onLine("Workspace: ${repos.size} repositories")
        for ((i, repo) in repos.withIndex()) {
            val name = repo.name
            val db = File(dbRoot, name)
            onLine(">>> [$i+1/${repos.size}] $name (source)")
            if (!runProcess(
                    listOf(bins.index.absolutePath, "-s", ".", "-d", db.absolutePath),
                    repo,
                ) { line -> onLine(line); indexLog.add(line.take(500)) }
            ) {
                onLine("ERROR: source indexing failed for $name")
                return false
            }
            onLine(">>> [$i+1/${repos.size}] $name (vectors)")
            if (!runProcess(
                    listOf(bins.index.absolutePath, "-s", ".", "-d", db.absolutePath, "--vectors") + modelArgs,
                    repo,
                ) { line -> onLine(line); indexLog.add(line.take(500)) }
            ) {
                onLine("ERROR: vector step failed for $name")
                return false
            }
            writeModelMarker(File(db, "model.sha"))
        }
        return true
    }

    /**
     * 换模型后的向量自愈（0.7.0）：捆绑模型的指纹与上次成功跑向量时记录的
     * 不一致 → 只重跑向量步骤（Rust 端 ensure_vectors_model 会清掉旧空间的
     * 向量重算）。指纹算法与 Rust 端一致：sha256(model.safetensors) 前 16 hex。
     * @return true 表示需要并已启动补跑。
     */
    fun refreshVectorsIfModelChanged(onLine: (String) -> Unit): Boolean {
        val fp = bundledModelFingerprint() ?: return false
        val marker = modelMarkerFile() ?: return false
        if (marker.isFile && marker.readText().trim() == fp) {
            return false
        }
        val root = projectRoot() ?: return false
        val bins = resolveBinaries() ?: return false
        val db = databaseDir()
        val modelDir = bundledModelDir() ?: return false
        onLine(">>> semcode-index (vectors, model changed)")
        val ok = runProcess(
            listOf(
                bins.index.absolutePath, "-s", ".", "-d", db.absolutePath,
                "--vectors", "--model-path", modelDir.absolutePath,
            ),
            root,
        ) { line ->
            onLine(line)
            indexLog.add(line.take(500))
        }
        if (ok) {
            writeModelMarker()
            onLine("Vectors rebuilt for the new embedding model")
        }
        return true
    }

    private fun bundledModelFingerprint(): String? {
        val dir = bundledModelDir() ?: return null
        return sha256Prefix(File(dir, "model.safetensors"))
    }

    private fun modelMarkerFile(): File? =
        projectRoot()?.let { File(it, ".idea/semcode-model.sha") }

    private fun writeModelMarker() {
        val fp = bundledModelFingerprint() ?: return
        modelMarkerFile()?.writeText(fp)
    }

    private fun writeModelMarker(file: File) {
        val fp = bundledModelFingerprint() ?: return
        file.parentFile?.mkdirs()
        file.writeText(fp)
    }

    private fun sha256Prefix(file: File): String? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().take(8).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        log.warn("model fingerprint failed: ${file}", e)
        null
    }

    /** 查询用常驻 MCP 子进程（懒启动，进程死亡自动重启）。 */
    fun mcpClient(): SemcodeMcpClient? {
        val root = projectRoot() ?: return null
        val bins = resolveBinaries() ?: return null
        if (client == null) {
            val modelArgs = bundledModelDir()?.let { listOf("--model-path", it.absolutePath) } ?: emptyList()
            // 工作区项目（根下多个 git 仓库）用 --workspace 一进程管全部；
            // 单仓项目维持 -d/--git-repo。
            val baseArgs = if (workspaceRepos().isNotEmpty()) {
                listOf(bins.mcp.absolutePath, "--workspace", root.absolutePath, "--lazy")
            } else {
                listOf(
                    bins.mcp.absolutePath,
                    "-d", databaseDir().absolutePath,
                    "--git-repo", root.absolutePath,
                    "--lazy",
                )
            }
            client = SemcodeMcpClient(baseArgs + modelArgs, root)
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
