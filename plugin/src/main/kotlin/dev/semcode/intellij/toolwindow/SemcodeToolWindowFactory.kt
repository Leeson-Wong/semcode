package dev.semcode.intellij.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.semcode.intellij.core.SemcodeProcessService
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** 给 AI 编码会话的引导提示：一键拷贝后用户粘贴进 Claude Code 等。 */
private fun bootstrapPrompt(isWorkspace: Boolean, repoCount: Int): String = buildString {
    append(
        """
        这个项目已经配置了 semcode MCP（.mcp.json 由 Semcode 插件自动写入，无需手动注册），它提供 git 感知的语义代码检索、调用图和 git 历史考古。使用要点：

        - 服务以 lazy 模式运行：先用 list_categories 看分类，get_tools 拿某类工具的 schema，再用 call_tool 执行
        - code_lookup：find_function / find_type / find_callers / find_callchain —— 精确或模糊查符号与调用关系。调用图按名字解析，同名函数（C 尤其常见）请结合结果里的文件路径判断
        - code_search：vgrep_functions 语义搜索（参数是 query_text，支持中文）/ grep_functions 正则搜索
        - git_history：find_commit 可查任意已索引 commit。头部 "N of M indexed" 表示区间内只有 N 个提交已索引——回填历史用 index_project 的 full=true，不是错误
        - status：indexing_status 查索引状态；index_project 触发索引（full=true 为全量，新装或刚支持新语言时用）
        - 支持语言：C/C++、Rust、Python、Zig、Java、Kotlin、ArkTS（.ets）
        - 预期行为（不是故障）：file_survey 在大索引上约 30 秒并附 "reference counts omitted" 注记；非内核项目 lore 工具返回"无 lore 数据"说明
        """.trimIndent()
    )
    if (isWorkspace) {
        append(
            """

        - 工作区模式（本项目是）：这个工作区含 $repoCount 个 git 仓库，一个 server 统一应答——查询自动扇出到全部仓库，结果带 "== repo: 仓库名 ==" 分节头；file_survey 的 path 参数要写 "<仓库名>/<仓库内相对路径>"；找不到的符号会在其它仓库如实报 not found，属正常""".trimIndent()
        )
    }
}

/**
 * Semcode 控制台：索引状态卡（状态 + 重建/刷新/AI 提示词拷贝）+
 * 语义/正则双搜索 + 结构化结果列表（双击跳转）+ 查询历史。
 * 查询走常驻 semcode-mcp 子进程。
 */
class SemcodeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SemcodeConsolePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private class SearchEntry(
        val name: String,
        val filePath: String,
        val line: Int,
        val detail: String,
    )

    private class SemcodeConsolePanel(private val project: Project) : JPanel(BorderLayout()) {

        private val statusLabel = JLabel("Checking semcode status…")
        private val consoleArea = com.intellij.ui.components.JBTextArea(6, 60).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            emptyText.setText("Indexing output appears here")
        }
        private val consoleTimer = javax.swing.Timer(500) { refreshConsole() }.apply { stop() }
        private var indexStartMillis = 0L
        private val searchField = JBTextField().apply { emptyText.text = "Describe what you're looking for…" }
        private val searchButton = JButton("Search")
        private val modeBox = JComboBox(arrayOf("Semantic", "Regex"))
        private val historyModel = DefaultComboBoxModel<String>()
        private val historyBox = JComboBox(historyModel)
        private val resultModel = com.intellij.ui.CollectionListModel<SearchEntry>()
        private val resultList = JBList(resultModel).apply {
            emptyText.setText("No results yet — search above")
            cellRenderer = object : ColoredListCellRenderer<SearchEntry>() {
                override fun customizeCellRenderer(
                    list: JList<out SearchEntry>,
                    value: SearchEntry,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(value.name, com.intellij.ui.SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${value.detail}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("\n${value.filePath}:${value.line}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    ipad = com.intellij.util.ui.JBUI.insets(4, 2)
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) navigateSelected()
                }
            })
        }

        init {
            val statusPanel = JPanel(BorderLayout())
            statusPanel.add(statusLabel, BorderLayout.CENTER)
            val statusButtons = JPanel()
            val rebuildButton = JButton("Rebuild Index")
            val refreshButton = JButton("Refresh")
            val copyPromptButton = JButton("Copy Bootstrap Prompt")
            statusButtons.add(rebuildButton)
            statusButtons.add(refreshButton)
            statusButtons.add(copyPromptButton)
            statusPanel.add(statusButtons, BorderLayout.EAST)
            rebuildButton.addActionListener { rebuildIndex() }
            refreshButton.addActionListener { refreshStatus() }
            copyPromptButton.addActionListener { copyAiPrompt() }

            // 索引可视化（用户 2026-11-16 要求面板内可见）：启动自动索引与
            // 手动 Rebuild 共用 service 的日志流；跑动中 500ms 刷新一次尾部，
            // 状态行带已用时间（用户 2026-11-17 要求计时）。
            service().addIndexListener { running ->
                SwingUtilities.invokeLater {
                    if (running) {
                        indexStartMillis = System.currentTimeMillis()
                        statusLabel.text = "Indexing… 0s"
                        consoleTimer.start()
                    } else {
                        consoleTimer.stop()
                        refreshConsole()
                        refreshStatus()
                    }
                }
            }
            if (service().indexRunning) {
                indexStartMillis = System.currentTimeMillis()
                consoleTimer.start()
            }

            val searchPanel = JPanel(BorderLayout())
            val top = JPanel(BorderLayout())
            top.add(modeBox, BorderLayout.WEST)
            top.add(searchField, BorderLayout.CENTER)
            top.add(searchButton, BorderLayout.EAST)
            val historyRow = JPanel(BorderLayout())
            historyRow.add(JLabel("History:"), BorderLayout.WEST)
            historyRow.add(historyBox, BorderLayout.CENTER)
            searchPanel.add(top, BorderLayout.NORTH)
            searchPanel.add(historyRow, BorderLayout.SOUTH)
            historyBox.addActionListener {
                val q = historyBox.selectedItem as? String ?: return@addActionListener
                if (q.isNotBlank() && searchField.text != q) {
                    searchField.text = q
                    doSearch()
                }
            }

            add(JPanel(BorderLayout()).apply {
                add(statusPanel, BorderLayout.NORTH)
                add(JBScrollPane(consoleArea).apply {
                    preferredSize = java.awt.Dimension(0, 120)
                    maximumSize = java.awt.Dimension(Int.MAX_VALUE, 160)
                }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            val resultsPanel = JBScrollPane(resultList)
            add(JPanel(BorderLayout()).apply {
                add(searchPanel, BorderLayout.NORTH)
                add(resultsPanel, BorderLayout.CENTER)
            }, BorderLayout.CENTER)

            searchButton.addActionListener { doSearch() }
            searchField.addActionListener { doSearch() }

            SwingUtilities.invokeLater { refreshStatus() }
        }

        private fun service(): SemcodeProcessService = SemcodeProcessService.getInstance(project)

        private fun copyAiPrompt() {
            val repos = service().workspaceRepos()
            val prompt = bootstrapPrompt(isWorkspace = repos.isNotEmpty(), repoCount = repos.size)
            com.intellij.openapi.ide.CopyPasteManager.getInstance()
                .setContents(java.awt.datatransfer.StringSelection(prompt))
            notify("Bootstrap prompt copied — paste it into your AI coding session")
        }

        private fun refreshConsole() {
            val tail = service().indexLog.toList().takeLast(80).joinToString("\n")
            if (consoleArea.text != tail) {
                consoleArea.text = tail
                consoleArea.caretPosition = consoleArea.text.length
            }
            if (service().indexRunning && indexStartMillis > 0) {
                val secs = (System.currentTimeMillis() - indexStartMillis) / 1000
                statusLabel.text = "Indexing… " + if (secs >= 60) {
                    "${secs / 60}m${secs % 60}s"
                } else {
                    "${secs}s"
                }
            }
        }

        private fun refreshStatus() {
            statusLabel.text = "Checking semcode status…"
            ApplicationManager.getApplication().executeOnPooledThread {
                val text = readStatus()
                SwingUtilities.invokeLater { statusLabel.text = text }
            }
        }

        private fun readStatus(): String {
            if (service().resolveBinaries() == null) {
                return "<html>Semcode binaries not configured.<br>Settings | Tools | Semcode</html>"
            }
            val db = service().databaseDir()
            val mcp = service().mcpClient()
                ?: return "<html>Database: ${db.absolutePath}</html>"
            val status = mcp.callTool("indexing_status")
                ?.lineSequence()?.filter { it.isNotBlank() }?.joinToString("<br>")
                ?: "indexing status unavailable"
            return "<html>Database: .idea/semcode.db<br>${status.replace("<", "&lt;")}</html>"
        }

        private fun rebuildIndex() {
            val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Semcode indexing", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val ok = service().runIndexChain(
                        { line ->
                            lines.add(line)
                            indicator.text = line.take(120)
                        },
                        cancelled = { indicator.isCanceled },
                    )
                    SwingUtilities.invokeLater {
                        refreshStatus()
                        if (ok) {
                            notify("Indexing complete (${lines.size} output lines)")
                        } else {
                            // 尾部多行而不是一行：索引器的真实报错常常不在最后一行
                            val tail = lines.asReversed()
                                .takeWhile { !it.startsWith(">>>") }
                                .take(3)
                                .asReversed()
                                .joinToString(" | ")
                                .ifBlank { "no output" }
                                .take(300)
                            notify(
                                "Indexing failed — $tail",
                            )
                        }
                    }
                }
            })
        }

        private fun notify(message: String) {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("Semcode")
                .createNotification("Semcode", message, com.intellij.notification.NotificationType.INFORMATION)
                .notify(project)
        }

        private fun doSearch() {
            val query = searchField.text.trim()
            if (query.isEmpty()) return
            rememberHistory(query)
            searchButton.isEnabled = false
            resultModel.replaceAll(emptyList())
            resultList.emptyText.setText("Searching…")

            val semantic = modeBox.selectedItem == "Semantic"
            ApplicationManager.getApplication().executeOnPooledThread {
                val entries = if (semantic) semanticSearch(query) else regexSearch(query)
                SwingUtilities.invokeLater {
                    resultModel.replaceAll(entries)
                    resultList.emptyText.setText(
                        if (entries.isEmpty()) "No results for \"$query\"" else "No results yet — search above",
                    )
                    searchButton.isEnabled = true
                }
            }
        }

        private fun semanticSearch(query: String): List<SearchEntry> {
            val mcp = service().mcpClient() ?: return markUnavailable()
            val obj = mcp.callToolJson(
                "vgrep_functions",
                mapOf("query_text" to query, "limit" to 20, "format" to "json"),
            ) ?: return emptyList()
            return obj.getAsJsonArray("results")?.map { el ->
                val r = el.asJsonObject
                SearchEntry(
                    name = r.get("name")?.asString ?: "?",
                    filePath = r.get("file_path")?.asString ?: "",
                    line = r.get("line_start")?.asInt ?: 0,
                    detail = "${r.get("similarity")?.asDouble ?: 0.0}%",
                )
            } ?: emptyList()
        }

        private fun regexSearch(query: String): List<SearchEntry> {
            val mcp = service().mcpClient() ?: return markUnavailable()
            val obj = mcp.callToolJson(
                "grep_functions",
                mapOf("pattern" to query, "limit" to 100, "format" to "json"),
            ) ?: return emptyList()
            return obj.getAsJsonArray("results")?.map { el ->
                val r = el.asJsonObject
                val lines = r.getAsJsonArray("matched_lines")?.size() ?: 0
                SearchEntry(
                    name = r.get("name")?.asString ?: "?",
                    filePath = r.get("file_path")?.asString ?: "",
                    line = r.get("line_start")?.asInt ?: 0,
                    detail = "$lines match(es)",
                )
            } ?: emptyList()
        }

        private fun markUnavailable(): List<SearchEntry> {
            SwingUtilities.invokeLater {
                resultList.emptyText.setText("Semcode binaries not configured — Settings | Tools | Semcode")
            }
            return emptyList()
        }

        private fun rememberHistory(query: String) {
            if (historyModel.getIndexOf(query) >= 0) return
            historyModel.addElement(query)
            while (historyModel.size > 15) historyModel.removeElementAt(0)
        }

        private fun navigateSelected() {
            val entry = resultList.selectedValue ?: return
            val rootPath = project.basePath ?: return
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByIoFile(File(rootPath, entry.filePath))
            if (vf == null) {
                notify("File not found: ${entry.filePath}")
                return
            }
            OpenFileDescriptor(project, vf, (entry.line - 1).coerceAtLeast(0), 0).navigate(true)
        }
    }
}
