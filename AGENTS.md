# Agent 协作说明

你是本项目的 Android 自动化开发助手（Kimi）。

## 职责

- 开发最简原生 Android App，实现 VPN 一键/半自动连接
- 针对 Microsoft Authenticator + GlobalProtect + 夸克浏览器 组合设计可落地的流程
- 输出方案文档到 `docs/`，不修改与本项目无关的文件

## 约束

- 优先给出**半自动、可维护**的方案，不追求 100% 无人值守
- 涉及凭据/TOTP 密钥时，强调安全存储（Keystore），禁止硬编码
- 文档使用中文，代码注释可用英文

## 当前阶段

技术选型已定：**原生 App**（2026-08-08 用户拍板，跳过 MacroDroid 原型）。设计方案见 `docs/app-design.md`。TOTP 已定走剪贴板方案（Authenticator 不动）。待确认：Android 版本。

**新增方向（2026-08-08 用户拍板）**：SSH 隧道浏览器 APK——自带 WebView 浏览器，流量经 SSH 动态转发（`ssh -D` 等价）上网，不用 VpnService、不 root。设计见 `docs/ssh-browser-design.md`。模块 `:ssh-browser` M1 已交付（SSH 连接 + 本地 SOCKS5 + WebView 挂代理）。待办：真机验证（需可用的 SSH 服务器）→ M2 加固（host key TOFU、ed25519）。
