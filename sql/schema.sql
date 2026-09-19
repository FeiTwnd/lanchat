-- ============================================================================
-- 局域网聊天程序 数据库初始化脚本（可选加分项）
-- ============================================================================
-- 说明：
--   1. 本项目只使用数据库存储：用户账号存 chat_user，聊天记录存 chat_message；
--      磁盘上仅保留接收到的文件（data/received）与导出的聊天记录（data/export）。
--      服务器启动时会自动建表（CREATE TABLE IF NOT EXISTS），本脚本用于手工初始化
--      或重建库表，两者结构保持一致。
--   2. 本脚本按 MySQL 8.x 语法编写，字符集统一 utf8mb4 以完整支持中文与特殊符号。
--   3. 执行方式：
--        mysql -u root -p < sql/schema.sql
--      或在 MySQL 客户端中逐段执行。
--   4. 首次启动服务器时程序会自动创建默认管理员账号 admin，
--      其初始密码取自 config/chat.properties 的 admin.password（默认 admin123）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 创建数据库
-- ---------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS lanchat
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE lanchat;

-- ---------------------------------------------------------------------------
-- 表 1：chat_user 用户表
-- 用途：保存账号、昵称、密码散列与盐、角色与登录时间
-- 安全：password_hash 与 salt 分开存储，绝不保存明文密码
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS chat_user;
CREATE TABLE chat_user
(
    username        VARCHAR(32)  NOT NULL COMMENT '登录用户名，全局唯一，作为主键',
    nickname        VARCHAR(64)  NOT NULL COMMENT '显示昵称，可重复',
    salt            CHAR(32)     NOT NULL COMMENT '密码盐（16 字节的十六进制表示，共 32 字符）',
    password_hash   CHAR(64)     NOT NULL COMMENT 'SHA-256(盐||密码) 的十六进制散列，共 64 字符',
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN 管理员 / USER 普通用户',
    create_time     DATETIME     NULL COMMENT '注册时间',
    last_login_time DATETIME     NULL COMMENT '最近一次登录时间',
    PRIMARY KEY (username),
    KEY idx_chat_user_nickname (nickname),
    KEY idx_chat_user_role (role)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '用户表';

-- ---------------------------------------------------------------------------
-- 表 2：chat_message 聊天消息表
-- 用途：持久化聊天记录，支持按用户与时间范围检索、关键字模糊检索
-- 说明：content 字段对文本消息存放正文，对文件消息存放“文件名|大小|校验和”
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS chat_message;
CREATE TABLE chat_message
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息自增编号',
    msg_type    VARCHAR(32)  NOT NULL COMMENT '消息类型，取 MessageType 枚举名，如 TEXT_PRIVATE/TEXT_GROUP/FILE_REQUEST',
    sender      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '发送者用户名，系统消息固定为 SYSTEM',
    receiver    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '接收者用户名，空字符串表示广播',
    content     TEXT         NULL COMMENT '消息内容；文件消息为“文件名|大小|校验和”',
    file_name   VARCHAR(255) NULL COMMENT '文件名（仅文件消息使用）',
    file_size   BIGINT       NULL COMMENT '文件大小（字节，仅文件消息使用）',
    sha256      CHAR(64)     NULL COMMENT '文件 SHA-256 校验和（仅文件消息使用）',
    create_time DATETIME     NOT NULL COMMENT '消息产生时间',
    PRIMARY KEY (id),
    KEY idx_chat_message_time (create_time),
    KEY idx_chat_message_sender (sender, create_time),
    KEY idx_chat_message_receiver (receiver, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '聊天消息表';

-- ---------------------------------------------------------------------------
-- 表 3：chat_file_log 文件传输日志表（可选，用于统计与审计）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS chat_file_log;
CREATE TABLE chat_file_log
(
    transfer_id  VARCHAR(64)  NOT NULL COMMENT '传输会话编号（UUID）',
    sender       VARCHAR(32)  NOT NULL COMMENT '发送者用户名',
    receiver     VARCHAR(32)  NOT NULL COMMENT '接收者用户名',
    file_name    VARCHAR(255) NOT NULL COMMENT '文件名',
    file_size    BIGINT       NOT NULL COMMENT '文件大小（字节）',
    sha256       CHAR(64)     NULL COMMENT '文件校验和',
    result       VARCHAR(16)  NOT NULL COMMENT '传输结果：SUCCESS / REJECTED / FAILED',
    remark       VARCHAR(255) NULL COMMENT '结果说明或失败原因',
    finished_at  DATETIME     NOT NULL COMMENT '传输结束时间',
    PRIMARY KEY (transfer_id),
    KEY idx_chat_file_log_sender (sender, finished_at),
    KEY idx_chat_file_log_receiver (receiver, finished_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '文件传输日志表';

-- ---------------------------------------------------------------------------
-- 初始化数据：默认管理员账号
-- 密码为 admin123 的加盐 SHA-256 值仅为示例，实际由程序启动时自动创建，
-- 此处注释保留以说明表结构的使用方式，避免写入固定散列带来的安全误导。
-- ---------------------------------------------------------------------------
-- INSERT INTO chat_user(username, nickname, salt, password_hash, role, create_time)
-- VALUES('admin', '系统管理员', '<程序运行时随机生成>', '<程序运行时计算>', 'ADMIN', NOW());

-- ---------------------------------------------------------------------------
-- 常用查询示例
-- ---------------------------------------------------------------------------
-- 1. 查询某个用户参与的全部聊天记录（最近 100 条）
-- SELECT * FROM chat_message
--  WHERE sender = 'alice' OR receiver = 'alice' OR receiver = ''
--  ORDER BY create_time DESC LIMIT 100;

-- 2. 按时间范围查询某用户的消息
-- SELECT * FROM chat_message
--  WHERE (sender = 'alice' OR receiver = 'alice')
--    AND create_time BETWEEN '2025-01-01 00:00:00' AND '2025-01-31 23:59:59'
--  ORDER BY create_time;

-- 3. 关键字模糊检索
-- SELECT * FROM chat_message WHERE content LIKE '%课程设计%' ORDER BY create_time;

-- 4. 统计每个用户的发言条数
-- SELECT sender, COUNT(*) AS total FROM chat_message
--  WHERE msg_type IN ('TEXT_PRIVATE', 'TEXT_GROUP')
--  GROUP BY sender ORDER BY total DESC;

-- 5. 统计文件传输成功率
-- SELECT result, COUNT(*) AS total FROM chat_file_log GROUP BY result;
