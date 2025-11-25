import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;

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

    public IPPhone() {
        setTitle("IP Phone - 网络电话");
        setSize(450, 350);
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
        listenButton = new JButton("Listen 监听");
        topPanel.add(dialButton);
        topPanel.add(listenButton);

        add(topPanel, BorderLayout.NORTH);

        // 中部面板 - 状态显示区域
        JPanel centerPanel = new JPanel(new BorderLayout());
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        statusArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        centerPanel.add(new JScrollPane(statusArea), BorderLayout.CENTER);

        // 加载提示标签
        loadingLabel = new JLabel("", JLabel.CENTER);
        loadingLabel.setForeground(Color.BLUE);
        loadingLabel.setVisible(false);
        centerPanel.add(loadingLabel, BorderLayout.SOUTH);

        add(centerPanel, BorderLayout.CENTER);

        // 底部面板 - 挂断按钮
        hangupButton = new JButton("Hangup 挂断");
        hangupButton.setEnabled(false);
        JPanel bottomPanel = new JPanel();
        bottomPanel.add(hangupButton);
        add(bottomPanel, BorderLayout.SOUTH);

        // 按钮事件监听器
        dialButton.addActionListener(e -> dial());
        listenButton.addActionListener(e -> listen());
        hangupButton.addActionListener(e -> hangup());

        // 窗口关闭时清理资源
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cleanup();
            }
        });

        setVisible(true);
    }

    /**
     * 拨号功能 - 主动发起连接
     */
    private void dial() {
        // 如果正在监听，不允许拨号
        if (isListening) {
            statusArea.append("错误：正在监听状态，无法拨号。请先停止监听。\n");
            return;
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
     * 监听功能 - 等待对方连接
     */
    private void listen() {
        // 如果已经在通话或已经在监听，不允许重复监听
        if (isConnected || isListening) {
            statusArea.append("错误：已在通话或监听中\n");
            return;
        }

        new Thread(() -> {
            ServerSocket serverSocket = null;
            try {
                int port = Integer.parseInt(portField.getText());
                serverSocket = new ServerSocket(port);
                isListening = true;

                statusArea.append("正在监听端口 " + port + "，等待来电...\n");

                SwingUtilities.invokeLater(() -> {
                    listenButton.setEnabled(false);
                    dialButton.setEnabled(false);
                    loadingLabel.setText("等待来电中...");
                    loadingLabel.setVisible(true);
                });

                // 等待连接（阻塞）
                tcpSocket = serverSocket.accept();
                statusArea.append("收到来电，来自: " + tcpSocket.getInetAddress() + "\n");

                tcpOut = new PrintWriter(tcpSocket.getOutputStream(), true);
                tcpIn = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));

                // 读取拨号请求
                String msg = tcpIn.readLine();
                if ("DIAL".equals(msg)) {
                    tcpOut.println("ACCEPT"); // 自动接受
                    isConnected = true;
                    isListening = false;

                    statusArea.append("✅ 已接听，正在建立音频通道...\n");

                    SwingUtilities.invokeLater(() -> {
                        loadingLabel.setVisible(false);
                        hangupButton.setEnabled(true);
                    });

                    // 启动音频传输
                    startAudio(tcpSocket.getInetAddress().getHostAddress(), port + 1);

                    // 启动消息监听线程
                    startMessageListener();
                }
            } catch (Exception ex) {
                statusArea.append("❌ 监听错误: " + ex.getMessage() + "\n");
                SwingUtilities.invokeLater(() -> {
                    loadingLabel.setVisible(false);
                    listenButton.setEnabled(true);
                    dialButton.setEnabled(true);
                });
                isListening = false;
            } finally {
                // 关闭ServerSocket
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    /**
     * 启动音频传输
     * @param ip 对方IP地址
     * @param port UDP端口
     */
    private void startAudio(String ip, int port) {
        try {
            udpSocket = new DatagramSocket(port);
            audioSender = new AudioThread(ip, port, udpSocket, true);
            audioReceiver = new AudioThread(ip, port, udpSocket, false);
            audioSender.start();
            audioReceiver.start();
            statusArea.append("✅ 音频通道已建立，可以通话\n");
        } catch (Exception ex) {
            statusArea.append("❌ 音频启动失败: " + ex.getMessage() + "\n");
        }
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
                        statusArea.append("对方已挂断\n");
                        SwingUtilities.invokeLater(() -> {
                            cleanup();
                        });
                        break;
                    }
                }
            } catch (IOException ex) {
                if (shouldListen) {
                    statusArea.append("连接已断开\n");
                    SwingUtilities.invokeLater(() -> {
                        cleanup();
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
        }
    }

    /**
     * 清理资源，恢复UI状态
     */
    private void cleanup() {
        try {
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
            isListening = false;

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