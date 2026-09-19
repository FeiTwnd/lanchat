package com.chat.server;

import com.chat.common.Message;
import com.chat.common.MessageType;

import java.net.Socket;
import java.util.logging.Logger;

/**
 * 消息处理器抽象基类。
 *
 * <p>职责：实现“按类型分派”这一模板逻辑。子类只需实现三个具体处理方法，
 * 由本类负责把收到的消息路由到正确的方法上，从而消除子类中的类型判断分支。</p>
 *
 * <p>设计模式：这是模板方法（Template Method）的实际应用——骨架固定在本类中，
 * 变化的处理细节交给子类。</p>
 *
 * <p>默认行为：基类对所有具体处理方法提供空实现（仅记录日志），
 * 子类按需覆写自己关心的消息类型，符合“最小惊讶”原则：
 * 收到未处理的消息类型不会导致崩溃。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public abstract class AbstractMessageHandler implements MessageHandler {

    /** 日志记录器，供子类复用 */
    protected final Logger logger = Logger.getLogger(getClass().getName());

    /**
     * 按消息类型分派到对应的处理方法。
     *
     * <p>分派规则：</p>
     * <ul>
     *   <li>文本类消息 -> {@link #handleTextMessage(Message, Socket)}</li>
     *   <li>文件类消息 -> {@link #handleFileMessage(Message, Socket)}</li>
     *   <li>其余控制类消息 -> {@link #handleSystemMessage(Message, Socket)}</li>
     * </ul>
     *
     * @param message 待处理消息，为 null 时直接忽略
     * @param socket  消息来源连接
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
            default:
                handleSystemMessage(message, socket);
                break;
        }
    }

    /**
     * 处理在线用户列表消息，默认空实现。
     *
     * <p>单独抽出该方法而不是并入系统消息，是因为用户列表在本系统中
     * 既是服务器推送的数据也是客户端刷新的依据，值得一个独立的扩展点。</p>
     *
     * @param message 用户列表消息
     * @param socket  消息来源连接
     */
    public void handleUserList(Message message, Socket socket) {
        logger.fine(() -> "未处理用户列表消息: " + message.getType());
    }

    /**
     * 处理文本消息，默认空实现。
     *
     * @param message 文本消息
     * @param socket  消息来源连接
     */
    @Override
    public void handleTextMessage(Message message, Socket socket) {
        logger.fine(() -> "未处理文本消息: " + message.getType());
    }

    /**
     * 处理文件消息，默认空实现。
     *
     * @param message 文件消息
     * @param socket  消息来源连接
     */
    @Override
    public void handleFileMessage(Message message, Socket socket) {
        logger.fine(() -> "未处理文件消息: " + message.getType());
    }

    /**
     * 处理系统控制消息，默认空实现。
     *
     * @param message 控制消息
     * @param socket  消息来源连接
     */
    @Override
    public void handleSystemMessage(Message message, Socket socket) {
        logger.fine(() -> "未处理控制消息: " + message.getType());
    }

    /**
     * 用户上线事件，默认空实现。
     *
     * @param username 用户名
     * @param socket   连接
     */
    @Override
    public void onUserOnline(String username, Socket socket) {
        logger.fine(() -> "用户上线: " + username);
    }

    /**
     * 用户下线事件，默认空实现。
     *
     * @param username 用户名
     */
    @Override
    public void onUserOffline(String username) {
        logger.fine(() -> "用户下线: " + username);
    }
}
