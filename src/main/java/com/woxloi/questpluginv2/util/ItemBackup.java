package com.woxloi.questpluginv2.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * インベントリのファイルバックアップ／復元／クリアを担当するユーティリティ。
 *
 * 修正点:
 *  - saveInventoryToFile() は既存バックアップが残っている場合は上書きしない
 *    （クラッシュ等で前回の復元が行われずバックアップが残ったまま
 *      新しいクエストを開始すると、古いバックアップが消えて二度と
 *      戻せなくなる問題への対応）。
 *  - clearInventory() を追加。ActiveQuestManager.startQuest() で
 *    バックアップ直後に呼び出し、V1にあった「開始時にインベントリを
 *    クリアする」動作を復元する。
 */
public class ItemBackup {

    public static class InventoryBackup {
        private final ItemStack[] contents;
        private final ItemStack[] armorContents;
        private final ItemStack offHand;

        public InventoryBackup(ItemStack[] contents, ItemStack[] armorContents, ItemStack offHand) {
            this.contents = contents;
            this.armorContents = armorContents;
            this.offHand = offHand;
        }

        public ItemStack[] getContents() { return contents; }
        public ItemStack[] getArmorContents() { return armorContents; }
        public ItemStack getOffHand() { return offHand; }
    }

    /**
     * プレイヤーごとのインベントリバックアップファイルを取得する。
     * @param dataFolder プラグインのデータフォルダ（plugin.getDataFolder()）
     * @param uuid       対象プレイヤーのUUID
     */
    public static File getBackupFile(File dataFolder, UUID uuid) {
        File dir = new File(dataFolder, "inventory_backups");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, uuid.toString() + ".yml");
    }

    /**
     * 既にバックアップファイルが存在するかを確認する。
     * true の場合、saveInventoryToFile() は上書きを拒否する。
     * 呼び出し側（ActiveQuestManager）はこれを使って
     * 「前回の復元が完了していない」状態を検出し、安全側に倒すことができる。
     */
    public static boolean hasExistingBackup(File file) {
        return file.exists();
    }

    /**
     * プレイヤーのインベントリをBase64でファイルに保存する。
     * 既にバックアップファイルが存在する場合は上書きせず false を返す
     * （前回のクエストが正常終了せずバックアップが残っている可能性があるため）。
     *
     * @return 保存に成功したか（既存ファイルがあって保存しなかった場合も false）
     */
    public static boolean saveInventoryToFile(Player player, File file) {
        if (file.exists()) {
            Logger.getLogger("QuestPluginV2").log(Level.WARNING,
                    "既存のインベントリバックアップが残っています。上書きを中止しました: " + file.getName());
            return false;
        }

        try {
            InventoryBackup backup = new InventoryBackup(
                    player.getInventory().getContents(),
                    player.getInventory().getArmorContents(),
                    player.getInventory().getItemInOffHand()
            );

            YamlConfiguration config = new YamlConfiguration();
            config.set("contents", itemStackArrayToBase64(backup.getContents()));
            config.set("armor", itemStackArrayToBase64(backup.getArmorContents()));
            config.set("offhand", itemStackToBase64(backup.getOffHand()));

            config.save(file);

            player.sendMessage(QuestMessages.PREFIX + "§eインベントリをバックアップしました");
            return true;

        } catch (Exception e) {
            player.sendMessage(QuestMessages.PREFIX + "§eインベントリの保存に失敗しました");
            Logger.getLogger("QuestPluginV2").log(Level.SEVERE, "インベントリ保存中にエラー", e);
            return false;
        }
    }

    /**
     * プレイヤーのインベントリ・防具・オフハンドを空にする。
     * クエスト開始時にバックアップした直後に呼び出すことを想定している。
     */
    public static void clearInventory(Player player) {
        player.getInventory().setContents(new ItemStack[player.getInventory().getSize()]);
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        player.updateInventory();
    }

    /**
     * ファイルからインベントリを読み込み、プレイヤーに復元する。
     */
    public static void loadInventoryFromFile(Player player, File file) {
        if (!file.exists()) {
            player.sendMessage(QuestMessages.PREFIX + "§eバックアップファイルが存在しません");
            return;
        }

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

            ItemStack[] contents = itemStackArrayFromBase64(config.getString("contents"));
            ItemStack[] armor = itemStackArrayFromBase64(config.getString("armor"));
            ItemStack offHand = itemStackFromBase64(config.getString("offhand"));

            player.getInventory().setContents(contents);
            player.getInventory().setArmorContents(armor);
            player.getInventory().setItemInOffHand(offHand == null ? new ItemStack(Material.AIR) : offHand);

            player.updateInventory();
            player.sendMessage(QuestMessages.PREFIX + "§eインベントリを復元しました");

        } catch (Exception e) {
            player.sendMessage(QuestMessages.PREFIX + "§eインベントリの復元に失敗しました");
            Logger.getLogger("QuestPluginV2").log(Level.SEVERE, "インベントリ復元中にエラー", e);
        }
    }

    // ========= Base64 シリアライズ関連 =========

    public static String itemStackArrayToBase64(ItemStack[] items) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

        dataOutput.writeInt(items.length);
        for (ItemStack item : items) {
            dataOutput.writeObject(item);
        }

        dataOutput.close();
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    public static ItemStack[] itemStackArrayFromBase64(String base64) throws IOException, ClassNotFoundException {
        if (base64 == null || base64.isEmpty()) return new ItemStack[0];

        ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
        BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

        int size = dataInput.readInt();
        ItemStack[] items = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            items[i] = (ItemStack) dataInput.readObject();
        }

        dataInput.close();
        return items;
    }

    public static String itemStackToBase64(ItemStack item) throws IOException {
        if (item == null) return "";
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

        dataOutput.writeObject(item);
        dataOutput.close();

        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    public static ItemStack itemStackFromBase64(String base64) throws IOException, ClassNotFoundException {
        if (base64 == null || base64.isEmpty()) return null;

        ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
        BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

        ItemStack item = (ItemStack) dataInput.readObject();
        dataInput.close();
        return item;
    }
}