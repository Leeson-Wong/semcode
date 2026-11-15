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

/**
 * Semcode 控制台：索引状态卡（状态 + 重建/刷新）+ 语义/正则双搜索 +
 * 结构化结果列表（双击跳转）+ 查询历史。查询走常驻 semcode-mcp 子进程。
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
            statusButtons.add(rebuildButton)
            statusButtons.add(refreshButton)
            statusPanel.add(statusButtons, BorderLayout.EAST)
            rebuildButton.addActionListener { rebuildIndex() }
            refreshButton.addActionListener { refreshStatus() }

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

            add(statusPanel, BorderLayout.NORTH)
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
                    val ok = service().runIndexChain { line ->
                        lines.add(line)
                        indicator.text = line.take(120)
                    }
                    SwingUtilities.invokeLater {
                        refreshStatus()
                        if (ok) {
                            notify("Indexing complete (${lines.size} output lines)")
                        } else {
                            notify(
                                "Indexing failed — last output: " +
                                    (lines.lastOrNull { !it.startsWith(">>>") } ?: "none"),
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
