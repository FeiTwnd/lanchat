package com.chat.client.ui;

/**
 * Swing 富文本转义工具。
 *
 * <p>职责：把用户可控的文本（昵称、聊天正文）转义成可以安全嵌入
 * {@code <html>} 标签的形式。</p>
 *
 * <p><b>为什么必须转义</b>：Swing 的 {@code JLabel}/{@code JTree} 只要文本以
 * {@code <html>} 开头就会按 HTML 解析。昵称与正文都来自使用者输入，
 * 一个叫 {@code <b>} 的昵称足以让整行渲染错乱，含 {@code <table>} 的长正文
 * 更会让单元格布局彻底崩掉——表现为"某个人的名字一出现列表就花了"。
 * 转义后这些字符只会被当成普通文字画出来。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class HtmlEscape {

    /** 私有构造，禁止实例化工具类 */
    private HtmlEscape() {
    }

    /**
     * 转义 HTML 敏感字符。
     *
     * <p>只处理 {@code & < >} 三个：引号与单引号在"标签体文本"位置不具特殊含义
     * （本项目的用法都是把文本放进标签体，不放进属性值），多转义反而会让
     * 显示出来的内容多出 {@code &quot;} 这类可见字符。</p>
     *
     * <p>{@code &} 必须最先替换：否则后续替换产生的 {@code &lt;} 会被再转义成
     * {@code &amp;lt;}，显示成字面的 "&lt;"。</p>
     *
     * @param text 原始文本，可为 null
     * @return 转义后的文本；入参为 null 时返回空串
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 转义并把换行折叠为空格。
     *
     * <p>用于"单行展示"的场合（引用摘要、树节点副标题）：那里没有多行的余地，
     * 留着换行符会让富文本按 HTML 规则折行，把固定行高的列表撑破。</p>
     *
     * @param text 原始文本，可为 null
     * @return 转义且不含换行的文本
     */
    public static String oneLine(String text) {
        return escape(text).replace('\n', ' ').replace('\r', ' ');
    }
}
