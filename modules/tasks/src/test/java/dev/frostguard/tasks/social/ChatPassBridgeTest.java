package dev.frostguard.tasks.social;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.chat.ChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Evidence level: automated tests. Screens are handed over as already-parsed messages, so this
 * covers the rule for calling a walk "caught up" and not the reading of a real frame.
 */
class ChatPassBridgeTest {

    private static ChatPass pass(Set<String> stored) {
        ChatPass pass = new ChatPass("alliance", body -> Optional.empty(), null, null, null, 700);
        pass.useKnownHistory(m -> stored.contains(m.body()));
        return pass;
    }

    private static ChatMessage msg(String body) {
        return new ChatMessage(Instant.parse("2026-10-24T05:00:00Z"), "alliance", "Nightjar", "INF", 0,
                body, "", List.of(), ChatMessage.Kind.TEXT, "");
    }

    private static void screen(ChatPass pass, String... bodies) {
        pass.beginScreen();
        for (String b : bodies) {
            pass.keep(msg(b));
        }
        pass.endScreen();
    }

    @Test
    void twoScreensOfStoredMessagesAreABridge() {
        ChatPass pass = pass(Set.of("rally at the north gate in ten minutes", "who has spare speedups for tonight",
                "bear trap starts at nine sharp everyone", "please translate the leader announcement above"));
        screen(pass, "rally at the north gate in ten minutes", "who has spare speedups for tonight");
        screen(pass, "bear trap starts at nine sharp everyone", "please translate the leader announcement above");
        assertTrue(pass.reachedKnownHistory());
    }

    @Test
    void emptyScreensAreNotABridge() {
        ChatPass pass = pass(Set.of());
        screen(pass);
        screen(pass);
        assertFalse(pass.reachedKnownHistory());
    }

    @Test
    void shortRepeatedMessagesAreNotEvidence() {
        ChatPass pass = pass(Set.of("ok", "jajaja", "lol", "gg", "thanks"));
        screen(pass, "ok", "jajaja", "lol");
        screen(pass, "gg", "thanks");
        assertFalse(pass.reachedKnownHistory());
    }

    @Test
    void oneStoredMessageIsNotEnough() {
        ChatPass pass = pass(Set.of("the only stored message here"));
        screen(pass, "the only stored message here");
        screen(pass);
        assertFalse(pass.reachedKnownHistory());
    }

    @Test
    void aNewMessageRestartsTheCount() {
        ChatPass pass = pass(Set.of("rally at the north gate in ten minutes", "who has spare speedups for tonight",
                "bear trap starts at nine sharp everyone"));
        screen(pass, "rally at the north gate in ten minutes", "who has spare speedups for tonight");
        screen(pass, "a brand new message nobody has stored");
        screen(pass, "bear trap starts at nine sharp everyone");
        assertFalse(pass.reachedKnownHistory());
    }

    @Test
    void withoutKnownHistoryTheOldRuleStillApplies() {
        ChatPass pass = new ChatPass("alliance", body -> Optional.empty(), null, null, null, 700);
        screen(pass, "first");
        screen(pass);
        screen(pass);
        assertTrue(pass.reachedKnownHistory());
    }
}
