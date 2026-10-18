package com.ic2newera.technology;

import com.ic2newera.robot.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Informational index of actual registered assembly recipes. It does not gate robot features. */
final class RobotTechnologyCatalog {
    static void append(MinecraftServer server, Map<ResourceLocation, TechnologyNode> nodes) {
        add(server, nodes, "blueprint", RobotRegistry.BLUEPRINT.get(), "通用机器人图纸", "一张图纸可重复合成八种角色娃娃。", 0);
        for (RobotCharacter character : RobotCharacter.values()) add(server, nodes, character.id,
                RobotRegistry.DOLLS.get(character).get(), character.chineseName + "机器人娃娃",
                "使用对应发色染料装配；装电池即可使用，无成就门禁。", character.ordinal() + 1);
    }
    private static void add(MinecraftServer server, Map<ResourceLocation, TechnologyNode> nodes,
                            String name, Item item, String title, String description, int row) {
        var recipeId = new ResourceLocation("ic2newera", "robots/" + name);
        var recipe = server.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) return;
        List<Component> steps = new ArrayList<>();
        steps.add(Component.literal("工作台无序合成，每次产出 1 个："));
        for (var ingredient : recipe.getIngredients()) {
            ItemStack[] choices = ingredient.getItems();
            if (choices.length > 0) steps.add(Component.literal("• ").append(choices[0].getHoverName().copy()
                    .append(choices.length > 1 ? " 或同配方允许的替代材料 × 1" : " × 1")));
        }
        steps.add(Component.literal("图纸合成后返还，不需要成就解锁。"));
        if (!name.equals("blueprint")) {
            steps.add(Component.literal("右键空位放置；右键机器人打开信息、跟随、待命、收回与聊天界面。只有放置者可操作。"));
            steps.add(Component.literal("点击发送才调用模型；单人复用助手模型设置，多人服由服主配置。其他功能不依赖模型。"));
            steps.add(Component.literal("电池/装备页面装入已充电的机器人电池、斧/镐/武器或防具。每次死亡扣 1 次寿命并销毁电池，五次耗尽；正常收回保留状态。"));
            steps.add(Component.literal("伐木/采矿在下令位置附近已加载区域执行，需要正确工具；杀戮模式仅攻击敌对生物。手机可通过同一网络派发命令，也能打开角色聊天并朗读回复。"));
            steps.add(Component.literal("角色语音使用可选独立运行时；缺少模型服务或资源不足时保留文字。"));
        }
        var itemId = ForgeRegistries.ITEMS.getKey(item);
        var id = new ResourceLocation("ic2newera", "robot_guide/" + name);
        nodes.put(id, new TechnologyNode(id, Component.literal(title), Component.literal(description), "机器人娃娃",
                itemId, List.of(), List.of(new TechnologyNode.Objective("item", itemId, 1)), steps, 0, row));
    }
}
