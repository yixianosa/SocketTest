# 双向证书校验（mTLS）方案分析

> **基于**: SocketTest v3.0.1 当前代码基线  
> **分析范围**: TCP Client / TCP Server 两个功能模块

---

## 一、当前 SSL 状态评估

| 模块 | 当前 SSL 支持 | 验证方向 | 具体实现 |
|------|-------------|---------|---------|
| TCP 客户端 | ✅ 已有 | **单向**（客户端验证服务端证书） | `SocketTestClient` + `MyTrustManager` |
| TCP 服务端 | ❌ 无 | 无 | 纯 `ServerSocket`，无 TLS |
| UDP | ❌ 不适用 | 不适用 | UDP 不支持 TLS |

### 客户端现有 SSL 代码路径（`SocketTestClient.java` 约第 200 行）

```java
// Secure 复选框切换 isSecure
private JCheckBox secureButton = new JCheckBox("Secure");
private boolean isSecure = false;
```

```java
// connect() 方法中
if (isSecure) {
    TrustManager[] tm = new TrustManager[]{new MyTrustManager(SocketTestClient.this)};
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(new KeyManager[0], tm, new SecureRandom());  // KeyManager 为空数组
    SSLSocketFactory factory = context.getSocketFactory();
    socket = factory.createSocket(ip, portNo);
}
```

### MyTrustManager 现有能力（`MyTrustManager.java`）

```
checkServerTrusted()  →  委托 SunX509 → 失败则弹出对话框让用户决定是否信任
checkClientTrusted()  →  直接抛异常（未真正处理客户端证书）
getAcceptedIssuers()  →  透传给 SunX509
```

**核心缺口**:
1. 客户端未加载自身证书 → `KeyManager[]` 为空 → 无法向服务端出示证书
2. 服务端完全没有 SSL → 无法接收 TLS 连接，更无法要求客户端证书
3. 无 keystore/truststore 文件选择 UI

---

## 二、方案设计

### 总体思路

```
┌─────────────────────────────────┐     ┌─────────────────────────────────┐
│  TCP 客户端                      │     │  TCP 服务端                      │
│                                 │     │                                 │
│  [Keystore] 客户端证书+私钥       │────▶│  [Truststore] 客户端 CA          │
│  [Truststore] 服务端 CA          │◀────│  [Keystore] 服务端证书+私钥       │
│                                 │     │                                 │
│  SSLSocket                      │     │  SSLServerSocket                 │
│  ↑ setNeedClientAuth(隐含)      │     │  ↑ setNeedClientAuth(true)      │
└─────────────────────────────────┘     └─────────────────────────────────┘
         mTLS 握手过程
    1. ClientHello ──────────▶
    2. ◀───────── ServerHello + ServerCertificate
    3. ◀───────── CertificateRequest（服务端要求客户端证书）
    4. ClientCertificate ──▶（客户端出示证书）
    5. 双向证书验证 ──────▶  完成 TLS 握手
```

### 2.1 新增配置实体

建议新增一个 `SSLConfig` 类，统一承载双方配置：

```java
public class SSLConfig {
    private String keyStorePath;       // Keystore 文件路径
    private String keyStorePassword;   // Keystore 密码
    private String keyPassword;        // 私钥密码（通常与 keystore 密码相同）
    private String trustStorePath;     // Truststore 文件路径
    private String trustStorePassword; // Truststore 密码
    private boolean needClientAuth;    // 是否要求客户端证书（服务端专用）
    private boolean enabled;           // 是否启用 SSL
}
```

### 2.2 客户端改动要点

**文件**: `SocketTestClient.java`

| 改动项 | 说明 |
|--------|------|
| UI 增加 "SSL Config" 按钮 | 弹出配置对话框，让用户选择 keystore/truststore 文件 |
| 替换现有 Secure 复选框 | 改为带详细配置的 SSL 启用机制（可保留复选框作为快捷开关） |
| `connect()` 方法 | 加载 keystore 构建 `KeyManager[]`，加载 truststore 构建 `TrustManager[]` |
| 配置持久化 | 可通过 Java 属性文件记住上次的 keystore/truststore 路径 |

**当前代码改造点**（`SocketTestClient.java` `connect()` 方法相关行）：

```java
// 改动前
TrustManager[] tm = new TrustManager[]{new MyTrustManager(SocketTestClient.this)};
SSLContext context = SSLContext.getInstance("TLS");
context.init(new KeyManager[0], tm, new SecureRandom());

// 改动后
KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
kmf.init(keyStore, keyPassword.toCharArray());
TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
tmf.init(trustStore);
SSLContext context = SSLContext.getInstance("TLS");
context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
```

### 2.3 服务端改动要点

**文件**: `SocketTestServer.java` + `SocketServer.java`

这是**核心改动**，因为当前服务端完全没有 SSL。

| 改动项 | 说明 |
|--------|------|
| UI 增加 "Secure" 选项 | 类似客户端的 Secure 复选框 + SSL Config 按钮 |
| `SocketTestServer.connect()` | 根据是否 SSL 选择创建 `ServerSocket` 或 `SSLServerSocket` |
| `SocketServer` 构造函数 | 接收 `ServerSocket` 基类（多态），无需区分 SSL 与否 |
| `SSLServerSocket.setNeedClientAuth(true)` | 开启客户端证书要求 |
| 连接回显 | `SSLSocket.getSession().getPeerCertificates()` 可在 UI 显示客户端证书信息 |

**注意**: `SocketServer` 内部持有的是 `ServerSocket` 对象，而 `SSLServerSocket` 继承自 `ServerSocket`，所以 `server.accept()` 返回的 `Socket` / `SSLSocket` 也是父子关系。只需在创建时做分支即可，`SocketServer.run()` 中的 `accept()` 和 I/O 逻辑可以复用。

### 2.4 MyTrustManager 改动要点

**文件**: `MyTrustManager.java`

| 改动项 | 原因 |
|--------|------|
| `checkClientTrusted()` 完善 | 目前直接抛异常，应改为与 `checkServerTrusted()` 类似的委托+对话框逻辑 |
| 增加 keystore 加载能力 | 或拆分为新的 `SSLContextBuilder` 工具类 |
| 证书信息展示 | 握手成功后可在 UI 显示对端证书信息 |

### 2.5 UI 改动：SSL 配置对话框

建议新增 `SSLDialog.java`（`swing/` 包），包含：

```
┌──────────────────────────────────────┐
│  SSL Configuration                    │
│                                      │
│  ☑ Enable SSL                        │
│                                      │
│  [Keystore]                          │
│  File:  [__________________] [Browse] │
│  Password: [________________]        │
│                                      │
│  [Truststore]                        │
│  File:  [__________________] [Browse] │
│  Password: [________________]        │
│                                      │
│  ☑ Require Client Certificate        │
│     (Server only)                    │
│                                      │
│            [ OK ]  [ Cancel ]        │
└──────────────────────────────────────┘
```

### 2.6 文件格式支持

| 格式 | JKS（Java KeyStore） | PKCS12 | PEM |
|------|---------------------|--------|-----|
| Java 原生支持 | ✅ java.security.KeyStore | ✅ JDK 9+ 默认 | ❌ 需 BouncyCastle |
| 推荐策略 | **优先支持 PKCS12**（JDK 默认格式），同时兼容 JKS | ✅ | 可在后续迭代中通过 `KeyStore.getInstance("PKCS12")` 加载 PEM 转换后的文件 |

---

## 三、方案对比

### 方案 A: 最小改动（推荐）

**范围**: TCP 客户端和 TCP 服务端都支持 mTLS，但不改动 `MyTrustManager` 核心逻辑

| 步骤 | 文件 | 改动量 |
|------|------|--------|
| 1. 新增 `SSLConfig` 配置类 | `net/sf/sockettest/SSLConfig.java` | 新增 ~60 行 |
| 2. 新增 `SSLDialog` 配置对话框 | `swing/SSLDialog.java` | 新增 ~150 行 |
| 3. 增强 `SocketTestClient.connect()` | `swing/SocketTestClient.java` | 修改 ~30 行 |
| 4. 增强 `SocketTestServer.connect()` + 添加 Secure 控件 | `swing/SocketTestServer.java` | 修改 ~80 行 |
| 5. `SocketServer` 构造函数接收 `ServerSocket` 基类 | `SocketServer.java` | 修改 ~5 行（已有基类引用） |
| 6. 增强 `MyTrustManager.checkClientTrusted()` | `MyTrustManager.java` | 修改 ~20 行 |

**优点**: 改动集中、不影响现有非 SSL 功能、服务端复用已有 I/O 逻辑

### 方案 B: 完整重构（较复杂）

统一提取 SSL 工具方法到 `SSLUtil`，所有面板通过工具类创建 SSLContext，抽出 `KeyStoreManager` 独立管理证书加载/缓存。

**适用场景**: 预期会持续扩展 SSL 功能（如支持 PEM、支持多证书切换）

---

## 四、边界情况与风险

| 风险 | 说明 | 应对 |
|------|------|------|
| **兼容性** | 服务端新增 SSL 后，未打勾 Secure 时仍需保持纯文本 `ServerSocket` | 通过 `if(isSecure)` 分支，不改非 SSL 路径 |
| **密码处理** | keystore 密码在内存中明文持有 | 使用 `char[]` 并在使用后立即清空 |
| **异常处理** | 证书加载失败、SSL 握手失败不能影响程序稳定性 | 捕获异常并弹出对话框提示，不崩溃 |
| **SocketServer 线程** | 现有 `ServerSocket` 引用，`SSLServerSocket` 是其子类可直接替换 | 已验证继承关系兼容 |
| **性能** | SSL 握手有额外延迟 | 桌面测试工具，不影响 |

---

## 五、推荐实施步骤

> 前置说明: 你现有的 `SocketServer` 和 `UdpServer` 都使用 `ServerSocket` / `DatagramSocket` 基类引用，`SSLServerSocket extends ServerSocket`，所以子类替换兼容。

```
Step 1: 新增 SSLConfig 配置类
Step 2: 新增 SSLDialog 配置对话框
Step 3: 改造 SocketTestClient.connect() 支持 mTLS
Step 4: 为 SocketTestServer 添加 SSL 能力
Step 5: 增强 MyTrustManager 支持客户端证书验证
Step 6: 集成测试（明文模式回归 + SSL/mTLS 模式验证）
```

> 各步骤不存在硬依赖时可以并行。

---

## 六、参考: mTLS 与单向 SSL 的核心差异

```
单向 SSL（当前实现）             双向 mTLS（目标）
─────────────────               ─────────────────
客户端验证服务端证书              客户端验证服务端证书
                                 服务端同时验证客户端证书
服务端无需证书                    服务端必须有证书
客户端无需证书                    客户端必须有证书
SSLContext.init(emptyKM, tm, sr)  SSLContext.init(km, tm, sr)
                                  SSLServerSocket.setNeedClientAuth(true)
```

