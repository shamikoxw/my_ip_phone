import javax.sound.sampled.*;
import java.net.*;

/**
 * 音频处理线程类
 * 负责音频的采集、发送、接收和播放
 */
public class AudioThread extends Thread {
    // 网络参数
    private String remoteIP;      // 对方IP地址
    private int remotePort;       // 对方UDP端口
    private DatagramSocket socket; // UDP套接字

    // 线程控制
    private boolean isSender;     // true表示发送线程，false表示接收线程
    private volatile boolean running = true; // 线程运行标志

    // 音频设备
    private TargetDataLine microphone;  // 麦克风（输入）
    private SourceDataLine speaker;     // 扬声器（输出）

    /**
     * 构造函数
     * @param ip 对方IP地址
     * @param port 对方UDP端口
     * @param socket UDP套接字
     * @param isSender true为发送线程，false为接收线程
     */
    public AudioThread(String ip, int port, DatagramSocket socket, boolean isSender) {
        this.remoteIP = ip;
        this.remotePort = port;
        this.socket = socket;
        this.isSender = isSender;
    }

    /**
     * 线程运行主体
     */
    @Override
    public void run() {
        try {
            // 定义音频格式：8000Hz采样率，16位，单声道，有符号，大端序
            AudioFormat format = new AudioFormat(8000, 16, 1, true, true);

            if (isSender) {
                // 发送线程：从麦克风读取音频并通过UDP发送
                sendAudio(format);
            } else {
                // 接收线程：从UDP接收音频并通过扬声器播放
                receiveAudio(format);
            }
        } catch (Exception ex) {
            // 只有在线程运行时才打印异常（避免正常停止时的异常输出）
            if (running) {
                System.err.println("音频线程错误 (" + (isSender ? "发送" : "接收") + "): " + ex.getMessage());
            }
        }
    }

    /**
     * 发送音频数据
     * @param format 音频格式
     * @throws Exception
     */
    private void sendAudio(AudioFormat format) throws Exception {
        // 获取麦克风设备
        DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(micInfo)) {
            System.err.println("系统不支持该音频格式的麦克风");
            return;
        }

        microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
        microphone.open(format);
        microphone.start();

        System.out.println("麦克风已启动，开始发送音频...");

        byte[] buffer = new byte[1024]; // 音频缓冲区

        // 持续读取麦克风数据并发送
        while (running) {
            int count = microphone.read(buffer, 0, buffer.length);
            if (count > 0) {
                try {
                    // 封装成UDP数据包并发送
                    DatagramPacket packet = new DatagramPacket(
                            buffer, count,
                            InetAddress.getByName(remoteIP),
                            remotePort
                    );
                    socket.send(packet);
                } catch (Exception ex) {
                    if (running) {
                        System.err.println("发送音频数据失败: " + ex.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 接收并播放音频数据
     * @param format 音频格式
     * @throws Exception
     */
    private void receiveAudio(AudioFormat format) throws Exception {
        // 获取扬声器设备
        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);

        if (!AudioSystem.isLineSupported(speakerInfo)) {
            System.err.println("系统不支持该音频格式的扬声器");
            return;
        }

        speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
        speaker.open(format);
        speaker.start();

        System.out.println("扬声器已启动，开始接收音频...");

        byte[] buffer = new byte[1024]; // 音频缓冲区

        // 持续接收UDP数据包并播放
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // 阻塞等待接收

                // 将接收到的音频数据写入扬声器播放
                speaker.write(packet.getData(), 0, packet.getLength());
            } catch (Exception ex) {
                if (running) {
                    System.err.println("接收音频数据失败: " + ex.getMessage());
                }
            }
        }
    }

    /**
     * 停止音频线程并释放资源
     */
    public void stopAudio() {
        running = false; // 设置停止标志

        // 关闭麦克风
        if (microphone != null) {
            try {
                microphone.stop();
                microphone.close();
                System.out.println("麦克风已关闭");
            } catch (Exception ex) {
                System.err.println("关闭麦克风失败: " + ex.getMessage());
            }
        }

        // 关闭扬声器
        if (speaker != null) {
            try {
                speaker.drain(); // 等待缓冲区数据播放完
                speaker.stop();
                speaker.close();
                System.out.println("扬声器已关闭");
            } catch (Exception ex) {
                System.err.println("关闭扬声器失败: " + ex.getMessage());
            }
        }
    }
}