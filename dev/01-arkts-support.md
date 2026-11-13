# ArkTS 语言支持评估

> 评估日期：2026-11-13。结论：语法库现成，不需要自写 parser；纯代码量
> 300-500 行，MVP 3-5 个工作日。

## 前提：现成的 ArkTS 语法库

- [tree-sitter-arkts](https://github.com/harmony-contrib/tree-sitter-arkts)
  （crates.io：`tree-sitter-arkts`，0.3.0，2026-09 发布，MIT，约 2000 下载/月）
- fork 自 tree-sitter-typescript，移除 jsx/tsx/flow
- **原生支持 ArkUI 扩展**：`struct` 声明、`@interface` 注解、`Column() { ... }`
  组件块、`@Extend` 前导点链、`import lazy`
- 备选：`tree-sitter-arkts-ng`
- 兼容性风险点：它 dev 测试基于 tree-sitter 0.24，semcode 核心是 0.26
  （`Cargo.toml:34`）。基于 tree-sitter-language ABI，大概率兼容，一次
  `cargo check` 验证即可

## 需要动的代码（照 Python 的模式抄）

| 文件 | 改动 | 规模 |
|------|------|------|
| `Cargo.toml` | 加 `tree-sitter-arkts` 依赖 | 1 行 |
| `src/file_extensions.rs:8` | `SUPPORTED_EXTENSIONS` 加 `"ets"`。**不要**加进 `DEFAULT_EXTENSIONS`（:25），与 `py`/`cpp` 一样按需 `-e` 开启，避免现有用户索引行为突变 | 几行 |
| `src/treesitter_analyzer.rs:42` | `Language::ArkTs` 枚举 + 版本常量（:59-63）+ `extractor_version` match（:68）+ `extractor_versions` 列表（:82）+ 扩展名映射（:120）+ parser/queries 字段与 match 臂（:1643-1658） | ~30 行机械改动 |
| `src/treesitter_analyzer.rs` | `create_arkts_queries()`：函数/注释/类型/装饰器/调用五组查询，参考 Python 版（:1441-1509） | ~80 行，**主要工作量** |
| `src/treesitter_analyzer.rs` | `arkts_owner_qualifier()`（类嵌套限定，参考 `python_owner_qualifier`:6602）+ `this` 接收者约定（:3445 现有 match 只认 `"self"`，TS 系是 `this`）+ `arkts_guard()`（可先返回 None） | ~100 行 |
| `src/file_survey.rs` | `SurveyLanguage::ArkTs`（:23）+ grammar 映射（:90）+ `extract_arkts`（:321）+ `is_basic_type` 臂（:179） | ~80 行 |
| 测试 | 真实 `.ets` 样例的查询测试 + grammar 版本锁测试（仓库 pre-commit 跑 `cargo test`，测试规范必须对齐） | ~半天 |

### create_arkts_queries 需要覆盖的模式

- `struct` 组件声明（ArkUI 页面单元，走 type_query）
- `build()` 方法、类方法、箭头函数、可选参数、泛型
- 装饰器走 Python 的 `macro_query` 模式（装饰器 ≈ 宏位）
- 调用查询：`call_expression` + member expression（`obj.method()`）

## 不用动的部分（架构红利）

- **数据库 schema**：语言通过 `extractor_version` 字符串记录
  （`src/database/schema.rs:901`），表结构语言无关，零迁移
- **向量化**：CodeBERT 处理纯文本，语言无关
- **query/MCP/LSP 二进制**：按名字/内容/向量查，语言无关
- **符号提取**（`src/git.rs:1309`）：被 `is_c_cpp_file` 守卫，天然跳过
- **宏机制**：C/C++ 专属；ArkTS 装饰器走 Python 模式

## 工作量分解

| 阶段 | 内容 | 时间 |
|------|------|------|
| 机械接入 | 枚举/版本/扩展名/依赖/编译跑通 | 半天 |
| 查询编写 | 五组 tree-sitter 查询 + 对真实 `.ets` 调试 | 1-2 天 |
| 语义辅助 | owner qualifier、`this` 接收者、守卫 | 半天-1 天 |
| survey + 测试 | file_survey + 对齐仓库测试规范 | 1 天 |
| 实战验证 | 真实鸿蒙工程索引 + 查询验证 | 半天 |

## 风险

1. **调用图噪音（主要风险）**：`build()` 里的 `Column()`/`Text()` 等 ArkUI
   组件调用会被记为函数调用边。这些是 DSL 不是真函数调用，需要按名字或按
   上下文（struct 体内）过滤，否则调用链查询被 UI 组件污染
2. **语法库成熟度**：0.3.0 还年轻（2026-05 首发），复杂装饰器组合可能有解析
   缺口。tree-sitter 容错解析，缺口只导致个别函数提取不全，不会崩
3. **tree-sitter 核心版本兼容**：见上文，`cargo check` 一次验证

## semcode-lsp 侧配套

`docs/lsp-server.md` 显示 LSP 服务端目前只声明 C/C++（:166-169）。接入
ArkTS 后 LSP 的 filetypes 与查询也要加 `.ets`（若走 LSP 导航路线）。
