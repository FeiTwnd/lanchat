# 局域网聊天程序

基于 Java SE 的局域网即时通信程序，采用客户端与服务器分离的架构。服务器负责登录校验、消息转发、聊天记录存储和文件传输协调，客户端提供 Swing 图形界面。服务器同时监听 TCP 与 UDP：TCP 承载业务消息，UDP 用于局域网内自动发现服务器。

项目只依赖 JDK 标准库和 MySQL JDBC 驱动，不使用 Netty、Spring、JavaFX 等框架。

## 功能

| 模块 | 功能 |
|---|---|
| 用户 | 注册、登录、修改昵称、修改密码、自定义头像、管理员删除用户 |
| 在线列表 | 按管理员、普通用户、离线分组展示，支持按用户名或昵称过滤 |
| 私聊 | 双击用户打开会话，支持引用、撤回、未读提示和离线消息补投 |
| 群聊 | 所有在线用户参与，支持 `@` 提醒、引用和未读提示 |
| 文件传输 | 分块传输、进度显示、SHA-256 校验、失败清理，单文件上限 200MB |
| 聊天记录 | 存入 MySQL，支持按日期或关键字查询，支持导出为文本 |
| 消息可靠性 | 稳定消息标识、送达确认、去重、离线补投、断线重连 |
| 服务器控制台 | 启停服务、查看在线用户和运行日志 |
| 智能助手 | 配置后注册一个虚拟用户，私聊即可调用扣子工作流问答 |

## 技术栈

- Java 17 及以上，开发验证使用 JDK 21
- Swing 自绘界面，无外部 UI 框架
- TCP 长连接与 UDP 广播
- Java 对象序列化，配合类型白名单和单条消息资源预算
- MySQL 8 存储用户、聊天记录和文件传输日志
- PBKDF2 口令散列，AES-GCM 聊天记录加密

## 运行环境

- JDK 17 或更高版本
- MySQL 8
- Windows、Linux 或 macOS 桌面环境
- 多机通信时处于同一局域网，单机也可运行

## 快速开始

### 1. 初始化数据库

```bash
mysql -u root -p < sql/schema.sql
```

服务器启动时也会执行 `CREATE TABLE IF NOT EXISTS`。手工执行脚本用于首次建库，需要重建时先删除 `lanchat` 数据库。

### 2. 创建配置文件

```bash
cp config/chat.properties.example config/chat.properties
```

至少填写以下项目：

- `db.url`、`db.username`、`db.password`：数据库连接信息
- `security.message.secret`：聊天记录加密口令，必填
- `admin.password`：首次启动时创建管理员账号，可留空

### 3. 编译

在项目根目录执行：

```bash
mkdir -p out/classes
find src/main/java -name '*.java' > /tmp/lanchat-sources.txt
javac -encoding UTF-8 -cp lib/mysql-connector-j-8.2.0.jar -d out/classes @/tmp/lanchat-sources.txt
```

Windows 下把 `find` 命令替换为：

```bat
dir /s /b src\main\java\*.java > sources.txt
javac -encoding UTF-8 -cp lib\mysql-connector-j-8.2.0.jar -d out\classes @sources.txt
```

### 4. 启动服务器

```bash
java -cp "out/classes:lib/mysql-connector-j-8.2.0.jar" com.chat.server.ChatServer
```

Windows 下类路径分隔符改为分号：

```bat
java -cp "out\classes;lib\mysql-connector-j-8.2.0.jar" com.chat.server.ChatServer
```

### 5. 启动客户端

```bash
java -cp out/classes com.chat.client.ChatClientApp
```

客户端可以启动多个实例。需要单机演示时，可用内置服务器模式启动一个客户端：

```bash
java -cp "out/classes:lib/mysql-connector-j-8.2.0.jar" com.chat.client.ChatClientApp --local-server
```

### 在 IntelliJ IDEA 中运行

1. 打开项目根目录，选择 JDK 17 或更高版本。
2. 把 `src/main/java` 标记为 Sources Root，把 `src/test/java` 标记为 Test Sources Root。
3. 把 `lib/mysql-connector-j-8.2.0.jar` 加入项目依赖。
4. 运行 `com.chat.server.ChatServer` 启动服务器，运行 `com.chat.client.ChatClientApp` 启动客户端。
5. 客户端需要多开时，在运行配置中勾选 Allow multiple instances。

所有运行配置的工作目录应保持为项目根目录，程序按工作目录查找 `config/chat.properties` 和 `data/`。

## 配置说明

配置文件为 `config/chat.properties`，修改后需要重启程序。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `server.port` | 9527 | 服务器 TCP 端口 |
| `discovery.port` | 30000 | UDP 自动发现端口 |
| `server.max.connections` | 200 | 最大并发连接数 |
| `heartbeat.interval.ms` | 15000 | 客户端心跳间隔 |
| `heartbeat.timeout.ms` | 60000 | 服务端判定连接超时的时间 |
| `file.received.dir` | `data/received` | 接收文件的保存目录 |
| `file.max.size` | 209715200 | 单文件大小上限，单位字节 |
| `admin.password` | 空 | 首次启动时创建管理员账号，留空则不创建 |
| `db.driver` | `com.mysql.cj.jdbc.Driver` | JDBC 驱动类名 |
| `db.url` | `jdbc:mysql://localhost:3306/lanchat?...` | 数据库地址 |
| `db.username` | root | 数据库用户名 |
| `db.password` | 空 | 数据库口令 |
| `security.message.secret` | 无 | 聊天记录加密口令，必填 |
| `ai.workflow.id` | 空 | 扣子工作流编号 |
| `ai.api.token` | 空 | 扣子个人访问令牌 |
| `ai.api.url` | `https://api.coze.cn/v1/workflow/run` | 工作流接口地址 |

`db.url` 中的 `serverTimezone` 必须与数据库所在机器的时区一致。`ai.workflow.id` 与 `ai.api.token` 任意一项为空时，智能助手不启用。

## 目录结构

```
LANChat/
├── src/main/java/com/chat/
│   ├── common/        消息实体、配置、常量、编解码与反序列化过滤
│   ├── exception/     自定义异常
│   ├── util/          口令散列、消息加解密、文件、日期与导出工具
│   ├── dao/           DAO 接口与 MySQL 实现
│   ├── service/       用户、消息、文件与智能助手业务
│   ├── server/        服务器、连接处理、消息分发、自动发现与控制台
│   └── client/        客户端网络层与 Swing 界面
├── src/test/java/com/chat/test/   单元测试与集成测试
├── config/            配置模板与本地配置
├── sql/schema.sql     建库建表脚本
├── lib/               MySQL JDBC 驱动
├── data/              接收文件与导出记录
├── image/             主界面入口图标，缺失时使用矢量图标
└── docs/              代码导读与测试说明
```

## 测试

项目使用自带的测试运行器，不依赖 JUnit 或 Mockito。

```bash
javac -encoding UTF-8 -cp "out/classes:lib/mysql-connector-j-8.2.0.jar" -d out/test-classes $(find src/test/java -name '*.java')
java -cp "out/classes:out/test-classes:lib/mysql-connector-j-8.2.0.jar" com.chat.test.TestRunner
java -cp "out/classes:out/test-classes:lib/mysql-connector-j-8.2.0.jar" com.chat.test.IntegrationTest
```

单元测试共 57 条，集成测试共 10 条。测试数据库默认使用 `lanchat_test`，可通过系统属性 `lanchat.test.db.url` 覆盖。

Windows 下把源码清单命令换成 `dir /s /b src\main\java\*.java > sources.txt`，类路径分隔符改为分号。测试的环境准备、用例说明和判定标准见 [测试说明](docs/test/测试说明.md)。

## 文档

- [代码导读](docs/代码导读.md)：项目结构、核心链路和关键类说明
- [测试说明](docs/test/测试说明.md)：测试环境、命令、用例和覆盖范围

## 常见问题

**服务器启动后立即退出**

检查 `security.message.secret` 是否填写，数据库是否可连接，`db.url`、用户名和口令是否正确。启动日志会给出具体原因。

**客户端无法连接服务器**

确认服务器已启动，地址和端口正确，服务器主机的防火墙已放行 TCP 9527 入站连接。

**自动发现不到服务器**

部分无线网络会屏蔽 UDP 广播，此时手动填写服务器 IP 和端口。

**中文显示乱码**

源码和配置统一使用 UTF-8。命令行编译必须带 `-encoding UTF-8`，IDE 的文件编码也应设为 UTF-8。

**文件传输失败**

确认文件未超过大小上限，对方在线并接受传输，接收目录可写。传输完成后程序会校验 SHA-256，校验失败的残缺文件会被删除。

**数据如何重置**

停止服务器和客户端，重新执行 `sql/schema.sql` 会保留已有表和数据；需要清空时先删除 `lanchat` 数据库再执行脚本。接收文件与导出记录分别位于 `data/received` 和 `data/export`，可直接清空。
