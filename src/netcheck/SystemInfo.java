package netcheck;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Состояние компьютера: Wi-Fi (SSID/сигнал/радио), проводная сеть, VPN.
 * Аналог android.net.ConnectivityManager + WifiManager из Android-версии.
 */
public final class SystemInfo {

    public record Wifi(String ssid, int signalPercent, String radio, boolean connected) {
    }

    public static final class Snapshot {
        public boolean physicalOnline;
        public boolean wifiConnected;
        public boolean ethernet;
        public boolean vpnActive;
        public Wifi wifi;
        public String linkText = "Отключена";
    }

    /** Признаки VPN-интерфейса в имени/описании адаптера. */
    private static final Pattern VPN = Pattern.compile(
            "(?i)(\\bvpn\\b|wireguard|openvpn|nordlynx|proton|hamachi|radmin|happ\\b|wintun|tap-windows|\\btun\\b|\\btunnel\\b|zerotier|tailscale|anyconnect|globalprotect|softether|sstp|ikev2|l2tp|pptp|pppoe)");

    /** Системные псевдо-адаптеры, которые не являются ни VPN, ни реальной сетью. */
    private static final Pattern NOT_REAL = Pattern.compile(
            "(?i)(teredo|isatap|ip-https|6to4|wi-?fi direct|wan miniport|loopback|bluetooth|kernel debug)");

    /** Виртуальные адаптеры гипервизоров — не считаем их «физической» сетью. */
    private static final Pattern VM = Pattern.compile(
            "(?i)(virtualbox|vmware|vmnet|hyper-?v|vethernet|qemu|parallels)");

    private SystemInfo() {
    }

    public static Snapshot probe() {
        Snapshot s = new Snapshot();
        s.wifi = readWifi();
        s.wifiConnected = s.wifi != null && s.wifi.connected();

        boolean physical = false;
        boolean vpn = false;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getDisplayName() == null ? ni.getName() : ni.getDisplayName();
                boolean vpnIface = VPN.matcher(name).find() && !NOT_REAL.matcher(name).find();
                boolean hasAddress = hasUsableAddress(ni);
                if (vpnIface) {
                    if (hasAddress) {
                        vpn = true;
                    }
                } else if (hasAddress && !VM.matcher(name).find() && !NOT_REAL.matcher(name).find()) {
                    physical = true;
                }
            }
        } catch (Exception ignored) {
        }

        s.vpnActive = vpn;
        s.physicalOnline = s.wifiConnected || physical;
        s.ethernet = physical && !s.wifiConnected;

        if (s.wifiConnected) {
            String ssid = s.wifi.ssid() == null ? "" : s.wifi.ssid().trim();
            String radio = s.wifi.radio() == null ? "" : s.wifi.radio().trim();
            s.linkText = "Wi-Fi" + (ssid.isEmpty() ? "" : " \"" + ssid + "\"")
                    + (radio.isEmpty() ? "" : " (" + radio + ")");
        } else if (s.ethernet) {
            s.linkText = "Проводное (Ethernet)";
        } else {
            s.linkText = "Отключена";
        }
        return s;
    }

    private static boolean hasUsableAddress(NetworkInterface ni) {
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
            InetAddress address = ia.getAddress();
            if (address == null || address.isLoopbackAddress() || address.isAnyLocalAddress()) {
                continue;
            }
            if (address instanceof Inet4Address) {
                return true;
            }
            if (address instanceof Inet6Address v6 && !v6.isLinkLocalAddress()) {
                return true;
            }
        }
        return false;
    }

    private static Wifi readWifi() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("netsh", "wlan", "show", "interfaces")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            byte[] bytes;
            try (InputStream in = process.getInputStream()) {
                bytes = in.readAllBytes();
            }
            return parseWifi(decodeConsole(bytes));
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * netsh пишет в OEM-кодировке консоли (на русской Windows это CP866),
     * поэтому сначала пробуем UTF-8, затем IBM866. ASCII (английская Windows)
     * корректно читается в обоих случаях.
     */
    private static String decodeConsole(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (Exception e) {
            try {
                return new String(bytes, Charset.forName("IBM866"));
            } catch (Exception e2) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    private static Wifi parseWifi(String text) {
        String ssid = null;
        String radio = "";
        String state = "";
        int signal = -1;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("ssid:")) {
                ssid = valueAfterColon(line);
            } else if (lower.startsWith("signal:") || lower.startsWith("сигнал:")) {
                signal = firstInt(line);
            } else if (lower.startsWith("radio type:") || lower.startsWith("тип радио:")) {
                radio = valueAfterColon(line);
            } else if (lower.startsWith("state:") || lower.startsWith("состояние:")) {
                state = valueAfterColon(line);
            }
        }

        if (ssid == null && signal < 0) {
            return null;
        }
        String stateLower = state.toLowerCase(Locale.ROOT);
        boolean connected = stateLower.contains("подключ") || stateLower.contains("connected")
                || (state.isEmpty() && ssid != null && !ssid.isEmpty());
        return new Wifi(ssid == null ? "" : ssid, signal, radio, connected);
    }

    private static String valueAfterColon(String line) {
        int idx = line.indexOf(':');
        return idx < 0 ? "" : line.substring(idx + 1).trim();
    }

    private static int firstInt(String line) {
        Matcher m = Pattern.compile("(\\d+)").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
