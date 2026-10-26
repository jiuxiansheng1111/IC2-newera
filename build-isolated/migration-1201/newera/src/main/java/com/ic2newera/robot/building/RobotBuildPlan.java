package com.ic2newera.robot.building;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** A bounded, owner-approved description of a robot construction. */
public record RobotBuildPlan(String kind, String title, List<Part> parts, List<String> notes,
                             CompoundTag metadata) {
    public static final int MAX_PARTS = 2048;
    private static final int MAX_NOTES = 64;
    private static final Set<Block> FORBIDDEN = Set.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
            Blocks.BARRIER, Blocks.BEDROCK, Blocks.STRUCTURE_BLOCK, Blocks.STRUCTURE_VOID,
            Blocks.JIGSAW, Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.NETHER_PORTAL);

    public RobotBuildPlan {
        if (!"house".equals(kind) && !"production".equals(kind)) throw new IllegalArgumentException("kind must be house or production");
        title = bounded(title, 96);
        if (parts == null || parts.isEmpty() || parts.size() > MAX_PARTS) throw new IllegalArgumentException("construction must contain 1 to 2048 parts");
        parts = List.copyOf(parts);
        if (notes == null || notes.size() > MAX_NOTES) throw new IllegalArgumentException("too many notes");
        notes = notes.stream().map(s -> bounded(s, 256)).toList();
        metadata = metadata == null ? new CompoundTag() : metadata.copy();
        if (metadata.getAllKeys().size() > 32 || metadata.toString().length() > 32768)
            throw new IllegalArgumentException("metadata is too large");
        Set<BlockPos> uniqueOffsets = new HashSet<>();
        for (Part part : parts) {
            validatePart(part);
            if (!uniqueOffsets.add(part.offset())) throw new IllegalArgumentException("duplicate construction position");
        }
    }

    public record Part(BlockPos offset, String blockId, Map<String, String> properties, String phase) {
        /** Some native blocks have distinct placement items for state variants (IC2 insulation). */
        public Item materialItem() {
            BlockState target = state();
            if (target.getBlock() == Blocks.WATER) return Items.WATER_BUCKET;
            RobotPowerCable cable = RobotPowerCable.forPart(this);
            if (cable != null) return cable.materialItem();
            return target.getBlock().asItem();
        }

        public Part {
            if (offset == null) throw new IllegalArgumentException("missing part position");
            offset = offset.immutable();
            if (offset.getX() < -48 || offset.getX() > 48 || offset.getZ() < -48 || offset.getZ() > 48
                    || offset.getY() < 0 || offset.getY() > 16)
                throw new IllegalArgumentException("part is outside the supported construction bounds");
            ResourceLocation id = ResourceLocation.tryParse(blockId == null ? "" : blockId);
            if (id == null) throw new IllegalArgumentException("invalid block id");
            blockId = id.toString();
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (properties.size() > 16) throw new IllegalArgumentException("too many block properties");
            for (Map.Entry<String, String> entry : properties.entrySet())
                if (entry.getKey().length() > 32 || entry.getValue() == null || entry.getValue().length() > 64)
                    throw new IllegalArgumentException("block property is too long");
            phase = bounded(phase == null ? "main" : phase, 48);
        }

        /** Resolves and validates against the server's live Forge block registry. */
        public BlockState state() {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            Block block = id == null ? null : BuiltInRegistries.BLOCK.get(id);
            if (block == null || block == Blocks.AIR || FORBIDDEN.contains(block))
                throw new IllegalArgumentException("unsupported or unsafe block: " + blockId);
            BlockState state = block.defaultBlockState();
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property == null) throw new IllegalArgumentException("unknown property " + entry.getKey() + " for " + blockId);
                state = apply(state, property, entry.getValue(), blockId);
            }
            return state;
        }
    }

    @Override public CompoundTag metadata() { return metadata.copy(); }

    public Map<String, Integer> materials() {
        Map<String, Integer> result = new TreeMap<>();
        for (Part part : parts) {
            BlockState state = part.state();
            if (state.getBlock() instanceof DoorBlock && !state.hasProperty(DoorBlock.HALF))
                throw new IllegalArgumentException("invalid door block: " + part.blockId());
            if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                throw new IllegalArgumentException("doors must be specified by their lower half");
            Item item = part.materialItem();
            if (state.getBlock() != Blocks.WATER && (!(item instanceof net.minecraft.world.item.BlockItem blockItem) || blockItem.getBlock() != state.getBlock()))
                throw new IllegalArgumentException("block has no matching placeable item: " + part.blockId());
            result.merge(BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(result);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putString("Kind", kind); tag.putString("Title", title);
        ListTag partTags = new ListTag();
        for (Part part : parts) {
            CompoundTag entry = new CompoundTag(); entry.putLong("Pos", part.offset().asLong());
            entry.putString("Block", part.blockId()); entry.putString("Phase", part.phase());
            CompoundTag props = new CompoundTag(); part.properties().forEach(props::putString); entry.put("Properties", props);
            partTags.add(entry);
        }
        tag.put("Parts", partTags);
        ListTag noteTags = new ListTag(); for (String note : notes) noteTags.add(StringTag.valueOf(note));
        tag.put("Notes", noteTags); tag.put("Metadata", metadata.copy()); return tag;
    }

    public static RobotBuildPlan load(CompoundTag tag) {
        ListTag raw = tag.getList("Parts", 10);
        if (raw.size() > MAX_PARTS) throw new IllegalArgumentException("too many construction parts in save data");
        List<Part> parts = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            CompoundTag entry = raw.getCompound(i), properties = entry.getCompound("Properties");
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : properties.getAllKeys()) values.put(key, properties.getString(key));
            parts.add(new Part(BlockPos.of(entry.getLong("Pos")), entry.getString("Block"), values, entry.getString("Phase")));
        }
        ListTag rawNotes = tag.getList("Notes", 8);
        if (rawNotes.size() > MAX_NOTES) throw new IllegalArgumentException("too many construction notes in save data");
        List<String> notes = new ArrayList<>(rawNotes.size()); for (int i = 0; i < rawNotes.size(); i++) notes.add(rawNotes.getString(i));
        return new RobotBuildPlan(tag.getString("Kind"), tag.getString("Title"), parts, notes, tag.getCompound("Metadata"));
    }

    private static void validatePart(Part part) {
        if (part == null) throw new IllegalArgumentException("null construction part");
        BlockState state = part.state();
        if (state.getBlock() instanceof BedBlock) throw new IllegalArgumentException("beds are not supported");
        if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
            throw new IllegalArgumentException("doors must contain only their lower half");
        if (state.getBlock() != Blocks.WATER && (!(part.materialItem() instanceof net.minecraft.world.item.BlockItem item)
                || item.getBlock() != state.getBlock()))
            throw new IllegalArgumentException("block has no placeable item: " + part.blockId());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState apply(BlockState state, Property property, String value, String blockId) {
        Optional parsed = property.getValue(value);
        if (parsed.isEmpty()) throw new IllegalArgumentException("invalid value " + value + " for " + property.getName() + " on " + blockId);
        return state.setValue(property, (Comparable) parsed.get());
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid text field");
        return value;
    }
}
