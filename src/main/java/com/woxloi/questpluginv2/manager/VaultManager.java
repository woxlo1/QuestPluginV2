package com.woxloi.questpluginv2.manager;

import com.woxloi.questpluginv2.QuestPluginV2;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.plugin.RegisteredServiceProvider;

public class VaultManager {

    private final QuestPluginV2 plugin;
    private Economy economy;
    private Permission permission;
    private boolean enabled = false;

    public VaultManager(QuestPluginV2 plugin) {
        this.plugin = plugin;
        setupPermission();
    }

    private void setupPermission() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Permission> rsp =
                plugin.getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp != null) {
            permission = rsp.getProvider();
        }
    }

    public void setEconomy(Economy economy) {
        this.economy = economy;
        this.enabled = true;
    }

    public Economy getEconomy() { return economy; }
    public Permission getPermission() { return permission; }
    public boolean isEnabled() { return enabled && economy != null; }
}
