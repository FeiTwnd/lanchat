# 实施计划（内部）

> 对应 Harness MODE_REQ Plan 阶段。状态在图例中打勾；验证方式全部为 `javac` 编译 + 测试运行。

## 一、文件清单与接口契约（冻结）

### 1. common

| 任务 | 文件 | 关键签名 | 状态 |
|---|---|---|---|
| P1 | `com/chat/common/MessageType.java` | `enum{LOGIN,LOGIN_RESULT,LOGOUT,TEXT_PRIVATE,TEXT_GROUP,USER_LIST,USER_ONLINE,USER_OFFLINE,USER_UPDATE,FILE_REQUEST,FILE_ACCEPT,FILE_REJECT,FILE_CHUNK,FILE_END,FILE_RESULT,HISTORY_REQUEST,HISTORY_RESULT,HEARTBEAT,HEARTBEAT_ACK,SYSTEM,ERROR}` | [x] |
| P1 | `com/chat/common/Message.java` | `abstract class Message implements Serializable`：`getType/ getSender/getReceiver/getTimestamp/getSummary`，静态 `newInstance(long id, MessageType)` | [x] |
| P1 | `com/chat/common/TextMessage.java` | `TextMessage(String sender,String receiver,String content)` + `getContent()` | [x] |
| P1 | `com/chat/common/FileMessage.java` | 文件元信息 + `getChunkIndex/getTotalChunks/getData/getSha256` | [x] |
| P1 | `com/chat/common/SystemMessage.java` | `SystemMessage(String receiver,String content)` | [x] |
| P1 | `com/chat/common/User.java` | `User(String username,String nickname)`，`getSalt/getPasswordHash/setNickname` | [x] |
| P1 | `com/chat/common/Constants.java` | 端口、路径、大小限制、校验正则、消息类型常量 | [x] |
| P1 | `com/chat/common/Result.java` | `Result<T>{success,message,data}` + `ok(T)` / `fail(String)` | [x] |
| P1 | `com/chat/common/Config.java` | `Properties` 读取 `config/chat.properties`，`getInt/get/getBoolean` | [x] |

### 2. exception / util

| 任务 | 文件 | 关键签名 |
|---|---|---|
| P1 | `exception/ChatException.java` | 业务异常基类 |
| P1 | `exception/UserNotFoundException.java` | 用户不存在/不在线 |
| P1 | `exception/FileTransferException.java` | 文件传输异常 |
| P1 | `util/SecurityUtil.java` | `generateSalt()`、`hashPassword(pwd,salt)`、`verifyPassword(raw,stored)`、`sha256Hex(byte[])` |
| P1 | `util/JsonUtil.java` | `toJson(Object)`、`fromJson(String,Class<T>)`（轻量自研，无依赖） |
| P1 | `util/FileUtil.java` | `readAllBytes`、`writeBytes`、`sha256(File)`、`ensureDir(Path)`、`humanSize(long)`、`uniqueTargetPath` |
| P1 | `util/DateUtil.java` | `format(LocalDateTime)`、`parse(String)`、`dayKey()`、`formatDuration(long)` |

### 3. dao

| 任务 | 文件 | 关键签名 |
|---|---|---|
| P2 | `dao/BaseDao.java` | `interface BaseDao<T,ID>`：`save/update/deleteById/findById/findAll/count` |
| P2 | `dao/UserDao.java` | `extends BaseDao<User,String>` + `findByUsername`、`exists` |
| P2 | `dao/UserDaoImpl.java` | 文件实现，`data/users.txt`，格式 `username\tnickname\tsalt\thash\tcreateTime\tlastLoginTime\trole` |
| P2 | `dao/MessageDao.java` | `extends BaseDao<Message,Long>` + `findByUser(String,LocalDateTime,LocalDateTime)`、`findAll(from,to)`、`saveHistory(Message)`、`exportTo(Path,List<Message>)` |
| P2 | `dao/MessageDaoImpl.java` | `data/history/yyyy-MM-dd.log`，行格式 `id\ttimestamp\ttype\tsender\treceiver\tcontent` |
| P2 | `dao/JdbcUserDao.java` | 可选：classpath 存在 JDBC 驱动时启用，`sql/schema.sql` 建表 |

### 4. service

| 任务 | 文件 | 关键签名 |
|---|---|---|
| P3 | `service/UserService.java` | `register/login/updateNickname/updatePassword/deleteUser/findByUsername/listAll`，返回 `Result<T>` |
| P3 | `service/MessageService.java` | `saveMessage`、`queryHistory(user,from,to)`、`exportHistory(user,path)`、`search(keyword)` |
| P3 | `service/FileService.java` | `prepareSend(File)`、`appendChunk(FileMessage)`、`finishReceive()`、校验校验和 |

### 5. server

| 任务 | 文件 | 关键签名 |
|---|---|---|
| P4 | `server/ChatServer.java` | 单例；`start()/stop()`；TCP `ServerSocket` + UDP `DiscoveryResponder`；线程池 |
| P4 | `server/ClientHandler.java` | `Runnable` + `MessageHandler` 实现；`send(Message)` 同步；`close()` |
| P4 | `server/UserManager.java` | `ConcurrentHashMap<String,ClientHandler>`；`online/offline/get/list/broadcast`；`CopyOnWriteArrayList<ServerObserver>` |
| P4 | `server/DiscoveryResponder.java` | UDP 30000 端口应答 |
| P4 | `server/ServerUI.java` | 服务器日志、在线用户表、启停按钮 |
| P4 | `server/HeartbeatScanner.java` | 定时清理超时连接 |

### 6. client

| 任务 | 文件 | 关键签名 |
|---|---|---|
| P5 | `client/ChatClient.java` | `connect(host,port,name)`、`send(Message)`、`addListener(ChatListener)`、`close()`；发送线程池 |
| P5 | `client/ChatListener.java` | `onMessage(Message)` 回调接口 |
| P5 | `client/DiscoveryClient.java` | `discover(timeoutMs)` 返回局域网服务器地址列表 |
| P5 | `client/FileReceiver.java` | 接收分块落盘 + 进度回调 + 校验 |
| P6 | `client/ui/BaseUI.java` | 抽象 `JFrame` 子类：字体、居中、图标、EDT 工具 |
| P6 | `client/ui/LoginUI.java` | 登录/注册/自动发现服务器 |
| P6 | `client/ui/ClientUI.java` | 在线用户 `JTable`、群聊、私聊、文件、历史记录 |
| P6 | `client/ui/PrivateChatUI.java` | 私聊窗口 |
| P6 | `client/ui/GroupChatUI.java` | 群聊窗口 |
| P6 | `client/ui/FileTransferUI.java` | 文件选择 + `JProgressBar` + 传输日志 |
| P6 | `client/ChatClientApp.java` | 启动入口（`client.Main`） |

### 7. test

| 任务 | 文件 |
|---|---|
| P7 | `com/chat/test/TestRunner.java`（零依赖断言 + 汇总）、`UserServiceTest`、`MessageServiceTest`、`FileServiceTest`、`UserDaoTest`、`SecurityUtilTest`、`IntegrationTest` |

## 二、回滚策略

- 项目为全新目录，回滚 = 删除对应包/文件；每阶段产物先编译通过再继续，失败仅回退本次新增文件。
- 数据库为可选路径，主链路只用文件 IO，JDBC 失败自动降级，不影响主流程。
- 运行期数据位于 `data/`，删除该目录即可恢复出厂状态。

## 三、简化预判

- 不为 Service 层引入接口（仅一种实现，接口是冗余抽象），仅 `BaseDao<T,ID>` 保留接口以满足泛型与多实现（文件/JDBC）。
- GUI 复用：私聊/群聊窗口共用 `BaseChatPanel` 的消息渲染逻辑（`appendMessage`），避免三处重复。
- 工具类方法全部 `static`，无状态，无需单例。
