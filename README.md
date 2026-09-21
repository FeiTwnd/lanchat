# 局域网聊天程序（Java 课程设计 · 题目13：网络聊天程序）

一个基于 **Java SE + Socket + 多线程 + Swing** 的类 QQ 局域网聊天系统，采用 C/S 架构，
纯标准库实现，**不依赖任何第三方框架**（不使用 Netty、Spring、JavaFX）；唯一的外部 jar 是
MySQL 的 JDBC 驱动（`lib/mysql-connector-j-8.2.0.jar`），用于连接本项目唯一的存储——MySQL。
代码原创，总计 19024 行主源码（59 个文件）与 2577 行测试代码（10 个文件），全部中文注释。

---

## 一、功能一览

| 模块 | 功能 | 实现要点 |
|---|---|---|
| 局域网自动发现 | 启动客户端自动搜索局域网内的聊天服务器，一键填入地址 | UDP 广播 `LANCHAT_DISCOVER`，服务器应答自身 IP、端口与在线人数；发现失败时可手动输入 IP |
| 用户管理 | 注册、登录、修改昵称、修改密码、按用户名查询、管理员删除用户 | 口令以 PBKDF2（12 万次迭代）加盐散列存储，不存明文；兼容历史单轮 SHA-256 散列并在登录成功后透明升级；用户名唯一；同一账号不允许重复登录；登录失败 5 次锁定 5 分钟 |
| 在线用户列表 | 好友树形式展示在线用户，按管理员/普通用户/离线分组并显示人数 | 服务端 `ConcurrentHashMap` 维护在线表，变更后向所有客户端广播列表快照；客户端 `JTree` + 自定义 `TreeCellRenderer` 渲染头像、昵称、账号与在线状态 |
| 界面皮肤 | 无系统边框窗口、自绘标题栏与按钮、Java2D 生成头像与图标，零图片资源 | `com.chat.client.ui.theme` 包统一配色、字体与绘制；窗口可拖动、双击最大化、右下角缩放 |
| 私聊 | 双击用户即可一对一聊天 | 消息含发送者/接收者/内容/时间/类型，服务器按接收者定向转发，对方不在线时明确回执失败 |
| 群聊 | 群聊大厅，所有人可见 | 服务器广播给全部在线连接，含用户上下线系统通知；群聊窗口关闭期间累计未读条数并显示在主界面入口按钮上 |
| 文件传输 | 发送任意类型文件，带实时进度条 | 64KB 分块传输、接收方确认、SHA-256 完整性校验、失败自动清理残缺文件、单文件上限 200MB |
| 消息可靠性 | 稳定消息标识、送达确认、离线消息补投、断线重连 | 网络层与服务端已实现：发送侧为每条消息生成稳定 `message_id`，服务端据此幂等去重；接收方确认后置为已投递，未投递的消息在对方下次登录时补投（客户端按标识去重，实现"至少一次投递"）；连接断开后客户端用会话令牌（30 分钟有效）自动重连并恢复登录态。相关界面展示随第 3 轮完善 |
| 消息持久化 | 聊天记录统一存入 MySQL，支持按用户与日期范围查询、导出 | 三张表 `chat_user` / `chat_message` / `chat_file_log`，启动时自动建表；正文以 AES-GCM 密文落库 |
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
| 多线程 | 连接处理线程池（按需创建、空闲回收，上限由 `server.max.connections` 控制）、发送线程池、心跳调度线程、UDP 应答线程、断线重连线程、文件传输独立线程 |
| 网络编程 | `ServerSocket` / `Socket`（TCP）+ `DatagramSocket`（UDP 广播发现） |
| GUI | Swing：无边框 `JFrame` + 自绘标题栏、`JPanel`、`JTree`、`JTextPane`、`JProgressBar`、`JPasswordField`、`JSplitPane`、`JTabbedPane`、`CardLayout` |
| 自绘资源 | Java2D 绘制图标（`Glyphs`）与头像（`AvatarFactory`），不引入任何图片文件；`SkinButton` 自定义绘制四种按钮形态 |
| 线程模型 | 网络回调线程只做协议解码，所有窗口创建、显示与文本渲染统一切换到事件分发线程（EDT） |
| 持久化 | JDBC（MySQL，项目唯一的存储方式；启动时自动建表） |
| 安全 | PBKDF2 口令散列 + 常量时间比较、AES-GCM 正文落盘加密、反序列化白名单与深度/引用/字节上限、控制报文字段数与字符集校验 |
| 设计模式 | 单例（`ChatServer`）、工厂（`ChatMessageFactory`）、模板方法/策略（`AbstractMessageHandler`）、DAO、观察者（`ServerObserver`）、监听器（`ChatListener`） |
| 序列化 | `Message` 实现 `Serializable`，TCP 上使用 `ObjectOutputStream` 逐帧传输；反序列化受 `com.chat.common.*;java.lang.*;java.time.*;java.util.*;!*` 白名单约束 |
| 消息可靠性 | 每条消息带稳定 `message_id`（36 位 UUID 文本），服务端落库前按标识幂等去重，接收方以 `MSG_ACK` 回确认；未投递消息在对方登录后补投，客户端按标识去重，形成"至少一次投递 + 本地去重" |
| 协议类型 | `MessageType` 共 34 项，涵盖登录/注册、用户列表、私聊/群聊、系统通知、文件传输、心跳、历史查询、导出（`EXPORT_REQUEST`/`EXPORT_RESULT`）、消息确认与离线补投等 |
| 日志 | `java.util.logging` 记录关键操作 |

### 界面设计要点

界面全部由代码绘制，**不包含任何图片资源文件**（不使用 `.png`、`.ico`、字体图标等外部素材），
因此不存在素材版权问题，也便于逐行讲解：

| 类 | 职责 |
|---|---|
| `theme/Theme` | 统一配色常量、字体探测（微软雅黑 → 苹方 → Noto Sans CJK → 文泉驿 → 逻辑字体）与少量全局外观键 |
| `theme/Glyphs` | 用 `Painter` 函数式接口 + lambda 描述图标，`Glyphs.of(size, color, painter)` 生成 `Icon` |
| `theme/AvatarFactory` | 绘制统一的默认头像（圆底人形剪影）：在线底色更深并带绿色状态圆点，离线底色更浅、状态点为灰色，管理员带橙色描边，结果按状态缓存 |
| `theme/SkinButton` | 自绘按钮，提供 `PRIMARY` / `NORMAL` / `GHOST` / `DANGER` 四种形态与图标 + 文本布局 |
| `theme/SkinTitleBar` | 自绘标题栏：拖动移动窗口、双击最大化/还原、最小化与关闭按钮 |
| `theme/WindowResizer` | 在 `JLayeredPane` 上叠加右/下/右下三个透明手柄，实现无边框窗口缩放 |
| `BaseUI` | 无边框窗口骨架：根面板背景、描边、标题栏与 `body()` 内容区，统一各窗口结构 |
| `OnlineUserTree` | 好友树：根节点显示在线人数，管理员/普通用户/离线三个分组，关键词过滤与离线置灰 |
| `ClientUI` | 唯一的消息分发者：把网络线程回调切到 EDT，再按类型投递给聊天窗口、文件窗口或好友树 |

---

## 三、运行环境

- **JDK 17 或更高版本**（本项目在 JDK 21 上开发与验证；未使用任何预览特性）
- 操作系统：Windows / Linux / macOS
- 网络：多台机器需处于同一局域网；单机演示亦可
- 数据库：需要一台可连接的 MySQL（本项目唯一的存储方式），建表由程序启动时自动完成
- 无需 Maven/Gradle；需要 MySQL 的 JDBC 驱动 jar，仓库内已提供 `lib/mysql-connector-j-8.2.0.jar`

检查环境：

```bash
java -version
javac -version
```

---

## 四、快速开始

### 方式一：在 IntelliJ IDEA 中运行（推荐）

本项目是普通 Java 工程，不依赖 Maven/Gradle，用 IDEA 打开后按以下步骤操作即可。

1. 打开工程：`File -> Open`，选择项目根目录（本工程没有 pom.xml，直接以普通工程打开即可）。
2. 确认 SDK 与源码根：`File -> Project Structure -> Project` 中 SDK 选择 JDK 17 及以上
   （本项目在 JDK 21 上验证）；确认 `src/main/java` 已标记为 `Sources Root`、
   `src/test/java` 已标记为 `Test Sources Root`。测试源码根未标记时，
   `com.chat.test` 包下的类会全部报红，测试也无法运行。
3. 添加数据库驱动：`File -> Project Structure -> Libraries -> + -> Java`，选择仓库内的
   `lib/mysql-connector-j-8.2.0.jar`（服务器与测试配置需要；只运行客户端可以不添加）。
4. 编译：菜单 `Build -> Rebuild Project`。主源码产物在 `out/production/LANChat`，
   测试源码产物在 `out/test/LANChat`。
5. 运行：在工具栏的运行配置下拉框中选择下表中的任一配置，点击 `Run`。

| 配置名 | 主类 | 程序参数 | 说明 |
| --- | --- | --- | --- |
| 服务器（图形控制台） | `com.chat.server.ChatServer` | 无 | 带 Swing 控制台的服务器 |
| 客户端（可多开） | `com.chat.client.ChatClientApp` | 无 | 演示多用户必须先允许并行运行 |
| 客户端（含内置服务器） | `com.chat.client.ChatClientApp` | `--local-server` | 单机一键演示 |
| 单元测试（47 用例） | `com.chat.test.TestRunner` | 无 | 项目自带的零依赖测试框架 |
| 集成测试（7 用例） | `com.chat.test.IntegrationTest` | 无 | 内部自行启动服务器与多个客户端 |

本机 `.idea/runConfigurations/` 下已生成上述 5 个配置，打开 IDEA 后可直接在下拉框中选择
（IDE 工程文件按惯例不入库，因此只在本机生效）；若列表中没有出现，按上表手工新增即可。

四个必须注意的坑：

- **工作目录**：每个配置的 `Working directory` 保持默认的 `$PROJECT_DIR$`。程序以系统属性
  `user.dir` 为基准定位 `config/chat.properties` 与 `data/` 目录，工作目录若改成模块目录或
  `out/`，会读不到配置文件（`security.message.secret` 随之缺失），服务器将直接拒绝启动。
- **多开客户端**：客户端配置需在 `Modify options` 中勾选 `Allow multiple instances`，
  否则第二次运行时 IDEA 会弹出 `Stop and Rerun` 并终止已登录的客户端，无法演示双人聊天。
- **端口占用**：同一时刻只保留一个服务器实例（IDEA 中启动的与命令行启动的互斥），
  否则新实例会因 9527 端口被占用而启动失败，运行集成测试前尤其需要检查。
- **编码**：`File -> Settings -> Editor -> File Encodings` 统一选择 UTF-8，源码含中文注释与
  中文界面文案；另外 `config/chat.properties` 由 `Properties.load(InputStream)` 读取，
  该方法按 ISO-8859-1 解码，若要在配置值中写中文，需使用 `\uXXXX` 转义形式。

IDEA 的编译输出目录是 `out/production/LANChat`（主源码）与 `out/test/LANChat`（测试源码），
源码本身不产生其它中间目录。

### 方式二：命令行方式（可选）

在 IDEA 中构建过一次之后，也可以直接用命令行运行它的产物。
服务器需要连接数据库，因此类路径中必须包含 JDBC 驱动 jar：

```bash
# 服务器（图形控制台）
java -cp "out/production/LANChat:lib/mysql-connector-j-8.2.0.jar" com.chat.server.ChatServer

# 客户端（可多开）——客户端不连数据库，无需驱动
java -cp out/production/LANChat com.chat.client.ChatClientApp

# 测试
java -cp "out/production/LANChat:out/test/LANChat:lib/mysql-connector-j-8.2.0.jar" com.chat.test.TestRunner
java -cp "out/production/LANChat:out/test/LANChat:lib/mysql-connector-j-8.2.0.jar" com.chat.test.IntegrationTest
```

Windows 下把类路径分隔符 `:` 换成 `;`。

若不想依赖 IDEA 的产物目录，也可以自己编译到临时目录：

```bash
mkdir -p /tmp/lanchat-classes
find src/main/java -name '*.java' > /tmp/lanchat-sources.txt
javac -encoding UTF-8 -cp lib/mysql-connector-j-8.2.0.jar -d /tmp/lanchat-classes @/tmp/lanchat-sources.txt
java -cp "/tmp/lanchat-classes:lib/mysql-connector-j-8.2.0.jar" com.chat.server.ChatServer
```

---

## 五、首次使用流程

0. **首次运行必须先配置 `security.message.secret`**（聊天记录加密口令），否则服务器拒绝启动。
   复制模板后填写即可：

   ```bash
   cp config/chat.properties.example config/chat.properties
   # 至少填写：security.message.secret、db.password，并按实际机器修改 db.url
   ```

1. 启动服务器。启动时会自动建表；若 `admin.password` 已填写且 `admin` 账号不存在，
   则自动创建管理员账号（用户名 `admin`，密码取 `admin.password` 的值）。
   **`admin.password` 留空则不创建任何管理员账号**，只在日志中留下一条告警，程序不提供任何内置默认口令。
2. 启动客户端，点击「自动发现服务器」；若发现失败，手动填写服务器 IP 与端口 `9527`。
3. 点击「注册新账号」创建普通账号（用户名 3-16 位字母/数字/下划线且以字母开头，密码 6-32 位），
   注册成功后会自动登录。
4. 登录后主界面左侧是好友树，按「管理员 / 普通用户 / 离线」分组并显示人数，离线用户置灰保留在树中：
   - 搜索框输入用户名或昵称即可过滤
   - 双击某个用户 → 打开私聊窗口
   - 「进入群聊大厅」→ 群聊窗口
   - 发送文件：在私聊窗口内点击「发送文件」直接把文件发给对方；在群聊窗口内点击
     「发送文件」会先确认接收名单，然后群发给全部在线用户。接收方确认后开始传输，窗口内显示进度条
   - 「查询聊天记录」→ 按用户名与日期范围检索
   - 「导出我的聊天记录」→ 由服务端查库渲染后回传，客户端落盘到本机 `data/export/`
5. 所有窗口无系统边框：按住标题栏拖动可移动窗口，双击标题栏最大化/还原，拖动右边缘、下边缘或右下角可缩放。
6. 多开几个客户端即可体验多人在线、私聊、群聊与文件互传。

---

## 六、目录结构

```
LANChat/
├── src/main/java/com/chat/            主源码（59 个 .java，19024 行）
│   ├── common/        公共模块：消息体系、用户实体、常量、配置、编解码与工厂
│   ├── exception/     自定义异常体系
│   ├── util/          工具类：口令散列与密钥派生、消息加解密、导出渲染、文件、日期
│   ├── dao/           持久化层：泛型 DAO 接口 + MySQL（JDBC）实现
│   ├── service/       业务层：用户、消息、文件三大服务
│   ├── server/        服务器端：主服务、连接处理器、消息处理器、用户管理、UDP 发现、控制台界面
│   └── client/        客户端：网络层 + ui 子包的 Swing 界面
│       └── ui/theme/  界面皮肤：配色与字体、Java2D 图标与头像、自绘按钮与标题栏、窗口缩放手柄
├── src/test/java/com/chat/test/       测试代码（10 个 .java，2577 行；零依赖自研测试框架）
├── config/            chat.properties 与其模板 chat.properties.example
├── sql/               schema.sql 建表与升级脚本（程序启动时会自动建表，此脚本用于手工初始化或重建）
├── lib/               mysql-connector-j-8.2.0.jar（服务器与测试连接数据库所需）
├── data/              运行期数据：仅 data/received（接收文件）与 data/export（导出记录）
└── docs/              文档
    ├── design/        需求分析、系统设计、数据库设计、实现说明
    ├── test/          测试报告、测试用例表、界面走查截图（screenshots）与缺陷证据（evidence）
    ├── report/        课程设计报告
    ├── ppt/           答辩 PPT 大纲
    └── 用户手册.md
```

---

## 七、配置文件说明

配置文件位于 `config/chat.properties`，**修改后需重启程序生效**。仓库内提供了模板
`config/chat.properties.example`（不含任何真实口令），首次使用请复制为 `config/chat.properties` 后再填写：

```bash
cp config/chat.properties.example config/chat.properties
```

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `server.port` | 9527 | 服务器 TCP 端口，客户端需一致 |
| `discovery.port` | 30000 | UDP 自动发现端口 |
| `server.max.connections` | 200 | 允许的最大并发连接数，超出后拒绝新连接 |
| `heartbeat.interval.ms` | 15000 | 客户端心跳发送间隔 |
| `heartbeat.timeout.ms` | 60000 | 服务器判定连接掉线的心跳超时阈值，必须明显大于心跳间隔 |
| `file.received.dir` | `./data/received` | 接收文件的保存目录 |
| `file.max.size` | 209715200 | 单文件大小上限（字节），默认 200MB；收发两端均按此校验 |
| `admin.password` | 空 | 仅用于首次创建管理员：`admin` 账号不存在且此项非空时才创建；**留空则不创建管理员，只在日志中记一条告警**，程序不提供任何内置默认口令 |
| `db.driver` | com.mysql.cj.jdbc.Driver | JDBC 驱动类名 |
| `db.url` | `jdbc:mysql://localhost:3306/lanchat?...` | 数据库连接地址；`serverTimezone` 必须填数据库所在机器的真实时区 |
| `db.username` / `db.password` | root / 空 | 数据库账号密码 |
| `security.message.secret` | 无 | **必填**：聊天记录正文的加密口令。**留空时服务器拒绝启动**；口令变更后此前写入的密文无法解密 |

补充说明：

- 程序以 MySQL 作为唯一存储：用户账号、聊天记录、文件传输登记都在库里；
  磁盘上只保留 `data/received`（接收的文件）与 `data/export`（导出的记录）。
- 数据根目录由 `data.dir` 决定（默认 `./data`），并可用系统属性 `-Dlanchat.data.dir=<路径>` 临时覆盖，
  常用于自动化测试隔离。
- 聊天记录读写使用的数据库配置在所有程序实例间必须一致；`security.message.secret` 也必须一致，
  否则查出来的历史记录会显示为占位文本。

### 时区陷阱（务必核对）

`db.url` 中的 `serverTimezone` 必须填**数据库所在机器**的真实时区。填错会造成库里的时间整体偏移：
例如数据库在东八区而连接串填 `UTC`，程序写入的 09:21 会以 01:21 存进 `DATETIME` 列。
程序自身读写一致（界面显示正常），但用 `mysql` 命令行或图形工具直接查库会整整差 8 小时。

### 数据库准备

```bash
# 1. 建库建表（程序启动时也会自动执行 CREATE TABLE IF NOT EXISTS，此步用于手工初始化或重建）
mysql -u root -p < sql/schema.sql

# 2. 修改 config/chat.properties：填写 db.url / db.username / db.password 与
#    security.message.secret、admin.password

# 3. 启动服务器时把驱动加入类路径（IDEA 中把 lib/mysql-connector-j-8.2.0.jar 加为工程依赖）
java -cp "out/production/LANChat:lib/mysql-connector-j-8.2.0.jar" com.chat.server.ChatServer
```

数据库不可用时服务器会**拒绝启动**并在日志中说明原因（而不是静默换一种存储方式）。
已有数据库升级时，`sql/schema.sql` 末尾以注释形式给出了需要手工执行的 `ALTER TABLE` 语句
（运行期建表只做 `CREATE TABLE IF NOT EXISTS`，不会修改已存在表的列宽）。

---

## 八、测试

项目采用**零依赖自研测试框架**（自定义 `@Test` 注解 + 反射运行器 `TestRunner`），
**不使用 JUnit / Mockito**，保证任何 JDK 环境都能直接编译运行。在 IDEA 的运行配置下拉框中选择
`单元测试（47 用例）` 或 `集成测试（7 用例）` 后点击 `Run` 即可（命令行等价写法见第四节）。

| 测试类型 | 用例数 | 结果 | 覆盖范围 |
|---|---|---|---|
| 单元测试 | 47 | 全部通过 | `SecurityUtilTest` 7、`UserDaoTest` 7、`UserServiceTest` 9、`MessageServiceTest` 12、`FileServiceTest` 12 |
| 集成测试 | 7 | 全部通过 | 真实启动服务器 + 多客户端：登录、用户列表刷新、私聊、群聊、文件传输、下线刷新、异常场景 |
| 组件级界面校验 | 6 | 全部通过 | 默认头像无渐变、在线/离线状态标记与管理员描边、好友树选中行整行浅蓝且无系统外观橙色、选中态像素断言、私聊与群聊窗口的"发送文件"按钮（离屏渲染 + 像素断言，截图见 `docs/test/screenshots/`） |
| 界面走查 | 14 | 上一轮通过，本轮未重跑 | 无边框窗口与拖动缩放、好友树分组、私聊/群聊渲染次数、文件传输进度、离线归组（Xvfb + Robot，上一轮快照） |

自动化测试连接数据库时使用独立测试库（把 `db.url` 中的库名替换为 `lanchat_test` 并附加
`createDatabaseIfNotExist=true`，可用系统属性 `lanchat.test.db.url` 覆盖），
每个用例开始前清空 `chat_user` 与 `chat_message`，避免污染开发库。
最近一次验证结果为单元 47/47、集成 7/7 通过（使用独立库 `lanchat_verify` 以避免并行干扰）。

详细的用例清单与结果见 `docs/test/测试报告.md` 与 `docs/test/测试用例表.md`。

---

## 九、文档索引

| 文档 | 路径 | 内容 |
|---|---|---|
| 用户手册 | `docs/用户手册.md` | 面向使用者的完整操作指南与常见问题排查 |
| 需求分析 | `docs/design/01-需求分析.md` | 功能/非功能需求、用例图与用例描述 |
| 系统设计 | `docs/design/02-系统设计.md` | 架构图、类图、四张核心时序图、设计模式、线程模型 |
| 数据库与持久化设计 | `docs/design/03-数据库与持久化设计.md` | MySQL 存储、ER 图、三张表的表结构、索引与安全设计 |
| 实现说明 | `docs/design/04-实现说明.md` | 文件清单、关键方法签名、关键算法、扩展指南 |
| 测试报告 | `docs/test/测试报告.md` | 测试策略、结果汇总、需求覆盖矩阵、缺陷记录 |
| 测试用例表 | `docs/test/测试用例表.md` | 74 个用例（47 单元 + 7 集成 + 6 组件级界面校验 + 14 上一轮界面走查）的输入、预期、实际与结论 |
| 课程设计报告 | `docs/report/课程设计报告.md` | 按高校模板撰写的完整报告 |
| 答辩 PPT 大纲 | `docs/ppt/答辩PPT大纲.md` | 逐页大纲、演示脚本、答辩问题准备 |

> 说明：`docs/design/`、`docs/report/`、`docs/ppt/` 下的过程文档尚未随本轮"仅 MySQL 存储"的改造同步更新，
> 其中仍可能残留"文件存储""可选数据库"等旧表述，请以本 README、`docs/用户手册.md`
> 与 `docs/superpowers/specs/2026-09-20-docs-facts.md` 为准。

---

## 十、常见问题

**无法连接服务器**
确认服务器已启动并显示「服务器运行中」；确认 IP 与端口填写正确；确认**服务器主机**的防火墙已放行
入站 TCP 9527（Windows 首次启动时需允许 Java 通过防火墙）。客户端主机不需要任何入站规则。

**自动发现不到服务器**
部分无线 AP 开启了客户端隔离或屏蔽 UDP 广播，此时请手动填写服务器 IP 地址。
自动发现只是便捷入口，不影响核心功能。服务器与客户端在同一台机器上时，
程序会额外走一次回环探测，因此在单机演示场景下也能被发现。

**服务器启动即退出**
最常见的原因是 `security.message.secret` 未填写（服务器拒绝启动以加密聊天记录），
其次是数据库不可用或 `db.url`/账号口令不正确。这两类原因都会在启动日志中给出中文说明。

**端口被占用**
修改 `config/chat.properties` 中的 `server.port`（客户端登录界面同步修改端口）后重启。

**界面中文显示异常**
代码统一使用 UTF-8：IDEA 中确认 `File -> Settings -> Editor -> File Encodings` 已设为 UTF-8，
命令行编译时务必带 `-encoding UTF-8`。若在部分 Linux 桌面环境字体缺失，可安装中文字体包后重启程序。

**文件传输失败**
检查文件是否超过 200MB 上限；确认对方在线且同意接收；接收目录 `data/received` 需可写；
传输完成后程序会自动比对 SHA-256，校验不通过会删除残缺文件并提示失败原因。

**数据在哪里，如何重置**
用户账号、聊天记录与文件传输登记都在 MySQL 的 `lanchat` 库中，磁盘上只有 `data/received/`
（接收到的文件）与 `data/export/`（导出的聊天记录）。
停止服务器与客户端后删除 `data/received/`、`data/export/` 下的内容即可清掉本机文件；
账号与聊天记录需在数据库中清理（例如重新执行 `sql/schema.sql` 重建库），
停止服务器后删除数据库中相应的库或表，再重新启动服务器即可恢复到空库状态。
`admin.password` 非空时，服务器启动会重建管理员账号。
删除 IDEA 的 `out/` 目录则等于清理编译产物，重新 `Build -> Rebuild Project` 即可恢复。

---

## 十一、说明

### 11.1 安全边界与已知取舍

**已实现**

- 口令散列：`PBKDF2WithHmacSHA256`，迭代 120000 次、派生 256 位，存储格式 `pbkdf2$120000$<64 位十六进制>`；
  校验使用常量时间比较。历史版本写入的单轮加盐 SHA-256 散列仍可登录，登录成功后自动重算为 PBKDF2 写回，
  用户无需改密。
- 正文落盘加密：`chat_message.content` 以 `AES/GCM/NoPadding` 密文保存，密钥由 `security.message.secret`
  经 `PBKDF2WithHmacSHA256`（固定盐、65536 次迭代）派生；密文前缀 `enc:v1:`，无前缀的值按历史明文处理；
  解密失败显示占位文本并记警告。`security.message.secret` 为空时服务器拒绝启动，不回退到内置默认口令。
- 查询越权防护：历史查询对象一律取服务端登录态，不信任客户端提交的用户名，只能查询本人记录。
- 登录限流：同一「用户名（小写）+ 来源地址」连续失败 5 次锁定 5 分钟，到期自动解锁；
  账号不存在与密码错误的提示完全一致，日志中仍区分记录。
- 反序列化白名单：`com.chat.common.*;java.lang.*;java.time.*;java.util.*;!*`，并限制 `maxdepth=8`、
  `maxrefs=1000`、`maxbytes=4MB`、`maxarray=65536`。
- 协议与文件校验：控制报文字段数上限 16；文件名剔除路径、拒绝 `..` 与控制字符；文件分块数必须与文件大小精确自洽；
  SHA-256 必须为 64 位十六进制；文件传输需先登记且状态转换合法。
- UDP 发现限流：报文长度与格式严格校验，单来源限流 5 次/秒（突发 10），畸形包静默丢弃。

**尚未覆盖的边界（如实说明）**

- **网络传输仍是明文**：上述加密只保护"落盘后的正文"。客户端与服务器之间仍是 Java 原生序列化的
  明文 TCP 报文，在同一局域网内抓包可见消息内容，传输层没有做任何加密或签名。
- 无传输层身份认证与防重放：协议依赖局域网可信任这一前提，不具备跨公网部署的安全强度。

### 11.2 后续可扩展方向

网络层与服务端已具备稳定消息标识、`MSG_ACK` 送达确认与去重、离线消息补投、会话令牌断线重连
（见第一章"消息可靠性"）。以下功能在代码中尚未接线或尚未提供界面入口，当前版本不可用：

- 可靠性剩余部分：历史记录分页及其界面入口（DAO 与服务层已有分页查询方法 `queryPage`，
  但客户端界面仍是一次性查询，没有翻页入口）。
- 消息类型枚举中已定义但**业务尚未接线**的类型：`SEARCH_REQUEST` / `SEARCH_RESULT`（消息搜索界面）、
  `MSG_RECALL`（消息撤回）、`READ_RECEIPT`（已读回执）。`chat_message` 表中的 `read_flag`、`recalled`
  两列同属预留列。
- 界面（第 3 轮）已交付：左右消息气泡（自己靠右、他人靠左、系统消息居中）、多行输入
  （Enter 发送 / Shift+Enter 换行、输入法组合期间不误发）、智能时间戳、空状态、
  群聊入口按钮的未读条数；并修复了文件被拒或失败后发送会话与文件句柄未释放的问题。
- 界面（第 3 轮）尚未落地：私聊与全局未读角标、断线横幅、拖拽与剪贴板发送文件、
  消息搜索界面、消息撤回界面、`@` 提醒、引用回复、表情面板、头像资料卡。

### 11.3 其它

- 本项目的已知设计取舍与历史缺陷修复记录见 `docs/test/测试报告.md` 的缺陷记录章节。
- 本项目以 MySQL 作为唯一存储，不存在"文件存储模式"与 `db.enabled` 开关等历史配置。
- 全部代码与文档为原创实现，未复制网络或往届代码。
