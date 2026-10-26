package com.ic2newera.robot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import com.ic2newera.robot.companionship.RobotReplyLanguage;

/** Shared server-side route for spoken movement intents. */
public final class RobotMovementOrders {
    public record Result(boolean accepted, RobotMovementIntent intent, String code, String message) {
        public String localizedMessage(RobotReplyLanguage language) {
            if (language == null) return message;
            if (intent == RobotMovementIntent.STATUS && code.startsWith("STATUS:")) return statusLocalized(message, language);
            boolean en = language == RobotReplyLanguage.EN;
            boolean zh = language == RobotReplyLanguage.ZH;
            if ("ALREADY".equals(code) && intent == RobotMovementIntent.RESUME) return zh
                    ? "保存的任务已经在运行。现有任务记录会继续保留。"
                    : en ? "The saved task is already running. Its current record is still in place."
                    : "保存済みのタスクはすでに実行中です。現在の記録はそのまま残っています。";
            if ("OK".equals(code)) return switch (intent) {
                case COME -> zh ? "我走过去找你啦。等我一下，我们一会儿见。"
                        : en ? "I’m walking over to you. Give me a moment, and we can catch up."
                        : "そっちへ歩いて行くね。少し待っていてくれるとうれしいな。";
                case FOLLOW -> zh ? "我会步行跟着你。接下来想去哪里，我们一起走吧。"
                        : en ? "I’ll follow you on foot. Where shall we head next?"
                        : "歩いてそばについていくね。次はどこへ行こうか。";
                case HOME -> zh ? "我沿着路回家啦。等你回来，我们再慢慢聊。"
                        : en ? "I’m heading home on foot. We can catch up when you’re back."
                        : "歩いて家に戻るね。帰ったら、またゆっくりお話ししよう。";
                case SET_HOME -> zh ? "我把这里记作家了。以后你让我回家，我会沿路走到这里。"
                        : en ? "I’ve marked this spot as home. When you ask me to return, I’ll walk back here."
                        : "ここを家として記録しました。帰るときは歩いてこの場所を目指します。";
                case STOP -> message.contains("暂停工作")
                        ? zh ? "工作已暂停，任务记录仍保留。移动也停下来了，你可以稍后再安排。"
                                : en ? "Work is paused, and its task record is preserved. I’ve stopped moving too, so you can decide what’s next."
                                : "作業を一時停止し、タスク記録はそのまま残しました。移動も止めたので、次の指示を待っています。"
                        : zh ? "移动已停止，现有任务记录仍然保留。你可以继续安排下一步。"
                                : en ? "I’ve stopped moving, and the existing task record is still preserved. You can decide what to do next."
                                : "移動を止めました。これまでのタスク記録はそのまま残っています。";
                case WAIT -> zh ? "我会先在这里等你。正在进行的工作已暂停，任务记录仍然保留。"
                        : en ? "I’ll wait here for you. Any active work is paused, and its task record is preserved."
                        : "ここで待っています。進行中の作業は一時停止し、タスク記録はそのまま残しています。";
                case RESUME -> {
                    String task = message.contains("STATUS:BUILD") ? "BUILD"
                            : message.contains("STATUS:COOK") ? "COOK"
                            : message.contains("STATUS:COLLECT") ? "COLLECT" : "RUNNING";
                    yield switch (task) {
                        case "BUILD" -> zh ? "已继续之前保存的建造或生产任务。原有进度和任务记录会接着保留。"
                                : en ? "I’ve resumed the saved build or production task. Its existing progress and record are preserved."
                                : "保存済みの建築・生産タスクを再開しました。これまでの進行状況と記録はそのまま保たれます。";
                        case "COOK" -> zh ? "已继续之前保存的烹饪任务。原有进度和任务记录会接着保留。"
                                : en ? "I’ve resumed the saved cooking task. Its existing progress and record are preserved."
                                : "保存済みの料理タスクを再開しました。これまでの進行状況と記録はそのまま保たれます。";
                        case "COLLECT" -> zh ? "已继续之前保存的采集任务。目标和已有任务记录会接着保留。"
                                : en ? "I’ve resumed the saved collection task. Its target and existing record are preserved."
                                : "保存済みの採集タスクを再開しました。目標とこれまでの記録はそのまま保たれます。";
                        default -> zh ? "保存的任务已经在运行。现有任务记录会继续保留。"
                                : en ? "The saved task is already running. Its current record is still in place."
                                : "保存済みのタスクはすでに実行中です。現在の記録はそのまま残っています。";
                    };
                }
                case STATUS -> statusLocalized(message, language);
                default -> message;
            };
            if (language == RobotReplyLanguage.ZH) return message;
            return switch (code) {
                case "INVALID" -> en ? "I couldn’t identify a clear movement request." : "移動の指示を確認できませんでした。";
                case "OWNER" -> en ? "The robot’s owner or online state is invalid." : "ロボットの所有者または接続状態を確認できません。";
                case "REACH" -> en ? "I can’t connect to this robot right now." : "今はこのロボットに接続できません。";
                case "POWER" -> intent == RobotMovementIntent.RESUME
                        ? en ? "The robot needs power before it can resume work." : "作業を再開するにはロボットの充電が必要です。"
                        : en ? "The robot needs power before it can move." : "移動するにはロボットの充電が必要です。";
                case "RANGE" -> en ? "Stand within 3 blocks and keep line of sight to set home." : "家を設定するには3ブロック以内で視線を通してください。";
                case "UNSAFE" -> en ? "This position has no safe place to stand, so it cannot be set as home." : "安全に立てる場所ではないため、家に設定できません。";
                case "BUSY" -> intent == RobotMovementIntent.RESUME
                        ? en ? "I’m already working and have no paused task to resume." : "作業中で、再開できる一時停止タスクはありません。"
                        : en ? "I’m working. Pause the task before giving me a movement order." : "作業中です。移動を指示する前に作業を一時停止してください。";
                case "DIMENSION" -> en ? "I can only walk to you within the same dimension. A cross-dimension trip needs an explicit wormhole request and module." : "同じディメンション内だけ歩いて移動できます。異なるディメンションへの移動には、明示的なワームホール指示とモジュールが必要です。";
                case "NO_HOME" -> en ? "I don’t have a home position yet." : "まだ家の位置が設定されていません。";
                case "UNLOADED" -> en ? "My home is not loaded, so I did not start moving. Return nearby and try again." : "家の場所が読み込まれていないため、移動を開始していません。近くまで戻ってから再度指示してください。";
                case "NO_TASK" -> en ? "There is no saved task to resume." : "再開できる保存済みタスクはありません。";
                case "ALREADY" -> en ? "The saved task is already running." : "保存済みのタスクはすでに実行中です。";
                case "LOCKED" -> en ? "A partially completed line refit needs attention before I can resume or move." : "途中の生産ライン改造記録を確認するまで、再開や移動はできません。";
                case "STATUS:READY", "STATUS:WORKING", "STATUS:PAUSED", "STATUS:UNPOWERED" -> statusLocalized(message, language);
                default -> message;
            };
        }
        private static String statusLocalized(String message, RobotReplyLanguage language) {
            if (message == null) return "";
            boolean en = language == RobotReplyLanguage.EN;
            boolean zh = language == RobotReplyLanguage.ZH;
            String detail = message.substring(message.indexOf('|') + 1);
            String home = "";
            int homeAt = detail.indexOf("；家：");
            if (homeAt >= 0) home = detail.substring(homeAt + 3).trim();
            String state = message.startsWith("STATUS:UNPOWERED|") ? (en ? "no usable battery" : zh ? "无可用电池" : "使用できるバッテリーがありません")
                    : message.startsWith("STATUS:WORKING|") ? (en ? "working" : zh ? "正在工作" : "作業中です")
                    : message.startsWith("STATUS:PAUSED|") ? (en ? "task paused" : zh ? "任务已暂停" : "タスクは一時停止中です")
                    : (en ? "on standby" : zh ? "待命中" : "待機中です");
            if (home.isBlank()) return en ? "Robot status: " + state + "." : zh ? "机器人状态：" + state + "。" : "ロボットの状態：" + state + "。";
            return en ? "Robot status: " + state + ". Home: " + home : zh ? "机器人状态：" + state + "。家：" + home : "ロボットの状態：" + state + "。家：" + home;
        }
    }
    private RobotMovementOrders() { }
    public static String localizedNotice(String code, RobotReplyLanguage language) {
        if (language == null || language == RobotReplyLanguage.ZH) {
            return "FOLLOW_RANGE".equals(code) ? "前方暂时没有安全的已加载步行路线；我会隔一段时间再尝试。"
                    : "前方没有安全步行路线，移动目标已保留；位置变化后会再次尝试。";
        }
        if (language == RobotReplyLanguage.EN) return "FOLLOW_RANGE".equals(code)
                ? "There is no safe, loaded walking route ahead right now. I’ll try again shortly."
                : "There is no safe walking route ahead. I’ve kept the destination and will retry after a short pause.";
        return "FOLLOW_RANGE".equals(code)
                ? "この先に安全な読み込み済みの道がありません。少し時間をおいて再試行します。"
                : "安全な歩行ルートがありません。目的地を保持し、少し待ってから再試行します。";
    }
    public static Result apply(ServerPlayer player, RobotEntity robot, boolean remote, RobotMovementIntent intent) {
        if (player == null || robot == null || intent == null || intent == RobotMovementIntent.NONE)
            return result(false, intent, "INVALID", "没有识别到明确的移动指令。");
        if (!player.isAlive() || !robot.isAlive() || !robot.isOwnedBy(player)) return result(false, intent, "OWNER", "机器人归属或在线状态无效。");
        if (remote ? !com.ic2newera.industrial.IndustrialMobileAccess.canReachRobot(player, robot)
                   : player.level() != robot.level() || !robot.canManage(player))
            return result(false, intent, "REACH", "当前无法连接到这台机器人。");
        if (intent == RobotMovementIntent.STATUS) {
            String state = robot.movementBusy() ? "STATUS:WORKING|" : robot.hasPausedCollectionTask() || robot.hasCookingTask() && robot.mode() != RobotEntity.COOKING
                    || robot.buildTask() != null && robot.mode() == RobotEntity.STAY ? "STATUS:PAUSED|" : "STATUS:READY|";
            if (!robot.powered()) state = "STATUS:UNPOWERED|";
            return result(true, intent, state.substring(0, state.indexOf('|')), state + robot.status() + "；家：" + robot.homeDimension() + " " + robot.homePos().toShortString());
        }
        if (intent == RobotMovementIntent.STOP) {
            boolean paused = robot.pauseWorkPreservingTask();
            robot.stopMovement();
            return result(true, intent, "OK", paused ? "已暂停工作并保留任务记录。" : "已停止移动，任务记录已保留。");
        }
        if (intent == RobotMovementIntent.WAIT) {
            robot.pauseWorkPreservingTask();
            robot.stopMovement(); return result(true, intent, "OK", "我会在这里等你；工作已暂停，任务记录保留。");
        }
        if (intent == RobotMovementIntent.RESUME) {
            if (!robot.powered()) return result(false, intent, "POWER", "机器人电量不足，无法恢复工作。");
            if (robot.lineRefitTask() != null || robot.industrialRecoveryBlocked()) {
                if (robot.industrialRecoveryBlocked()) return result(false, intent, "LOCKED", "工业恢复记录需要先核对，不能直接恢复。");
                if (robot.movementBusy() && robot.mode() != RobotEntity.STAY)
                    return result(true, intent, "ALREADY", "STATUS:BUILD|" + robot.status());
                robot.resumeBuild();
                return result(robot.mode() == RobotEntity.REFITTING || robot.mode() == RobotEntity.PRODUCTION, intent,
                        robot.mode() == RobotEntity.REFITTING || robot.mode() == RobotEntity.PRODUCTION ? "OK" : "LOCKED", "STATUS:BUILD|" + robot.status());
            }
            if (robot.buildTask() != null || robot.industrialGoal() != null && robot.industrialGoal().hasSavedState()) {
                if (robot.movementBusy() && robot.mode() != RobotEntity.STAY) return result(true, intent, "ALREADY", "STATUS:BUILD|" + robot.status());
                robot.resumeBuild();
                return robot.mode() != RobotEntity.STAY ? result(true, intent, "OK", "STATUS:BUILD|" + robot.status())
                        : result(false, intent, "NO_TASK", "没有可恢复的建造或生产任务。");
            }
            if (robot.hasCookingTask()) {
                if (robot.mode() == RobotEntity.COOKING) return result(true, intent, "ALREADY", "STATUS:COOK|" + robot.status());
                if (robot.resumeCookingTask()) return result(true, intent, "OK", "STATUS:COOK|" + robot.status());
            }
            if (robot.hasPausedCollectionTask()) {
                if (robot.resumePausedCollection()) return result(true, intent, "OK", "STATUS:COLLECT|" + robot.status());
                return result(false, intent, "BUSY", "当前状态下无法恢复已暂停的采集任务。");
            }
            if (robot.movementBusy()) return result(false, intent, "BUSY", "机器人正在执行没有可恢复记录的工作。");
            return result(false, intent, "NO_TASK", "没有可恢复的已保存任务。");
        }
        if (!robot.powered()) return result(false, intent, "POWER", "机器人电量不足，无法移动。");
        if (intent == RobotMovementIntent.SET_HOME) {
            if (remote || player.level() != robot.level() || robot.distanceToSqr(player) > 9 || !robot.hasLineOfSight(player))
                return result(false, intent, "RANGE", "设置家需要在机器人3格内并保持视线。");
            if (!robot.setHomeAnchor(robot.blockPosition())) return result(false, intent, "UNSAFE", "当前位置没有安全的落脚空间，无法设为家。");
            return result(true, intent, "OK", "已把当前位置设为家。");
        }
        if (robot.lineRefitTask() != null || robot.industrialRecoveryBlocked())
            return result(false, intent, "LOCKED", "存在未完成的生产线改造或工业恢复记录，需先检查现场，暂不能移动。");
        if (robot.movementBusy()) return result(false, intent, "BUSY", "机器人正在执行工作；请先暂停工作，再下达移动指令。");
        if (intent == RobotMovementIntent.FOLLOW || intent == RobotMovementIntent.COME) {
            if (player.level() != robot.level()) return result(false, intent, "DIMENSION", "机器人只能在同一维度步行前往你身边。");
            if (intent == RobotMovementIntent.FOLLOW) robot.beginMovement(intent, robot.level().dimension().location().toString(), null);
            else robot.beginMovement(intent, robot.level().dimension().location().toString(), player.blockPosition().immutable());
            return result(true, intent, "OK", intent == RobotMovementIntent.FOLLOW ? "已开始步行跟随。" : "已开始步行前往你身边。");
        }
        if (intent == RobotMovementIntent.HOME) {
            if (!robot.hasHomeAnchor()) return result(false, intent, "NO_HOME", "还没有设置机器人之家。");
            if (!robot.homeDimension().equals(robot.level().dimension().location().toString()))
                return result(false, intent, "DIMENSION", "家在其他维度，机器人无法步行过去；需要明确的虫洞请求与模块支持。");
            if (!robot.level().hasChunkAt(robot.homePos())) return result(false, intent, "UNLOADED", "家的位置尚未加载，未开始移动。请先回到附近再下令。");
            robot.beginMovement(intent, robot.homeDimension(), robot.homePos());
            return result(true, intent, "OK", "已开始步行回家。");
        }
        return result(false, intent, "INVALID", "这个移动指令当前无法执行。");
    }
    private static Result result(boolean accepted, RobotMovementIntent intent, String code, String message) {
        return new Result(accepted, intent, code, message);
    }
}
