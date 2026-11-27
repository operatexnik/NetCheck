package netcheck;

import javax.net.ssl.HttpsURLConnection;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сетевые проверки. Логика перенесена из Android-версии (MainActivity)
 * один в один: те же URL, таймауты, правила геоблокировки и подсчёт пинга.
 */
public final class Checks {

    public enum Type {NORMAL, GEMINI, CF_TRACE}

    public record Target(String name, String url, Type type, boolean ruWhiteList) {
    }

    public record Result(String status, boolean success, long pingMs, long elapsedMs) {
        public static Result fail() {
            return new Result("\u274C", false, 0, 0);
        }

        public static Result fail(long elapsedMs) {
            return new Result("\u274C", false, 0, elapsedMs);
        }
    }

    public record Location(String text, String countryCode) {
    }

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static final Set<String> BLOCKED = Set.of("RU", "BY", "CN", "HK", "IR", "KP", "SY", "CU");

    public static final List<Target> TARGETS = List.of(
            new Target("VK (РФ)", "https://vk.com/favicon.ico", Type.NORMAL, true),
            new Target("Telegram", "https://api.telegram.org", Type.NORMAL, false),
            new Target("Discord", "https://discord.com/api/v9/gateway", Type.NORMAL, false),
            new Target("YouTube", "https://www.youtube.com/generate_204", Type.NORMAL, false),
            new Target("Gemini", "https://gemini.google.com/app", Type.GEMINI, false),
            new Target("Claude", "https://claude.ai/cdn-cgi/trace", Type.CF_TRACE, false),
            new Target("ChatGPT", "https://chatgpt.com/cdn-cgi/trace", Type.CF_TRACE, false)
    );

    private static final Pattern LOC = Pattern.compile("loc=([A-Z]{2})");
    private static final Pattern IP = Pattern.compile("ip=([^\\s]+)");
    private static final Pattern GEMINI_LOC = Pattern.compile("\"vXmutd\"[^A-Z]*([A-Z]{2})");
    private static final Pattern SUCCESS_FALSE = Pattern.compile("\"success\"\\s*:\\s*false");

    /** -Dnetcheck.debug=true — печатает причины и тайминги сбоев в stderr. */
    private static final boolean DEBUG = Boolean.getBoolean("netcheck.debug");

    private static void debug(String format, Object... args) {
        if (DEBUG) {
            System.err.printf(format + "%n", args);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Checks() {
    }

    private static HttpsURLConnection open(String url, int timeoutMs, String method,
                                           boolean followRedirects) throws Exception {
        HttpsURLConnection conn = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(followRedirects);
        conn.setRequestMethod(method);
        conn.setUseCaches(false);
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("User-Agent", UA);
        return conn;
    }

    private static void closeQuietly(HttpURLConnection conn) {
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private static String readBody(HttpURLConnection conn, int code) {
        try {
            InputStream in = (code >= 200 && code <= 399) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Резервная проверка «жив ли Яндекс» по настоящему HTTPS. */
    public static boolean checkHttpsAlive(String url) {
        HttpsURLConnection conn = null;
        try {
            conn = open(url, 3500, "HEAD", false);
            int code = conn.getResponseCode();
            return code >= 200 && code <= 499;
        } catch (Exception e) {
            debug("[yandex  ] FAIL %s: %s", e.getClass().getSimpleName(), e.getMessage());
            return false;
        } finally {
            closeQuietly(conn);
        }
    }

    /** Быстрая локация через Cloudflare. */
    public static Location fetchFastCfLocation() {
        HttpsURLConnection conn = null;
        try {
            conn = open("https://1.1.1.1/cdn-cgi/trace", 3000, "GET", true);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String text = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String ip = find(IP, text);
            String loc = find(LOC, text);
            if (loc.isEmpty()) {
                return null;
            }
            return new Location("Локация:  Страна [" + loc + "]\nIP:       " + ip, loc);
        } catch (Exception e) {
            debug("[loc-fast] FAIL %s: %s", e.getClass().getSimpleName(), e.getMessage());
            return null;
        } finally {
            closeQuietly(conn);
        }
    }

    /** Подробная локация через ipwho.is. */
    public static Location fetchFullIpLocation() {
        HttpsURLConnection conn = null;
        try {
            conn = open("https://ipwho.is/?lang=ru", 4000, "GET", true);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (SUCCESS_FALSE.matcher(json).find()) {
                return null;
            }
            String country = jsonString(json, "country");
            String code = jsonString(json, "country_code").toUpperCase(Locale.ROOT);
            String city = jsonString(json, "city");
            String ip = jsonString(json, "ip");
            String org = jsonString(json, "org");

            String place = city.isEmpty() ? country : country + ", " + city;
            String text = "Локация:  " + place + " [" + code + "]\nIP / ISP: " + ip
                    + (org.isEmpty() ? "" : " (" + org + ")");
            return new Location(text, code);
        } catch (Exception e) {
            debug("[loc-full] FAIL %s: %s", e.getClass().getSimpleName(), e.getMessage());
            return null;
        } finally {
            closeQuietly(conn);
        }
    }

    public static Result checkService(Target target) {
        Result first = checkServiceOnce(target, initialTimeout(target));
        if (!"\u274C".equals(first.status())) {
            // Успех или явная геоблокировка — повтор не нужен.
            return first;
        }

        // Повторяем один раз всё, кроме Gemini (у него длинный таймаут и блокировка —
        // штатный сценарий). Для Cloudflare-хостов пауза больше: короткие провалы
        // маршрута через VPN обычно проходят за пару секунд.
        if (target.type() == Type.GEMINI) {
            return first;
        }

        boolean cloudflare = target.type() == Type.CF_TRACE;
        boolean fastFailure = first.elapsedMs() < 1500;
        long delay = cloudflare ? 2000 : (fastFailure ? 400 : 1200);
        int retryTimeout = cloudflare ? 4500 : 3500;

        debug("[%-8s] transient failure (%dms), retry in %dms",
                target.name(), first.elapsedMs(), delay);
        sleepQuietly(delay);
        Result second = checkServiceOnce(target, retryTimeout);
        return "\u274C".equals(second.status()) ? first : second;
    }

    private static int initialTimeout(Target target) {
        return switch (target.type()) {
            case GEMINI -> 15000;
            case CF_TRACE -> 6000;
            case NORMAL -> 4500;
        };
    }

    private static Result checkServiceOnce(Target target, int timeout) {
        long started = System.nanoTime();
        HttpsURLConnection conn = null;
        try {
            conn = open(target.url(), timeout, "GET", false);

            long start = System.nanoTime();
            conn.connect();
            long tlsMs = elapsedMs(start);

            start = System.nanoTime();
            int code = conn.getResponseCode();
            long rttMs = elapsedMs(start);

            long ping = rttMs >= 5 ? rttMs : Math.max(1, tlsMs / 2);

            String status = switch (target.type()) {
                case NORMAL -> ping + " мс";
                case CF_TRACE -> cfTraceStatus(conn, code, ping);
                case GEMINI -> geminiStatus(conn, code, ping);
            };
            if ("\u274C".equals(status)) {
                debug("[%-8s] FAIL empty-trace %5dms code=%d timeout=%d",
                        target.name(), elapsedMs(started), code, timeout);
                return Result.fail(elapsedMs(started));
            }
            debug("[%-8s] OK   %5dms tls=%d rtt=%d code=%d timeout=%d",
                    target.name(), elapsedMs(started), tlsMs, rttMs, code, timeout);
            return new Result(status, true, ping, elapsedMs(started));
        } catch (Exception e) {
            debug("[%-8s] FAIL %5dms %s: %s (timeout=%d)",
                    target.name(), elapsedMs(started),
                    e.getClass().getSimpleName(), e.getMessage(), timeout);
            return Result.fail(elapsedMs(started));
        } finally {
            closeQuietly(conn);
        }
    }

    private static String cfTraceStatus(HttpURLConnection conn, int code, long ping) {
        String body = readBody(conn, code);
        String loc = find(LOC, body);
        if (BLOCKED.contains(loc) || code == 403 || code == 451) {
            String tag = loc.isEmpty() ? "" : " [" + loc + "]";
            return "\u274C Геоблок" + tag + " (" + ping + " мс)";
        }
        if (!loc.isEmpty()) {
            return ping + " мс \u2705 [" + loc + "]";
        }
        return "\u274C";
    }

    private static String geminiStatus(HttpsURLConnection conn, int code, long ping) {
        String locationHeader = conn.getHeaderField("Location");
        String locationLower = locationHeader == null ? "" : locationHeader.toLowerCase(Locale.ROOT);
        String body = readGeminiSnippet(conn);
        String googleLoc = find(GEMINI_LOC, body);

        boolean explicitBlock = locationLower.contains("unsupported")
                || body.contains("BardErrorInfo")
                || body.toLowerCase(Locale.ROOT).contains("unsupported_country");

        if (BLOCKED.contains(googleLoc) || explicitBlock) {
            String tag = googleLoc.isEmpty() ? "" : " [" + googleLoc + "]";
            return "\u274C Геоблок" + tag + " (" + ping + " мс)";
        }
        if (!googleLoc.isEmpty()) {
            return ping + " мс \u2705 [" + googleLoc + "]";
        }
        if (code >= 200 && code <= 499) {
            return ping + " мс \u2705 [?]";
        }
        return "\u274C";
    }

    /**
     * Читает страницу Gemini, но останавливается сразу после нахождения
     * кода страны vXmutd, чтобы не качать всю страницу.
     */
    private static String readGeminiSnippet(HttpsURLConnection conn) {
        InputStream in;
        try {
            int code = conn.getResponseCode();
            in = (code >= 200 && code <= 399) ? conn.getInputStream() : conn.getErrorStream();
        } catch (Exception e) {
            return "";
        }
        if (in == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while (sb.length() < 90000 && (read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
                if (sb.indexOf("\"vXmutd\"") >= 0
                        || sb.indexOf("unsupported_country") >= 0
                        || sb.indexOf("BardErrorInfo") >= 0) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static String jsonString(String json, String key) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = pattern.matcher(json);
        return m.find() ? unescape(m.group(1)) : "";
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append("\\u");
                        }
                    } else {
                        sb.append("\\u");
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}
