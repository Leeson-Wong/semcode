package dev.semcode.intellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Semcode 插件全局配置。二进制目录默认约定：优先插件捆绑分发（阶段 3 打包
 * 后），否则用户在 Settings 里指向本地构建（开发期用 target/release）。
 */
@Service(Service.Level.APP)
@State(name = "SemcodeSettings", storages = [Storage("semcode.xml")])
class SemcodeSettings : PersistentStateComponent<SemcodeSettings> {
    /** 存放 semcode-index.exe / semcode.exe / semcode-mcp.exe 的目录 */
    var binariesDir: String = ""

    /** 向量模型目录（model2vec 蒸馏产物）。空 = semcode 默认缓存位置 */
    var modelDir: String = ""

    override fun getState(): SemcodeSettings = this

    override fun loadState(state: SemcodeSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): SemcodeSettings =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(SemcodeSettings::class.java)
    }
}
