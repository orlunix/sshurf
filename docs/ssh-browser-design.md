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

> M2（2026-08-09）重构为「SSH 网络应用门户」：Index 为默认首页（书签即应用），Browser 全屏 + 悬浮按钮回 Index，Config 支持多份 SSH 配置（单选启用）+ 全局密钥，Log 独立全屏页。转发核心（Service/SOCKS/加密存储）未动。

```
ssh-browser/src/main/java/dev/sshbrowser/
├── IndexActivity.java       # 默认首页：状态条 + 网址/搜索 + 书签网格；点书签自动连隧道再打开
├── BrowserActivity.java     # 全屏 WebView + ProxyController；唯一 FAB = 回 Index；本地错误页
├── ConfigActivity.java      # 多份 SSH 配置（单选启用）；认证在编辑弹窗内二选一：密码 或 私钥（导入/生成/复制公钥）
├── LogActivity.java         # 全屏日志：✓绿/✗红 着色、复制全部、清空
├── SshTunnelService.java    # 前台 Service：JSch Session 管理、自动重连、挂 Socks5Server
├── Socks5Server.java        # 极简 SOCKS5：握手 + CONNECT → direct-tcpip（异步 open 轮询确认）
├── Profiles.java            # 多份 SSH 配置存储（加密），含旧单配置迁移
├── BookmarkStore.java       # 书签（名称+URL），普通 SharedPreferences
├── KeyManager.java          # JSch 生成 RSA-4096 密钥对
├── SshConfig.java           # 全局密钥对/口令的加密存储（主密钥走 Keystore）
└── Bus.java                 # 状态/日志总线：300 条环形缓冲，重连回放
```

依赖：`com.github.mwiede:jsch`（SSH）、`androidx.webkit:webkit`（ProxyController）、`androidx.security:security-crypto`（加密存储）。

### UI 约定（M2 定稿）

- 冷启动永远进 Index；后台切回由系统恢复原页面；首次安装无配置直接落 Config
- 网页内默认全屏（隐藏系统栏），唯一控件是右下角 FAB，点按 = 回 Index
- Config 入口在 Index 右上角齿轮；Log 入口在 Config 内
- 网站密码不自建密码库，走系统 Autofill（WebView 原生支持）

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
