package com.example.orders.demo;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A product picture for the demo catalogue: the category's colour, the brand, the product name and
 * the category. Drawn here, so the demo needs no photos from anywhere.
 */
public final class DemoTiles {

    static final int SIZE = 1000;

    private DemoTiles() {}

    public static byte[] render(String brand, String name, String category, String hexColour) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color base = Color.decode(hexColour);
            g.setPaint(new GradientPaint(0, 0, base.brighter(), SIZE, SIZE, base.darker()));
            g.fillRect(0, 0, SIZE, SIZE);

            // A soft ring, so tiles are not flat colour
            g.setColor(new Color(255, 255, 255, 40));
            g.setStroke(new BasicStroke(60));
            g.drawOval(520, -180, 700, 700);

            // White card with the text
            g.setColor(new Color(255, 255, 255, 235));
            g.fill(new RoundRectangle2D.Double(90, 300, SIZE - 180, 470, 60, 60));

            g.setColor(base.darker());
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 46));
            g.drawString(brand.toUpperCase(), 150, 390);

            g.setColor(new Color(20, 30, 50));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
            int y = 480;
            for (String line : wrap(name.replaceFirst("^" + java.util.regex.Pattern.quote(brand) + "\\s+", ""),
                    g.getFontMetrics(), SIZE - 300, 3)) {
                g.drawString(line, 150, y);
                y += 78;
            }

            g.setColor(new Color(90, 100, 120));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 38));
            g.drawString(category, 150, 730);

            g.setColor(new Color(255, 255, 255, 220));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
            g.drawString("Demo photo", 90, 900);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not draw a demo photo", e);
        }
    }

    private static List<String> wrap(String text, FontMetrics metrics, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        if (lines.size() > maxLines) {
            List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
            cut.set(maxLines - 1, cut.get(maxLines - 1) + "…");
            return cut;
        }
        return lines;
    }
}
