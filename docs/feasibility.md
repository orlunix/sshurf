# 技术选型与可行性分析

> 2026-08-08 初版。目标：Microsoft Authenticator + GlobalProtect + 夸克浏览器 组合下的 VPN 半自动连接。
>
> **决策更新（2026-08-08）**：用户已选定**原生 App** 路线（最简实现，允许 WebView），跳过 MacroDroid 原型阶段。详细设计见 `docs/app-design.md`。

## 1. 技术选型对比

### 1.1 MacroDroid（首选）

**形态**：商业化自动化工具，靠「触发器 → 动作」规则驱动，UI 交互走系统无障碍服务。

- 优点
  - 零编译、零签名，配置即所得；1–3 天可出半自动原型
  - 内置 UI Interaction（点击/输入文本/等待控件出现），对夸克这类第三方浏览器有效
  - 触发器丰富（通知监听、剪贴板变化、手动快捷方式），可把「一键」做成桌面快捷方式
  - 出问题随时手工接管，天然符合「半自动、可维护」的约束
- 缺点
  - UI Interaction 依赖坐标/控件文本，夸克或 GlobalProtect 改版即失效，需要维护
  - 无障碍读屏受 Android 版本限制（Android 15 加强了对第三方无障碍服务的限制，需要用户手动在「受限设置」里放行）
  - 读 Authenticator 验证码依赖其通知/剪贴板，若页面内渲染则读屏不稳定
  - 凭据安全：MacroDroid 没有 Keystore 级加密存储，密码只能以变量或加密变量存放，安全性弱于自写 App

### 1.2 自写 Android App（备选 / 终态）

**形态**：Kotlin App，自带 AccessibilityService + Keystore。

- 优点
  - 凭据可入 Android Keystore（硬件级加密），满足安全约束
  - AccessibilityService 代码可控：按控件 ID/文本定位，比 MacroDroid 的脚本式交互稳定
  - 可接管整个状态机（等待 SAML 页 → 填表 → 读码 → 提交），错误可重试、可上报
- 缺点
  - 开发 + 调试无障碍流程成本高（预估 1–2 周）
  - 需要自签名/侧载分发；夸克、GlobalProtect 是黑盒，控件树需逐一 dump 适配
  - 维护负担转移到自己手里，对方 App 升级同样要跟进

### 1.3 PWA

**形态**：网页应用。

- 优点：开发成本最低，跨平台
- 缺点：**不可行**。浏览器沙箱内无法触发其他 App（GlobalProtect）、无法使用无障碍服务、无法读取 Authenticator 验证码。VPN 连接本身是系统级能力，PWA 够不到。仅可作「状态展示/说明页」，不能作为实现路径，予以排除。

### 1.4 结论

| 方案 | 可行性 | 成本 | 稳定性 | 凭据安全 | 结论 |
|------|--------|------|--------|----------|------|
| MacroDroid | 高 | 1–3 天 | 中（UI 改版敏感） | 弱 | **首选原型** |
| 自写 App | 高 | 1–2 周 | 高 | 强（Keystore） | 稳定后升级 |
| PWA | 否 | — | — | — | 排除 |

路径：先用 MacroDroid 做半自动原型验证流程可行，稳定后再评估是否封装为自写 App。

## 2. MacroDroid 第一版配置大纲

> 前置假设待用户确认（见 `docs/requirements.md` 待确认项）。以下按最可能形态设计：Connect 后跳夸克整页登录 + 6 位 TOTP。

### 2.0 环境准备

1. 安装 MacroDroid，授予无障碍服务权限
2. Android 15+ 需在「设置 → 应用 → MacroDroid → 受限设置」中手动允许无障碍
3. 开启 MacroDroid 的通知读取权限（用于捕获 Authenticator 通知）

### 2.1 Macro 结构（单条主 Macro + 手动触发）

**触发器**：桌面快捷方式 / 浮动按钮（一键启动）

**动作序列**：

1. **启动 GlobalProtect**
   - 动作：Launch Application → GlobalProtect
   - 等待主页加载（Wait: 屏幕内容包含 "Connect" / "连接"）
2. **触发 Connect**
   - UI Interaction：点击 Connect 按钮
   - 等待：检测到夸克浏览器进入前台（Trigger-like wait 或轮询包名 `com.quark.browser`）
3. **夸克内填用户名密码**
   - UI Interaction：等待用户名输入框出现 → 填入用户名（存 MacroDroid 变量）
   - UI Interaction：填密码（加密变量；注意 MacroDroid 无 Keystore，风险见 1.1）
   - 点击登录/提交
4. **读取 TOTP 验证码**
   - 方案 A（首选）：监听 Authenticator 的通知复制码，或手动切到 Authenticator 复制 → MacroDroid 监听剪贴板变化取 6 位数字
   - 方案 B：UI Interaction 读屏 Authenticator 列表页抓 6 位码（不稳定，作兜底）
   - 正则提取 `\b\d{6}\b` 存入变量
5. **回填验证码**
   - 切回夸克 → UI Interaction 等待验证码输入框 → 填入变量 → 提交
6. **确认连接**
   - 等待 GlobalProtect 状态变为 Connected（通知监听或读屏）
   - 成功：弹出 Toast「VPN 已连接」；失败/超时（如 60s）：Toast 提示人工接管

### 2.2 容错设计（第一版即内置）

- 每一步等待都设超时，超时即停止并提示人工继续——保证「半自动」而非卡死
- 剪贴板读取前备份原内容、用后恢复，避免覆盖用户数据
- 密码/TOTP 不写入日志；MacroDroid 变量标记为敏感

### 2.3 已知风险

- 夸克 SAML 页面控件可能无稳定 ID，UI Interaction 需按文本/坐标定位，改版即需重配
- 若 2FA 实为推送「批准」而非 TOTP，第 4–5 步改为：监听 Authenticator 推送通知 → 直接点「批准」，流程更简单
- 若 Connect 后是 GlobalProtect 内置 WebView 而非夸克，UI Interaction 目标包名需改，且部分 ROM 对 WebView 无障碍支持差，可能退回坐标点击

## 3. 下一步

1. 用户确认 `docs/requirements.md` 中 4 项待确认问题
2. 在真机上跑通 2.1 的动作序列，逐步调 UI Interaction
3. 记录夸克 / GlobalProtect 的控件 dump，沉淀到 `docs/` 供后续自写 App 复用
