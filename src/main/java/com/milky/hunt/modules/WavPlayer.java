package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;

import javax.sound.sampled.*;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * WAV player (Milky Addon version).
 *
 * Folder:
 * - <.minecraft>/MilkyMod/WavPlayer
 *   where <.minecraft> = MinecraftClient.getInstance().runDirectory
 *
 * Pack-in sample:
 * - Put file at: milky-addon-main/src/main/resources/assets/FlaxenHair.wav
 * - Resource path in jar: assets/FlaxenHair.wav
 * - Extracted to: <folder>/FlaxenHair.wav
 *
 * IMPORTANT behavior:
 * - Ensure folder exists.
 * - If FlaxenHair.wav is missing, extract it into the folder.
 * - Do NOT overwrite FlaxenHair.wav if it already exists.
 * - Do NOT touch other files in the folder.
 *
 * Startup safety:
 * - Do NOT touch filesystem or start playback in onActivate().
 * - Delay folder init + startPlayback to the first activation tick (stable).
 */
public class WavPlayer extends Module {
    // ======================
    // Pack-in WAV info
    // ======================
    private static final String MUSIC_FILE = "FlaxenHair.wav";
    private static final String MUSIC_RESOURCE = "assets/FlaxenHair.wav";

    // Audio state (multi-thread)
    private volatile Clip audioClip = null;
    private volatile Thread audioThread = null;
    private volatile boolean stopRequested = false;

    // Current playing (for HUD)
    private volatile String currentPlaying = "(none)";

    private final Random random = new Random();

    // Decided lazily (first activation tick / or Open button click)
    private volatile Path soundDir = null;

    // Gates: do filesystem init & start playback only after first activation tick (stable)
    private boolean pendingInit = false;
    private boolean pendingStart = false;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> randomSound = sgGeneral.add(new BoolSetting.Builder()
        .name("random-sound")
        .description("Play random WAV files from the folder continuously.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> soundFile = sgGeneral.add(new StringSetting.Builder()
        .name("sound-file")
        .description("Name of the WAV file to play. Ignored if random-sound is enabled.")
        .defaultValue(MUSIC_FILE)
        .visible(() -> !randomSound.get())
        .build()
    );

    private final Setting<Double> volume = sgGeneral.add(new DoubleSetting.Builder()
        .name("volume")
        .description("Volume of the sound (0.0 to 1.0).")
        .defaultValue(0.5)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    public WavPlayer() {
        super(Addon.MilkyModCategory, "wavPlayer", "Plays WAV files (loops). Random mode plays random WAVs forever.");
    }

    // --------- Custom module UI (ONLY the button) ---------

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WButton open = list.add(theme.button("Open WAV Folder")).expandX().widget();
        open.action = () -> {
            // Open button also checks folder existence;
            // ensure folder exists; if FlaxenHair.wav missing -> extract it (no overwrite).
            ensureFolderExistsAndEnsureDefaultWav();

            try {
                Util.getOperatingSystem().open(getSoundDir().toFile());
            } catch (Exception ignored) {}
        };

        return list;
    }

    // --------- Lifecycle ---------

    @Override
    public void onActivate() {
        // DO NOT touch filesystem or start playback here (avoid early startup wrong path).
        pendingInit = true;
        pendingStart = true;
    }

    @Override
    public void onDeactivate() {
        stopPlayback();
        currentPlaying = "(none)";
        pendingInit = false;
        pendingStart = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;

        if (pendingInit) {
            pendingInit = false;
            ensureFolderExistsAndEnsureDefaultWav();
        }

        if (pendingStart) {
            pendingStart = false;
            startPlayback();
        }
    }

    // --------- HUD info ---------

    @Override
    public String getInfoString() {
        String name;
        String time = formatTimeInfo();

        if (randomSound.get()) {
            name = stripWav(currentPlaying);
            if (name.isEmpty() || "(none)".equalsIgnoreCase(name)) name = "(none)";
            return "random " + name + time;
        } else {
            String f = soundFile.get();
            if (f == null) f = "";
            name = stripWav(f.trim());
            if (name.isEmpty()) name = "(no file)";
            return name + time;
        }
    }

    private String formatTimeInfo() {
        Clip c = audioClip;
        if (c == null) return "";
        try {
            if (!c.isOpen()) return "";

            long posUs = c.getMicrosecondPosition();
            long lenUs = c.getMicrosecondLength();
            if (lenUs <= 0) return "";

            return " " + fmtMmSs(posUs) + "/" + fmtMmSs(lenUs);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String fmtMmSs(long microseconds) {
        long totalSeconds = Math.max(0, microseconds / 1_000_000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private String stripWav(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.toLowerCase().endsWith(".wav")) return s.substring(0, s.length() - 4);
        return s;
    }

    // --------- Folder resolution ---------

    private Path getSoundDir() {
        Path dir = soundDir;
        if (dir != null) return dir;

        Path base = null;
        try {
            File runDir = MinecraftClient.getInstance().runDirectory;
            if (runDir != null) base = runDir.toPath();
        } catch (Throwable ignored) {}

        if (base == null) {
            try {
                base = FabricLoader.getInstance().getGameDir();
            } catch (Throwable ignored) {}
        }

        if (base == null) base = Path.of(".");

        dir = base.resolve("MilkyMod").resolve("WavPlayer").toAbsolutePath().normalize();
        soundDir = dir;
        return dir;
    }

    /**
     * Behavior:
     * - Ensure folder exists (create if missing).
     * - If FlaxenHair.wav is missing, extract it.
     * - Do NOT overwrite if it exists.
     * - Do NOT touch other files.
     */
    private void ensureFolderExistsAndEnsureDefaultWav() {
        try {
            Path dir = getSoundDir();
            Files.createDirectories(dir);

            Path target = dir.resolve(MUSIC_FILE);
            if (!Files.exists(target)) {
                extractMusicIfMissing(target);
            }
        } catch (Exception ignored) {}
    }

    private void extractMusicIfMissing(Path targetFile) {
        // Only called when targetFile does not exist.
        try (InputStream in = WavPlayer.class.getClassLoader().getResourceAsStream(MUSIC_RESOURCE)) {
            if (in == null) return; // resource not packaged correctly
            Files.copy(in, targetFile); // no overwrite
        } catch (Exception ignored) {}
    }

    // --------- Playback ---------

    private void startPlayback() {
        stopPlayback();

        // Folder init happens only in:
        // - first activation tick
        // - Open WAV Folder button

        if (randomSound.get()) {
            List<String> wavs = findWavFilesNoCreate();
            stopRequested = false;

            audioThread = new Thread(() -> runRandomLoop(wavs), "WavPlayer-Audio");
            audioThread.setDaemon(true);
            try { audioThread.start(); } catch (Exception ignored) {}

        } else {
            String f = soundFile.get();
            if (f == null) return;
            f = f.trim();

            // Be forgiving: append .wav if missing
            if (!f.isEmpty() && !f.toLowerCase().endsWith(".wav")) f = f + ".wav";
            if (!f.toLowerCase().endsWith(".wav")) return;

            Path p = getSoundDir().resolve(f);
            if (!Files.exists(p)) return;

            currentPlaying = f;
            stopRequested = false;

            final String fixedFile = f;
            audioThread = new Thread(() -> runFixedLoop(fixedFile), "WavPlayer-Audio");
            audioThread.setDaemon(true);
            try { audioThread.start(); } catch (Exception ignored) {}
        }
    }

    private List<String> findWavFilesNoCreate() {
        List<String> wavFiles = new ArrayList<>();
        try {
            Path dir = getSoundDir();
            if (!Files.exists(dir)) return wavFiles;

            try (var stream = Files.list(dir)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase().endsWith(".wav"))
                    .forEach(path -> wavFiles.add(path.getFileName().toString()));
            }
        } catch (Exception ignored) {}
        return wavFiles;
    }

    private String getRandomWavFile(List<String> cached) {
        if (cached == null || cached.isEmpty()) return null;
        return cached.get(random.nextInt(cached.size()));
    }

    // --------- Playback loops ---------

    private void runFixedLoop(String file) {
        Path soundPath = getSoundDir().resolve(file);

        AudioInputStream audioInputStream = null;
        Clip clip = null;

        try {
            audioInputStream = AudioSystem.getAudioInputStream(soundPath.toFile());
            clip = AudioSystem.getClip();
            clip.open(audioInputStream);

            audioClip = clip;
            applyVolume(clip);

            clip.loop(Clip.LOOP_CONTINUOUSLY);
            while (!stopRequested && clip.isOpen()) {
                Thread.sleep(100);
            }

        } catch (InterruptedException ignored) {
        } catch (Exception ignored) {
        } finally {
            cleanupClipAndStream(clip, audioInputStream);
            if (audioClip == clip) audioClip = null;
        }
    }

    private void runRandomLoop(List<String> cachedWavs) {
        while (!stopRequested) {
            String file = getRandomWavFile(cachedWavs);
            if (file == null) {
                currentPlaying = "(none)";
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                continue;
            }

            currentPlaying = file;

            Path soundPath = getSoundDir().resolve(file);
            if (!Files.exists(soundPath)) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                continue;
            }

            AudioInputStream audioInputStream = null;
            Clip clip = null;

            try {
                audioInputStream = AudioSystem.getAudioInputStream(soundPath.toFile());
                clip = AudioSystem.getClip();
                clip.open(audioInputStream);

                audioClip = clip;
                applyVolume(clip);

                clip.start();

                long lenUs = clip.getMicrosecondLength();

                // Grace period: allow backend to start advancing position
                long graceDeadline = System.currentTimeMillis() + 500;
                while (!stopRequested && clip.isOpen()
                    && (clip.getMicrosecondPosition() == 0)
                    && System.currentTimeMillis() < graceDeadline) {
                    Thread.sleep(10);
                }

                while (!stopRequested && clip.isOpen()) {
                    long posUs = clip.getMicrosecondPosition();
                    if (lenUs > 0 && posUs >= lenUs - 20_000) break;
                    Thread.sleep(50);
                }

            } catch (InterruptedException ignored) {
            } catch (Exception ignored) {
            } finally {
                cleanupClipAndStream(clip, audioInputStream);
                if (audioClip == clip) audioClip = null;
            }

            if (!stopRequested) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void applyVolume(Clip clip) {
        try {
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float dB = (float) (Math.log(Math.max(0.0001, volume.get())) / Math.log(10.0) * 20.0);
                gainControl.setValue(Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB)));
            }
        } catch (Exception ignored) {}
    }

    private void cleanupClipAndStream(Clip clip, AudioInputStream audioInputStream) {
        try {
            if (clip != null) {
                try { clip.stop(); } catch (Exception ignored2) {}
                try { clip.close(); } catch (Exception ignored2) {}
            }
        } finally {
            try {
                if (audioInputStream != null) audioInputStream.close();
            } catch (Exception ignored2) {}
        }
    }

    private void stopPlayback() {
        stopRequested = true;

        Thread t = audioThread;
        if (t != null && t.isAlive()) {
            try { t.interrupt(); } catch (Exception ignored) {}
        }
        audioThread = null;

        Clip c = audioClip;
        audioClip = null;

        if (c != null) {
            Thread closer = new Thread(() -> {
                try { c.stop(); } catch (Exception ignored) {}
                try { c.close(); } catch (Exception ignored) {}
            }, "WavPlayer-Audio-Closer");
            closer.setDaemon(true);
            try { closer.start(); } catch (Exception ignored) {}
        }
    }
}
