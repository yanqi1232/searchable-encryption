package com.bdic.admin;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * 根据窗口大小动态缩放字体、按钮内边距与常见布局间距。
 */
public final class UiScaleManager {

    /** 记录组件原始字体大小的 client property 键。 */
    private static final String BASE_FONT_SIZE_KEY = "ui.scale.baseFontSize";
    /** 记录按钮原始内边距的 client property 键。 */
    private static final String BASE_BUTTON_MARGIN_KEY = "ui.scale.baseButtonMargin";
    /** 记录组件原始边框的 client property 键。 */
    private static final String BASE_BORDER_KEY = "ui.scale.baseBorder";
    /** 记录 FlowLayout 原始间距的 client property 键。 */
    private static final String BASE_FLOW_GAP_KEY = "ui.scale.baseFlowGap";
    /** 记录 GridLayout 原始间距的 client property 键。 */
    private static final String BASE_GRID_GAP_KEY = "ui.scale.baseGridGap";
    /** 记录 Box.Filler 原始尺寸的 client property 键。 */
    private static final String BASE_FILLER_SIZE_KEY = "ui.scale.baseFillerSize";
    private static final String MANAGER_KEY = "ui.scale.manager";

    /** 被缩放管理器接管的主窗口。 */
    private final JFrame frame;
    /** 设计时基准宽度。 */
    private final int baseWidth;
    /** 设计时基准高度。 */
    private final int baseHeight;
    /** 允许的最小缩放比例。 */
    private final double minScale;
    /** 允许的最大缩放比例。 */
    private final double maxScale;

    /**
     * 创建缩放管理器，基准尺寸用于把当前窗口大小换算成比例。
     */
    private UiScaleManager(JFrame frame, int baseWidth, int baseHeight, double minScale, double maxScale) {
        this.frame = frame;
        this.baseWidth = Math.max(1, baseWidth);
        this.baseHeight = Math.max(1, baseHeight);
        this.minScale = minScale;
        this.maxScale = maxScale;
    }

    /**
     * 给窗口安装自适应缩放监听器，并立即应用一次当前比例。
     */
    public static void install(JFrame frame, int baseWidth, int baseHeight) {
        UiScaleManager manager = new UiScaleManager(frame, baseWidth, baseHeight, 1.0d, 1.8d);
        frame.getRootPane().putClientProperty(MANAGER_KEY, manager);
        manager.applyCurrentScale();
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                manager.applyCurrentScale();
            }
        });
    }

    public static void reapplyCurrentScale(JFrame frame) {
        if (frame == null || frame.getRootPane() == null) {
            return;
        }
        Object managerValue = frame.getRootPane().getClientProperty(MANAGER_KEY);
        if (managerValue instanceof UiScaleManager manager) {
            manager.applyCurrentScale();
        }
    }

    public static void reapplyCurrentScale(JFrame frame, Component root) {
        if (frame == null || frame.getRootPane() == null || root == null) {
            return;
        }
        Object managerValue = frame.getRootPane().getClientProperty(MANAGER_KEY);
        if (managerValue instanceof UiScaleManager manager) {
            manager.applyCurrentScale(root);
        }
    }

    /**
     * 按当前窗口大小重新计算比例，并递归缩放窗口内组件。
     */
    private void applyCurrentScale() {
        double scale = calculateScale(frame.getWidth(), frame.getHeight());
        scaleRecursively(frame.getRootPane(), scale);
        frame.revalidate();
        frame.repaint();
    }

    /**
     * 只缩放指定的动态子树，避免搜索结果等局部刷新时重算整个窗口。
     */
    private void applyCurrentScale(Component root) {
        double scale = calculateScale(frame.getWidth(), frame.getHeight());
        scaleRecursively(root, scale);
        root.revalidate();
        root.repaint();
    }

    /**
     * 使用宽高两个方向中较小的比例，避免某个方向内容被放得过大。
     */
    private double calculateScale(int width, int height) {
        double widthScale = width / (double) baseWidth;
        double heightScale = height / (double) baseHeight;
        double rawScale = Math.min(widthScale, heightScale);
        if (rawScale < minScale) {
            return minScale;
        }
        if (rawScale > maxScale) {
            return maxScale;
        }
        return rawScale;
    }

    /**
     * 深度遍历组件树，对每个 Swing 组件应用字体、边框和间距缩放。
     */
    private void scaleRecursively(Component component, double scale) {
        if (component instanceof JComponent jComponent) {
            scaleFont(jComponent, scale);
            scaleBorder(jComponent, scale);
            scaleLayoutGap(jComponent, scale);
            scaleButtonMargin(jComponent, scale);
            scaleBoxFiller(jComponent, scale);
        }

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                scaleRecursively(child, scale);
            }
        }
    }

    /**
     * 缩放组件字体；第一次遇到组件时保存原始字号，后续都基于原始值计算。
     */
    private void scaleFont(JComponent component, double scale) {
        Font font = component.getFont();
        if (font == null) {
            return;
        }
        Object baseValue = component.getClientProperty(BASE_FONT_SIZE_KEY);
        float baseSize;
        if (baseValue instanceof Float f) {
            baseSize = f;
        } else {
            baseSize = font.getSize2D();
            component.putClientProperty(BASE_FONT_SIZE_KEY, baseSize);
        }
        float scaledSize = Math.max(11f, (float) (baseSize * scale));
        component.setFont(font.deriveFont(scaledSize));
    }

    /**
     * 缩放按钮内边距，避免窗口放大后按钮文本显得拥挤。
     */
    private void scaleButtonMargin(JComponent component, double scale) {
        if (!(component instanceof AbstractButton button)) {
            return;
        }
        Insets margin = button.getMargin();
        if (margin == null) {
            return;
        }
        Object baseMarginValue = component.getClientProperty(BASE_BUTTON_MARGIN_KEY);
        Insets baseMargin;
        if (baseMarginValue instanceof Insets insets) {
            baseMargin = insets;
        } else {
            baseMargin = copyInsets(margin);
            component.putClientProperty(BASE_BUTTON_MARGIN_KEY, baseMargin);
        }
        button.setMargin(scaleInsets(baseMargin, scale));
    }

    /**
     * 缩放组件边框中的留白部分。
     */
    private void scaleBorder(JComponent component, double scale) {
        Border currentBorder = component.getBorder();
        if (currentBorder == null) {
            return;
        }
        Object baseBorderValue = component.getClientProperty(BASE_BORDER_KEY);
        Border baseBorder;
        if (baseBorderValue instanceof Border border) {
            baseBorder = border;
        } else {
            baseBorder = currentBorder;
            component.putClientProperty(BASE_BORDER_KEY, baseBorder);
        }
        Border scaledBorder = scaleBorderValue(baseBorder, scale);
        if (scaledBorder != null) {
            component.setBorder(scaledBorder);
        }
    }

    /**
     * 递归缩放 EmptyBorder 和 CompoundBorder，其它边框保持原样。
     */
    private Border scaleBorderValue(Border border, double scale) {
        if (border instanceof EmptyBorder emptyBorder) {
            Insets insets = emptyBorder.getBorderInsets();
            return BorderFactory.createEmptyBorder(
                    scaled(insets.top, scale),
                    scaled(insets.left, scale),
                    scaled(insets.bottom, scale),
                    scaled(insets.right, scale)
            );
        }
        if (border instanceof CompoundBorder compoundBorder) {
            Border outside = scaleBorderValue(compoundBorder.getOutsideBorder(), scale);
            Border inside = scaleBorderValue(compoundBorder.getInsideBorder(), scale);
            if (outside != null && inside != null) {
                return BorderFactory.createCompoundBorder(outside, inside);
            }
        }
        return border;
    }

    /**
     * 缩放 FlowLayout 和 GridLayout 的水平/垂直间距。
     */
    private void scaleLayoutGap(JComponent component, double scale) {
        LayoutManager layoutManager = component.getLayout();
        if (layoutManager instanceof FlowLayout flowLayout) {
            int[] baseGap = (int[]) component.getClientProperty(BASE_FLOW_GAP_KEY);
            if (baseGap == null) {
                baseGap = new int[]{flowLayout.getHgap(), flowLayout.getVgap()};
                component.putClientProperty(BASE_FLOW_GAP_KEY, baseGap);
            }
            flowLayout.setHgap(scaled(baseGap[0], scale));
            flowLayout.setVgap(scaled(baseGap[1], scale));
            return;
        }
        if (layoutManager instanceof GridLayout gridLayout) {
            int[] baseGap = (int[]) component.getClientProperty(BASE_GRID_GAP_KEY);
            if (baseGap == null) {
                baseGap = new int[]{gridLayout.getHgap(), gridLayout.getVgap()};
                component.putClientProperty(BASE_GRID_GAP_KEY, baseGap);
            }
            gridLayout.setHgap(scaled(baseGap[0], scale));
            gridLayout.setVgap(scaled(baseGap[1], scale));
        }
    }

    /**
     * 缩放 Box.Filler 占位组件，保持界面留白随窗口一起变化。
     */
    private void scaleBoxFiller(JComponent component, double scale) {
        if (!(component instanceof Box.Filler filler)) {
            return;
        }
        Object baseSizeValue = component.getClientProperty(BASE_FILLER_SIZE_KEY);
        Dimension baseSize;
        if (baseSizeValue instanceof Dimension dimension) {
            baseSize = dimension;
        } else {
            baseSize = filler.getPreferredSize();
            component.putClientProperty(BASE_FILLER_SIZE_KEY, baseSize);
        }
        Dimension preferred = scaleDimension(baseSize, scale);
        filler.changeShape(preferred, preferred, preferred);
    }

    /** 按比例缩放 Insets 四个方向的值。 */
    private Insets scaleInsets(Insets baseInsets, double scale) {
        return new Insets(
                scaled(baseInsets.top, scale),
                scaled(baseInsets.left, scale),
                scaled(baseInsets.bottom, scale),
                scaled(baseInsets.right, scale)
        );
    }

    /** 复制 Insets，防止后续修改影响原始边距记录。 */
    private Insets copyInsets(Insets insets) {
        return new Insets(insets.top, insets.left, insets.bottom, insets.right);
    }

    /** 按比例缩放 Dimension 的宽高。 */
    private Dimension scaleDimension(Dimension baseSize, double scale) {
        return new Dimension(
                Math.max(0, scaled(baseSize.width, scale)),
                Math.max(0, scaled(baseSize.height, scale))
        );
    }

    /** 把单个整数尺寸按比例缩放并四舍五入。 */
    private int scaled(int value, double scale) {
        return Math.max(0, (int) Math.round(value * scale));
    }
}
