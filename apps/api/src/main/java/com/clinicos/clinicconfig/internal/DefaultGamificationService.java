package com.clinicos.clinicconfig.internal;

import static com.clinicos.shared.jooq.tables.BadgeThreshold.BADGE_THRESHOLD;
import static com.clinicos.shared.jooq.tables.GamificationSettings.GAMIFICATION_SETTINGS;
import static com.clinicos.shared.jooq.tables.WeeklyGoal.WEEKLY_GOAL;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.clinicconfig.api.GamificationService;
import com.clinicos.clinicconfig.api.GamificationService.BadgeThreshold;
import com.clinicos.clinicconfig.api.GamificationService.GamificationSettings;
import com.clinicos.clinicconfig.api.GamificationService.WeeklyGoal;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;

@Service
public class DefaultGamificationService implements GamificationService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final NotificationService notificationService;

    public DefaultGamificationService(DSLContext dsl, TransactionTemplate transactionTemplate,
            NotificationService notificationService) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.notificationService = notificationService;
    }

    @Override
    public GamificationSettings get(UUID clinicId) {
        return transactionTemplate.execute(status -> {
            var row = dsl.insertInto(GAMIFICATION_SETTINGS)
                    .set(GAMIFICATION_SETTINGS.CLINIC_ID, clinicId)
                    .set(GAMIFICATION_SETTINGS.SHOW_LEVEL_RING, true)
                    .set(GAMIFICATION_SETTINGS.SHOW_STREAKS, true)
                    .set(GAMIFICATION_SETTINGS.SHOW_BADGES, true)
                    .set(GAMIFICATION_SETTINGS.SHOW_WEEKLY_GOALS, true)
                    .set(GAMIFICATION_SETTINGS.SHOW_LEADERBOARD, false)
                    .set(GAMIFICATION_SETTINGS.SHOW_REWARD, true)
                    .onConflict(GAMIFICATION_SETTINGS.CLINIC_ID)
                    .doNothing()
                    .returning()
                    .fetchOne();
            if (row == null) {
                return dsl.selectFrom(GAMIFICATION_SETTINGS)
                        .where(GAMIFICATION_SETTINGS.CLINIC_ID.eq(clinicId))
                        .fetchOne(r -> new GamificationSettings(
                                r.getShowLevelRing(), r.getShowStreaks(), r.getShowBadges(),
                                r.getShowWeeklyGoals(), r.getShowLeaderboard(), r.getShowReward()));
            }
            return new GamificationSettings(
                    row.getShowLevelRing(), row.getShowStreaks(), row.getShowBadges(),
                    row.getShowWeeklyGoals(), row.getShowLeaderboard(), row.getShowReward());
        });
    }

    @Override
    public void updateSettings(UUID clinicId, GamificationSettings settings, UUID actorMembershipId) {
        transactionTemplate.executeWithoutResult(status ->
                dsl.insertInto(GAMIFICATION_SETTINGS)
                        .set(GAMIFICATION_SETTINGS.CLINIC_ID, clinicId)
                        .set(GAMIFICATION_SETTINGS.SHOW_LEVEL_RING, settings.showLevelRing())
                        .set(GAMIFICATION_SETTINGS.SHOW_STREAKS, settings.showStreaks())
                        .set(GAMIFICATION_SETTINGS.SHOW_BADGES, settings.showBadges())
                        .set(GAMIFICATION_SETTINGS.SHOW_WEEKLY_GOALS, settings.showWeeklyGoals())
                        .set(GAMIFICATION_SETTINGS.SHOW_LEADERBOARD, settings.showLeaderboard())
                        .set(GAMIFICATION_SETTINGS.SHOW_REWARD, settings.showReward())
                        .onConflict(GAMIFICATION_SETTINGS.CLINIC_ID)
                        .doUpdate()
                        .setNonKeyToExcluded()
                        .execute());
        notifyOwners(clinicId, actorMembershipId, "إعدادات التحفيز");
    }

    @Override
    public List<WeeklyGoal> getGoals(UUID clinicId) {
        return transactionTemplate.execute(status ->
                dsl.selectFrom(WEEKLY_GOAL)
                        .where(WEEKLY_GOAL.CLINIC_ID.eq(clinicId))
                        .orderBy(WEEKLY_GOAL.SLOT.asc())
                        .fetch(r -> new WeeklyGoal(r.getSlot(), r.getTitle(), r.getTarget())));
    }

    @Override
    public void updateGoal(UUID clinicId, int slot, String title, int target, UUID actorMembershipId) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("اسم الهدف مطلوب");
        }
        if (target < 0) {
            throw new IllegalArgumentException("الهدف لا يمكن أن يكون سالباً");
        }
        transactionTemplate.executeWithoutResult(status ->
                dsl.insertInto(WEEKLY_GOAL)
                        .set(WEEKLY_GOAL.CLINIC_ID, clinicId)
                        .set(WEEKLY_GOAL.SLOT, slot)
                        .set(WEEKLY_GOAL.TITLE, title.trim())
                        .set(WEEKLY_GOAL.TARGET, target)
                        .onConflict(WEEKLY_GOAL.CLINIC_ID, WEEKLY_GOAL.SLOT)
                        .doUpdate()
                        .setNonKeyToExcluded()
                        .execute());
        notifyOwners(clinicId, actorMembershipId, "الأهداف الأسبوعية");
    }

    @Override
    public List<BadgeThreshold> getThresholds(UUID clinicId) {
        return transactionTemplate.execute(status ->
                dsl.selectFrom(BADGE_THRESHOLD)
                        .where(BADGE_THRESHOLD.CLINIC_ID.eq(clinicId))
                        .orderBy(BADGE_THRESHOLD.NAME.asc())
                        .fetch(r -> new BadgeThreshold(r.getName(), r.getThreshold())));
    }

    @Override
    public void updateThreshold(UUID clinicId, String name, int threshold, UUID actorMembershipId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("اسم الشارة مطلوب");
        }
        if (threshold < 0) {
            throw new IllegalArgumentException("العتبة لا يمكن أن تكون سالبة");
        }
        transactionTemplate.executeWithoutResult(status ->
                dsl.insertInto(BADGE_THRESHOLD)
                        .set(BADGE_THRESHOLD.CLINIC_ID, clinicId)
                        .set(BADGE_THRESHOLD.NAME, name.trim())
                        .set(BADGE_THRESHOLD.THRESHOLD, threshold)
                        .onConflict(BADGE_THRESHOLD.CLINIC_ID, BADGE_THRESHOLD.NAME)
                        .doUpdate()
                        .setNonKeyToExcluded()
                        .execute());
        notifyOwners(clinicId, actorMembershipId, "عتبات الشارات");
    }

    private void notifyOwners(UUID clinicId, UUID actorMembershipId, String area) {
        notificationService.notifyRoles(clinicId, actorMembershipId, Set.of("owner"),
                NotificationKind.CLINIC_SETTINGS_CHANGED, Map.of("area", area));
    }
}
