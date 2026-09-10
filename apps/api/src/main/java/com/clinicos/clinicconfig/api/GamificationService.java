package com.clinicos.clinicconfig.api;

import java.util.List;
import java.util.UUID;

public interface GamificationService {

    GamificationSettings get(UUID clinicId);

    void updateSettings(UUID clinicId, GamificationSettings settings);

    List<WeeklyGoal> getGoals(UUID clinicId);

    void updateGoal(UUID clinicId, int slot, String title, int target);

    List<BadgeThreshold> getThresholds(UUID clinicId);

    void updateThreshold(UUID clinicId, String name, int threshold);

    record GamificationSettings(
            boolean showLevelRing,
            boolean showStreaks,
            boolean showBadges,
            boolean showWeeklyGoals,
            boolean showLeaderboard,
            boolean showReward) {
    }

    record WeeklyGoal(int slot, String title, int target) {
    }

    record BadgeThreshold(String name, int threshold) {
    }
}
