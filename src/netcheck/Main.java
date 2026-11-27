package netcheck;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class Main {

    private static App app;
    private static TrayManager tray;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // Доверяем также хранилищу сертификатов Windows (лечит HTTPS-инспекцию антивирусов).
        SslTrust.install();

        if (args.length > 0 && "--test".equals(args[0])) {
            runTest(args.length > 1 ? args[1] : "netcheck-test.txt");
            return;
        }
        if (args.length > 0 && "--screenshot".equals(args[0])) {
            screenshot(args.length > 1 ? args[1] : "netcheck.png");
            return;
        }
        SwingUtilities.invokeLater(Main::launch);
    }

    private static void launch() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        boolean firstInstance = SingleInstance.acquire();
        if (!firstInstance && SingleInstance.sendShow()) {
            return;
        }

        try {
            tray = TrayManager.install(Main::showWindow, Main::trayCheck, Main::exit);
        } catch (Exception e) {
            e.printStackTrace();
            tray = null;
        }

        app = new App(tray);
        if (firstInstance) {
            SingleInstance.listen(Main::showWindow);
        }
        app.show();
    }

    private static void showWindow() {
        if (app != null) {
            app.show();
        }
    }

    private static void trayCheck() {
        if (app != null) {
            app.startCheck();
        }
    }

    private static void exit() {
        if (app != null) {
            app.exit();
        }
        SingleInstance.close();
        System.exit(0);
    }

    /** Консольный прогон диагностики: пишет результат в UTF-8-файл. */
    private static void runTest(String path) throws Exception {
        SystemInfo.Snapshot sys = SystemInfo.probe();
        StringBuilder out = new StringBuilder();
        out.append("physicalOnline=").append(sys.physicalOnline)
                .append(" wifiConnected=").append(sys.wifiConnected)
                .append(" ethernet=").append(sys.ethernet)
                .append(" vpnActive=").append(sys.vpnActive)
                .append('\n');
        out.append("link=").append(sys.linkText).append('\n');
        if (sys.wifi != null) {
            out.append("wifi=ssid '").append(sys.wifi.ssid()).append("' signal=")
                    .append(sys.wifi.signalPercent()).append("% radio='")
                    .append(sys.wifi.radio()).append("' connected=").append(sys.wifi.connected())
                    .append('\n');
        }
        out.append('\n');

        CountDownLatch done = new CountDownLatch(1);
        final long started = System.nanoTime();
        CheckSession session = new CheckSession(sys, new CheckSession.Listener() {
            @Override
            public void onChange(CheckSession.Snapshot snapshot) {
            }

            @Override
            public void onDone(CheckSession.Snapshot snapshot) {
                out.append("--- INFO ---\n").append(snapshot.infoText)
                        .append("\n\n--- SERVICES ---\n").append(snapshot.servicesText)
                        .append("\n\nelapsedMs=").append((System.nanoTime() - started) / 1_000_000L)
                        .append('\n');
                done.countDown();
            }
        });
        session.start();
        if (!done.await(60, TimeUnit.SECONDS)) {
            out.append("TIMEOUT\n");
        }

        Path target = Paths.get(path);
        Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        System.out.println("Result written to " + target.toAbsolutePath());
        System.exit(0);
    }

    /** Рисует окно с демонстрационными данными в PNG (для проверки вёрстки). */
    private static void screenshot(String path) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            try {
                App demo = new App(null);
                demo.renderDemo(
                        "Интернет: Есть (Глобальный) ✅\n"
                                + "Сигнал:   3/4 (▮▮▮▯)\n"
                                + "Связь:    Wi-Fi \"Redmi Note 8\" (802.11n)\n"
                                + "VPN:      Активен ✅\n"
                                + "Локация:  Russia, Moscow [RU]\n"
                                + "IP / ISP: 95.24.10.7 (MTS PJSC)",
                        "VK (РФ) : 96 мс\n"
                                + "Telegram: 118 мс\n"
                                + "Discord : ❌\n"
                                + "YouTube : 205 мс\n"
                                + "Gemini  : 640 мс ✅ [RU]\n"
                                + "Claude  : 390 мс ❌ Геоблок [RU]\n"
                                + "ChatGPT : ❌ Геоблок [RU] (210 мс)");
                demo.shot(path);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        System.exit(0);
    }
}
