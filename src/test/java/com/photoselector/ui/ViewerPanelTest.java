package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.PhotoGroup;
import com.photoselector.core.CropRect;
import com.photoselector.core.EditState;
import com.photoselector.io.ThumbnailService;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

class ViewerPanelTest {

    @TempDir
    Path tempDir;

    @Test
    void 预读取前后邻居_跳到远处时丢掉不再相邻的缓存() throws Exception {
        Theme.install();
        Path folder = Files.createDirectories(tempDir.resolve("素材"));
        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            photos.add(TestImages.writeJpeg(folder.resolve("p" + i + ".jpg"), 400, 300));
        }

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(600, 450);
            viewer.showPhoto(photos.get(2), photos.get(1), photos.get(3));
        });

        waitUntil(() -> viewer.hasImage() && viewer.cachedImageCountForTest() == 3,
                "当前照片和前后邻居预读取完成");
        assertTrue(viewer.isCachedForTest(photos.get(1)));
        assertTrue(viewer.isCachedForTest(photos.get(2)));
        assertTrue(viewer.isCachedForTest(photos.get(3)));

        // 向右一张：新窗口是 C/D/E，原来的 B 立刻出局，C 仍可复用。
        SwingUtilities.invokeAndWait(() ->
                viewer.showPhoto(photos.get(3), photos.get(2), photos.get(4)));
        assertFalse(viewer.isCachedForTest(photos.get(1)),
                "滑到下一张后，旧上一张已经不在新的三张窗口里");
        assertTrue(viewer.isCachedForTest(photos.get(2)), "新的上一张应当仍复用缓存");
        assertTrue(viewer.hasImage(), "相邻张命中预读取时应当直接上屏，不用再等解码");

        // 直接点到很远的 G：新邻居只有 F，旧的 C/D/E 必须全部销毁。
        SwingUtilities.invokeAndWait(() ->
                viewer.showPhoto(photos.get(6), photos.get(5), null));
        assertFalse(viewer.hasImage(), "没预读过的远照片应当先清掉旧画面，等新图回来");
        assertFalse(viewer.isCachedForTest(photos.get(2)));
        assertFalse(viewer.isCachedForTest(photos.get(3)));
        assertFalse(viewer.isCachedForTest(photos.get(4)));
        waitUntil(() -> viewer.hasImage() && viewer.cachedImageCountForTest() == 2
                        && loader.inFlightCount() == 0,
                "新照片和唯一邻居读取完成");
        assertTrue(viewer.isCachedForTest(photos.get(5)));
        assertTrue(viewer.isCachedForTest(photos.get(6)));
        loader.shutdown();
    }

    @Test
    void 选中一张素材之后大图会显示它的原图() throws Exception {
        Theme.install();
        Path folder = Files.createDirectories(tempDir.resolve("素材"));
        Path photo = TestImages.writeJpeg(folder.resolve("p0.jpg"), 900, 600);

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(760, 520);
            layoutTree(viewer);
        });

        // 一开始什么都没有
        assertTrue(!viewer.hasImage());
        BufferedImage blank = render(viewer);
        assertTrue(countColors(blank) > 1, "空状态也该有一行提示");

        SwingUtilities.invokeAndWait(() -> viewer.showPhoto(photo));
        waitUntil(viewer::hasImage, "原图解码完成");

        BufferedImage rendered = render(viewer);
        Path output = Path.of("target", "ui-viewer.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(rendered, "png", output.toFile());
        assertTrue(countColors(rendered) > 3, "应当真的画出照片");
        loader.shutdown();
    }

    @Test
    void 方向键前后过片_走到头就停下() throws Exception {
        Theme.install();
        Path folder = Files.createDirectories(tempDir.resolve("素材"));
        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            photos.add(TestImages.writeJpeg(folder.resolve("p" + i + ".jpg"), 300, 200));
        }

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(600, 200);
            filmstrip.setGroups(List.of(new PhotoGroup("一组", folder, photos)), 120);
            layoutTree(filmstrip);
        });

        filmstrip.setCurrentPhoto(photos.get(0));
        filmstrip.moveCurrent(1);
        assertEquals(photos.get(1), filmstrip.currentPhoto());
        filmstrip.moveCurrent(1);
        assertEquals(photos.get(2), filmstrip.currentPhoto());

        filmstrip.moveCurrent(1); // 已经是最后一张
        assertEquals(photos.get(2), filmstrip.currentPhoto(), "到头了就不该再动，也不该绕回第一张");

        filmstrip.moveCurrent(-1);
        assertEquals(photos.get(1), filmstrip.currentPhoto());
        loader.shutdown();
    }

    @Test
    void 适应窗口与一比一的切换() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 900, 600);

        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        assertTrue(viewer.isFitToWindow(), "新照片默认应当适应窗口");

        SwingUtilities.invokeAndWait(viewer::actualSize);
        assertTrue(!viewer.isFitToWindow());
        assertEquals(100, viewer.zoomPercent());

        SwingUtilities.invokeAndWait(viewer::fitToWindow);
        assertTrue(viewer.isFitToWindow());
        loader.shutdown();
    }

    @Test
    void 改默认倍率之后_当前这张和以后每张都按新倍率显示() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path folder = Files.createDirectories(tempDir.resolve("素材"));
        Path first = TestImages.writeJpeg(folder.resolve("a.jpg"), 900, 600);
        Path second = TestImages.writeJpeg(folder.resolve("b.jpg"), 900, 600);

        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(first);
        });
        waitUntil(viewer::hasImage, "第一张");
        assertTrue(viewer.isFitToWindow(), "未改设置时默认适应窗口");

        // 改成 50%：眼前这张立刻跟着变，不用翻页
        SwingUtilities.invokeAndWait(() -> viewer.setDefaultScale(0.5));
        assertEquals(50, viewer.zoomPercent(), "改完立刻按新倍率显示当前这张");

        // 翻到下一张：仍然是新倍率
        SwingUtilities.invokeAndWait(() -> viewer.showPhoto(second));
        waitUntil(viewer::hasImage, "第二张");
        assertEquals(50, viewer.zoomPercent(), "之后翻到的每张都用新倍率");
        loader.shutdown();
    }

    @Test
    void 表头缩放胶囊点得中_并真的切换显示模式() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 900, 600);

        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(500, 400);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");
        render(viewer); // 控件位置在绘制时确定

        java.awt.Rectangle actual = viewer.zoomChipBoundsForTest(1);
        java.awt.Rectangle fit = viewer.zoomChipBoundsForTest(0);
        assertTrue(actual != null && fit != null, "画过之后应当能拿到两枚胶囊的矩形");
        assertEquals(500 - 12, actual.x + actual.width, "整组胶囊应当贴着表头右侧留白");

        clickAt(viewer, actual);
        assertEquals(100, viewer.zoomPercent(), "点 100% 应当按一图片像素对一屏幕像素显示");

        clickAt(viewer, fit);
        assertTrue(viewer.isFitToWindow(), "点适应之后应当切回按窗口大小显示");
        loader.shutdown();
    }

    @Test
    void 当前画面临时缩放不会改掉默认缩放偏好() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 900, 600);

        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(500, 400);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        SwingUtilities.invokeAndWait(viewer::actualSize);
        assertTrue(viewer.isDefaultFitToWindow(),
                "当前这张切到 100% 只是在看细节，默认策略仍是适应窗口");

        SwingUtilities.invokeAndWait(() -> viewer.setDefaultScale(0.5));
        assertTrue(!viewer.isDefaultFitToWindow(), "选了固定倍率才算改了默认策略");

        SwingUtilities.invokeAndWait(viewer::setDefaultFitToWindow);
        assertTrue(viewer.isDefaultFitToWindow());
        assertTrue(viewer.isFitToWindow(), "改默认策略时当前这张也立刻切到适应窗口");
        loader.shutdown();
    }

    @Test
    void 迟到的旧原图不能盖住当前照片() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path folder = Files.createDirectories(tempDir.resolve("素材"));
        Path first = TestImages.writeJpeg(folder.resolve("a.jpg"), 300, 200);
        Path second = TestImages.writeJpeg(folder.resolve("b.jpg"), 400, 100);

        // 关掉真实加载，只靠手工投递回调来模拟“慢解码晚回来”，避免测试看机器速度。
        loader.shutdown();
        SwingUtilities.invokeAndWait(() -> viewer.showPhoto(second));
        long currentRequest = viewer.loadVersionForTest();

        BufferedImage oldImage = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        viewer.onLoaded(first, currentRequest - 1, oldImage);
        assertTrue(!viewer.hasImage(), "旧代次的结果必须丢掉");

        BufferedImage currentImage = new BufferedImage(40, 10, BufferedImage.TYPE_INT_RGB);
        viewer.onLoaded(second, currentRequest, currentImage);
        assertTrue(viewer.hasImage(), "当前代次的结果应当正常上屏");
        assertEquals(40, viewer.imageSizeForTest().width);
        assertEquals(10, viewer.imageSizeForTest().height);
    }

    @Test
    void 裁切工具拖出选框并显示裁切后的画面() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 400, 300);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(600, 500);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        AtomicReference<CropRect> chosen = new AtomicReference<>();
        viewer.setOnCropCommitted(chosen::set);
        SwingUtilities.invokeAndWait(() -> viewer.setCursorTool(CursorTool.CROP));
        assertTrue(viewer.isCropModeForTest());

        Rectangle image = viewer.imageDrawRectForTest();
        Point from = new Point(image.x + image.width / 4, image.y + image.height / 4);
        Point to = new Point(image.x + image.width * 3 / 4,
                image.y + image.height * 3 / 4);
        SwingUtilities.invokeAndWait(() -> {
            sendMouse(viewer, from, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1_DOWN_MASK);
            sendMouse(viewer, to, MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON1_DOWN_MASK);
            sendMouse(viewer, to, MouseEvent.MOUSE_RELEASED, 0);
        });

        CropRect crop = chosen.get();
        assertTrue(crop != null, "拖动后应当提交一块裁切区域");
        assertEquals(0.25, crop.x(), 0.03);
        assertEquals(0.25, crop.y(), 0.03);
        assertEquals(0.50, crop.width(), 0.03);
        assertEquals(0.50, crop.height(), 0.03);

        SwingUtilities.invokeAndWait(() -> {
            viewer.setEdits(EditState.NONE.withCrop(crop));
            viewer.setGridVisible(true);
        });
        Path output = Path.of("target", "ui-crop-grid.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(render(viewer), "png", output.toFile());

        SwingUtilities.invokeAndWait(() -> viewer.setCursorTool(CursorTool.SELECT));
        assertEquals(200, viewer.imageSizeForTest().width);
        assertEquals(150, viewer.imageSizeForTest().height);
        loader.shutdown();
    }

    @Test
    void 三分网格只画在图片范围内() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 400, 300);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(600, 500);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");
        viewer.setGridVisible(true);
        Rectangle image = viewer.imageDrawRectForTest();
        BufferedImage rendered = render(viewer);

        int y = image.y + image.height / 2;
        int x = image.x + image.width / 3;
        assertTrue(rendered.getRGB(x, y) != rendered.getRGB(x + 2, y),
                "三分之一处应当看到一条辅助线");
        assertTrue(rendered.getRGB(image.x - 4, y) != rendered.getRGB(x, y),
                "图片外的留白不应被网格铺满");
        loader.shutdown();
    }

    @Test
    void 已有裁切框可以移动和从右下角缩放() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 400, 300);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(600, 500);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");
        CropRect initial = new CropRect(0.25, 0.25, 0.5, 0.5);
        AtomicReference<CropRect> result = new AtomicReference<>();
        viewer.setOnCropCommitted(result::set);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setEdits(EditState.NONE.withCrop(initial));
            viewer.setCursorTool(CursorTool.CROP);
        });
        Rectangle image = viewer.imageDrawRectForTest();
        Rectangle crop = screenRect(initial, image);

        Point center = new Point(crop.x + crop.width / 2, crop.y + crop.height / 2);
        Point movedCenter = new Point(center.x + 40, center.y);
        SwingUtilities.invokeAndWait(() -> {
            sendMouse(viewer, center, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1_DOWN_MASK);
            sendMouse(viewer, movedCenter, MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON1_DOWN_MASK);
            sendMouse(viewer, movedCenter, MouseEvent.MOUSE_RELEASED, 0);
        });
        CropRect moved = result.get();
        assertTrue(moved != null && moved.x() > initial.x(), "框内拖动应当移动选框");
        assertEquals(initial.width(), moved.width(), 0.02, "移动不改变选框宽度");

        SwingUtilities.invokeAndWait(() -> viewer.setEdits(EditState.NONE.withCrop(moved)));
        Rectangle movedScreen = screenRect(moved, image);
        Point corner = new Point(movedScreen.x + movedScreen.width,
                movedScreen.y + movedScreen.height);
        Point enlarged = new Point(corner.x + 30, corner.y + 20);
        SwingUtilities.invokeAndWait(() -> {
            sendMouse(viewer, corner, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1_DOWN_MASK);
            sendMouse(viewer, enlarged, MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON1_DOWN_MASK);
            sendMouse(viewer, enlarged, MouseEvent.MOUSE_RELEASED, 0);
        });
        CropRect resized = result.get();
        assertTrue(resized.width() > moved.width(), "抓右下角外拖应当变宽");
        assertTrue(resized.height() > moved.height(), "抓右下角外拖应当变高");
        loader.shutdown();
    }

    @Test
    void 滚轮在整百倍率之间跳() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 900, 600);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        SwingUtilities.invokeAndWait(() -> viewer.setDefaultScale(0.18));
        assertEquals(18, viewer.zoomPercent(), "起点是默认那一档");

        // 一路往上滚：档位要够密，但 100、200、300、400 这些整百档必须都恰好踩得到
        List<Integer> visited = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 12; i++) {
                viewer.zoomStep(1);
                visited.add(viewer.zoomPercent());
            }
        });
        assertTrue(visited.contains(100) && visited.contains(200)
                        && visited.contains(300) && visited.contains(400),
                "整百的常用档都该滚得到，实际经过：" + visited);
        for (int i = 1; i < visited.size(); i++) {
            // 到顶（800%）之后停在原地，所以允许相等，但不许变小
            assertTrue(visited.get(i) >= visited.get(i - 1), "一路往上滚不该回头：" + visited);
        }

        // 一路往下滚，最后停在默认那一档
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 20; i++) {
                viewer.zoomStep(-1);
            }
        });
        assertEquals(5, viewer.zoomPercent(),
                "到底了就停在最低档（5%），不再动——默认档下面还有几档可以看整卷构图");

        // 从"适应窗口"算出来的零碎比例起步，按方向落到相邻档
        viewer.fitToWindow();
        int fitted = viewer.zoomPercent();
        viewer.zoomStep(1);
        assertTrue(viewer.zoomPercent() > fitted, "往上滚应当比适应出来的比例大");
        loader.shutdown();
    }

    @Test
    void 默认适应窗口的4000乘6000画幅在720乘1080查看区正好是18百分比() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        // 等比缩小 10 倍，保持 4:6 画幅；查看区也等比取 72×108，
        // 数学结果与 4000×6000 / 720×1080 完全相同，同时避免测试占近百 MB。
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("4000x6000.jpg"),
                400, 600);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(72 + 24, 108 + 26 + 24);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        assertTrue(viewer.isFitToWindow());
        assertEquals(18, viewer.zoomPercent(),
                "4:6 画幅在 720×1080 有效查看区中适应结果应为 18%");
        loader.shutdown();
    }

    @Test
    void 从适应窗口向下滚可以回到默认的18百分比() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 1200, 800);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        viewer.fitToWindow();
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 20 && viewer.zoomPercent() > 5; i++) {
                viewer.zoomStep(-1);
                if (viewer.zoomPercent() == 18) {
                    break;
                }
            }
        });
        assertEquals(18, viewer.zoomPercent(), "滚轮档位里必须有默认的 18%");
        loader.shutdown();
    }

    @Test
    void 滚轮缩放以鼠标所指的那一点为基准() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 1200, 800);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        // 先摆到 100% 这个确定的起点（档位表变密之后，滚到 100% 要几格是会变的）
        SwingUtilities.invokeAndWait(viewer::actualSize);
        assertEquals(100, viewer.zoomPercent());

        // 光标放在视图中间偏一点的位置：连滚两格，它下面那个图片像素应当基本不动
        java.awt.Point cursor = new java.awt.Point(200, 160);
        java.awt.Point before = viewer.imagePointAt(cursor);
        SwingUtilities.invokeAndWait(() -> {
            viewer.zoomStep(1, cursor);
            viewer.zoomStep(1, cursor);
        });
        java.awt.Point after = viewer.imagePointAt(cursor);

        assertTrue(viewer.zoomPercent() > 100, "应当真的放大了");
        assertEquals(before.x, after.x, 2, "光标下面的图片像素应当基本没挪动（横）");
        assertEquals(before.y, after.y, 2, "光标下面的图片像素应当基本没挪动（纵）");
        loader.shutdown();
    }

    @Test
    void 图比窗口大时能平移_但拖不出视野() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 1200, 800);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");
        SwingUtilities.invokeAndWait(viewer::actualSize);

        SwingUtilities.invokeAndWait(() -> viewer.panBy(10000, 10000));
        int limitX = viewer.offsetXForTest();
        int limitY = viewer.offsetYForTest();
        assertTrue(limitX > 0 && limitY > 0, "图比窗口大，应当能平移");

        SwingUtilities.invokeAndWait(() -> viewer.panBy(10000, 10000));
        assertEquals(limitX, viewer.offsetXForTest(), "再往外拖也拖不动了");
        assertEquals(limitY, viewer.offsetYForTest());

        SwingUtilities.invokeAndWait(() -> viewer.panBy(-100000, -100000));
        assertEquals(-limitX, viewer.offsetXForTest(), "反方向是同一个界限");
        assertEquals(-limitY, viewer.offsetYForTest());

        // 换回适应窗口：图变小了，偏移归零居中
        SwingUtilities.invokeAndWait(viewer::fitToWindow);
        assertEquals(0, viewer.offsetXForTest());
        assertEquals(0, viewer.offsetYForTest());
        loader.shutdown();
    }

    @Test
    void 放大到超出窗口时_表头不会被照片盖住() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 1200, 800);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        // 放大到远超窗口：连滚七格到 700%，照片必然溢出图片区
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 7; i++) {
                viewer.zoomStep(1);
            }
        });

        BufferedImage rendered = render(viewer);
        // 表头底部那条分隔线（HEADER_HEIGHT 26，往上 7 像素）应当还在。
        // 照片要是盖上来，这条线就没有了。
        int lineY = 19;
        int rgb = rendered.getRGB(rendered.getWidth() / 2, lineY) & 0xFFFFFF;
        assertEquals(Theme.BORDER.getRGB() & 0xFFFFFF, rgb,
                "放大之后表头那条分隔线应当还在，说明照片被限制在图片区里");
        loader.shutdown();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }

    private static BufferedImage render(javax.swing.JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
        return image;
    }

    private static int countColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                colors.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return colors.size();
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static void clickAt(ViewerPanel viewer, java.awt.Rectangle bounds) throws Exception {
        SwingUtilities.invokeAndWait(() -> viewer.dispatchEvent(new java.awt.event.MouseEvent(
                viewer, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                java.awt.event.MouseEvent.BUTTON1_DOWN_MASK,
                bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, 1, false,
                java.awt.event.MouseEvent.BUTTON1)));
    }

    private static void sendMouse(ViewerPanel viewer, Point point, int id, int modifiers) {
        int button = id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1;
        viewer.dispatchEvent(new MouseEvent(viewer, id, System.currentTimeMillis(), modifiers,
                point.x, point.y, id == MouseEvent.MOUSE_PRESSED ? 1 : 0, false, button));
    }

    private static Rectangle screenRect(CropRect crop, Rectangle image) {
        int left = image.x + (int) Math.round(crop.x() * image.width);
        int top = image.y + (int) Math.round(crop.y() * image.height);
        int right = image.x + (int) Math.round((crop.x() + crop.width()) * image.width);
        int bottom = image.y + (int) Math.round((crop.y() + crop.height()) * image.height);
        return new Rectangle(left, top, right - left, bottom - top);
    }
}
