package com.hvlplayer;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.JRootPane;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.JComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;

/** The compact Liquid Glass-style HVL player window. */
final class PlayerWindow {
    private static final int ROW_HEIGHT = 58;
    private static final Color WINDOW_BACKGROUND = Color.BLACK;
    private static final Color SURFACE = new Color(17, 17, 17);
    private static final Color SURFACE_ALT = new Color(28, 28, 28);
    private static final Color BORDER = new Color(255, 255, 255, 54);
    private static final Color SELECTED = new Color(255, 255, 255, 34);
    private static final Color SELECTED_TEXT = Color.WHITE;
    private static final Color ACCENT = Color.WHITE;
    private static final Color TEXT = Color.WHITE;
    private static final Color MUTED = new Color(202, 202, 202);
    private static final Color SUBTLE = new Color(145, 145, 145);
    private static final Font UI_FONT = new Font("Helvetica Neue", Font.PLAIN, 13);
    private static final Font UI_FONT_BOLD = new Font("Helvetica Neue", Font.BOLD, 13);

    private final JFrame frame;
    private final DefaultListModel<Track> listModel = new DefaultListModel<>();
    private final JList<Track> playlist = new JList<>(listModel);
    private final TrackRenderer trackRenderer = new TrackRenderer();
    private final SmoothScrollPane playlistScrollPane = new SmoothScrollPane(playlist);
    private final ArtworkLabel cover = new ArtworkLabel();
    private final JLabel nowPlayingState = new JLabel("ĐÃ CHỌN");
    private final JLabel title = new JLabel("Chưa chọn bài");
    private final JLabel artist = new JLabel("—");
    private final JLabel album = new JLabel("—");
    private final JLabel libraryLabel = new JLabel("Đang đọc thư viện…");
    private final JLabel statusLabel = new JLabel("Đang chuẩn bị…");
    private final JLabel elapsedLabel = new JLabel("0:00");
    private final JLabel durationLabel = new JLabel("--:--");
    private final SeekSlider progress = new SeekSlider();
    private final MenuBarIcon.TransportButton shuffleButton =
            new MenuBarIcon.TransportButton(MenuBarIcon.Control.SHUFFLE);
    private final MenuBarIcon.TransportButton previousButton =
            new MenuBarIcon.TransportButton(MenuBarIcon.Control.PREVIOUS);
    private final MenuBarIcon.TransportButton playButton =
            new MenuBarIcon.TransportButton(MenuBarIcon.Control.PLAY);
    private final MenuBarIcon.TransportButton nextButton =
            new MenuBarIcon.TransportButton(MenuBarIcon.Control.NEXT);
    private final MenuBarIcon.TransportButton repeatButton =
            new MenuBarIcon.TransportButton(MenuBarIcon.Control.REPEAT);
    private final Random random = new Random();
    private final AudioEngine engine;
    private final MenuBarIcon menuBarIcon;
    private final MacMediaKeys mediaKeys;
    private final Timer timer;

    private List<Track> tracks = List.of();
    private Map<Track, ImageIcon> coverIcons = Map.of();
    private int currentIndex = -1;
    private int displayedIndex = -1;
    private boolean shuffle;
    private boolean repeatOne;
    private boolean userSeeking;
    private boolean updatingProgress;
    private Runnable quitAction = () -> {
    };

    PlayerWindow(boolean background) {
        frame = new JFrame("HVL");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(500, 600));
        frame.setSize(560, 700);
        frame.setLocationByPlatform(true);
        frame.setBackground(WINDOW_BACKGROUND);
        BufferedImage appIcon = createAppIcon();
        frame.setIconImage(appIcon);

        engine = new AudioEngine(this::songFinished);
        mediaKeys = new MacMediaKeys(this::handleMediaCommand);
        menuBarIcon = new MenuBarIcon(
                appIcon,
                this::showWindow,
                this::playOrPause,
                this::previous,
                () -> next(true),
                this::toggleShuffle,
                this::toggleRepeat,
                this::quitFromMenuBar,
                this::trayNowPlaying,
                this::trayPlayLabel,
                this::trayModeLabel,
                () -> repeatOne);
        timer = new Timer(250, event -> refreshProgress());
        timer.start();

        buildUi();
        installActions();
        if (!background) {
            frame.setVisible(false);
        }
    }

    void setQuitAction(Runnable quitAction) {
        this.quitAction = quitAction;
    }

    void startMenuBarIcon() {
        menuBarIcon.start();
        menuBarIcon.refresh();
        mediaKeys.start();
    }

    void stopMenuBarIcon() {
        mediaKeys.stop();
        menuBarIcon.stop();
    }

    void stopPlayback() {
        engine.stop();
        mediaKeys.update(null, false, false, 0);
        timer.stop();
    }

    void showWindow() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::showWindow);
            return;
        }
        frame.setVisible(true);
        if (frame.getExtendedState() == JFrame.ICONIFIED) {
            frame.setExtendedState(JFrame.NORMAL);
        }
        frame.toFront();
        frame.requestFocus();
    }

    void hideWindow() {
        frame.setVisible(false);
    }

    void loadLibrary() {
        Path musicDirectory = MusicLibrary.locateMusicDirectory();
        setStatus("Đang đọc metadata và ảnh bìa…");
        new SwingWorker<LoadedLibrary, Void>() {
            @Override
            protected LoadedLibrary doInBackground() throws Exception {
                List<Track> loaded = MusicLibrary.load(musicDirectory);
                Map<Track, ImageIcon> covers = new IdentityHashMap<>();
                Map<Track, ImageIcon> thumbnails = new IdentityHashMap<>();
                for (Track track : loaded) {
                    BufferedImage artwork = decodeArtwork(track.artwork());
                    covers.put(track, new ImageIcon(artwork == null
                            ? createPlaceholderCover(148, 148) : cropAndScale(artwork, 148, 148)));
                    thumbnails.put(track, new ImageIcon(artwork == null
                            ? createPlaceholderCover(34, 34) : cropAndScale(artwork, 34, 34)));
                }
                return new LoadedLibrary(loaded, covers, thumbnails);
            }

            @Override
            protected void done() {
                try {
                    LoadedLibrary loaded = get();
                    coverIcons = loaded.covers();
                    trackRenderer.setThumbnails(loaded.thumbnails());
                    setTracks(loaded.tracks());
                    setStatus("Chọn bài hát • icon HVL trên thanh menu để điều khiển nhanh");
                } catch (Exception exception) {
                    setStatus("Không đọc được thư viện nhạc");
                    libraryLabel.setText("Kiểm tra thư mục nhạc trong app");
                }
            }
        }.execute();
    }

    private void buildUi() {
        JPanel root = new LiquidBackground(new BorderLayout(0, 11));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildMainContent(), BorderLayout.CENTER);
        root.add(buildPlayerBar(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setJMenuBar(createMenuBar());
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);

        JPanel brandBlock = new JPanel();
        brandBlock.setOpaque(false);
        brandBlock.setLayout(new BoxLayoutCompat(brandBlock));
        JLabel brand = label("HVL", 25, TEXT, true);
        JLabel subtitle = label("THƯ VIỆN NHẠC", 10, MUTED, true);
        brandBlock.add(brand);
        brandBlock.add(javax.swing.Box.createVerticalStrut(1));
        brandBlock.add(subtitle);
        header.add(brandBlock, BorderLayout.WEST);

        JPanel headerMeta = new JPanel();
        headerMeta.setOpaque(false);
        headerMeta.setLayout(new BoxLayoutCompat(headerMeta));
        libraryLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        styleLabel(libraryLabel, 12, TEXT, true);
        JLabel shortcut = label("Icon HVL trên thanh menu để điều khiển nhanh", 11, SUBTLE, false);
        shortcut.setAlignmentX(Component.RIGHT_ALIGNMENT);
        headerMeta.add(libraryLabel);
        headerMeta.add(javax.swing.Box.createVerticalStrut(4));
        headerMeta.add(shortcut);
        header.add(headerMeta, BorderLayout.EAST);
        return header;
    }

    private JPanel buildMainContent() {
        JPanel content = new JPanel(new BorderLayout(0, 11));
        content.setOpaque(false);
        content.add(buildNowPlayingCard(), BorderLayout.NORTH);
        content.add(buildPlaylistCard(), BorderLayout.CENTER);
        return content;
    }

    private JPanel buildNowPlayingCard() {
        RoundedPanel nowPlaying = new RoundedPanel(SURFACE, BORDER, 18);
        nowPlaying.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        nowPlaying.setLayout(new BorderLayout(15, 0));

        cover.setPreferredSize(new Dimension(148, 148));
        cover.setMinimumSize(new Dimension(148, 148));
        cover.setHorizontalAlignment(SwingConstants.CENTER);
        cover.setVerticalAlignment(SwingConstants.CENTER);
        cover.setIcon(new javax.swing.ImageIcon(createPlaceholderCover(148, 148)));
        nowPlaying.add(cover, BorderLayout.WEST);

        JPanel trackInfo = new JPanel();
        trackInfo.setOpaque(false);
        trackInfo.setLayout(new BoxLayoutCompat(trackInfo));
        nowPlayingState.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        artist.setAlignmentX(Component.LEFT_ALIGNMENT);
        album.setAlignmentX(Component.LEFT_ALIGNMENT);
        styleLabel(nowPlayingState, 10, ACCENT, true);
        styleLabel(title, 21, TEXT, true);
        styleLabel(artist, 14, MUTED, false);
        styleLabel(album, 12, SUBTLE, false);
        trackInfo.add(nowPlayingState);
        trackInfo.add(javax.swing.Box.createVerticalStrut(11));
        trackInfo.add(title);
        trackInfo.add(javax.swing.Box.createVerticalStrut(6));
        trackInfo.add(artist);
        trackInfo.add(javax.swing.Box.createVerticalStrut(4));
        trackInfo.add(album);
        trackInfo.add(javax.swing.Box.createVerticalGlue());
        JLabel hint = label("Chọn một bài trong danh sách để nghe", 11, SUBTLE, false);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        trackInfo.add(hint);
        nowPlaying.add(trackInfo, BorderLayout.CENTER);
        return nowPlaying;
    }

    private JPanel buildPlaylistCard() {
        RoundedPanel listCard = new RoundedPanel(SURFACE, BORDER, 18);
        listCard.setLayout(new BorderLayout());

        JPanel listHeader = new JPanel(new BorderLayout(12, 0));
        listHeader.setOpaque(false);
        listHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(11, 14, 9, 14)));
        JLabel listTitle = label("Danh sách bài hát", 15, TEXT, true);
        JLabel listHint = label("Nhấp chọn • nhấp đúp phát", 11, SUBTLE, false);
        listHeader.add(listTitle, BorderLayout.WEST);
        listHeader.add(listHint, BorderLayout.EAST);
        listCard.add(listHeader, BorderLayout.NORTH);

        JPanel tableHeader = new JPanel(new BorderLayout(12, 0));
        tableHeader.setOpaque(false);
        tableHeader.setBorder(BorderFactory.createEmptyBorder(8, 14, 5, 14));
        JLabel numberHeader = label("#", 10, SUBTLE, true);
        numberHeader.setHorizontalAlignment(SwingConstants.CENTER);
        numberHeader.setPreferredSize(new Dimension(72, 14));
        JLabel songHeader = label("BÀI HÁT", 10, SUBTLE, true);
        JLabel albumHeader = label("ALBUM", 10, SUBTLE, true);
        albumHeader.setPreferredSize(new Dimension(112, 14));
        JLabel lengthHeader = label("THỜI LƯỢNG", 10, SUBTLE, true);
        lengthHeader.setHorizontalAlignment(SwingConstants.RIGHT);
        lengthHeader.setPreferredSize(new Dimension(56, 14));
        tableHeader.add(numberHeader, BorderLayout.WEST);
        tableHeader.add(songHeader, BorderLayout.CENTER);
        JPanel rightHeaders = new JPanel(new BorderLayout(12, 0));
        rightHeaders.setOpaque(false);
        rightHeaders.setPreferredSize(new Dimension(180, 14));
        rightHeaders.add(albumHeader, BorderLayout.WEST);
        rightHeaders.add(lengthHeader, BorderLayout.EAST);
        tableHeader.add(rightHeaders, BorderLayout.EAST);

        JPanel tableArea = new JPanel(new BorderLayout());
        tableArea.setOpaque(false);
        tableArea.add(tableHeader, BorderLayout.NORTH);
        playlist.setBackground(SURFACE);
        playlist.setForeground(TEXT);
        playlist.setSelectionBackground(SELECTED);
        playlist.setSelectionForeground(SELECTED_TEXT);
        playlist.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playlist.setFixedCellHeight(ROW_HEIGHT);
        playlist.setCellRenderer(trackRenderer);
        playlist.setBorder(BorderFactory.createEmptyBorder(0, 8, 5, 8));
        playlist.setFocusable(true);
        JScrollPane scrollPane = playlistScrollPane;
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBackground(SURFACE);
        scrollPane.getViewport().setBackground(SURFACE);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        tableArea.add(scrollPane, BorderLayout.CENTER);
        listCard.add(tableArea, BorderLayout.CENTER);
        return listCard;
    }

    private JPanel buildPlayerBar() {
        RoundedPanel playerBar = new RoundedPanel(SURFACE, BORDER, 16);
        playerBar.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        playerBar.setLayout(new BorderLayout(0, 8));

        JPanel progressArea = new JPanel(new BorderLayout(0, 5));
        progressArea.setOpaque(false);
        progress.setPreferredSize(new Dimension(10, 16));
        progress.setToolTipText("Kéo hoặc bấm để tua đến vị trí mong muốn");
        JPanel timeRow = new JPanel(new BorderLayout());
        timeRow.setOpaque(false);
        styleLabel(elapsedLabel, 11, MUTED, false);
        styleLabel(durationLabel, 11, MUTED, false);
        timeRow.add(elapsedLabel, BorderLayout.WEST);
        timeRow.add(durationLabel, BorderLayout.EAST);
        progressArea.add(progress, BorderLayout.NORTH);
        progressArea.add(timeRow, BorderLayout.SOUTH);
        playerBar.add(progressArea, BorderLayout.NORTH);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        controls.setOpaque(false);
        labelControl(shuffleButton, "Phát ngẫu nhiên");
        labelControl(previousButton, "Bài trước");
        labelControl(playButton, "Phát bài đang chọn / tạm dừng");
        labelControl(nextButton, "Bài tiếp theo");
        labelControl(repeatButton, "Lặp lại bài hiện tại");
        controls.add(shuffleButton);
        controls.add(previousButton);
        controls.add(playButton);
        controls.add(nextButton);
        controls.add(repeatButton);
        playerBar.add(controls, BorderLayout.CENTER);

        styleLabel(statusLabel, 11, MUTED, false);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        playerBar.add(statusLabel, BorderLayout.SOUTH);
        return playerBar;
    }

    private static void labelControl(MenuBarIcon.TransportButton button, String label) {
        button.setToolTipText(label);
        button.getAccessibleContext().setAccessibleName(label);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu menu = new JMenu("HVL");
        JMenuItem hide = new JMenuItem("Ẩn cửa sổ");
        hide.addActionListener(event -> hideWindow());
        JMenuItem quit = new JMenuItem("Thoát HVL");
        quit.addActionListener(event -> quitAction.run());
        menu.add(hide);
        menu.add(new JSeparator());
        menu.add(quit);
        menuBar.add(menu);
        return menuBar;
    }

    private void installActions() {
        previousButton.addActionListener(event -> previous());
        nextButton.addActionListener(event -> next(true));
        playButton.addActionListener(event -> playOrPause());
        shuffleButton.addActionListener(event -> toggleShuffle());
        repeatButton.addActionListener(event -> toggleRepeat());
        playlist.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectionChanged();
            }
        });
        progress.addChangeListener(event -> {
            if (userSeeking && !updatingProgress) {
                updateSeekPreview();
            }
        });
        progress.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (canSeek()) {
                    userSeeking = true;
                    progress.setValueIsAdjusting(true);
                    setProgressFromMouse(event);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (userSeeking) {
                    setProgressFromMouse(event);
                    progress.setValueIsAdjusting(false);
                    userSeeking = false;
                    seekToSliderPosition();
                }
            }
        });
        progress.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                if (userSeeking) {
                    setProgressFromMouse(event);
                }
            }
        });
        playlist.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = playlist.locationToIndex(event.getPoint());
                boolean onTrack = index >= 0 && playlist.getCellBounds(index, index) != null
                        && playlist.getCellBounds(index, index).contains(event.getPoint());
                if (event.getClickCount() == 2 && onTrack) {
                    playlist.setSelectedIndex(index);
                    playSelected();
                }
            }
        });
        playlist.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "play-selected");
        playlist.getActionMap().put("play-selected", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                playSelected();
            }
        });

        JRootPane root = frame.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("SPACE"), "toggle-play");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("RIGHT"), "next-song");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("LEFT"), "previous-song");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("meta W"), "hide-window");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("meta Q"), "quit-app");
        root.getActionMap().put("toggle-play", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                playOrPause();
            }
        });
        root.getActionMap().put("next-song", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                next(true);
            }
        });
        root.getActionMap().put("previous-song", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                previous();
            }
        });
        root.getActionMap().put("hide-window", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                hideWindow();
            }
        });
        root.getActionMap().put("quit-app", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                quitAction.run();
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                hideWindow();
            }
        });
    }

    private void setTracks(List<Track> loadedTracks) {
        tracks = loadedTracks;
        listModel.clear();
        for (Track track : tracks) {
            listModel.addElement(track);
        }
        libraryLabel.setText("%d bài hát".formatted(tracks.size()));
        if (!tracks.isEmpty()) {
            currentIndex = 0;
            displayedIndex = 0;
            playlist.setSelectedIndex(0);
            updateTrackInfo(tracks.get(0));
            refreshProgress();
        } else {
            currentIndex = -1;
            displayedIndex = -1;
            setStatus("Chưa có file nhạc trong app");
        }
    }

    private void selectionChanged() {
        int selected = playlist.getSelectedIndex();
        if (selected < 0 || selected >= tracks.size()) {
            return;
        }
        Track selectedTrack = tracks.get(selected);
        boolean playbackActive = engine.isPlaying() || engine.isPaused();
        if (!playbackActive || selected == currentIndex) {
            displayedIndex = selected;
            updateTrackInfo(selectedTrack);
        }
        if (selected != currentIndex) {
            setStatus("Đã chọn “%s” • nhấn Phát để nghe".formatted(selectedTrack.title()));
        } else if (!playbackActive) {
            setStatus("Đã chọn bài hát • nhấp đúp để phát ngay");
        }
        refreshProgress();
    }

    private void playSelected() {
        int selected = playlist.getSelectedIndex();
        if (selected >= 0 && selected < tracks.size()) {
            playAt(selected);
        }
    }

    private void playOrPause() {
        int selected = playlist.getSelectedIndex();
        if (selected >= 0 && selected != currentIndex) {
            playAt(selected);
            return;
        }
        if (currentIndex < 0 && !tracks.isEmpty()) {
            playAt(selected >= 0 ? selected : 0);
            return;
        }
        if (!engine.isPlaying() && !engine.isPaused() && currentIndex >= 0) {
            playAt(currentIndex);
            return;
        }
        try {
            engine.togglePause();
            setStatus(engine.isPaused() ? "Đã tạm dừng" : "Đang phát");
        } catch (IOException exception) {
            setStatus("Không thể điều khiển phát nhạc");
        }
        refreshProgress();
    }

    private void handleMediaCommand(int command) {
        switch (command) {
            case MacMediaKeys.PREVIOUS -> previous();
            case MacMediaKeys.NEXT -> next(true);
            case MacMediaKeys.TOGGLE, MacMediaKeys.PLAY, MacMediaKeys.PAUSE -> {
                boolean playing = engine.isPlaying();
                boolean paused = engine.isPaused();
                if (command == MacMediaKeys.PLAY && playing
                        || command == MacMediaKeys.PAUSE && !playing) {
                    return;
                }
                if (!playing && !paused) {
                    if (command != MacMediaKeys.PAUSE) {
                        playAt(currentIndex >= 0 ? currentIndex : 0);
                    }
                    return;
                }
                try {
                    engine.togglePause();
                    setStatus(engine.isPaused() ? "Đã tạm dừng" : "Đang phát");
                } catch (IOException exception) {
                    setStatus("Không thể điều khiển phát nhạc");
                }
                refreshProgress();
            }
            default -> {
            }
        }
    }

    private void playAt(int index) {
        if (index < 0 || index >= tracks.size()) {
            return;
        }
        Track track = tracks.get(index);
        try {
            engine.play(track);
            currentIndex = index;
            displayedIndex = index;
            playlist.setSelectedIndex(index);
            playlistScrollPane.stopAnimation();
            playlist.ensureIndexIsVisible(index);
            updateTrackInfo(track);
            setStatus("Đang phát");
            refreshProgress();
        } catch (IOException exception) {
            setStatus("Không thể phát file này");
        }
    }

    private void previous() {
        if (currentIndex < 0 || tracks.isEmpty()) {
            return;
        }
        int previousIndex = currentIndex - 1;
        if (previousIndex < 0) {
            previousIndex = tracks.size() - 1;
        }
        playAt(previousIndex);
    }

    private void next(boolean wrapAtEnd) {
        if (tracks.isEmpty()) {
            return;
        }
        if (currentIndex < 0) {
            playAt(0);
            return;
        }
        int nextIndex = chooseNextIndex(wrapAtEnd);
        if (nextIndex >= 0) {
            playAt(nextIndex);
        }
    }

    private int chooseNextIndex(boolean wrapAtEnd) {
        if (tracks.size() == 1) {
            return shuffle || wrapAtEnd ? 0 : -1;
        }
        if (shuffle) {
            int nextIndex;
            do {
                nextIndex = random.nextInt(tracks.size());
            } while (nextIndex == currentIndex);
            return nextIndex;
        }
        int nextIndex = currentIndex + 1;
        return nextIndex < tracks.size() ? nextIndex : (wrapAtEnd ? 0 : -1);
    }

    private void songFinished(Track finishedTrack) {
        if (currentIndex < 0 || tracks.isEmpty() || engine.track() != finishedTrack) {
            return;
        }
        int nextIndex = repeatOne ? currentIndex : chooseNextIndex(false);
        if (nextIndex >= 0) {
            playAt(nextIndex);
        } else {
            setStatus("Đã phát hết danh sách");
            refreshProgress();
        }
    }

    private void updateTrackInfo(Track track) {
        title.setText(escapeHtml(track.title()));
        title.setToolTipText(track.title());
        artist.setText(escapeHtml(track.artist()));
        album.setText(escapeHtml(track.album()));
        durationLabel.setText(track.durationText());
        setProgressValue(0);
        elapsedLabel.setText("0:00");
        cover.setIcon(coverIcons.get(track));
    }

    private void refreshProgress() {
        boolean playbackActive = engine.isPlaying() || engine.isPaused();
        int progressIndex = playbackActive ? currentIndex : displayedIndex;
        Track progressTrack = progressIndex >= 0 && progressIndex < tracks.size()
                ? tracks.get(progressIndex)
                : null;
        if (progressTrack == null) {
            playButton.setPlaying(false);
            shuffleButton.setActive(shuffle);
            repeatButton.setActive(repeatOne);
            menuBarIcon.refresh();
            mediaKeys.update(null, false, false, 0);
            return;
        }
        double position = playbackActive ? engine.positionSeconds() : 0;
        long duration = progressTrack.durationSeconds();
        if (duration > 0) {
            setProgressValue((int) Math.min(1000, Math.round(position * 1000 / duration)));
        } else {
            setProgressValue(0);
        }
        elapsedLabel.setText(AudioInfo.formatSeconds(position));
        durationLabel.setText(progressTrack.durationText());
        if (playbackActive) {
            nowPlayingState.setText(engine.isPaused() ? "TẠM DỪNG" : "ĐANG PHÁT");
            nowPlayingState.setForeground(ACCENT);
        } else {
            nowPlayingState.setText("ĐÃ CHỌN");
            nowPlayingState.setForeground(MUTED);
        }
        boolean selectedCurrent = playlist.getSelectedIndex() == currentIndex;
        playButton.setPlaying(engine.isPlaying() && selectedCurrent);
        shuffleButton.setActive(shuffle);
        repeatButton.setActive(repeatOne);
        menuBarIcon.refresh();
        mediaKeys.update(playbackActive ? progressTrack : null,
                engine.isPlaying(), engine.isPaused(), position);
    }

    private boolean canSeek() {
        return currentIndex >= 0 && currentIndex < tracks.size()
                && tracks.get(currentIndex).durationSeconds() > 0
                && (engine.isPlaying() || engine.isPaused());
    }

    private void setProgressValue(int value) {
        updatingProgress = true;
        progress.setValue(Math.max(0, Math.min(1000, value)));
        updatingProgress = false;
    }

    private void setProgressFromMouse(MouseEvent event) {
        int start = 8;
        int end = Math.max(start + 1, progress.getWidth() - 8);
        double fraction = (double) (event.getX() - start) / (end - start);
        progress.setValue((int) Math.round(Math.max(0, Math.min(1, fraction)) * 1000));
        updateSeekPreview();
    }

    private void updateSeekPreview() {
        if (!canSeek()) {
            return;
        }
        Track track = tracks.get(currentIndex);
        double target = track.durationSeconds() * progress.getValue() / 1000.0;
        elapsedLabel.setText(AudioInfo.formatSeconds(target));
    }

    private void seekToSliderPosition() {
        if (!canSeek()) {
            refreshProgress();
            return;
        }
        Track track = tracks.get(currentIndex);
        double target = track.durationSeconds() * progress.getValue() / 1000.0;
        try {
            engine.seekTo(target);
            setStatus("Đã tua đến " + AudioInfo.formatSeconds(target));
        } catch (IOException exception) {
            setStatus("Không thể tua bài hát này");
        }
        refreshProgress();
    }

    private void toggleShuffle() {
        shuffle = !shuffle;
        shuffleButton.setActive(shuffle);
        setStatus(shuffle ? "Đang bật phát ngẫu nhiên" : "Đang phát theo danh sách");
        menuBarIcon.refresh();
    }

    private void toggleRepeat() {
        repeatOne = !repeatOne;
        repeatButton.setActive(repeatOne);
        setStatus(repeatOne ? "Đang lặp lại bài hiện tại" : "Đã tắt lặp lại bài");
        menuBarIcon.refresh();
    }

    private void quitFromMenuBar() {
        quitAction.run();
    }

    private String trayNowPlaying() {
        if (currentIndex >= 0 && currentIndex < tracks.size()) {
            return tracks.get(currentIndex).title();
        }
        return "Chưa chọn bài";
    }

    private String trayPlayLabel() {
        if (engine.isPlaying()) {
            return "Tạm dừng";
        }
        if (engine.isPaused()) {
            return "Tiếp tục";
        }
        return "Phát";
    }

    private String trayModeLabel() {
        return shuffle ? "Ngẫu nhiên" : "Theo danh sách";
    }

    private void setStatus(String value) {
        statusLabel.setText(value);
        statusLabel.setToolTipText(value);
    }

    private static JLabel label(String text, int size, Color color, boolean bold) {
        JLabel result = new JLabel(text);
        styleLabel(result, size, color, bold);
        return result;
    }

    private static void styleLabel(JLabel label, int size, Color color, boolean bold) {
        label.setFont(bold ? UI_FONT_BOLD.deriveFont((float) size) : UI_FONT.deriveFont((float) size));
        label.setForeground(color);
    }

    private static BufferedImage decodeArtwork(byte[] artwork) {
        if (artwork != null && artwork.length > 0) {
            try {
                return ImageIO.read(new ByteArrayInputStream(artwork));
            } catch (IOException ignored) {
                // Fall back to the built-in cover when an embedded image is malformed.
            }
        }
        return null;
    }

    private static BufferedImage cropAndScale(BufferedImage source, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double scale = Math.max((double) width / source.getWidth(), (double) height / source.getHeight());
        int scaledWidth = (int) Math.ceil(source.getWidth() * scale);
        int scaledHeight = (int) Math.ceil(source.getHeight() * scale);
        int x = (width - scaledWidth) / 2;
        int y = (height - scaledHeight) / 2;
        graphics.drawImage(source, x, y, scaledWidth, scaledHeight, null);
        graphics.dispose();
        return result;
    }

    private static BufferedImage roundImage(BufferedImage source, int width, int height, int radius) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.clip(new java.awt.geom.RoundRectangle2D.Double(
                0, 0, Math.max(0, width - 1), Math.max(0, height - 1), radius, radius));
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return result;
    }

    private static BufferedImage createPlaceholderCover(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(18, 18, 18));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(255, 255, 255, 28));
        graphics.fillOval(width / 2 - width / 3, height / 2 - height / 3, width * 2 / 3, height * 2 / 3);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font("Helvetica Neue", Font.BOLD, Math.max(22, width / 4)));
        String text = "HVL";
        int textWidth = graphics.getFontMetrics().stringWidth(text);
        graphics.drawString(text, (width - textWidth) / 2,
                height / 2 + graphics.getFontMetrics().getAscent() / 3);
        graphics.dispose();
        return image;
    }

    private static BufferedImage createAppIcon() {
        try {
            Path coverPath = MusicLibrary.locateMusicDirectory().resolve("cover.jpg");
            if (Files.isRegularFile(coverPath)) {
                BufferedImage cover = ImageIO.read(coverPath.toFile());
                if (cover != null) {
                    return roundImage(cropAndScale(cover, 256, 256), 256, 256, 48);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Fall back to the built-in icon when the source cover is unavailable.
        }
        return roundImage(createPlaceholderCover(256, 256), 256, 256, 48);
    }

    private static String escapeHtml(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return "<html>" + value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>";
    }

    private record LoadedLibrary(
            List<Track> tracks,
            Map<Track, ImageIcon> covers,
            Map<Track, ImageIcon> thumbnails) {
    }

    private static final class SmoothScrollPane extends JScrollPane {
        private final Timer animation;
        private double position;
        private double target;
        private boolean adjustingInternally;

        private SmoothScrollPane(JList<Track> list) {
            super(list);
            animation = new Timer(16, event -> animate());
            setWheelScrollingEnabled(false);
            getVerticalScrollBar().addAdjustmentListener(event -> {
                if (!adjustingInternally && animation.isRunning()) {
                    stopAnimation();
                }
            });
            addMouseWheelListener(event -> {
                JScrollBar bar = getVerticalScrollBar();
                int maximum = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
                if (maximum == 0) {
                    return;
                }
                event.consume();
                if (!animation.isRunning()) {
                    position = bar.getValue();
                    target = position;
                }
                target = Math.max(0, Math.min(maximum,
                        target + event.getPreciseWheelRotation() * ROW_HEIGHT));
                if (!animation.isRunning()) {
                    animation.start();
                }
            });
        }

        private void animate() {
            JScrollBar bar = getVerticalScrollBar();
            int maximum = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
            target = Math.max(0, Math.min(maximum, target));
            position += (target - position) * 0.32;
            if (Math.abs(target - position) < 0.5) {
                position = target;
                animation.stop();
            }
            adjustingInternally = true;
            bar.setValue((int) Math.round(position));
            adjustingInternally = false;
        }

        private void stopAnimation() {
            animation.stop();
        }
    }

    private static final class RoundedPanel extends JPanel {
        private final Color color;
        private final Color borderColor;
        private final int radius;

        private RoundedPanel(Color color, Color borderColor, int radius) {
            this.color = color;
            this.borderColor = borderColor;
            this.radius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = Math.max(0, getWidth() - 1);
            int height = Math.max(0, getHeight() - 1);
            g2.setColor(color);
            g2.fillRoundRect(0, 0, width, height, radius, radius);
            g2.setColor(borderColor);
            g2.drawRoundRect(0, 0, width, height, radius, radius);
            g2.setColor(new Color(255, 255, 255, 22));
            g2.drawRoundRect(1, 1, Math.max(0, width - 2), Math.max(0, height - 2), radius - 2, radius - 2);
            g2.dispose();
        }
    }

    private static final class LiquidBackground extends JPanel {
        private LiquidBackground(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(WINDOW_BACKGROUND);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    private static final class ArtworkLabel extends JLabel {
        private final int radius;

        private ArtworkLabel() {
            this(16);
        }

        private ArtworkLabel(int radius) {
            this.radius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.clip(new java.awt.geom.RoundRectangle2D.Double(0, 0,
                    Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), radius, radius));
            g2.setColor(new Color(255, 255, 255, 16));
            g2.fillRect(0, 0, getWidth(), getHeight());
            super.paintComponent(g2);
            g2.dispose();
        }

        @Override
        protected void paintBorder(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(255, 255, 255, 64));
            g2.drawRoundRect(0, 0, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), radius, radius);
            g2.dispose();
        }
    }

    private static final class SeekSlider extends JSlider {
        private SeekSlider() {
            super(0, 1000, 0);
            setOpaque(false);
            setFocusable(false);
            setBorder(BorderFactory.createEmptyBorder());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int left = 8;
            int right = Math.max(left + 1, getWidth() - 8);
            int centerY = getHeight() / 2;
            int trackHeight = 3;
            int trackY = centerY - trackHeight / 2;
            g2.setColor(new Color(255, 255, 255, 60));
            g2.fillRoundRect(left, trackY, right - left, trackHeight, trackHeight, trackHeight);
            int filled = (int) Math.round((right - left) * getValue() / 1000.0);
            if (filled > 0) {
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(left, trackY, filled, trackHeight, trackHeight, trackHeight);
            }
            int thumbX = left + filled;
            g2.setColor(Color.WHITE);
            g2.fillOval(thumbX - 5, centerY - 5, 10, 10);
            g2.dispose();
        }
    }

    private static final class TrackRenderer extends JPanel implements ListCellRenderer<Track> {
        private final JLabel number = label("", 11, MUTED, false);
        private final ArtworkLabel thumbnail = new ArtworkLabel(10);
        private final JLabel name = label("", 13, TEXT, true);
        private final JLabel artist = label("", 11, MUTED, false);
        private final JLabel album = label("", 11, MUTED, false);
        private final JLabel length = label("", 11, MUTED, false);
        private boolean selected;
        private boolean alternate;
        private Map<Track, ImageIcon> thumbnails = Map.of();

        private void setThumbnails(Map<Track, ImageIcon> thumbnails) {
            this.thumbnails = thumbnails;
        }

        private TrackRenderer() {
            setLayout(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            number.setPreferredSize(new Dimension(34, 10));
            number.setHorizontalAlignment(SwingConstants.CENTER);
            thumbnail.setPreferredSize(new Dimension(34, 34));
            thumbnail.setMinimumSize(new Dimension(34, 34));
            JPanel left = new JPanel(new BorderLayout(8, 0));
            left.setOpaque(false);
            left.add(number, BorderLayout.WEST);
            left.add(thumbnail, BorderLayout.CENTER);
            left.setPreferredSize(new Dimension(72, 34));
            add(left, BorderLayout.WEST);

            JPanel labels = new JPanel(new GridLayout(2, 1, 0, 1));
            labels.setOpaque(false);
            labels.add(name);
            labels.add(artist);
            add(labels, BorderLayout.CENTER);

            JPanel rightInfo = new JPanel(new BorderLayout(12, 0));
            rightInfo.setOpaque(false);
            rightInfo.setPreferredSize(new Dimension(180, 10));
            album.setPreferredSize(new Dimension(112, 10));
            rightInfo.add(album, BorderLayout.WEST);
            length.setPreferredSize(new Dimension(56, 10));
            length.setHorizontalAlignment(SwingConstants.RIGHT);
            rightInfo.add(length, BorderLayout.EAST);
            add(rightInfo, BorderLayout.EAST);
            setOpaque(false);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends Track> list,
                Track value,
                int index,
                boolean selected,
                boolean focused) {
            this.selected = selected;
            this.alternate = index % 2 != 0;
            number.setText(value.trackNumber() > 0 ? "%02d".formatted(value.trackNumber()) : "•");
            thumbnail.setIcon(thumbnails.get(value));
            name.setText(ellipsize(value.title(), 34));
            artist.setText(ellipsize(value.artist(), 28));
            album.setText(ellipsize(value.album(), 20));
            length.setText(value.durationText());
            name.setForeground(selected ? SELECTED_TEXT : TEXT);
            artist.setForeground(selected ? new Color(220, 220, 220) : MUTED);
            number.setForeground(selected ? ACCENT : MUTED);
            album.setForeground(selected ? new Color(220, 220, 220) : MUTED);
            length.setForeground(selected ? new Color(220, 220, 220) : MUTED);
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = selected ? SELECTED : (alternate ? SURFACE_ALT : SURFACE);
            g2.setColor(fill);
            g2.fillRoundRect(2, 1, Math.max(0, getWidth() - 5), Math.max(0, getHeight() - 3), 10, 10);
            if (selected) {
                g2.setColor(ACCENT);
                g2.fillRoundRect(2, 9, 3, Math.max(0, getHeight() - 18), 3, 3);
            }
            g2.dispose();
            super.paintComponent(graphics);
        }

        private static String ellipsize(String value, int max) {
            if (value == null) {
                return "—";
            }
            return value.length() > max ? value.substring(0, Math.max(0, max - 1)) + "…" : value;
        }
    }

    /** Small BoxLayout adapter so the layout reads naturally at call sites. */
    private static final class BoxLayoutCompat extends javax.swing.BoxLayout {
        private BoxLayoutCompat(java.awt.Container target) {
            super(target, javax.swing.BoxLayout.Y_AXIS);
        }

        private BoxLayoutCompat(java.awt.Container target, boolean xAxis) {
            super(target, xAxis ? javax.swing.BoxLayout.X_AXIS : javax.swing.BoxLayout.Y_AXIS);
        }
    }
}
