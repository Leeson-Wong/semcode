plugins {
    id("java")
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.semcode"
// 版本策略：每次需要用户重装插件的构建都必须升版本号，让用户能在
// Settings | Plugins 里看清装的是哪一版（用户 2026-11-14 明确要求）
version = "0.8.0"

repositories {
    // 阿里云镜像优先（本机 init.d/mirror.gradle 只作用于 settings 层，
    // project 级仓库会旁路它，故此处显式声明），官方源兜底
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.aliyun.com/repository/gradle-plugin")
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // 用本机 DevEco Studio（基线 243.24978 = 2024.3 系）作本地平台：零下载，
        // 且编译目标即部署目标（fork 系 IDE 插件开发的正确姿势）。
        // 本机 IDEA/CLion 为 2026.3（263），对 243 目标太新，不用。
        // 备选：intellijIdeaCommunity("243.21565.14") { useInstaller = false } + jetbrainsRuntime()
        local("E:/devtools/DevEco Studio")
    }
}

kotlin {
    // 2024.3 运行于 JBR 21；since-build 钉在 243，字节码 21 全线兼容
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// 模型 + win-x64 二进制随插件分发（用户 2026-11-16 拍板零配置）：外部用户
// 装 zip 即用，不再要求先 cargo build。二进制取 target/release（须先
// cargo build --release；strip=true 已在 Cargo.toml 开启），模型 3 文件 ~31MB。
// IJ Platform Gradle Plugin 2.x 的 prepareSandbox 不是 Copy 子类，挂不上去，
// 直接改 buildPlugin 的 Zip；runIde 沙箱不带两者，开发机用 Settings 覆盖 +
// semcode 缓存目录回退。
val releaseDir = layout.projectDirectory.dir("../target/release")
tasks.named<Zip>("buildPlugin") {
    // into 相对 zip 根 spec（其自身已带 semcode-intellij/ 前缀），故只写 model/bin
    from(layout.projectDirectory.dir("model/model2vec")) {
        into("model")
    }
    from(releaseDir) {
        include("semcode-index.exe", "semcode-mcp.exe")
        into("bin")
    }
    doFirst {
        val missing = listOf("semcode-index.exe", "semcode-mcp.exe")
            .filterNot { releaseDir.file(it).asFile.isFile }
        require(missing.isEmpty()) {
            "bundled binaries missing in ${releaseDir}: $missing — run `cargo build --release` first"
        }
    }
}

intellijPlatform {
    // 不用 IntelliJ GUI Designer 表单，无需字节码插桩；
    // 且插桩工具对解包版微软 JDK 的布局探测有缺陷（找 <JAVA_HOME>\Packages）
    instrumentCode = false
    // buildSearchableOptions 起无头 IDE 遍历设置页；DevEco 下华为 CodeGenie
    // 插件在无头模式原生崩溃（SemanticSearchManager JNI），直接禁用该可选任务
    buildSearchableOptions = false
    pluginConfiguration {
        id = "dev.semcode.intellij"
        name = "Semcode"
        version = project.version.toString()
        ideaVersion {
            // 243 = DevEco 26.0 基线；263 = 本机 CLion 2026.3（手动测试用）。
            // 插件只用稳定平台 API（ToolWindow/Action/Settings/通知），宽区间风险低
            sinceBuild = "243"
            untilBuild = "263.*"
        }
    }
}
