package com.ic2newera.network;

import com.ic2newera.technology.TechnologyClientState;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;
import java.io.*;
import java.util.*;

/** Filtered server-to-client view, with no client completion command. */
public record TechnologyDataPacket(long revision, int index, int parts, CompoundTag snapshot) {
    public static final int MAX_PARTS = 1024;
    private static final int WIRE_BUDGET = 192 * 1024;
    private static final int ALLOCATION_BUDGET = 1024 * 1024;
    public TechnologyDataPacket { snapshot = snapshot.copy(); }
    public static List<TechnologyDataPacket> split(long revision, CompoundTag snapshot) {
        List<CompoundTag> chunks = new ArrayList<>();
        CompoundTag metadata = snapshot.copy(); metadata.remove("nodes");
        ListTag nodes = snapshot.getList("nodes", Tag.TAG_COMPOUND);
        for (int start = 0; start < nodes.size(); start += 16)
            splitRange(metadata, nodes, start, Math.min(start + 16, nodes.size()), chunks);
        if (nodes.isEmpty()) {
            metadata.put("nodes", new ListTag());
            if (!withinBudget(metadata)) throw new IllegalArgumentException("Technology metadata exceeds the safe packet budget");
            chunks.add(metadata);
        }
        if (chunks.size() > MAX_PARTS) throw new IllegalArgumentException("Technology snapshot has too many parts");
        List<TechnologyDataPacket> packets = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) packets.add(new TechnologyDataPacket(revision, i, chunks.size(), chunks.get(i)));
        return packets;
    }
    private static void splitRange(CompoundTag metadata, ListTag nodes, int start, int end, List<CompoundTag> chunks) {
        CompoundTag chunk = metadata.copy(); ListTag values = new ListTag();
        for (int i = start; i < end; i++) values.add(nodes.getCompound(i).copy());
        chunk.put("nodes", values);
        if (withinBudget(chunk)) { chunks.add(chunk); return; }
        if (end - start <= 1) throw new IllegalArgumentException("A technology node exceeds the safe packet budget");
        int middle = (start + end) / 2;
        splitRange(metadata, nodes, start, middle, chunks); splitRange(metadata, nodes, middle, end, chunks);
    }
    private static boolean withinBudget(CompoundTag value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(value, new DataOutputStream(bytes));
            if (bytes.size() > WIRE_BUDGET) return false;
            NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), new NbtAccounter(ALLOCATION_BUDGET));
            return true;
        } catch (IOException | RuntimeException error) { return false; }
    }
    public static void encode(TechnologyDataPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.revision); buffer.writeVarInt(packet.index); buffer.writeVarInt(packet.parts);
        buffer.writeNbt(packet.snapshot);
    }
    public static TechnologyDataPacket decode(FriendlyByteBuf buffer) {
        long revision = buffer.readLong(); int index = buffer.readVarInt(), parts = buffer.readVarInt();
        if (parts < 1 || parts > MAX_PARTS || index < 0 || index >= parts || buffer.readableBytes() > WIRE_BUDGET)
            throw new IllegalArgumentException("Invalid technology snapshot part");
        CompoundTag tag = buffer.readNbt();
        return new TechnologyDataPacket(revision, index, parts, tag == null ? new CompoundTag() : tag);
    }
    public static void handle(TechnologyDataPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> TechnologyClientState.acceptPart(packet.revision, packet.index, packet.parts, packet.snapshot));
        context.setPacketHandled(true);
    }
}
