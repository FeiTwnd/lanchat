-- 局域网聊天程序数据库初始化脚本
-- 执行方式：mysql -u root -p < sql/schema.sql

CREATE DATABASE IF NOT EXISTS lanchat
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE lanchat;

CREATE TABLE IF NOT EXISTS chat_user
(
    username        VARCHAR(32)  NOT NULL COMMENT '登录用户名',
    nickname        VARCHAR(64)  NOT NULL COMMENT '显示昵称',
    salt            CHAR(32)     NOT NULL COMMENT '密码盐，16 字节的十六进制表示',
    password_hash   VARCHAR(128) NOT NULL COMMENT '口令散列，格式 pbkdf2$迭代数$hex',
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN 或 USER',
    create_time     DATETIME     NULL COMMENT '注册时间',
    last_login_time DATETIME     NULL COMMENT '最近登录时间',
    avatar          MEDIUMTEXT   NULL COMMENT '头像 PNG 的 Base64 文本',
    PRIMARY KEY (username),
    KEY idx_chat_user_nickname (nickname),
    KEY idx_chat_user_role (role)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '用户表';

CREATE TABLE IF NOT EXISTS chat_message
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息编号',
    message_id  VARCHAR(36)  NULL COMMENT '消息稳定标识，UUID',
    msg_type    VARCHAR(32)  NOT NULL DEFAULT 'TEXT_PRIVATE' COMMENT '消息类型，取 MessageType 枚举名',
    sender      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '发送者用户名',
    receiver    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '接收者用户名，空串表示广播',
    content     TEXT         NULL COMMENT '消息内容，文本消息为 AES-GCM 密文',
    file_name   VARCHAR(255) NULL COMMENT '文件名，仅文件消息使用',
    file_size   BIGINT       NULL COMMENT '文件大小，仅文件消息使用',
    sha256      CHAR(64)     NULL COMMENT '文件 SHA-256 校验和，仅文件消息使用',
    delivered   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已投递：0 否，1 是',
    read_flag   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读：0 否，1 是',
    recalled    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已撤回：0 否，1 是',
    create_time DATETIME     NOT NULL COMMENT '消息产生时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_receiver_delivered (receiver, delivered, id),
    KEY idx_chat_message_time (create_time),
    KEY idx_chat_message_sender (sender, create_time),
    KEY idx_chat_message_receiver (receiver, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '聊天消息表';

CREATE TABLE IF NOT EXISTS chat_file_log
(
    transfer_id VARCHAR(64)  NOT NULL COMMENT '传输会话编号',
    sender      VARCHAR(32)  NOT NULL COMMENT '发送者用户名',
    receiver    VARCHAR(32)  NOT NULL COMMENT '接收者用户名',
    file_name   VARCHAR(255) NOT NULL COMMENT '文件名',
    file_size   BIGINT       NOT NULL COMMENT '文件大小，单位字节',
    sha256      CHAR(64)     NULL COMMENT '文件 SHA-256 校验和',
    result      VARCHAR(16)  NOT NULL COMMENT '传输结果：SUCCESS、REJECTED 或 FAILED',
    remark      VARCHAR(255) NULL COMMENT '结果说明',
    finished_at DATETIME     NOT NULL COMMENT '传输结束时间',
    PRIMARY KEY (transfer_id),
    KEY idx_chat_file_log_sender (sender, finished_at),
    KEY idx_chat_file_log_receiver (receiver, finished_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '文件传输日志表';
