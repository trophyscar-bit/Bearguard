package dev.frostguard.tasks.combat;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOP_FLAG_STRING;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_LEADER_JOIN_FLAG_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_LEADER_JOIN_TROOP_FLAG_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BearJoinSquadKeysTest {

    @Test
    void aLeaderWithSavedSquadsJoinsWithThem() {
        assertEquals(new BearJoinSquadKeys(BEAR_TRAP_LEADER_JOIN_FLAG_INT, BEAR_TRAP_LEADER_JOIN_TROOP_FLAG_STRING),
                BearJoinSquadKeys.forRole(true, "2,3"));
    }

    @Test
    void aLeaderWithoutSavedSquadsJoinsWithTheJoinerSquads() {
        for (String unsaved : new String[] { null, "", "  " }) {
            assertEquals(new BearJoinSquadKeys(BEAR_TRAP_JOIN_FLAG_INT, BEAR_TRAP_JOIN_TROOP_FLAG_STRING),
                    BearJoinSquadKeys.forRole(true, unsaved));
        }
    }

    @Test
    void aJoinerAlwaysUsesTheJoinerSquads() {
        assertEquals(BearJoinSquadKeys.JOINER, BearJoinSquadKeys.forRole(false, "2,3"));
        assertEquals(BearJoinSquadKeys.JOINER, BearJoinSquadKeys.forRole(false, ""));
    }
}
