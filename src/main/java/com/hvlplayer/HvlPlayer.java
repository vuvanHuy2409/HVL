package com.hvlplayer;

import javax.swing.SwingUtilities;
import java.util.Arrays;

public final class HvlPlayer {
    private HvlPlayer() {
    }

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name",
                System.getProperty("apple.awt.application.name", "HVL"));
        System.setProperty("apple.awt.UIElement",
                System.getProperty("apple.awt.UIElement", "true"));
        System.setProperty("file.encoding", "UTF-8");

        SingleInstance instance = SingleInstance.acquire();
        if (instance == null) {
            return;
        }
        boolean background = Arrays.asList(args).contains("--background");

        SwingUtilities.invokeLater(() -> {
            PlayerWindow window = new PlayerWindow(background);
            instance.setShowAction(window::showWindow);

            window.setQuitAction(() -> {
                window.stopPlayback();
                window.stopMenuBarIcon();
                window.hideWindow();
                instance.close();
                System.exit(0);
            });

            window.startMenuBarIcon();
            window.loadLibrary();
            if (!background) {
                window.showWindow();
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                window.stopPlayback();
                instance.close();
            }, "hvl-shutdown"));
        });
    }
}
