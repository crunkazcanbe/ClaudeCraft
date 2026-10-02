package com.crunkazcanbe.claudecraft;

import io.netty.buffer.Unpooled;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMerchant;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerMerchant;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.network.play.client.CPacketEntityAction;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The "do anything a player can do" verbs (goto, mine, craft, chests, eat, trade, fish, fight...).
 * CommandServer.handle() falls through to here for anything it doesn't know. Every verb acts like a
 * real player (client packets through PlayerControllerMP), so it works on servers too, not just
 * single-player. See USAGE.md at the repo root.
 */
final class Actions {
    private Actions() {}
    static final java.util.Random RNG = new java.util.Random();

    static Minecraft mc() { return Minecraft.getMinecraft(); }

    /** @return the response, or null when the verb isn't ours */
    static String handle(String cmd, String[] p, String line) throws Exception {
        switch (cmd) {
            case "chatbox": return CommandServer.onClient(() -> chatBox(rest(line, 1)));
            case "status":  return CommandServer.onClient(Actions::status);
            case "players": return CommandServer.onClient(Actions::players);
            case "goto":    return walkTo(new BlockPos(i(p[1]), i(p[2]), i(p[3])), p.length > 4 && p[4].equalsIgnoreCase("sprint"));
            case "follow":  return follow(p[1], p.length > 2 ? i(p[2]) : 30);
            case "come":    return follow(p[1], 0);
            case "mine":    return mine(new BlockPos(i(p[1]), i(p[2]), i(p[3])));
            case "minearea": return mineArea(new BlockPos(i(p[1]), i(p[2]), i(p[3])), new BlockPos(i(p[4]), i(p[5]), i(p[6])));
            case "placeat": return placeAt(new BlockPos(i(p[1]), i(p[2]), i(p[3])), p.length > 4 ? rest(line, 4) : null);
            case "open":    return CommandServer.onClient(() -> openBlock(new BlockPos(i(p[1]), i(p[2]), i(p[3]))));
            case "find":    return CommandServer.onClient(() -> find(p[1], p.length > 2 ? Math.min(48, i(p[2])) : 24));
            case "select":  return CommandServer.onClient(() -> select(rest(line, 1)));
            case "equip":   return CommandServer.onClient(Actions::equipArmor);
            case "eat":     return eat(p.length > 1 ? rest(line, 1) : null);
            case "craft":   return craft(p[1], p.length > 2 ? i(p[2]) : 1);
            case "recipes": return CommandServer.onClient(() -> recipes(p[1]));
            case "take":    return CommandServer.onClient(() -> shiftMatching(false, p.length > 1 ? rest(line, 1) : "all"));
            case "put":     return CommandServer.onClient(() -> shiftMatching(true, p.length > 1 ? rest(line, 1) : "all"));
            case "move":    return CommandServer.onClient(() -> move(i(p[1]), i(p[2]), p.length > 3 ? i(p[3]) : -1));
            case "toss":    return CommandServer.onClient(() -> toss(rest(line, 1)));
            case "trades":  return CommandServer.onClient(Actions::trades);
            case "trade":   return trade(i(p[1]), p.length > 2 ? i(p[2]) : 1);
            case "enchant": return CommandServer.onClient(() -> { Container c = mc().player.openContainer; mc().playerController.sendEnchantPacket(c.windowId, i(p[1])); return "OK enchant option " + p[1]; });
            case "sleep":   return CommandServer.onClient(() -> openBlock(new BlockPos(i(p[1]), i(p[2]), i(p[3]))));
            case "wake":    return CommandServer.onClient(() -> { mc().player.connection.sendPacket(new CPacketEntityAction(mc().player, CPacketEntityAction.Action.STOP_SLEEPING)); return "OK wake"; });
            case "respawn": return CommandServer.onClient(() -> { mc().player.respawnPlayer(); mc().displayGuiScreen(null); return "OK respawn"; });
            case "dismount": return CommandServer.onClient(() -> { mc().player.connection.sendPacket(new CPacketEntityAction(mc().player, CPacketEntityAction.Action.START_SNEAKING)); mc().player.dismountRidingEntity(); mc().player.connection.sendPacket(new CPacketEntityAction(mc().player, CPacketEntityAction.Action.STOP_SNEAKING)); return "OK dismount"; });
            case "hit":     return CommandServer.onClient(() -> hit(i(p[1])));
            case "lookent": return CommandServer.onClient(() -> { Entity e = mc().world.getEntityByID(i(p[1])); if (e == null) return "ERR no entity"; lookAt(e.posX, e.posY + e.getEyeHeight() * 0.8, e.posZ); return "OK"; });
            case "fight":   return fight(p.length > 1 ? Double.parseDouble(p[1]) : 8, p.length > 2 ? i(p[2]) : 20, p.length > 3 && p[3].equalsIgnoreCase("all"));
            case "fish":    return fish(p.length > 1 ? i(p[1]) : 60);
            case "swap":    return CommandServer.onClient(() -> { mc().player.connection.sendPacket(new net.minecraft.network.play.client.CPacketPlayerDigging(net.minecraft.network.play.client.CPacketPlayerDigging.Action.SWAP_HELD_ITEMS, BlockPos.ORIGIN, EnumFacing.DOWN)); return "OK swapped hands"; });
            case "stop":    return CommandServer.onClient(() -> { releaseAll(); return "OK stopped"; });
            default: return null;
        }
    }

    /** exactly what pressing Enter in the chat box does (GuiScreen.sendChatMessage): mod chat hooks, client commands, then send */
    static String chatBox(String msg) {
        msg = net.minecraftforge.event.ForgeEventFactory.onClientSendMessage(msg);
        if (msg.isEmpty()) return "OK chatbox (a mod took it)";
        mc().ingameGUI.getChatGUI().addToSentMessages(msg);
        if (net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(mc().player, msg) != 0) return "OK chatbox (client command)";
        mc().player.sendChatMessage(msg);
        return "OK chatbox sent: " + msg;
    }

    // ------------------------------------------------------------------ info
    static String status() {
        EntityPlayerSP pl = mc().player;
        if (pl == null) return "ERR no player";
        World w = mc().world;
        BlockPos bp = new BlockPos(pl);
        StringBuilder fx = new StringBuilder();
        for (PotionEffect e : pl.getActivePotionEffects()) fx.append(e.getEffectName().replace("effect.", "")).append(' ').append(e.getAmplifier() + 1).append(' ').append(e.getDuration() / 20).append("s,");
        return String.format(Locale.ROOT, "{\"hp\":%.1f,\"maxhp\":%.1f,\"food\":%d,\"sat\":%.1f,\"air\":%d,\"xp\":%d,\"armor\":%d,\"mode\":\"%s\",\"dim\":%d,\"biome\":\"%s\",\"time\":%d,\"rain\":%b,\"thunder\":%b,\"light\":%d,\"riding\":\"%s\",\"sleeping\":%b,\"offhand\":\"%s\",\"effects\":\"%s\",\"pos\":\"%d,%d,%d\"}",
                pl.getHealth(), pl.getMaxHealth(), pl.getFoodStats().getFoodLevel(), pl.getFoodStats().getSaturationLevel(), pl.getAir(),
                pl.experienceLevel, pl.getTotalArmorValue(), mc().playerController.getCurrentGameType(), pl.dimension,
                CommandServer.esc(w.getBiome(bp).getBiomeName()), w.getWorldTime() % 24000, w.isRaining(), w.isThundering(), w.getLight(bp),
                pl.getRidingEntity() == null ? "" : CommandServer.esc(pl.getRidingEntity().getName()), pl.isPlayerSleeping(),
                CommandServer.esc(pl.getHeldItemOffhand().isEmpty() ? "empty" : pl.getHeldItemOffhand().getDisplayName()), fx, bp.getX(), bp.getY(), bp.getZ());
    }

    static String players() {
        StringBuilder sb = new StringBuilder();
        for (NetworkPlayerInfo n : mc().getConnection().getPlayerInfoMap())
            sb.append(n.getGameProfile().getName()).append(" ping=").append(n.getResponseTime()).append("; ");
        return sb.length() == 0 ? "(nobody)" : sb.toString();
    }

    /** nearest blocks whose id contains the text, e.g. find diamond_ore 32 */
    static String find(String what, int r) {
        EntityPlayerSP pl = mc().player;
        String q = what.toLowerCase(Locale.ROOT);
        List<BlockPos> hits = new ArrayList<>();
        BlockPos c = new BlockPos(pl);
        for (BlockPos bp : BlockPos.getAllInBox(c.add(-r, -r, -r), c.add(r, r, r))) {
            IBlockState s = mc().world.getBlockState(bp);
            if (s.getBlock() != Blocks.AIR && String.valueOf(s.getBlock().getRegistryName()).contains(q)) hits.add(bp.toImmutable());
        }
        hits.sort((a, b) -> Double.compare(a.distanceSq(c), b.distanceSq(c)));
        StringBuilder sb = new StringBuilder(hits.size() + " found: ");
        for (int k = 0; k < Math.min(10, hits.size()); k++) sb.append(hits.get(k).getX()).append(',').append(hits.get(k).getY()).append(',').append(hits.get(k).getZ()).append("; ");
        return sb.toString();
    }

    // ------------------------------------------------------------------ walking
    static boolean passable(World w, BlockPos p) {
        IBlockState s = w.getBlockState(p);
        return s.getCollisionBoundingBox(w, p) == null && s.getMaterial() != net.minecraft.block.material.Material.LAVA;
    }
    static boolean standable(World w, BlockPos p) {
        return passable(w, p) && passable(w, p.up()) && !passable(w, p.down()) && w.getBlockState(p.down()).getMaterial() != net.minecraft.block.material.Material.LAVA;
    }

    /** A* over blocks: walk, step up 1, drop up to 3. ponytail: no doors/ladders/swimming/parkour; add when a route needs them. */
    static List<BlockPos> path(BlockPos from, BlockPos to, int maxNodes) {
        World w = mc().world;
        Map<BlockPos, BlockPos> came = new HashMap<>();
        Map<BlockPos, Double> g = new HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[1], (double) b[1]));
        Set<BlockPos> closed = new HashSet<>();
        g.put(from, 0.0);
        open.add(new Object[]{from, Math.sqrt(from.distanceSq(to))});
        BlockPos best = from; double bestH = Double.MAX_VALUE;
        while (!open.isEmpty() && closed.size() < maxNodes) {
            BlockPos cur = (BlockPos) open.poll()[0];
            if (!closed.add(cur)) continue;
            double h = Math.sqrt(cur.distanceSq(to));
            if (h < bestH) { bestH = h; best = cur; }
            if (cur.equals(to) || (h < 1.5 && Math.abs(cur.getY() - to.getY()) < 1)) { best = cur; break; }
            for (EnumFacing f : EnumFacing.HORIZONTALS) {
                BlockPos side = cur.offset(f);
                BlockPos next = null;
                if (standable(w, side)) next = side;
                else if (standable(w, side.up()) && passable(w, cur.up(2))) next = side.up();
                else for (int d = 1; d <= 3 && next == null; d++) { if (!passable(w, side.down(d - 1))) break; if (standable(w, side.down(d))) next = side.down(d); }
                if (next == null || closed.contains(next)) continue;
                double ng = g.get(cur) + 1 + (next.getY() != cur.getY() ? 0.5 : 0);
                if (ng < g.getOrDefault(next, Double.MAX_VALUE)) { g.put(next, ng); came.put(next, cur); open.add(new Object[]{next, ng + Math.sqrt(next.distanceSq(to))}); }
            }
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p = best; p != null && !p.equals(from); p = came.get(p)) out.add(p);
        Collections.reverse(out);
        return out;
    }

    static String walkTo(BlockPos target, boolean sprint) throws Exception {
        List<BlockPos> route = CommandServer.onClient2(() -> path(new BlockPos(mc().player), target, 30000));
        if (route.isEmpty()) return "ERR no path (or already there)";
        String r = walkRoute(route, sprint);
        BlockPos end = CommandServer.onClient2(() -> new BlockPos(mc().player));
        return r + " at " + end.getX() + "," + end.getY() + "," + end.getZ() + (end.distanceSq(target) <= 2.5 ? " (arrived)" : " (closest reachable, " + route.size() + " steps planned)");
    }

    static String walkRoute(List<BlockPos> route, boolean sprint) throws Exception {
        try {
            for (BlockPos wp : route) {
                long until = System.currentTimeMillis() + 3000;
                while (true) {
                    Boolean done = CommandServer.onClient2(() -> {
                        EntityPlayerSP pl = mc().player;
                        double dx = wp.getX() + 0.5 - pl.posX, dz = wp.getZ() + 0.5 - pl.posZ;
                        if (dx * dx + dz * dz < 0.12 && Math.abs(pl.posY - wp.getY()) < 1.2) return true;
                        CommandServer.setLook((float) Math.toDegrees(Math.atan2(-dx, dz)), pl.rotationPitch);
                        net.minecraft.client.settings.KeyBinding.setKeyBindState(mc().gameSettings.keyBindForward.getKeyCode(), true);
                        net.minecraft.client.settings.KeyBinding.setKeyBindState(mc().gameSettings.keyBindSprint.getKeyCode(), sprint);
                        boolean up = wp.getY() > Math.floor(pl.posY + 0.01) || pl.collidedHorizontally;
                        net.minecraft.client.settings.KeyBinding.setKeyBindState(mc().gameSettings.keyBindJump.getKeyCode(), up && pl.onGround);
                        return false;
                    });
                    if (done) break;
                    if (System.currentTimeMillis() > until) return "STUCK near " + wp.getX() + "," + wp.getY() + "," + wp.getZ();
                    Thread.sleep(50);
                }
            }
            return "OK walked " + route.size() + " steps";
        } finally {
            CommandServer.onClient(() -> { releaseAll(); return "OK"; });
        }
    }

    static void releaseAll() {
        net.minecraft.client.settings.GameSettings gs = mc().gameSettings;
        for (net.minecraft.client.settings.KeyBinding k : new net.minecraft.client.settings.KeyBinding[]{gs.keyBindForward, gs.keyBindBack, gs.keyBindLeft, gs.keyBindRight, gs.keyBindJump, gs.keyBindSprint, gs.keyBindSneak, gs.keyBindAttack, gs.keyBindUseItem})
            net.minecraft.client.settings.KeyBinding.setKeyBindState(k.getKeyCode(), false);
    }

    /** walk to a player; seconds=0 means just once */
    static String follow(String name, int seconds) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        String last = "ERR no player " + name;
        do {
            BlockPos t = CommandServer.onClient2(() -> { Entity e = mc().world.getPlayerEntityByName(name); return e == null ? null : new BlockPos(e); });
            if (t == null) return last;
            double d = CommandServer.onClient2(() -> Math.sqrt(new BlockPos(mc().player).distanceSq(t)));
            if (d > 2.5) last = walkTo(t, d > 8); else Thread.sleep(500);
        } while (System.currentTimeMillis() < end);
        return last;
    }

    // ------------------------------------------------------------------ blocks
    static void lookAt(double x, double y, double z) {
        EntityPlayerSP pl = mc().player;
        double dx = x - pl.posX, dy = y - (pl.posY + pl.getEyeHeight()), dz = z - pl.posZ;
        CommandServer.setLook((float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    static EnumFacing faceToward(BlockPos b) {
        EntityPlayerSP pl = mc().player;
        return EnumFacing.getFacingFromVector((float) (pl.posX - (b.getX() + 0.5)), (float) (pl.posY + pl.getEyeHeight() - (b.getY() + 0.5)), (float) (pl.posZ - (b.getZ() + 0.5)));
    }

    /** best hotbar tool for a block */
    static void bestTool(IBlockState s) {
        EntityPlayerSP pl = mc().player;
        int best = pl.inventory.currentItem; float speed = pl.inventory.getStackInSlot(best).getDestroySpeed(s);
        for (int k = 0; k < 9; k++) { float v = pl.inventory.getStackInSlot(k).getDestroySpeed(s); if (v > speed) { speed = v; best = k; } }
        pl.inventory.currentItem = best;
    }

    static String mine(BlockPos b) throws Exception {
        double dist = CommandServer.onClient2(() -> mc().player.getDistanceSq(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5));
        if (dist > 20) { String w = walkTo(b, false); if (w.startsWith("ERR")) return w; }
        String first = CommandServer.onClient(() -> {
            IBlockState s = mc().world.getBlockState(b);
            if (s.getBlock().isAir(s, mc().world, b)) return "air";
            bestTool(s);
            lookAt(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            mc().playerController.clickBlock(b, faceToward(b));
            return "OK";
        });
        if ("air".equals(first)) return "OK already air";
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            Boolean gone = CommandServer.onClient2(() -> {
                IBlockState s = mc().world.getBlockState(b);
                if (s.getBlock().isAir(s, mc().world, b)) return true;
                mc().playerController.onPlayerDamageBlock(b, faceToward(b));
                mc().player.swingArm(EnumHand.MAIN_HAND);
                return false;
            });
            if (gone) return "OK mined " + b.getX() + "," + b.getY() + "," + b.getZ();
            Thread.sleep(50);
        }
        CommandServer.onClient(() -> { mc().playerController.resetBlockRemoving(); return "OK"; });
        return "ERR couldn't break it in 30 s (wrong tool, or protected)";
    }

    /** top layer first so nothing falls on us. ponytail: one block at a time, no digging-in strategy. */
    static String mineArea(BlockPos a, BlockPos b) throws Exception {
        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos bp : BlockPos.getAllInBox(a, b)) todo.add(bp.toImmutable());
        if (todo.size() > 4096) return "ERR box too big (max 4096)";
        todo.sort((x, y) -> y.getY() - x.getY());
        int ok = 0, fail = 0;
        for (BlockPos bp : todo) { String r = mine(bp); if (r.startsWith("OK mined")) ok++; else if (!r.startsWith("OK")) fail++; }
        return "OK mined " + ok + " blocks, " + fail + " failed";
    }

    static String placeAt(BlockPos b, String item) throws Exception {
        if (item != null) { String s = CommandServer.onClient(() -> select(item)); if (s.startsWith("ERR")) return s; }
        double dist = CommandServer.onClient2(() -> mc().player.getDistanceSq(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5));
        if (dist > 20) walkTo(b, false);
        return CommandServer.onClient(() -> {
            World w = mc().world;
            for (EnumFacing f : EnumFacing.values()) {
                BlockPos n = b.offset(f);
                if (w.getBlockState(n).getMaterial().isReplaceable()) continue;
                EnumFacing face = f.getOpposite();
                Vec3d hit = new Vec3d(n.getX() + 0.5 + face.getFrontOffsetX() * 0.5, n.getY() + 0.5 + face.getFrontOffsetY() * 0.5, n.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5);
                lookAt(hit.x, hit.y, hit.z);
                net.minecraft.util.EnumActionResult r = mc().playerController.processRightClickBlock(mc().player, mc().world, n, face, hit, EnumHand.MAIN_HAND);
                mc().player.swingArm(EnumHand.MAIN_HAND);
                return "OK placed against " + n.getX() + "," + n.getY() + "," + n.getZ() + " -> " + r;
            }
            return "ERR nothing solid next to that spot to place against";
        });
    }

    static String openBlock(BlockPos b) {
        EnumFacing f = faceToward(b);
        Vec3d hit = new Vec3d(b.getX() + 0.5 + f.getFrontOffsetX() * 0.5, b.getY() + 0.5 + f.getFrontOffsetY() * 0.5, b.getZ() + 0.5 + f.getFrontOffsetZ() * 0.5);
        lookAt(hit.x, hit.y, hit.z);
        net.minecraft.util.EnumActionResult r = mc().playerController.processRightClickBlock(mc().player, mc().world, b, f, hit, EnumHand.MAIN_HAND);
        mc().player.swingArm(EnumHand.MAIN_HAND);
        return "OK used " + mc().world.getBlockState(b).getBlock().getRegistryName() + " -> " + r;
    }

    // ------------------------------------------------------------------ items
    static boolean matches(ItemStack s, String q) {
        if (s.isEmpty()) return false;
        if (q.equals("all")) return true;
        String l = q.toLowerCase(Locale.ROOT);
        return s.getDisplayName().toLowerCase(Locale.ROOT).contains(l) || String.valueOf(s.getItem().getRegistryName()).contains(l);
    }

    /** put the first matching item in hand (from anywhere in the inventory) */
    static String select(String q) { return selectWhere(s -> matches(s, q), q); }

    static String selectWhere(Predicate<ItemStack> want, String label) {
        EntityPlayerSP pl = mc().player;
        for (int k = 0; k < 9; k++) if (want.test(pl.inventory.getStackInSlot(k))) { pl.inventory.currentItem = k; return "OK holding " + pl.inventory.getStackInSlot(k).getDisplayName(); }
        for (int k = 9; k < 36; k++) if (want.test(pl.inventory.getStackInSlot(k))) {
            mc().playerController.windowClick(pl.inventoryContainer.windowId, k, pl.inventory.currentItem, ClickType.SWAP, pl);
            return "OK holding " + pl.inventory.getStackInSlot(k).getDisplayName() + " (moved from slot " + k + ")";
        }
        return "ERR no " + label + " in inventory";
    }

    static String equipArmor() {
        EntityPlayerSP pl = mc().player;
        int n = 0;
        for (int k = 0; k < 36; k++) {
            ItemStack s = pl.inventory.getStackInSlot(k);
            if (!(s.getItem() instanceof ItemArmor)) continue;
            if (!pl.getItemStackFromSlot(((ItemArmor) s.getItem()).armorType).isEmpty()) continue;
            mc().playerController.windowClick(pl.inventoryContainer.windowId, k < 9 ? k + 36 : k, 0, ClickType.QUICK_MOVE, pl);
            n++;
        }
        return "OK put on " + n + " armor pieces";
    }

    static String eat(String q) throws Exception {
        String s = CommandServer.onClient(() -> q == null ? selectWhere(st -> st.getItem() instanceof ItemFood, "food") : select(q));
        if (s.startsWith("ERR")) return s;
        int before = CommandServer.onClient2(() -> mc().player.getFoodStats().getFoodLevel());
        CommandServer.onClient(() -> {
            net.minecraft.client.settings.KeyBinding.setKeyBindState(mc().gameSettings.keyBindUseItem.getKeyCode(), true);
            mc().playerController.processRightClick(mc().player, mc().world, EnumHand.MAIN_HAND);
            return "OK";
        });
        Thread.sleep(1900);
        CommandServer.onClient(() -> { net.minecraft.client.settings.KeyBinding.setKeyBindState(mc().gameSettings.keyBindUseItem.getKeyCode(), false); return "OK"; });
        int after = CommandServer.onClient2(() -> mc().player.getFoodStats().getFoodLevel());
        return "OK ate (" + s.substring(3) + ") food " + before + " -> " + after;
    }

    static IRecipe findRecipe(String q) {
        String l = q.toLowerCase(Locale.ROOT);
        IRecipe fallback = null;
        for (IRecipe r : CraftingManager.REGISTRY) {
            ItemStack out = r.getRecipeOutput();
            if (out.isEmpty()) continue;
            String id = String.valueOf(out.getItem().getRegistryName());
            if (id.equals(l) || id.endsWith(":" + l)) return r;
            if (fallback == null && matches(out, l)) fallback = r;
        }
        return fallback;
    }

    static String recipes(String q) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (IRecipe r : CraftingManager.REGISTRY) {
            if (!matches(r.getRecipeOutput(), q) || n++ >= 15) continue;
            sb.append(r.getRegistryName()).append(" -> ").append(r.getRecipeOutput().getDisplayName()).append(" x").append(r.getRecipeOutput().getCount()).append(r.canFit(2, 2) ? " (2x2)" : " (table)").append("; ");
        }
        return sb.length() == 0 ? "(no recipes)" : sb.toString();
    }

    /** craft via the recipe book (the server pulls the ingredients), then shift-click the result */
    static String craft(String q, int times) throws Exception {
        IRecipe r = findRecipe(q);
        if (r == null) return "ERR no recipe for " + q;
        String err = CommandServer.onClient(() -> {
            Container c = mc().player.openContainer;
            if (!r.canFit(2, 2) && !(c instanceof ContainerWorkbench)) return "ERR needs a crafting table: open one first (open x y z)";
            return null;
        });
        if (err != null) return err;
        int before = CommandServer.onClient2(() -> count(r.getRecipeOutput()));
        for (int t = 0; t < times; t++) {
            CommandServer.onClient(() -> { Container c = mc().player.openContainer; mc().playerController.func_194338_a(c.windowId, r, false, mc().player); return "OK"; });
            Thread.sleep(200);
            CommandServer.onClient(() -> { Container c = mc().player.openContainer; mc().playerController.windowClick(c.windowId, 0, 0, ClickType.QUICK_MOVE, mc().player); return "OK"; });
            Thread.sleep(100);
        }
        int after = CommandServer.onClient2(() -> count(r.getRecipeOutput()));
        return (after > before ? "OK" : "ERR") + " crafted " + (after - before) + " " + r.getRecipeOutput().getDisplayName() + (after > before ? "" : " (missing ingredients?)");
    }

    static int count(ItemStack like) {
        int n = 0;
        for (ItemStack s : mc().player.inventory.mainInventory) if (s.getItem() == like.getItem()) n += s.getCount();
        return n;
    }

    /** shift-click matching stacks out of the open container (take) or into it (put) */
    static String shiftMatching(boolean fromPlayer, String q) {
        Container c = mc().player.openContainer;
        if (c == mc().player.inventoryContainer) return "ERR open a chest/furnace/machine first (open x y z)";
        int n = 0;
        for (Slot sl : c.inventorySlots) {
            boolean isPlayer = sl.inventory == mc().player.inventory;
            if (isPlayer != fromPlayer || !matches(sl.getStack(), q)) continue;
            mc().playerController.windowClick(c.windowId, sl.slotNumber, 0, ClickType.QUICK_MOVE, mc().player);
            n++;
        }
        return "OK " + (fromPlayer ? "put" : "took") + " " + n + " stacks";
    }

    /** move a stack (or count items) from one slot of the open screen to another (slot numbers from `gui`) */
    static String move(int from, int to, int count) {
        Container c = mc().player.openContainer;
        EntityPlayerSP pl = mc().player;
        mc().playerController.windowClick(c.windowId, from, 0, ClickType.PICKUP, pl);
        if (count < 0) mc().playerController.windowClick(c.windowId, to, 0, ClickType.PICKUP, pl);
        else for (int k = 0; k < count; k++) mc().playerController.windowClick(c.windowId, to, 1, ClickType.PICKUP, pl);
        if (!pl.inventory.getItemStack().isEmpty()) mc().playerController.windowClick(c.windowId, from, 0, ClickType.PICKUP, pl);
        return "OK moved " + from + " -> " + to;
    }

    static String toss(String q) {
        EntityPlayerSP pl = mc().player;
        int n = 0;
        for (int k = 0; k < 36; k++) {
            if (!matches(pl.inventory.getStackInSlot(k), q)) continue;
            mc().playerController.windowClick(pl.inventoryContainer.windowId, k < 9 ? k + 36 : k, 1, ClickType.THROW, pl);
            n++;
        }
        return "OK dropped " + n + " stacks";
    }

    // ------------------------------------------------------------------ villagers
    static String trades() {
        if (!(mc().currentScreen instanceof GuiMerchant)) return "ERR open a villager first (useent <id>)";
        MerchantRecipeList l = ((GuiMerchant) mc().currentScreen).getMerchant().getRecipes(mc().player);
        if (l == null) return "(no trades yet)";
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < l.size(); k++) {
            MerchantRecipe r = l.get(k);
            sb.append(k).append(": ").append(r.getItemToBuy().getCount()).append(' ').append(r.getItemToBuy().getDisplayName());
            if (r.hasSecondItemToBuy()) sb.append(" + ").append(r.getSecondItemToBuy().getCount()).append(' ').append(r.getSecondItemToBuy().getDisplayName());
            sb.append(" -> ").append(r.getItemToSell().getCount()).append(' ').append(r.getItemToSell().getDisplayName()).append(r.isRecipeDisabled() ? " (sold out)" : "").append("; ");
        }
        return sb.toString();
    }

    static String trade(int index, int times) throws Exception {
        String err = CommandServer.onClient(() -> {
            Container c = mc().player.openContainer;
            if (!(c instanceof ContainerMerchant)) return "ERR open a villager first";
            ((ContainerMerchant) c).setCurrentRecipeIndex(index);
            PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
            buf.writeInt(index);
            mc().player.connection.sendPacket(new CPacketCustomPayload("MC|TrSel", buf));
            return null;
        });
        if (err != null) return err;
        int got = 0;
        for (int t = 0; t < times; t++) {
            Thread.sleep(150);
            boolean ok = CommandServer.onClient2(() -> {
                Container c = mc().player.openContainer;
                if (c.getSlot(2).getStack().isEmpty()) return false;
                mc().playerController.windowClick(c.windowId, 2, 0, ClickType.QUICK_MOVE, mc().player);
                return true;
            });
            if (!ok) break;
            got++;
        }
        return "OK traded " + got + " times";
    }

    // ------------------------------------------------------------------ combat / fishing
    static String hit(int id) {
        Entity e = mc().world.getEntityByID(id);
        if (e == null) return "ERR no entity " + id;
        lookAt(e.posX, e.posY + e.height * 0.6, e.posZ);
        mc().playerController.attackEntity(mc().player, e);
        mc().player.swingArm(EnumHand.MAIN_HAND);
        return "OK hit " + e.getName();
    }

    /** fight nearby hostiles (or everything with "all"), waiting for a full swing each hit, for N seconds */
    static String fight(double radius, int seconds, boolean all) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        int hits = 0;
        try {
            while (System.currentTimeMillis() < end) {
                String r = CommandServer.onClient(() -> {
                    EntityPlayerSP pl = mc().player;
                    EntityLivingBase target = null; double bd = radius * radius;
                    for (Entity e : mc().world.getEntitiesWithinAABB(EntityLivingBase.class, new AxisAlignedBB(pl.posX - radius, pl.posY - 4, pl.posZ - radius, pl.posX + radius, pl.posY + 4, pl.posZ + radius))) {
                        if (e == pl || !e.isEntityAlive() || e instanceof net.minecraft.entity.player.EntityPlayer || (!all && !(e instanceof IMob))) continue;
                        double d = pl.getDistanceSq(e);
                        if (d < bd) { bd = d; target = (EntityLivingBase) e; }
                    }
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(mc().gameSettings.keyBindForward.getKeyCode(), target != null && bd > 9);
                    if (target == null) return "none";
                    // aim like a hand on a mouse: turn part of the way each tick, with a little wobble; swing only when on target
                    double dx = target.posX - pl.posX, dy = target.posY + target.height * 0.6 - (pl.posY + pl.getEyeHeight()), dz = target.posZ - pl.posZ;
                    float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    float ey = net.minecraft.util.math.MathHelper.wrapDegrees(wantYaw - pl.rotationYaw), ep = wantPitch - pl.rotationPitch;
                    float step = 0.35f + RNG.nextFloat() * 0.25f;
                    CommandServer.setLook(pl.rotationYaw + ey * step + (float) RNG.nextGaussian() * 0.8f, pl.rotationPitch + ep * step + (float) RNG.nextGaussian() * 0.5f);
                    boolean onTarget = Math.abs(ey) < 9 && Math.abs(ep) < 12;
                    if (bd <= 9 && onTarget && pl.getCooledAttackStrength(0) >= 0.95f && RNG.nextFloat() < 0.35f) { mc().playerController.attackEntity(pl, target); pl.swingArm(EnumHand.MAIN_HAND); return "hit"; }
                    return "wait";
                });
                if ("hit".equals(r)) hits++;
                Thread.sleep(50);
            }
        } finally { CommandServer.onClient(() -> { releaseAll(); return "OK"; }); }
        return "OK fought " + seconds + " s, " + hits + " hits";
    }

    /** cast, wait for the bobber to dip, reel in; repeat for N seconds. ponytail: dip = bobber drops >0.1 in one poll */
    static String fish(int seconds) throws Exception {
        String s = CommandServer.onClient(() -> select("fishing_rod"));
        if (s.startsWith("ERR")) return s;
        long end = System.currentTimeMillis() + seconds * 1000L;
        int caught = 0;
        while (System.currentTimeMillis() < end) {
            CommandServer.onClient(() -> { mc().playerController.processRightClick(mc().player, mc().world, EnumHand.MAIN_HAND); return "OK"; });
            Thread.sleep(2500);
            double last = Double.NaN;
            long castEnd = Math.min(end, System.currentTimeMillis() + 45000);
            boolean bite = false;
            while (System.currentTimeMillis() < castEnd) {
                Double y = CommandServer.onClient2(() -> { EntityFishHook h = mc().player.fishEntity; return h == null ? null : h.posY; });
                if (y == null) break;
                if (!Double.isNaN(last) && last - y > 0.1) { bite = true; break; }
                last = y;
                Thread.sleep(100);
            }
            CommandServer.onClient(() -> { if (mc().player.fishEntity != null) mc().playerController.processRightClick(mc().player, mc().world, EnumHand.MAIN_HAND); return "OK"; });
            if (bite) caught++;
            Thread.sleep(600);
        }
        return "OK fished " + seconds + " s, reeled in " + caught + " bites";
    }

    // ------------------------------------------------------------------ small helpers
    static int i(String s) { return Integer.parseInt(s.trim()); }

    /** the text after the first n words */
    static String rest(String line, int n) {
        String[] parts = line.trim().split("\\s+", n + 1);
        return parts.length > n ? parts[n] : "";
    }
}
