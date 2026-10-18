package com.photoselector.ui.theme;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JScrollBar;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * 全局配色与外观设置。
 *
 * <p>所有色值都取自 Capture One 22 界面的实测取样，集中定义在这里。
 * 界面代码一律引用这些常量、不写死颜色——想调整风格只改这一个文件。
 */
public final class Theme {

    /** 大图区背景，最深的一层。 */
    public static final Color CANVAS = new Color(0x191919);

    /** 面板、工具栏、胶片条的底色。 */
    public static final Color PANEL = new Color(0x2D2D2D);

    /** 高亮与选中：鼠标悬停、当前选中的项。 */
    public static final Color HIGHLIGHT = new Color(0x414141);

    /** 鼠标移到按钮上时用的底色，比 {@link #HIGHLIGHT} 轻一档。 */
    public static final Color HOVER = new Color(0x363636);

    /** 激活的工具。Capture One 用它标记"当前正在用的那个工具"。 */
    public static final Color ACCENT = new Color(0xFF8B16);

    /** 正文文字。 */
    public static final Color TEXT = new Color(0xC3C3C3);

    /** 次要文字：文件名、说明标签。 */
    public static final Color TEXT_MUTED = new Color(0xA0A0A0);

    /** 不可用的文字与图标：导航按钮到头时的样子。 */
    public static final Color TEXT_DISABLED = new Color(0x5A5A5A);

    /** 分隔线，比面板再深一点。 */
    public static final Color BORDER = new Color(0x1A1A1A);

    /** 素材条上每个文件夹外圈的细边框：比分隔线亮，能看出"一组到这儿为止"。 */
    public static final Color GROUP_BORDER = new Color(0x505050);

    /** 指针压到分隔条上时的提示色：一条细白线，等于"这里可以拖"。 */
    public static final Color DIVIDER_HINT = new Color(0xFFFFFF);

    /** 裁切时盖在选区外的半透明遮罩。 */
    public static final Color CROP_MASK = new Color(0, 0, 0, 145);

    /** 裁切框角/边上的提示条；比正文略透明，避免压住画面细节。 */
    public static final Color CROP_HANDLE = new Color(195, 195, 195, 210);

    /** 三分网格辅助线：轻一点，只提示构图，不抢照片本身。 */
    public static final Color GRID_LINE = new Color(255, 255, 255, 85);

    /** 界面主字体。微软雅黑在中文环境下显示最端正。 */
    public static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);

    /** 小一号的字体，工具栏标签这类地方用。 */
    public static final Font FONT_SMALL = new Font("Microsoft YaHei UI", Font.PLAIN, 11);

    private Theme() {
        // 常量类，禁止实例化
    }

    /**
     * 安装外观：先选跨平台（Metal）外观，保证在任何机器上长得一样，
     * 再把项目会用到的那些控件的默认颜色统一改深。
     *
     * <p>Swing 的默认配色都放在 {@code UIManager} 这张全局表里，
     * 换掉表里的值就是最省事、也最正统的"换肤"方式。
     */
    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // 装不上就退回默认外观，不影响功能
        }

        put("control", PANEL);
        put("info", PANEL);

        put("Panel.background", PANEL);
        put("Panel.foreground", TEXT);
        put("Label.foreground", TEXT);

        put("MenuBar.background", PANEL);
        put("MenuBar.foreground", TEXT);
        put("Menu.background", PANEL);
        put("Menu.foreground", TEXT);
        put("Menu.selectionBackground", HIGHLIGHT);
        put("Menu.selectionForeground", TEXT);
        put("MenuItem.background", PANEL);
        put("MenuItem.foreground", TEXT);
        put("MenuItem.selectionBackground", HIGHLIGHT);
        put("MenuItem.selectionForeground", TEXT);
        put("PopupMenu.background", PANEL);
        put("PopupMenu.foreground", TEXT);
        // 菜单一律用基础外观：系统外观会给弹层画白边、蓝底和蓝色的快捷键文字，
        // 在深色界面上非常跳。换成基础实现后，颜色就完全听我们的。
        UIManager.put("MenuUI", "javax.swing.plaf.basic.BasicMenuUI");
        UIManager.put("MenuItemUI", "javax.swing.plaf.basic.BasicMenuItemUI");
        UIManager.put("PopupMenuUI", "javax.swing.plaf.basic.BasicPopupMenuUI");
        UIManager.put("PopupMenuSeparatorUI", "javax.swing.plaf.basic.BasicPopupMenuSeparatorUI");
        UIManager.put("MenuItem.borderPainted", Boolean.FALSE);
        UIManager.put("Menu.borderPainted", Boolean.FALSE);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(BORDER));
        put("MenuItem.acceleratorForeground", TEXT_MUTED);
        put("MenuItem.acceleratorSelectionForeground", TEXT);
        put("Separator.foreground", HIGHLIGHT);
        put("Separator.background", BORDER);

        put("Button.background", PANEL);
        put("Button.foreground", TEXT);
        put("CheckBox.background", PANEL);
        put("CheckBox.foreground", TEXT);
        put("RadioButton.background", PANEL);
        put("RadioButton.foreground", TEXT);

        put("ScrollBar.background", CANVAS);
        put("ScrollBar.thumb", HIGHLIGHT);
        put("ScrollBar.track", CANVAS);
        // Metal 会给滑块画一圈立体边框，不换成同色就还是亮银色
        put("ScrollBar.thumbHighlight", HIGHLIGHT);
        put("ScrollBar.thumbShadow", BORDER);
        put("ScrollBar.thumbDarkShadow", BORDER);
        put("ScrollBar.thumbLightShadow", BORDER);

        put("SplitPane.background", PANEL);

        // 目录树的展开把手：换掉 Metal 默认那个空心小圆圈
        UIManager.put("Tree.collapsedIcon", Icons.triangle(false));
        UIManager.put("Tree.expandedIcon", Icons.triangle(true));
        put("Tree.background", PANEL);
        put("Tree.textBackground", PANEL);
        put("Tree.textForeground", TEXT);
        put("Tree.selectionBackground", HIGHLIGHT);
        put("Tree.selectionForeground", TEXT);
        // 连接节点的那些线：默认是亮蓝色，在深底上非常刺眼
        put("Tree.line", BORDER);
        put("Tree.hash", BORDER);
        put("SplitPaneDivider.draggingColor", DIVIDER_HINT);

        put("ToolTip.background", HIGHLIGHT);
        put("ToolTip.foreground", TEXT);
        put("OptionPane.background", PANEL);
        put("OptionPane.messageForeground", TEXT);
    }

    private static void put(String key, Color color) {
        // ColorUIResource 是 UIManager 认识的"默认值"类型，
        // 用它才能保证外观实现愿意采纳我们的颜色
        UIManager.put(key, new ColorUIResource(color));
    }

    /**
     * 把自己造的滚动条换成朴素外观。
     *
     * <p>默认（Metal）外观会画亮银色的立体滑块和两个箭头按钮，在深色界面上非常扎眼；
     * 换成基础实现后，颜色就能用我们设的那两个键控制，箭头按钮也一并去掉。
     */
    @SuppressWarnings("serial")
    public static void themeScrollBar(JScrollBar bar) {
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = HIGHLIGHT;
                trackColor = CANVAS;
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return zeroSizeButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return zeroSizeButton();
            }

            private JButton zeroSizeButton() {
                JButton button = new JButton();
                Dimension zero = new Dimension(0, 0);
                button.setPreferredSize(zero);
                button.setMinimumSize(zero);
                button.setMaximumSize(zero);
                return button;
            }
        });
    }
}
