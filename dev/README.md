# Semcode 商业化开发文档

本目录存放 semcode 鸿蒙生态（DevEco Studio 插件 + ArkTS 支持）的评估与执行文档。
与仓库正式文档（`docs/`）无关，是独立的开发/决策记录。

## 文档索引

| 文档 | 内容 |
|------|------|
| [01-arkts-support.md](01-arkts-support.md) | ArkTS 语言支持评估：触点清单、工作量、风险 |
| [02-deveco-plugin.md](02-deveco-plugin.md) | DevEco Studio 插件可行性：平台事实、技术路线、与 IDE 原生能力对比 |
| [03-distribution.md](03-distribution.md) | 上架分发路径：JetBrains Marketplace 机制与流程 |
| [04-commercial.md](04-commercial.md) | 商业策略：窗口期、合规、定价、逆向风险 |
| [05-execution-plan.md](05-execution-plan.md) | 执行计划：阶段划分与风险验证（阶段 1 已被 07 细化取代） |
| [07-iteration-plan-v2.md](07-iteration-plan-v2.md) | 插件迭代 v2：ToolWindow 控制台化、零配置、库入 .idea、MCP 写能力、分发打包 |

## 决策记录

- **2026-11-14** 价值验证优先。插件形态定调：**ToolWindow 即产品**（索引
  状态/触发/检索/可视化全部收进侧边栏）、零配置（砍模型路径设置，模型随
  zip 捆绑）、索引数据放 `.idea/semcode.db`、MCP 增 `index_project` 写
  工具。打包仅 win-x64 单平台（v1.3），四平台交叉编译顺延至上架前。
  详见 07。
- **2026-11-13** 确定「插件先行，ArkTS 后接」的执行顺序。理由：插件阶段的
  不确定性（DevEco LSP API 可用性、四平台二进制打包）是全局风险，应先验证；
  插件（Kotlin）与 ArkTS 索引（Rust）代码零交叉；插件 MVP 用现有 C/C++
  支持即可端到端验证价值。

## 关键结论速览

- ArkTS 支持：语法库现成（tree-sitter-arkts），纯代码量 300-500 行，MVP 3-5 天
- 插件：走原生 IntelliJ 插件路线（语义搜索 UI 必须原生），MVP 3-6 周
- 上架：只有 JetBrains Marketplace 一条正经路径（DevEco 的 IDE 内插件市场就是它）
- 合规：全依赖链宽松协议（MIT/Apache-2.0），闭源商用合法
- 窗口期：按 6-12 个月规划，最大威胁是华为自家 AI 助手内置语义检索
