# 需求摘要

> 来自 2026-08-08 讨论

## 用户目标

做一个 Android 自动化，实现 VPN 一键连接。

## 已确认组件

- **验证器**: Microsoft Authenticator
- **VPN**: GlobalProtect (Palo Alto)
- **浏览器**: 夸克 (Quark)

## 待确认

1. Connect 后是夸克整页打开，还是 GlobalProtect 内置 WebView？
2. 2FA 是 6 位 TOTP 还是 Authenticator 推送「批准」？
3. Android 版本（是否 15+，影响无障碍读屏）
4. Authenticator 中有几个账号

## 推荐实现路径

1. MacroDroid 半自动原型（1–3 天调试）
2. 稳定后可选封装为独立 App
