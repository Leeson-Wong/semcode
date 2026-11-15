# 插件迭代计划 v2（价值验证后的产品化路线）

> 制定日期：2026-11-14。基于 2026-11-14 CLion 实测反馈细化并取代
> [05-execution-plan.md](05-execution-plan.md) 的阶段 1 范围；阶段 2（ArkTS）、
> 阶段 3（上架）维持不变。

## 背景：2026-11-14 实测结论

- 插件 1.0 脚手架在 CLion 2026.3 装机可用（需手动配 binariesDir，开发期形态）
- JDK17 索引 + 语义检索质量达标："memory allocation slow path" →
  `update_slow_allocations` 84%，`safepoint` → `safepoint` 93.5%
- semcode-mcp 已全局注册给 Claude Code（`-d` 钉死单库模式）
- 语义检索在 agent 无关场景验证通过；agent 侧（Claude Code + MCP）待用户验证

## 需求来源：用户 2026-11-14 产品方向反馈

1. **ToolWindow 是产品本体**：不止检索；索引状态、触发、可视化全部收进侧边栏
2. **零配置**：配置模型本地地址是奇怪操作，砍掉；触发索引不能藏在 Tools 菜单
3. **索引数据放 `.idea/`**：随工程走、天然被 ignore，不在仓库根目录添文件
4. **MCP 允许触发重索引**：agent 侧闭环（"重新索引这个工程"一句话完成）
5. **最终用户视角优先**：开发者路径必须显式标注；发布形态零手工步骤
   （不含手动下模型、手动编译、手敲注册命令）

## v1.1：ToolWindow 控制台化（原 05 的 1.2/1.3 并入本版）

- [x] **索引状态卡**（侧边栏顶部）：当前索引状态（MCP `indexing_status`）、
      重建/刷新按钮（2026-11-14 实现；实体计数与"落后 N 提交"待补）
- [x] **库位置迁移**：插件一律 `-d <project>/.idea/semcode.db`（semcode CLI
      的默认行为不变）。旧位置 `.semcode.db` 静默迁移 + 通知（2026-11-14）
- [x] **搜索区**：语义（vgrep）/ 正则（grep）双入口 + 结构化结果列表 +
      点击跳转（文件:行号）（2026-11-14）
- [x] 查询历史（会话内，最近 15 条）（2026-11-14）
- [x] **Settings 裁剪**：删 modelDir（2026-11-14）；binariesDir 保留为
      开发期兜底
- [x] **底层改造**：`SemcodeProcessService` 查询走常驻 semcode-mcp JSON-RPC
      子进程（`SemcodeMcpClient`，懒启动/崩溃重启/随工程释放）（2026-11-14）

**退出标准**：装 zip 即用零配置（开发期除外）；从侧边栏完成
索引 → 检索 → 跳转全流程；索引数据在 `.idea/` 内。
（待 CLion 实测验收）

## v1.2：MCP 写能力与防呆（Rust 侧，可与 v1.1 并行）

- [x] **`index_project` 工具**：触发即返回 + `indexing_status` 轮询；同一库
      并发互斥（best-effort：启动 auto-index 设置 InProgress 前存在小窗口）；
      失败自动回滚本次新增的 git_commits 行（`remove_git_commits`）。
      2026-11-14 端到端实测通过（临时小仓库：源码+向量两阶段完成，
      vgrep JSON 出结果）
- [x] **失败索引的 `git_commits` 毒化清理**（随上条一并实现）
- [x] **MCP 无库目录启动防呆**：启动时 stderr 明确提示"无索引，将后台
      自动索引"；空库查询文案指向 index_project/CLI（不静默）。
      注意：空 schema 目录仍会创建（auto-index 需要），但不再是无声陷阱
- [x] **查询协议纯净**：`vgrep_functions`/`grep_functions` 增 `format:"json"`
      结构化输出（结果带 name/file_path/line_start/相似度/预览/matched_lines），
      供插件消费；修复向量化库函数 14 处 `println!` 污染 MCP stdout 的缺陷
      （改 `tracing::info!` 走 stderr）
- [ ] 状态统计工具：实体计数、索引新旧度（落后提交数、上次索引时间），
      供 agent 文本呈现（未做，v1.2 遗留）

## v1.3：win-x64 打包（"自己用"到"别人用"的分界线，单平台）

> 2026-11-14 用户明确：打包分发不等四平台交叉编译。DevEco 种子用户主力在
> Windows，win-x64 单平台 zip 即可分发；四平台顺延到上架前。

- [ ] zip 捆绑：win-x64 二进制（本机 release 产物，strip + zip 瘦身，当前
      单二进制 ~190MB 未瘦身）+ 模型 30MB + 插件本体
- [ ] **首跑零配置验收**：干净 Windows 机器装 zip → 打开工程 → 侧边栏一键
      索引 → 检索出结果，全程无终端、无下载、无配置
- [ ] 种子用户分发（GitHub Releases zip / 磁盘安装，零审核）

## 四平台交叉编译（原 0.3）：顺延至上架前

- dev/06 workflow 落地 fork 仓库；mac-arm64 / mac-x64 / linux-x64 出包
- 是 Marketplace 多平台兼容声明的前置，不是种子用户分发的前置

## 排序与依赖

| 版本 | 内容 | 依赖 | 粗估（单人 + AI） |
|------|------|------|-------------------|
| v1.1 | ToolWindow 控制台 + .idea 库 + MCP 客户端 | 无，现在可开工 | 1-2 周 |
| v1.2 | MCP 写工具 + 防呆 | 无（Rust 侧，与 v1.1 并行） | 数天 |
| v1.3 | win-x64 捆绑打包 + 种子用户 | 价值验证通过（进行中） | 数天 |
| 阶段 2 | ArkTS | v1.1 的语言注册数据驱动 | 3-5 天 |
| 四平台 CI | mac/linux 出包 | 无硬依赖，择机 | 数天 |
| 阶段 3 | Marketplace 上架 | v1.3 + 四平台 CI | 1 周 |

## 本机环境快照（2026-11-14，Windows）

构建一台机器全通所需的环境事实，供复现：

- DevEco Studio：`E:/devtools/DevEco Studio`（build 243.24978，JBR 21）
- Gradle 9.2.1（wrapper dists 缓存）+ KGP 2.1.0 实测可用
- rustc 1.93.1（需 `RUSTUP_TOOLCHAIN=stable-x86_64-pc-windows-msvc` 显式指定）
- Cargo.lock 的 kstring 已本地降级 2.0.4→2.0.2（上游 lock 需 rustc 1.96+；
  长期解是升工具链并还原 lock）
- protoc 36.2：`E:/devtools/protoc/bin/protoc.exe`（PROTOC 环境变量已丢，
  构建时显式设）
- aws-lc-sys 需要 cmake：用 VS2022 自带的（PATH 之外，需临时加）
- HF 直连不通；hf-mirror.com 可用（模型已就位
  `%LOCALAPPDATA%\semcode\models\model2vec`，3 文件 30MB）
