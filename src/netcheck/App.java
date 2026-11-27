package netcheck;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Главное окно приложения. */
public final class App {

    private final JFrame frame = new JFrame("Net & VPN Checker");
    private final JTextArea infoArea = new JTextArea();
    private final JTextArea servicesArea = new JTextArea();
    private final PrimaryButton checkButton = new PrimaryButton("ПРОВЕРИТЬ");
    private final TrayManager tray;
    private final AtomicInteger runSeq = new AtomicInteger();

    private CheckSession session;
    private boolean hiddenHintShown;

    public App(TrayManager tray) {
        this.tray = tray;
        buildUi();
        infoArea.setText("Нажмите «Проверить» для запуска диагностики");
        servicesArea.setText(initialServices());
        infoArea.setCaretPosition(0);
        servicesArea.setCaretPosition(0);
    }

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setIconImage(Icons.appIcon(64));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.setBorder(new EmptyBorder(22, 22, 22, 22));

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new javax.swing.BoxLayout(column, javax.swing.BoxLayout.Y_AXIS));
        column.setAlignmentX(Component.LEFT_ALIGNMENT);

        column.add(Theme.section("СТАТУС СЕТИ И VPN"));
        column.add(javax.swing.Box.createVerticalStrut(10));
        column.add(card(infoArea, 15, 7));
        column.add(javax.swing.Box.createVerticalStrut(22));
        column.add(Theme.section("ДОСТУПНОСТЬ И ПИНГ (МС)"));
        column.add(javax.swing.Box.createVerticalStrut(10));
        column.add(card(servicesArea, 16, 8));

        root.add(column, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(new EmptyBorder(18, 0, 0, 0));
        checkButton.setPreferredSize(new Dimension(10, 58));
        checkButton.addActionListener(e -> startCheck());
        bottom.add(checkButton, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();

        // Не выпускаем окно за пределы рабочего стола (ноутбуки с малым экраном, масштаб 125%).
        java.awt.Rectangle usable = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int width = Math.min(500, Math.max(420, usable.width - 32));
        int height = Math.min(760, Math.max(560, usable.height - 16));
        frame.setMinimumSize(new Dimension(Math.min(440, width), Math.min(620, height)));
        frame.setSize(width, height);
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                hideToTray();
            }
        });

        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "check");
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_R, java.awt.event.InputEvent.CTRL_DOWN_MASK), "check");
        frame.getRootPane().getActionMap().put("check", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startCheck();
            }
        });

        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide");
        frame.getRootPane().getActionMap().put("hide", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hideToTray();
            }
        });
    }

    private static JPanel card(JTextArea area, int fontSize, int rows) {
        area.setEditable(false);
        area.setOpaque(false);
        area.setForeground(Theme.TEXT);
        area.setCaretColor(Theme.TEXT);
        area.setSelectionColor(Theme.ACCENT);
        area.setSelectedTextColor(Color.WHITE);
        area.setFont(Theme.mono(fontSize));
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setBorder(null);
        area.setMargin(new Insets(0, 0, 0, 0));

        JScrollPane scroll = new JScrollPane(area,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.PANEL);
        panel.setBorder(new EmptyBorder(12, 16, 12, 16));
        panel.add(scroll, BorderLayout.CENTER);

        FontMetrics metrics = area.getFontMetrics(area.getFont());
        int height = rows * metrics.getHeight() + 26;
        panel.setPreferredSize(new Dimension(360, height));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static String initialServices() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Checks.TARGETS.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(String.format(Locale.ROOT, "%-8s: \u2014", Checks.TARGETS.get(i).name()));
        }
        return sb.toString();
    }

    public void startCheck() {
        final int id = runSeq.incrementAndGet();
        if (session != null) {
            session.cancel();
            session = null;
        }

        checkButton.setEnabled(false);
        checkButton.setText("ПРОВЕРКА...");
        checkButton.repaint();
        if (tray != null) {
            tray.setState(TrayManager.State.CHECKING, "Net & VPN Checker\nПроверка...");
        }

        Thread probe = new Thread(() -> {
            SystemInfo.Snapshot snapshot;
            try {
                snapshot = SystemInfo.probe();
            } catch (Exception e) {
                snapshot = new SystemInfo.Snapshot();
            }
            final SystemInfo.Snapshot prepared = snapshot;
            SwingUtilities.invokeLater(() -> {
                if (id != runSeq.get()) {
                    return;
                }
                beginSession(prepared);
            });
        }, "netcheck-probe");
        probe.setDaemon(true);
        probe.start();
    }

    private void beginSession(SystemInfo.Snapshot sys) {
        session = new CheckSession(sys, new CheckSession.Listener() {
            @Override
            public void onChange(CheckSession.Snapshot snapshot) {
                render(snapshot);
            }

            @Override
            public void onDone(CheckSession.Snapshot snapshot) {
                checkButton.setEnabled(true);
                checkButton.setText("ПРОВЕРИТЬ");
                if (tray != null) {
                    tray.setState(TrayManager.stateFor(snapshot), tooltipFor(snapshot));
                    if (!frame.isVisible()) {
                        tray.balloon("Net & VPN Checker", summary(snapshot));
                    }
                }
            }
        });
        session.start();
    }

    private void render(CheckSession.Snapshot snapshot) {
        infoArea.setText(snapshot.infoText);
        infoArea.setCaretPosition(0);
        servicesArea.setText(snapshot.servicesText);
        servicesArea.setCaretPosition(0);
    }

    public void show() {
        frame.setVisible(true);
        if ((frame.getExtendedState() & JFrame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~JFrame.ICONIFIED);
        }
        frame.toFront();
        frame.requestFocus();
    }

    private void hideToTray() {
        if (tray == null) {
            exit();
            return;
        }
        frame.setVisible(false);
        if (!hiddenHintShown) {
            hiddenHintShown = true;
            tray.balloon("Свёрнуто в трей",
                    "Приложение продолжает работать. Клик по иконке в трее откроет окно.");
        }
    }

    public void exit() {
        if (session != null) {
            session.cancel();
        }
        if (tray != null) {
            tray.remove();
        }
        frame.dispose();
    }

    private static String summary(CheckSession.Snapshot snapshot) {
        if (!snapshot.physicalOnline) {
            return "Сеть отключена";
        }
        String tg = snapshot.tgPing > 0 ? "TG:" + snapshot.tgPing : "TG:\u274C";
        String ai = snapshot.aiOk >= 3 ? "AI:3/3 \u2705"
                : snapshot.aiOk > 0 ? "AI:" + snapshot.aiOk + "/3 \u26A0"
                : "AI:\u274C";
        if (snapshot.globalAlive) {
            String loc = snapshot.countryCode.isEmpty() ? "" : " [" + snapshot.countryCode + "]";
            return "Глоб \u2705" + loc + "\n" + tg + " " + ai;
        }
        if (snapshot.ruAlive) {
            return "Только белый список РФ \u26A0\nМир: \u274C";
        }
        return "Нет интернета \u274C\nГлухо / обрыв";
    }

    private static String tooltipFor(CheckSession.Snapshot snapshot) {
        return "Net & VPN Checker\n" + summary(snapshot);
    }

    /* ---------- режим демонстрационного скриншота ---------- */

    public void renderDemo(String info, String services) {
        infoArea.setText(info);
        servicesArea.setText(services);
        infoArea.setCaretPosition(0);
        servicesArea.setCaretPosition(0);
        checkButton.setEnabled(true);
        checkButton.setText("ПРОВЕРИТЬ");
    }

    public void shot(String path) throws Exception {
        frame.setLocation(-4000, -4000);
        frame.setVisible(true);
        frame.validate();
        Container content = frame.getContentPane();
        int width = Math.max(1, content.getWidth());
        int height = Math.max(1, content.getHeight());

        BufferedImage image = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(2, 2);
        content.paint(g);
        g.dispose();

        ImageIO.write(image, "png", new File(path));
        frame.setVisible(false);
        frame.dispose();
    }

    /** Кнопка с рисуемым скруглённым фоном (тёмная тема не дружит с LAF). */
    private static final class PrimaryButton extends JButton {
        PrimaryButton(String text) {
            super(text);
            setFont(Theme.ui(15, Font.BOLD));
            setForeground(Color.WHITE);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(0, 16, 0, 16));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            Color background;
            if (!isEnabled()) {
                background = Theme.DISABLED;
            } else if (getModel().isPressed()) {
                background = Theme.ACCENT_DOWN;
            } else if (getModel().isRollover()) {
                background = Theme.ACCENT_HOVER;
            } else {
                background = Theme.ACCENT;
            }
            g2.setColor(background);
            g2.fillRoundRect(0, 0, width, height, 12, 12);

            g2.setFont(getFont());
            FontMetrics metrics = g2.getFontMetrics();
            String text = getText();
            g2.setColor(isEnabled() ? Color.WHITE : Theme.DISABLED_TEXT);
            int x = (width - metrics.stringWidth(text)) / 2;
            int y = (height - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(text, x, y);
            g2.dispose();
        }
    }
}
