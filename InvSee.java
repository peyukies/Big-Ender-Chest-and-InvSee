package com.example.invsee;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /invsee <oyuncu> - Çevrimiçi bir oyuncunun envanterini canlı olarak gösterir.
 *
 * Görünüm düzeni (54 slot):
 *   1-3. satır : oyuncunun ana envanteri
 *   4. satır   : hotbar
 *   5. satır   : kask, göğüslük, pantolon, bot, sol el
 *   kalan      : dolgu (tıklanamaz)
 */
public class InvSee extends JavaPlugin implements Listener {

    private static final int VIEW_SIZE = 54;

    /** Görünüm slotu -> hedef oyuncunun envanter slotu (-1 = dolgu). */
    private static final int[] VIEW_TO_TARGET = new int[VIEW_SIZE];

    static {
        Arrays.fill(VIEW_TO_TARGET, -1);
        for (int s = 0; s < 27; s++) VIEW_TO_TARGET[s] = 9 + s;   // ana envanter
        for (int s = 27; s < 36; s++) VIEW_TO_TARGET[s] = s - 27; // hotbar
        VIEW_TO_TARGET[36] = 39; // kask
        VIEW_TO_TARGET[37] = 38; // göğüslük
        VIEW_TO_TARGET[38] = 37; // pantolon
        VIEW_TO_TARGET[39] = 36; // bot
        VIEW_TO_TARGET[40] = 40; // sol el
    }

    private static class Session {
        final UUID target;
        Inventory view;
        final ItemStack[] snap = new ItemStack[VIEW_SIZE];

        Session(UUID target) {
            this.target = target;
        }
    }

    private static class InvHolder implements InventoryHolder {
        final Session session;

        InvHolder(Session session) {
            this.session = session;
        }

        @Override
        public Inventory getInventory() {
            return session.view;
        }
    }

    private final List<Session> sessions = new ArrayList<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        // Her 2 tickte bir görünüm <-> gerçek envanter eşitlemesi
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Iterator<Session> it = sessions.iterator();
            while (it.hasNext()) {
                Session s = it.next();
                Player target = Bukkit.getPlayer(s.target);
                if (target == null || s.view.getViewers().isEmpty()) {
                    it.remove();
                    continue;
                }
                sync(s, target);
            }
        }, 2L, 2L);
    }

    @Override
    public void onDisable() {
        for (Session s : new ArrayList<>(sessions)) {
            new ArrayList<>(s.view.getViewers()).forEach(HumanEntity::closeInventory);
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------
    // Eşitleme
    // ------------------------------------------------------------------
    private static boolean isEmpty(ItemStack it) {
        return it == null || it.getType().isAir();
    }

    private static boolean same(ItemStack a, ItemStack b) {
        boolean ea = isEmpty(a), eb = isEmpty(b);
        if (ea && eb) return true;
        if (ea || eb) return false;
        return a.equals(b);
    }

    private static ItemStack copy(ItemStack it) {
        return isEmpty(it) ? null : it.clone();
    }

    /**
     * Her slot için: görünümde değişiklik varsa (admin değiştirdi) hedefe yazılır,
     * hedefte değişiklik varsa (oyuncu değiştirdi) görünüme yazılır.
     */
    private void sync(Session s, Player target) {
        PlayerInventory pi = target.getInventory();
        for (int vs = 0; vs < VIEW_SIZE; vs++) {
            int ts = VIEW_TO_TARGET[vs];
            if (ts < 0) continue;

            ItemStack viewItem = s.view.getItem(vs);
            ItemStack targetItem = pi.getItem(ts);

            if (!same(viewItem, s.snap[vs])) {
                pi.setItem(ts, copy(viewItem));
                s.snap[vs] = copy(viewItem);
            } else if (!same(targetItem, s.snap[vs])) {
                s.view.setItem(vs, copy(targetItem));
                s.snap[vs] = copy(targetItem);
            }
        }
    }

    private void open(Player viewer, Player target) {
        Session s = new Session(target.getUniqueId());
        s.view = Bukkit.createInventory(new InvHolder(s), VIEW_SIZE, Component.text("Envanter - " + target.getName()));

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            filler.setItemMeta(meta);
        }

        PlayerInventory pi = target.getInventory();
        for (int vs = 0; vs < VIEW_SIZE; vs++) {
            int ts = VIEW_TO_TARGET[vs];
            if (ts < 0) {
                s.view.setItem(vs, filler);
            } else {
                ItemStack it = pi.getItem(ts);
                s.view.setItem(vs, copy(it));
                s.snap[vs] = copy(it);
            }
        }

        sessions.add(s);
        viewer.openInventory(s.view);
    }

    // ------------------------------------------------------------------
    // Komut: /invsee <oyuncu>
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Bu komut sadece oyun içinde kullanılabilir.");
            return true;
        }
        Player p = (Player) sender;

        if (!p.hasPermission("invsee.use")) {
            p.sendMessage("§cBu komutu kullanma yetkin yok.");
            return true;
        }
        if (args.length != 1) {
            p.sendMessage("§eKullanım: /" + label + " <oyuncu>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            p.sendMessage("§cOyuncu bulunamadı veya çevrimdışı.");
            return true;
        }
        if (target.equals(p)) {
            p.sendMessage("§cKendi envanterine bu komutla bakamazsın.");
            return true;
        }
        if (target.hasPermission("invsee.exempt")) {
            p.sendMessage("§cBu oyuncunun envanterine bakamazsın.");
            return true;
        }

        open(p, target);
        getLogger().info(p.getName() + " -> " + target.getName() + " envanterini açtı"
                + (p.hasPermission("invsee.edit") ? " (düzenleme yetkili)." : " (sadece izleme)."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("invsee.use")) {
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
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof InvHolder)) return;

        // Düzenleme yetkisi yoksa tamamen salt okunur
        if (!e.getWhoClicked().hasPermission("invsee.edit")) {
            e.setCancelled(true);
            return;
        }
        int raw = e.getRawSlot();
        if (raw >= 0 && raw < VIEW_SIZE && VIEW_TO_TARGET[raw] < 0) {
            e.setCancelled(true); // dolgu slotları
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof InvHolder)) return;

        if (!e.getWhoClicked().hasPermission("invsee.edit")) {
            e.setCancelled(true);
            return;
        }
        for (int raw : e.getRawSlots()) {
            if (raw < VIEW_SIZE && VIEW_TO_TARGET[raw] < 0) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof InvHolder)) return;
        Session s = ((InvHolder) e.getInventory().getHolder()).session;

        // Kapanmadan önce son değişiklikleri hedefe aktar
        Player target = Bukkit.getPlayer(s.target);
        if (target != null) sync(s, target);
        sessions.remove(s);
    }

    /** Hedef oyuncu çıkarsa ona bakan adminlerin ekranını kapat. */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        for (Session s : new ArrayList<>(sessions)) {
            if (s.target.equals(id)) {
                new ArrayList<>(s.view.getViewers()).forEach(HumanEntity::closeInventory);
                sessions.remove(s);
            }
        }
    }
}
