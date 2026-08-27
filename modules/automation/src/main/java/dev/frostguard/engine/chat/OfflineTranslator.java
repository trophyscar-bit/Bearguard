package dev.frostguard.engine.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Translates chat into English on this machine, with nothing on the other end of a wire.
 *
 * <p>What this replaces was four public translation front-ends run by volunteers as a favour. That
 * arrangement had already failed twice at a single user: one endpoint answered 429 and the other
 * exhausted its anonymous daily allowance and began returning a quota notice in place of a
 * translation. Neither failure said anything -- the transcript simply stopped being in English.
 * Multiply one user by an install base and the arrangement is not a dependency, it is a promise
 * somebody else never made.
 *
 * <p>The model is Marian, many languages into English, quantised to int8. It reads every language
 * the game ships in, and several it does not that people write anyway. Roughly a hundred megabytes,
 * carried beside the OCR weights, and it needs no runtime the application does not already have --
 * chat is read with ONNX Runtime, so translating with it costs weights and nothing else.
 *
 * <p>Two networks. The encoder reads the foreign sentence once. The decoder then writes English one
 * token at a time, each step shown everything it has written so far. It stops at the end-of-text
 * token, or at a ceiling, because a model that has lost its way will otherwise write until told to
 * stop.
 */
public final class OfflineTranslator implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession encoder;
    private final OrtSession decoder;

    /** Subword pieces and the scores that decide how a word is cut up. */
    private final Map<String, Double> pieceScores;

    /** Piece text to the id the model was trained on. A different order to the piece list. */
    private final Map<String, Integer> pieceIds;
    private final String[] idPieces;

    private final int startId;
    private final int eosId;
    private final int padId;
    private final int unkId;

    /**
     * Opens the model, or reports why it could not.
     *
     * @param modelDir the directory holding encoder.onnx, decoder.onnx and the two vocab files
     */
    public OfflineTranslator(Path modelDir) throws OrtException, IOException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setInterOpNumThreads(1);
        options.setIntraOpNumThreads(THREADS);
        this.encoder = env.createSession(modelDir.resolve("encoder.onnx").toString(), options);
        this.decoder = env.createSession(modelDir.resolve("decoder.onnx").toString(), options);

        this.pieceScores = new HashMap<>(40_000);
        for (String line : Files.readAllLines(modelDir.resolve("vocab.tsv"),
                StandardCharsets.UTF_8)) {
            int tab = line.lastIndexOf('\t');
            if (tab > 0) {
                pieceScores.put(line.substring(0, tab), Double.parseDouble(line.substring(tab + 1)));
            }
        }

        List<String> lines = Files.readAllLines(modelDir.resolve("tokens.tsv"),
                StandardCharsets.UTF_8);
        this.pieceIds = new HashMap<>(lines.size() * 2);
        this.idPieces = new String[lines.size() + 8];
        for (String line : lines) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String piece = line.substring(0, tab);
            int id = Integer.parseInt(line.substring(tab + 1));
            pieceIds.put(piece, id);
            if (id < idPieces.length) {
                idPieces[id] = piece;
            }
        }

        Map<String, Integer> meta = readMeta(modelDir.resolve("meta.json"));
        this.startId = meta.getOrDefault("decoder_start_token_id", 0);
        this.eosId = meta.getOrDefault("eos_token_id", 0);
        this.padId = meta.getOrDefault("pad_token_id", 0);
        this.unkId = meta.getOrDefault("unk_id", 1);
    }

    /** Four, matching the reader. Beyond that the work is too small to divide usefully. */
    private static final int THREADS = 4;

    /** True when a model is present, so a caller can decide rather than discover mid-pass. */
    public static boolean isAvailable(Path modelDir) {
        return Files.isRegularFile(modelDir.resolve("encoder.onnx"))
                && Files.isRegularFile(modelDir.resolve("decoder.onnx"))
                && Files.isRegularFile(modelDir.resolve("vocab.tsv"))
                && Files.isRegularFile(modelDir.resolve("tokens.tsv"));
    }

    /**
     * Where the model sits, whether this is a checkout or an installation.
     *
     * <p>The same walk the OCR weights use, for the same reason: the working directory is the top
     * of a checkout for us and wherever a shortcut pointed for everybody else.
     */
    public static Path defaultModelDir() {
        Path here = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path up = here; up != null; up = up.getParent()) {
            for (Path candidate : new Path[] {
                    up.resolve("lib").resolve("translate"),
                    up.resolve("tools").resolve("translate").resolve("model")}) {
                if (isAvailable(candidate)) {
                    return candidate;
                }
            }
        }
        return here.resolve("lib").resolve("translate");
    }

    /**
     * Which model reads a given message.
     *
     * <p>One general model covers every Latin, Cyrillic, Greek, Arabic and Thai language the game
     * ships in. It is measurably poor at the three CJK ones: handed "everyone open rallies now" in
     * Korean it answered "Oh, my God", which is not a translation, it is noise wearing the shape of
     * one. Those three get a model each, chosen by the script the message is written in, because
     * the script is a fact about the characters and needs no guessing.
     */
    static String scriptOf(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) {
                return "ko";
            }
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF)) {
                return "ja";
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return "zh";
            }
        }
        return "";
    }

    /**
     * Renders one message in English.
     *
     * @return the English, or empty when the model produced nothing worth keeping
     */
    public Optional<String> toEnglish(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(run(body.strip())).filter(s -> !s.isBlank());
        } catch (OrtException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private String run(String body) throws OrtException {
        long[] tokens = encode(body);
        long[][] batch = {tokens};
        long[][] mask = {new long[tokens.length]};
        java.util.Arrays.fill(mask[0], 1L);

        float[][][] hidden;
        try (OnnxTensor ids = OnnxTensor.createTensor(env, batch);
             OnnxTensor attention = OnnxTensor.createTensor(env, mask);
             OrtSession.Result out = encoder.run(
                     Map.of("input_ids", ids, "attention_mask", attention))) {
            hidden = (float[][][]) out.get(0).getValue();
        }

        List<Long> produced = new ArrayList<>();
        produced.add((long) startId);
        int ceiling = Math.min(MOST_TOKENS, tokens.length * 3 + 16);
        for (int step = 0; step < ceiling; step++) {
            long[][] far = {toArray(produced)};
            int next;
            try (OnnxTensor decIn = OnnxTensor.createTensor(env, far);
                 OnnxTensor hid = OnnxTensor.createTensor(env, hidden);
                 OnnxTensor attention = OnnxTensor.createTensor(env, mask);
                 OrtSession.Result out = decoder.run(Map.of(
                         "decoder_input_ids", decIn, "hidden", hid, "attention_mask", attention))) {
                float[][][] logits = (float[][][]) out.get(0).getValue();
                next = bestToken(logits[0][logits[0].length - 1]);
            }
            if (next == eosId) {
                break;
            }
            produced.add((long) next);
        }
        return detokenise(produced);
    }

    /**
     * The most likely next token, with padding forbidden.
     *
     * <p>The ban is not an optimisation. This model's own generation settings list padding as a
     * banned word, and without that ban the first token it wants to emit is padding -- for every
     * sentence, in every language. A decode loop missing it produces a row of padding and looks
     * exactly like a broken model.
     */
    private int bestToken(float[] logits) {
        int best = -1;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (i == padId) {
                continue;
            }
            if (logits[i] > bestScore) {
                bestScore = logits[i];
                best = i;
            }
        }
        return best;
    }

    /** Long enough for any chat message, short enough that a model in a loop stops. */
    private static final int MOST_TOKENS = 96;

    /**
     * Cuts a sentence into the subword pieces the model was trained on.
     *
     * <p>Unigram SentencePiece, scored by Viterbi rather than by taking the longest piece that
     * fits. The scores are what decide where a word breaks, and a word broken in the wrong place
     * is a different word to this model -- greedy matching produces pieces that exist and a
     * sentence that does not mean what was written.
     */
    private long[] encode(String body) {
        String text = WORD_START + body.replace(' ', WORD_START.charAt(0));
        int n = text.length();
        double[] best = new double[n + 1];
        int[] fromIndex = new int[n + 1];
        String[] fromPiece = new String[n + 1];
        java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
        best[0] = 0;

        for (int i = 0; i < n; i++) {
            if (best[i] == Double.NEGATIVE_INFINITY) {
                continue;
            }
            int limit = Math.min(n, i + LONGEST_PIECE);
            for (int j = i + 1; j <= limit; j++) {
                Double score = pieceScores.get(text.substring(i, j));
                if (score != null && best[i] + score > best[j]) {
                    best[j] = best[i] + score;
                    fromIndex[j] = i;
                    fromPiece[j] = text.substring(i, j);
                }
            }
            // A character no piece covers would otherwise strand the rest of the sentence, so it
            // is taken alone at a heavy penalty and becomes an unknown token.
            if (fromPiece[i + 1] == null && best[i] + STRANDED_PENALTY > best[i + 1]) {
                best[i + 1] = best[i] + STRANDED_PENALTY;
                fromIndex[i + 1] = i;
                fromPiece[i + 1] = text.substring(i, i + 1);
            }
        }

        List<String> pieces = new ArrayList<>();
        for (int at = n; at > 0; at = fromIndex[at]) {
            if (fromPiece[at] == null) {
                break;
            }
            pieces.add(fromPiece[at]);
        }
        Collections.reverse(pieces);

        long[] out = new long[pieces.size() + 1];
        for (int i = 0; i < pieces.size(); i++) {
            Integer id = pieceIds.get(pieces.get(i));
            out[i] = id == null ? unkId : id;
        }
        out[out.length - 1] = eosId;
        return out;
    }

    /** SentencePiece marks where a word begins rather than where a space was. */
    private static final String WORD_START = "▁";

    /** No piece in this inventory is longer, so the search stops there. */
    private static final int LONGEST_PIECE = 24;

    /** What a character nothing covers costs, so it is taken only when nothing else works. */
    private static final double STRANDED_PENALTY = -20.0;

    private String detokenise(List<Long> produced) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < produced.size(); i++) {
            int id = produced.get(i).intValue();
            String piece = id >= 0 && id < idPieces.length ? idPieces[id] : null;
            if (piece != null) {
                sb.append(piece);
            }
        }
        return sb.toString().replace(WORD_START, " ").strip();
    }

    private static long[] toArray(List<Long> values) {
        long[] out = new long[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** The handful of ids the loop needs. Read by hand rather than pulling in a JSON parser. */
    private static Map<String, Integer> readMeta(Path file) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String part : Files.readString(file, StandardCharsets.UTF_8)
                .replace("{", "").replace("}", "").split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0].replace("\"", "").trim();
            String value = kv[1].replace("\"", "").trim();
            try {
                out.put(key, Integer.parseInt(value));
            } catch (NumberFormatException notANumber) {
                // meta carries a couple of non-numeric entries; the loop needs none of them.
            }
        }
        return out;
    }

    @Override
    public void close() {
        for (AutoCloseable c : new AutoCloseable[] {decoder, encoder}) {
            try {
                if (c != null) {
                    c.close();
                }
            } catch (Exception ignored) {
                // Nothing useful to do when a session refuses to close at shutdown.
            }
        }
    }
}
