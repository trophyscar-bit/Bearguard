package dev.frostguard.engine.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Evidence level: automated test reproducing a live crash.
 *
 * <p>The fallback endpoint carried a raw {@code |} between its language codes, which is not legal
 * in a URI query. {@code URI.create} threw {@link IllegalArgumentException}, and being unchecked it
 * travelled out of the translator, out of the row reader, and out of the capture task -- the queue
 * retried the task immediately, so the symptom was chat reopening every few seconds having captured
 * nothing, with no cause anywhere in the log.
 */
class ChatTranslatorTest {

    @Test
    void bothEndpointsAssembleIntoAddressesThatParse() {
        // The body that triggered it live: an apostrophe and a trademark sign, both of which the
        // encoder handles -- the illegal character was in the endpoint itself, not the message.
        String body = URLEncoder.encode("a SNoopy'™ gonna miss ur", StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> URI.create(ChatTranslator.PRIMARY + body));
        assertDoesNotThrow(() -> URI.create(ChatTranslator.FALLBACK + body));
    }
}
