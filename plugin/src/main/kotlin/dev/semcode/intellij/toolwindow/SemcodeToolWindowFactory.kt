package dev.semcode.intellij.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import dev.semcode.intellij.core.SemcodeProcessService
import java.awt.BorderLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Semcode Tool Window（脚手架版）：自然语言输入 → `semcode -q "vgrep ..."`。
 * 1.2 阶段升级为 semcode-mcp JSON-RPC 客户端 + 结构化结果列表 + 点击跳转。
 */
class SemcodeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SemcodePanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private class SemcodePanel(project: Project) : JPanel(BorderLayout()) {
        private val searchField = JBTextField().apply { emptyText.text = "Describe what you're looking for…" }
        private val outputArea = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        private val searchButton = JButton("Search")

        init {
            val top = JPanel(BorderLayout())
            top.add(searchField, BorderLayout.CENTER)
            top.add(searchButton, BorderLayout.EAST)
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(outputArea), BorderLayout.CENTER)

            val root = project.basePath?.let { File(it) }

            fun doSearch() {
                val query = searchField.text.trim()
                if (query.isEmpty() || root == null) return
                outputArea.text = "Searching: $query…\n"
                searchButton.isEnabled = false

                ApplicationManager.getApplication().executeOnPooledThread {
                    val service = SemcodeProcessService.getInstance(project)
                    val buffer = StringBuilder()
                    val ok = service.runQuery(root, "vgrep --limit 10 \"$query\"") { line ->
                        synchronized(buffer) { buffer.appendLine(line) }
                        SwingUtilities.invokeLater {
                            outputArea.text = buffer.toString()
                        }
                    }
                    SwingUtilities.invokeLater {
                        if (!ok) outputArea.append("\n[search failed]")
                        searchButton.isEnabled = true
                    }
                }
            }

            searchButton.addActionListener { doSearch() }
            searchField.addActionListener { doSearch() }
        }
    }
}
