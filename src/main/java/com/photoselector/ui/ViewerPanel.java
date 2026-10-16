package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.swing.JPanel;

import com.photoselector.io.ImageSource;
import com.photoselector.ui.theme.Theme;

/**
 * 中间的大图视图：看素材照片的原尺寸内容。
 *
 * <p>这是挑片的主工具——缩略图只能告诉你"有这张"，只有大图能告诉你"这张行不行"。
 *
 * <p>几个决定：
 * <ul>
 *   <li><b>取的是原图</b>（{@link ImageSource#loadFull()}），不是缩略图。
 *       代价是慢——24MP 的 JPEG 要几百毫秒，所以走后台线程，先显示"正在读取"。</li>
 *   <li>默认<b>适应窗口</b>：等比缩放到刚好放得下，不裁切也不放大。
 *       缩放到 100% 与拖拽平移是后面的事。</li>
 *   <li>同一时刻只留一张原图。24MP 解出来是九十多兆，留着上一张纯属浪费内存。</li>
 * </ul>
 */
@SuppressWarnings("serial")
final class ViewerPanel extends JPanel {

    /** 表头（文件名 · 像素 · 路径）占的高度。 */
    private static final int HEADER_HEIGHT = 26;

    /** 图片四周的留白。 */
    private static final int PADDING = 12;

    private final ThumbnailLoader loader;

    private Path photo;
    private BufferedImage image;
    private boolean loading;
    private boolean failed;

    ViewerPanel(ThumbnailLoader loader) {
        this.loader = loader;
        setBackground(Theme.CANVAS);
        setOpaque(true);
    }

    /** 换一张要看的大图；传 null 表示什么都不看。 */
    void showPhoto(Path newPhoto) {
        this.photo = newPhoto;
        this.image = null;
        this.failed = false;
        this.loading = newPhoto != null;
        repaint();
        if (newPhoto != null) {
            loader.loadFull(newPhoto, this::onLoaded);
        }
    }

    Path photo() {
        return photo;
    }

    boolean hasImage() {
        return image != null;
    }

    boolean isLoading() {
        return loading;
    }

    /** 原图到了；读不出来时收到的是 null。 */
    private void onLoaded(BufferedImage loaded) {
        loading = false;
        failed = loaded == null;
        image = loaded;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        paintHeader(g2);
        paintImageArea(g2);
        g2.dispose();
    }

    private void paintHeader(Graphics2D g) {
        g.setFont(Theme.FONT_SMALL);
        g.setColor(Theme.TEXT_MUTED);

        String text;
        if (photo == null) {
            text = "还没有选中照片";
        } else {
            String pixels = image == null ? "…" : image.getWidth() + " × " + image.getHeight() + " 像素";
            text = photo.getFileName() + "    " + pixels + "    " + photo.getParent();
        }
        g.drawString(shortenMiddle(text, g.getFontMetrics(), getWidth() - PADDING * 2), PADDING, 17);

        g.setColor(Theme.BORDER);
        g.fillRect(PADDING, HEADER_HEIGHT - 7, Math.max(0, getWidth() - PADDING * 2), 1);
    }

    private void paintImageArea(Graphics2D g) {
        if (image != null) {
            paintFitted(g);
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

    /** 等比缩放到刚好放得下：不裁切，也不把小图放大。 */
    private void paintFitted(Graphics2D g) {
        Rectangle area = imageArea();
        double scale = Math.min((double) area.width / image.getWidth(),
                (double) area.height / image.getHeight());
        scale = Math.min(scale, 1.0); // 原图比窗口小就别放大，放大了只会糊

        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        g.drawImage(image, area.x + (area.width - width) / 2, area.y + (area.height - height) / 2,
                width, height, null);
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
