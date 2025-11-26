import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * IP电话主程序
 * 实现基于TCP的信令控制和UDP的音频传输
 */
public class IPPhone extends JFrame {
    // UI组件
    private JTextField ipField, portField;
    private JButton dialButton, hangupButton, listenButton;
    private JTextArea statusArea;
    private JLabel loadingLabel; // 加载图标
    private JLabel callStatusLabel; // 通话状态标签
    private JLabel callDurationLabel; // 通话时长标签
    private JPanel micIndicator; // 麦克风指示器

    // 网络组件
    private Socket tcpSocket;
    private DatagramSocket udpSocket;
    private PrintWriter tcpOut;
    private BufferedReader tcpIn;

    // 状态标志
    private boolean isConnected = false;
    private boolean isListening = false;

    // 音频线程
    private AudioThread audioSender, audioReceiver;

    // 消息监听线程
    private Thread messageListener;
    private volatile boolean shouldListen = false;

    // 通话时长计时
    private Timer callTimer;
    private long callStartTime;

    // 监听服务器
    private ServerSocket serverSocket;
    private Thread listenThread;

    public IPPhone() {
        setTitle("IP Phone - 网络电话");
        setSize(450, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // 顶部面板 - 输入区域
        JPanel topPanel = new JPanel(new GridLayout(3, 2, 10, 10));
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // 添加边距

        topPanel.add(new JLabel("IP Address:"));
        ipField = new JTextField("127.0.0.1");
        topPanel.add(ipField);

        topPanel.add(new JLabel("Port:"));
        portField = new JTextField("5000");
        topPanel.add(portField);

        dialButton = new JButton("Dial 拨号");
        listenButton = new JButton("Start Listen 开始监听");
        topPanel.add(dialButton);
        topPanel.add(listenButton);

        add(topPanel, BorderLayout.NORTH);

        // 中部面板 - 状态显示区域
        JPanel centerPanel = new JPanel(new BorderLayout());
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        statusArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        centerPanel.add(new JScrollPane(statusArea), BorderLayout.CENTER);

        // 通话状态面板
        JPanel statusPanel = new JPanel(new BorderLayout());
        callStatusLabel = new JLabel("", JLabel.CENTER);
        callStatusLabel.setFont(new Font("微软雅黑", Font.BOLD, 14));
        callStatusLabel.setForeground(new Color(0, 150, 0));
        statusPanel.add(callStatusLabel, BorderLayout.NORTH);

        callDurationLabel = new JLabel("", JLabel.CENTER);
        callDurationLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        callDurationLabel.setForeground(Color.DARK_GRAY);
        statusPanel.add(callDurationLabel, BorderLayout.CENTER);

        // 加载提示标签
        loadingLabel = new JLabel("", JLabel.CENTER);
        loadingLabel.setForeground(Color.BLUE);
        loadingLabel.setVisible(false);
        statusPanel.add(loadingLabel, BorderLayout.SOUTH);

        centerPanel.add(statusPanel, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        // 底部面板 - 挂断按钮和麦克风指示器
        JPanel bottomPanel = new JPanel(new BorderLayout());

        // 麦克风指示器（左下角）
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        micIndicator = new JPanel();
        micIndicator.setPreferredSize(new Dimension(30, 30));
        micIndicator.setBackground(Color.GRAY);
        micIndicator.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 2));
        // 使用简单的文字代替emoji，确保跨平台兼容
        JLabel micLabel = new JLabel("MIC");
        micLabel.setFont(new Font("Arial", Font.BOLD, 10));
        micIndicator.add(micLabel);
        leftPanel.add(micIndicator);
        bottomPanel.add(leftPanel, BorderLayout.WEST);

        // 挂断按钮（居中）
        hangupButton = new JButton("Hangup 挂断");
        hangupButton.setEnabled(false);
        JPanel centerButtonPanel = new JPanel();
        centerButtonPanel.add(hangupButton);
        bottomPanel.add(centerButtonPanel, BorderLayout.CENTER);

        add(bottomPanel, BorderLayout.SOUTH);

        // 按钮事件监听器
        dialButton.addActionListener(e -> dial());
        listenButton.addActionListener(e -> toggleListen());
        hangupButton.addActionListener(e -> hangup());

        // 窗口关闭时清理资源
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cleanup();
                stopListening();
            }
        });

        setVisible(true);

        // 不自动监听，等待用户手动点击
    }

    /**
     * 切换监听状态
     */
    private void toggleListen() {
        if (isListening) {
            stopListening();
        } else {
            startListening();
        }
    }

    /**
     * 开始监听
     */
    private void startListening() {
        if (isConnected) {
            statusArea.append("错误：正在通话中，无法开始监听\n");
            return;
        }

        if (isListening) {
            statusArea.append("错误：已经在监听中\n");
            return;
        }

        listenThread = new Thread(() -> {
            try {
                int port = Integer.parseInt(portField.getText());
                serverSocket = new ServerSocket(port);
                isListening = true;

                SwingUtilities.invokeLater(() -> {
                    statusArea.append("✓ 已开始监听端口 " + port + "，等待来电...\n");
                    listenButton.setText("Stop Listen 停止监听");
                    dialButton.setEnabled(false);
                });

                while (isListening) {
                    try {
                        // 等待连接（阻塞，但可被中断）
                        tcpSocket = serverSocket.accept();

                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("收到来电，来自: " + tcpSocket.getInetAddress() + "\n");
                        });

                        tcpOut = new PrintWriter(tcpSocket.getOutputStream(), true);
                        tcpIn = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));

                        // 读取拨号请求
                        String msg = tcpIn.readLine();
                        if ("DIAL".equals(msg)) {
                            tcpOut.println("ACCEPT"); // 自动接受
                            isConnected = true;
                            isListening = false; // 停止监听标志

                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("✓ 已接听，正在建立音频通道...\n");
                                loadingLabel.setVisible(false);
                                hangupButton.setEnabled(true);
                                dialButton.setEnabled(false);
                                listenButton.setEnabled(false);
                            });

                            // 启动音频传输
                            startAudio(tcpSocket.getInetAddress().getHostAddress(), port + 1);

                            // 启动消息监听线程
                            startMessageListener();

                            // 开始计时
                            startCallTimer();

                            break; // 停止接受新连接
                        }
                    } catch (SocketException se) {
                        // ServerSocket被关闭，正常退出
                        break;
                    }
                }
            } catch (Exception ex) {
                if (isListening) {
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("✗ 监听错误: " + ex.getMessage() + "\n");
                        listenButton.setText("Start Listen 开始监听");
                        dialButton.setEnabled(true);
                    });
                    isListening = false;
                }
            } finally {
                // 确保ServerSocket被关闭
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        listenThread.start();
    }

    /**
     * 停止监听
     */
    private void stopListening() {
        if (!isListening) {
            return; // 如果没在监听，直接返回
        }

        isListening = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            SwingUtilities.invokeLater(() -> {
                statusArea.append("已停止监听\n");
                listenButton.setText("Start Listen 开始监听");
                dialButton.setEnabled(true);
            });
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 拨号功能 - 主动发起连接
     */
    private void dial() {
        // 如果正在监听，先停止监听
        if (isListening) {
            stopListening();
        }

        // 在新线程中执行拨号，避免UI卡顿
        new Thread(() -> {
            try {
                String ip = ipField.getText();
                int port = Integer.parseInt(portField.getText());

                // 显示加载状态
                SwingUtilities.invokeLater(() -> {
                    loadingLabel.setText("正在连接 " + ip + ":" + port + " ...");
                    loadingLabel.setVisible(true);
                    dialButton.setEnabled(false);
                });

                statusArea.append("正在拨号至 " + ip + ":" + port + "...\n");

                // 建立TCP连接
                tcpSocket = new Socket();
                tcpSocket.connect(new InetSocketAddress(ip, port), 5000); // 5秒超时
                tcpOut = new PrintWriter(tcpSocket.getOutputStream(), true);
                tcpIn = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));

                // 发送拨号请求
                tcpOut.println("DIAL");
                String response = tcpIn.readLine();

                // 隐藏加载状态
                SwingUtilities.invokeLater(() -> {
                    loadingLabel.setVisible(false);
                });

                if ("ACCEPT".equals(response)) {
                    isConnected = true;
                    statusArea.append("✨ 连接成功！正在建立音频通道...\n");

                    SwingUtilities.invokeLater(() -> {
                        dialButton.setEnabled(false);
                        listenButton.setEnabled(false);
                        hangupButton.setEnabled(true);
                    });

                    // 启动音频传输
                    startAudio(ip, port + 1);

                    // 启动消息监听线程，监听对方的挂断消息
                    startMessageListener();

                    // 开始计时
                    startCallTimer();
                } else {
                    statusArea.append("❌ 连接被拒绝\n");
                    cleanup();
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    loadingLabel.setVisible(false);
                    dialButton.setEnabled(true);
                });
                statusArea.append("❌ 拨号失败: " + ex.getMessage() + "\n");
            }
        }).start();
    }

    /**
     * 启动通话计时器
     */
    private void startCallTimer() {
        callStartTime = System.currentTimeMillis();
        callStatusLabel.setText("● 通话中");
        callDurationLabel.setText("00:00");

        callTimer = new Timer(1000, e -> {
            long elapsed = System.currentTimeMillis() - callStartTime;
            long seconds = elapsed / 1000;
            long minutes = seconds / 60;
            seconds = seconds % 60;
            callDurationLabel.setText(String.format("%02d:%02d", minutes, seconds));
        });
        callTimer.start();
    }

    /**
     * 停止通话计时器
     */
    private void stopCallTimer() {
        if (callTimer != null) {
            callTimer.stop();
            callTimer = null;
        }
        callStatusLabel.setText("");
        callDurationLabel.setText("");
    }

    /**
     * 启动音频传输
     * @param ip 对方IP地址
     * @param port UDP端口
     */
    private void startAudio(String ip, int port) {
        try {
            udpSocket = new DatagramSocket(port);
            audioSender = new AudioThread(ip, port, udpSocket, true, this);
            audioReceiver = new AudioThread(ip, port, udpSocket, false, this);
            audioSender.start();
            audioReceiver.start();
            statusArea.append("✅ 音频通道已建立，可以通话\n");
        } catch (Exception ex) {
            statusArea.append("❌ 音频启动失败: " + ex.getMessage() + "\n");
        }
    }

    /**
     * 更新麦克风指示器
     * @param hasSound 是否有声音
     */
    public void updateMicIndicator(boolean hasSound) {
        SwingUtilities.invokeLater(() -> {
            if (hasSound) {
                micIndicator.setBackground(new Color(0, 200, 0)); // 绿色
            } else {
                micIndicator.setBackground(Color.GRAY); // 灰色
            }
        });
    }

    /**
     * 启动TCP消息监听线程
     * 用于接收对方的HANGUP消息
     */
    private void startMessageListener() {
        shouldListen = true;
        messageListener = new Thread(() -> {
            try {
                String msg;
                while (shouldListen && (msg = tcpIn.readLine()) != null) {
                    if ("HANGUP".equals(msg)) {
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("对方已挂断\n");
                            cleanup();
                            // 不自动重新监听，由用户手动控制
                        });
                        break;
                    }
                }
            } catch (IOException ex) {
                if (shouldListen) {
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("连接已断开\n");
                        cleanup();
                        // 不自动重新监听，由用户手动控制
                    });
                }
            }
        });
        messageListener.start();
    }

    /**
     * 挂断通话
     */
    private void hangup() {
        try {
            // 发送挂断消息给对方
            if (tcpOut != null && isConnected) {
                tcpOut.println("HANGUP");
            }
            statusArea.append("通话已结束\n");
        } catch (Exception ex) {
            statusArea.append("挂断错误: " + ex.getMessage() + "\n");
        } finally {
            cleanup();
            // 不自动重新监听，由用户手动控制
        }
    }

    /**
     * 清理资源，恢复UI状态
     */
    private void cleanup() {
        try {
            // 停止计时器
            stopCallTimer();

            // 停止消息监听
            shouldListen = false;

            // 停止音频线程
            if (audioSender != null) audioSender.stopAudio();
            if (audioReceiver != null) audioReceiver.stopAudio();

            // 关闭网络连接
            if (tcpSocket != null && !tcpSocket.isClosed()) tcpSocket.close();
            if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();

            // 重置状态
            isConnected = false;

            // 恢复麦克风指示器
            micIndicator.setBackground(Color.GRAY);

            // 恢复UI
            SwingUtilities.invokeLater(() -> {
                dialButton.setEnabled(true);
                listenButton.setEnabled(true);
                hangupButton.setEnabled(false);
                loadingLabel.setVisible(false);
            });
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 主函数入口
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new IPPhone());
    }
}