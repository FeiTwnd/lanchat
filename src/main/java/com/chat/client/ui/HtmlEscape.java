package com.chat.client.ui;

/**
 * 把普通文本转义成可嵌入 HTML 的形式，供 Swing 标签和提示框显示。
 */
public final class HtmlEscape {
    private HtmlEscape() {
    }

    /**
     * 转义文本中的和号与尖括号。
     *
     * @param text 原始文本，可为 null
     * @return 转义后的文本，入参为 null 时返回空串
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 转义文本并把换行符替换为空格，用于单行提示。
     *
     * @param text 原始文本，可为 null
     * @return 单行转义文本
     */
    public static String oneLine(String text) {
        return escape(text).replace('\n', ' ').replace('\r', ' ');
    }
}
