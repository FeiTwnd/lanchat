package com.chat.server;

import com.chat.common.Message;
import com.chat.common.MessageType;

import java.net.Socket;
import java.util.logging.Logger;

/**
 * 消息处理器抽象基类，按消息类型分派到对应的处理方法，未覆写的入口只记录调试日志。
 */
public abstract class AbstractMessageHandler implements MessageHandler {
    protected final Logger logger = Logger.getLogger(getClass().getName());

    /**
     * 按消息类型分派消息。
     *
     * @param message 待分派的消息
     * @param socket 客户端连接
     */
    public final void dispatch(Message message, Socket socket) {
        if (message == null || message.getType() == null) {
            logger.warning("收到空消息或缺少类型，已忽略");
            return;
        }
        MessageType type = message.getType();
        switch (type) {
            case TEXT_PRIVATE:
            case TEXT_GROUP:
                handleTextMessage(message, socket);
                break;
            case USER_LIST:
                handleUserList(message, socket);
                break;
            case FILE_REQUEST:
            case FILE_ACCEPT:
            case FILE_REJECT:
            case FILE_CHUNK:
            case FILE_END:
            case FILE_RESULT:
                handleFileMessage(message, socket);
                break;
            case REGISTER:
            case REGISTER_RESULT:
            case LOGIN:
            case LOGIN_RESULT:
            case LOGOUT:
            case USER_LIST_REQUEST:
            case USER_ONLINE:
            case USER_OFFLINE:
            case USER_UPDATE:
            case PASSWORD_CHANGE:
            case PROFILE_RESULT:
            case HISTORY_REQUEST:
            case HISTORY_RESULT:
            case EXPORT_REQUEST:
            case EXPORT_RESULT:
            case HEARTBEAT:
            case HEARTBEAT_ACK:
            case MSG_ACK:
            case OFFLINE_MESSAGE:
            case SESSION_RESUME:
            case MSG_RECALL:
            case SEARCH_REQUEST:
            case SEARCH_RESULT:
            case READ_RECEIPT:
            case SYSTEM:
            case ERROR:
                handleSystemMessage(message, socket);
                break;
            default:

                logger.warning(() -> "收到未纳入分派规则的消息类型，已拒绝: " + type);
                break;
        }
    }

    /**
     * 处理用户列表消息，默认仅记录日志。
     *
     * @param message 消息对象
     * @param socket 客户端连接
     */
    public void handleUserList(Message message, Socket socket) {
        logger.fine(() -> "未处理用户列表消息: " + message.getType());
    }

    @Override
    public void handleTextMessage(Message message, Socket socket) {
        logger.fine(() -> "未处理文本消息: " + message.getType());
    }

    @Override
    public void handleFileMessage(Message message, Socket socket) {
        logger.fine(() -> "未处理文件消息: " + message.getType());
    }

    @Override
    public void handleSystemMessage(Message message, Socket socket) {
        logger.fine(() -> "未处理控制消息: " + message.getType());
    }

    @Override
    public void onUserOnline(String username, Socket socket) {
        logger.fine(() -> "用户上线: " + username);
    }

    @Override
    public void onUserOffline(String username) {
        logger.fine(() -> "用户下线: " + username);
    }
}
