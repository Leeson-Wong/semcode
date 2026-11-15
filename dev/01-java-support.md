# Java 语言支持评估

> 评估日期：2026-11-15。结论：官方 grammar 成熟，不需要自写 parser；
> 纯代码量 ~350-450 行，MVP 3-4 个工作日（比 ArkTS 少一天：无 ArkUI
> DSL 污染、无组件调用过滤、guard 直接 None）。

## 动机

- jdk17 价值验证正在跑（F:/opensource/jdk17），仓库主体语言不可见是
  tester 的第一反馈
- Java 基本盘巨大，Go 侵蚀服务器市场没有想象中快；企业 Java 存量库
  本身是 semcode 的潜在客群
- 官方 grammar 风险低，先跑顺"加语言"流程，ArkTS 照抄更快
> 顺序决策（2026-11-15 用户拍板）：**Java 第一，ArkTS 第二**

## 前提：现成的 Java 语法库

- [tree-sitter-java](https://github.com/tree-sitter/tree-sitter-java)
  （crates.io：`tree-sitter-java`，最新 **0.23.5**，tree-sitter 官方
  org 维护，MIT）
- 官方 grammar，持续维护多年，无 ArkTS 那种 0.x 年轻版本风险
- 兼容性：同走 `tree-sitter-language` ABI 机制，semcode 核心 0.26
  （`Cargo.toml:34`）下大概率兼容，一次 `cargo check` 验证即可
  （仓库先例：tree-sitter-c 0.24 / tree-sitter-python 0.25 均在
  core 0.26 下工作）

## 需要动的代码（照 Python 的模式抄）

| 文件 | 改动 | 规模 |
|------|------|------|
| `Cargo.toml` | 加 `tree-sitter-java = "0.23"` | 1 行 |
| `src/file_extensions.rs:8` | `SUPPORTED_EXTENSIONS` 加 `"java"`。**不要**加进 `DEFAULT_EXTENSIONS`（:25），避免现有 CLI 用户索引行为突变 | 几行 |
| `src/treesitter_analyzer.rs:42` | `Language::Java` 枚举 + 版本常量 `TREE_SITTER_JAVA_VERSION = "0.23.5"`（:59-63）+ `extractor_version` match（:68）+ `extractor_versions` 列表（:82）+ 扩展名映射 `"java"`（:126） | ~10 行机械改动 |
| `src/treesitter_analyzer.rs:477-560` | `java_parser`/`java_queries` 字段 + `new()` 初始化 + `get_parser`（:1643）/`get_queries`（:1652）match 臂 | ~20 行机械改动 |
| `src/treesitter_analyzer.rs` | `create_java_queries()`：函数/注释/类型/注解/调用五组查询，参考 Python 版（:1441-1509） | ~100 行，**主要工作量** |
| `src/treesitter_analyzer.rs` | `java_owner_qualifier()`（package + 嵌套类限定，参考 `python_owner_qualifier`:6602）+ `this` 接收者约定（:3445 现有 match，Java 同 C++ 用 `"this"`）+ guard 臂（:4704，Java 无条件编译，直接 `None`） | ~100 行 |
| `src/file_survey.rs` | `SurveyLanguage::Java`（:23）+ from_path（:37）+ 错误消息（:79）+ grammar 映射（:90）+ `extract_java`（:428 Python 参考）+ `is_basic_type` 臂（:255：int/long/short/byte/char/boolean/float/double/void/var） | ~90 行 |
| `src/treesitter_analyzer.rs:10438` | 版本锁测试 `grammar_versions_match_the_lockfile` 加 `("tree-sitter-java", TREE_SITTER_JAVA_VERSION)` | 1 行 |
| `plugin/.../SemcodeProcessService.kt:57` | 扩展名串 `"c,h,cc,...,zig"` 加 `"java"`。**插件行为变化 → 升版 0.3.0 重建 zip 并告知用户** | 1 行 |
| MCP 侧 | **零改动**：`index_project`/启动自动索引用 `supported_extensions()`（semcode-mcp.rs:6476,6728），`SUPPORTED_EXTENSIONS` 加了 java 自动生效 | 0 行 |

### create_java_queries 需要覆盖的模式

- **函数**：`method_declaration`（name/parameters/type/body）+
  `constructor_declaration`（无 type 字段 → return_type 缺省 void）。
  无 body 的 abstract/interface/native 方法 MVP 跳过（与 Python 一致
  要求 body）；接口 `default` 方法有 body，能抓到
- **类型**：`class_declaration` / `interface_declaration` /
  `enum_declaration`（body 是 `enum_body`，字段名不同于 class！）/
  `record_declaration`（JDK 14+，jdk17 大量使用）/ 
  `annotation_type_declaration`，各自 name + body
- **注解走 macro_query**（Python 装饰器同位）：`marker_annotation`
  （`@Override`）+ `annotation`（`@SuppressWarnings("x")`）
- **调用**：`method_invocation`（裸调用 + `object: (_) @receiver` 的
  成员调用，capture 名对齐 Python 版）；`object_creation_expression`
  的 `type: (type_identifier)` 记为对构造器的调用边
- **注释**：`line_comment` + `block_comment`（注意 Java 没有名为
  `comment` 的节点，与 C/Python 不同）

### java_owner_qualifier 设计（与 Python 的关键差异）

- **package 从 AST 读，不从文件路径推**：`package_declaration` 的
  scoped_identifier 文本为准。jdk17 的 `src/java.base/share/classes/
  java/lang/String.java` 路径 ≠ 包名，路径推导会错
- 嵌套类链：向上走 `class/interface/enum/record/annotation_type
  _declaration` 的 name 字段，外层在前
- 例：`java.lang.String` 的方法 qualifier = `java.lang.String`；
  静态嵌套类 = `java.lang.Character.UnicodeBlock`
- 无 package 且无嵌套类 → None（同 C）

## 不用动的部分（架构红利，同 ArkTS）

- **数据库 schema**：语言通过 `extractor_version` 字符串记录
  （`src/database/schema.rs:901`），表结构语言无关，零迁移
- **向量化**：CodeBERT 处理纯文本，语言无关
- **query/MCP 二进制**：按名字/内容/向量查，语言无关
- **符号提取**（`src/git.rs:1309`）：被 `is_c_cpp_file` 守卫，天然跳过
- **MCP 索引路径**：见上表，零改动

## 工作量分解

| 阶段 | 内容 | 时间 |
|------|------|------|
| 机械接入 | 枚举/版本/扩展名/依赖/编译跑通 | 半天 |
| 查询编写 | 五组 tree-sitter 查询 + 对真实 .java 调试 | 1 天 |
| 语义辅助 | owner qualifier（package + 嵌套类）、`this` 接收者 | 半天 |
| survey + 测试 | file_survey + 版本锁测试 + 对齐仓库测试规范 | 半天-1 天 |
| 实战验证 | jdk17 增量索引 + 查询验证（find_function/file_survey） | 半天 |
| 插件配套 | extensions 串 + 升版 0.3.0 重建 zip | 半小时 |

## 风险

1. **重载合并噪声**：同名方法不同参数签名在调用边里按名字合并
   （`println` 的 10 个重载 → 一条边）。与 C 同名函数问题同构，
   find_callers 工具描述已注明按名字解析的局限，不新增债务
2. **泛型文本**：return_type/parameters 里的泛型原样保留
   （`Map<String, List<Integer>>`），one_line 折叠空白即可，无需展开
3. **body 字段名差异**：enum_body/interface_body/class_body——
   type_query 必须分别写，漏了会导致类型提取空结果（编译期 query
   报错会兜底，不是静默失败）
4. **匿名类归属**：匿名类方法会命中 function_query，owner 归最近的
   命名类（匿名类自身无 name）。可接受噪声，MVP 不处理
5. **静态导入/继承的隐式调用**：`import static` 的裸调用会记为
   指向同名函数的边，可能是错的 owner——同 C 头文件场景的已知局限

## 验证靶场

- 单测：真实 .java 样例（含 record/enum/嵌套类/注解/构造器/泛型）
- 实战：`F:/opensource/jdk17` 增量索引 `.java` → find_function 查
  `String` 的方法、vgrep、file_survey 一个 java/lang 文件
- jdk17 是理想靶场：record（JDK 16+）、sealed（JDK 17）、
  switch 表达式全都有

## semcode-lsp 侧配套

`docs/lsp-server.md` 显示 LSP 服务端目前只声明 C/C++（:166-169）。
接入 Java 后 LSP 的 filetypes 与查询也要加 `.java`（若走 LSP 导航
路线，MVP 不阻塞）。
