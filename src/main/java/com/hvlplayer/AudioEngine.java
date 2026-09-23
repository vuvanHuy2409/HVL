package com.hvlplayer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Cross-platform audio controller with lossless FLAC decoding and seek support. */
final class AudioEngine {
    private final Consumer<Track> finishedCallback;
    private boolean nativeMacAudio;
    private Process process;
    private Track track;
    private long generation;
    private long startedAtNanos;
    private double pausedPosition;
    private boolean paused;

    private Thread javaPlaybackThread;
    private SourceDataLine javaLine;
    private boolean javaStopRequested;
    private boolean javaPaused;
    private long javaPlayedFrames;
    private int javaFrameSize;
    private float javaFrameRate;

    AudioEngine(Consumer<Track> finishedCallback) {
        this.finishedCallback = finishedCallback;
        String backend = System.getProperty("hvl.audio.backend", "");
        this.nativeMacAudio = isMac() && !"java".equalsIgnoreCase(backend);
    }

    synchronized void play(Track newTrack) throws IOException {
        stop();
        track = newTrack;
        pausedPosition = 0;
        paused = false;
        javaStopRequested = false;
        javaPaused = false;
        javaPlayedFrames = 0;
        javaFrameSize = 0;
        javaFrameRate = 0;
        long playbackGeneration = ++generation;
        if (nativeMacAudio) {
            playWithAfplay(newTrack, playbackGeneration);
        } else {
            startJavaPlayback(newTrack, playbackGeneration, 0);
        }
    }

    synchronized void seekTo(double seconds) throws IOException {
        if (track == null) {
            return;
        }
        double target = Math.max(0, seconds);
        if (track.durationSeconds() > 0) {
            target = Math.min(target, track.durationSeconds());
        }
        Track currentTrack = track;
        if (nativeMacAudio) {
            boolean wasPaused = paused;
            stop();
            // afplay has no start-offset option. Switch this session to the
            // lossless Java Sound/JFLAC backend so seeking is sample-accurate.
            nativeMacAudio = false;
            track = currentTrack;
            pausedPosition = target;
            javaStopRequested = false;
            javaPaused = wasPaused;
            javaPlayedFrames = 0;
            javaFrameSize = 0;
            javaFrameRate = 0;
            long playbackGeneration = ++generation;
            startJavaPlayback(currentTrack, playbackGeneration, target);
            return;
        }

        boolean wasPaused = javaPaused;
        stop();
        track = currentTrack;
        pausedPosition = target;
        javaStopRequested = false;
        javaPaused = wasPaused;
        javaPlayedFrames = 0;
        javaFrameSize = 0;
        javaFrameRate = 0;
        long playbackGeneration = ++generation;
        double seekTarget = target;
        startJavaPlayback(currentTrack, playbackGeneration, seekTarget);
    }

    private void startJavaPlayback(Track newTrack, long playbackGeneration, double startSeconds) {
        javaPlaybackThread = new Thread(
                () -> playWithJavaSound(newTrack, playbackGeneration, startSeconds),
                "hvl-java-audio");
        javaPlaybackThread.setDaemon(true);
        javaPlaybackThread.start();
    }

    private void playWithAfplay(Track newTrack, long playbackGeneration) throws IOException {
        process = new ProcessBuilder("/usr/bin/afplay", newTrack.path().toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        startedAtNanos = System.nanoTime();
        Process playbackProcess = process;
        Thread monitor = new Thread(
                () -> waitForAfplay(playbackProcess, newTrack, playbackGeneration),
                "hvl-afplay-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    synchronized void togglePause() throws IOException {
        if (nativeMacAudio) {
            toggleAfplayPause();
            return;
        }
        if (javaPlaybackThread == null || !javaPlaybackThread.isAlive()) {
            return;
        }
        if (javaPaused) {
            javaPaused = false;
            if (javaLine != null) {
                javaLine.start();
            }
            notifyAll();
        } else {
            javaPaused = true;
            if (javaLine != null) {
                javaLine.stop();
                javaLine.flush();
            }
        }
    }

    private void toggleAfplayPause() throws IOException {
        if (process == null || !process.isAlive()) {
            return;
        }
        if (paused) {
            sendSignal("-CONT");
            startedAtNanos = System.nanoTime();
            paused = false;
        } else {
            pausedPosition = positionSeconds();
            sendSignal("-STOP");
            paused = true;
        }
    }

    synchronized void stop() {
        generation++;
        Process oldProcess = process;
        boolean wasPaused = paused;
        process = null;
        paused = false;
        pausedPosition = 0;
        if (oldProcess != null && oldProcess.isAlive()) {
            if (wasPaused) {
                sendSignal(oldProcess, "-CONT");
            }
            oldProcess.destroy();
            // Do not hold up Swing while the old player exits. A stopped afplay
            // process may need a moment to handle SIGTERM after SIGCONT.
            CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute(() -> {
                if (oldProcess.isAlive()) {
                    oldProcess.destroyForcibly();
                }
            });
        }

        javaStopRequested = true;
        javaPaused = false;
        Thread oldJavaThread = javaPlaybackThread;
        javaPlaybackThread = null;
        SourceDataLine oldLine = javaLine;
        javaLine = null;
        if (oldLine != null) {
            oldLine.stop();
            oldLine.flush();
            oldLine.close();
        }
        if (oldJavaThread != null) {
            oldJavaThread.interrupt();
        }
        notifyAll();
    }

    synchronized boolean isPlaying() {
        if (nativeMacAudio) {
            return process != null && process.isAlive() && !paused;
        }
        return javaPlaybackThread != null && javaPlaybackThread.isAlive()
                && !javaPaused && !javaStopRequested;
    }

    synchronized boolean isPaused() {
        return nativeMacAudio ? paused : javaPaused;
    }

    synchronized Track track() {
        return track;
    }

    synchronized double positionSeconds() {
        if (track == null) {
            return 0;
        }
        if (!nativeMacAudio) {
            return javaFrameRate > 0 ? javaPlayedFrames / javaFrameRate : 0;
        }
        double position = pausedPosition;
        if (!paused && process != null && process.isAlive()) {
            position += (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
        }
        if (track.durationSeconds() > 0) {
            position = Math.min(position, track.durationSeconds());
        }
        return Math.max(0, position);
    }

    private void playWithJavaSound(Track newTrack, long playbackGeneration, double startSeconds) {
        SourceDataLine line = null;
        boolean completed = false;
        try (AudioInputStream encoded = AudioSystem.getAudioInputStream(newTrack.path().toFile())) {
            AudioFormat sourceFormat = encoded.getFormat();
            AudioFormat pcmFormat = pcmFormat(sourceFormat);
            try (AudioInputStream pcm = sourceFormat.matches(pcmFormat)
                    ? encoded
                    : AudioSystem.getAudioInputStream(pcmFormat, encoded)) {
                DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, pcmFormat);
                line = (SourceDataLine) AudioSystem.getLine(lineInfo);
                line.open(pcmFormat);
                synchronized (this) {
                    if (!isCurrentJavaPlayback(playbackGeneration)) {
                        line.close();
                        return;
                    }
                    javaLine = line;
                    javaFrameSize = Math.max(1, pcmFormat.getFrameSize());
                    javaFrameRate = pcmFormat.getFrameRate();
                }
                long framesToSkip = javaFrameRate > 0
                        ? Math.max(0, Math.round(startSeconds * javaFrameRate))
                        : 0;
                long skippedFrames = skipFrames(pcm, framesToSkip, javaFrameSize, playbackGeneration);
                if (skippedFrames < 0) {
                    return;
                }
                synchronized (this) {
                    if (!isCurrentJavaPlayback(playbackGeneration)) {
                        return;
                    }
                    javaPlayedFrames = skippedFrames;
                }
                line.start();
                byte[] buffer = new byte[64 * 1024];
                int bytesRead;
                while ((bytesRead = pcm.read(buffer)) >= 0) {
                    if (bytesRead == 0) {
                        continue;
                    }
                    int offset = 0;
                    while (offset < bytesRead) {
                        synchronized (this) {
                            while (javaPaused && !javaStopRequested
                                    && playbackGeneration == generation) {
                                try {
                                    wait();
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            if (!isCurrentJavaPlayback(playbackGeneration)) {
                                return;
                            }
                        }
                        int written = line.write(buffer, offset, bytesRead - offset);
                        if (written <= 0) {
                            break;
                        }
                        synchronized (this) {
                            javaPlayedFrames += written / Math.max(1, javaFrameSize);
                        }
                        offset += written;
                    }
                }
                synchronized (this) {
                    if (!isCurrentJavaPlayback(playbackGeneration)) {
                        return;
                    }
                }
                line.drain();
                completed = true;
            }
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException
                 | IllegalArgumentException ignored) {
            // The UI will fall back to its normal stopped state if a Windows audio device fails.
        } finally {
            synchronized (this) {
                if (javaLine == line) {
                    javaLine = null;
                }
                if (javaPlaybackThread == Thread.currentThread()) {
                    javaPlaybackThread = null;
                    javaStopRequested = true;
                }
            }
            if (line != null) {
                line.stop();
                line.close();
            }
        }
        if (completed) {
            SwingUtilities.invokeLater(() -> {
                synchronized (AudioEngine.this) {
                    if (playbackGeneration != generation || track != newTrack) {
                        return;
                    }
                }
                finishedCallback.accept(newTrack);
            });
        }
    }

    private long skipFrames(
            AudioInputStream pcm,
            long framesToSkip,
            int frameSize,
            long playbackGeneration) throws IOException {
        long bytesRemaining = Math.multiplyExact(framesToSkip, Math.max(1, frameSize));
        long bytesSkipped = 0;
        byte[] discard = new byte[64 * 1024];
        while (bytesRemaining > 0) {
            synchronized (this) {
                if (!isCurrentJavaPlayback(playbackGeneration)) {
                    return -1;
                }
            }
            int requested = (int) Math.min(discard.length, bytesRemaining);
            int bytesRead = pcm.read(discard, 0, requested);
            if (bytesRead < 0) {
                break;
            }
            if (bytesRead == 0) {
                continue;
            }
            bytesSkipped += bytesRead;
            bytesRemaining -= bytesRead;
        }
        return bytesSkipped / Math.max(1, frameSize);
    }

    private synchronized boolean isCurrentJavaPlayback(long playbackGeneration) {
        return playbackGeneration == generation && !javaStopRequested
                && javaPlaybackThread == Thread.currentThread();
    }

    private static AudioFormat pcmFormat(AudioFormat sourceFormat) {
        if (AudioFormat.Encoding.PCM_SIGNED.equals(sourceFormat.getEncoding())
                && !sourceFormat.isBigEndian()) {
            return sourceFormat;
        }
        int bits = sourceFormat.getSampleSizeInBits() > 0 ? sourceFormat.getSampleSizeInBits() : 16;
        int bytesPerSample = Math.max(1, (bits + 7) / 8);
        int channels = Math.max(1, sourceFormat.getChannels());
        float sampleRate = sourceFormat.getSampleRate() > 0 ? sourceFormat.getSampleRate() : 44100f;
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, bits, channels,
                channels * bytesPerSample, sampleRate, false);
    }

    private void waitForAfplay(Process playbackProcess, Track finishedTrack, long playbackGeneration) {
        try {
            playbackProcess.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (this) {
            if (playbackGeneration != generation || process != playbackProcess) {
                return;
            }
            process = null;
            paused = false;
            pausedPosition = finishedTrack.durationSeconds();
        }
        SwingUtilities.invokeLater(() -> {
            synchronized (AudioEngine.this) {
                if (playbackGeneration != generation || track != finishedTrack) {
                    return;
                }
            }
            finishedCallback.accept(finishedTrack);
        });
    }

    private void sendSignal(String signal) throws IOException {
        if (process != null) {
            sendSignal(process, signal);
        }
    }

    private void sendSignal(Process target, String signal) {
        try {
            Process signalProcess = new ProcessBuilder("/bin/kill", signal, Long.toString(target.pid()))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            signalProcess.waitFor();
        } catch (IOException ignored) {
            // If a signal is unavailable, normal process controls still handle track changes.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
