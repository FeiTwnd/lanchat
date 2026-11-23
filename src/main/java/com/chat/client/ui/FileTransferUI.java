package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.MessageType;
import com.chat.util.FileUtil;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件传输窗口，展示收发进度、处理传输请求与结果，并按接收方复用窗口。
 */
public class FileTransferUI extends BaseUI {
    private static final long serialVersionUID = 20250118L;

    /** 按接收方用户名复用的传输窗口。 */
    private static final Map<String, FileTransferUI> WINDOWS = new ConcurrentHashMap<>();

    /** 传输编号到所属窗口的映射，用于分发进度与结果消息。 */
    private static final Map<String, FileTransferUI> TRANSFER_OWNERS = new ConcurrentHashMap<>();

    /** 发送完成后窗口自动关闭的延时，单位毫秒。 */
    private static final int AUTO_CLOSE_DELAY_MS = 2000;

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(com.chat.common.Constants.LOGGER_NAME
                    + ".client.ui.FileTransferUI");

    private final transient ChatClient client;

    private final String peer;

    private final JTextField receiverField = new JTextField(12);

    private transient JPanel topPanel;

    /** 是否由聊天面板直接发起传输，此时隐藏顶部的接收方与文件选择区。 */
    private transient boolean chatInitiated;

    private final JLabel fileLabel = new JLabel("尚未选择文件");

    private final JProgressBar progressBar = new JProgressBar(0, 100);

    private final JLabel progressLabel = new JLabel("等待开始");

    private final JTextArea logArea = new JTextArea(8, 42);

    private transient File selectedFile;

    private FileTransferUI(ChatClient client, String peer) {
        super("文件传输 - " + peer + " - " + Constants.APP_NAME);
        this.client = client;
        this.peer = peer;
        this.receiverField.setText(peer);
        initComponents();
        setSize(640, 420);
        centerOnScreen();
    }

    /**
     * 获取或创建与指定用户的传输窗口。
     *
     * @param client 已登录的客户端
     * @param peer 接收方用户名
     * @return 该接收方对应的传输窗口
     */
    public static FileTransferUI windowFor(ChatClient client, String peer) {
        return WINDOWS.computeIfAbsent(peer, key -> new FileTransferUI(client, key));
    }

    /**
     * 按传输编号查找所属窗口。
     *
     * @param transferId 传输编号
     * @return 所属窗口，编号为空或没有对应窗口时返回 null
     */
    public static FileTransferUI ownerOf(String transferId) {
        return transferId == null ? null : TRANSFER_OWNERS.get(transferId);
    }

    /**
     * 关闭并清理所有传输窗口。
     */
    public static void disposeAll() {
        for (FileTransferUI window : new java.util.ArrayList<>(WINDOWS.values())) {
            window.dispose();
        }
        WINDOWS.clear();
        TRANSFER_OWNERS.clear();
    }

    private void initComponents() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
        top.setBackground(Theme.BG);
        top.add(new JLabel("接收方:"));
        top.add(receiverField);
        SkinButton chooseButton = new SkinButton("选择文件", SkinButton.Kind.NORMAL);
        chooseButton.addActionListener(e -> chooseFile());
        top.add(chooseButton);
        SkinButton sendButton = new SkinButton("发送", SkinButton.Kind.PRIMARY);
        sendButton.addActionListener(e -> startSend());
        top.add(sendButton);
        this.topPanel = top;

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.setBackground(Theme.BG);
        center.add(fileLabel, BorderLayout.NORTH);
        JPanel progressPanel = new JPanel(new BorderLayout(4, 4));
        progressPanel.setBackground(Theme.BG);
        Theme.styleProgressBar(progressBar);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(progressLabel, BorderLayout.SOUTH);
        center.add(progressPanel, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setFont(FONT_NORMAL);

        body().setLayout(new BorderLayout(4, 4));
        body().add(top, BorderLayout.NORTH);
        body().add(center, BorderLayout.CENTER);
        body().add(new JScrollPane(logArea), BorderLayout.SOUTH);
        appendLog("文件传输窗口已就绪。接收到的文件默认保存到 " + client.getReceiveDir());
        appendLog("单个文件大小上限为 " + Constants.MAX_FILE_SIZE_TEXT + "，传输过程显示实时进度");
    }

    private void chooseFile() {
        File file = NativeFileDialog.open(this, "选择要发送的文件", null, null);
        if (file != null && file.isFile()) {
            selectedFile = file;
            fileLabel.setText("已选择: " + selectedFile.getName()
                    + "（" + FileUtil.humanSize(selectedFile.length()) + "）");
            appendLog("已选择文件 " + selectedFile.getAbsolutePath());
        }
    }

    private void startSend() {
        if (selectedFile == null) {
            showError("请先选择要发送的文件");
            return;
        }
        String receiver = receiverField.getText().trim();
        if (receiver.isEmpty()) {
            showError("请填写接收方用户名");
            return;
        }

        // 发起传输并登记编号与当前窗口的关系
        File sending = selectedFile;
        String transferId = client.sendFile(receiver, sending);
        if (transferId == null) {
            progressLabel.setText("发送失败");
            appendLog("发送失败：文件不合法或连接异常");

            showError("发送失败：文件不合法或连接异常（" + sending.getName() + "）");
            return;
        }
        TRANSFER_OWNERS.put(transferId, this);
        progressBar.setValue(0);
        progressLabel.setText("已发送传输请求，等待对方确认……");
        appendLog("已向 " + receiver + " 发起文件传输请求，编号 "
                + transferId.substring(0, 8));
        selectedFile = null;

        fileLabel.setText("已发送: " + sending.getName()
                + "（" + FileUtil.humanSize(sending.length()) + "）");
    }

    /**
     * 更新进度条与进度文字，可在任意线程调用。
     *
     * @param fileName 当前传输的文件名
     * @param progress 完成比例，取值 0 到 1
     * @param text 当前阶段说明
     */
    public void updateProgress(String fileName, double progress, String text) {
        onEdt(() -> {
            int percent = (int) Math.round(Math.max(0, Math.min(1, progress)) * 100);
            progressBar.setValue(percent);
            progressBar.setString(percent + "%");
            progressLabel.setText(fileName + " - " + text);
        });
    }

    /**
     * 向日志区追加一行带时间戳的记录。
     *
     * @param message 日志内容
     */
    public void appendLog(String message) {
        onEdt(() -> {
            logArea.append(logLine(message) + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * 登记传输编号与当前窗口的归属关系。
     *
     * @param transferId 传输编号
     */
    public void registerTransfer(String transferId) {
        if (transferId != null) {
            TRANSFER_OWNERS.put(transferId, this);
        }
    }

    /**
     * 处理收到的文件传输请求，按需询问用户是否接收。
     *
     * @param request 传输请求消息
     */
    public void handleIncomingRequest(FileMessage request) {
        registerTransfer(request.getTransferId());

        boolean selfSend = request.getSender() != null
                && request.getSender().equals(client.getUsername());
        onEdt(() -> {
            appendLog("收到来自 " + request.getSender() + " 的文件: " + request.getSummary());
            progressBar.setValue(0);
            progressLabel.setText(selfSend
                    ? "正在接收: " + request.getFileName() : "等待确认: " + request.getFileName());
        });
        if (selfSend) {
            // 自己发给自己的副本直接接收
            client.respondFileRequest(request, true, "");
            appendLog("这是发给自己的一份副本，已自动接收");
            return;
        }
        boolean accepted = confirm("用户 " + request.getSender() + " 想发送文件\n\n"
                + "文件名: " + request.getFileName() + "\n"
                + "大小: " + FileUtil.humanSize(request.getFileSize()) + "\n\n是否接收？");
        client.respondFileRequest(request, accepted, accepted ? "" : "用户拒绝接收");
        appendLog(accepted ? "已同意接收，开始传输……" : "已拒绝接收该文件");
        if (!accepted) {
            TRANSFER_OWNERS.remove(request.getTransferId());
        }
    }

    /**
     * 处理传输结束的回执，更新状态并按需关闭窗口。
     *
     * @param result 传输结果消息
     */
    public void handleResult(FileMessage result) {
        TRANSFER_OWNERS.remove(result.getTransferId());
        boolean success = result.isAccepted();

        boolean senderSide = !client.getUsername().equals(result.getSender());
        onEdt(() -> {
            progressBar.setValue(success ? 100 : 0);
            progressLabel.setText(success ? "传输完成" : "传输失败");
        });
        appendLog((success ? "[成功] " : "[失败] ") + result.getMessage());
        if (!success) {
            // 失败时释放发送会话并提示原因
            releaseSendSession(result.getTransferId());
            showError(result.getMessage());
            return;
        }
        if (senderSide) {
            // 发送方成功后在延时后关闭窗口
            appendLog("传输已完成，本窗口将在 " + (AUTO_CLOSE_DELAY_MS / 1000) + " 秒后自动关闭");
            scheduleAutoClose();
        }
    }

    /**
     * 释放服务端为失败传输保留的发送会话。
     *
     * @param transferId 传输编号
     */
    private void releaseSendSession(String transferId) {
        try {
            client.getFileService().closeSend(transferId);
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.FINE, "释放文件发送会话失败: " + transferId, e);
        }
    }

    private void scheduleAutoClose() {
        javax.swing.Timer timer = new javax.swing.Timer(AUTO_CLOSE_DELAY_MS, event -> {
            if (TRANSFER_OWNERS.containsValue(this)) {
                // 还有未完成的传输时保持窗口打开
                return;
            }
            dispose();
        });
        timer.setRepeats(false);
        timer.start();
    }

    /**
     * 按文件消息类型分发到对应的处理逻辑。
     *
     * @param message 文件消息
     */
    public void handleFileMessage(FileMessage message) {
        MessageType type = message.getType();
        switch (type) {
            case FILE_REQUEST:
                handleIncomingRequest(message);
                break;
            case FILE_CHUNK:
                updateProgress(message.getFileName(), parseProgress(message.getMessage()),
                        textProgress(message));
                break;
            case FILE_RESULT:
                handleResult(message);
                break;
            case FILE_REJECT:
                appendLog("对方拒绝接收文件: " + message.getMessage());
                onEdt(() -> progressLabel.setText("对方拒绝接收"));
                TRANSFER_OWNERS.remove(message.getTransferId());

                // 对方拒绝后释放发送会话
                releaseSendSession(message.getTransferId());
                break;
            case FILE_ACCEPT:
                appendLog("对方已同意接收，开始发送数据……");
                onEdt(() -> progressLabel.setText("正在传输……"));
                break;
            default:
                appendLog("收到文件消息: " + type.getDescription());
                break;
        }
    }

    /**
     * 解析进度文本，无法解析时返回 0。
     *
     * @param text 进度文本
     * @return 完成比例
     */
    private double parseProgress(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException | NullPointerException e) {
            return 0.0;
        }
    }

    /**
     * 生成已传输块数的进度文字。
     *
     * @param message 文件分块消息
     * @return 形如 3/10 块的说明文本
     */
    private String textProgress(FileMessage message) {
        int total = Math.max(1, message.getTotalChunks());
        int current = Math.min(total, message.getChunkIndex() + 1);
        return "已传输 " + current + "/" + total + " 块";
    }

    /**
     * 关闭窗口并清理该窗口持有的窗口映射与传输记录。
     */
    @Override
    public void dispose() {
        WINDOWS.remove(peer);
        // 移除所有归属当前窗口的传输编号
        List<String> owned = TRANSFER_OWNERS.entrySet().stream()
                .filter(entry -> entry.getValue() == this)
                .map(Map.Entry::getKey)
                .toList();
        owned.forEach(TRANSFER_OWNERS::remove);
        super.dispose();
    }

    /**
     * 直接发送指定文件，供聊天面板调用。
     *
     * @param file 待发送的文件
     */
    public void sendNow(File file) {
        selectedFile = file;
        chatInitiated = true;
        if (topPanel != null && topPanel.isVisible()) {
            // 由聊天面板发起时隐藏顶部的接收方与选择区
            topPanel.setVisible(false);
            topPanel.getParent().revalidate();
            topPanel.getParent().repaint();
        }
        fileLabel.setText("已选择: " + file.getName() + "（" + FileUtil.humanSize(file.length()) + "）");
        startSend();
    }

    /**
     * 返回不小于 600×400 的首选窗口尺寸。
     *
     * @return 首选尺寸
     */
    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(Math.max(600, size.width), Math.max(400, size.height));
    }
}
