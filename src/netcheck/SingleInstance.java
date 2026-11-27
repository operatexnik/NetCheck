package netcheck;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Не даёт запустить второй экземпляр приложения: повторный запуск просто
 * показывает уже открытое окно.
 */
public final class SingleInstance {
    private static final int PORT = 48765;
    private static volatile ServerSocket serverSocket;

    private SingleInstance() {
    }

    public static boolean acquire() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
            serverSocket = socket;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** true — если нас услышал уже запущенный экземпляр. */
    public static boolean sendShow() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 1200);
            socket.setSoTimeout(1500);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String hello = reader.readLine();
            if (!"NETCHECK".equals(hello)) {
                return false;
            }
            OutputStream out = socket.getOutputStream();
            out.write("show\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void listen(Runnable onShow) {
        ServerSocket socket = serverSocket;
        if (socket == null) {
            return;
        }
        Thread thread = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(2000);
                    OutputStream out = client.getOutputStream();
                    out.write("NETCHECK\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    if ("show".equals(reader.readLine())) {
                        SwingUtilities.invokeLater(onShow);
                    }
                } catch (IOException ignored) {
                    // экземпляр закрывается или клиент отвалился
                }
            }
        }, "netcheck-single-instance");
        thread.setDaemon(true);
        thread.start();
    }

    public static void close() {
        try {
            ServerSocket socket = serverSocket;
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
