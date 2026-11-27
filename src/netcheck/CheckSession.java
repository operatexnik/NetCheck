package netcheck;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Одна сессия диагностики: параллельно проверяет локацию, Яндекс и все сервисы,
 * после каждой готовой проверки перерисовывает экран.
 */
public final class CheckSession {

    public interface Listener {
        void onChange(Snapshot snapshot);

        void onDone(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String infoText;
        public final String servicesText;
        public final boolean finished;
        public final String countryCode;
        public final boolean globalAlive;
        public final boolean ruAlive;
        public final boolean physicalOnline;
        public final long vkPing;
        public final long tgPing;
        public final int aiOk;
        public final String diagnosis;

        Snapshot(String infoText, String servicesText, boolean finished, String countryCode,
                 boolean globalAlive, boolean ruAlive, boolean physicalOnline,
                 long vkPing, long tgPing, int aiOk, String diagnosis) {
            this.infoText = infoText;
            this.servicesText = servicesText;
            this.finished = finished;
            this.countryCode = countryCode;
            this.globalAlive = globalAlive;
            this.ruAlive = ruAlive;
            this.physicalOnline = physicalOnline;
            this.vkPing = vkPing;
            this.tgPing = tgPing;
            this.aiOk = aiOk;
            this.diagnosis = diagnosis;
        }
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "netcheck-io");
        thread.setDaemon(true);
        return thread;
    });

    private final SystemInfo.Snapshot sys;
    private final Listener listener;
    private final Checks.Result[] results = new Checks.Result[Checks.TARGETS.size()];

    private volatile boolean cancelled;
    private boolean yandexAlive;
    private String locationText = "Локация:  Определяется...";
    private String countryCode = "";
    private boolean allFinished;

    public CheckSession(SystemInfo.Snapshot sys, Listener listener) {
        this.sys = sys;
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    public void start() {
        synchronized (this) {
            for (int i = 0; i < results.length; i++) {
                results[i] = new Checks.Result("\u23F3", false, 0, 0);
            }
        }

        if (!sys.physicalOnline) {
            synchronized (this) {
                for (int i = 0; i < results.length; i++) {
                    results[i] = Checks.Result.fail();
                }
                locationText = "Локация:  \u274C Нет сети";
                allFinished = true;
            }
            publish(true);
            return;
        }

        publish(false);
        POOL.execute(this::runAll);
    }

    private void runAll() {
        List<Future<?>> tasks = new ArrayList<>();

        // 1. Быстрая локация через Cloudflare, затем подробная через ipwho.is.
        tasks.add(POOL.submit(() -> {
            Checks.Location quick = Checks.fetchFastCfLocation();
            if (cancelled) {
                return;
            }
            if (quick != null) {
                synchronized (this) {
                    locationText = quick.text();
                    countryCode = quick.countryCode();
                }
                publish(false);
            }

            Checks.Location full = Checks.fetchFullIpLocation();
            if (cancelled) {
                return;
            }
            if (full != null) {
                synchronized (this) {
                    locationText = full.text();
                    countryCode = full.countryCode();
                }
                publish(false);
            } else if (quick == null) {
                synchronized (this) {
                    locationText = "Локация:  \u274C Не удалось определить";
                }
                publish(false);
            }
        }));

        // 2. Резервная HTTPS-проверка Яндекса для определения белого списка РФ.
        tasks.add(POOL.submit(() -> {
            boolean alive = Checks.checkHttpsAlive("https://ya.ru/favicon.ico");
            if (cancelled) {
                return;
            }
            synchronized (this) {
                yandexAlive = alive;
            }
            publish(false);
        }));

        // 3. Все сервисы параллельно, с мгновенным обновлением строк.
        for (int i = 0; i < Checks.TARGETS.size(); i++) {
            final int index = i;
            tasks.add(POOL.submit(() -> {
                Checks.Result result = Checks.checkService(Checks.TARGETS.get(index));
                if (cancelled) {
                    return;
                }
                synchronized (this) {
                    results[index] = result;
                }
                publish(false);
            }));
        }

        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (Exception ignored) {
            }
        }
        if (cancelled) {
            return;
        }
        synchronized (this) {
            allFinished = true;
        }
        publish(true);
    }

    private void publish(boolean done) {
        Snapshot snapshot;
        synchronized (this) {
            snapshot = snapshotLocked();
        }
        if (cancelled) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (cancelled) {
                return;
            }
            listener.onChange(snapshot);
            if (done) {
                listener.onDone(snapshot);
            }
        });
    }

    private Snapshot snapshotLocked() {
        boolean ruAlive = yandexAlive;
        boolean globalAlive = false;
        long vkPing = 0;
        long tgPing = 0;
        int aiOk = 0;
        List<String> lines = new ArrayList<>(results.length);

        for (int i = 0; i < results.length; i++) {
            Checks.Target target = Checks.TARGETS.get(i);
            Checks.Result result = results[i];
            String status = result.status();

            if (target.name().startsWith("Gemini") && status.contains("[?]") && !countryCode.isEmpty()) {
                if (Checks.BLOCKED.contains(countryCode)) {
                    status = status.replace("\u2705 [?]", "\u274C Геоблок [" + countryCode + "]");
                } else {
                    status = status.replace("[?]", "[" + countryCode + "]");
                }
            }

            boolean effective = result.success() && !status.contains("Геоблок");
            if (effective) {
                if (target.ruWhiteList()) {
                    ruAlive = true;
                } else {
                    globalAlive = true;
                }
            }
            if (i == 0 && effective) {
                vkPing = result.pingMs();
            }
            if (i == 1 && effective) {
                tgPing = result.pingMs();
            }
            if (target.type() != Checks.Type.NORMAL && effective) {
                aiOk++;
            }

            lines.add(String.format(Locale.ROOT, "%-8s: %s", target.name(), status));
        }

        String diagnosis;
        if (!sys.physicalOnline) {
            diagnosis = "Нет (Сеть отключена) \u274C";
        } else if (globalAlive) {
            diagnosis = "Есть (Глобальный) \u2705";
        } else if (ruAlive && allFinished) {
            diagnosis = "Только Белый список РФ \u26A0";
        } else if (ruAlive) {
            diagnosis = "РФ есть, мир проверяется...";
        } else if (!allFinished) {
            diagnosis = "Проверка связи...";
        } else {
            diagnosis = "Нет (Глухо / Нет связи) \u274C";
        }

        String location = locationText;
        if (!sys.physicalOnline) {
            location = "Локация:  \u274C Нет сети";
        } else if (allFinished && !globalAlive && !ruAlive) {
            location = "Локация:  \u274C Нет доступа к сети";
        }

        String signal;
        if (sys.wifiConnected && sys.wifi != null) {
            int bars = bars(sys.wifi.signalPercent());
            signal = "Сигнал:   " + bars + "/4 (" + barsVisual(bars) + ")";
        } else if (sys.ethernet) {
            signal = "Сигнал:   4/4 (\u25AE\u25AE\u25AE\u25AE)";
        } else {
            signal = "Сигнал:   0/4 (\u25AF\u25AF\u25AF\u25AF)";
        }

        String vpn = sys.vpnActive
                ? (sys.physicalOnline ? "Активен \u2705" : "Включен (без сети) \u26A0")
                : "Выключен";

        String info = "Интернет: " + diagnosis + "\n"
                + signal + "\n"
                + "Связь:    " + sys.linkText + "\n"
                + "VPN:      " + vpn + "\n"
                + location;

        return new Snapshot(info, String.join("\n", lines), allFinished, countryCode,
                globalAlive, ruAlive, sys.physicalOnline, vkPing, tgPing, aiOk, diagnosis);
    }

    private static int bars(int percent) {
        if (percent >= 80) {
            return 4;
        }
        if (percent >= 60) {
            return 3;
        }
        if (percent >= 40) {
            return 2;
        }
        if (percent >= 20) {
            return 1;
        }
        return 0;
    }

    private static String barsVisual(int bars) {
        int filled = Math.max(0, Math.min(4, bars));
        return "\u25AE".repeat(filled) + "\u25AF".repeat(4 - filled);
    }
}
