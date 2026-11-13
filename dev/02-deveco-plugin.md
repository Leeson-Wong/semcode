# DevEco Studio 插件可行性评估

> 评估日期：2026-11-13。结论：可行，走原生 IntelliJ 插件路线，MVP 3-6 周。
> 2026-11-13 补充：阶段 0.1 已实测（本机 DevEco Studio 26.0），LSP 路线判死，
> 原生路线为唯一路线。

## 平台事实（已验证）

**阶段 0.1 实测结论（2026-11-13，本机 DevEco Studio 26.0.0.461）：**

| 验证项 | 结果 |
|--------|------|
| 基线 build 号 | **243.24978**（IntelliJ Platform 2024.3 系），`product-info.json` 实测 |
| `com.intellij.lsp` API | **不存在**：464 个模块描述符无 `intellij.platform.lsp`；全量 lib jar 扫描无 `com/intellij/lsp/*` 类；仅有裸 lsp4j 库。符合预期——LSP API 2025-09 才对 Community 系开放，DevEco 26.0 基线（2024.3）早于该时间点 |
| 市场通道 | IDE 内插件浏览器缓存 `*-meta.json`（JetBrains Marketplace 元数据），证实走 Marketplace |
| ArkTS 语言支持归属 | 华为私有插件 `harmony` / `openharmony`（捆绑插件无 javascript/typescript，`.ets` filetype 由华为插件注册，我方不得重复注册） |
| 华为 AI 助手 | `codegenie-plugin` 捆绑在装（威胁 #1 的实体确认） |

**插件兼容声明：`since-build = 243`，`until-build = 243.*` 起步。**

| 事实 | 依据 |
|------|------|
| DevEco Studio 基于 IntelliJ IDEA Community 开源版 | Wikipedia、华为官方口径 |
| 第三方插件可以安装 | 官方文档有 "Install Plugin from Disk" 流程 |
| 社区插件已实际兼容 DevEco 5.x | Rainbow Brackets 明确列出 DevEco 支持；intellij-extra-icons 2026-01 版本 "tested with DevEco Studio 5.1.1" |
| 插件市场分渠道 | 华为论坛确认：插件可见性受 IDE 基线版本、兼容范围、市场渠道限制；磁盘安装不受限 |

## 与 IntelliJ 内置导航的对比（重要：定位要诚实）

纯导航层面，**semcode-lsp 是 IntelliJ 原生导航的弱化版**：

| 能力 | IntelliJ 内置（DevEco 对 ArkTS） | semcode-lsp |
|------|------|------|
| 跳转定义 | 精确到符号，完整语义模型 | 名称匹配（光标取词查库，按 函数>宏>类型>typedef） |
| 查找引用 | 精确到每处使用的行列 | **只到函数级**（`docs/lsp-server.md:194`） |
| 实时性 | 编辑即时生效 | 需手动 `semcode-index`，无增量感知 |
| 语言 | ArkTS 一等公民 | 目前只有 C/C++ |

**插件的真正卖点不是导航**，是：

1. **语义搜索**：自然语言描述意图找代码，IntelliJ 内置完全没有，且 LSP 协议
   无对应请求类型 → **必须原生 Tool Window**
2. **零编译数据库**：IntelliJ 系 C++ 索引依赖 CMake/compile_commands.json；
   tree-sitter 不需要编译配置
3. **Git 版本感知**：跳转/引用限定在当前 commit 的版本上

## 技术路线

### 路线 A：LSP 薄封装（1-2 周）❌ 已判死

Kotlin 插件通过 `LspServerDescriptor` 挂 semcode-lsp，提供跳转/引用。

- ~~必须 hello-world 验证~~ **2026-11-13 静态验证完毕：DevEco 26.0 无
  `com.intellij.lsp` API（模块与类均不存在），此路线不可行**。除非华为未来
  升基线到 2025.2+ 并保留该模块
- 即使可用，也只覆盖导航，覆盖不了核心卖点（语义搜索）——判死无损失

### 路线 B：原生插件（3-6 周 MVP）✅ 唯一路线（已确认）

- `GotoDeclarationHandler` + `ReferencesSearch` 扩展点做导航
- **Tool Window 做语义搜索界面**（核心卖点，LSP 承载不了）
- "Index Project" Action：后台跑 `semcode-index` 外部进程 + 进度条
- Settings 页配置 semcode 二进制路径 / 数据库路径

## 插件设计约束（为 ArkTS 预留）

- **语言/文件类型注册必须数据驱动**：读配置或查询 semcode 支持列表，
  禁止硬编码 C/C++ 列表，否则接 ArkTS 时插件层返工
- 语义搜索 Tool Window 天然语言无关（查询走数据库）；只有导航层有
  filetypes 问题

## 插件调用 semcode 的集成约束（2026-11-13 Windows 实测）

1. **索引器必须以工程根为 CWD、`-s .` 相对路径调用**。从父目录用
   `--source .\test_code` 相对路径调用时，scope 前缀解析为 `./test_code/`，
   与 git tree 内路径（如 `main.c`）永远不匹配 → **静默索引 0 文件且报
   成功**（`src/git_range.rs:1348` scope_prefixes 路径）。这是上游 bug，
   插件侧以"CWD=工程根 + -s ."绕开，永不踩
2. **查询工具的 `--git-repo` 默认 `.`**：插件启动查询进程时必须显式传
   工程根，否则解析到错误的仓库 SHA（嵌套仓库/子目录场景必错）
3. **索引是严格两步**：`--vectors` 语义为"只做向量化"（`src/bin/index.rs`
   的 flag 分支），源码索引与向量化必须分两次调用：先 `semcode-index -s .`
   再 `semcode-index -s . --vectors`。对空库直接 `--vectors` 会静默产出
   0 向量且报成功（踩过）。插件的 "Index Project" 必须链式跑这两步
4. PQ 向量索引需 ≥256 个函数（少于则报错跳过，vgrep 退化为暴力检索仍
   可用）。插件进度/健康提示要区分这两种状态
5. 失败的索引运行会把 commit 记入 `git_commits` 表导致后续"已索引"跳过
   （需要 `--clear` 解毒）。插件对失败索引要有清理或重试策略
6. 模型权重经 HF 下载：直连 huggingface.co 本身可达（实测 200/1.9s），但
   **hf-xet 传输后端会 0 字节卡死**（CAS 分片端点不通）。解法只需
   `HF_HUB_DISABLE_XET=1` 走普通 HTTPS CDN（实测 ~3MB/s），无需镜像站。
   插件直接捆绑蒸馏后的模型（几十 MB）绕开整个问题（见打包节）

## 打包（通用上架的最大工程风险）——2026-11-13 侦察更新

**重大利好：上游已不用 ONNX Runtime。** CLAUDE.md 的"CodeBERT + ONNX"描述
过时，实际嵌入栈是 `model2vec-rs`（纯 Rust：ndarray + safetensors +
tokenizers + hf-hub，`Cargo.toml:59`），256 维静态嵌入。原生构建依赖只剩：
aws-lc-sys（要 CMake+C）、curl-sys（vendored）、zstd-sys、esaxx-rs（C++）、
tree-sitter 各语法（C）。四平台风险大幅下降。

- 插件带 semcode 原生二进制需覆盖 4 平台：win-x64、mac-arm64、mac-x64、
  linux-x64；建议 GitHub Actions matrix（各平台原生 runner）而非从 Windows
  交叉编译（aws-lc-sys/openssl vendored 交叉编译是坑）
- **模型不自动下载**（`src/vectorizer.rs:76` 直接报错要求手动放置）：上游的
  模型管线是手动蒸馏（`scripts/direct_download.py` 下 nomic 权重 →
  `scripts/nomic2vec.py` 蒸馏 → 移入缓存目录；README:304-320，作者自述
  "mostly untested"）。Windows 缓存路径是 `%LOCALAPPDATA%\semcode\models\
  model2vec`（`dirs::cache_dir()`）
  - **产品决策：蒸馏产物只有几十 MB，直接打进插件 zip 分发**，一步到位
    解决首跑体验（上游手动管线对普通用户不可用）
- Windows 开发机构建需要 protoc（已装 36.2 到 D:\devtools\protoc，
  `PROTOC` 用户环境变量已设）——只影响从源码构建的开发者，不影响装插件的
  最终用户
- 注意 Marketplace zip 体积限制与更新流量

## 前置依赖

1. ~~ArkTS 支持~~——已决定后接（见 05-execution-plan.md），插件 MVP 用
   C/C++ 即可验证端到端价值
2. **Windows 构建验证**：DevEco 用户基本在 Windows。依赖链（ONNX Runtime、
   LanceDB、tree-sitter）均支持 Windows，风险不大但要跑通全流程
