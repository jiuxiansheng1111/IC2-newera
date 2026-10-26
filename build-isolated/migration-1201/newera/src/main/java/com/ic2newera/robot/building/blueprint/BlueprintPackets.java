package com.ic2newera.robot.building.blueprint;

import com.ic2newera.robot.*;
import com.ic2newera.robot.building.*;
import com.ic2newera.industrial.IndustrialMobileAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Uploading is a bounded proposal operation. World construction still requires START. */
public final class BlueprintPackets {
    public static final int CHUNK_BYTES = 24 * 1024;
    private static final Set<UUID> PROCESSING = new HashSet<>();
    private static final java.util.concurrent.ExecutorService PARSERS = java.util.concurrent.Executors.newFixedThreadPool(2, r -> { Thread t=new Thread(r,"newera-blueprint-import");t.setDaemon(true);return t; });
    private static final Map<ServerPlayer, Upload> UPLOADS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> NEXT_UPLOAD = new WeakHashMap<>();
    private static final class Upload {
        final Begin begin; final ByteArrayOutputStream bytes; final long expires;
        int next;
        Upload(Begin begin) { this.begin=begin; bytes=new ByteArrayOutputStream(Math.min(begin.size, CHUNK_BYTES)); expires=System.nanoTime()+120_000_000_000L; }
    }
    private BlueprintPackets() { }
    public static void register() {
        var c = RobotBuildPackets.CHANNEL;
        c.registerMessage(2, Begin.class, Begin::encode, Begin::decode, Begin::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        c.registerMessage(3, Chunk.class, Chunk::encode, Chunk::decode, Chunk::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        c.registerMessage(4, Reply.class, Reply::encode, Reply::decode, Reply::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public record Begin(UUID ticket, UUID robot, boolean remote, int workers, String filename, int size, String sha256) {
        static void encode(Begin p,FriendlyByteBuf b) { b.writeUUID(p.ticket); b.writeUUID(p.robot);b.writeBoolean(p.remote);b.writeVarInt(p.workers);b.writeUtf(p.filename,160);b.writeVarInt(p.size);b.writeUtf(p.sha256,64); }
        static Begin decode(FriendlyByteBuf b) { return new Begin(b.readUUID(),b.readUUID(),b.readBoolean(),b.readVarInt(),b.readUtf(160),b.readVarInt(),b.readUtf(64)); }
        static void handle(Begin p,Supplier<NetworkEvent.Context> ctx) {
            var context=ctx.get(); context.enqueueWork(()->{
                var player=context.getSender(); if(player==null)return;
                UPLOADS.entrySet().removeIf(e->e.getKey().hasDisconnected() || e.getValue().expires<System.nanoTime());
                if (p.workers<1 || p.workers>8 || p.size<1 || p.size>BlueprintImporter.MAX_INPUT_BYTES
                        || !validFilename(p.filename) || !p.sha256.matches("[a-fA-F0-9]{64}") || resolve(player,p)==null) {
                    fail(player,p.ticket,"无法导入：文件、机器人或操作权限无效。"); return;
                }
                if (System.nanoTime()<NEXT_UPLOAD.getOrDefault(player,0L) || UPLOADS.size()+PROCESSING.size()>=8 || UPLOADS.containsKey(player) || PROCESSING.contains(player.getUUID())) {
                    fail(player,p.ticket,"已有图纸正在处理，请稍后重试。"); return;
                }
                NEXT_UPLOAD.put(player,System.nanoTime()+2_000_000_000L);
                UPLOADS.put(player,new Upload(p));
            }); context.setPacketHandled(true);
        }
    }
    public record Chunk(UUID ticket,int index,byte[] content) {
        static void encode(Chunk p,FriendlyByteBuf b){b.writeUUID(p.ticket);b.writeVarInt(p.index);b.writeByteArray(p.content);}
        static Chunk decode(FriendlyByteBuf b){return new Chunk(b.readUUID(),b.readVarInt(),b.readByteArray(CHUNK_BYTES));}
        static void handle(Chunk p,Supplier<NetworkEvent.Context> ctx){
            var context=ctx.get();context.enqueueWork(()->{
                var player=context.getSender();if(player==null)return;
                var u=UPLOADS.get(player);if(u==null || !u.begin.ticket.equals(p.ticket))return;
                if(u.expires<System.nanoTime() || p.index!=u.next || p.content.length==0 || u.bytes.size()+p.content.length>u.begin.size){
                    UPLOADS.remove(player);fail(player,p.ticket,"图纸上传顺序或大小不正确，请重新提交。");return;
                }
                u.bytes.writeBytes(p.content);u.next++;
                if(u.bytes.size()!=u.begin.size)return;
                UPLOADS.remove(player);
                PROCESSING.add(player.getUUID());
                byte[] data=u.bytes.toByteArray();
                var server=player.server;
                UUID owner=player.getUUID();
                PARSERS.execute(()->{
                    try {
                        if(!digest(data).equalsIgnoreCase(u.begin.sha256))throw new IllegalArgumentException("文件校验失败。");
                        var plan=BlueprintImporter.read(u.begin.filename,data);
                        byte[] display=BlueprintWire.encode(plan);
                        String archived;
                        try { archived=archive(owner,u.begin.filename,data); } catch(IOException e) { archived="服务器归档未写入；本机inbox原文件保留。"; }
                        String stored=archived;
                        server.execute(()->{
                            PROCESSING.remove(owner);
                            if(player.hasDisconnected() || server.getPlayerList().getPlayer(owner)!=player)return;
                            var robot=resolve(player,u.begin);
                            if(robot==null){fail(player,p.ticket,"机器人已离线或超出操作距离。");return;}
                            if(u.begin.remote && !IndustrialPhoneItem.hasHeldCharge(player,IndustrialPhoneItem.COMMAND_COST)){fail(player,p.ticket,IndustrialPhoneItem.EMPTY_MESSAGE);return;}
                            var result=RobotBuildService.previewBlueprint(player,robot,u.begin.remote,plan,u.begin.workers);
                            if(!result.accepted()){fail(player,p.ticket,result.report());return;}
                            if(u.begin.remote && !IndustrialPhoneItem.consumeHeld(player,IndustrialPhoneItem.COMMAND_COST)){
                                RobotBuildService.discardProposal(player,result.proposal());fail(player,p.ticket,IndustrialPhoneItem.EMPTY_MESSAGE);return;
                            }
                            if(u.begin.remote)player.getInventory().setChanged();
                            send(player,new Reply(p.ticket,true,result.proposal(),clip(result.report()+"\n图纸归档："+stored),display));
                        });
                    }catch(Exception error){
                        server.execute(()->{PROCESSING.remove(owner);if(!player.hasDisconnected())fail(player,p.ticket,"图纸未执行："+(error instanceof IllegalArgumentException?error.getMessage():"无法读取或解析图纸，请检查文件格式。"));});
                    }
                });
            });context.setPacketHandled(true);
        }
    }
    public record Reply(UUID ticket,boolean success,UUID proposal,String report,byte[] plan) {
        static void encode(Reply p,FriendlyByteBuf b){b.writeUUID(p.ticket);b.writeBoolean(p.success);b.writeUUID(p.proposal);b.writeUtf(p.report,24000);b.writeByteArray(p.plan);}
        static Reply decode(FriendlyByteBuf b){return new Reply(b.readUUID(),b.readBoolean(),b.readUUID(),b.readUtf(24000),b.readByteArray(BlueprintWire.MAX_WIRE));}
        static void handle(Reply p,Supplier<NetworkEvent.Context> ctx){var c=ctx.get();c.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->com.ic2newera.client.robot.blueprint.DefaultBlueprintClient.receive(p)));c.setPacketHandled(true);}
    }
    private static RobotEntity resolve(ServerPlayer player,Begin p) {
        RobotEntity robot=p.remote?RobotContacts.resolve(player.server,p.robot):player.serverLevel().getEntity(p.robot) instanceof RobotEntity r?r:null;
        return robot!=null && robot.isAlive() && robot.isOwnedBy(player) && player.isAlive() && !player.isSpectator()
                && (p.remote?IndustrialMobileAccess.canReachRobot(player,robot):robot.canManage(player))?robot:null;
    }
    private static boolean validFilename(String s){return s!=null && s.length()<=160 && !s.contains("..") && !s.contains("/") && !s.contains("\\") && !s.contains(":")
            && s.matches("[^\\p{Cntrl}]+\\.(?i:json|nbt|schem|litematic|bbmodel)");}
    public static String digest(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String archive(UUID owner,String filename,byte[] bytes)throws IOException {
        Path base=FMLPaths.GAMEDIR.get().toRealPath();
        for(String component:List.of("IC2-NewEra-blueprints","received",owner.toString())){
            base=base.resolve(component);
            if(Files.isSymbolicLink(base))throw new IOException("symbolic directory");
            Files.createDirectories(base);
            if(!Files.isDirectory(base,LinkOption.NOFOLLOW_LINKS))throw new IOException("not a directory");
        }
        String name=digest(bytes).substring(0,16)+"-"+filename;Path target=base.resolve(name);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))return name;
        long count=0,size=0;
        try(var list=Files.newDirectoryStream(base)){for(Path p:list){count++;if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))size+=Files.size(p);}}
        if(count>=64 || size+bytes.length>128L*1024*1024)throw new IOException("archive full");
        Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);return name;
    }
    private static String clip(String s){return s.substring(0,Math.min(s.length(),24000));}
    private static void fail(ServerPlayer p,UUID ticket,String message){send(p,new Reply(ticket,false,RobotBuildService.NONE,clip(message),new byte[0]));}
    private static void send(ServerPlayer p,Reply reply){RobotBuildPackets.CHANNEL.send(PacketDistributor.PLAYER.with(()->p),reply);}
}
