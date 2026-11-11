package com.example.bigenderchest;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class BigEnderChest extends JavaPlugin implements Listener {

    private static final int SIZE = 54; // 2 sandık = 6 satır = 54 slot
    private static final String TITLE = "Ender Sandığı";

    private final Map<UUID, Inventory> cache = new HashMap<>();
    private File dataDir;

    /** Envanterin bizim ender sandığımız olduğunu anlamak için. */
    public static class EChestHolder implements InventoryHolder {
        final UUID owner;
        Inventory inventory;

        EChestHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        dataDir = new File(getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            getLogger().warning("data klasörü oluşturulamadı!");
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        // Her 5 dakikada bir otomatik kayıt (çökme durumuna karşı)
        long period = 20L * 60 * 5;
        Bukkit.getScheduler().runTaskTimer(this, this::saveAll, period, period);
    }

    @Override
    public void onDisable() {
        for (Inventory inv : new ArrayList<>(cache.values())) {
            new ArrayList<>(inv.getViewers()).forEach(v -> v.closeInventory());
        }
        saveAll();
    }

    private File fileOf(UUID id) {
        return new File(dataDir, id + ".yml");
    }

    /**
     * Sandığı getirir. Sahibi çevrimdışıysa ve kayıt dosyası yoksa null döner.
     * onlineOwner doluysa ve ilk kullanımsa vanilla ender sandığı içeri taşınır.
     */
    private Inventory getChest(UUID id, String name, Player onlineOwner) {
        Inventory cached = cache.get(id);
        if (cached != null) return cached;

        File file = fileOf(id);
        if (!file.exists() && onlineOwner == null) return null;

        EChestHolder holder = new EChestHolder(id);
        Inventory inv = Bukkit.createInventory(holder, SIZE, Component.text(TITLE + " - " + name));
        holder.inventory = inv;

        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            List<?> list = yaml.getList("items");
            if (list != null) {
                for (int i = 0; i < list.size() && i < SIZE; i++) {
                    Object o = list.get(i);
                    if (o instanceof ItemStack) inv.setItem(i, (ItemStack) o);
                }
            }
        } else {
            // İlk kullanım: vanilla ender sandığındaki eşyaları taşı
            ItemStack[] vanilla = onlineOwner.getEnderChest().getContents();
            for (int i = 0; i < vanilla.length && i < SIZE; i++) {
                inv.setItem(i, vanilla[i]);
            }
            onlineOwner.getEnderChest().clear();
            save(id, inv);
        }

        cache.put(id, inv);
        return inv;
    }

    private void save(UUID id, Inventory inv) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : inv.getContents()) items.add(it);
        yaml.set("items", items);
        try {
            yaml.save(fileOf(id));
        } catch (IOException e) {
            getLogger().severe("Ender sandığı kaydedilemedi (" + id + "): " + e.getMessage());
        }
    }

    private void saveAll() {
        for (Map.Entry<UUID, Inventory> e : cache.entrySet()) {
            save(e.getKey(), e.getValue());
        }
    }

    private void openOwn(Player p) {
        p.openInventory(getChest(p.getUniqueId(), p.getName(), p));
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
    }

    // ------------------------------------------------------------------
    // Komut: /enderchest [oyuncu]
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Bu komut sadece oyun içinde kullanılabilir.");
            return true;
        }
        Player p = (Player) sender;

        if (args.length == 0) {
            if (!p.hasPermission("bigenderchest.use")) {
                p.sendMessage("§cBu komutu kullanma yetkin yok.");
                return true;
            }
            openOwn(p);
            return true;
        }

        if (!p.hasPermission("bigenderchest.admin")) {
            p.sendMessage("§cBaşkalarının ender sandığına bakma yetkin yok.");
            return true;
        }

        UUID id;
        String name;
        Inventory inv;
        Player online = Bukkit.getPlayerExact(args[0]);
        if (online != null) {
            id = online.getUniqueId();
            name = online.getName();
            inv = getChest(id, name, online);
        } else {
            OfflinePlayer off = Bukkit.getOfflinePlayer(args[0]);
            id = off.getUniqueId();
            name = off.getName() != null ? off.getName() : args[0];
            inv = getChest(id, name, null);
        }

        if (inv == null) {
            p.sendMessage("§cBu oyuncunun kayıtlı bir ender sandığı yok (plugin kurulduktan sonra hiç açmamış olabilir).");
            return true;
        }

        p.openInventory(inv);
        getLogger().info(p.getName() + " -> " + name + " oyuncusunun ender sandığını açtı.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("bigenderchest.admin")) {
            String prefix = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------
    // Olaylar
    // ------------------------------------------------------------------
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.ENDER_CHEST) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("bigenderchest.use")) return; // yetkisi yoksa vanilla açılır

        // Shift + elde blok varsa blok koymaya izin ver
        if (p.isSneaking() && p.getInventory().getItemInMainHand().getType() != Material.AIR) return;

        // Hem ana el hem yan el olayını iptal et (yoksa vanilla sandık da açılır)
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        openOwn(p);
    }

    /** Başkasının sandığına bakan admin, düzenleme yetkisi yoksa sadece izleyebilir. */
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof EChestHolder)) return;
        EChestHolder h = (EChestHolder) e.getInventory().getHolder();
        if (e.getWhoClicked().getUniqueId().equals(h.owner)) return;
        if (!e.getWhoClicked().hasPermission("bigenderchest.admin.edit")) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof EChestHolder)) return;
        EChestHolder h = (EChestHolder) e.getInventory().getHolder();
        if (e.getWhoClicked().getUniqueId().equals(h.owner)) return;
        if (!e.getWhoClicked().hasPermission("bigenderchest.admin.edit")) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof EChestHolder)) return;
        EChestHolder holder = (EChestHolder) e.getInventory().getHolder();
        Inventory inv = e.getInventory();
        save(holder.owner, inv);

        if (e.getPlayer() instanceof Player && e.getPlayer().getUniqueId().equals(holder.owner)) {
            Player p = (Player) e.getPlayer();
            p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 1f, 1f);
        }

        // Sahibi çevrimdışıysa ve kimse bakmıyorsa önbellekten at
        Bukkit.getScheduler().runTask(this, () -> {
            if (inv.getViewers().isEmpty() && Bukkit.getPlayer(holder.owner) == null) {
                cache.remove(holder.owner);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Inventory inv = cache.get(id);
        if (inv != null) {
            save(id, inv);
            // Bir admin hâlâ bakıyorsa önbellekte kalsın; kapanınca temizlenir
            if (inv.getViewers().isEmpty()) cache.remove(id);
        }
    }
}
