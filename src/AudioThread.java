import javax.sound.sampled.*;
import java.net.*;

public class AudioThread extends Thread {
    private String remoteIP;
    private int remotePort;
    private DatagramSocket socket;
    private boolean isSender;
    private volatile boolean running = true;
    private TargetDataLine microphone;
    private SourceDataLine speaker;

    public AudioThread(String ip, int port, DatagramSocket socket, boolean isSender) {
        this.remoteIP = ip;
        this.remotePort = port;
        this.socket = socket;
        this.isSender = isSender;
    }

    public void run() {
        try {
            AudioFormat format = new AudioFormat(8000, 16, 1, true, true);

            if (isSender) {
                DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
                microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
                microphone.open(format);
                microphone.start();

                byte[] buffer = new byte[1024];
                while (running) {
                    int count = microphone.read(buffer, 0, buffer.length);
                    if (count > 0) {
                        DatagramPacket packet = new DatagramPacket(buffer, count,
                                InetAddress.getByName(remoteIP), remotePort);
                        socket.send(packet);
                    }
                }
            } else {
                DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
                speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
                speaker.open(format);
                speaker.start();

                byte[] buffer = new byte[1024];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    speaker.write(packet.getData(), 0, packet.getLength());
                }
            }
        } catch (Exception ex) {
            if (running) {
                ex.printStackTrace();
            }
        }
    }

    public void stopAudio() {
        running = false;
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
        if (speaker != null) {
            speaker.stop();
            speaker.close();
        }
    }
}