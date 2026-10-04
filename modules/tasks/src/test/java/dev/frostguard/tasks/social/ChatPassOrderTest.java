package dev.frostguard.tasks.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.api.chat.ChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Evidence level: automated tests. The screens here are handed to the pass as already-parsed
 * messages, so this covers the ordering bookkeeping and not the reading of a real frame.
 */
class ChatPassOrderTest {

    private static ChatPass pass() {
        return new ChatPass("alliance", body -> Optional.empty(), null, null, null, 700);
    }

    private static ChatMessage msg(String author, String body) {
        return new ChatMessage(Instant.parse("2026-09-24T05:00:00Z"), "alliance", author, "INF", 0,
                body, "", List.of(), ChatMessage.Kind.TEXT, "");
    }

    private static List<String> bodies(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::body).toList();
    }

    @Test
    void returnsMessagesOldestFirstEvenThoughTheWalkReadsNewestFirst() {
        ChatPass pass = pass();
        // The newest screen is read first, then the one above it. The overlap -- a message that
        // sits at the top of the newer screen and the bottom of the older -- is what joins them.
        pass.beginScreen();
        pass.keep(msg("Nightjar", "the fourth thing said was about the gathering point"));
        pass.keep(msg("Marisol", "then somebody asked who has spare speedups tonight"));
        pass.beginScreen();
        pass.keep(msg("Nightjar", "earliest message about the bear trap schedule"));
        pass.keep(msg("Marisol", "a reply agreeing with the plan for nine"));
        pass.keep(msg("Nightjar", "the fourth thing said was about the gathering point"));

        assertEquals(List.of(
                "earliest message about the bear trap schedule",
                "a reply agreeing with the plan for nine",
                "the fourth thing said was about the gathering point",
                "then somebody asked who has spare speedups tonight"),
                bodies(pass.chronological()));
    }

    @Test
    void aMessageKeepsItsPlaceWhenAFullerCopyFromAnOlderScreenReplacesIt() {
        ChatPass pass = pass();
        pass.beginScreen();
        pass.keep(msg("Nightjar", "rally at the north gate in ten minutes bring every troop you have"));
        pass.keep(msg("Marisol", "on my way with the whole squad tonight"));
        pass.beginScreen();
        pass.keep(msg("Marisol", "somebody please translate what the leader wrote above"));
        // Seen a second time. Re-filing it moves it to the end of the collection order, which is
        // exactly what must not change where it sits in the conversation.
        pass.keep(msg("Nightjar", "rally at the north gate in ten minutes bring every troop you have"));

        List<String> order = bodies(pass.chronological());

        assertEquals(3, order.size());
        assertEquals("somebody please translate what the leader wrote above", order.get(0));
        assertEquals("rally at the north gate in ten minutes bring every troop you have", order.get(1));
        assertEquals("on my way with the whole squad tonight", order.get(2));
    }
}
