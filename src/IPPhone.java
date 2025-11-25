import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;

public class IPPhone extends JFrame {
    private JTextField ipField, portField;
    private JButton dialButton, hangupButton, listenButton;
    private JTextArea statusArea;
    private Socket tcpSocket;
    private DatagramSocket udpSocket;
    private PrintWriter tcpOut;
    private BufferedReader tcpIn;
    private boolean isConnected = false;
    private AudioThread audioSender, audioReceiver;

    public IPPhone() {
        setTitle("IP Phone");
        setSize(400, 300);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Top panel
        JPanel topPanel = new JPanel(new GridLayout(3, 2, 5, 5));
        topPanel.add(new JLabel("IP Address:"));
        ipField = new JTextField("127.0.0.1");
        topPanel.add(ipField);
        topPanel.add(new JLabel("Port:"));
        portField = new JTextField("5000");
        topPanel.add(portField);

        dialButton = new JButton("Dial");
        listenButton = new JButton("Listen");
        topPanel.add(dialButton);
        topPanel.add(listenButton);

        add(topPanel, BorderLayout.NORTH);

        // Status area
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        add(new JScrollPane(statusArea), BorderLayout.CENTER);

        // Hangup button
        hangupButton = new JButton("Hangup");
        hangupButton.setEnabled(false);
        add(hangupButton, BorderLayout.SOUTH);

        // Listeners
        dialButton.addActionListener(e -> dial());
        listenButton.addActionListener(e -> listen());
        hangupButton.addActionListener(e -> hangup());

        setVisible(true);
    }

    private void dial() {
        try {
            String ip = ipField.getText();
            int port = Integer.parseInt(portField.getText());

            tcpSocket = new Socket(ip, port);
            tcpOut = new PrintWriter(tcpSocket.getOutputStream(), true);
            tcpIn = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));

            tcpOut.println("DIAL");
            String response = tcpIn.readLine();

            if ("ACCEPT".equals(response)) {
                isConnected = true;
                statusArea.append("Connected to " + ip + ":" + port + "\n");
                dialButton.setEnabled(false);
                listenButton.setEnabled(false);
                hangupButton.setEnabled(true);

                startAudio(ip, port + 1);
            }
        } catch (Exception ex) {
            statusArea.append("Error: " + ex.getMessage() + "\n");
        }
    }

    private void listen() {
        new Thread(() -> {
            try {
                int port = Integer.parseInt(portField.getText());
                ServerSocket serverSocket = new ServerSocket(port);
                statusArea.append("Listening on port " + port + "...\n");

                tcpSocket = serverSocket.accept();
                statusArea.append("Incoming call from " + tcpSocket.getInetAddress() + "\n");

                tcpOut = new PrintWriter(tcpSocket.getOutputStream(), true);
                tcpIn = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));

                String msg = tcpIn.readLine();
                if ("DIAL".equals(msg)) {
                    tcpOut.println("ACCEPT");
                    isConnected = true;

                    SwingUtilities.invokeLater(() -> {
                        dialButton.setEnabled(false);
                        listenButton.setEnabled(false);
                        hangupButton.setEnabled(true);
                    });

                    startAudio(tcpSocket.getInetAddress().getHostAddress(), port + 1);
                }
                serverSocket.close();
            } catch (Exception ex) {
                statusArea.append("Error: " + ex.getMessage() + "\n");
            }
        }).start();
    }

    private void startAudio(String ip, int port) {
        try {
            udpSocket = new DatagramSocket(port);
            audioSender = new AudioThread(ip, port, udpSocket, true);
            audioReceiver = new AudioThread(ip, port, udpSocket, false);
            audioSender.start();
            audioReceiver.start();
            statusArea.append("Audio channel started\n");
        } catch (Exception ex) {
            statusArea.append("Audio error: " + ex.getMessage() + "\n");
        }
    }

    private void hangup() {
        try {
            if (tcpOut != null) {
                tcpOut.println("HANGUP");
            }
            cleanup();
            statusArea.append("Call ended\n");
        } catch (Exception ex) {
            statusArea.append("Error: " + ex.getMessage() + "\n");
        }
    }

    private void cleanup() {
        try {
            if (audioSender != null) audioSender.stopAudio();
            if (audioReceiver != null) audioReceiver.stopAudio();
            if (tcpSocket != null) tcpSocket.close();
            if (udpSocket != null) udpSocket.close();
            isConnected = false;
            dialButton.setEnabled(true);
            listenButton.setEnabled(true);
            hangupButton.setEnabled(false);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new IPPhone());
    }
}