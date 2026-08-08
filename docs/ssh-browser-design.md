# SSH 隧道浏览器 APK 设计方案

> 2026-08-08 初版。目标：一个自带浏览器的 APK，浏览器流量经 SSH 动态转发（`ssh -D` 等价）上网，**不使用 VpnService、不需要 root**，与 GlobalProtect 互不干扰。
> 调研结论：市面无现成一体化 APK（VPNoverSSH 走 VPN、SSH Tunnel 要 root、HTTP Injector 类闭源）；可用 ConnectBot + Privacy Browser 组合零代码验证链路；各模块均有开源参考（Privacy Browser 的 WebView+SOCKS、ConnectBot 的 SSH、tailscale-socks5-Android 的无 VPN 本地 SOCKS 架构）。

## 1. 架构

单模块 `:ssh-browser`，纯 Java，无后端：

```
┌──────────────── 本 App ────────────────┐
│ BrowserActivity (WebView)              │
│   └─ ProxyController: socks5://127.0.0.1:10808
│ Socks5Server（自写极简：no-auth + CONNECT）│
│   └─ 每个连接 → JSch direct-tcpip 通道   │
│ SshTunnelService（前台 Service）         │
│   └─ JSch Session 长连接 + 断线重连      │
└────────────────┬───────────────────────┘
                 │ SSH 加密隧道 (TCP 22/443 等)
          ┌──────▼───────┐
          │ SSH 服务器    │──→ 代连目标主机:端口，数据原路返回
          └──────────────┘
```

- **域名在服务器侧解析**（SOCKS5 ATYP=domain 直接传域名 + SSH 服务器侧 connect），内网域名可用，无 DNS 泄漏
- HTTPS 站点端到端 TLS 不受影响，隧道只是字节透传
- 仅本 App 的 WebView 流量进隧道；SSH 连接本身是裸 Socket，不走 WebView 代理，无回环风险

## 2. 模块划分

```
ssh-browser/src/main/java/dev/sshbrowser/
├── MainActivity.java        # 配置（host/port/user/密码/私钥口令）+ 密钥生成/导入 + 连接/断开 + 入口
├── BrowserActivity.java     # WebView + ProxyController 挂 SOCKS5 代理 + 地址栏
├── SshTunnelService.java    # 前台 Service：JSch Session 管理、自动重连、挂 Socks5Server
├── Socks5Server.java        # 极简 SOCKS5 服务端（约 150 行）：握手 + CONNECT → direct-tcpip
├── KeyManager.java          # JSch 生成 RSA-4096 密钥对，私钥入加密存储，公钥展示/复制
├── SshConfig.java           # EncryptedSharedPreferences 读写（主密钥走 Android Keystore）
└── Bus.java                 # 状态/日志回调（连接状态 → MainActivity）
```

依赖：`com.github.mwiede:jsch`（SSH）、`androidx.webkit:webkit`（ProxyController）、`androidx.security:security-crypto`（加密存储）。

## 3. 安全设计（继承 AGENTS.md 硬约束）

- SSH 私钥、密码一律存 `EncryptedSharedPreferences`（AES-256-GCM，主密钥在 Android Keystore）
- **禁止硬编码任何凭据**；私钥在设备上生成，不离开手机；公钥手动加到服务器 `~/.ssh/authorized_keys`
- 优先密钥认证；密码认证仅作可选兜底
- 日志不打印私钥/密码
- **M1 已知让步**：SSH host key 未做校验（`StrictHostKeyChecking=no`），首次连接指纹会打到日志。M2 改为 TOFU（首次记录指纹并持久化，变更时告警），见 §5 风险 R1

## 4. 边界与限制

- 只覆盖 App 内浏览器流量，其他 App 不受影响（设计目标，非缺陷）
- SSH 隧道只转发 TCP；网页浏览（HTTP/HTTPS）无碍，QUIC/WebRTC 自动回退或被内核规避
- 需要前台 Service 保活；切网/息屏断线后自动重连（5s 退避）
- 速度上限取决于 SSH 服务器带宽

## 5. 风险与验证

| # | 风险 | 验证方法 | 兜底 |
|---|------|----------|------|
| R1 | host key 不校验有中间人风险 | M1 仅内网自用，日志记指纹 | M2 TOFU 持久化校验 |
| R2 | 设备 WebView 不支持 `PROXY_OVERRIDE` | 运行时 `WebViewFeature.isFeatureSupported` 检查并提示 | 更新系统 WebView；无其他路径则提示不支持 |
| R3 | 后台被杀导致隧道中断 | 前台 Service + 重连实测 | 通知提示用户手动重开 |
| R4 | ed25519 需 Bouncy Castle，增大包体 | M1 用 RSA-4096（JCE 内置） | M2 按需引入 bcprov 支持 ed25519 |

## 6. 里程碑

- **M1 骨架**：配置页 + 密钥生成/导入 + SSH 连接 + 本地 SOCKS5 + WebView 挂代理，浏览器能打开网页。已交付。
  - 实装教训：mwiede-jsch 在设置了流的情况下 `ChannelDirectTCPIP.connect()` 是**异步**的（立即返回、泵线程里才发 open 请求），必须轮询 `isConnected()/isClosed()` 等通道真正打开后再回 SOCKS 成功包，否则通道被误关、浏览器报 `ERR_CONNECTION_CLOSED`。已通过本机回环测试（curl → 本地 SOCKS → sshd → baidu/ifconfig 均 HTTP 200）验证修复。
- **M2 加固**：host key TOFU、ed25519、SSH 服务器跑在 443 端口的支持、断网自动恢复优化。
- **M3 体验**：书签/首页、连接状态页内提示、代理失败时的友好错误页。
