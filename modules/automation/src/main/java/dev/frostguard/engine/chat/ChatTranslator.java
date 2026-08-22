package dev.frostguard.engine.chat;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Renders foreign chat into English over the network, holding nothing locally.
 *
 * <p>Frostguard ships to people who did not ask for a translation stack, so the constraint is that
 * this must add no install size, no service to run and no key to obtain. That rules out shipping
 * language models -- the offline engines are hundreds of megabytes -- and rules out the hosted
 * APIs that need an account. What is left is a keyless public endpoint, which is what this uses.
 *
 * <p>World chat is multilingual and unpredictable, so no language list is maintained: the endpoint
 * detects the source itself. Three things keep the request volume low enough for that to be
 * reasonable:
 *
 * <ul>
 *   <li>English is recognised locally by {@link ChatLineCleaner#looksEnglish} and never sent;</li>
 *   <li>results are cached by normalised text, and game chat repeats heavily;</li>
 *   <li>a failure returns empty rather than retrying, so the transcript degrades to the original
 *       text instead of stalling behind the network.</li>
 * </ul>
 *
 * <p>The primary endpoint is unofficial and may rate-limit or change without notice. That is the
 * accepted risk of the no-key constraint, and it is why there is a documented fallback and why
 * every caller must treat an empty result as normal.
 */
public final class ChatTranslator {

    static final String PRIMARY =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=";

    /** Documented, keyless, but capped for anonymous callers -- a backstop, not a primary. */
    static final String FALLBACK =
            "https://api.mymemory.translated.net/get?langpair=autodetect%7Cen&q=";

    private static final int MAX_CHARS = 1200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final Map<String, String> cache;
    private final boolean enabled;

    public ChatTranslator(boolean enabled, int cacheSize) {
        this.enabled = enabled;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > cacheSize;
            }
        });
    }

    /**
     * @return the English rendering, or empty when the body is already English, translation is
     *         switched off, or the lookup did not succeed
     */
    public Optional<String> toEnglish(String body) {
        if (!enabled || body == null || body.isBlank() || ChatLineCleaner.looksEnglish(body)) {
            return Optional.empty();
        }
        String key = ChatLineCleaner.cacheKey(body);
        String hit = cache.get(key);
        if (hit != null) {
            return hit.isEmpty() ? Optional.empty() : Optional.of(hit);
        }

        String trimmed = body.length() > MAX_CHARS ? body.substring(0, MAX_CHARS) : body;
        String result = viaPrimary(trimmed).orElseGet(() -> viaFallback(trimmed).orElse(""));

        // Cache the failure too. A body that cannot be translated will arrive again on the next
        // overlapping scroll-back, and re-requesting it every pass is how a keyless endpoint
        // starts refusing service.
        cache.put(key, result);
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    private Optional<String> viaPrimary(String body) {
        return fetch(PRIMARY + URLEncoder.encode(body, StandardCharsets.UTF_8)).flatMap(raw -> {
            try {
                // Shape: [[["translated","source",...], ...], ...] -- concatenate the segments.
                JsonNode segments = MAPPER.readTree(raw).path(0);
                StringBuilder sb = new StringBuilder();
                for (JsonNode seg : segments) {
                    sb.append(seg.path(0).asText(""));
                }
                String out = sb.toString().trim();
                return out.isEmpty() ? Optional.empty() : Optional.of(out);
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                return Optional.empty();
            }
        });
    }

    private Optional<String> viaFallback(String body) {
        return fetch(FALLBACK + URLEncoder.encode(body, StandardCharsets.UTF_8)).flatMap(raw -> {
            try {
                String out = MAPPER.readTree(raw).path("responseData").path("translatedText")
                        .asText("").trim();
                return out.isEmpty() ? Optional.empty() : Optional.of(out);
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                return Optional.empty();
            }
        });
    }

    private Optional<String> fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Frostguard")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 ? Optional.of(res.body()) : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException is URI.create rejecting the assembled address. It used to
            // escape this method, and because it is unchecked it travelled all the way out of the
            // capture task -- the queue retried the task at once, so a bad address showed up as
            // chat restarting every few seconds with nothing captured and no cause logged. A
            // translation is a nicety; failing to build its URL must not end the pass.
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** How many distinct phrases have been resolved, for the capture log. */
    public int cachedPhrases() {
        return cache.size();
    }
}
