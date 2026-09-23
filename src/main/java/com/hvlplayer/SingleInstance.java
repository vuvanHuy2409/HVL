package com.hvlplayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Keeps the global shortcut and UI in one process when the app is clicked twice. */
final class SingleInstance implements AutoCloseable {
    private static final int PORT = 48761;

    private final ServerSocket serverSocket;
    private volatile Runnable showAction;

    private SingleInstance(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        Thread serverThread = new Thread(this::listen, "hvl-instance-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    static SingleInstance acquire() {
        try {
            ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getLoopbackAddress());
            return new SingleInstance(server);
        } catch (IOException alreadyRunning) {
            notifyExistingInstance();
            return null;
        }
    }

    void setShowAction(Runnable showAction) {
        this.showAction = showAction;
    }

    private void listen() {
        while (!serverSocket.isClosed()) {
            try (Socket socket = serverSocket.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                reader.readLine();
                Runnable action = showAction;
                if (action != null) {
                    action.run();
                }
            } catch (IOException ignored) {
                // Closing the server socket is the normal shutdown path.
            }
        }
    }

    private static void notifyExistingInstance() {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            writer.println("SHOW");
        } catch (IOException ignored) {
            // A stale port should not prevent the player from being launched later.
        }
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Nothing else is needed during shutdown.
        }
    }
}
