package com.ic2newera.field;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ordered personal stops. Coordinates are copied from an authorized server record or the player's actual position. */
final class FieldItinerary {
    static final int CAP = 24;
    record Stop(UUID id, GlobalPos place, String name) {}
    private static CompoundTag root(ServerPlayer p) {
        var forge = p.getPersistentData();
        if (!forge.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)) forge.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return forge.getCompound(Player.PERSISTED_NBT_TAG);
    }
    private static List<Stop> read(ServerPlayer p) {
        var list = root(p).getList("NewEraItinerary", Tag.TAG_COMPOUND); List<Stop> out = new ArrayList<>();
        for (int i=0; i<Math.min(CAP,list.size()); i++) {
            var c=list.getCompound(i); var dim=ResourceLocation.tryParse(c.getString("World"));
            if (dim!=null && c.hasUUID("Id")) out.add(new Stop(c.getUUID("Id"),
                    GlobalPos.of(ResourceKey.create(Registries.DIMENSION,dim),BlockPos.of(c.getLong("Pos"))),c.getString("Name")));
        }
        return out;
    }
    private static void write(ServerPlayer p,List<Stop> route) {
        ListTag list=new ListTag();
        for (var s: route) { var tag=new CompoundTag(); tag.putUUID("Id",s.id); tag.putString("World",s.place.dimension().location().toString());
            tag.putLong("Pos",s.place.pos().asLong()); tag.putString("Name",s.name); list.add(tag); }
        root(p).put("NewEraItinerary",list);
    }
    static String add(ServerPlayer p, GlobalPos place,String label) {
        if (place==null) return "还没有这个地点的记录。";
        List<Stop> route=read(p);
        if (route.stream().anyMatch(s->s.place.equals(place) && s.name.equals(label))) return "这个地点已经在路线里。";
        if (route.size()>=CAP) return "路线已满，请先移除或完成一站。";
        route.add(new Stop(UUID.randomUUID(), place,label)); write(p,route);
        return "已加入第 " + route.size() + " 站 · " + label;
    }
    static List<FieldJournal.Candidate> choices(ServerPlayer p) {
        return read(p).stream().map(s -> new FieldJournal.Candidate(s.id.toString(),s.name+"　"+s.place.pos().toShortString())).toList();
    }
    static List<String> describe(ServerPlayer p) {
        var route=read(p); List<String> rows=new ArrayList<>();rows.add("行程　"+route.size()+" / "+CAP+" 站");
        for (int i=0;i<route.size();i++) {var s=route.get(i);rows.add((i+1)+"　"+s.name+"　"+s.place.dimension().location()+"　"+s.place.pos().toShortString());}
        return rows;
    }
    static String edit(ServerPlayer p,String op,String id) {
        if (op.equals("pin_here")) return add(p,GlobalPos.of(p.level().dimension(),p.blockPosition()),"我的路标");
        List<Stop> route=read(p); int idx=-1;
        for(int i=0;i<route.size();i++) if(route.get(i).id.toString().equals(id)){idx=i;break;}
        if (idx < 0) return "请先选择一站。";
        if(op.equals("run")) {
            var s=route.get(idx); FieldRoutes.start(p,s.place,s.name,null); FieldRoutes.itinerary(p,s.id);
            return "记录地点 · "+s.name;
        }
        if(op.equals("up") && idx>0) java.util.Collections.swap(route,idx,idx-1);
        else if(op.equals("down") && idx+1<route.size()) java.util.Collections.swap(route,idx,idx+1);
        else if(op.equals("drop")) route.remove(idx);
        write(p,route); return "路线已更新。";
    }
    static void arrived(ServerPlayer p, UUID id) {
        List<Stop> route=read(p);
        int i=0;while(i<route.size()&&!route.get(i).id.equals(id))i++;
        if(i==route.size()) return;
        route.remove(i); write(p,route);
        if(i<route.size()) { var next=route.get(i);FieldRoutes.start(p,next.place,next.name,null);FieldRoutes.itinerary(p,next.id); }
    }
}
