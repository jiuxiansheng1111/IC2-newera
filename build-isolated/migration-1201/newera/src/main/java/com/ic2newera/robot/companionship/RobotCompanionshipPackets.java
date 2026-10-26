package com.ic2newera.robot.companionship;

import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.robot.IndustrialPhoneItem;
import com.ic2newera.robot.RobotContacts;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotPackets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** One bounded client request and one entity-bound snapshot/result response for companionship actions. */
public final class RobotCompanionshipPackets {
    public static final int STATUS = 0, PET = 1, HOLD_HANDS = 2, START_GUESS = 3,
            GUESS = 4, START_RPS = 5, RPS = 6, SET_LANGUAGE = 7, CANCEL_GAME = 8;
    private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();
    private RobotCompanionshipPackets() { }

    public static void register() {
        RobotPackets.CHANNEL.registerMessage(9, Request.class, Request::encode, Request::decode, Request::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        RobotPackets.CHANNEL.registerMessage(10, Reply.class, Reply::encode, Reply::decode, Reply::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void request(UUID robot, boolean remote, int action, int value) {
        request(robot, remote, action, value, UUID.randomUUID());
    }
    public static void request(UUID robot, boolean remote, int action, int value, UUID ticket) {
        if (!validRequest(action, value)) return;
        RobotPackets.CHANNEL.sendToServer(new Request(ticket, robot, remote, action, value));
    }

    private static boolean validRequest(int action, int value) {
        return switch (action) {
            case STATUS, START_GUESS, START_RPS -> value == 0;
            case PET -> value == 0;
            case HOLD_HANDS -> value == 0 || value == 1;
            case GUESS -> value >= RobotCompanionship.MIN_GUESS && value <= RobotCompanionship.MAX_GUESS;
            case RPS -> value >= 0 && value <= 2;
            case SET_LANGUAGE -> value >= 0 && value <= 2;
            case CANCEL_GAME -> value == 0;
            default -> false;
        };
    }

    public record Request(UUID ticket, UUID robot, boolean remote, int action, int value) {
        static void encode(Request p, FriendlyByteBuf b) {
            b.writeUUID(p.ticket); b.writeUUID(p.robot); b.writeByte(p.remote ? 1 : 0);
            b.writeByte(p.action); b.writeByte(p.value);
        }
        static Request decode(FriendlyByteBuf b) {
            UUID ticket = b.readUUID(), robot = b.readUUID();
            int remote = b.readUnsignedByte(), action = b.readUnsignedByte(), value = b.readUnsignedByte();
            if (remote > 1 || !validRequest(action, value)) throw new IllegalArgumentException("Invalid companionship request");
            return new Request(ticket, robot, remote == 1, action, value);
        }
        static void handle(Request p, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context c = supplier.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player == null || player.hasDisconnected() || !player.isAlive()) return;
                RobotEntity robot = p.remote ? RobotContacts.resolve(player.server, p.robot)
                        : player.serverLevel().getEntity(p.robot) instanceof RobotEntity found ? found : null;
                if (robot == null || !robot.isAlive() || !robot.isOwnedBy(player)) {
                    send(player, p, false, RobotCompanionship.ResultCode.NOT_OWNER, null);
                    return;
                }
                if (p.remote && !IndustrialMobileAccess.hasPhone(player)) {
                    send(player, p, false, RobotCompanionship.ResultCode.NOT_OWNER, robot.companionship().status());
                    return;
                }
                boolean cancelling = p.action == CANCEL_GAME;
                if (!p.remote && !cancelling && !robot.canManage(player)) {
                    send(player, p, false, RobotCompanionship.ResultCode.TOO_FAR, robot.companionship().status());
                    return;
                }
                if (p.remote && !cancelling && !robot.powered()) {
                    send(player, p, false, RobotCompanionship.ResultCode.UNPOWERED, robot.companionship().status());
                    return;
                }
                if (p.remote && !cancelling && !IndustrialMobileAccess.canReachRobot(player, robot)) {
                    send(player, p, false, RobotCompanionship.ResultCode.NOT_OWNER, robot.companionship().status());
                    return;
                }
                if (p.remote && cancelling && (!(robot.level() instanceof net.minecraft.server.level.ServerLevel level)
                        || level.getServer() != player.server)) {
                    send(player, p, false, RobotCompanionship.ResultCode.NOT_OWNER, null);
                    return;
                }
                // Only owner-authorized local control can trigger physical affection actions.
                if (p.remote && (p.action == PET || p.action == HOLD_HANDS)) {
                    send(player, p, false, RobotCompanionship.ResultCode.TOO_FAR, null);
                    return;
                }
                long now = player.level().getGameTime();
                if (!cancelling && now < LAST_REQUEST.getOrDefault(player, Long.MIN_VALUE)) {
                    send(player, p, false, RobotCompanionship.ResultCode.RATE_LIMITED, robot.companionship().status());
                    return;
                }
                if (!cancelling) LAST_REQUEST.put(player, now + 4);
                int cost = p.action == STATUS ? IndustrialPhoneItem.READ_COST : cancelling ? 0 : IndustrialPhoneItem.COMMAND_COST;
                if (p.remote && cost > 0 && !IndustrialPhoneItem.hasHeldCharge(player, cost)) {
                    send(player, p, false, RobotCompanionship.ResultCode.UNPOWERED, robot.companionship().status());
                    return;
                }
                RobotCompanionship.Result result = null;
                if (p.action != STATUS) {
                    switch (p.action) {
                        case PET -> result = robot.companionship().pet(player);
                        case HOLD_HANDS -> result = robot.companionship().setHoldingHands(player, p.value == 1);
                        case START_GUESS -> result = robot.companionship().startGuessNumber(player);
                        case GUESS -> result = robot.companionship().submitGuessNumber(player, p.value);
                        case START_RPS -> result = robot.companionship().startRockPaperScissors(player);
                        case RPS -> result = robot.companionship().submitRockPaperScissors(player, choice(p.value));
                        case SET_LANGUAGE -> result = robot.companionship().setReplyLanguage(player, language(p.value));
                        case CANCEL_GAME -> result = robot.companionship().cancelGame(player);
                        default -> { return; }
                    }
                }
                if (p.remote && cost > 0 && (result == null || result.accepted())) {
                    IndustrialPhoneItem.consumeHeld(player, cost); player.getInventory().setChanged();
                }
                RobotCompanionship.Status status = result == null ? robot.companionship().status() : result.status();
                RobotCompanionship.ResultCode code = result == null ? RobotCompanionship.ResultCode.OK : result.code();
                send(player, p, result == null || result.accepted(), code, status);
            });
            c.setPacketHandled(true);
        }
    }

    private static RobotCompanionship.Choice choice(int id) {
        return switch (id) { case 0 -> RobotCompanionship.Choice.ROCK; case 1 -> RobotCompanionship.Choice.PAPER;
            case 2 -> RobotCompanionship.Choice.SCISSORS; default -> throw new IllegalArgumentException("Invalid choice"); };
    }
    private static RobotReplyLanguage language(int id) {
        return switch (id) { case 0 -> RobotReplyLanguage.ZH; case 1 -> RobotReplyLanguage.JA;
            case 2 -> RobotReplyLanguage.EN; default -> throw new IllegalArgumentException("Invalid language"); };
    }
    private static int languageId(RobotReplyLanguage language) {
        return switch (language) { case ZH -> 0; case JA -> 1; case EN -> 2; };
    }
    private static int tierId(RobotCompanionship.Tier tier) {
        return switch (tier) { case STRANGER -> 0; case FAMILIAR -> 1; case CLOSE -> 2; case DEVOTED -> 3; };
    }
    private static int gameId(RobotCompanionship.Game game) {
        return switch (game) { case NONE -> 0; case GUESS_NUMBER -> 1; case ROCK_PAPER_SCISSORS -> 2; };
    }
    private static int codeId(RobotCompanionship.ResultCode code) {
        return switch (code) {
            case OK -> 0; case NOT_OWNER -> 1; case TOO_FAR -> 2; case UNPOWERED -> 3;
            case UNSAFE -> 4; case RATE_LIMITED -> 5; case DAILY_LIMIT -> 6;
            case GAME_NOT_ACTIVE -> 7; case INVALID_INPUT -> 8; case GAME_EXPIRED -> 9;
            case GAME_STARTED -> 10; case GUESS_HIGH -> 11; case GUESS_LOW -> 12;
            case GAME_WON -> 13; case GAME_LOST -> 14; case WIN -> 15; case LOSS -> 16; case DRAW -> 17;
            case GAME_ALREADY_ACTIVE -> 18; case CANCELLED -> 19;
        };
    }
    private static RobotCompanionship.ResultCode code(int id) {
        return switch (id) {
            case 0 -> RobotCompanionship.ResultCode.OK; case 1 -> RobotCompanionship.ResultCode.NOT_OWNER;
            case 2 -> RobotCompanionship.ResultCode.TOO_FAR; case 3 -> RobotCompanionship.ResultCode.UNPOWERED;
            case 4 -> RobotCompanionship.ResultCode.UNSAFE; case 5 -> RobotCompanionship.ResultCode.RATE_LIMITED;
            case 6 -> RobotCompanionship.ResultCode.DAILY_LIMIT; case 7 -> RobotCompanionship.ResultCode.GAME_NOT_ACTIVE;
            case 8 -> RobotCompanionship.ResultCode.INVALID_INPUT; case 9 -> RobotCompanionship.ResultCode.GAME_EXPIRED;
            case 10 -> RobotCompanionship.ResultCode.GAME_STARTED; case 11 -> RobotCompanionship.ResultCode.GUESS_HIGH;
            case 12 -> RobotCompanionship.ResultCode.GUESS_LOW; case 13 -> RobotCompanionship.ResultCode.GAME_WON;
            case 14 -> RobotCompanionship.ResultCode.GAME_LOST; case 15 -> RobotCompanionship.ResultCode.WIN;
            case 16 -> RobotCompanionship.ResultCode.LOSS; case 17 -> RobotCompanionship.ResultCode.DRAW;
            case 18 -> RobotCompanionship.ResultCode.GAME_ALREADY_ACTIVE; case 19 -> RobotCompanionship.ResultCode.CANCELLED;
            default -> throw new IllegalArgumentException("Invalid companionship result code");
        };
    }
    private static void send(ServerPlayer player, Request request, boolean accepted,
                             RobotCompanionship.ResultCode code, RobotCompanionship.Status status) {
        Reply reply = status == null
                ? new Reply(request.ticket, request.robot, request.remote, accepted, codeId(code), false, 0, 0, false, false, 0, 0, 0)
                : new Reply(request.ticket, request.robot, request.remote, accepted, codeId(code), true,
                    status.affection(), tierId(status.tier()), status.petting(), status.holdingHands(), gameId(status.game()),
                    status.attemptsRemaining(), languageId(status.language()));
        RobotPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
    }

    public record Reply(UUID ticket, UUID robot, boolean remote, boolean accepted, int code, boolean hasStatus,
                        int affection, int tier, boolean petting, boolean holdingHands, int game,
                        int attemptsRemaining, int language) {
        static void encode(Reply p, FriendlyByteBuf b) {
            b.writeUUID(p.ticket); b.writeUUID(p.robot); b.writeByte(p.remote ? 1 : 0); b.writeByte(p.accepted ? 1 : 0);
            b.writeByte(p.code); b.writeByte(p.hasStatus ? 1 : 0); b.writeByte(p.affection); b.writeByte(p.tier);
            b.writeByte(p.petting ? 1 : 0); b.writeByte(p.holdingHands ? 1 : 0); b.writeByte(p.game);
            b.writeByte(p.attemptsRemaining); b.writeByte(p.language);
        }
        static Reply decode(FriendlyByteBuf b) {
            UUID ticket = b.readUUID(), robot = b.readUUID();
            int remote = b.readUnsignedByte(), accepted = b.readUnsignedByte(), code = b.readUnsignedByte(), has = b.readUnsignedByte();
            int affection = b.readUnsignedByte(), tier = b.readUnsignedByte(), petting = b.readUnsignedByte(), hands = b.readUnsignedByte();
            int game = b.readUnsignedByte(), attempts = b.readUnsignedByte(), language = b.readUnsignedByte();
            if (remote > 1 || accepted > 1 || has > 1 || petting > 1 || hands > 1 || affection > 100 || tier > 3
                    || game > 2 || attempts > 3 || language > 2 || code > 19 || (has == 0 && (affection != 0 || tier != 0 || petting != 0 || hands != 0 || game != 0 || attempts != 0 || language != 0)))
                throw new IllegalArgumentException("Invalid companionship reply");
            return new Reply(ticket, robot, remote == 1, accepted == 1, code, has == 1, affection, tier, petting == 1,
                    hands == 1, game, attempts, language);
        }
        static void handle(Reply p, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context c = supplier.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.ic2newera.client.robot.RobotCompanionshipScreen.receive(p)));
            c.setPacketHandled(true);
        }
    }
}
