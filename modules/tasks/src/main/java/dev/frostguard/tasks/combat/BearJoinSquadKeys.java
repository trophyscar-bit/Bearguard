package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;

/**
 * Which saved squads a Bear Trap run joins rallies with. A leader has hero and troop squads of its
 * own; until those are saved, a leader joins with the joiner squads, so older profiles keep working.
 */
public record BearJoinSquadKeys(ConfigurationKeyEnum hero, ConfigurationKeyEnum troop) {

    public static final BearJoinSquadKeys JOINER = new BearJoinSquadKeys(
            ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOP_FLAG_STRING);
    public static final BearJoinSquadKeys LEADER = new BearJoinSquadKeys(
            ConfigurationKeyEnum.BEAR_TRAP_LEADER_JOIN_FLAG_INT, ConfigurationKeyEnum.BEAR_TRAP_LEADER_JOIN_TROOP_FLAG_STRING);

    /**
     * The leader's hero squads decide for both lists. Joining always needs a hero squad, so blank
     * hero squads mean the leader lists were never saved, while blank troop squads beside saved hero
     * squads are a real choice of no troop-only squads and must not pull in the joiner's.
     */
    public static BearJoinSquadKeys forRole(boolean leading, String leaderHeroSquads) {
        return leading && leaderHeroSquads != null && !leaderHeroSquads.isBlank() ? LEADER : JOINER;
    }
}
