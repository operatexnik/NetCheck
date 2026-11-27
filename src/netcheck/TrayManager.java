package netcheck;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Иконка в системном трее — замена плитке быстрых настроек на Android.
 * Клик открывает окно, меню умеет запустить проверку и выйти.
 */
public final class TrayManager {

    public enum State {IDLE, CHECKING, GLOBAL, WHITELIST, FAIL, OFFLINE}

    private final SystemTray systemTray;
    private final TrayIcon icon;
    private final MenuItem checkItem = new MenuItem("Проверить сейчас");

    private TrayManager(Runnable onOpen, Runnable onCheck, Runnable onExit) {
        systemTray = SystemTray.getSystemTray();
        icon = new TrayIcon(Icons.trayIcon(64, accent(State.IDLE)), "Net & VPN Checker");
        icon.setImageAutoSize(true);

        MenuItem openItem = new MenuItem("Открыть окно");
        openItem.addActionListener(e -> onOpen.run());
        checkItem.addActionListener(e -> onCheck.run());
        MenuItem exitItem = new MenuItem("Выход");
        exitItem.addActionListener(e -> onExit.run());

        PopupMenu menu = new PopupMenu();
        menu.add(openItem);
        menu.add(checkItem);
        menu.addSeparator();
        menu.add(exitItem);
        icon.setPopupMenu(menu);

        // Двойной клик по иконке.
        icon.addActionListener(e -> onOpen.run());
        // Одиночный клик тоже открывает окно (на Windows ActionListener ловит двойной).
        icon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                    onOpen.run();
                }
            }
        });

        try {
            systemTray.add(icon);
        } catch (AWTException e) {
            throw new IllegalStateException("Не удалось добавить иконку в трей", e);
        }
        setState(State.IDLE, "Net & VPN Checker\nНажмите для проверки");
    }

    /** null, если системный трей недоступен. */
    public static TrayManager install(Runnable onOpen, Runnable onCheck, Runnable onExit) {
        if (!SystemTray.isSupported()) {
            return null;
        }
        return new TrayManager(onOpen, onCheck, onExit);
    }

    public void setState(State state, String tooltip) {
        icon.setImage(Icons.trayIcon(64, accent(state)));
        String text = tooltip == null || tooltip.isBlank() ? "Net & VPN Checker" : tooltip;
        icon.setToolTip(text.length() > 127 ? text.substring(0, 127) : text);
        checkItem.setEnabled(state != State.CHECKING);
    }

    public void balloon(String title, String text) {
        icon.displayMessage(title, text, TrayIcon.MessageType.NONE);
    }

    public void remove() {
        systemTray.remove(icon);
    }

    public static State stateFor(CheckSession.Snapshot snapshot) {
        if (!snapshot.finished) {
            return State.CHECKING;
        }
        if (!snapshot.physicalOnline) {
            return State.OFFLINE;
        }
        if (snapshot.globalAlive) {
            return State.GLOBAL;
        }
        if (snapshot.ruAlive) {
            return State.WHITELIST;
        }
        return State.FAIL;
    }

    private static Color accent(State state) {
        return switch (state) {
            case CHECKING -> new Color(0xFFB300);
            case GLOBAL -> new Color(0x43A047);
            case WHITELIST -> new Color(0xFB8C00);
            case FAIL, OFFLINE -> new Color(0xE53935);
            default -> Theme.ACCENT;
        };
    }
}
