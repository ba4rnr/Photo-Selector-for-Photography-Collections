package com.photoselector.io;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 一张照片的内容来源。
 *
 * <p>这层抽象是专门给 DNG 留的位置：界面只认这个接口，
 * 先在 JPG 上把整套流程跑通；以后补一个 {@code DngImageSource}
 * （解析 TIFF 的 IFD 链表、取出内嵌的全尺寸预览），界面代码一行都不用改。
 */
public interface ImageSource {

    /** 文件名（不含目录），界面显示用。 */
    String fileName();

    /** 像素宽度。 */
    int width();

    /** 像素高度。 */
    int height();

    /**
     * 按原尺寸解码，供大图视图使用。
     *
     * <p>这是一次真正的解码，24MP 的照片要花几百毫秒，<b>不要</b>在界面线程上调用。
     */
    BufferedImage loadFull();

    /**
     * 解码成"高度不超过 {@code maxHeight}"的缩略图，供胶片条和网格使用。
     *
     * <p>实现上应当尽量省事——能不解全尺寸就不解全尺寸。
     */
    BufferedImage loadThumbnail(int maxHeight);

    /**
     * 按扩展名挑一个实现。
     *
     * <p>以后接入 DNG 时，只需要在这里多一个分支，
     * 调用方（缩略图服务、界面）都不用动。
     */
    static ImageSource open(Path file) {
        if (isSupported(file)) {
            return new JpegImageSource(file);
        }
        throw new UnsupportedOperationException("暂时只支持 JPG，还不认识：" + file.getFileName());
    }

    /** 这个文件是不是我们读得了的格式。判断只此一处，别处都从这里问。 */
    static boolean isSupported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }
}
