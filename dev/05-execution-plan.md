# 执行计划

> 制定日期：2026-11-13。已定顺序：**插件先行，ArkTS 后接**。

## 为什么插件先行（决策依据）

1. **先消全局风险**：插件阶段的不确定性（DevEco LSP API 可用性、四平台
   二进制打包）是全项目生死风险；ArkTS 只是增量工作。风险大的先验证
2. **两线代码零交叉**：插件是 Kotlin，ArkTS 是 Rust
3. **插件 MVP 用现有 C/C++ 支持即可端到端验证价值**（CLion/IDEA 用户 +
   DevEco 项目的 native 部分），不用等 ArkTS

## 阶段划分

### 阶段 0：风险验证（1-2 天）⚠️ 生死项先做

| # | 验证项 | 方法 | 失败后果 |
|---|--------|------|----------|
| 0.1 | DevEco Studio 基线与 LSP API | ~~在 DevEco 5.x 装最小 LSP hello-world 插件~~ **✅ 2026-11-13 完成（静态验证，无需 hello-world）**：本机 DevEco Studio 26.0.0.461，基线 **243.24978（2024.3 系）**；`com.intellij.lsp` 模块与类均不存在 → LSP 路线判死，原生路线为唯一路线；ArkTS filetype 归华为 `harmony`/`openharmony` 插件 | 不致命：原生插件路线不依赖它，导航走扩展点 |
| 0.2 | semcode Windows 全流程 | **✅ 通过（2026-11-13）**：release 构建出 4 个二进制；test_code 索引成功；`func`/`callers`/`grep`/`vgrep` 全部正确（vgrep "memory allocation" 正确命中 allocate_buffer 第一名）。向量模型用现成 `minishlab/potion-base-8M`（256 维，格式兼容）替代 nomic 蒸馏——nomic 老师 1.8GB 在本机网络下不动（HF xet-bridge CDN 限流 ~KB/s 级），蒸馏挪到 CI 做。坑：① 缺 protoc（已装 36.2）② 索引器相对路径 scope bug（CWD=工程根 + `-s .` 绕开，见 02 集成约束）③ 查询必须显式 `--git-repo` ④ PQ 向量索引需 ≥256 行函数（小工程报错但 vgrep 暴力检索仍可用）⑤ 模型放 `%LOCALAPPDATA%\semcode\models\model2vec`（Windows 下 `dirs::cache_dir()` 语义） | 致命：DevEco 用户在 Windows，必须解决 ✅ 已消 |
| 0.3 | 四平台交叉编译 | win-x64 / mac-arm64 / mac-x64 / linux-x64 出包，ONNX Runtime + LanceDB 原生依赖全过 | 最大工程风险；若个别平台卡死，首发砍平台 |

**退出标准：0.2 必须通过；0.3 至少 win-x64 + mac-arm64 通过。**

### 阶段 1：插件 MVP（3-6 周）

范围（砍到最小）：

- [ ] 语义搜索 Tool Window（核心卖点，语言无关）
- [ ] "Index Project" Action：后台跑 `semcode-index` + 进度条
- [ ] 正则/精确搜索入口（复用 semcode 查询能力）
- [ ] Settings：semcode 二进制路径、数据库路径
- [ ] 导航（可选，最后做）：GotoDeclarationHandler / ReferencesSearch，
      或阶段 0.1 通过则走 LSP
- [ ] **语言注册数据驱动**：从配置/semcode 读取支持列表，禁止硬编码
      （ArkTS 接入时插件不返工）

分发：GitHub Releases zip + 磁盘安装（beta 期零审核）。

**退出标准：真实 C/C++ 工程 + 真实鸿蒙工程（native 部分）端到端可用，
种子用户装得上。**

### 阶段 2：ArkTS 支持（3-5 天）

详见 [01-arkts-support.md](01-arkts-support.md)。要点回顾：

- [ ] `tree-sitter-arkts` 接入（先 `cargo check` 验证 tree-sitter 0.26 兼容）
- [ ] 五组查询 + owner qualifier + `this` 接收者 + file_survey
- [ ] ArkUI 组件调用过滤（`Column()`/`Text()` 等 DSL 噪音，按 struct 体内
      上下文或名单过滤）
- [ ] 真实鸿蒙工程 `.ets` 索引验证
- [ ] 插件侧：`ets` filetype 注册（数据驱动下应该只是配置/版本升级）

**退出标准：DevEco 里对 `.ets` 语义搜索返回有意义的结果。**

### 阶段 3：上架发布（1 周）

- [ ] Marketplace listing：多产品兼容声明（含 DevEco 基线范围）
- [ ] LICENSE/NOTICE 合规打包（闭源衍生，保留上游声明）
- [ ] 定价上线（见 04-commercial.md；或 freemium 免费版先冲排名）
- [ ] 审核沟通预留 1-2 个工作日

## 里程碑与时间线（理想情况）

| 里程碑 | 时间 |
|--------|------|
| 阶段 0 完成 | 第 1 周 |
| 插件 MVP beta（磁盘安装） | 第 4-7 周 |
| ArkTS 支持合入 | 第 5-8 周（可与阶段 1 后半并行） |
| Marketplace 上架 | 第 8-9 周 |

窗口期规划为 6-12 个月（见 04-commercial.md），两个月内上架是硬目标。

## 长期维护承诺（认账清单）

- DevEco 大版本升基线 → 调插件兼容范围
- semcode 上游合并（闭源 fork 税）
- 模型/语法库版本升级

赚完时间差后：回馈上游 ArkTS 支持，换生态声誉（届时独家性已消耗完毕，
回馈无损失）。
