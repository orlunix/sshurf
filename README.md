# sshurf

带 SSH Socket 转发能力的 Android 浏览器：自带 WebView，全部流量经 SSH 动态转发（`ssh -D` 等价）出网，不用 VpnService、不需要 root。

本仓库同时包含一个早期的 GlobalProtect 一键连接实验模块（`app/`）。

## 模块

| 模块 | 说明 | 状态 |
|------|------|------|
| `app/` | GlobalProtect 一键连接（SAML 自动填表 + 剪贴板 TOTP） | M1 骨架 |
| `ssh-browser/` | SSH 隧道浏览器：自带 WebView，流量走 SSH 动态转发（`ssh -D` 等价），不用 VpnService、不 root | M1 已真机验证 |

## ssh-browser 快速上手

1. 安装 APK，填 SSH 服务器主机/端口/用户名
2. 生成密钥对（或导入已有私钥），公钥加入服务器 `~/.ssh/authorized_keys`
3. 「连接隧道」→「打开浏览器」，出口 IP 即为 SSH 服务器

架构：WebView → `ProxyController`(socks5://127.0.0.1:10808) → 自写极简 SOCKS5 → JSch `direct-tcpip` 通道 → SSH 服务器代连目标。域名在服务器侧解析。

## 文档

- `docs/requirements.md` / `docs/feasibility.md` — 需求与选型
- `docs/app-design.md` — GlobalProtect 自动化设计
- `docs/ssh-browser-design.md` — SSH 隧道浏览器设计（含 JSch 异步 connect 实装教训）

## 构建

```
JAVA_HOME=$PWD/tools/jdk17 tools/gradle-8.9/bin/gradle :ssh-browser:assembleDebug
```

构建工具链（JDK17/Gradle/Android SDK）在 `tools/`，体积大不入库，按需下载。

## 协作

见 `AGENTS.md`（Kimi agent 协作说明）。
