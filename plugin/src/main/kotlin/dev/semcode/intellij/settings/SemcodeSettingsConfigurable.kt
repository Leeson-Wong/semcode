package dev.semcode.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JComponent

class SemcodeSettingsConfigurable : Configurable {
    private val binariesDirField = JBTextField(36)
    private val modelDirField = JBTextField(36)

    override fun getDisplayName(): String = "Semcode"

    override fun createComponent(): JComponent = panel {
        row("Semcode binaries directory:") { cell(binariesDirField) }
        row("Model directory (optional):") { cell(modelDirField) }
        row {
            comment(
                "Binaries dir must contain semcode-index(.exe), semcode(.exe), semcode-mcp(.exe). " +
                    "Empty model dir = semcode default cache location."
            )
        }
    }

    override fun isModified(): Boolean {
        val s = SemcodeSettings.getInstance()
        return binariesDirField.text != s.binariesDir || modelDirField.text != s.modelDir
    }

    override fun apply() {
        val s = SemcodeSettings.getInstance()
        s.binariesDir = binariesDirField.text.trim()
        s.modelDir = modelDirField.text.trim()
    }

    override fun reset() {
        val s = SemcodeSettings.getInstance()
        binariesDirField.text = s.binariesDir
        modelDirField.text = s.modelDir
    }
}

internal fun semcodeExe(dir: File, name: String): File =
    File(dir, if (System.getProperty("os.name").lowercase().contains("win")) "$name.exe" else name)
