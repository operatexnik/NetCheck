package netcheck;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Font;

/** Общая палитра и шрифты приложения. */
public final class Theme {
    public static final Color BG = new Color(0x000000);
    public static final Color PANEL = new Color(0x111111);
    public static final Color TEXT = new Color(0xFFFFFF);
    public static final Color MUTED = new Color(0x888888);
    public static final Color ACCENT = new Color(0x1E88E5);
    public static final Color ACCENT_HOVER = new Color(0x42A5F5);
    public static final Color ACCENT_DOWN = new Color(0x1565C0);
    public static final Color DISABLED = new Color(0x2A2A2A);
    public static final Color DISABLED_TEXT = new Color(0x777777);

    private Theme() {
    }

    public static Font mono(int size) {
        return new Font("Monospaced", Font.PLAIN, size);
    }

    public static Font ui(int size, int style) {
        return new Font("Segoe UI", style, size);
    }

    public static JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setFont(ui(12, Font.BOLD));
        label.setForeground(MUTED);
        label.setAlignmentX(0f);
        return label;
    }
}
