package com.chat.common;

/**
 * 系统通知消息。
 *
 * <p>职责：承载“XXX 上线了”“文件已保存到 …”“登录失败”等由服务器或本地程序产生的提示，
 * 与用户真实发言区分开，界面可用不同颜色渲染，历史记录也可单独过滤。</p>
 *
 * <p>发送者固定为 {@link Constants#SYSTEM_SENDER}，接收者为空表示广播给所有人。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class SystemMessage extends Message {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250104L;

    /** 通知正文 */
    private final String content;

    /**
     * 构造系统通知。
     *
     * @param receiver 接收者用户名，广播传空字符串
     * @param content  通知正文
     * @throws IllegalArgumentException 当 content 为 null 时抛出
     */
    public SystemMessage(String receiver, String content) {
        if (content == null) {
            throw new IllegalArgumentException("系统通知内容不能为 null");
        }
        setSender(Constants.SYSTEM_SENDER);
        setReceiver(receiver == null ? "" : receiver);
        this.content = content;
    }

    /**
     * 获取通知正文。
     *
     * @return 通知文本
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取消息摘要。
     *
     * @return 通知正文
     */
    @Override
    public String getSummary() {
        return content;
    }
}
