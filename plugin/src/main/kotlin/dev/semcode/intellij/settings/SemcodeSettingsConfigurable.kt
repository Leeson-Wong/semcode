package dev.semcode.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JComponent

class SemcodeSettingsConfigurable : Configurable {
    private val binariesDirField = JBTextField(36)
    private val workerThreadsField = JBTextField(6)

    override fun getDisplayName(): String = "Semcode"

    override fun createComponent(): JComponent = panel {
        row("Binaries directory (optional override):") { cell(binariesDirField) }
        row {
            comment(
                "Leave empty to use the binaries bundled with the plugin. " +
                    "Set this only to run against a local build (e.g. target/release); " +
                    "the directory must contain semcode-index(.exe) and semcode-mcp(.exe)."
            )
        }
        row("Indexing worker threads (blank = auto):") { cell(workerThreadsField) }
        row {
            comment(
                "Auto keeps it modest (a quarter of the cores, 2-8) and children run at " +
                    "below-normal priority, so indexing never starves your foreground apps. " +
                    "Raise it to trade responsiveness for faster full rebuilds."
            )
        }
    }

    override fun isModified(): Boolean {
        val s = SemcodeSettings.getInstance()
        return binariesDirField.text != s.binariesDir ||
            workerThreadsField.text.trim().toIntOrNull().let { it ?: 0 } != s.workerThreads
    }

    override fun apply() {
        val s = SemcodeSettings.getInstance()
        s.binariesDir = binariesDirField.text.trim()
        s.workerThreads = workerThreadsField.text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    override fun reset() {
        val s = SemcodeSettings.getInstance()
        binariesDirField.text = s.binariesDir
        workerThreadsField.text = if (s.workerThreads >= 1) s.workerThreads.toString() else ""
    }
}

internal fun semcodeExe(dir: File, name: String): File =
    File(dir, if (System.getProperty("os.name").lowercase().contains("win")) "$name.exe" else name)
