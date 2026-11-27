package netcheck;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Заставляет Java доверять стандартным корневым сертификатам И хранилищу
 * сертификатов Windows. Это важно, если антивирус (Kaspersky, ESET и т.п.)
 * инспектирует HTTPS и подменяет сертификат своим: без этого Java падает
 * с «PKIX path building failed», и сервис ошибочно показывается как «❌».
 */
public final class SslTrust {

    private SslTrust() {
    }

    public static void install() {
        try {
            X509TrustManager system = trustManager(null);
            X509TrustManager windows = isWindows() ? trustManager("Windows-ROOT") : null;
            if (system == null && windows == null) {
                return;
            }

            X509TrustManager combined;
            if (system == null) {
                combined = windows;
            } else if (windows == null) {
                combined = system;
            } else {
                combined = new Composite(system, windows);
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{combined}, null);
            SSLContext.setDefault(context);
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        } catch (Exception ignored) {
            // Остаёмся на стандартном хранилище Java.
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** keyStoreType == null — стандартное хранилище Java (cacerts). */
    private static X509TrustManager trustManager(String keyStoreType) {
        try {
            KeyStore keyStore = keyStoreType == null ? null : KeyStore.getInstance(keyStoreType);
            if (keyStore != null) {
                keyStore.load(null, null);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    return x509;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Пробует доверять по очереди нескольким хранилищам. */
    private static final class Composite implements X509TrustManager {
        private final X509TrustManager[] delegates;

        Composite(X509TrustManager... delegates) {
            this.delegates = delegates;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, true);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, false);
        }

        private void check(X509Certificate[] chain, String authType, boolean client)
                throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    if (client) {
                        delegate.checkClientTrusted(chain, authType);
                    } else {
                        delegate.checkServerTrusted(chain, authType);
                    }
                    return;
                } catch (CertificateException e) {
                    last = e;
                } catch (RuntimeException e) {
                    last = new CertificateException(e);
                }
            }
            throw last != null ? last : new CertificateException("Untrusted certificate chain");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509TrustManager delegate : delegates) {
                for (X509Certificate certificate : delegate.getAcceptedIssuers()) {
                    all.add(certificate);
                }
            }
            return all.toArray(new X509Certificate[0]);
        }
    }
}
