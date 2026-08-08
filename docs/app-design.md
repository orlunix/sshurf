# 原生 App 设计方案（最简版）

> 2026-08-08。用户已拍板：不做 MacroDroid，直接做原生 APK，越简单越好，可用 WebView。
> 前置阅读：`docs/requirements.md`、`docs/feasibility.md`。
> 决策更新（2026-08-08，二次修订）：TOTP 获取方式按用户要求改为**抓屏 + OCR**——App 通过 MediaProjection 拉起 Authenticator、截取一帧、ML Kit 本地识别 6 位码。风险：Authenticator 若对工作账号设 `FLAG_SECURE`，抓到的帧为黑屏，则此路不通，退回手动输入/剪贴板兜底。

## 1. 核心思路

整个流程里最难自动化的两件事：

1. **读 Microsoft Authenticator 的 TOTP 验证码** —— 读屏不稳定
2. **在夸克浏览器里自动填表** —— 第三方 App 的 WebView 无障碍支持差

最简方案直接把这两件事收进自己 App 内解决：

- **TOTP 走剪贴板**：用户从 Authenticator 复制 6 位码，App 监听剪贴板变化，正则提取 `\b\d{6}\b` 自动回填。不读屏、不碰 Authenticator 数据。（可选升级：若日后重新扫码拿到 `otpauth://` 密钥，可改为 App 内 Keystore 存储 + 自算验证码，连复制这一步都省掉。）
- **SAML 登录用自己的 WebView**：不碰夸克。用户名/密码用 JS 注入自动填，TOTP 直接填，全部在自己进程内完成，无障碍都用不上。
- **GlobalProtect 只做「启动 + 点 Connect」**：这一下用无障碍服务点，或者干脆让用户手点（半自动兜底）。

> 关键前提待验证：GlobalProtect 的 SAML 回调能否被我们自己的 WebView 接住（见 §4 风险 R1）。若不能，退回无障碍填夸克/内置浏览器的方案 B。

## 2. 架构

单模块 Kotlin App，无后端，四个界面：

```
app/
├── MainActivity          # 主界面：一键连接按钮 + 状态显示 + TOTP 当前码
├── SamlWebActivity       # WebView：加载 SAML 页，JS 注入自动填表
├── SettingsActivity      # 配置：用户名、密码、TOTP 密钥导入、VPN portal 地址
├── service/
│   └── VpnAccessibilityService  # 方案 B 用：点 GlobalProtect Connect、填第三方浏览器
└── crypto/
    ├── TotpGenerator     # HMAC-SHA1 TOTP（30s 步长，6 位）
    └── SecretStore       # Keystore + EncryptedSharedPreferences
```

数据流：

```
点「一键连接」
  → 启动 GlobalProtect（Intent 拉起）
  → 点 Connect（无障碍 / 或用户手点）
  → SAML 页出现
      ├─ 方案 A：回调被本 App WebView 接管 → JS 注入填用户名/密码 → 提交 → 填 TOTP → 提交
      └─ 方案 B：无障碍在夸克/GP 内置浏览器里填（需要网页无障碍增强）
  → 轮询 GlobalProtect 状态（通知监听 / 读屏）→ Connected → 通知用户
  → 任一步超时（默认 60s）→ 弹通知提示人工接管
```

## 3. 安全设计（硬约束，来自 AGENTS.md）

- 密码、TOTP 密钥一律存 `EncryptedSharedPreferences`（AES-256-GCM，主密钥放 Android Keystore，支持硬件 backing）
- **禁止硬编码任何凭据**；首次使用在 Settings 里手动录入或扫码导入 `otpauth://`
- 日志不打印密码/TOTP；WebView 的 `WebViewClient` 里过滤掉含凭据的表单数据
- TOTP 码只在内存中短时存在，不持久化

## 4. 风险与验证顺序

| # | 风险 | 验证方法 | 兜底 |
|---|------|----------|------|
| R1 | GP 的 SAML 回调 scheme（如 `globalprotectcallback:`）能否被本 App WebView 拦截并交还给 GP 客户端 | 真机抓一次完整登录流程，看最终重定向 URL | 方案 B：放弃自有 WebView，改用无障碍填 GP 唤起的浏览器 |
| R2 | ~~用户是否还能拿到 TOTP 密钥~~ **已决策（2026-08-08）**：Authenticator 不动，默认剪贴板方案 | — | 可选升级：重新扫码拿 `otpauth://` 后改自算 |
| R3 | 无障碍服务在 Android 15+ 受限 | 确认真机 Android 版本；侧载 App 需在「受限设置」手动放行 | 方案 A 下无障碍只用于点 Connect，可改用户手点，影响最小 |
| R4 | 公司 IdP（Azure AD / Okta 等）登录页 DOM 变化 | JS 注入按 `input[type=email/password]` 等通用选择器写，配置化选择器 | 注入失败时 WebView 照常展示，用户手填（半自动） |

验证顺序建议：~~R2~~（已定：剪贴板方案）→ R1（决定 A/B 路线）→ 再动工写代码。

## 5. 里程碑

- **M1 骨架**：空壳 APK，能拉起 GlobalProtect、监听剪贴板并抓取 6 位验证码显示在界面上。半天。
- **M2 SAML 自动化**：按 R1 结果选 A（WebView 注入填用户名/密码/TOTP）或 B（无障碍填表）。1–3 天，主要在真机调试。
- **M3 状态检测与容错**：连接状态轮询、超时人工接管提示。半天。
- **M4（可选升级）**：导入 `otpauth://` → Keystore 存储 → App 自算 TOTP，省掉手动复制。半天。

总计约 2–4 天，M2 是不确定性大头。

## 6. 待用户确认（动工前）

1. ~~TOTP 密钥能否拿到~~ **已定**：Authenticator 保持现状，默认剪贴板方案（R2 关闭）
2. **Android 版本**：是否 15+？（影响无障碍放行步骤，R3；方案 A 下不阻塞）
3. 动工时告诉我，我先搭 M1 骨架（Gradle 工程 + 拉起 GP + 剪贴板抓码界面）
