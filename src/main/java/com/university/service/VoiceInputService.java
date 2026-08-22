package com.university.service;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Voice-to-text for the microphone button on the University AI Assistant panel. Captures one
 * utterance from the default microphone and returns the recognized text — nothing here sends
 * anything to Gemini or the database, or auto-submits the result; {@link
 * com.university.controller.AIAssistantPanelController} only drops the text into the existing
 * chat input field, exactly as if the user had typed it.
 *
 * <p>Recognition runs entirely offline through <a href="https://alphacephei.com/vosk/">Vosk</a>
 * (a Kaldi-based speech recognizer with Java/JNA bindings). This replaced an earlier version that
 * shelled out to Windows' built-in {@code System.Speech} dictation grammar — that engine's
 * general-purpose "dictation" mode has no real language model behind it and produced very poor
 * transcriptions for ordinary sentences. Vosk uses an actual trained acoustic + language model,
 * so it does far better on unconstrained English speech, and, being local, needs no API key.</p>
 *
 * <p><b>Setup required:</b> the jar only contains code, not the language model. Download a Vosk
 * English model from https://alphacephei.com/vosk/models (e.g. {@code vosk-model-en-us-0.22} for
 * the best accuracy, or the smaller {@code vosk-model-small-en-us-0.15} for a lighter download),
 * unzip it, and point this service at the resulting folder (the one directly containing {@code
 * am/}, {@code conf/}, {@code graph/}, etc.) via one of, in order:
 * <ol>
 *   <li>JVM system property {@code -Dvosk.model.path=C:\path\to\model}</li>
 *   <li>Environment variable {@code VOSK_MODEL_PATH}</li>
 *   <li>A folder named {@code vosk-model} in the directory the app is launched from</li>
 *   <li>A folder named {@code vosk-model} under {@code %USERPROFILE%\.universitysystem}</li>
 * </ol>
 * Until a model is found, {@link #recognizeSpeech()} reports {@link Status#SERVICE_UNAVAILABLE} —
 * the same status already shown to the user as "Voice input is unavailable right now."
 */
public class VoiceInputService {

    // TEMPORARY — diagnostic logging to collect real RMS numbers for tuning SILENCE_RMS_THRESHOLD
    // etc. against actual mic/room conditions instead of guessing. Safe to delete this Logger and
    // every LOG.info call below once tuning data has been collected; nothing else depends on it.
    private static final Logger LOG = LoggerFactory.getLogger(VoiceInputService.class);

    public enum Status { SUCCESS, NO_MICROPHONE, NO_SPEECH, RECOGNITION_FAILED, SERVICE_UNAVAILABLE }

    public record VoiceResult(Status status, String text) {
        static VoiceResult success(String text) {
            return new VoiceResult(Status.SUCCESS, text);
        }

        static VoiceResult error(Status status) {
            return new VoiceResult(status, null);
        }
    }

    /** Vosk models are trained on 16kHz audio; feeding a different rate hurts accuracy badly. */
    private static final float SAMPLE_RATE = 16000f;

    /** Hard ceiling on one recording, regardless of pauses. Safety backstop only — normal
     *  recordings end via {@link #TRAILING_SILENCE_STOP_MS}, not this. */
    private static final float MAX_UTTERANCE_SECONDS = 30.0f;

    /** 8192 bytes = 2048 samples = ~256ms per read at 16kHz mono. Chosen over a smaller chunk to
     *  halve the number of native {@code acceptWaveForm} calls (and RMS passes) per second of
     *  audio, cutting JNI/JNA marshalling overhead during recording; still fine-grained enough
     *  that {@link #TRAILING_SILENCE_STOP_MS} stays accurate to within one chunk. */
    private static final int READ_BUFFER_BYTES = 8192;

    /** {@link Recognizer#acceptWaveForm} returns {@code true} the moment Vosk's own endpointer
     *  notices *any* pause, including a normal breath between two sentences — not just when the
     *  user is actually done talking. Stopping there (as an earlier version of this class did)
     *  silently truncated anything said after the first pause. Instead, each {@code true} just
     *  finalizes one segment's text (the recognizer keeps accepting audio for the next segment
     *  from the same stream), and segments are concatenated; whether the user is actually finished
     *  is decided independently below via {@link #TRAILING_SILENCE_STOP_MS} of real silence. */
    private static final double SILENCE_RMS_THRESHOLD = 500.0;

    /** How much continuous near-silence, after speech has started, means "done talking". Long
     *  enough to survive a mid-sentence breath, short enough not to make the user wait around
     *  after they've actually finished. */
    private static final long TRAILING_SILENCE_STOP_MS = 1200;

    /** Target RMS (of 16-bit PCM samples) that quiet audio is boosted toward before reaching the
     *  recognizer. Consumer mics/rooms frequently deliver speech well below the level Vosk's
     *  acoustic model was trained on, which hurts transcription accuracy independent of anything
     *  the decoder itself can fix; a simple automatic-gain pass on the captured samples measurably
     *  helps without touching the model. Chosen as a comfortable "clear speech" level — audio
     *  already at or above it is left untouched. */
    private static final double TARGET_RMS = 3500.0;

    /** Ceiling on how much a quiet chunk is amplified, so near-silent background noise (which has
     *  a very low RMS) never gets blown up into loud garbage that could be mistaken for speech. */
    private static final double MAX_GAIN = 4.0;

    /** Below this RMS a chunk is treated as effectively silent/noise floor, not quiet speech, so
     *  gain is skipped entirely rather than amplifying noise (and to avoid a near-zero divisor). */
    private static final double MIN_RMS_FOR_GAIN = 50.0;

    /** Fraction of the way {@link #currentGain} moves from its previous value toward each new
     *  chunk's target gain, once per chunk. Real captures bounce between quiet and loud
     *  chunk-to-chunk (breaths, consonants vs. vowels, mic noise floor) — applying each chunk's own
     *  gain independently, as an earlier version of this method did, snaps the multiplier between
     *  roughly 1x and {@link #MAX_GAIN} every ~256ms. That step-change in amplitude at every chunk
     *  boundary distorts the waveform's envelope in a way real speech never does, which corrupts
     *  the spectral features the recognizer decodes on — independent of anything the language model
     *  can compensate for. Smoothing the gain (and ramping it linearly across each chunk's samples
     *  in {@link #applyAutoGain}, rather than one flat multiplier per chunk) keeps the boost but
     *  removes the discontinuities. 0.35 reaches ~90% of a new target within ~5 chunks (~1.3s) while
     *  keeping any single chunk-to-chunk jump small. */
    private static final double GAIN_SMOOTHING = 0.35;

    /** Loaded once per process and shared by every {@link VoiceInputService} instance/recognizer —
     *  loading it is the expensive part (disk + memory), while a {@link Recognizer} built from it
     *  is cheap and used one-per-utterance. */
    private static volatile Model sharedModel;
    private static volatile boolean modelLoadAttempted = false;
    private static final Object MODEL_LOCK = new Object();

    /** Line currently being captured from, if any, so {@link #stopListening()} (called from the
     *  JavaFX Application Thread) has something to interrupt from outside {@link
     *  #captureAndRecognize}'s loop, which runs on the caller's background thread. */
    private volatile TargetDataLine activeLine;

    /** Set by {@link #stopListening()}; checked each iteration of {@link #captureAndRecognize}'s
     *  loop so a stop request ends capture on the next chunk instead of waiting for trailing
     *  silence or the hard timeout. */
    private volatile boolean stopRequested;

    /** Gain currently being applied, smoothed chunk-to-chunk by {@link #applyAutoGain}; see
     *  {@link #GAIN_SMOOTHING}. Reset to 1.0 (no boost) at the start of every {@link
     *  #captureAndRecognize} call so one recording's gain state never bleeds into the next on this
     *  reused instance. */
    private double currentGain = 1.0;

    /**
     * Blocking — records and recognizes one utterance. Callers must not invoke this on the JavaFX
     * Application Thread.
     */
    public VoiceResult recognizeSpeech() {
        stopRequested = false;
        Model model = loadModelOnce();
        if (model == null) {
            return VoiceResult.error(Status.SERVICE_UNAVAILABLE);
        }

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(lineInfo)) {
            return VoiceResult.error(Status.NO_MICROPHONE);
        }

        TargetDataLine line;
        try {
            line = (TargetDataLine) AudioSystem.getLine(lineInfo);
            line.open(format);
        } catch (LineUnavailableException | SecurityException | IllegalArgumentException e) {
            return VoiceResult.error(Status.NO_MICROPHONE);
        }

        activeLine = line;
        try (Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
            return captureAndRecognize(line, recognizer);
        } catch (IOException e) {
            return VoiceResult.error(Status.RECOGNITION_FAILED);
        } finally {
            activeLine = null;
            line.close();
        }
    }

    /**
     * Ends the microphone capture started by an in-flight {@link #recognizeSpeech()} call
     * immediately, without waiting for trailing-silence detection or the hard timeout. Safe to
     * call from any thread, including the JavaFX Application Thread. {@link TargetDataLine#stop()}
     * unblocks the background thread's in-progress {@link TargetDataLine#read}, so {@link
     * #captureAndRecognize} finalizes on whatever audio was captured up to this point rather than
     * discarding it. A no-op if nothing is currently being recorded.
     */
    public void stopListening() {
        stopRequested = true;
        TargetDataLine line = activeLine;
        if (line != null) {
            line.stop();
        }
    }

    /** Reads audio, concatenating every segment Vosk finalizes along the way, until real trailing
     *  silence says the user is done (or the hard timeout is hit). */
    private VoiceResult captureAndRecognize(TargetDataLine line, Recognizer recognizer) {
        currentGain = 1.0;
        line.start();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long startTime = System.currentTimeMillis();
        long deadline = startTime + (long) (MAX_UTTERANCE_SECONDS * 1000);

        StringBuilder transcript = new StringBuilder();
        boolean speechStarted = false;
        long silenceRunStart = -1;
        String stopReason = "timeout"; // TEMPORARY — overwritten below when the real reason is known

        while (!stopRequested && System.currentTimeMillis() < deadline) {
            int bytesRead = line.read(buffer, 0, buffer.length);
            if (bytesRead <= 0) {
                continue;
            }

            double rms = computeRms(buffer, bytesRead);
            applyAutoGain(buffer, bytesRead, rms);

            boolean segmentFinalized = recognizer.acceptWaveForm(buffer, bytesRead);
            if (segmentFinalized) {
                appendSegment(transcript, extractText(recognizer.getResult()));
            }

            boolean silent = rms < SILENCE_RMS_THRESHOLD;
            long now = System.currentTimeMillis();

            // TEMPORARY — diagnostic only, see LOG field javadoc.
            LOG.info("voice-rms t={}ms rms={} silent={} speechStarted={} silenceMs={} segmentFinalized={}",
                    now - startTime, Math.round(rms), silent, speechStarted,
                    silenceRunStart < 0 ? 0 : (now - silenceRunStart), segmentFinalized);

            if (silent) {
                if (silenceRunStart < 0) {
                    silenceRunStart = now;
                } else if (speechStarted && (now - silenceRunStart) >= TRAILING_SILENCE_STOP_MS) {
                    stopReason = "trailing-silence";
                    break;
                }
            } else {
                speechStarted = true;
                silenceRunStart = -1;
            }
        }
        if (stopRequested) {
            stopReason = "stop-requested";
        }
        line.stop();

        appendSegment(transcript, extractText(recognizer.getFinalResult()));
        String text = collapseSpelledLetters(transcript.toString().trim());

        // TEMPORARY — diagnostic only, see LOG field javadoc.
        LOG.info("voice-rms done stopReason={} durationMs={} speechStarted={} text=\"{}\"",
                stopReason, System.currentTimeMillis() - startTime, speechStarted, text);

        if (text.isEmpty()) {
            return VoiceResult.error(Status.NO_SPEECH);
        }
        return VoiceResult.success(text);
    }

    private static void appendSegment(StringBuilder transcript, String segment) {
        if (segment == null || segment.isBlank()) {
            return;
        }
        if (transcript.length() > 0) {
            transcript.append(' ');
        }
        transcript.append(segment);
    }

    /** Root-mean-square amplitude of a chunk of little-endian 16-bit PCM samples, used as a
     *  simple energy-based "is anyone currently talking" signal independent of Vosk's own
     *  decoder state (which resets between segments). */
    private static double computeRms(byte[] buffer, int bytesRead) {
        int sampleCount = bytesRead / 2;
        if (sampleCount == 0) {
            return 0.0;
        }
        long sumSquares = 0;
        for (int i = 0; i < sampleCount; i++) {
            short sample = (short) ((buffer[2 * i] & 0xFF) | (buffer[2 * i + 1] << 8));
            sumSquares += (long) sample * sample;
        }
        return Math.sqrt((double) sumSquares / sampleCount);
    }

    /** Boosts a chunk of little-endian 16-bit PCM samples in place toward {@link #TARGET_RMS} when
     *  {@code rms} (already computed by the caller for silence detection, so this adds no extra
     *  pass over the buffer) says the audio is quiet but not just noise floor. Loud/clear audio
     *  (rms already at or above target) is left untouched — this only ever helps quiet input, it
     *  never attenuates.
     *
     *  <p>Applied as a smooth ramp, not one flat multiplier per chunk: {@link #currentGain} moves
     *  only {@link #GAIN_SMOOTHING} of the way toward this chunk's target every call, and the gain
     *  actually multiplied into each sample is linearly interpolated from the previous chunk's
     *  ending gain to this chunk's new value across the chunk's samples. Both steps exist to avoid
     *  a step-change in amplitude at chunk boundaries — see {@link #GAIN_SMOOTHING}'s javadoc for
     *  why that discontinuity, not the boost itself, was corrupting recognition. */
    private void applyAutoGain(byte[] buffer, int bytesRead, double rms) {
        double targetGain = (rms < MIN_RMS_FOR_GAIN || rms >= TARGET_RMS)
                ? 1.0
                : Math.min(MAX_GAIN, TARGET_RMS / rms);

        double startGain = currentGain;
        currentGain += (targetGain - currentGain) * GAIN_SMOOTHING;
        double endGain = currentGain;

        int sampleCount = bytesRead / 2;
        if (sampleCount == 0) {
            return;
        }
        for (int i = 0; i < sampleCount; i++) {
            double progress = sampleCount == 1 ? 1.0 : (double) i / (sampleCount - 1);
            double gain = startGain + (endGain - startGain) * progress;
            int idx = 2 * i;
            short sample = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
            int boosted = (int) Math.round(sample * gain);
            boosted = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, boosted));
            buffer[idx] = (byte) (boosted & 0xFF);
            buffer[idx + 1] = (byte) ((boosted >> 8) & 0xFF);
        }
    }

    /** Every real English word Vosk's dictation vocabulary is close enough to, phonetically, that
     *  it decodes a spoken letter *name* as that word rather than the bare letter — e.g. saying
     *  "gee" for G comes back from the recognizer as the word "gee", not "G". Used by {@link
     *  #collapseSpelledLetters} to recognize a run of such words as someone spelling something out
     *  loud rather than as unrelated text. Deliberately just the standard English names for the 26
     *  letters (plus their common homophone spellings) — nothing here is specific to any particular
     *  abbreviation. */
    private static final Map<String, Character> LETTER_NAMES = buildLetterNames();

    private static Map<String, Character> buildLetterNames() {
        Map<String, Character> map = new HashMap<>();
        map.put("a", 'A'); map.put("ay", 'A'); map.put("eh", 'A');
        map.put("b", 'B'); map.put("be", 'B'); map.put("bee", 'B');
        map.put("c", 'C'); map.put("see", 'C'); map.put("sea", 'C');
        map.put("d", 'D'); map.put("dee", 'D');
        map.put("e", 'E'); map.put("ee", 'E');
        map.put("f", 'F'); map.put("ef", 'F'); map.put("eff", 'F');
        map.put("g", 'G'); map.put("gee", 'G');
        map.put("h", 'H'); map.put("aitch", 'H'); map.put("haitch", 'H');
        map.put("i", 'I'); map.put("eye", 'I'); map.put("aye", 'I');
        map.put("j", 'J'); map.put("jay", 'J');
        map.put("k", 'K'); map.put("kay", 'K');
        map.put("l", 'L'); map.put("el", 'L'); map.put("ell", 'L');
        map.put("m", 'M'); map.put("em", 'M');
        map.put("n", 'N'); map.put("en", 'N');
        map.put("o", 'O'); map.put("oh", 'O');
        map.put("p", 'P'); map.put("pee", 'P');
        map.put("q", 'Q'); map.put("cue", 'Q'); map.put("queue", 'Q');
        map.put("r", 'R'); map.put("are", 'R'); map.put("ar", 'R');
        map.put("s", 'S'); map.put("es", 'S'); map.put("ess", 'S');
        map.put("t", 'T'); map.put("tee", 'T');
        map.put("u", 'U'); map.put("you", 'U'); map.put("yu", 'U');
        map.put("v", 'V'); map.put("vee", 'V');
        map.put("w", 'W');
        map.put("x", 'X'); map.put("ex", 'X');
        map.put("y", 'Y'); map.put("why", 'Y');
        map.put("z", 'Z'); map.put("zee", 'Z'); map.put("zed", 'Z');
        return Collections.unmodifiableMap(map);
    }

    /** Collapses runs of two or more consecutive words that are each an English spoken-letter name
     *  (see {@link #LETTER_NAMES}) into the concatenated uppercase letters they were spelling out —
     *  e.g. "what is the gee pee a requirement" becomes "what is the GPA requirement", and "oh oh
     *  pee" becomes "OOP". A lone matching word is left untouched: ordinary English is full of
     *  one-off matches ("a", "I", "see"), so it's specifically a *run* of them — the pattern of
     *  someone spelling something out loud, one letter at a time — that gets collapsed, not any
     *  single word that happens to sound like a letter. "double u"/"double you" is treated as the
     *  two-word spoken name for W. */
    private static String collapseSpelledLetters(String text) {
        if (text.isEmpty()) {
            return text;
        }
        String[] tokens = text.split("\\s+");
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.length) {
            List<Character> run = new ArrayList<>();
            int j = i;
            while (j < tokens.length) {
                String word = stripPunctuation(tokens[j]).toLowerCase(Locale.ROOT);
                if (word.equals("double") && j + 1 < tokens.length) {
                    String next = stripPunctuation(tokens[j + 1]).toLowerCase(Locale.ROOT);
                    if (next.equals("u") || next.equals("you")) {
                        run.add('W');
                        j += 2;
                        continue;
                    }
                }
                Character letter = LETTER_NAMES.get(word);
                if (letter == null) {
                    break;
                }
                run.add(letter);
                j++;
            }
            if (run.size() >= 2) {
                StringBuilder letters = new StringBuilder();
                run.forEach(letters::append);
                out.add(letters.toString());
                i = j;
            } else {
                out.add(tokens[i]);
                i++;
            }
        }
        return String.join(" ", out);
    }

    private static String stripPunctuation(String word) {
        return word.replaceAll("[^a-zA-Z]", "");
    }

    /** Vosk result JSON looks like {@code {"text": "what is the difference between ..."}}. */
    private static String extractText(String resultJson) {
        try {
            return new JSONObject(resultJson).optString("text", "").trim();
        } catch (JSONException e) {
            return null;
        }
    }

    /** Loads {@link #sharedModel} at most once per process; every later/failed call reuses that
     *  outcome instead of re-scanning the filesystem or re-parsing the model on every click. */
    private static Model loadModelOnce() {
        Model model = sharedModel;
        if (model != null || modelLoadAttempted) {
            return model;
        }
        synchronized (MODEL_LOCK) {
            if (modelLoadAttempted) {
                return sharedModel;
            }
            modelLoadAttempted = true;
            Path modelPath = resolveModelPath();
            if (modelPath == null) {
                return null;
            }
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                sharedModel = new Model(modelPath.toString());
            } catch (IOException | RuntimeException e) {
                sharedModel = null;
            }
            return sharedModel;
        }
    }

    /** First existing directory among the system property, env var, and the two conventional
     *  install locations described in this class's Javadoc; null if none of them exist. */
    private static Path resolveModelPath() {
        String[] candidates = {
                System.getProperty("vosk.model.path"),
                System.getenv("VOSK_MODEL_PATH"),
                "vosk-model",
                System.getProperty("user.home") + "/.universitysystem/vosk-model"
        };
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }
}
