package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.MessageType;
import com.chat.util.FileUtil;

import javax.swing.JFileChooser;
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
 * 文件传输窗口。
 *
 * <p>职责：发起文件发送、展示传输进度与结果。窗口按“对端用户名”复用，
 * 与同一好友的多次传输共用同一个窗口，避免弹窗泛滥。</p>
 *
 * <p>进度路由：{@link ChatClient} 会把 FILE_CHUNK 消息先转换为仅含进度信息的
 * 轻量消息再回调界面；界面通过静态注册表 {@link #WINDOWS} 依据传输编号把进度
 * 投递到正确的窗口。</p>
 *
 * <p>界面与网络解耦：窗口不直接操作 Socket，发送与接收全部通过
 * {@link ChatClient} 与 {@link com.chat.service.FileService} 完成。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class FileTransferUI extends BaseUI {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250118L;

    /** 活跃窗口注册表：对端用户名 -> 窗口，保证同一好友只开一个传输窗口 */
    private static final Map<String, FileTransferUI> WINDOWS = new ConcurrentHashMap<>();

    /** 传输编号到窗口的映射，用于接收进度与结果回执 */
    private static final Map<String, FileTransferUI> TRANSFER_OWNERS = new ConcurrentHashMap<>();

    /** 客户端实例 */
    private final transient ChatClient client;

    /** 传输对象（好友用户名或文件发送者） */
    private final String peer;

    /** 接收方输入框（仅发送场景可编辑） */
    private final JTextField receiverField = new JTextField(12);

    /** 文件路径显示 */
    private final JLabel fileLabel = new JLabel("尚未选择文件");

    /** 进度条 */
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    /** 进度说明 */
    private final JLabel progressLabel = new JLabel("等待开始");

    /** 传输日志 */
    private final JTextArea logArea = new JTextArea(8, 42);

    /** 当前待发送文件 */
    private transient File selectedFile;

    /**
     * 构造文件传输窗口。
     *
     * @param client 客户端实例
     * @param peer   对端用户名
     */
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
     * 获取或创建与指定对端的传输窗口。
     *
     * @param client 客户端实例
     * @param peer   对端用户名
     * @return 窗口实例
     */
    public static FileTransferUI windowFor(ChatClient client, String peer) {
        return WINDOWS.computeIfAbsent(peer, key -> new FileTransferUI(client, key));
    }

    /**
     * 依据传输编号查找所属窗口。
     *
     * @param transferId 传输编号
     * @return 窗口实例；未登记时返回 null
     */
    public static FileTransferUI ownerOf(String transferId) {
        return transferId == null ? null : TRANSFER_OWNERS.get(transferId);
    }

    /**
     * 组装界面组件。
     */
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

    /**
     * 弹出文件选择对话框。
     */
    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要发送的文件");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedFile = chooser.getSelectedFile();
            fileLabel.setText("已选择: " + selectedFile.getName()
                    + "（" + FileUtil.humanSize(selectedFile.length()) + "）");
            appendLog("已选择文件 " + selectedFile.getAbsolutePath());
        }
    }

    /**
     * 发起文件发送。
     */
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
        if (receiver.equals(client.getUsername())) {
            showError("不能给自己发送文件");
            return;
        }
        String transferId = client.sendFile(receiver, selectedFile);
        if (transferId == null) {
            progressLabel.setText("发送失败");
            appendLog("发送失败：文件不合法或连接异常");
            return;
        }
        TRANSFER_OWNERS.put(transferId, this);
        progressBar.setValue(0);
        progressLabel.setText("已发送传输请求，等待对方确认……");
        appendLog("已向 " + receiver + " 发起文件传输请求，编号 "
                + transferId.substring(0, 8));
        selectedFile = null;
        fileLabel.setText("尚未选择文件");
    }

    /**
     * 更新进度显示。
     *
     * @param fileName 文件名
     * @param progress 进度比例（0.0 - 1.0）
     * @param text     附加说明
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
     * 追加传输日志。
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
     * 登记一个接收中的传输编号，使进度能路由到本窗口。
     *
     * @param transferId 传输编号
     */
    public void registerTransfer(String transferId) {
        if (transferId != null) {
            TRANSFER_OWNERS.put(transferId, this);
        }
    }

    /**
     * 处理收到的文件传输请求：询问用户是否接收并回执。
     *
     * @param request 文件请求消息
     */
    public void handleIncomingRequest(FileMessage request) {
        registerTransfer(request.getTransferId());
        onEdt(() -> {
            appendLog("收到来自 " + request.getSender() + " 的文件: " + request.getSummary());
            progressBar.setValue(0);
            progressLabel.setText("等待确认: " + request.getFileName());
        });
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
     * 处理传输结果回执。
     *
     * @param result 结果消息
     */
    public void handleResult(FileMessage result) {
        TRANSFER_OWNERS.remove(result.getTransferId());
        boolean success = result.isAccepted();
        onEdt(() -> {
            progressBar.setValue(success ? 100 : 0);
            progressLabel.setText(success ? "传输完成" : "传输失败");
        });
        appendLog((success ? "[成功] " : "[失败] ") + result.getMessage());
        if (!success) {
            showError(result.getMessage());
        }
    }

    /**
     * 根据消息类型分发到本窗口的处理方法。
     *
     * @param message 文件相关消息
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
     * 解析进度消息中携带的比例。
     *
     * @param text 进度文本
     * @return 进度比例；解析失败返回 0
     */
    private double parseProgress(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException | NullPointerException e) {
            return 0.0;
        }
    }

    /**
     * 生成进度的可读文本。
     *
     * @param message 进度消息
     * @return 形如 {@code 已传输 3/10 块} 的文本
     */
    private String textProgress(FileMessage message) {
        int total = Math.max(1, message.getTotalChunks());
        int current = Math.min(total, message.getChunkIndex() + 1);
        return "已传输 " + current + "/" + total + " 块";
    }

    /**
     * 窗口关闭时注销注册表，防止内存泄漏。
     */
    @Override
    public void dispose() {
        WINDOWS.remove(peer);
        List<String> owned = TRANSFER_OWNERS.entrySet().stream()
                .filter(entry -> entry.getValue() == this)
                .map(Map.Entry::getKey)
                .toList();
        owned.forEach(TRANSFER_OWNERS::remove);
        super.dispose();
    }

    /**
     * 以指定文件立即发起一次传输。
     *
     * <p>聊天窗口内的"发送文件"按钮已经知道接收方是谁（私聊窗口是对端、群聊窗口是全部在线用户），
     * 因此选择文件后可以直接发起，不需要再让使用者回到本窗口点一次"发送"。</p>
     *
     * @param file 待发送文件
     */
    public void sendNow(File file) {
        selectedFile = file;
        fileLabel.setText("已选择: " + file.getName() + "（" + FileUtil.humanSize(file.length()) + "）");
        startSend();
    }

    /**
     * 获取尺寸建议，避免窗口过小。
     *
     * @return 首选尺寸
     */
    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(Math.max(600, size.width), Math.max(400, size.height));
    }
}
