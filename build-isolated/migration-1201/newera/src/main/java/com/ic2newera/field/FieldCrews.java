package com.ic2newera.field;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Expedition-only invitations for survival players who do not have a scoreboard team. */
public final class FieldCrews extends SavedData {
    private static final String ID = "ic2newera_expedition_crews";
    private final Map<UUID, UUID> members = new HashMap<>();
    private final Map<UUID, Invite> invitations = new HashMap<>();
    private record Invite(UUID leader, long until) {}

    private static FieldCrews data(ServerPlayer player) {
        return player.server.overworld().getDataStorage().computeIfAbsent(FieldCrews::load, FieldCrews::new, ID);
    }
    public static String key(ServerPlayer player) {
        if (player.getTeam() != null) return "team:" + player.getTeam().getName();
        UUID leader = data(player).members.get(player.getUUID());
        return leader == null ? "solo:" + player.getUUID() : "crew:" + leader;
    }
    public static String describe(ServerPlayer player) {
        if (player.getTeam() != null) return "队伍：" + player.getTeam().getName();
        FieldCrews d = data(player);
        UUID leader = d.members.get(player.getUUID());
        if (leader != null) {
            long size = d.members.values().stream().filter(leader::equals).count();
            return "远征小队 · " + size + " 人" + (leader.equals(player.getUUID()) ? " · 你是队长" : "");
        }
        Invite inv = d.invitations.get(player.getUUID());
        if (inv != null && inv.until > player.server.overworld().getGameTime()) {
            var inviter = player.server.getPlayerList().getPlayer(inv.leader);
            return "邀请来自 " + (inviter == null ? "已离线的队长" : inviter.getGameProfile().getName()) + "，点「接受邀请」入队";
        }
        return "个人远征 · 可邀请在线朋友共享清单";
    }
    static String act(ServerPlayer player, String action, String argument) {
        FieldCrews d = data(player);
        UUID me = player.getUUID(), leader = d.members.get(me);
        var manager = player.server.getPlayerList();
        long now = player.server.overworld().getGameTime();
        d.invitations.values().removeIf(i -> i.until <= now);
        if (action.equals("leave")) {
            if (leader == null) return player.getTeam() != null ? "你使用的是原有游戏队伍，请由该队伍管理成员。" : "你当前是个人远征。";
            if (me.equals(leader)) return "你是队长，使用「解散小队」会结束整支远征小队。";
            d.members.remove(me); d.setDirty();
            return "已离开远征小队，个人远征记录已恢复。";
        }
        if (action.equals("disband")) {
            if (!me.equals(leader)) return "只有远征小队队长可以解散。";
            d.members.values().removeIf(me::equals);
            d.invitations.values().removeIf(inv -> me.equals(inv.leader)); d.setDirty();
            return "小队已解散，各成员回到自己的远征记录。";
        }
        if (player.getTeam() != null) return "当前已使用同名游戏队伍共享远征，请让朋友加入你的游戏队伍。";
        if (action.equals("accept")) {
            Invite i = d.invitations.get(me);
            if (i == null || leader != null || !i.leader.equals(d.members.get(i.leader))) return "没有可接受的邀请，或你已在小队中。";
            var host = manager.getPlayer(i.leader);
            if (host == null || host.getTeam() != null) return "队长当前不在这支远征小队中，请稍后再试。";
            if (d.members.values().stream().filter(i.leader::equals).count() >= 8) return "这支小队已经有八人。";
            d.members.put(me, i.leader); d.invitations.remove(me); d.setDirty();
            return "已加入 " + host.getGameProfile().getName() + " 的远征小队。";
        }
        if (!action.equals("invite")) return "未知的小队操作。";
        if (leader != null && !leader.equals(me)) return "请让远征队长邀请新成员。";
        var friend = manager.getPlayerByName(argument.trim());
        if (friend == null || friend == player) return "请输入一位在线朋友的完整游戏名。";
        if (friend.getTeam() != null || d.members.containsKey(friend.getUUID())) return "这位朋友已经在其他队伍中。";
        if (d.members.values().stream().filter(me::equals).count() >= 8) return "这支小队已经有八人。";
        if (leader == null) {
            String previous = key(player);
            d.members.put(me, me); d.setDirty();
            LunarExpedition.sharePlan(player, previous, key(player));
        }
        d.invitations.put(friend.getUUID(), new Invite(me, now + 20 * 60 * 5));
        friend.sendSystemMessage(net.minecraft.network.chat.Component.literal(player.getGameProfile().getName()
                + " 邀你加入月球远征。在工业手机的「远征手记 → 小队」中接受，五分钟内有效。"));
        return "邀请已发给 " + friend.getGameProfile().getName() + "。";
    }
    private static FieldCrews load(CompoundTag tag) {
        FieldCrews d = new FieldCrews();
        for (var row : tag.getList("Members", Tag.TAG_COMPOUND)) {
            var c = (CompoundTag)row;
            if (c.hasUUID("Player") && c.hasUUID("Leader")) d.members.put(c.getUUID("Player"), c.getUUID("Leader"));
        }
        return d;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        var rows = new net.minecraft.nbt.ListTag();
        members.forEach((player, leader) -> {
            var row = new CompoundTag(); row.putUUID("Player", player); row.putUUID("Leader", leader); rows.add(row);
        });
        tag.put("Members", rows); return tag;
    }
}
