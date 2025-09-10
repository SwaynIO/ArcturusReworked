package com.eu.habbo.habbohotel.games.football;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.achievements.AchievementManager;
import com.eu.habbo.habbohotel.games.Game;
import com.eu.habbo.habbohotel.games.GameTeamColors;
import com.eu.habbo.habbohotel.items.interactions.games.football.scoreboards.InteractionFootballScoreboard;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomUnit;
import com.eu.habbo.habbohotel.rooms.RoomUserAction;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.messages.outgoing.rooms.users.RoomUserActionComposer;

import java.util.Map;


public class FootballGame extends Game {
    private Room room;

    public FootballGame(Room room) {
        super(null, null, room, true);

        this.room = room;
    }

    @Override
    public void initialise() {
    }

    @Override
    public void run() {
    }

    public void onScore(RoomUnit kicker, GameTeamColors team) {
        if (this.room == null || !this.room.isLoaded())
            return;

        // Optimized: Cache achievement lookups and reduce Map iterations
        Habbo habbo = this.room.getHabbo(kicker);
        if (habbo != null) {
            // Cache achievement manager instance
            var achievementManager = Emulator.getGameEnvironment().getAchievementManager();
            AchievementManager.progressAchievement(habbo, achievementManager.getAchievement("FootballGoalScored"));
            
            int ownerId = this.room.getOwnerId();
            if (habbo.getHabboInfo().getId() != ownerId) {
                AchievementManager.progressAchievement(ownerId, achievementManager.getAchievement("FootballGoalScoredInRoom"));
            }
        }

        // Send wave action to room
        this.room.sendComposer(new RoomUserActionComposer(kicker, RoomUserAction.WAVE).compose());

        // Optimized: Direct value iteration instead of Map.Entry
        var scoreboards = this.room.getRoomSpecialTypes().getFootballScoreboards(team);
        if (!scoreboards.isEmpty()) {
            for (InteractionFootballScoreboard scoreboard : scoreboards.values()) {
                scoreboard.changeScore(1);
            }
        }
    }
}