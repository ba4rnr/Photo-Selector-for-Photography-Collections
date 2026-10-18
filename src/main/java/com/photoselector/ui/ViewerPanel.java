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
import java.awt.geom.Area;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.photoselector.core.CropRect;
import com.photoselector.core.EditState;
import com.photoselector.core.PhotoTransforms;
import com.photoselector.ui.theme.ChipGroup;
import com.photoselector.ui.theme.TextStyles;
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
 * <p>原图缓存只保留“当前张 + 浏览顺序里的上一张和下一张”。方向键连续过片时，
 * 相邻照片已经解好，可以直接上屏；一旦跳到不相邻的照片，旧邻居会立即从缓存移除，
 * 尚未完成的预读取也会取消，避免好几张已解码的大图长期占着内存。
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
     * 滚轮走的档位表。
     *
     * <p>取的是 25 / 33 / 50 / 66 这类"能一眼看懂的比例"，再往上按 100、133、200、300……
     * 走到 800%。这样既有足够的密度（不会一步从 14% 跳到 100%），
     * 又保证整百的那些常用档——100%、200%、300%、400%——都恰好落在表上，
     * 滚到它们时不会有零头。
     */
    private static final double[] ZOOM_STEPS = {
        0.05, 0.08, 0.10, 0.14, 0.18, 0.20, 0.25, 0.33, 0.50, 0.66,
        1.00, 1.33, 2.00, 3.00, 4.00, 6.00, 8.00
    };

    /**
     * 「查看 → 默认放大倍率」里给用户挑的几个档（百分比）。
     *
     * <p>取值都在 {@link #ZOOM_STEPS} 上，这样"默认倍率"和滚轮档位是同一套刻度，
     * 不会出现菜单里选了一个滚轮永远滚不出来的数。
     */
    static int[] defaultZoomChoices() {
        return new int[] {14, 18, 25, 33, 50, 66, 100};
    }

    private final ThumbnailLoader loader;

    /**
     * 固定倍率模式下的默认比例（新照片默认是“适应窗口”，这是备用固定档）。
     *
     * <p>固定模式用同一个真实像素比例；若选“适应窗口”，则由查看区大小现算。
     *
     * <p>可在「查看 → 默认缩放」里切换“适应窗口”和固定档位；选择会记住。
     */
    private double defaultScale = 0.18;

    private Path photo;
    private BufferedImage image;

    /**
     * 原图（只按 EXIF 摆正过，没有套旋转 / 翻转）。
     *
     * <p>旋转和翻转是"虚拟编辑"：每次改动都从原图重新生成 {@link #image}，
     * 而不是在前一版结果上叠——叠来叠去既会糊，撤销时也找不回来。
     */
    private BufferedImage rawImage;

    /** 只套旋转 / 翻转、还没裁切的画面；裁切工具要在它上面画完整选区。 */
    private BufferedImage orientedImage;
    private boolean loading;
    private boolean failed;
    private boolean deferredLoad;

    /** 当前照片和前后邻居的原图；只按规范化路径保存，最多三张。 */
    private final Map<Path, BufferedImage> fullImageCache = new HashMap<>();

    /** 正在后台读取的邻居；换片时只取消已经不再属于新邻居集合的任务。 */
    private final Map<Path, ThumbnailLoader.LoadHandle> prefetchLoads = new HashMap<>();
    private Path previousPrefetch;
    private Path nextPrefetch;
    private ThumbnailLoader.LoadHandle currentLoad = ThumbnailLoader.LoadHandle.NONE;

    /**
     * 原图请求的代次。每换一次照片就加一，迟到的旧解码结果据此丢掉。
     *
     * <p>没有它的话，快速连按方向键时 A 的慢解码可能在 B 之后回来，把 B 的画面盖掉，
     * 表格里的文件名却还是 B。
     */
    private long loadVersion;

    /** 当前照片的虚拟编辑；由 {@link #setEdits} 从中间商那边灌进来。 */
    private EditState edits = EditState.NONE;

    /** 是不是"适应窗口"。新照片默认适应窗口；固定倍率是用户主动选择的另一种模式。 */
    private boolean fitToWindow = true;

    /**
     * "以后打开照片时默认怎么显示"这项偏好。
     *
     * <p>它和 {@link #fitToWindow} 不是同一件事：用户当前这张可以临时滚到 100%，
     * 但"默认缩放"仍然可以选着"适应窗口"。把两者混成一个字段，会让菜单里的
     * 默认缩放勾选跟着当前画面乱跳。
     */
    private boolean defaultFitToWindow = true;

    /** 非适应窗口时用的比例。 */
    private double customScale = defaultScale;

    /** 相对"居中"位置挪了多少。 */
    private int offsetX;
    private int offsetY;

    /** 拖动平移用：按下时的指针位置，以及当时的偏移。 */
    private Point dragStart;
    private int dragOffsetX;
    private int dragOffsetY;

    /** 从目标栏拖来的照片正悬在大图区上：这时整块面板要亮一圈，示意"松手就扔"。 */
    private boolean trashHint;

    /** 当前的光标工具。它决定在大图上按住左键拖动是什么意思。 */
    private CursorTool tool = CursorTool.SELECT;

    /** 裁切模式：显示未裁切的完整画面，用拖动框选新的裁切区域。 */
    private boolean cropMode;
    /** 没有已保存裁切时，编辑框默认覆盖整张画面，先让四边/四角手柄可见。 */
    private static final CropRect FULL_CROP = new CropRect(0, 0, 1, 1);
    private Point cropDragStart;
    private CropRect cropPreview;
    private CropRect cropOriginal;
    private CropGrab cropGrab;

    /** 裁切框当前抓住的是内部、某条边，还是某个角。 */
    private enum CropGrab {
        NEW, MOVE, NW, N, NE, E, SE, S, SW, W
    }

    /** 三分网格只是查看辅助，不进入编辑历史。 */
    private boolean gridVisible;

    /** 用户完成一次裁切框选后通知外部。 */
    private Consumer<CropRect> onCropCommitted = crop -> { };

    /**
     * 表头右侧「适应 / 100%」这一组胶囊。
     *
     * <p>位置、激活态和命中判断都由组件库统一管理，面板不再自己量两遍矩形。
     */
    private ChipGroup zoomControls;

    /** 指针当前悬停的缩放胶囊；-1 表示没悬停。 */
    private int hoveredZoomChip = -1;

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
                int chip = zoomChipAt(e.getPoint());
                if (chip == 0) {
                    fitToWindow();
                } else if (chip == 1) {
                    actualSize();
                } else if (cropMode && imageDrawRect().contains(e.getPoint())) {
                    Point pressed = clampToImage(e.getPoint());
                    cropDragStart = pressed;
                    cropOriginal = edits.crop();
                    cropGrab = cropGrabAt(pressed, cropOriginal);
                    if (cropOriginal == null && cropGrab != CropGrab.NEW) {
                        // 还没有裁切框时，边/角手柄对应的是整张画面。
                        cropOriginal = FULL_CROP;
                    }
                    setCropCursor(cropGrab);
                    cropPreview = cropGrab == CropGrab.NEW ? null : cropOriginal;
                    repaint();
                } else if (canPan()) {
                    dragStart = e.getPoint();
                    dragOffsetX = offsetX;
                    dragOffsetY = offsetY;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (cropDragStart != null) {
                    // 整图选框不能被“移动”，但用户从画面内部直接拖出一块新区域
                    // 仍然是旧操作；第一次真正拖动后把它转成 NEW。
                    if (cropGrab == CropGrab.MOVE && FULL_CROP.equals(cropOriginal)
                            && e.getPoint().distance(cropDragStart) > 5) {
                        cropGrab = CropGrab.NEW;
                    }
                    cropPreview = cropGrab == CropGrab.NEW
                            ? normalizedCrop(cropDragStart, e.getPoint())
                            : adjustedCrop(cropDragStart, e.getPoint());
                    repaint();
                    return;
                }
                if (dragStart == null) {
                    return;
                }
                setOffset(dragOffsetX + (e.getX() - dragStart.x),
                        dragOffsetY + (e.getY() - dragStart.y));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (cropDragStart != null) {
                    CropRect selected = cropGrab == CropGrab.NEW
                            ? normalizedCrop(cropDragStart, e.getPoint())
                            : adjustedCrop(cropDragStart, e.getPoint());
                    CropRect original = cropOriginal;
                    cropDragStart = null;
                    cropPreview = null;
                    cropOriginal = null;
                    cropGrab = null;
                    if (selected != null && !selected.equals(original)) {
                        onCropCommitted.accept(selected);
                    }
                    repaint();
                    updateCropCursor(e.getPoint());
                    return;
                }
                dragStart = null;
                setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setHoveredZoomChip(zoomChipAt(e.getPoint()));
                updateCropCursor(e.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHoveredZoomChip(-1);
                if (cropMode) {
                    setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoomStep(-e.getWheelRotation() > 0 ? 1 : -1, e.getPoint());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    // ─────────────────────────── 对外接口 ───────────────────────────

    /** 换一张要看的大图；不提供邻居时不会预读取。传 null 表示什么都不看。 */
    void showPhoto(Path newPhoto) {
        showPhoto(newPhoto, null, null);
    }

    /**
     * 换一张大图，同时告诉视图它在浏览顺序里的前后邻居。
     *
     * <p>缓存和预读取都以这三张为界：当前张、上一张、下一张。新照片如果正好在旧邻居里，
     * 可以直接复用已经解好的原图；如果是一次跨得很远的点选，旧缓存会在方法返回前清掉。
     *
     * @param newPhoto 当前照片
     * @param previous 浏览顺序里的上一张，没有就传 null
     * @param next     浏览顺序里的下一张，没有就传 null
     */
    void showPhoto(Path newPhoto, Path previous, Path next) {
        long request = ++loadVersion;
        cancelCurrentLoad();
        this.photo = normalize(newPhoto);
        this.deferredLoad = false;
        this.image = null;
        this.orientedImage = null;
        this.rawImage = null;
        this.edits = EditState.NONE;
        this.cropDragStart = null;
        this.cropPreview = null;
        this.cropOriginal = null;
        this.cropGrab = null;
        this.failed = false;
        this.loading = this.photo != null;

        previousPrefetch = neighbor(previous, this.photo);
        nextPrefetch = neighbor(next, this.photo);
        retainOnlyCurrentAndNeighbors();
        resetView(); // 换片把位置收回中间；缩放比例保留，方便连着看一堆细节
        repaint();

        if (this.photo == null) {
            return;
        }

        BufferedImage cached = fullImageCache.get(this.photo);
        if (cached != null) {
            loading = false;
            rawImage = cached;
            rebuildImageFromEdits();
            prefetch(previousPrefetch);
            prefetch(nextPrefetch);
        } else {
            Path requested = this.photo;
            currentLoad = loader.loadFull(requested,
                    loaded -> onLoaded(requested, request, loaded));
        }
    }

    /**
     * 只记下“当前照片是谁”，暂不解码。
     *
     * <p>整理模式的大图区不可见，那里点缩略图只需要选中状态和编辑对象；
     * 等真正切回浏览模式再调用 {@link #showPhoto(Path)} 解码原图。
     */
    void selectPhotoDeferred(Path newPhoto) {
        ++loadVersion;
        cancelCurrentLoad();
        clearFullImageCache();
        previousPrefetch = null;
        nextPrefetch = null;
        this.photo = normalize(newPhoto);
        this.image = null;
        this.orientedImage = null;
        this.rawImage = null;
        this.failed = false;
        this.loading = newPhoto != null;
        this.deferredLoad = newPhoto != null;
        this.edits = EditState.NONE;
        resetView();
        repaint();
    }

    /** 当前照片是否只是被选中、还没有真正读取原图。 */
    boolean isLoadDeferred() {
        return deferredLoad;
    }

    /**
     * 套用一组虚拟编辑（旋转 / 翻转）后重新显示；传 null 表示回到原样。
     *
     * <p>只重算画面，不重读文件——旋转和翻转都是在已经解出来的原图上做的。
     * 换片时 {@link #showPhoto} 会把编辑清成"没动过"，等外面再把新照片的编辑灌进来。
     */
    void setEdits(EditState newEdits) {
        this.edits = newEdits == null ? EditState.NONE : newEdits;
        rebuildImageFromEdits();
        resetView(); // 宽高可能换了，位置收回中间
        repaint();
    }

    /** 当前生效的虚拟编辑。给测试和排查用。 */
    EditState edits() {
        return edits;
    }

    /** 用原图 + 当前的编辑重算显示图；没原图就什么都不做。 */
    private void rebuildImageFromEdits() {
        if (rawImage == null) {
            image = null;
            orientedImage = null;
            return;
        }
        orientedImage = PhotoTransforms.applyOrientation(rawImage, edits);
        image = PhotoTransforms.applyCrop(orientedImage, edits.crop());
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
     * 改"打开一张照片时的默认倍率"，并立刻按新倍率显示当前这张。
     *
     * <p>立刻生效是有意的：这个选项在菜单里，改完就能看见画面变大变小，
     * 不用翻到下一张才知道自己选对了没有。
     */
    void setDefaultScale(double scale) {
        this.defaultScale = Math.max(0.01, Math.min(MAX_SCALE, scale));
        this.defaultFitToWindow = false;
        fitToWindow = false;
        customScale = this.defaultScale;
        resetView();
        repaint();
        onZoomChanged.run();
    }

    /**
     * 应用启动时保存的默认缩放方式。
     *
     * <p>和 {@link #setDefaultScale} 的区别：这里只配置“以后怎么打开”，
     * 不会因为启动时读了一次偏好文件就把默认 Fit 模式关掉。
     */
    void applyStartupZoom(boolean fit, double scale) {
        this.defaultScale = Math.max(0.01, Math.min(MAX_SCALE, scale));
        this.customScale = this.defaultScale;
        this.fitToWindow = fit;
        this.defaultFitToWindow = fit;
        resetView();
        repaint();
        onZoomChanged.run();
    }

    /** 把"以后默认适应窗口"写进偏好，并让当前这张立刻切过去。 */
    void setDefaultFitToWindow() {
        this.defaultFitToWindow = true;
        fitToWindow();
    }

    /** 「查看 → 默认缩放」里该勾哪一项；不受当前这张临时缩放的影响。 */
    boolean isDefaultFitToWindow() {
        return defaultFitToWindow;
    }

    /** 当前设定的默认倍率（0.18 = 18%）。 */
    double defaultScale() {
        return defaultScale;
    }

    /** 滚轮缩放：在档位表上走一格。基准点取视图中心（不跟随鼠标）。 */
    void zoomStep(int direction) {
        zoomStep(direction, new Point(getWidth() / 2, getHeight() / 2));
    }

    /**
     * 滚轮缩放：在档位表上走一格，并且<b>以鼠标所指的那一点为基准</b>。
     *
     * <p>以鼠标为基准的意思是：缩放前后，光标下面始终是同一个图片像素。
     * 放大看细节时这一点很要紧——不然每滚一格画面都往中心跑，得反复拖回来。
     *
     * <p>从"适应窗口"算出来的零碎比例（比如 63%）起步时，先按方向落到相邻档：
     * 往上取比它大的那一档，往下取比它小的那一档——方向感比"绝对最近"更重要。
     *
     * @param direction 正数放大，负数缩小
     * @param anchor    以屏幕上的哪一点为基准
     */
    void zoomStep(int direction, Point anchor) {
        if (displayImage() == null) {
            return;
        }
        double current = fitToWindow ? fitScale() : customScale;

        int below = stepIndexBelow(current);
        boolean exact = Math.abs(ZOOM_STEPS[below] - current) < 1e-6;
        int next = direction > 0 ? below + 1 : (exact ? below - 1 : below);
        next = Math.max(0, Math.min(ZOOM_STEPS.length - 1, next));

        zoomAround(ZOOM_STEPS[next], anchor);
        onZoomChanged.run();
    }

    /** 档位表里"不大于这个比例"的最后一档。 */
    private static int stepIndexBelow(double scale) {
        int index = 0;
        for (int i = 0; i < ZOOM_STEPS.length; i++) {
            if (ZOOM_STEPS[i] <= scale + 1e-6) {
                index = i;
            }
        }
        return index;
    }

    /**
     * 缩放到指定比例，并让 {@code anchor} 下面那个图片像素留在原地。
     *
     * <p>算法就是解一个一次方程：设光标下的图片坐标是 (u, v)，
     * 那么缩放后想让 {@code 图片左上角 + u × 新比例 == 光标} 仍然成立，
     * 反推出新的偏移量即可。
     */
    private void zoomAround(double newScale, Point anchor) {
        Rectangle area = imageArea();
        double oldScale = currentScale();
        Dimension before = scaledSize();
        int x0 = area.x + (area.width - before.width) / 2 + offsetX;
        int y0 = area.y + (area.height - before.height) / 2 + offsetY;
        double imageX = (anchor.x - x0) / oldScale;
        double imageY = (anchor.y - y0) / oldScale;

        fitToWindow = false;
        customScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));

        Dimension after = scaledSize();
        offsetX = (int) Math.round(anchor.x - imageX * customScale
                - area.x - (area.width - after.width) / 2.0);
        offsetY = (int) Math.round(anchor.y - imageY * customScale
                - area.y - (area.height - after.height) / 2.0);
        clampOffset();
        repaint();
    }

    /**
     * 屏幕上某一点对应的图片像素坐标。
     *
     * <p>给"以鼠标为基准缩放"用的，测试也拿它验证"光标下始终是同一个像素"。
     */
    Point imagePointAt(Point screenPoint) {
        if (displayImage() == null) {
            return null;
        }
        Rectangle area = imageArea();
        Dimension size = scaledSize();
        double scale = currentScale();
        int x0 = area.x + (area.width - size.width) / 2 + offsetX;
        int y0 = area.y + (area.height - size.height) / 2 + offsetY;
        return new Point((int) Math.round((screenPoint.x - x0) / scale),
                (int) Math.round((screenPoint.y - y0) / scale));
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

    /** 屏幕上那张图现在的像素尺寸；还没有图就是 null。给测试用。 */
    java.awt.Dimension imageSizeForTest() {
        return image == null ? null : new java.awt.Dimension(image.getWidth(), image.getHeight());
    }

    boolean hasImage() {
        return image != null;
    }

    /** 丢掉当前图和邻居的原图缓存，下一次显示会重新读磁盘。保存或外部改动后使用。 */
    void clearFullImageCache() {
        for (ThumbnailLoader.LoadHandle handle : prefetchLoads.values()) {
            handle.cancel();
        }
        prefetchLoads.clear();
        fullImageCache.clear();
    }

    /** 当前缓存了几张原图；正常情况下最多是当前张 + 前后两张。 */
    int cachedImageCountForTest() {
        return fullImageCache.size();
    }

    boolean isCachedForTest(Path candidate) {
        return fullImageCache.containsKey(normalize(candidate));
    }

    /** 当前显示比例（百分比）。表头和测试用。 */
    int zoomPercent() {
        if (displayImage() == null) {
            return 100;
        }
        return (int) Math.round(currentScale() * 100);
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 接收一次原图解码结果；只有“当前照片 + 最新代次”的这一份才允许上屏。
     *
     * <p>包级可见是为了测试能直接构造一个迟到的旧结果，不必依赖真实磁盘速度。
     */
    void onLoaded(Path requested, long request, BufferedImage loaded) {
        if (request != loadVersion || !Objects.equals(requested, photo)) {
            return;
        }
        currentLoad = ThumbnailLoader.LoadHandle.NONE;
        loading = false;
        deferredLoad = false;
        failed = loaded == null;
        rawImage = loaded;
        if (loaded != null) {
            fullImageCache.put(normalize(requested), loaded);
        }
        rebuildImageFromEdits();
        // 当前这张先上屏，再去读前后两张；别让预读取抢在用户真正要看的那张前面。
        prefetch(previousPrefetch);
        prefetch(nextPrefetch);
        resetView();
        repaint();
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    /** 邻居不能就是当前照片本身；其余路径统一规范化，免得同一文件出现两份缓存。 */
    private static Path neighbor(Path candidate, Path current) {
        Path normalized = normalize(candidate);
        return normalized == null || normalized.equals(current) ? null : normalized;
    }

    /**
     * 只留下当前照片与它现在的前后邻居。
     *
     * <p>这一步发生在换片当下，而不是等后台解码完：跳到远处照片时，
     * 旧图占的内存会立刻释放，旧预取任务也会马上取消。
     */
    private void retainOnlyCurrentAndNeighbors() {
        Set<Path> keep = new HashSet<>();
        if (photo != null) {
            keep.add(photo);
        }
        if (previousPrefetch != null) {
            keep.add(previousPrefetch);
        }
        if (nextPrefetch != null) {
            keep.add(nextPrefetch);
        }

        fullImageCache.keySet().removeIf(path -> !keep.contains(path));
        Iterator<Map.Entry<Path, ThumbnailLoader.LoadHandle>> loads =
                prefetchLoads.entrySet().iterator();
        while (loads.hasNext()) {
            Map.Entry<Path, ThumbnailLoader.LoadHandle> entry = loads.next();
            if (!keep.contains(entry.getKey())) {
                entry.getValue().cancel();
                loads.remove();
            }
        }
    }

    /** 后台读一张邻居原图；已有缓存或已经在路上就不重复请求。 */
    private void prefetch(Path candidate) {
        if (candidate == null || candidate.equals(photo)
                || fullImageCache.containsKey(candidate)
                || prefetchLoads.containsKey(candidate)) {
            return;
        }
        ThumbnailLoader.LoadHandle handle = loader.loadFull(candidate,
                loaded -> onPrefetched(candidate, loaded));
        prefetchLoads.put(candidate, handle);
    }

    /** 邻居读完后只进内存，不换屏；它必须仍属于当前照片的前后两张。 */
    private void onPrefetched(Path requested, BufferedImage loaded) {
        prefetchLoads.remove(requested);
        if (loaded == null || photo == null
                || !(requested.equals(previousPrefetch) || requested.equals(nextPrefetch))) {
            return;
        }
        fullImageCache.put(requested, loaded);
    }

    private void cancelCurrentLoad() {
        currentLoad.cancel();
        currentLoad = ThumbnailLoader.LoadHandle.NONE;
    }

    /** 当前原图请求的代次；给回归测试构造“迟到的旧结果”用。 */
    long loadVersionForTest() {
        return loadVersion;
    }

    private void resetView() {
        offsetX = 0;
        offsetY = 0;
        dragStart = null;
    }

    /** 适应窗口时该用多大比例。 */
    private double fitScale() {
        BufferedImage display = displayImage();
        if (display == null) {
            return 1.0;
        }
        Rectangle area = imageArea();
        return Math.min((double) area.width / display.getWidth(),
                (double) area.height / display.getHeight());
    }

    private double currentScale() {
        return fitToWindow ? fitScale() : customScale;
    }

    /** 图片画出来之后占多大。 */
    private Dimension scaledSize() {
        BufferedImage display = displayImage();
        if (display == null) {
            return new Dimension(1, 1);
        }
        double scale = currentScale();
        return new Dimension(Math.max(1, (int) Math.round(display.getWidth() * scale)),
                Math.max(1, (int) Math.round(display.getHeight() * scale)));
    }

    /**
     * 把偏移夹回"图片边缘最多和窗口边缘对齐"的范围。
     *
     * <p>某个方向上图片比窗口小的话，那个方向只能居中（偏移强制为 0）——
     * 不然能把照片拖出视野，只剩一片黑。
     */
    private void clampOffset() {
        if (displayImage() == null) {
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
    boolean canPan() {
        if (displayImage() == null) {
            return false;
        }
        if (tool != CursorTool.PAN) {
            return false; // 只有"平移"工具才搬画面，这正是它存在的理由
        }
        Rectangle area = imageArea();
        Dimension size = scaledSize();
        return size.width > area.width || size.height > area.height;
    }

    /**
     * 换当前工具。
     *
     * <p>工具只改变拖动行为，不再改变鼠标形状：鼠标始终使用系统默认箭头，
     * 只有真正按住平移时临时显示系统的移动光标。
     */
    void setCursorTool(CursorTool cursorTool) {
        this.tool = cursorTool;
        boolean nextCropMode = cursorTool == CursorTool.CROP;
        if (cropMode != nextCropMode) {
            cropMode = nextCropMode;
            cropDragStart = null;
            cropPreview = null;
            cropOriginal = null;
            cropGrab = null;
            // 裁切要尽量看到完整画面，进入时自动适应窗口。
            fitToWindow();
        }
        setCursor(Cursor.getDefaultCursor());
    }

    /** 裁切完成时的回调；MainPanel 负责把它记进中间商。 */
    void setOnCropCommitted(Consumer<CropRect> listener) {
        this.onCropCommitted = listener == null ? crop -> { } : listener;
    }

    /** 三分网格开关。 */
    void setGridVisible(boolean visible) {
        if (gridVisible != visible) {
            gridVisible = visible;
            repaint();
        }
    }

    boolean isGridVisible() {
        return gridVisible;
    }

    /** 现在拖动是不是"把照片拖出去"。 */
    boolean isDragOutEnabled() {
        return tool == CursorTool.SELECT;
    }

    /**
     * 拖出去时跟着鼠标走的那张小图；现缩一张，不去动缩略图缓存。
     *
     * <p>参数是正在拖的那张照片——大图面板一次只显示一张，所以它和
     * "当前照片"总是同一个，参数只是为了让调用处的写法统一。
     */
    BufferedImage ghostThumbnail(Path dragging) {
        if (image == null) {
            return null;
        }
        int width = 140;
        int height = Math.max(1, Math.round(image.getHeight() * (float) width / image.getWidth()));
        BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return small;
    }

    /** 指针命中了哪个缩放胶囊；没画过或点在空隙里都返回 -1。 */
    private int zoomChipAt(Point point) {
        return zoomControls == null ? -1 : zoomControls.indexAt(point);
    }

    private void setHoveredZoomChip(int index) {
        if (hoveredZoomChip != index) {
            hoveredZoomChip = index;
            repaint();
        }
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
        if (trashHint) {
            paintTrashHint(g2);
        }
        g2.dispose();
    }

    /** 从目标栏拖过来、正悬在大图区上：亮一圈白边，示意"松手就丢进垃圾桶"。 */
    private void paintTrashHint(Graphics2D g) {
        g.setColor(Theme.DIVIDER_HINT);
        g.setStroke(new java.awt.BasicStroke(3));
        g.drawRect(2, 2, Math.max(0, getWidth() - 5), Math.max(0, getHeight() - 5));
    }

    /** 拖过来的照片悬在大图区上时亮边框；松手或移开就灭掉。 */
    void setTrashHint(boolean on) {
        if (trashHint != on) {
            trashHint = on;
            repaint();
        }
    }

    /** 眼下是不是正亮着"垃圾桶"框。给测试用。 */
    boolean isTrashHintForTest() {
        return trashHint;
    }

    private void paintHeader(Graphics2D g) {
        g.setFont(Theme.FONT_SMALL);
        FontMetrics metrics = g.getFontMetrics();

        // 右边留给缩放控件
        paintZoomControls(g, metrics);
        int controlsWidth = zoomControls == null ? 0 : zoomControls.width() + 12;

        g.setColor(Theme.TEXT_MUTED);
        String text;
        if (photo == null) {
            text = "还没有选中照片";
        } else {
            BufferedImage display = displayImage();
            String pixels = display == null ? "…"
                    : display.getWidth() + " × " + display.getHeight() + " 像素";
            text = photo.getFileName() + "    " + pixels + "    " + photo.getParent();
        }
        g.drawString(shortenMiddle(text, metrics, getWidth() - PADDING * 2 - controlsWidth),
                PADDING, 17);

        g.setColor(Theme.BORDER);
        g.fillRect(PADDING, HEADER_HEIGHT - 7, Math.max(0, getWidth() - PADDING * 2), 1);
    }

    /** 表头右侧的缩放控件：适应窗口、1:1，以及当前百分比。 */
    private void paintZoomControls(Graphics2D g, FontMetrics metrics) {
        String percent = displayImage() == null ? "" : zoomPercent() + "%";
        int percentWidth = percent.isEmpty() ? 0 : metrics.stringWidth(percent) + 10;

        int y = (HEADER_HEIGHT - 8 - ChipGroup.HEIGHT) / 2 + 2;
        zoomControls = ChipGroup.rightAligned(metrics, getWidth() - PADDING, y, "适应", "100%");
        int active = fitToWindow ? 0 : customScale == 1.0 ? 1 : -1;
        zoomControls.paint(g, metrics, active, hoveredZoomChip);

        if (!percent.isEmpty()) {
            g.setColor(Theme.TEXT_MUTED);
            g.drawString(percent, zoomControls.left() - percentWidth, 17);
        }
    }

    /** 第 index 个缩放胶囊的位置；给离屏渲染测试核对命中区域用。 */
    Rectangle zoomChipBoundsForTest(int index) {
        return zoomControls == null ? null : zoomControls.boundsAt(index);
    }

    private void paintImageArea(Graphics2D g) {
        if (displayImage() != null) {
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
        BufferedImage display = displayImage();
        clipped.drawImage(display, x, y, size.width, size.height, null);
        if (gridVisible) {
            paintThirdsGrid(clipped, imageDrawRect());
        }
        if (cropMode) {
            paintCropOverlay(clipped, imageDrawRect());
        }
        clipped.dispose();
    }

    /** 当前应该上屏的图：裁切模式下用未裁切的完整画面，其余时候用最终编辑结果。 */
    private BufferedImage displayImage() {
        return cropMode ? orientedImage : image;
    }

    /** 图片在屏幕上的实际绘制矩形（不含四周留白）。 */
    private Rectangle imageDrawRect() {
        BufferedImage display = displayImage();
        if (display == null) {
            return new Rectangle(0, 0, 0, 0);
        }
        Rectangle area = imageArea();
        Dimension size = scaledSize();
        return new Rectangle(area.x + (area.width - size.width) / 2 + offsetX,
                area.y + (area.height - size.height) / 2 + offsetY,
                size.width, size.height);
    }

    private Point clampToImage(Point point) {
        Rectangle bounds = imageDrawRect();
        return new Point(
                Math.max(bounds.x, Math.min(bounds.x + bounds.width - 1, point.x)),
                Math.max(bounds.y, Math.min(bounds.y + bounds.height - 1, point.y)));
    }

    /** 屏幕上的两个角点换成相对完整画面的裁切矩形；太小的拖动视为误触。 */
    private CropRect normalizedCrop(Point start, Point end) {
        Rectangle image = imageDrawRect();
        if (image.width <= 0 || image.height <= 0) {
            return null;
        }
        Point a = clampToImage(start);
        Point b = clampToImage(end);
        int left = Math.min(a.x, b.x);
        int right = Math.max(a.x, b.x);
        int top = Math.min(a.y, b.y);
        int bottom = Math.max(a.y, b.y);
        if (right - left < 6 || bottom - top < 6) {
            return null;
        }
        return new CropRect(
                (left - image.x) / (double) image.width,
                (top - image.y) / (double) image.height,
                (right - left) / (double) image.width,
                (bottom - top) / (double) image.height);
    }

    /** 判断按下的位置是在移动已有选框、抓边角缩放，还是重新画一个。 */
    private CropGrab cropGrabAt(Point point, CropRect original) {
        CropRect interaction = original == null ? FULL_CROP : original;
        Rectangle r = cropScreenRect(interaction);
        int tolerance = 8;
        boolean left = Math.abs(point.x - r.x) <= tolerance;
        boolean right = Math.abs(point.x - (r.x + r.width)) <= tolerance;
        boolean top = Math.abs(point.y - r.y) <= tolerance;
        boolean bottom = Math.abs(point.y - (r.y + r.height)) <= tolerance;
        boolean acrossX = point.x >= r.x - tolerance && point.x <= r.x + r.width + tolerance;
        boolean acrossY = point.y >= r.y - tolerance && point.y <= r.y + r.height + tolerance;

        if (left && top) return CropGrab.NW;
        if (right && top) return CropGrab.NE;
        if (left && bottom) return CropGrab.SW;
        if (right && bottom) return CropGrab.SE;
        if (top && acrossX) return CropGrab.N;
        if (bottom && acrossX) return CropGrab.S;
        if (left && acrossY) return CropGrab.W;
        if (right && acrossY) return CropGrab.E;
        return r.contains(point) ? CropGrab.MOVE : CropGrab.NEW;
    }

    /** 裁切模式下按指针所在的手柄/选框区域切换系统双箭头光标。 */
    private void updateCropCursor(Point point) {
        if (!cropMode || !imageDrawRect().contains(point)) {
            if (cropMode) {
                setCursor(Cursor.getDefaultCursor());
            }
            return;
        }
        setCropCursor(cropGrabAt(point, edits.crop()));
    }

    private void setCropCursor(CropGrab grab) {
        int type = switch (grab) {
            case NW -> Cursor.NW_RESIZE_CURSOR;
            case N -> Cursor.N_RESIZE_CURSOR;
            case NE -> Cursor.NE_RESIZE_CURSOR;
            case E -> Cursor.E_RESIZE_CURSOR;
            case SE -> Cursor.SE_RESIZE_CURSOR;
            case S -> Cursor.S_RESIZE_CURSOR;
            case SW -> Cursor.SW_RESIZE_CURSOR;
            case W -> Cursor.W_RESIZE_CURSOR;
            case MOVE -> Cursor.MOVE_CURSOR;
            case NEW -> Cursor.CROSSHAIR_CURSOR;
        };
        setCursor(Cursor.getPredefinedCursor(type));
    }

    /** 移动 / 缩放已有选框，并保证四个边始终留在图片里。 */
    private CropRect adjustedCrop(Point start, Point end) {
        Rectangle image = imageDrawRect();
        if (cropOriginal == null || image.width <= 0 || image.height <= 0) {
            return null;
        }
        Point a = clampToImage(start);
        Point b = clampToImage(end);
        double dx = (b.x - a.x) / (double) image.width;
        double dy = (b.y - a.y) / (double) image.height;
        double left = cropOriginal.x();
        double top = cropOriginal.y();
        double right = left + cropOriginal.width();
        double bottom = top + cropOriginal.height();
        double minWidth = Math.min(0.5, 6.0 / image.width);
        double minHeight = Math.min(0.5, 6.0 / image.height);

        switch (cropGrab) {
            case MOVE -> {
                left = clamp(left + dx, 0, 1 - cropOriginal.width());
                top = clamp(top + dy, 0, 1 - cropOriginal.height());
                right = left + cropOriginal.width();
                bottom = top + cropOriginal.height();
            }
            case NW -> {
                left = clamp(left + dx, 0, right - minWidth);
                top = clamp(top + dy, 0, bottom - minHeight);
            }
            case N -> top = clamp(top + dy, 0, bottom - minHeight);
            case NE -> {
                right = clamp(right + dx, left + minWidth, 1);
                top = clamp(top + dy, 0, bottom - minHeight);
            }
            case E -> right = clamp(right + dx, left + minWidth, 1);
            case SE -> {
                right = clamp(right + dx, left + minWidth, 1);
                bottom = clamp(bottom + dy, top + minHeight, 1);
            }
            case S -> bottom = clamp(bottom + dy, top + minHeight, 1);
            case SW -> {
                left = clamp(left + dx, 0, right - minWidth);
                bottom = clamp(bottom + dy, top + minHeight, 1);
            }
            case W -> left = clamp(left + dx, 0, right - minWidth);
            default -> {
                return cropOriginal;
            }
        }
        return new CropRect(left, top, right - left, bottom - top);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Rectangle cropScreenRect(CropRect crop) {
        Rectangle image = imageDrawRect();
        int left = image.x + (int) Math.round(crop.x() * image.width);
        int top = image.y + (int) Math.round(crop.y() * image.height);
        int right = image.x + (int) Math.round((crop.x() + crop.width()) * image.width);
        int bottom = image.y + (int) Math.round((crop.y() + crop.height()) * image.height);
        return new Rectangle(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    private void paintThirdsGrid(Graphics2D g, Rectangle image) {
        if (image.width <= 2 || image.height <= 2) {
            return;
        }
        g.setColor(Theme.GRID_LINE);
        int x1 = image.x + image.width / 3;
        int x2 = image.x + image.width * 2 / 3;
        int y1 = image.y + image.height / 3;
        int y2 = image.y + image.height * 2 / 3;
        g.fillRect(x1, image.y, 1, image.height);
        g.fillRect(x2, image.y, 1, image.height);
        g.fillRect(image.x, y1, image.width, 1);
        g.fillRect(image.x, y2, image.width, 1);
    }

    private void paintCropOverlay(Graphics2D g, Rectangle image) {
        CropRect selection = cropPreview != null
                ? cropPreview
                : edits.crop() != null ? edits.crop() : FULL_CROP;
        if (image.width <= 0 || image.height <= 0) {
            return;
        }
        Rectangle selected = cropScreenRect(selection);
        Area mask = new Area(image);
        mask.subtract(new Area(selected));
        g.setColor(Theme.CROP_MASK);
        g.fill(mask);

        // 不再画白色矩形边框；选框边界只由明暗差表达，避免和手柄重复。
        paintCropHandles(g, selected);
    }

    /** 四角和四边中点的白色裁切条：告诉用户这里可以抓住缩放。 */
    private static void paintCropHandles(Graphics2D g, Rectangle crop) {
        int thickness = 4;
        int length = Math.min(18, Math.max(6,
                Math.min(crop.width, crop.height) / 4));
        int midX = crop.x + crop.width / 2;
        int midY = crop.y + crop.height / 2;
        int right = crop.x + crop.width - 1;
        int bottom = crop.y + crop.height - 1;
        int half = length / 2;
        int halfThickness = thickness / 2;
        Area handles = new Area();

        // 四个角是 L 形：横向条和纵向条只向画面内侧延伸，交叉点正好压住
        // 明暗边界。不能像十字那样四向都伸，否则角上会多出画面外的半截。
        addCropHandle(handles, crop.x - halfThickness, crop.y - halfThickness,
                length, thickness);                              // 左上横
        addCropHandle(handles, crop.x - halfThickness, crop.y - halfThickness,
                thickness, length);                              // 左上竖
        addCropHandle(handles, right - length + halfThickness, crop.y - halfThickness,
                length, thickness);                              // 右上横
        addCropHandle(handles, right - halfThickness, crop.y - halfThickness,
                thickness, length);                              // 右上竖
        addCropHandle(handles, crop.x - halfThickness, bottom - halfThickness,
                length, thickness);                              // 左下横
        addCropHandle(handles, crop.x - halfThickness, bottom - length + halfThickness,
                thickness, length);                              // 左下竖
        addCropHandle(handles, right - length + halfThickness, bottom - halfThickness,
                length, thickness);                              // 右下横
        addCropHandle(handles, right - halfThickness, bottom - length + halfThickness,
                thickness, length);                              // 右下竖

        addCropHandle(handles, midX - half, crop.y - halfThickness, length, thickness);
        addCropHandle(handles, midX - half, bottom - halfThickness, length, thickness);
        addCropHandle(handles, crop.x - halfThickness, midY - half, thickness, length);
        addCropHandle(handles, right - halfThickness, midY - half, thickness, length);
        // 只填一次合并后的形状：两条半透明条若分别绘制，L 形转角会因为
        // Alpha 混合两次而比周围更亮/更不透明。
        g.setColor(Theme.CROP_HANDLE);
        g.fill(handles);
    }

    private static void addCropHandle(Area handles, int x, int y, int width, int height) {
        handles.add(new Area(new Rectangle(x, y, width, height)));
    }

    /** 图片绘制矩形；给裁切 / 网格测试用。 */
    Rectangle imageDrawRectForTest() {
        return imageDrawRect();
    }

    /** 眼下是不是裁切模式。 */
    boolean isCropMode() {
        return cropMode;
    }

    /** 兼容测试里的旧名字。 */
    boolean isCropModeForTest() {
        return isCropMode();
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
        return TextStyles.middle(text, metrics, maxWidth);
    }
}
