# 上架分发路径

> 调研日期：2026-11-13。结论：**只有 JetBrains Marketplace 一条正经上架
> 路径；"上 DevEco 自己的市场"目前不存在**。

## 核心事实：DevEco Studio 的插件市场就是 JetBrains Marketplace

华为官方 FAQ（DevEco Studio 安装插件异常排查）原话：

> 检查 JetBrains 插件市场，确认插件是否与 IDE、SDK 版本兼容，如果看到下载
> 按钮上写明 "Install to DevEco Studio xxx"，一般就是兼容的

即 DevEco Studio 里 Settings → Plugins 的插件浏览器查的就是 JetBrains
Marketplace。插件在市场上声明了 DevEco 兼容后，用户 IDE 内直接搜到、点装，
**自动更新也走市场通道**。

## 华为自己也是这么分发的

HarmonyOS Dev Assistant 插件托管在 plugins.jetbrains.com
（plugin/32572-harmonyos-dev-assistant），DevEco 用户从市场装或磁盘装 zip。
华为**没有**独立的、对第三方开放的 DevEco 插件市场：

- AppGallery → 应用市场，给 App 的
- Component Center / 伙伴 SDK 市场 → 组件与 SDK，不是 IDE 插件
- CodeGenie / DevEco Code → 官方预装

## 推荐上架组合

### 主路径：JetBrains Marketplace

1. JetBrains 账号 → plugins.jetbrains.com 上传插件 zip
2. `plugin.xml` 的 `since-build`/`until-build` 覆盖 DevEco 平台基线
   （**已实测：DevEco Studio 26.0.0.461 = build 243.24978，即 2024.3 系**，
   声明 `since-build = 243` 起步；若同时覆盖主线 IDE 需注意 243 与新版
   API 的兼容范围）
3. 审核一般 1-2 个工作日；上传自动签名（未签名插件在新 IDE 有警告）
4. 兼容声明生效后 DevEco 用户看到 "Install to DevEco Studio" 按钮

### 多渠道分发的注意点

若 `since-build`/`until-build` 无法同时覆盖主流 IntelliJ IDE 与 DevEco
基线（DevEco 基线落后主线一两年），可能需发多个分发渠道（IntelliJ 一个、
Android Studio/DevEco 一个），参考 Rainbow Brackets 的兼容声明做法。

### 辅助路径：GitHub Releases + 磁盘安装

beta 期 / 企业内部分发用，"Install Plugin from Disk" 零审核零门槛。华为
自己的 Dev Assistant 也保留这条路。

## 通用上架（一份插件卖全家桶）

- 插件 IDE 无关：IDEA / CLion / PyCharm / RustRover + DevEco 都能装
- 话术分场景：CLion/IDEA 里卖语义搜索（导航打不过原生）；DevEco 里卖
  ArkTS 语义搜索（独家）
- 真正成本在打包：4 平台二进制 + 模型首次下载机制（见 02 文档）

## 风险提示

JetBrains 审核对 DevEco 这类 fork 的兼容性声明无特殊限制（有先例），但若
插件深度依赖华为私有 API（而非纯平台 API），审核可能要求说明。
