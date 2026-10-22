package com.xiongx9527.askdata.chart.server;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Server-side chart rasterization: renders askdata normalized G2-style chart config
 * (produced by ChatChartBuilder, shape type/title/columns/axis) plus data rows into a PNG.
 *
 * <p>Equivalent to SQLBot's g2-ssr (AntV G2 config -> PNG) but implemented with JDK
 * Java2D instead of Node/browser, so it is offline-unit-testable and requires no external
 * process. Supports column/bar/line/area/pie/table; unknown types render as a table.</p>
 */
@Component
public class ChartImageRenderer {

    public static final int DEFAULT_WIDTH = 900;
    public static final int DEFAULT_HEIGHT = 520;

    private static final Color BG = Color.WHITE;
    private static final Color AXIS = Color.DARK_GRAY;
    private static final Color GRID = new Color(220, 220, 220);
    private static final Color[] SERIES = {
            new Color(91, 143, 246), new Color(91, 199, 160), new Color(246, 173, 85),
            new Color(237, 104, 104), new Color(165, 125, 225), new Color(82, 196, 196)
    };

    public byte[] renderPng(Map<String, Object> chart, List<Map<String, Object>> data) {
        return renderPng(chart, data, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public byte[] renderPng(Map<String, Object> chart, List<Map<String, Object>> data, int width, int height) {
        BufferedImage img = render(chart, data, width, height);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("chart PNG encode failed: " + e.getMessage(), e);
        }
    }

    public BufferedImage render(Map<String, Object> chart, List<Map<String, Object>> data, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, width, height);

        String type = chart == null ? "table" : String.valueOf(chart.get("type"));
        String title = chart == null ? "" : String.valueOf(chart.get("title") == null ? "" : chart.get("title"));

        int pad = 48;
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        if (title != null && !title.isEmpty()) {
            g.setColor(Color.BLACK);
            g.drawString(title, pad, 36);
        }

        List<Map<String, Object>> rows = data == null ? new ArrayList<>() : data;
        switch (type) {
            case "pie":
                drawPie(g, chart, rows, pad, 56, width - pad * 2, height - pad - 56);
                break;
            case "line":
            case "area":
                drawCartesian(g, chart, rows, type, pad, 56, width - pad * 2, height - pad - 56, true);
                break;
            case "bar":
                drawCartesian(g, chart, rows, "bar", pad, 56, width - pad * 2, height - pad - 56, false);
                break;
            case "column":
                drawCartesian(g, chart, rows, "column", pad, 56, width - pad * 2, height - pad - 56, false);
                break;
            default:
                drawTable(g, chart, rows, pad, 56, width - pad * 2, height - pad - 56);
        }
        g.dispose();
        return img;
    }

    private static List<String> yFields(Map<String, Object> chart) {
        List<String> fields = new ArrayList<>();
        Object axis = chart.get("axis");
        if (axis instanceof Map) {
            Object y = ((Map<?, ?>) axis).get("y");
            if (y instanceof List) {
                for (Object item : (List<?>) y) {
                    String v = axisValueField(item);
                    if (v != null) fields.add(v);
                }
            } else {
                String v = axisValueField(y);
                if (v != null) fields.add(v);
            }
        }
        return fields;
    }

    private static String xField(Map<String, Object> chart) {
        Object axis = chart.get("axis");
        if (axis instanceof Map) {
            Object x = ((Map<?, ?>) axis).get("x");
            if (x instanceof Map) {
                Object v = ((Map<?, ?>) x).get("value");
                return v == null ? null : String.valueOf(v);
            }
        }
        return null;
    }

    private static String axisValueField(Object node) {
        if (node instanceof Map) {
            Object v = ((Map<?, ?>) node).get("value");
            return v == null ? null : String.valueOf(v);
        }
        return node == null ? null : String.valueOf(node);
    }

    private static List<String> categories(List<Map<String, Object>> rows, String xField) {
        List<String> cats = new ArrayList<>();
        if (xField == null) {
            for (int i = 0; i < rows.size(); i++) cats.add(String.valueOf(i + 1));
        } else {
            for (Map<String, Object> row : rows) {
                Object v = row.get(xField);
                cats.add(v == null ? "" : String.valueOf(v));
            }
        }
        return cats;
    }

    private static double[] values(List<Map<String, Object>> rows, String field) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Object v = field == null ? null : rows.get(i).get(field);
            out[i] = parseDouble(v);
        }
        return out;
    }

    private static double parseDouble(Object v) {
        if (v == null) return 0d;
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private void drawCartesian(Graphics2D g, Map<String, Object> chart, List<Map<String, Object>> rows,
                               String type, int x0, int y0, int w, int h, boolean connect) {
        List<String> yfs = yFields(chart);
        String xf = xField(chart);
        if (yfs.isEmpty()) {
            drawEmpty(g, x0, y0, w, h);
            return;
        }
        List<String> cats = categories(rows, xf);
        int n = Math.max(cats.size(), 1);
        double max = 0;
        double min = 0;
        for (String yf : yfs) {
            double[] vals = values(rows, yf);
            for (double v : vals) {
                if (v > max) max = v;
                if (v < min) min = v;
            }
        }
        if (max == min) max = max + 1;
        if (min > 0) min = 0;

        int left = x0 + 60;
        int bottom = y0 + h - 40;
        int plotW = w - 80;
        int plotH = h - 60;

        g.setColor(GRID);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        int ticks = 5;
        for (int i = 0; i <= ticks; i++) {
            int gy = bottom - (int) ((double) i / ticks * plotH);
            g.drawLine(left, gy, left + plotW, gy);
            double val = min + (max - min) * (double) i / ticks;
            g.setColor(Color.GRAY);
            g.drawString(fmt(val), left - 56, gy + 4);
            g.setColor(GRID);
        }
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(left, y0, left, bottom);
        g.drawLine(left, bottom, left + plotW, bottom);

        double catW = (double) plotW / n;
        for (int s = 0; s < yfs.size(); s++) {
            String yf = yfs.get(s);
            double[] vals = values(rows, yf);
            Color c = SERIES[s % SERIES.length];
            g.setColor(c);
            int prevX = -1, prevY = -1;
            for (int i = 0; i < cats.size(); i++) {
                int cx = left + (int) (catW * (i + 0.5));
                int cy = bottom - (int) (((vals[i] - min) / (max - min)) * plotH);
                if (type.equals("column")) {
                    int bw = (int) (catW * 0.6 / yfs.size());
                    int bx = cx - bw / 2 + s * (bw + 4);
                    g.fillRect(bx, cy, bw, bottom - cy);
                } else if (type.equals("bar")) {
                    int bh = (int) (catW * 0.6 / yfs.size());
                    int by = cy - bh / 2 + s * (bh + 4);
                    g.fillRect(left, by, cx - left, bh);
                } else if (connect) {
                    if (prevX >= 0) {
                        g.setStroke(new BasicStroke(2.5f));
                        g.drawLine(prevX, prevY, cx, cy);
                        g.setStroke(new BasicStroke(1.5f));
                    }
                    if (type.equals("area")) {
                        g.fillRect(cx - 1, cy, 2, bottom - cy);
                    }
                    g.fillOval(cx - 4, cy - 4, 8, 8);
                }
                prevX = cx;
                prevY = cy;
            }
        }
        g.setColor(Color.GRAY);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        for (int i = 0; i < cats.size(); i++) {
            int cx = left + (int) (catW * (i + 0.5));
            String label = cats.get(i);
            if (label.length() > 6) label = label.substring(0, 6) + "..";
            g.drawString(label, cx - 12, bottom + 18);
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        int lx = left;
        for (int s = 0; s < yfs.size(); s++) {
            g.setColor(SERIES[s % SERIES.length]);
            g.fillRect(lx, y0 - 6, 14, 14);
            g.setColor(Color.DARK_GRAY);
            g.drawString(yfs.get(s), lx + 18, y0 + 6);
            lx += 30 + g.getFontMetrics().stringWidth(yfs.get(s)) + 14;
        }
    }

    private void drawPie(Graphics2D g, Map<String, Object> chart, List<Map<String, Object>> rows,
                         int x0, int y0, int w, int h) {
        List<String> yfs = yFields(chart);
        String xf = xField(chart);
        if (yfs.isEmpty()) {
            drawEmpty(g, x0, y0, w, h);
            return;
        }
        String yf = yfs.get(0);
        List<String> cats = categories(rows, xf);
        double[] vals = values(rows, yf);
        double total = 0;
        for (double v : vals) total += Math.abs(v);
        if (total <= 0) {
            drawEmpty(g, x0, y0, w, h);
            return;
        }
        int cx = x0 + h / 2;
        int cy = y0 + h / 2;
        int r = Math.min(h, w) / 2 - 20;
        int start = 0;
        for (int i = 0; i < cats.size(); i++) {
            int angle = (int) (Math.abs(vals[i]) / total * 360);
            g.setColor(SERIES[i % SERIES.length]);
            g.fillArc(cx - r, cy - r, r * 2, r * 2, start, angle);
            start += angle;
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        int lx = cx + r + 20;
        int ly = cy - (cats.size() * 20) / 2;
        for (int i = 0; i < cats.size(); i++) {
            g.setColor(SERIES[i % SERIES.length]);
            g.fillRect(lx, ly + i * 20, 14, 14);
            g.setColor(Color.DARK_GRAY);
            String label = cats.get(i) + " (" + fmt(vals[i]) + ")";
            if (label.length() > 22) label = label.substring(0, 22) + "..";
            g.drawString(label, lx + 18, ly + i * 20 + 12);
        }
    }

    private void drawTable(Graphics2D g, Map<String, Object> chart, List<Map<String, Object>> rows,
                           int x0, int y0, int w, int h) {
        List<String> cols = new ArrayList<>();
        Object columns = chart == null ? null : chart.get("columns");
        if (columns instanceof List) {
            for (Object item : (List<?>) columns) {
                if (item instanceof Map) {
                    Object v = ((Map<?, ?>) item).get("value");
                    if (v != null) cols.add(String.valueOf(v));
                }
            }
        }
        if (cols.isEmpty() && !rows.isEmpty()) {
            cols.addAll(rows.get(0).keySet());
        }
        if (cols.isEmpty()) {
            drawEmpty(g, x0, y0, w, h);
            return;
        }
        int colW = Math.min((w - 20) / cols.size(), 200);
        int rowH = 28;
        int tx = x0;
        int ty = y0;
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(new Color(240, 242, 245));
        g.fillRect(tx, ty, colW * cols.size(), rowH);
        g.setColor(Color.BLACK);
        for (int c = 0; c < cols.size(); c++) {
            String label = cols.get(c);
            if (label.length() > 18) label = label.substring(0, 18) + "..";
            g.drawString(label, tx + c * colW + 6, ty + 19);
        }
        ty += rowH;
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        for (int r = 0; r < rows.size() && ty < y0 + h; r++) {
            Map<String, Object> row = rows.get(r);
            if (r % 2 == 1) {
                g.setColor(new Color(248, 249, 250));
                g.fillRect(tx, ty, colW * cols.size(), rowH);
            }
            g.setColor(Color.DARK_GRAY);
            for (int c = 0; c < cols.size(); c++) {
                Object v = row.get(cols.get(c));
                String s = v == null ? "" : String.valueOf(v);
                if (s.length() > 18) s = s.substring(0, 18) + "..";
                g.drawString(s, tx + c * colW + 6, ty + 19);
            }
            ty += rowH;
        }
    }

    private void drawEmpty(Graphics2D g, int x0, int y0, int w, int h) {
        g.setColor(Color.GRAY);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.drawString("no data", x0 + w / 2 - 30, y0 + h / 2);
    }

    private static String fmt(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
