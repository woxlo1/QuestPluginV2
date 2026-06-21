package com.woxloi.questpluginv2.model.reward;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * クエスト報酬の1エントリを表すクラス
 */
public class QuestReward {

    private final RewardType type;

    // COMMAND用
    private String command;

    // MONEY用
    private double amount;

    // ITEM用
    private Material material;
    private int itemAmount;
    private String itemName;

    // EXP用
    private int expAmount;

    // PERMISSION用
    private String permission;

    private QuestReward(RewardType type) {
        this.type = type;
    }

    // ---- ファクトリメソッド ----

    public static QuestReward ofCommand(String command) {
        QuestReward r = new QuestReward(RewardType.COMMAND);
        r.command = command;
        return r;
    }

    public static QuestReward ofMoney(double amount) {
        QuestReward r = new QuestReward(RewardType.MONEY);
        r.amount = amount;
        return r;
    }

    public static QuestReward ofItem(Material material, int itemAmount, String itemName) {
        QuestReward r = new QuestReward(RewardType.ITEM);
        r.material = material;
        r.itemAmount = itemAmount;
        r.itemName = itemName;
        return r;
    }

    public static QuestReward ofExp(int expAmount) {
        QuestReward r = new QuestReward(RewardType.EXP);
        r.expAmount = expAmount;
        return r;
    }

    public static QuestReward ofPermission(String permission) {
        QuestReward r = new QuestReward(RewardType.PERMISSION);
        r.permission = permission;
        return r;
    }

    // ---- ゲッター ----

    public RewardType getType() { return type; }
    public String getCommand() { return command; }
    public double getAmount() { return amount; }
    public Material getMaterial() { return material; }
    public int getItemAmount() { return itemAmount; }
    public String getItemName() { return itemName; }
    public int getExpAmount() { return expAmount; }
    public String getPermission() { return permission; }

    /**
     * このrewardをアイテムとして生成する
     */
    public ItemStack toItemStack() {
        if (type != RewardType.ITEM || material == null) return null;
        ItemStack item = new ItemStack(material, itemAmount);
        if (itemName != null && !itemName.isEmpty()) {
            var meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(itemName.replace("&", "§"));
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    /**
     * MySQL保存用シリアライズ文字列
     * フォーマット: TYPE:value
     */
    public String serialize() {
        return switch (type) {
            case COMMAND    -> "COMMAND:" + command;
            case MONEY      -> "MONEY:" + amount;
            case ITEM       -> "ITEM:" + material.name() + ":" + itemAmount + ":" + (itemName != null ? itemName : "");
            case EXP        -> "EXP:" + expAmount;
            case PERMISSION -> "PERMISSION:" + permission;
        };
    }

    /**
     * シリアライズ文字列からデシリアライズ
     */
    public static QuestReward deserialize(String data) {
        if (data == null || data.isEmpty()) return null;
        String[] parts = data.split(":", 2);
        if (parts.length < 2) return null;
        RewardType type = RewardType.fromString(parts[0]);
        if (type == null) return null;
        String value = parts[1];
        return switch (type) {
            case COMMAND    -> ofCommand(value);
            case MONEY      -> { try { yield ofMoney(Double.parseDouble(value)); } catch (NumberFormatException e) { yield null; } }
            case ITEM       -> {
                String[] ip = value.split(":", 3);
                if (ip.length < 2) yield null;
                Material mat = Material.matchMaterial(ip[0]);
                if (mat == null) yield null;
                int amt = 1;
                try { amt = Integer.parseInt(ip[1]); } catch (NumberFormatException ignored) {}
                String name = ip.length >= 3 ? ip[2] : "";
                yield ofItem(mat, amt, name);
            }
            case EXP        -> { try { yield ofExp(Integer.parseInt(value)); } catch (NumberFormatException e) { yield null; } }
            case PERMISSION -> ofPermission(value);
        };
    }
}
