import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders Pickwick's launcher banner (Android TV / Fire TV, 320x180 at xhdpi)
 * at every density, plus store-listing assets (docs/store). No Python needed — JDK 17 runs
 * single-file sources: java -Djava.awt.headless=true scripts/Banner.java app/src/main/res docs/store
 */
public class Banner {
    static final Color TEAL = new Color(0x00695C);
    static final Color TEAL_DEEP = new Color(0x004D43);
    static final Color WHITE = Color.WHITE;

    public static void main(String[] args) throws Exception {
        File res = new File(args[0]);
        File store = new File(args[1]);
        store.mkdirs();
        // Banner: 160x90 dp. Densities: mdpi 1x, hdpi 1.5x, xhdpi 2x, xxhdpi 3x, xxxhdpi 4x.
        String[] buckets = { "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" };
        double[] scales = { 1, 1.5, 2, 3, 4 };
        for (int i = 0; i < buckets.length; i++) {
            File dir = new File(res, "drawable-" + buckets[i]);
            dir.mkdirs();
            ImageIO.write(banner((int) Math.round(160 * scales[i]), (int) Math.round(90 * scales[i])), "png",
                new File(dir, "tv_banner.png"));
        }
        ImageIO.write(icon(512), "png", new File(store, "icon-512.png"));
        ImageIO.write(feature(1280, 720), "png", new File(store, "feature-1280x720.png"));
        ImageIO.write(banner(1280, 720), "png", new File(store, "tv-banner-1280x720.png"));
        System.out.println("wrote banners to " + res + " and store assets to " + store);
    }

    static Graphics2D g(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static Font font(float size) {
        for (String name : new String[] { "Segoe UI", "Arial", "SansSerif" }) {
            Font f = new Font(name, Font.BOLD, Math.round(size));
            if (f.getFamily().equalsIgnoreCase(name) || name.equals("SansSerif")) return f.deriveFont(size);
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size));
    }

    /** Play triangle inside a rounded tile, the launcher icon's mark, at a given tile size. */
    static void mark(Graphics2D g, double x, double y, double size, boolean tile) {
        if (tile) {
            g.setColor(TEAL_DEEP);
            g.fill(new RoundRectangle2D.Double(x, y, size, size, size * 0.24, size * 0.24));
        }
        Path2D tri = new Path2D.Double();
        // Same proportions as ic_launcher_foreground (44,38)-(44,70)-(71,54) in a 108 box.
        tri.moveTo(x + size * 0.40, y + size * 0.35);
        tri.lineTo(x + size * 0.40, y + size * 0.65);
        tri.lineTo(x + size * 0.66, y + size * 0.50);
        tri.closePath();
        g.setColor(WHITE);
        g.fill(tri);
    }

    /** 16:9 banner: tile on the left, wordmark on the right, both on brand teal. */
    static BufferedImage banner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setColor(TEAL);
        g.fillRect(0, 0, w, h);
        double tile = h * 0.56;
        String text = "Pickwick";
        double gap = h * 0.10;
        // Fit the wordmark: shrink until mark + gap + text sits inside 84% of the width.
        float size = (float) (h * 0.30);
        Font f = font(size);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        double total = tile + gap + fm.stringWidth(text);
        while (total > w * 0.84 && size > 8) {
            size *= 0.95f;
            f = font(size);
            g.setFont(f);
            fm = g.getFontMetrics();
            total = tile + gap + fm.stringWidth(text);
        }
        double x = (w - total) / 2.0;
        double y = (h - tile) / 2.0;
        mark(g, x, y, tile, true);
        g.setColor(WHITE);
        double baseline = h / 2.0 + (fm.getAscent() - fm.getDescent()) / 2.0;
        g.drawString(text, (float) (x + tile + gap), (float) baseline);
        g.dispose();
        return img;
    }

    /** Square store icon: the adaptive icon flattened (teal ground, white play). */
    static BufferedImage icon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setColor(TEAL);
        g.fillRect(0, 0, s, s);
        mark(g, 0, 0, s, false);
        g.dispose();
        return img;
    }

    /** Feature graphic: mark, wordmark and the one-line promise. */
    static BufferedImage feature(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setColor(TEAL);
        g.fillRect(0, 0, w, h);
        double tile = h * 0.42;
        double x = w * 0.14;
        double y = (h - tile) / 2.0 - h * 0.04;
        mark(g, x, y, tile, true);
        Font big = font((float) (h * 0.20));
        g.setFont(big);
        g.setColor(WHITE);
        FontMetrics fm = g.getFontMetrics();
        double tx = x + tile + w * 0.05;
        double base = y + tile * 0.62;
        g.drawString("Pickwick", (float) tx, (float) base);
        String tagline = "Only the channels you chose.";
        float ts = (float) (h * 0.065);
        Font small = font(ts).deriveFont(Font.PLAIN);
        g.setFont(small);
        while (tx + g.getFontMetrics().stringWidth(tagline) > w * 0.95 && ts > 8) {
            ts *= 0.95f;
            small = font(ts).deriveFont(Font.PLAIN);
            g.setFont(small);
        }
        g.setColor(new Color(0xB2DFDB));
        g.drawString(tagline, (float) tx, (float) (base + h * 0.13));
        g.dispose();
        return img;
    }
}
