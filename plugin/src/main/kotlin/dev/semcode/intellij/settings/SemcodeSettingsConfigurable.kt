package dev.semcode.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JComponent

class SemcodeSettingsConfigurable : Configurable {
    private val binariesDirField = JBTextField(36)

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
    }

    override fun isModified(): Boolean {
        val s = SemcodeSettings.getInstance()
        return binariesDirField.text != s.binariesDir
    }

    override fun apply() {
        val s = SemcodeSettings.getInstance()
        s.binariesDir = binariesDirField.text.trim()
    }

    override fun reset() {
        val s = SemcodeSettings.getInstance()
        binariesDirField.text = s.binariesDir
    }
}

internal fun semcodeExe(dir: File, name: String): File =
    File(dir, if (System.getProperty("os.name").lowercase().contains("win")) "$name.exe" else name)
