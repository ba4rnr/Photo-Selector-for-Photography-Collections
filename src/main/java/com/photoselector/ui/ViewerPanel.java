package com.photoselector.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.photoselector.io.ImageSource;
import com.photoselector.ui.theme.Theme;

/**
 * 中间的大图视图：看素材照片的原尺寸内容。
 *
 * <p>这是挑片的主工具——缩略图只能告诉你"有这张"，只有大图能告诉你"这张行不行"。
 *
 * <p>缩放与平移的状态就两个：一个比例、一个偏移。分三种比例：
 * <ul>
 *   <li><b>适应窗口</b>：按窗口大小现算，随窗口变化。默认就是它。</li>
 *   <li><b>100%</b>：一个图片像素对一个屏幕像素，看合焦与否就靠它。</li>
 *   <li><b>滚轮缩放</b>：在适应窗口的基础上乘以倍数，从 5% 到 800%。</li>
 * </ul>
 *
 * <p>平移偏移会被<b>夹住</b>：最多拖到图片边缘与窗口边缘对齐，再拖也拖不动。
 * 图片比窗口小的那个方向直接固定居中——否则能把照片拖出视野，只剩一片黑。
 *
 * <p>同一时刻只留一张原图。24MP 解出来是九十多兆，留着上一张纯属浪费内存。
 */
@SuppressWarnings("serial")
final class ViewerPanel extends JPanel {

    /** 表头（文件名 · 像素 · 缩放控件）占的高度。 */
    private static final int HEADER_HEIGHT = 26;

    /** 图片四周的留白。 */
    private static final int PADDING = 12;

    /** 缩放比例的上下限。 */
    private static final double MIN_SCALE = 0.05;
    private static final double MAX_SCALE = 8.0;

    /**
     * 打开一张照片时的默认放大倍率。
     *
     * <p>用一个固定比例而不是"适应窗口"，是为了让不同的照片都按同一个倍率显示——
     * 你的照片多在 6000 像素上下，14% 大约是 840 像素，正好一屏宽。
     * 连着翻几张时看到的比例一致，方便横向比较。
     */
    private static final double DEFAULT_SCALE = 0.14;

    private final ThumbnailLoader loader;

    private Path photo;
    private BufferedImage image;
    private boolean loading;
    private boolean failed;

    /** 是不是"适应窗口"。默认不是——默认按 {@link #DEFAULT_SCALE} 显示。 */
    private boolean fitToWindow;

    /** 非适应窗口时用的比例。 */
    private double customScale = DEFAULT_SCALE;

    /** 相对"居中"位置挪了多少。 */
    private int offsetX;
    private int offsetY;

    /** 拖动平移用：按下时的指针位置，以及当时的偏移。 */
    private Point dragStart;
    private int dragOffsetX;
    private int dragOffsetY;

    /** 表头上两个按钮的位置，画的时候算出来，点击时做命中判断。 */
    private Rectangle fitButtonBounds;
    private Rectangle actualButtonBounds;

    /** 缩放状态变了时通知外部（菜单里的勾选跟着变）。 */
    private Runnable onZoomChanged = () -> { };

    ViewerPanel(ThumbnailLoader loader) {
        this.loader = loader;
        setBackground(Theme.CANVAS);
        setOpaque(true);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (hit(e.getPoint(), fitButtonBounds)) {
                    fitToWindow();
                } else if (hit(e.getPoint(), actualButtonBounds)) {
                    actualSize();
                } else if (canPan()) {
                    dragStart = e.getPoint();
                    dragOffsetX = offsetX;
                    dragOffsetY = offsetY;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null) {
                    return;
                }
                setOffset(dragOffsetX + (e.getX() - dragStart.x),
                        dragOffsetY + (e.getY() - dragStart.y));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragStart = null;
                setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoomStep(-e.getWheelRotation() > 0 ? 1 : -1);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    // ─────────────────────────── 对外接口 ───────────────────────────

    /** 换一张要看的大图；传 null 表示什么都不看。 */
    void showPhoto(Path newPhoto) {
        this.photo = newPhoto;
        this.image = null;
        this.failed = false;
        this.loading = newPhoto != null;
        resetView(); // 换片把位置收回中间；缩放比例保留，方便连着看一堆细节
        repaint();
        if (newPhoto != null) {
            loader.loadFull(newPhoto, this::onLoaded);
        }
    }

    /** 适应窗口：按当前窗口大小现算比例，偏移归零。 */
    void fitToWindow() {
        fitToWindow = true;
        resetView();
        repaint();
        onZoomChanged.run();
    }

    /** 100%：一个图片像素对一个屏幕像素。 */
    void actualSize() {
        fitToWindow = false;
        customScale = 1.0;
        resetView();
        repaint();
        onZoomChanged.run();
    }

    /**
     * 滚轮缩放：在"整百倍率"的档位之间跳。
     *
     * <p>档位是 {@code 100%、200%、300%……}，外加最下面那档默认的
     * {@link #DEFAULT_SCALE}（14%）。<b>第一格一定是跳到 100%</b>——
     * 之前做成连续倍数（14% → 16% → 18%……）是个错误：那样靠滚轮根本到不了 100%，
     * 而 100% 恰恰是看合焦最常用的那一档。
     *
     * <p>从别的比例（比如"适应窗口"算出来的 63%）滚下去，也是先落到最近的整百档：
     * 往上取比它大的那一档，往下取比它小的那一档——方向感比"绝对最近"更重要。
     *
     * @param direction 正数放大，负数缩小
     */
    void zoomStep(int direction) {
        if (image == null) {
            return;
        }
        double current = fitToWindow ? fitScale() : customScale;
        fitToWindow = false;

        if (direction > 0) {
            // 还没到 100% 就直接跳到 100%，否则往下一个整百档去
            customScale = current < 1.0 ? 1.0 : Math.floor(current) + 1.0;
        } else {
            // 100% 及以下就退回默认那档；再往下没有更小的整百档了
            customScale = current <= 1.0 ? DEFAULT_SCALE : Math.ceil(current) - 1.0;
            if (customScale < 1.0) {
                customScale = DEFAULT_SCALE;
            }
        }
        customScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, customScale));
        clampOffset();
        repaint();
        onZoomChanged.run();
    }

    void setOnZoomChanged(Runnable listener) {
        this.onZoomChanged = listener == null ? () -> { } : listener;
    }

    boolean isFitToWindow() {
        return fitToWindow;
    }

    /** 平移。拖动时是"从按下的那一点算起"，这里是从当前位置再挪一点。 */
    void panBy(int dx, int dy) {
        setOffset(offsetX + dx, offsetY + dy);
    }

    int offsetXForTest() {
        return offsetX;
    }

    int offsetYForTest() {
        return offsetY;
    }

    private void setOffset(int x, int y) {
        offsetX = x;
        offsetY = y;
        clampOffset();
        repaint();
    }

    Path photo() {
        return photo;
    }

    boolean hasImage() {
        return image != null;
    }

    /** 当前显示比例（百分比）。表头和测试用。 */
    int zoomPercent() {
        if (image == null) {
            return 100;
        }
        return (int) Math.round(currentScale() * 100);
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private void onLoaded(BufferedImage loaded) {
        loading = false;
        failed = loaded == null;
        image = loaded;
        resetView();
        repaint();
    }

    private void resetView() {
        offsetX = 0;
        offsetY = 0;
        dragStart = null;
    }

    /** 适应窗口时该用多大比例。 */
    private double fitScale() {
        if (image == null) {
            return 1.0;
        }
        Rectangle area = imageArea();
        return Math.min((double) area.width / image.getWidth(),
                (double) area.height / image.getHeight());
    }

    private double currentScale() {
        return fitToWindow ? fitScale() : customScale;
    }

    /** 图片画出来之后占多大。 */
    private Dimension scaledSize() {
        double scale = currentScale();
        return new Dimension(Math.max(1, (int) Math.round(image.getWidth() * scale)),
                Math.max(1, (int) Math.round(image.getHeight() * scale)));
    }

    /**
     * 把偏移夹回"图片边缘最多和窗口边缘对齐"的范围。
     *
     * <p>某个方向上图片比窗口小的话，那个方向只能居中（偏移强制为 0）——
     * 不然能把照片拖出视野，只剩一片黑。
     */
    private void clampOffset() {
        if (image == null) {
            offsetX = 0;
            offsetY = 0;
            return;
        }
        Rectangle area = imageArea();
        Dimension size = scaledSize();
        int limitX = Math.max(0, (size.width - area.width) / 2);
        int limitY = Math.max(0, (size.height - area.height) / 2);
        offsetX = Math.max(-limitX, Math.min(limitX, offsetX));
        offsetY = Math.max(-limitY, Math.min(limitY, offsetY));
    }

    /** 现在能不能拖：图片在某个方向上比窗口大就行。 */
    private boolean canPan() {
        if (image == null) {
            return false;
        }
        Rectangle area = imageArea();
        Dimension size = scaledSize();
        return size.width > area.width || size.height > area.height;
    }

    private static boolean hit(Point point, Rectangle bounds) {
        return bounds != null && bounds.contains(point);
    }

    // ─────────────────────────── 绘制 ───────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // 放大到超过 1:1 时，双线性会把像素糊在一起；用最近邻才看得清真正的像素
        if (!fitToWindow && customScale > 1.0) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        paintHeader(g2);
        paintImageArea(g2);
        g2.dispose();
    }

    private void paintHeader(Graphics2D g) {
        g.setFont(Theme.FONT_SMALL);
        FontMetrics metrics = g.getFontMetrics();

        // 右边留给缩放控件
        paintZoomControls(g, metrics);
        int controlsWidth = (fitButtonBounds == null ? 0 : fitButtonBounds.width)
                + (actualButtonBounds == null ? 0 : actualButtonBounds.width) + 12;

        g.setColor(Theme.TEXT_MUTED);
        String text;
        if (photo == null) {
            text = "还没有选中照片";
        } else {
            String pixels = image == null ? "…" : image.getWidth() + " × " + image.getHeight() + " 像素";
            text = photo.getFileName() + "    " + pixels + "    " + photo.getParent();
        }
        g.drawString(shortenMiddle(text, metrics, getWidth() - PADDING * 2 - controlsWidth),
                PADDING, 17);

        g.setColor(Theme.BORDER);
        g.fillRect(PADDING, HEADER_HEIGHT - 7, Math.max(0, getWidth() - PADDING * 2), 1);
    }

    /** 表头右侧的缩放控件：适应窗口、1:1，以及当前百分比。 */
    private void paintZoomControls(Graphics2D g, FontMetrics metrics) {
        String percent = image == null ? "" : zoomPercent() + "%";
        int percentWidth = percent.isEmpty() ? 0 : metrics.stringWidth(percent) + 10;

        int actualWidth = metrics.stringWidth("100%") + 18;
        int fitWidth = metrics.stringWidth("适应") + 18;
        int height = 20;
        int y = (HEADER_HEIGHT - 8 - height) / 2 + 2;
        int x = getWidth() - PADDING - actualWidth;
        actualButtonBounds = new Rectangle(x, y, actualWidth, height);
        x -= fitWidth + 6;
        fitButtonBounds = new Rectangle(x, y, fitWidth, height);

        paintChip(g, fitButtonBounds, "适应", metrics, fitToWindow);
        paintChip(g, actualButtonBounds, "100%", metrics, !fitToWindow && customScale == 1.0);

        if (!percent.isEmpty()) {
            g.setColor(Theme.TEXT_MUTED);
            g.drawString(percent, fitButtonBounds.x - percentWidth, 17);
        }
    }

    /** 一个小圆角块：当前生效的那个用亮一点的底色。 */
    private void paintChip(Graphics2D g, Rectangle bounds, String label, FontMetrics metrics,
                           boolean active) {
        g.setColor(active ? Theme.HIGHLIGHT : Theme.PANEL);
        g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 7, 7);
        g.setColor(active ? Theme.TEXT : Theme.TEXT_MUTED);
        g.drawString(label, bounds.x + (bounds.width - metrics.stringWidth(label)) / 2,
                bounds.y + bounds.height / 2 + metrics.getAscent() / 2 - 1);
    }

    private void paintImageArea(Graphics2D g) {
        if (image != null) {
            paintImage(g);
            return;
        }
        String hint;
        if (photo == null) {
            hint = "在下方素材条里点一张照片，这里显示原图";
        } else if (loading) {
            hint = "正在读取原图…";
        } else if (failed) {
            hint = "这张读不出来：" + photo.getFileName();
        } else {
            hint = "";
        }
        if (hint.isEmpty()) {
            return;
        }
        g.setFont(Theme.FONT);
        g.setColor(Theme.TEXT_MUTED);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(hint, Math.max(PADDING, (getWidth() - metrics.stringWidth(hint)) / 2),
                HEADER_HEIGHT + Math.max(metrics.getAscent(),
                        (getHeight() - HEADER_HEIGHT - metrics.getHeight()) / 2));
    }

    private void paintImage(Graphics2D g) {
        Rectangle area = imageArea();
        Dimension size = scaledSize();
        clampOffset();

        int x = area.x + (area.width - size.width) / 2 + offsetX;
        int y = area.y + (area.height - size.height) / 2 + offsetY;
        // 必须把绘制范围限制在图片区之内：放大之后图片比这块地方大得多，
        // 不裁的话它会从表头上方一路盖过去，把文件名和缩放控件全糊掉
        Graphics2D clipped = (Graphics2D) g.create();
        clipped.clip(area);
        clipped.drawImage(image, x, y, size.width, size.height, null);
        clipped.dispose();
    }

    /** 留给图片的那块地方：表头以下、四周留白以内。 */
    private Rectangle imageArea() {
        return new Rectangle(PADDING, HEADER_HEIGHT + PADDING,
                Math.max(1, getWidth() - PADDING * 2),
                Math.max(1, getHeight() - HEADER_HEIGHT - PADDING * 2));
    }

    /**
     * 太长就从中间掐掉。
     *
     * <p>路径两头的信息量最大——开头是盘符、结尾是文件夹名，中间的层级最不重要。
     */
    static String shortenMiddle(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int keep = text.length();
        while (keep > 2 && metrics.stringWidth(
                text.substring(0, keep / 2) + ellipsis + text.substring(text.length() - keep / 2))
                > maxWidth) {
            keep--;
        }
        int half = Math.max(1, keep / 2);
        return text.substring(0, half) + ellipsis + text.substring(text.length() - half);
    }
}
