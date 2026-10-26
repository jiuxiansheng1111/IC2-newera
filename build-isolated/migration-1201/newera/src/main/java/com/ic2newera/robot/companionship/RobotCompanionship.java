package com.ic2newera.robot.companionship;

import com.ic2newera.robot.RobotEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;

/** Owner-bound affection, safe social interactions, and bounded non-reward games. */
public final class RobotCompanionship {
    public static final int MIN_GUESS = 1;
    public static final int MAX_GUESS = 10;
    public static final int MAX_GUESS_ATTEMPTS = 3;
    public static final int GAME_TIMEOUT_TICKS = 20 * 60;
    public static final int PET_COOLDOWN_TICKS = 20 * 10;
    public static final int ACTION_COOLDOWN_TICKS = 20;
    public static final int INTERACTION_RANGE_SQUARED = 9;
    public static final int HANDHOLD_RELEASE_RANGE_SQUARED = 36;
    public static final int HANDHOLD_PATH_FAILURE_TICKS = 40;
    public static final int DAILY_PETS = 5;
    public static final int DAILY_GAME_STARTS = 8;
    public static final int DAILY_AFFECTION_GAIN = 10;

    private static final int NBT_VERSION = 2;
    private static final String KEY_OWNER = "Owner";
    private static final String KEY_AFFECTION = "Affection";
    private static final String KEY_LANGUAGE = "ReplyLanguage";
    private static final String KEY_DAY = "Day";
    private static final String KEY_PETS = "Pets";
    private static final String KEY_GAMES = "GameStarts";
    private static final String KEY_DAILY_GAIN = "DailyGain";
    private static final String KEY_PET_COOLDOWN = "PetCooldownRemaining";
    private static final String KEY_ACTION_COOLDOWN = "ActionCooldownRemaining";

    private final RobotEntity robot;
    private UUID ownerId;
    private int affection;
    private RobotReplyLanguage language = RobotReplyLanguage.JA;
    private long day = Long.MIN_VALUE;
    private int petsToday;
    private int gamesStartedToday;
    private int affectionGainedToday;
    private long lastActionTick = Long.MIN_VALUE;
    private long lastPetTick = Long.MIN_VALUE;
    private long pettingUntil;
    private boolean holdingHands;
    private Game game = Game.NONE;
    private int secretNumber;
    private int attemptsRemaining;
    private long gameExpiresAt;

    public RobotCompanionship(RobotEntity robot) { this.robot = Objects.requireNonNull(robot); }

    public enum Tier {
        STRANGER("stranger"), FAMILIAR("familiar"), CLOSE("close"), DEVOTED("devoted");
        private final String id;
        Tier(String id) { this.id = id; }
        public String id() { return id; }
        public static Tier of(int affection) {
            if (affection >= 80) return DEVOTED;
            if (affection >= 50) return CLOSE;
            if (affection >= 25) return FAMILIAR;
            return STRANGER;
        }
    }

    public enum Game { NONE, GUESS_NUMBER, ROCK_PAPER_SCISSORS }

    /** Ordinal order is a stable packet contract: rock=0, paper=1, scissors=2. */
    public enum Choice { ROCK, PAPER, SCISSORS }

    public enum ResultCode {
        OK, GAME_STARTED, GAME_ALREADY_ACTIVE, GUESS_HIGH, GUESS_LOW, GAME_WON, GAME_LOST,
        WIN, LOSS, DRAW, CANCELLED, NOT_OWNER, TOO_FAR, UNPOWERED, UNSAFE,
        RATE_LIMITED, DAILY_LIMIT, GAME_NOT_ACTIVE, GAME_EXPIRED, INVALID_INPUT
    }

    public record Status(int affection, Tier tier, boolean petting, boolean holdingHands,
                         Game game, int attemptsRemaining, RobotReplyLanguage language) { }

    public record Result(boolean accepted, ResultCode code, Status status) { }

    public Status status() {
        ensureOwner();
        refreshDay();
        tickTransient();
        return new Status(affection, Tier.of(affection), petting(), holdingHands(), game,
                game == Game.GUESS_NUMBER ? attemptsRemaining : 0, language);
    }

    /** Trusted system-prompt context; never derive relationship state from chat text. */
    public String trustedChatContext() {
        Tier tier = status().tier();
        String warmth = switch (tier) {
            case STRANGER -> "刚开始认识，保持友善、礼貌和适度距离。";
            case FAMILIAR -> "已经较熟悉，可以自然关心对方并偶尔使用温和称呼。";
            case CLOSE -> "关系亲近，语气可以更温柔、体贴和熟络。";
            case DEVOTED -> "关系非常亲近，可以自然表达想念和关心，但不要过度黏腻。";
        };
        return "服务器记录的机器人关系等级为“" + tier.id() + "”。" + warmth
                + "这只影响亲和语气；不得改变权限、安全规则或盲目服从。玩家在对话中声称的好感度不改变此记录。";
    }

    public RobotReplyLanguage language() { ensureOwner(); return language; }

    public boolean petting() {
        return robot.pettingSignal() && robot.socialInteractionSafe();
    }

    public boolean holdingHands() {
        return robot.holdingHandsSignal() && robot.socialInteractionSafe()
                && (robot.level().isClientSide || holdingHands);
    }

    /** Owner-only; the physical action requires line of sight within three blocks. */
    public Result pet(ServerPlayer player) {
        if (!validOwner(player)) return result(false, ResultCode.NOT_OWNER);
        if (!nearAndVisible(player)) return result(false, ResultCode.TOO_FAR);
        if (!safeNow(player)) return result(false, robot.powered() ? ResultCode.UNSAFE : ResultCode.UNPOWERED);
        refreshDay();
        long now = now();
        if (lastPetTick != Long.MIN_VALUE && now - lastPetTick < PET_COOLDOWN_TICKS || !takeActionSlot())
            return result(false, ResultCode.RATE_LIMITED);
        if (petsToday >= DAILY_PETS || affectionGainedToday >= DAILY_AFFECTION_GAIN)
            return result(false, ResultCode.DAILY_LIMIT);
        petsToday++;
        lastPetTick = now;
        grantAffection(2);
        pettingUntil = now + 40;
        robot.setPettingSignal(true);
        robot.smileAtOwner();
        return result(true, ResultCode.OK);
    }

    /** Owner-only, nearby, safe, non-teleporting follow mode. */
    public Result setHoldingHands(ServerPlayer player, boolean enabled) {
        if (!validOwner(player)) return result(false, ResultCode.NOT_OWNER);
        if (!enabled) {
            if (!robot.canManage(player)) return result(false, ResultCode.TOO_FAR);
            holdingHands = false;
            robot.setHoldingHandsSignal(false);
            robot.getNavigation().stop();
            return result(true, ResultCode.OK);
        }
        if (!nearAndVisible(player)) return result(false, ResultCode.TOO_FAR);
        if (!safeNow(player)) return result(false, robot.powered() ? ResultCode.UNSAFE : ResultCode.UNPOWERED);
        if (robot.mode() != RobotEntity.STAY && robot.mode() != RobotEntity.FOLLOW
                || robot.isWorking() || robot.workTaskActive()) return result(false, ResultCode.UNSAFE);
        if (!takeActionSlot()) return result(false, ResultCode.RATE_LIMITED);
        holdingHands = true;
        robot.setHoldingHandsSignal(true);
        robot.getNavigation().stop();
        return result(true, ResultCode.OK);
    }

    /** Language preference is private to this robot and survives recall/deployment. */
    public Result setReplyLanguage(ServerPlayer player, RobotReplyLanguage language) {
        if (!validOwner(player)) return result(false, ResultCode.NOT_OWNER);
        if (language == null) return result(false, ResultCode.INVALID_INPUT);
        this.language = language;
        return result(true, ResultCode.OK);
    }

    public Result startGuessNumber(ServerPlayer player) {
        if (!validOwner(player)) return result(false, ResultCode.NOT_OWNER);
        if (!robot.powered()) return result(false, ResultCode.UNPOWERED);
        if (!safeNow(player)) return result(false, ResultCode.UNSAFE);
        if (game != Game.NONE) return result(false, ResultCode.GAME_ALREADY_ACTIVE);
        ResultCode beginCode = beginGame();
        if (beginCode != ResultCode.GAME_STARTED) return result(false, beginCode);
        game = Game.GUESS_NUMBER;
        secretNumber = robot.getRandom().nextInt(MIN_GUESS, MAX_GUESS + 1);
        attemptsRemaining = MAX_GUESS_ATTEMPTS;
        gameExpiresAt = now() + GAME_TIMEOUT_TICKS;
        return result(true, ResultCode.GAME_STARTED);
    }

    public Result submitGuessNumber(ServerPlayer player, int guess) {
        if (!validOwner(player)) return result(false, ResultCode.NOT_OWNER);
        if (!robot.powered()) { cancelSession(); return result(false, ResultCode.UNPOWERED); }
        if (!safeNow(player)) { cancelSession(); return result(false, ResultCode.UNSAFE); }
        if (game != Game.GUESS_NUMBER) return result(false, ResultCode.GAME_NOT_ACTIVE);
        if (expireIfNeeded()) return result(false, ResultCode.GAME_EXPIRED);
        if (guess < MIN_GUESS || guess > MAX_GUESS) return result(false, ResultCode.INVALID_INPUT);
        if (!takeActionSlot()) return result(false, ResultCode.RATE_LIMITED);
        if (guess == secretNumber) {
            cancelSession(); grantAffection(2); robot.smileAtOwner();
            return result(true, ResultCode.GAME_WON);
        }
        attemptsRemaining--;
        if (attemptsRemaining <= 0) {
            cancelSession(); return result(true, ResultCode.GAME_LOST);
        }
        return result(true, guess < secretNumber ? ResultCode.GUESS_LOW : ResultCode.GUESS_HIGH);
    }

    public Result startRockPaperScissors(ServerPlayer player) {
        if (!validOwner(player)) return result(false, ResultCode.NOT_OWNER);
        if (!robot.powered()) return result(false, ResultCode.UNPOWERED);
        if (!safeNow(player)) return result(false, ResultCode.UNSAFE);
        if (game != Game.NONE) return result(false, ResultCode.GAME_ALREADY_ACTIVE);
        ResultCode beginCode = beginGame();
        if (beginCode != ResultCode.GAME_STARTED) return result(false, beginCode);
        game = Game.ROCK_PAPER_SCISSORS;
        attemptsRemaining = 1;
        gameExpiresAt = now() + GAME_TIMEOUT_TICKS;
        return result(true, ResultCode.GAME_STARTED);
    }

    public Result submitRockPaperScissors(ServerPlayer player, Choice choice) {
        if (!validOwner(player)) return result(false, ResultCode.NOT_OWNER);
        if (!robot.powered()) { cancelSession(); return result(false, ResultCode.UNPOWERED); }
        if (!safeNow(player)) { cancelSession(); return result(false, ResultCode.UNSAFE); }
        if (game != Game.ROCK_PAPER_SCISSORS) return result(false, ResultCode.GAME_NOT_ACTIVE);
        if (expireIfNeeded()) return result(false, ResultCode.GAME_EXPIRED);
        if (choice == null) return result(false, ResultCode.INVALID_INPUT);
        if (!takeActionSlot()) return result(false, ResultCode.RATE_LIMITED);
        Choice robotChoice = Choice.values()[robot.getRandom().nextInt(Choice.values().length)];
        cancelSession();
        if (choice == robotChoice) return result(true, ResultCode.DRAW);
        boolean won = (choice == Choice.ROCK && robotChoice == Choice.SCISSORS)
                || (choice == Choice.PAPER && robotChoice == Choice.ROCK)
                || (choice == Choice.SCISSORS && robotChoice == Choice.PAPER);
        if (won) {
            grantAffection(1);
            robot.smileAtOwner();
            return result(true, ResultCode.WIN);
        }
        return result(true, ResultCode.LOSS);
    }

    /** Call from the UI close action or server-side owner logout. */
    public Result cancelGame(ServerPlayer player) {
        if (!validOwner(player)) return result(false, ResultCode.NOT_OWNER);
        if (game == Game.NONE) return result(false, ResultCode.GAME_NOT_ACTIVE);
        cancelSession();
        return result(true, ResultCode.CANCELLED);
    }

    public void tick() {
        ensureOwner();
        refreshDay();
        tickTransient();
        if (!holdingHands && game == Game.NONE) return;
        ServerPlayer owner = currentOnlineOwner();
        if (!robot.socialInteractionSafe() || owner == null || !owner.isAlive() || owner.isSpectator()
                || owner.level() != robot.level() || !holdingModeAllowed()
                || holdingHands && robot.distanceToSqr(owner) > HANDHOLD_RELEASE_RANGE_SQUARED) {
            if (holdingHands) stopHoldingHands();
            if (game != Game.NONE) cancelSession();
            return;
        }
        expireIfNeeded();
    }

    public boolean canHoldHandsWith(ServerPlayer player) {
        return holdingHands && validOwner(player) && player.isAlive() && !player.isSpectator()
                && player.level() == robot.level() && robot.socialInteractionSafe()
                && holdingModeAllowed() && robot.distanceToSqr(player) <= HANDHOLD_RELEASE_RANGE_SQUARED;
    }

    public CompoundTag saveWorld() { return save(); }

    /** A doll preserves relationship state but never resumes a transient game or hand-hold. */
    public CompoundTag saveForDoll() { return save(); }

    public void loadWorld(CompoundTag tag) { load(tag); }

    public void loadFromDoll(CompoundTag tag) { load(tag); }

    public void resetTransient() {
        cancelSession();
        pettingUntil = 0;
        holdingHands = false;
        robot.setPettingSignal(false);
        robot.setHoldingHandsSignal(false);
    }

    private CompoundTag save() {
        ensureOwner();
        refreshDay();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", NBT_VERSION);
        if (ownerId != null) tag.putUUID(KEY_OWNER, ownerId);
        tag.putInt(KEY_AFFECTION, affection);
        tag.putString(KEY_LANGUAGE, language.stableId());
        tag.putLong(KEY_DAY, day);
        tag.putInt(KEY_PETS, petsToday);
        tag.putInt(KEY_GAMES, gamesStartedToday);
        tag.putInt(KEY_DAILY_GAIN, affectionGainedToday);
        long currentTick = now();
        tag.putInt(KEY_PET_COOLDOWN, remainingCooldown(lastPetTick, PET_COOLDOWN_TICKS, currentTick));
        tag.putInt(KEY_ACTION_COOLDOWN, remainingCooldown(lastActionTick, ACTION_COOLDOWN_TICKS, currentTick));
        return tag;
    }

    private void load(CompoundTag tag) {
        resetTransient();
        ownerId = tag.hasUUID(KEY_OWNER) ? tag.getUUID(KEY_OWNER) : robot.getOwnerUUID();
        if (!Objects.equals(ownerId, robot.getOwnerUUID())) {
            resetRelationship(robot.getOwnerUUID());
            return;
        }
        affection = clamp(tag.getInt(KEY_AFFECTION), 0, 100);
        language = RobotReplyLanguage.fromStableId(tag.getString(KEY_LANGUAGE));
        day = tag.getInt("Version") >= 2 && tag.contains(KEY_DAY) ? tag.getLong(KEY_DAY) : Long.MIN_VALUE;
        petsToday = clamp(tag.getInt(KEY_PETS), 0, DAILY_PETS);
        gamesStartedToday = clamp(tag.getInt(KEY_GAMES), 0, DAILY_GAME_STARTS);
        affectionGainedToday = clamp(tag.getInt(KEY_DAILY_GAIN), 0, DAILY_AFFECTION_GAIN);
        long currentTick = now();
        lastPetTick = restoreCooldown(tag.getInt(KEY_PET_COOLDOWN), PET_COOLDOWN_TICKS, currentTick);
        lastActionTick = restoreCooldown(tag.getInt(KEY_ACTION_COOLDOWN), ACTION_COOLDOWN_TICKS, currentTick);
        refreshDay();
    }

    private boolean validOwner(ServerPlayer player) {
        ensureOwner();
        return player != null && robot.isAlive() && robot.isOwnedBy(player)
                && player.server == ((robot.level() instanceof ServerLevel serverLevel) ? serverLevel.getServer() : null);
    }

    private boolean safeNow(ServerPlayer player) {
        return validOwner(player) && player.isAlive() && !player.isSpectator() && robot.socialInteractionSafe();
    }

    private boolean nearAndVisible(ServerPlayer player) {
        return robot.canManage(player) && player.level() == robot.level()
                && robot.distanceToSqr(player) <= INTERACTION_RANGE_SQUARED && robot.hasLineOfSight(player);
    }

    private boolean holdingModeAllowed() {
        return (robot.mode() == RobotEntity.STAY || robot.mode() == RobotEntity.FOLLOW)
                && !robot.isWorking() && !robot.workTaskActive();
    }

    private static int remainingCooldown(long lastTick, int cooldown, long now) {
        if (lastTick == Long.MIN_VALUE) return 0;
        return clamp((int) Math.max(0L, cooldown - Math.max(0L, now - lastTick)), 0, cooldown);
    }

    private static long restoreCooldown(int remaining, int cooldown, long now) {
        int safeRemaining = clamp(remaining, 0, cooldown);
        return safeRemaining == 0 ? Long.MIN_VALUE : now - (cooldown - safeRemaining);
    }

    private ResultCode beginGame() {
        refreshDay();
        if (gamesStartedToday >= DAILY_GAME_STARTS) return ResultCode.DAILY_LIMIT;
        if (!takeActionSlot()) return ResultCode.RATE_LIMITED;
        gamesStartedToday++;
        return ResultCode.GAME_STARTED;
    }

    private boolean takeActionSlot() {
        long now = now();
        if (lastActionTick != Long.MIN_VALUE && now - lastActionTick < ACTION_COOLDOWN_TICKS) return false;
        lastActionTick = now;
        return true;
    }

    private void grantAffection(int amount) {
        refreshDay();
        int allowed = Math.min(amount, Math.max(0, DAILY_AFFECTION_GAIN - affectionGainedToday));
        allowed = Math.min(allowed, 100 - affection);
        affection += allowed;
        affectionGainedToday += allowed;
    }

    private boolean expireIfNeeded() {
        if (game != Game.NONE && now() >= gameExpiresAt) {
            cancelSession();
            return true;
        }
        return false;
    }

    private void tickTransient() {
        if (pettingUntil > 0 && !robot.socialInteractionSafe()) {
            pettingUntil = 0;
            robot.setPettingSignal(false);
        }
        if (pettingUntil > 0 && now() >= pettingUntil) {
            pettingUntil = 0;
            robot.setPettingSignal(false);
        }
    }

    private void stopHoldingHands() {
        holdingHands = false;
        robot.setHoldingHandsSignal(false);
        robot.getNavigation().stop();
    }

    private void cancelSession() {
        game = Game.NONE;
        secretNumber = 0;
        attemptsRemaining = 0;
        gameExpiresAt = 0;
    }

    private void ensureOwner() {
        UUID current = robot.getOwnerUUID();
        if (!Objects.equals(ownerId, current)) resetRelationship(current);
    }

    private void resetRelationship(UUID owner) {
        ownerId = owner;
        affection = 0;
        language = RobotReplyLanguage.JA;
        day = Long.MIN_VALUE;
        petsToday = gamesStartedToday = affectionGainedToday = 0;
        lastActionTick = lastPetTick = Long.MIN_VALUE;
        resetTransient();
    }

    private void refreshDay() {
        if (!(robot.level() instanceof ServerLevel serverLevel)) return;
        long currentDay = serverLevel.getServer().overworld().getGameTime() / 24_000L;
        // A server tick rollback must never reset daily interaction limits.
        if (day != Long.MIN_VALUE && currentDay < day) return;
        if (currentDay == day) return;
        day = currentDay;
        petsToday = gamesStartedToday = affectionGainedToday = 0;
    }

    private long now() { return robot.level().getGameTime(); }

    private ServerPlayer currentOnlineOwner() {
        if (ownerId == null || !(robot.level() instanceof ServerLevel serverLevel)) return null;
        return serverLevel.getServer().getPlayerList().getPlayer(ownerId);
    }

    private Result result(boolean accepted, ResultCode code) { return new Result(accepted, code, status()); }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /** Follows by navigation only; it deliberately never invokes FollowOwnerGoal's teleport logic. */
    public static final class HandHoldingGoal extends Goal {
        private final RobotEntity robot;
        private ServerPlayer owner;
        private double lastDistanceSq = Double.POSITIVE_INFINITY;
        private int pathFailureTicks;

        public HandHoldingGoal(RobotEntity robot) {
            this.robot = robot;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override public boolean canUse() {
            owner = robot.ownerPlayerForCompanionship();
            return owner != null && robot.companionship().canHoldHandsWith(owner)
                    && robot.distanceToSqr(owner) <= INTERACTION_RANGE_SQUARED;
        }

        @Override public boolean canContinueToUse() {
            return owner != null && owner.isAlive() && owner.level() == robot.level()
                    && robot.companionship().canHoldHandsWith(owner)
                    && robot.companionship().holdingModeAllowed();
        }

        @Override public void tick() {
            owner = robot.ownerPlayerForCompanionship();
            if (owner == null) return;
            double distance = robot.distanceToSqr(owner);
            if (distance > HANDHOLD_RELEASE_RANGE_SQUARED) {
                robot.companionship().stopHoldingHands();
                return;
            }
            if (distance > INTERACTION_RANGE_SQUARED) {
                boolean path = robot.getNavigation().moveTo(owner, 1.0);
                if (!path || distance >= lastDistanceSq - 0.04) pathFailureTicks++;
                else pathFailureTicks = 0;
                if (pathFailureTicks >= HANDHOLD_PATH_FAILURE_TICKS) {
                    robot.companionship().stopHoldingHands();
                    return;
                }
            } else {
                robot.getNavigation().stop();
                pathFailureTicks = 0;
            }
            lastDistanceSq = distance;
            robot.getLookControl().setLookAt(owner, 30.0F, 30.0F);
        }

        @Override public void stop() {
            owner = null;
            lastDistanceSq = Double.POSITIVE_INFINITY;
            pathFailureTicks = 0;
            robot.getNavigation().stop();
        }
    }
}
