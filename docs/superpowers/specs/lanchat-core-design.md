# 局域网聊天程序（题目13）内部设计与开发约定

> 本文件为开发过程中的内部约定（对齐全局 Harness 的 MODE_REQ Spec），不参与交付评分，但作为代码一致性的单一事实来源。

## 一、改动范围

全新项目，无既有代码。交付物落盘于 `/home/feitwnd/Code/LANChat`：

- `src/main/java/com/chat/**` 主程序，纯 Java SE，无第三方运行时依赖。
- `src/test/java/com/chat/test/**` 测试程序，零依赖，`main` 可独立运行，同时带 JUnit 5 注解（有 JUnit 时可直接被 IDE 识别）。
- `scripts/**` 构建与启动脚本（`javac`/`java`，不依赖 Maven/Gradle）。
- `config/chat.properties`、`sql/schema.sql`、`data/**` 运行时数据目录。
- `docs/**` 文档体系。

## 二、分层与依赖方向（单向，禁止反向依赖）

```
client(GUI/网络)  ->  service  ->  dao  ->  (文件 / JDBC)
server(网络/管理) ->  service  ->  dao
client / server   ->  common(实体/异常/工具)  <- 所有层
```

- `common` 不依赖任何其它业务包。
- `dao` 只依赖 `common`。
- `service` 依赖 `dao` + `common`。
- `server` / `client` 依赖 `service` + `common`，二者之间不直接依赖。
- GUI 只调用 `ChatClient` 的公开方法，网络收发在后台线程完成，通过监听器回调刷新界面（EDT 安全）。

## 三、协议约定（TCP 对象流 + UDP 发现）

1. 传输方式：Java 原生序列化（`ObjectOutputStream` / `ObjectInputStream`），消息实体实现 `Serializable`，`serialVersionUID` 显式声明。
2. 每帧一个 `Message` 对象，`writeObject` + `flush`；连接建立后服务端先下发 `SYSTEM` 欢迎帧。
3. `Message` 使用工厂方法创建，取消 setter，保证不可变与线程安全。
4. UDP 发现：`DatagramSocket` 广播 `DISCOVER`，服务器 `DiscoveryResponder` 单播回 `ANNOUNCE`（含服务器 IP、TCP 端口、在线人数）；客户端据此自动填充服务器地址。
5. 心跳：客户端定时（默认 15s）发送 `HEARTBEAT`，服务端刷新 `lastActiveTime`；扫描线程清理超时连接。

## 四、业务规则（可判定）

| 编号 | 规则 |
|---|---|
| R1 | 用户名 3-16 字符，仅字母/数字/下划线；昵称 1-20 字符；密码 6-32 字符 |
| R2 | 密码以 `SHA-256(盐 + 密码)` 十六进制小写存储，盐为随机 16 字节，格式 `salt:hash` |
| R3 | 用户名唯一，重复注册抛 `ChatException` |
| R4 | 同一账号重复登录：拒绝第二次登录（返回失败原因） |
| R5 | 私聊接收者必须在线，否则抛 `UserNotFoundException` 并以系统消息回执 |
| R6 | 单文件上限 200MB（`Constants.MAX_FILE_SIZE`），超限抛 `FileTransferException` |
| R7 | 文件分块 8KB，传输完成后比对 SHA-256 校验和，不一致抛 `FileTransferException` |
| R8 | 聊天记录按天落盘 `data/history/yyyy-MM-dd.log`，支持按用户与时间范围查询 |
| R9 | 心跳超时 60s 判定掉线，服务端广播下线系统消息 |
| R10 | 管理员账号 `admin`（首次启动自动创建，初始密码写入 `config/chat.properties`） |

## 五、边界与异常处理

- 空值：所有公开方法入口做参数校验，非法参数抛 `IllegalArgumentException`，业务冲突抛 `ChatException` 子类。
- 并发：在线用户表 `ConcurrentHashMap`；每个连接的发送流加锁（`ClientHandler` 同步发送）；消息历史写入按文件加锁。
- 权限：删除用户、导出全部记录仅限管理员；非管理员调用返回失败。
- IO 失败：统一包装为 `ChatException` / `FileTransferException` 并写 `java.util.logging` 日志，向用户返回失败系统消息。
- 服务器未启动：客户端连接超时 3s，登录界面提示"无法连接服务器"，不抛栈到界面。

## 六、不做范围（Non-Goals）

- 不做加密通信（AES/XOR 仅作为可选说明文档，不实现进主链路）。
- 不做离线消息投递（用户不在线则提示发送失败）。
- 不做图片/表情、语音、视频、消息撤回、已读回执。
- 不做公网穿透、NAT 穿透、多服务器集群。
- 不做 Kotlin/JavaFX/Web 前端，仅 Swing。
- 不引入 Redis/MQ/Netty/Spring 等任何第三方框架（MySQL JDBC 驱动为可选项，classpath 存在时才启用）。

## 七、风险假设（≥3）

| 编号 | 假设 | 触发条件 | 应对 |
|---|---|---|---|
| A1 | 局域网屏蔽 UDP 广播导致自动发现失效 | 学校机房策略或无线 AP 隔离 | 登录界面保留手动填写服务器 IP 的输入框，UDP 仅作加速 |
| A2 | 对象序列化在两端类版本不一致时失败 | 客户端与服务端 jar 不同步 | `serialVersionUID` 固定；反序列化异常捕获后回执系统消息 |
| A3 | 大文件占满磁盘或阻塞 UI | 传输 200MB 文件 | 分块 + 后台线程 + 进度条；服务端限制大小；传输线程池独立 |
| A4 | 心跳与扫描线程未清理导致端口占用 | 强制关闭客户端 | 服务端扫描 + `finally` 关闭资源 + 端口复用（`setReuseAddress`） |
| A5 | GUI 在非 EDT 线程更新导致界面错乱 | 网络线程直接刷新表格 | 所有界面刷新统一 `SwingUtilities.invokeLater` |

## 八、第一性原理与最小实现

本质需求只有一个：**把一段字节可靠地从 A 送到 B，并让双方看得见**。

- 传输层最小实现 = 一条 TCP 长连接 + 一个长度确定的帧，因此选择"对象流逐帧写"而不是自定义协议头/粘包处理，省掉一整套拆包代码。
- 用户列表最小实现 = 服务端维护一张在线表并广播快照，因此不引入数据库作为在线状态的来源（数据库只负责离线持久化）。
- 文件传输最小实现 = 分块 + 校验和 + 结束帧，不引入 FTP/HTTP 服务，避免线程与端口翻倍。
- 简化预判：`MessageService` 与 `FileService` 共用 `MessageDao` 落盘；三个业务 Service 不设接口层（无多实现需求时接口是冗余抽象，仅 `BaseDao<T>` 因泛型与多实现保留接口）。
