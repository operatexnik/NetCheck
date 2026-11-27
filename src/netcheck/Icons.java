package netcheck;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Программно нарисованные иконки: окно, трей, состояния. */
public final class Icons {
    private Icons() {
    }

    public static Image appIcon(int size) {
        return draw(size, Theme.ACCENT);
    }

    public static Image trayIcon(int size, Color color) {
        return draw(size, color);
    }

    private static Image draw(int size, Color color) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        float pad = size * 0.04f;
        g.setColor(color);
        g.fill(new RoundRectangle2D.Float(pad, pad, size - 2 * pad, size - 2 * pad,
                size * 0.28f, size * 0.28f));

        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(size * 0.075f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        float cx = size / 2f;
        float cy = size * 0.74f;
        float[] radii = {size * 0.34f, size * 0.22f, size * 0.10f};
        for (float r : radii) {
            g.draw(new Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, 45, 90, Arc2D.OPEN));
        }
        float dot = size * 0.045f;
        g.fill(new Ellipse2D.Float(cx - dot, cy - dot, dot * 2, dot * 2));

        g.dispose();
        return img;
    }
}
