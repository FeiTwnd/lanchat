# 局域网聊天程序（Java 课程设计 · 题目13：网络聊天程序）

一个基于 **Java SE + Socket + 多线程 + Swing** 的类 QQ 局域网聊天系统，采用 C/S 架构，
纯标准库实现，**不依赖任何第三方框架**（不使用 Netty、Spring、JavaFX）。
代码原创，总计约 11300 行主源码与 2280 行测试代码，全部中文注释。

---

## 一、功能一览

| 模块 | 功能 | 实现要点 |
|---|---|---|
| 局域网自动发现 | 启动客户端自动搜索局域网内的聊天服务器，一键填入地址 | UDP 广播 `LANCHAT_DISCOVER`，服务器应答自身 IP、端口与在线人数；发现失败时可手动输入 IP |
| 用户管理 | 注册、登录、修改昵称、修改密码、按用户名查询、管理员删除用户 | 密码以「随机盐 + SHA-256」存储，不存明文；用户名唯一；同一账号不允许重复登录 |
| 在线用户列表 | 实时展示在线用户，上线/下线/改名自动刷新 | 服务端 `ConcurrentHashMap` 维护在线表，变更后向所有客户端广播列表快照 |
| 私聊 | 双击用户即可一对一聊天 | 消息含发送者/接收者/内容/时间/类型，服务器按接收者定向转发，对方不在线时明确回执失败 |
| 群聊 | 群聊大厅，所有人可见 | 服务器广播给全部在线连接，含用户上下线系统通知 |
| 文件传输 | 发送任意类型文件，带实时进度条 | 64KB 分块传输、接收方确认、SHA-256 完整性校验、失败自动清理残缺文件、单文件上限 200MB |
| 消息持久化 | 聊天记录按天落盘，支持按用户与时间范围查询、关键字检索、导出 | 文件存储 `data/history/yyyy-MM-dd.log`；可选 MySQL 存储 |
| 心跳检测 | 自动剔除掉线连接 | 客户端 15 秒心跳，服务端 60 秒无活跃判定掉线，20 秒扫描一次 |
| 服务器控制台 | 图形界面启停服务、查看日志与在线用户 | Swing 界面 + 观察者模式推送事件，核心逻辑不依赖界面 |

---

## 二、技术要点（对应课程评分点）

| 要求 | 落点 |
|---|---|
| 封装 | `User`、`Message` 等实体全部私有字段 + getter/setter；`clearCredential()` 脱敏 |
| 继承 | `TextMessage`/`FileMessage`/`SystemMessage` 继承 `Message`；`AbstractMessageHandler`；`BaseUI`/`BaseChatPanel` |
| 多态 | `Message.getSummary()` 抽象方法由三个子类各自实现，调用方无需判断具体类型 |
| 抽象类 | `Message`、`AbstractMessageHandler`、`BaseUI`、`BaseChatPanel` |
| 接口 | `MessageHandler`、`BaseDao<T, ID>`、`UserDao`、`MessageDao`、`ChatListener`、`ServerObserver` |
| 泛型 | `BaseDao<T, ID>`、`Result<T>` |
| 集合 | `ConcurrentHashMap`（在线表）、`CopyOnWriteArrayList`（监听器）、`ArrayList`、`Map`、`Set` |
| 异常 | 自定义 `ChatException` / `UserNotFoundException` / `FileTransferException`，配合 try-with-resources |
| 多线程 | 连接处理线程池、发送线程池、心跳调度线程、UDP 应答线程、文件传输独立线程 |
| 网络编程 | `ServerSocket` / `Socket`（TCP）+ `DatagramSocket`（UDP 广播发现） |
| GUI | Swing：`JFrame`、`JPanel`、`JTable`、`JTextPane`、`JProgressBar`、`JPasswordField`、`JSplitPane` |
| 持久化 | 文件 IO（`java.nio.file`）+ 可选 JDBC（MySQL） |
| 设计模式 | 单例（`ChatServer`）、工厂（`ChatMessageFactory`）、模板方法/策略（`AbstractMessageHandler`）、DAO、观察者（`ServerObserver`）、监听器（`ChatListener`） |
| 序列化 | `Message` 实现 `Serializable`，TCP 上使用 `ObjectOutputStream` 逐帧传输 |
| 日志 | `java.util.logging` 记录关键操作 |

---

## 三、运行环境

- **JDK 17 或更高版本**（本项目在 JDK 21 上开发与验证；未使用任何预览特性）
- 操作系统：Windows / Linux / macOS
- 网络：多台机器需处于同一局域网；单机演示亦可
- 无需 Maven/Gradle，无需任何第三方 jar（数据库模式为可选增强）

检查环境：

```bash
java -version
javac -version
```

---

## 四、快速开始

### 方式一：使用脚本（推荐）

Linux / macOS：

```bash
# 1. 编译并运行全部测试，同时打包可执行 jar 到 dist/
./scripts/build.sh

# 2. 启动服务器（图形控制台）
./scripts/run-server.sh
#    或控制台模式（无图形界面，适合远程环境）
./scripts/run-server.sh --console

# 3. 启动客户端（可开多个终端模拟多个用户）
./scripts/run-client.sh
#    单机演示：自动先启动内置服务器再打开客户端
./scripts/run-client.sh --local-server

# 4. 仅运行测试
./scripts/test.sh              # 单元测试 + 集成测试
./scripts/test.sh unit         # 仅 47 个单元测试用例
./scripts/test.sh integration  # 仅 7 个集成测试用例

# 5. 清理构建产物（--all 会同时删除运行期数据）
./scripts/clean.sh
```

Windows：

```bat
scripts\build.bat
scripts\run-server.bat
scripts\run-client.bat
scripts\test.bat
scripts\clean.bat
```

### 方式二：手动命令

```bash
# 编译主源码
mkdir -p build/classes
find src/main/java -name '*.java' > build/main-sources.txt
javac -encoding UTF-8 -d build/classes @build/main-sources.txt

# 启动服务器（图形控制台）
java -cp build/classes com.chat.server.ChatServer

# 启动服务器（控制台模式）
java -cp build/classes com.chat.server.ChatServer --console

# 启动客户端（可多开）
java -cp build/classes com.chat.client.ChatClientApp

# 运行测试
javac -encoding UTF-8 -cp build/classes -d build/test-classes $(find src/test/java -name '*.java')
java -cp build/classes:build/test-classes com.chat.test.TestRunner
java -cp build/classes:build/test-classes com.chat.test.IntegrationTest
```

Windows 下把类路径分隔符 `:` 换成 `;`。

### 方式三：运行已打包的 jar

```bash
java -jar dist/chat-server.jar            # 服务器
java -jar dist/chat-client.jar            # 客户端
java -jar dist/chat-client.jar --help     # 查看命令行帮助
java -jar dist/chat-test.jar              # 运行单元测试
```

### 方式四：在 IntelliJ IDEA 中运行

本项目是普通 Java 工程，不依赖 Maven/Gradle，导入 IDEA 后只需三步。

1. 打开工程：`File -> Open`，选择项目根目录（本工程没有 pom.xml，直接以普通工程打开即可）。
2. 确认 SDK 与源码根：`File -> Project Structure -> Project` 中 SDK 选择 JDK 17 及以上
   （本项目在 JDK 21 上验证）；右键 `src/main/java` 标记为 `Sources Root`，
   右键 `src/test/java` 标记为 `Test Sources Root`。测试源码根未标记时，
   `com.chat.test` 包下的类会全部报红，测试也无法运行。
3. 运行配置：`Run -> Edit Configurations` 新增 `Application` 类型配置，按需填写：

| 配置名 | 主类 | 程序参数 | 说明 |
| --- | --- | --- | --- |
| 服务器（图形控制台） | `com.chat.server.ChatServer` | 无 | 带 Swing 控制台的服务器 |
| 服务器（控制台模式） | `com.chat.server.ChatServer` | `--console` | 无图形界面，适合远程或无显示环境 |
| 客户端（可多开） | `com.chat.client.ChatClientApp` | 无 | 演示多用户必须先允许并行运行 |
| 客户端（含内置服务器） | `com.chat.client.ChatClientApp` | `--local-server` | 单机一键演示 |
| 单元测试（47 用例） | `com.chat.test.TestRunner` | 无 | 项目自带的零依赖测试框架 |
| 集成测试（7 用例） | `com.chat.test.IntegrationTest` | 无 | 内部自行启动服务器与多个客户端 |

本机 `.idea/runConfigurations/` 下已生成上述 6 个配置，打开 IDEA 后可直接在下拉框中选择
（IDE 工程文件按惯例不入库，因此只在本机生效）；若列表中没有出现，按上表手工新增即可。

四个必须注意的坑：

- **工作目录**：每个配置的 `Working directory` 保持默认的 `$PROJECT_DIR$`。程序以系统属性
  `user.dir` 为基准定位 `config/chat.properties` 与 `data/` 目录，工作目录若改成模块目录或
  `out/`，会读不到配置文件而静默退化成内置默认值。
- **多开客户端**：客户端配置需在 `Modify options` 中勾选 `Allow multiple instances`，
  否则第二次运行时 IDEA 会弹出 `Stop and Rerun` 并终止已登录的客户端，无法演示双人聊天。
- **端口占用**：同一时刻只保留一个服务器实例（IDEA 中启动的、`scripts/run-server.sh` 启动的、
  `dist/chat-server.jar` 启动的互斥），否则新实例会因 9527 端口被占用而启动失败，
  运行集成测试前尤其需要检查。
- **编码**：`File -> Settings -> Editor -> File Encodings` 统一选择 UTF-8，源码含中文注释与
  中文界面文案；另外 `config/chat.properties` 由 `Properties.load(InputStream)` 读取，
  该方法按 ISO-8859-1 解码，若要在配置值中写中文，需使用 `\uXXXX` 转义形式。

IDEA 的编译输出目录是 `out/`，与脚本使用的 `build/`、`dist/` 互不干扰，两种方式可混用。

---

## 五、首次使用流程

1. 启动服务器。首次启动会自动创建默认管理员账号：用户名 `admin`，密码 `admin123`（可在
   `config/chat.properties` 中通过 `admin.password` 修改后删除 `data/users.txt` 重新初始化）。
2. 启动客户端，点击「自动发现服务器」；若发现失败，手动填写服务器 IP 与端口 `9527`。
3. 点击「注册新账号」创建普通账号（用户名 3-16 位字母/数字/下划线且以字母开头，密码 6-32 位），
   注册成功后会自动登录。
4. 登录后主界面左侧显示在线用户列表：
   - 双击某个用户 → 打开私聊窗口
   - 「进入群聊大厅」→ 群聊窗口
   - 「发送文件」→ 选择文件并发送，对方确认后开始传输，窗口内显示进度条
   - 「查询聊天记录」→ 按用户名与日期范围检索
   - 「导出我的聊天记录」→ 导出到 `data/export/`
5. 多开几个客户端即可体验多人在线、私聊、群聊与文件互传。

---

## 六、目录结构

```
LANChat/
├── src/main/java/com/chat/            主源码
│   ├── common/        公共模块：消息体系、用户实体、常量、配置、编解码
│   ├── exception/     自定义异常体系
│   ├── util/          工具类：安全、文件、日期
│   ├── dao/           持久化层：泛型 DAO 接口 + 文件实现 + 可选 JDBC 实现
│   ├── service/       业务层：用户、消息、文件三大服务
│   ├── server/        服务器端：主服务、连接处理器、用户管理、UDP 发现、控制台界面
│   └── client/        客户端：网络层 + ui 子包的 Swing 界面
├── src/test/java/com/chat/test/       测试代码（零依赖自研测试框架）
├── scripts/           构建、启动、测试、清理脚本（sh + bat）
├── config/            chat.properties 配置文件
├── sql/               schema.sql 数据库建表脚本（可选加分项）
├── data/              运行期数据（用户数据、聊天记录、接收与导出文件）
├── dist/              构建输出的可执行 jar（构建后生成）
└── docs/              文档
    ├── design/        需求分析、系统设计、数据库设计、实现说明
    ├── test/          测试报告、测试用例表
    ├── report/        课程设计报告
    ├── ppt/           答辩 PPT 大纲
    └── 用户手册.md
```

---

## 七、配置文件说明

配置文件位于 `config/chat.properties`，修改后需重启程序生效。全部配置项都有内置默认值，
缺省时程序仍可正常运行。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `server.port` | 9527 | 服务器 TCP 端口，客户端需一致 |
| `discovery.port` | 30000 | UDP 自动发现端口 |
| `heartbeat.interval.ms` | 15000 | 客户端心跳发送间隔 |
| `heartbeat.timeout.ms` | 60000 | 服务器判定连接掉线的心跳超时阈值，必须明显大于心跳间隔 |
| `server.max.connections` | 200 | 允许的最大并发连接数 |
| `data.dir` | `./data` | 数据根目录（用户数据、聊天记录、导出文件） |
| `file.received.dir` | `./data/received` | 接收文件的保存目录 |
| `file.max.size` | 209715200 | 单文件大小上限（字节），默认 200MB；收发两端均按此校验 |
| `admin.password` | admin123 | 首次启动时创建的默认管理员密码 |
| `db.enabled` | false | 是否启用 MySQL 存储（可选加分项） |
| `db.driver` | com.mysql.cj.jdbc.Driver | JDBC 驱动类名 |
| `db.url` | `jdbc:mysql://localhost:3306/lanchat?...` | 数据库连接地址 |
| `db.username` / `db.password` | root / 空 | 数据库账号密码 |

另支持系统属性 `-Dlanchat.data.dir=<路径>` 临时覆盖数据目录，常用于自动化测试隔离。

### 启用数据库模式（可选）

```bash
# 1. 建库建表
mysql -u root -p < sql/schema.sql

# 2. 修改 config/chat.properties
#    db.enabled=true
#    db.username=你的账号
#    db.password=你的密码

# 3. 把 MySQL 驱动加入类路径后启动服务器
java -cp "build/classes:lib/mysql-connector-j-8.0.33.jar" com.chat.server.ChatServer
```

未显式设置 `db.enabled=true` 时，程序一律使用文件存储，不会尝试连接数据库。

---

## 八、测试

项目采用**零依赖自研测试框架**（`@Test` 注解 + 反射运行器），不引入 JUnit，
保证任何 JDK 环境都能直接编译运行。

```bash
./scripts/test.sh
```

| 测试类型 | 用例数 | 结果 | 覆盖范围 |
|---|---|---|---|
| 单元测试 | 47 | 全部通过 | 密码安全、用户 DAO、用户服务、消息服务与持久化、文件传输 |
| 集成测试 | 7 | 全部通过 | 真实启动服务器 + 多客户端：登录、用户列表刷新、私聊、群聊、文件传输、下线刷新、异常场景 |

详细的用例清单与结果见 `docs/test/测试报告.md` 与 `docs/test/测试用例表.md`。

---

## 九、文档索引

| 文档 | 路径 | 内容 |
|---|---|---|
| 用户手册 | `docs/用户手册.md` | 面向使用者的完整操作指南与常见问题排查 |
| 需求分析 | `docs/design/01-需求分析.md` | 功能/非功能需求、用例图与用例描述 |
| 系统设计 | `docs/design/02-系统设计.md` | 架构图、类图、四张核心时序图、设计模式、线程模型 |
| 数据库与持久化设计 | `docs/design/03-数据库与持久化设计.md` | 文件存储格式、ER 图、表结构、索引与安全设计 |
| 实现说明 | `docs/design/04-实现说明.md` | 文件清单、关键方法签名、关键算法、扩展指南 |
| 测试报告 | `docs/test/测试报告.md` | 测试策略、结果汇总、需求覆盖矩阵、缺陷记录 |
| 测试用例表 | `docs/test/测试用例表.md` | 54 个用例的输入、预期、实际与结论 |
| 课程设计报告 | `docs/report/课程设计报告.md` | 按高校模板撰写的完整报告 |
| 答辩 PPT 大纲 | `docs/ppt/答辩PPT大纲.md` | 逐页大纲、演示脚本、答辩问题准备 |

---

## 十、常见问题

**无法连接服务器**
确认服务器已启动并显示「服务器运行中」；确认 IP 与端口填写正确；确认防火墙未拦截 9527 端口
（Windows 首次启动时需允许 Java 通过防火墙）。

**自动发现不到服务器**
部分无线 AP 开启了客户端隔离或屏蔽 UDP 广播，此时请手动填写服务器 IP 地址。
自动发现只是便捷入口，不影响核心功能。服务器与客户端在同一台机器上时，
程序会额外走一次回环探测，因此在单机演示场景下也能被发现。

**端口被占用**
修改 `config/chat.properties` 中的 `server.port`（客户端登录界面同步修改端口）后重启。

**界面中文显示异常**
代码统一使用 UTF-8，编译时务必带 `-encoding UTF-8`（脚本中已包含）。若在部分 Linux 桌面环境
字体缺失，可安装中文字体包后重启程序。

**文件传输失败**
检查文件是否超过 200MB 上限；确认对方在线且同意接收；接收目录 `data/received` 需可写；
传输完成后程序会自动比对 SHA-256，校验不通过会删除残缺文件并提示失败原因。

**数据在哪里，如何重置**
用户数据 `data/users.txt`，聊天记录 `data/history/`，接收文件 `data/received/`，
导出文件 `data/export/`。执行 `./scripts/clean.sh --all` 可清空全部运行期数据恢复出厂状态。

---

## 十一、说明

- 本项目的已知设计取舍与历史缺陷修复记录见 `docs/test/测试报告.md` 的缺陷记录章节。
- 数据库为可选增强，默认关闭；如需启用请按上文说明操作。
- 全部代码与文档为原创实现，未复制网络或往届代码。
