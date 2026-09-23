package com.hvlplayer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JWindow;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Native menu-bar/system-tray controls for the background player. */
final class MenuBarIcon {
    private final Image appIcon;
    private final Runnable showAction;
    private final Runnable togglePlayAction;
    private final Runnable previousAction;
    private final Runnable nextAction;
    private final Runnable toggleShuffleAction;
    private final Runnable toggleRepeatAction;
    private final Runnable quitAction;
    private final Supplier<String> nowPlayingSupplier;
    private final Supplier<String> playLabelSupplier;
    private final Supplier<String> modeLabelSupplier;
    private final BooleanSupplier repeatSupplier;

    private TrayIcon trayIcon;
    private JWindow popover;
    private JLabel popoverTitle;
    private JLabel popoverState;
    private TransportButton playControl;
    private TransportButton shuffleControl;
    private TransportButton repeatControl;
    private MenuItem nowPlayingItem;
    private MenuItem playItem;
    private MenuItem modeItem;
    private MenuItem repeatItem;
    private String lastNowPlaying;
    private String lastPlayLabel;
    private String lastModeLabel;
    private boolean lastRepeat;

    MenuBarIcon(
            Image appIcon,
            Runnable showAction,
            Runnable togglePlayAction,
            Runnable previousAction,
            Runnable nextAction,
            Runnable toggleShuffleAction,
            Runnable toggleRepeatAction,
            Runnable quitAction,
            Supplier<String> nowPlayingSupplier,
            Supplier<String> playLabelSupplier,
            Supplier<String> modeLabelSupplier,
            BooleanSupplier repeatSupplier) {
        this.appIcon = appIcon;
        this.showAction = showAction;
        this.togglePlayAction = togglePlayAction;
        this.previousAction = previousAction;
        this.nextAction = nextAction;
        this.toggleShuffleAction = toggleShuffleAction;
        this.toggleRepeatAction = toggleRepeatAction;
        this.quitAction = quitAction;
        this.nowPlayingSupplier = nowPlayingSupplier;
        this.playLabelSupplier = playLabelSupplier;
        this.modeLabelSupplier = modeLabelSupplier;
        this.repeatSupplier = repeatSupplier;
    }

    void start() {
        if (!SystemTray.isSupported() || trayIcon != null) {
            return;
        }
        try {
            boolean mac = isMac();
            PopupMenu menu = mac ? null : createNativeMenu();
            trayIcon = new TrayIcon(createTrayImage(appIcon), "HVL", menu);
            trayIcon.setImageAutoSize(true);
            if (mac) {
                trayIcon.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent event) {
                        if (event.getClickCount() == 1) {
                            Point click = new Point(event.getXOnScreen(), event.getYOnScreen());
                            invokeOnSwing(() -> togglePopover(click));
                        }
                    }
                });
            } else {
                trayIcon.addActionListener(event -> invokeOnSwing(showAction));
            }
            SystemTray.getSystemTray().add(trayIcon);
            if (mac) {
                createPopover();
            }
            refresh();
        } catch (AWTException | RuntimeException ignored) {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
            trayIcon = null;
        }
    }

    void stop() {
        TrayIcon icon = trayIcon;
        trayIcon = null;
        JWindow window = popover;
        popover = null;
        if (window != null) {
            if (EventQueue.isDispatchThread()) {
                window.dispose();
            } else {
                SwingUtilities.invokeLater(window::dispose);
            }
        }
        if (icon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(icon);
        }
    }

    void refresh() {
        if (!EventQueue.isDispatchThread()) {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }
        if (trayIcon == null) {
            return;
        }
        String nowPlaying = safe(nowPlayingSupplier.get(), "HVL");
        String playLabel = safe(playLabelSupplier.get(), "Phát");
        String modeLabel = safe(modeLabelSupplier.get(), "Theo danh sách");
        boolean repeating = repeatSupplier.getAsBoolean();
        if (!nowPlaying.equals(lastNowPlaying)) {
            if (nowPlayingItem != null) {
                nowPlayingItem.setLabel(nowPlaying);
            }
            lastNowPlaying = nowPlaying;
            trayIcon.setToolTip("HVL — " + nowPlaying);
        }
        if (!playLabel.equals(lastPlayLabel)) {
            if (playItem != null) {
                playItem.setLabel(playLabel);
            }
            lastPlayLabel = playLabel;
        }
        if (!modeLabel.equals(lastModeLabel)) {
            if (modeItem != null) {
                modeItem.setLabel(modeLabel);
            }
            lastModeLabel = modeLabel;
        }
        if (repeating != lastRepeat && repeatItem != null) {
            repeatItem.setLabel(repeating ? "Lặp lại bài: Bật" : "Lặp lại bài: Tắt");
        }
        lastRepeat = repeating;
        if (popover != null) {
            popoverTitle.setText(ellipsize(nowPlaying, 28));
            popoverTitle.setToolTipText(nowPlaying);
            popoverState.setText(playLabel.equals("Tạm dừng") ? "Đang phát"
                    : playLabel.equals("Tiếp tục") ? "Tạm dừng" : "Sẵn sàng phát");
            playControl.setPlaying(playLabel.equals("Tạm dừng"));
            shuffleControl.setActive(modeLabel.equals("Ngẫu nhiên"));
            repeatControl.setActive(repeating);
        }
    }

    private PopupMenu createNativeMenu() {
        PopupMenu menu = new PopupMenu();
        nowPlayingItem = new MenuItem("HVL");
        nowPlayingItem.setEnabled(false);
        menu.add(nowPlayingItem);
        menu.addSeparator();
        menu.add(actionItem("Mở cửa sổ HVL", showAction));
        menu.add(actionItem("Bài trước", previousAction));
        playItem = new MenuItem("Phát");
        playItem.addActionListener(event -> invokeOnSwing(togglePlayAction));
        menu.add(playItem);
        menu.add(actionItem("Bài tiếp", nextAction));
        modeItem = new MenuItem("Theo danh sách");
        modeItem.addActionListener(event -> invokeOnSwing(toggleShuffleAction));
        menu.add(modeItem);
        repeatItem = actionItem("Lặp lại bài: Tắt", toggleRepeatAction);
        menu.add(repeatItem);
        menu.addSeparator();
        menu.add(actionItem("Thoát HVL", quitAction));
        return menu;
    }

    private void createPopover() {
        JWindow window = new JWindow();
        window.setType(Window.Type.POPUP);
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(true);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(new Color(12, 12, 12));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70)),
                BorderFactory.createEmptyBorder(13, 13, 10, 13)));
        content.setPreferredSize(new Dimension(304, 148));

        JPanel heading = new JPanel(new BorderLayout(10, 0));
        heading.setOpaque(false);
        heading.add(new JLabel(new ImageIcon(appIcon.getScaledInstance(34, 34, Image.SCALE_SMOOTH))),
                BorderLayout.WEST);
        JPanel text = new JPanel(new BorderLayout(0, 2));
        text.setOpaque(false);
        popoverTitle = new JLabel("HVL");
        popoverTitle.setFont(new Font("Helvetica Neue", Font.BOLD, 14));
        popoverTitle.setForeground(Color.WHITE);
        popoverState = new JLabel("Sẵn sàng phát");
        popoverState.setFont(new Font("Helvetica Neue", Font.PLAIN, 11));
        popoverState.setForeground(new Color(165, 165, 165));
        text.add(popoverTitle, BorderLayout.NORTH);
        text.add(popoverState, BorderLayout.SOUTH);
        heading.add(text, BorderLayout.CENTER);
        content.add(heading, BorderLayout.NORTH);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        controls.setOpaque(false);
        shuffleControl = control(Control.SHUFFLE, "Phát ngẫu nhiên", toggleShuffleAction);
        controls.add(shuffleControl);
        controls.add(control(Control.PREVIOUS, "Bài trước", previousAction));
        playControl = control(Control.PLAY, "Phát / tạm dừng", togglePlayAction);
        controls.add(playControl);
        controls.add(control(Control.NEXT, "Bài tiếp theo", nextAction));
        repeatControl = control(Control.REPEAT, "Lặp lại bài hiện tại", toggleRepeatAction);
        controls.add(repeatControl);
        content.add(controls, BorderLayout.CENTER);

        JPanel links = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));
        links.setOpaque(false);
        links.add(textButton("Mở cửa sổ", () -> {
            window.setVisible(false);
            showAction.run();
        }));
        links.add(textButton("Thoát HVL", () -> {
            window.setVisible(false);
            quitAction.run();
        }));
        content.add(links, BorderLayout.SOUTH);
        window.setContentPane(content);
        window.pack();
        window.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                window.setVisible(false);
            }
        });
        popover = window;
    }

    private void togglePopover(Point click) {
        if (trayIcon == null) {
            return;
        }
        if (popover.isVisible()) {
            popover.setVisible(false);
            return;
        }
        refresh();
        GraphicsConfiguration screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().contains(click)) {
                screen = device.getDefaultConfiguration();
                break;
            }
        }
        Rectangle bounds = screen.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        int left = bounds.x + insets.left;
        int right = bounds.x + bounds.width - insets.right - popover.getWidth();
        int top = bounds.y + insets.top;
        int bottom = bounds.y + bounds.height - insets.bottom - popover.getHeight();
        int x = Math.max(left, Math.min(right, click.x - popover.getWidth() / 2));
        int y = click.y < bounds.y + bounds.height / 2
                ? Math.max(top + 4, click.y + 18)
                : click.y - popover.getHeight() - 18;
        popover.setLocation(x, Math.max(top, Math.min(bottom, y)));
        popover.setVisible(true);
        popover.requestFocus();
    }

    private static TransportButton control(Control kind, String tooltip, Runnable action) {
        TransportButton button = new TransportButton(kind);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static JButton textButton(String caption, Runnable action) {
        JButton button = new JButton(caption);
        button.setFont(new Font("Helvetica Neue", Font.PLAIN, 11));
        button.setForeground(new Color(175, 175, 175));
        button.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static String ellipsize(String value, int length) {
        return value.length() > length ? value.substring(0, length - 1) + "…" : value;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static MenuItem actionItem(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(event -> invokeOnSwing(action));
        return item;
    }

    private static void invokeOnSwing(Runnable action) {
        SwingUtilities.invokeLater(action);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static BufferedImage createTrayImage(Image source) {
        int size = 32;
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setClip(new RoundRectangle2D.Double(2, 2, size - 4, size - 4, 10, 10));
        if (source != null) {
            graphics.drawImage(source, 0, 0, size, size, null);
        }
        graphics.setClip(null);
        graphics.setColor(new java.awt.Color(255, 255, 255, 72));
        graphics.drawRoundRect(2, 2, size - 4, size - 4, 10, 10);
        graphics.dispose();
        return result;
    }

    enum Control {
        SHUFFLE, PREVIOUS, PLAY, NEXT, REPEAT
    }

    static final class TransportButton extends JButton {
        private final Control kind;
        private boolean active;
        private boolean playing;

        TransportButton(Control kind) {
            this.kind = kind;
            setPreferredSize(new Dimension(kind == Control.PLAY ? 48 : 44, 48));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
        }

        void setActive(boolean active) {
            if (this.active != active) {
                this.active = active;
                repaint();
            }
        }

        void setPlaying(boolean playing) {
            if (this.playing != playing) {
                this.playing = playing;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (getModel().isRollover()) {
                g.setColor(new Color(255, 255, 255, 24));
                g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 12, 12);
            }
            g.translate(getWidth() / 2, getHeight() / 2);
            if (kind == Control.PLAY) {
                g.setColor(getModel().isPressed() ? new Color(210, 210, 210) : Color.WHITE);
                g.fillOval(-21, -21, 42, 42);
                g.setColor(Color.BLACK);
                if (playing) {
                    g.fillRoundRect(-6, -9, 5, 18, 2, 2);
                    g.fillRoundRect(2, -9, 5, 18, 2, 2);
                } else {
                    Path2D triangle = new Path2D.Double();
                    triangle.moveTo(-4, -10);
                    triangle.lineTo(10, 0);
                    triangle.lineTo(-4, 10);
                    triangle.closePath();
                    g.fill(triangle);
                }
            } else {
                g.setColor(active ? new Color(33, 215, 113) : new Color(188, 188, 192));
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (kind) {
                    case PREVIOUS -> {
                        g.fillRect(-11, -9, 3, 18);
                        Path2D shape = new Path2D.Double();
                        shape.moveTo(-7, 0);
                        shape.lineTo(8, -10);
                        shape.lineTo(8, 10);
                        shape.closePath();
                        g.fill(shape);
                    }
                    case NEXT -> {
                        g.fillRect(8, -9, 3, 18);
                        Path2D shape = new Path2D.Double();
                        shape.moveTo(7, 0);
                        shape.lineTo(-8, -10);
                        shape.lineTo(-8, 10);
                        shape.closePath();
                        g.fill(shape);
                    }
                    case SHUFFLE -> {
                        Path2D upper = new Path2D.Double();
                        upper.moveTo(-11, 7);
                        upper.lineTo(-7, 7);
                        upper.curveTo(-2, 7, 2, -7, 8, -7);
                        upper.lineTo(11, -7);
                        g.draw(upper);
                        Path2D lower = new Path2D.Double();
                        lower.moveTo(-11, -7);
                        lower.lineTo(-7, -7);
                        lower.curveTo(-2, -7, 2, 7, 8, 7);
                        lower.lineTo(11, 7);
                        g.draw(lower);
                        g.drawLine(7, -11, 11, -7);
                        g.drawLine(7, -3, 11, -7);
                        g.drawLine(7, 3, 11, 7);
                        g.drawLine(7, 11, 11, 7);
                    }
                    case REPEAT -> {
                        g.drawArc(-10, -9, 20, 18, 32, 150);
                        g.drawArc(-10, -9, 20, 18, 212, 150);
                        g.drawLine(-11, -5, -11, -10);
                        g.drawLine(-11, -10, -6, -9);
                        g.drawLine(11, 5, 11, 10);
                        g.drawLine(11, 10, 6, 9);
                        if (active) {
                            g.setFont(new Font("Dialog", Font.BOLD, 9));
                            g.drawString("1", -3, 3);
                        }
                    }
                    default -> {
                    }
                }
            }
            g.dispose();
        }
    }
}
