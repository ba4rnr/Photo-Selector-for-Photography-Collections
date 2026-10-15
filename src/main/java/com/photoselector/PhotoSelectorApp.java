package com.photoselector;

/**
 * 程序入口。
 *
 * <p>目前只是一个占位实现，用来验证项目骨架、构建与运行链路是否正常。
 * 真正的照片筛选功能会在后续迭代中接入这里。
 */
public final class PhotoSelectorApp {

    private static final String APP_NAME = "摄影集照片筛选器";

    private PhotoSelectorApp() {
        // 工具类，禁止实例化
    }

    /**
     * 返回程序名称，供启动信息与测试使用。
     *
     * @return 程序名称
     */
    public static String appName() {
        return APP_NAME;
    }

    public static void main(String[] args) {
        System.out.println(APP_NAME + " — 项目骨架已就绪。");
    }
}
