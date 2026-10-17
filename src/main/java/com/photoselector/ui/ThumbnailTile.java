package com.photoselector.ui;

import java.awt.image.BufferedImage;

import com.photoselector.core.EditState;
import com.photoselector.core.PhotoTransforms;
import com.photoselector.io.ThumbnailService;

/**
 * 某个面板正在显示的一格缩略图。
 *
 * <p>原始像素由 {@link ThumbnailService} 统一持有，这里只拿一个租约；
 * 没编辑时 {@code display} 直接指向共享缓存里的同一张图。只有真的带旋转 /
 * 裁切时才额外生成一份显示用结果，租约关闭时一起释放。
 */
final class ThumbnailTile implements AutoCloseable {

    private final ThumbnailService.Lease lease;
    private final BufferedImage display;

    private ThumbnailTile(ThumbnailService.Lease lease, BufferedImage display) {
        this.lease = lease;
        this.display = display;
    }

    static ThumbnailTile of(ThumbnailService.Lease lease, EditState edits) {
        BufferedImage display = edits == null || edits.isNone()
                ? lease.image()
                : PhotoTransforms.apply(lease.image(), edits);
        return new ThumbnailTile(lease, display);
    }

    BufferedImage image() {
        return display;
    }

    @Override
    public void close() {
        lease.close();
    }
}
