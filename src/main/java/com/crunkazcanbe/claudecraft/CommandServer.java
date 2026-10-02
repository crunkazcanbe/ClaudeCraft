package com.crunkazcanbe.claudecraft;

import com.google.common.util.concurrent.ListenableFuture;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import org.lwjgl.input.Keyboard;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * Line-based command server. Connect to 127.0.0.1:25599, send one command per
 * line, get one response line back. All game mutation runs on the client thread
 * via Minecraft.addScheduledTask so it is thread-safe.
 */
public final class CommandServer {

    public static final int PORT = 25599;
    private static volatile boolean started = false;

    private CommandServer() {}

    public static void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(CommandServer::run, "claudecraft-server");
        t.setDaemon(true);
        t.start();
        if (ClaudeCraft.LOG != null)
            ClaudeCraft.LOG.info("[ClaudeCraft] command server listening on 127.0.0.1:" + PORT);
    }

    private static void run() {
        try (ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                Socket sock = server.accept();
                handleClient(sock);
            }
        } catch (IOException e) {
            if (ClaudeCraft.LOG != null) ClaudeCraft.LOG.error("[ClaudeCraft] server failed", e);
        }
    }

    private static void handleClient(Socket sock) {
        try (Socket s = sock;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String resp;
                try {
                    resp = handle(line.trim());
                } catch (Throwable e) {
                    resp = "ERR " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                out.write(resp == null ? "OK" : resp);
                out.write("\n");
                out.flush();
            }
        } catch (IOException ignored) {
            // client disconnected
        }
    }

    /** Schedule a callable on the client thread and block for its result. */
    static String onClient(Callable<String> c) throws Exception {
        ListenableFuture<String> f = Minecraft.getMinecraft().addScheduledTask(c);
        return f.get();
    }

    /** same as onClient, any result type */
    static <T> T onClient2(Callable<T> c) throws Exception {
        return Minecraft.getMinecraft().addScheduledTask(c).get();
    }

    private static String handle(String line) throws Exception {
        if (line.isEmpty()) return "OK";
        // A remotely driven game is almost never the focused window: without this, every command
        // lands on the pause menu (and the integrated server stops ticking) the moment the Mac
        // window loses focus.
        Minecraft.getMinecraft().gameSettings.pauseOnLostFocus = false;
        String[] p = line.split("\\s+");
        String cmd = p[0].toLowerCase();

        switch (cmd) {
            case "ping":
                return "pong";

            case "look": { // look <yaw> <pitch>  (absolute)
                final float yaw = Float.parseFloat(p[1]);
                final float pitch = Float.parseFloat(p[2]);
                return onClient(() -> { setLook(yaw, pitch); return "OK look " + yaw + " " + pitch; });
            }

            case "turn": { // turn <dyaw> <dpitch>  (relative)
                final float dyaw = Float.parseFloat(p[1]);
                final float dpitch = Float.parseFloat(p[2]);
                return onClient(() -> {
                    EntityPlayerSP pl = Minecraft.getMinecraft().player;
                    if (pl == null) return "ERR no player";
                    setLook(pl.rotationYaw + dyaw, pl.rotationPitch + dpitch);
                    return "OK yaw=" + pl.rotationYaw + " pitch=" + pl.rotationPitch;
                });
            }

            case "key": { // key <name> <down|up>
                final String name = p[1];
                final boolean down = p[2].equalsIgnoreCase("down") || p[2].equalsIgnoreCase("on") || p[2].equals("1");
                return onClient(() -> setKey(name, down));
            }

            case "tap": { // tap <name>   (press for ~3 ticks then release)
                final String name = p[1];
                String r1 = onClient(() -> { String r = setKey(name, true); pressTick(name); return r; });
                Thread.sleep(120);
                onClient(() -> setKey(name, false));
                return r1;
            }

            case "slot":
            case "hotbar": { // slot <0-8>
                final int n = Integer.parseInt(p[1]);
                return onClient(() -> {
                    EntityPlayerSP pl = Minecraft.getMinecraft().player;
                    if (pl == null) return "ERR no player";
                    pl.inventory.currentItem = Math.max(0, Math.min(8, n));
                    return "OK slot " + pl.inventory.currentItem;
                });
            }

            case "place":
            case "use":
            case "rclick":
                return onClient(CommandServer::rightClick);

            case "break":
            case "attack":
            case "lclick":
                return onClient(CommandServer::leftClick);

            case "jump":
                onClient(() -> { setKey("jump", true); return "OK"; });
                Thread.sleep(150);
                onClient(() -> { setKey("jump", false); return "OK"; });
                return "OK jump";

            case "chat":
            case "cmd": { // chat <message...>   (cmd is identical; prefix with / for a command)
                int sp = line.indexOf(' ');
                final String msg = sp < 0 ? "" : line.substring(sp + 1);
                return onClient(() -> {
                    EntityPlayerSP pl = Minecraft.getMinecraft().player;
                    if (pl == null) return "ERR no player";
                    pl.sendChatMessage(msg);
                    return "OK sent: " + msg;
                });
            }

            case "state":
                return onClient(CommandServer::buildState);

            case "hold": { // hold <key> <ms>   (press, wait, release; key = vanilla name or any bind name)
                final String name = p[1];
                long ms = Long.parseLong(p[2]);
                String r1 = onClient(() -> setKey(name, true));
                Thread.sleep(Math.min(ms, 30000));
                onClient(() -> setKey(name, false));
                return r1 + " held " + ms + "ms";
            }

            case "combo": { // combo <key1> <key2> ... [ms]   press several keys at once (a chord)
                // Trailing number = hold time in ms; otherwise ~120ms. Presses every named key
                // down together, ticks them, releases them. Works for keybind chords (movement,
                // action binds) and raw physical keys. NOTE: hardware modifier combos (SHIFT+T)
                // and F3-debug combos read the real keyboard, so use the dedicated commands
                // (`reload`, `debug`) for those instead.
                int last = p.length - 1;
                long ms = 120;
                try { ms = Long.parseLong(p[last]); last--; } catch (NumberFormatException ignore) {}
                if (last < 1) return "ERR usage: combo <key1> <key2> ... [ms]";
                final String[] keys = java.util.Arrays.copyOfRange(p, 1, last + 1);
                String r1 = onClient(() -> {
                    StringBuilder sb = new StringBuilder("OK combo");
                    for (String k : keys) {
                        int code = keyCodeFor(k);
                        if (code == Keyboard.KEY_NONE) { sb.append(" [?").append(k).append("]"); continue; }
                        KeyBinding.setKeyBindState(code, true);
                        KeyBinding.onTick(code);
                        sb.append(' ').append(k);
                    }
                    return sb.toString();
                });
                Thread.sleep(Math.min(ms, 30000));
                onClient(() -> { for (String k : keys) { int c = keyCodeFor(k); if (c != Keyboard.KEY_NONE) KeyBinding.setKeyBindState(c, false); } return "OK"; });
                return r1 + " (" + ms + "ms)";
            }

            case "reload": // reload   rebuild all resources & re-bake models (exactly what F3+T does)
                return onClient(() -> { Minecraft.getMinecraft().refreshResources(); return "OK resources reloaded"; });

            case "sweep": // sweep   go through EVERY creative tab, scroll every page, check every item (report: logs/claudecraft-sweep.txt)
                return onClient(() -> CreativeSweep.INSTANCE.start());
            case "sweepstatus": // sweepstatus   progress of the creative sweep
                return onClient(() -> CreativeSweep.INSTANCE.status());
            case "perf": // perf   FPS, integrated-server tick time/TPS, chunks, entities, memory (one line)
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    Runtime rt = Runtime.getRuntime();
                    String mem = String.format("mem=%dMB/%dMB", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
                    String tick = "tick=n/a";
                    net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
                    if (srv != null) {
                        long[] t = srv.tickTimeArray;
                        double ms = 0; for (long v : t) ms += v; ms = ms / t.length / 1.0e6;
                        tick = String.format("tick=%.1fms tps=%.1f", ms, Math.min(20.0, 1000.0 / Math.max(ms, 1e-3)));
                    }
                    String world = mc.world == null ? "noworld"
                            : "chunks=" + mc.world.getChunkProvider().makeString() + " entities=" + mc.world.loadedEntityList.size();
                    return "OK fps=" + Minecraft.getDebugFPS() + " " + tick + " " + world + " " + mem;
                });

            case "debug": { // debug <t|a|b|d>   the useful F3+ debug actions (LWJGL2 can't fake the real combo)
                if (p.length < 2) return "ERR usage: debug <reload|chunks|hitboxes|clearchat>";
                final String what = p[1].toLowerCase();
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    switch (what) {
                        case "t": case "reload":                 mc.refreshResources(); return "OK F3+T resources reloaded";
                        case "a": case "chunks":                 if (mc.renderGlobal != null) mc.renderGlobal.loadRenderers(); return "OK F3+A chunks reloaded";
                        case "b": case "hitboxes":               mc.getRenderManager().setDebugBoundingBox(!mc.getRenderManager().isDebugBoundingBox()); return "OK F3+B hitboxes toggled";
                        case "d": case "clearchat":              mc.ingameGUI.getChatGUI().clearChatMessages(true); return "OK F3+D chat cleared";
                        default:                                 return "ERR unknown debug action: " + what;
                    }
                });
            }

            case "lookat": { // lookat <x> <y> <z>   (aim the crosshair at a world point, e.g. a block centre)
                final double tx = Double.parseDouble(p[1]), ty = Double.parseDouble(p[2]), tz = Double.parseDouble(p[3]);
                return onClient(() -> {
                    EntityPlayerSP pl = Minecraft.getMinecraft().player;
                    if (pl == null) return "ERR no player";
                    double dx = tx - pl.posX, dy = ty - (pl.posY + pl.getEyeHeight()), dz = tz - pl.posZ;
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    setLook(yaw, pitch);
                    return "OK lookat yaw=" + yaw + " pitch=" + pitch;
                });
            }

            case "rebind": { // rebind <bind name> <KEY_NAME>   e.g. rebind ir_keys.increase_throttle UP
                final String name = p[1];
                final int code = org.lwjgl.input.Keyboard.getKeyIndex(p[2].toUpperCase());
                return onClient(() -> {
                    KeyBinding kb = bindFor(name);
                    if (kb == null) return "ERR unknown bind: " + name;
                    if (code == 0) return "ERR unknown key name (use LWJGL names: UP, B, N, NUMPAD8...)";
                    Minecraft mc = Minecraft.getMinecraft();
                    mc.gameSettings.setOptionKeyBinding(kb, code);
                    KeyBinding.resetKeyBindingArrayAndHash();
                    mc.gameSettings.saveOptions();
                    return "OK " + kb.getKeyDescription() + " -> " + org.lwjgl.input.Keyboard.getKeyName(code);
                });
            }

            case "binds": { // binds [filter]   list key bindings (name=key)
                final String filter = p.length > 1 ? p[1].toLowerCase() : "";
                return onClient(() -> {
                    StringBuilder sb = new StringBuilder();
                    for (KeyBinding kb : Minecraft.getMinecraft().gameSettings.keyBindings) {
                        if (!kb.getKeyDescription().toLowerCase().contains(filter)) continue;
                        sb.append(kb.getKeyDescription()).append('=').append(kb.getDisplayName()).append("; ");
                    }
                    return sb.toString();
                });
            }

            case "gui": // gui   describe the open screen: class, slots with items, buttons
                return onClient(CommandServer::describeGui);

            case "click": { // click <slot#> [left|right|shift]   click a slot in the open container screen
                final int slot = Integer.parseInt(p[1]);
                final String how = p.length > 2 ? p[2].toLowerCase() : "left";
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (!(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer)) return "ERR no container screen open";
                    net.minecraft.inventory.Container c = ((net.minecraft.client.gui.inventory.GuiContainer) mc.currentScreen).inventorySlots;
                    if (slot < 0 || slot >= c.inventorySlots.size()) return "ERR slot out of range 0-" + (c.inventorySlots.size() - 1);
                    net.minecraft.inventory.ClickType type = how.equals("shift") ? net.minecraft.inventory.ClickType.QUICK_MOVE : net.minecraft.inventory.ClickType.PICKUP;
                    mc.playerController.windowClick(c.windowId, slot, how.equals("right") ? 1 : 0, type, mc.player);
                    return "OK click " + slot + " " + how;
                });
            }

            case "tip": { // tip [slot#|hand]   read an item's FULL tooltip text (exact numbers, no screenshot needed)
                final String which = p.length > 1 ? p[1].toLowerCase() : "hand";
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    net.minecraft.item.ItemStack st;
                    if (which.equals("hand")) {
                        st = mc.player.inventory.getCurrentItem();
                    } else {
                        int slot = Integer.parseInt(which);
                        if (mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer) {
                            net.minecraft.inventory.Container c = ((net.minecraft.client.gui.inventory.GuiContainer) mc.currentScreen).inventorySlots;
                            if (slot < 0 || slot >= c.inventorySlots.size()) return "ERR slot out of range 0-" + (c.inventorySlots.size() - 1);
                            st = c.inventorySlots.get(slot).getStack();
                        } else {
                            if (slot < 0 || slot >= mc.player.inventory.getSizeInventory()) return "ERR slot out of range";
                            st = mc.player.inventory.getStackInSlot(slot);
                        }
                    }
                    if (st.isEmpty()) return "ERR empty";
                    net.minecraft.client.util.ITooltipFlag.TooltipFlags flag = mc.gameSettings.advancedItemTooltips
                            ? net.minecraft.client.util.ITooltipFlag.TooltipFlags.ADVANCED
                            : net.minecraft.client.util.ITooltipFlag.TooltipFlags.NORMAL;
                    StringBuilder sb = new StringBuilder();
                    for (String tipLine : st.getTooltip(mc.player, flag)) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(tipLine));
                    }
                    return sb.toString();
                });
            }

            case "mhover": { // mhover <x> <y>   move the pointer over a spot without clicking (so tooltips render)
                final int hx = Integer.parseInt(p[1]), hy = Integer.parseInt(p[2]);
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    net.minecraft.client.gui.GuiScreen s = mc.currentScreen;
                    if (s == null) return "ERR no screen open";
                    int dx = hx * mc.displayWidth / s.width;
                    int dy = mc.displayHeight - hy * mc.displayHeight / s.height - 1;
                    org.lwjgl.input.Mouse.setCursorPosition(dx, dy);
                    return "OK mhover " + hx + "," + hy;
                });
            }

            case "button": { // button <id>   press a button on the open screen (ids from `gui`)
                final int id = Integer.parseInt(p[1]);
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    net.minecraft.client.gui.GuiScreen s = mc.currentScreen;
                    if (s == null) return "ERR no screen open";
                    for (net.minecraft.client.gui.GuiButton b : buttons(s)) {
                        if (b.id != id) continue;
                        java.lang.reflect.Method m = net.minecraftforge.fml.relauncher.ReflectionHelper.findMethod(
                                net.minecraft.client.gui.GuiScreen.class, "actionPerformed", "func_146284_a", net.minecraft.client.gui.GuiButton.class);
                        b.playPressSound(mc.getSoundHandler());
                        m.invoke(s, b);
                        return "OK button " + id + " (" + b.displayString + ")";
                    }
                    return "ERR no button " + id;
                });
            }

            case "type": { // type <text...>   type into the open screen (text fields, chat)
                int sp = line.indexOf(' ');
                final String text = sp < 0 ? "" : line.substring(sp + 1);
                return onClient(() -> {
                    net.minecraft.client.gui.GuiScreen s = Minecraft.getMinecraft().currentScreen;
                    if (s == null) return "ERR no screen open";
                    java.lang.reflect.Method m = net.minecraftforge.fml.relauncher.ReflectionHelper.findMethod(
                            net.minecraft.client.gui.GuiScreen.class, "keyTyped", "func_73869_a", char.class, int.class);
                    for (char ch : text.toCharArray()) m.invoke(s, ch, 0);
                    return "OK typed " + text.length() + " chars";
                });
            }

            case "enter": { // enter   press Enter in the open screen (send the chat line typed with `type`)
                return onClient(() -> {
                    net.minecraft.client.gui.GuiScreen s = Minecraft.getMinecraft().currentScreen;
                    if (s == null) return "ERR no screen open";
                    net.minecraftforge.fml.relauncher.ReflectionHelper.findMethod(net.minecraft.client.gui.GuiScreen.class, "keyTyped", "func_73869_a", char.class, int.class)
                            .invoke(s, '\r', Keyboard.KEY_RETURN);
                    return "OK enter";
                });
            }

            case "mclick": { // mclick <x> <y> [left|right]   click the open screen at GUI coordinates (see `gui` size)
                final int mx = Integer.parseInt(p[1]), my = Integer.parseInt(p[2]);
                final int btn = p.length > 3 && p[3].equalsIgnoreCase("right") ? 1 : 0;
                // Screens that pick what's under the cursor while drawing (maps) read the REAL pointer,
                // so move it there first and let a few frames render before clicking.
                String moved = onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    net.minecraft.client.gui.GuiScreen s = mc.currentScreen;
                    if (s == null) return "ERR no screen open";
                    int dx = mx * mc.displayWidth / s.width;
                    int dy = mc.displayHeight - my * mc.displayHeight / s.height - 1;
                    org.lwjgl.input.Mouse.setCursorPosition(dx, dy);
                    return "OK";
                });
                if (!moved.startsWith("OK")) return moved;
                Thread.sleep(200);
                return onClient(() -> {
                    net.minecraft.client.gui.GuiScreen s = Minecraft.getMinecraft().currentScreen;
                    if (s == null) return "ERR no screen open";
                    // Hover pass at the click point: screens that hit-test while drawing see the cursor
                    // here even when the OS pointer can't be moved (Cleanroom's LWJGL on macOS).
                    s.drawScreen(mx, my, 0f);
                    java.lang.reflect.Method down = net.minecraftforge.fml.relauncher.ReflectionHelper.findMethod(
                            net.minecraft.client.gui.GuiScreen.class, "mouseClicked", "func_73864_a", int.class, int.class, int.class);
                    java.lang.reflect.Method up = net.minecraftforge.fml.relauncher.ReflectionHelper.findMethod(
                            net.minecraft.client.gui.GuiScreen.class, "mouseReleased", "func_146286_b", int.class, int.class, int.class);
                    down.invoke(s, mx, my, btn);
                    up.invoke(s, mx, my, btn);
                    return "OK mclick " + mx + "," + my;
                });
            }

            case "pause": // pause   open the Esc (pause) menu exactly like pressing Esc in a world
                return onClient(() -> { Minecraft.getMinecraft().displayInGameMenu(); return "OK pause"; });

            case "esc": // esc   send a real Esc key press to the open screen (its own keyTyped, so menus react like to the keyboard)
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.currentScreen == null) return "ERR no screen open";
                    java.lang.reflect.Method m = null;
                    for (Class<?> c = mc.currentScreen.getClass(); c != null && m == null; c = c.getSuperclass())
                        for (java.lang.reflect.Method x : c.getDeclaredMethods())
                            if (x.getParameterCount() == 2 && x.getParameterTypes()[0] == char.class && x.getParameterTypes()[1] == int.class
                                    && (x.getName().equals("keyTyped") || x.getName().equals("func_73869_a"))) { m = x; break; }
                    if (m == null) return "ERR no keyTyped";
                    m.setAccessible(true);
                    m.invoke(mc.currentScreen, (char) 0, org.lwjgl.input.Keyboard.KEY_ESCAPE);
                    return "OK esc -> " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName());
                });

            case "close": // close   close the open screen (like Esc)
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.player != null && mc.currentScreen != null) mc.player.closeScreen();
                    else mc.displayGuiScreen(null);
                    return "OK closed";
                });

            case "shot": // shot   save an in-game screenshot to <game>/screenshots/claudecraft.png
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    net.minecraft.util.text.ITextComponent msg = net.minecraft.util.ScreenShotHelper.saveScreenshot(
                            mc.mcDataDir, "claudecraft.png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                    return "OK " + msg.getUnformattedText();
                });

            case "worlds": // worlds   list saved singleplayer worlds: folder = display name
                return onClient(() -> {
                    StringBuilder sb = new StringBuilder();
                    for (net.minecraft.world.storage.WorldSummary w : Minecraft.getMinecraft().getSaveLoader().getSaveList()) {
                        sb.append(w.getFileName()).append(" = ").append(esc(w.getDisplayName())).append("; ");
                    }
                    return sb.length() == 0 ? "(no worlds)" : sb.toString();
                });

            case "world": { // world <folder>   open a singleplayer world from the title screen (folder from `worlds`)
                final String folder = line.substring(line.indexOf(' ') + 1).trim();   // folders have spaces
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.world != null) return "ERR already in a world";
                    for (net.minecraft.world.storage.WorldSummary w : mc.getSaveLoader().getSaveList()) {
                        if (!w.getFileName().equals(folder)) continue;
                        // The summary has to be the real one from the save list: FML reads its WorldInfo.
                        net.minecraftforge.fml.client.FMLClientHandler.instance().tryLoadExistingWorld(
                                new net.minecraft.client.gui.GuiWorldSelection(new net.minecraft.client.gui.GuiMainMenu()), w);
                        return "OK loading " + folder;
                    }
                    return "ERR no world folder " + folder;
                });
            }

            case "newworld": { // newworld <folder> [flat|default] [creative|survival]   create + enter a new singleplayer world (cheats on)
                final String[] a = line.trim().split("\\s+");
                if (a.length < 2) return "ERR usage: newworld <folder> [flat|default] [creative|survival]";
                final String folder = a[1];
                final boolean flat = a.length < 3 || !a[2].equalsIgnoreCase("default");
                final boolean survival = a.length > 3 && a[3].equalsIgnoreCase("survival");
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.world != null) return "ERR already in a world";
                    if (mc.getSaveLoader().getWorldInfo(folder) != null) return "ERR world folder exists: " + folder;
                    net.minecraft.world.WorldSettings ws = new net.minecraft.world.WorldSettings(new java.util.Random().nextLong(),
                            survival ? net.minecraft.world.GameType.SURVIVAL : net.minecraft.world.GameType.CREATIVE,
                            true, false, flat ? net.minecraft.world.WorldType.FLAT : net.minecraft.world.WorldType.DEFAULT);
                    ws.enableCommands();
                    mc.launchIntegratedServer(folder, folder, ws);
                    return "OK creating " + folder + (flat ? " (flat)" : "") + (survival ? " survival" : " creative");
                });
            }

            case "quit": // quit   save and leave the world to the title screen (like Save and Quit)
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.world == null) return "ERR not in a world";
                    mc.world.sendQuittingDisconnectingPacket();
                    mc.loadWorld(null);
                    mc.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());
                    return "OK quit to title";
                });

            case "log": { // log [n]   last n chat / action-bar lines (command results, mod messages)
                int n = p.length > 1 ? Integer.parseInt(p[1]) : 10;
                return ChatLog.last(n);
            }

            case "entities": { // entities [radius]   nearby non-player entities: id name type x,y,z dist
                final double radius = p.length > 1 ? Double.parseDouble(p[1]) : 32;
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.world == null) return "ERR no world";
                    StringBuilder sb = new StringBuilder();
                    for (net.minecraft.entity.Entity e : mc.world.loadedEntityList) {
                        if (e == mc.player) continue;
                        double d = e.getDistance(mc.player);
                        if (d > radius) continue;
                        net.minecraft.util.ResourceLocation key = net.minecraft.entity.EntityList.getKey(e);
                        sb.append(e.getEntityId()).append(' ').append(esc(e.getName())).append(' ')
                                .append(key == null ? e.getClass().getSimpleName() : key.toString()).append(' ')
                                .append(fmt(e.posX)).append(',').append(fmt(e.posY)).append(',').append(fmt(e.posZ))
                                .append(" d=").append(fmt(d)).append(e == mc.player.getRidingEntity() ? " RIDING" : "").append("; ");
                    }
                    return sb.length() == 0 ? "(none)" : sb.toString();
                });
            }

            case "useent": { // useent <id>   right-click an entity by id (board a train, open a cart) without aiming
                final int id = Integer.parseInt(p[1]);
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    net.minecraft.entity.Entity e = mc.world == null ? null : mc.world.getEntityByID(id);
                    if (e == null) return "ERR no entity " + id;
                    RayTraceResult hit = new RayTraceResult(e, new net.minecraft.util.math.Vec3d(e.posX, e.posY + e.height / 2, e.posZ));
                    EnumActionResult at = mc.playerController.interactWithEntity(mc.player, e, hit, EnumHand.MAIN_HAND);
                    EnumActionResult plain = at == EnumActionResult.SUCCESS ? at
                            : mc.playerController.interactWithEntity(mc.player, e, EnumHand.MAIN_HAND);
                    mc.player.swingArm(EnumHand.MAIN_HAND);
                    return "OK useent " + id + " at=" + at + " plain=" + plain;
                });
            }

            case "inv": // inv   the player's inventory: slot:item xN
                return onClient(() -> {
                    EntityPlayerSP pl = Minecraft.getMinecraft().player;
                    if (pl == null) return "ERR no player";
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < pl.inventory.getSizeInventory(); i++) {
                        ItemStack st = pl.inventory.getStackInSlot(i);
                        if (!st.isEmpty()) sb.append(i).append(':').append(esc(st.getDisplayName())).append(" x").append(st.getCount()).append("; ");
                    }
                    return sb.length() == 0 ? "(empty)" : sb.toString();
                });

            case "blocks": { // blocks <x1> <y1> <z1> <x2> <y2> <z2>   non-air blocks in a box (max 4096) to check a build
                final int[] c = new int[6];
                for (int i = 0; i < 6; i++) c[i] = Integer.parseInt(p[i + 1]);
                return onClient(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.world == null) return "ERR no world";
                    StringBuilder sb = new StringBuilder();
                    int count = 0;
                    for (BlockPos bp : BlockPos.getAllInBox(new BlockPos(c[0], c[1], c[2]), new BlockPos(c[3], c[4], c[5]))) {
                        if (++count > 4096) return "ERR box too big (max 4096 blocks)";
                        IBlockState bs = mc.world.getBlockState(bp);
                        if (bs.getBlock().isAir(bs, mc.world, bp)) continue;
                        sb.append(bp.getX()).append(',').append(bp.getY()).append(',').append(bp.getZ()).append('=')
                                .append(bs.getBlock().getRegistryName()).append("; ");
                    }
                    return sb.length() == 0 ? "(all air)" : sb.toString();
                });
            }

            case "help":
                return "cmds: ping state look lookat turn key hold tap combo reload debug slot place break jump chat cmd log "
                        + "entities useent inv blocks binds rebind gui click button type close shot tip mhover "
                        + "worlds world quit help enter | ACTIONS: status players goto follow come mine minearea placeat open find select equip eat "
                        + "chatbox craft recipes take put move toss trades trade enchant sleep wake respawn dismount hit lookent fight fish swap stop  (see USAGE.md)";

            default: {
                String r = Actions.handle(cmd, p, line);
                return r != null ? r : "ERR unknown command: " + cmd + " (try: help)";
            }
        }
    }

    // ---- client-thread helpers (only called inside onClient/addScheduledTask) ----

    static void setLook(float yaw, float pitch) {
        EntityPlayerSP pl = Minecraft.getMinecraft().player;
        if (pl == null) return;
        pitch = Math.max(-90f, Math.min(90f, pitch));
        pl.rotationYaw = yaw;
        pl.prevRotationYaw = yaw;
        pl.rotationPitch = pitch;
        pl.prevRotationPitch = pitch;
        pl.rotationYawHead = yaw;
        pl.prevRotationYawHead = yaw;
        pl.setRotationYawHead(yaw);
    }

    private static KeyBinding bindFor(String name) {
        GameSettings gs = Minecraft.getMinecraft().gameSettings;
        switch (name.toLowerCase()) {
            case "forward": case "w":          return gs.keyBindForward;
            case "back":    case "s":          return gs.keyBindBack;
            case "left":    case "a":          return gs.keyBindLeft;
            case "right":   case "d":          return gs.keyBindRight;
            case "jump":    case "space":      return gs.keyBindJump;
            case "sneak":   case "shift":      return gs.keyBindSneak;
            case "sprint":                     return gs.keyBindSprint;
            case "attack":                     return gs.keyBindAttack;
            case "use":                        return gs.keyBindUseItem;
            case "inventory": case "e":        return gs.keyBindInventory;
            case "drop":    case "q":          return gs.keyBindDrop;
            default:
                // Any other mod's key by its binding name, e.g. "ir_keys.increase_throttle" --
                // Immersive Railroading drives trains from numpad binds a laptop doesn't have.
                for (KeyBinding kb : gs.keyBindings) {
                    if (kb.getKeyDescription().equalsIgnoreCase(name)) return kb;
                }
                return null;
        }
    }

    private static String setKey(String name, boolean down) {
        int code = keyCodeFor(name);
        if (code == Keyboard.KEY_NONE) return "ERR unknown key: " + name;
        KeyBinding.setKeyBindState(code, down);
        return "OK key " + name + " " + (down ? "down" : "up");
    }

    /**
     * Resolve a key name to an LWJGL keycode. First a ClaudeCraft/mod key-binding name (so
     * "forward", "ir_keys.horn" still work), then a raw physical key ("F3", "T", "LCONTROL",
     * "A", "UP"...). Raw keys let `key`/`tap`/`hold`/`combo` press anything, not just the
     * hard-coded movement binds.
     */
    private static int keyCodeFor(String name) {
        KeyBinding kb = bindFor(name);
        if (kb != null) return kb.getKeyCode();
        int raw = Keyboard.getKeyIndex(name.toUpperCase());
        return raw; // KEY_NONE (0) when unknown
    }

    /**
     * Register a press event (needed for tap-style binds like inventory/drop), then fire the
     * Forge input event so MOD keybinds actually run.
     *
     * Two things were missing and between them made every mod keybind silently do nothing:
     *   1. it resolved the name through bindFor() only, so a RAW key ("R", "Y") found no
     *      KeyBinding and never got an onTick at all -- isPressed() stayed false forever;
     *   2. setKeyBindState/onTick only change binding state. Handlers that subscribe to
     *      InputEvent.KeyInputEvent (Traincraft's TCKeyHandler, and most mods) are driven by
     *      Minecraft's real keyboard loop, which we never reach, so they were never invoked.
     * Posting the event is exactly what Minecraft.runTickKeyboard does for a real key.
     */
    private static void pressTick(String name) {
        int code = keyCodeFor(name);
        if (code != Keyboard.KEY_NONE) KeyBinding.onTick(code);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent());
    }

    private static String rightClick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.playerController == null) return "ERR no world";
        RayTraceResult r = mc.objectMouseOver;
        if (r == null) return "ERR no target";
        switch (r.typeOfHit) {
            case BLOCK: {
                EnumActionResult res = mc.playerController.processRightClickBlock(
                        mc.player, mc.world, r.getBlockPos(), r.sideHit, r.hitVec, EnumHand.MAIN_HAND);
                mc.player.swingArm(EnumHand.MAIN_HAND);
                return "OK rclick block " + r.getBlockPos().getX() + "," + r.getBlockPos().getY() + ","
                        + r.getBlockPos().getZ() + " face=" + r.sideHit + " -> " + res;
            }
            case ENTITY: {
                // Same order as vanilla rightClickMouse: interact-AT (with the hit point) first, then
                // plain interact. Mods like Immersive Railroading only handle the AT variant, so the
                // plain one alone never let the player board a train.
                EnumActionResult at = mc.playerController.interactWithEntity(mc.player, r.entityHit, r, EnumHand.MAIN_HAND);
                EnumActionResult plain = at == EnumActionResult.SUCCESS ? at
                        : mc.playerController.interactWithEntity(mc.player, r.entityHit, EnumHand.MAIN_HAND);
                mc.player.swingArm(EnumHand.MAIN_HAND);
                return "OK rclick entity at=" + at + " plain=" + plain;
            }
            default: {
                EnumActionResult res = mc.playerController.processRightClick(mc.player, mc.world, EnumHand.MAIN_HAND);
                return "OK use item -> " + res;
            }
        }
    }

    private static String leftClick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.playerController == null) return "ERR no world";
        RayTraceResult r = mc.objectMouseOver;
        if (r == null) return "ERR no target";
        switch (r.typeOfHit) {
            case BLOCK:
                mc.playerController.clickBlock(r.getBlockPos(), r.sideHit);
                mc.player.swingArm(EnumHand.MAIN_HAND);
                return "OK hit block " + r.getBlockPos().getX() + "," + r.getBlockPos().getY() + ","
                        + r.getBlockPos().getZ();
            case ENTITY:
                mc.playerController.attackEntity(mc.player, r.entityHit);
                mc.player.swingArm(EnumHand.MAIN_HAND);
                return "OK attack entity";
            default:
                return "OK miss";
        }
    }

    private static String buildState() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP pl = mc.player;
        if (pl == null) return "{\"world\":false}";

        String held = "empty";
        ItemStack stack = pl.getHeldItemMainhand();
        if (stack != null && !stack.isEmpty()) {
            held = stack.getDisplayName() + " x" + stack.getCount();
        }

        String looking = "none";
        RayTraceResult r = mc.objectMouseOver;
        if (r != null && r.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos bp = r.getBlockPos();
            IBlockState bs = mc.world.getBlockState(bp);
            String id = bs.getBlock().getRegistryName() == null ? "?" : bs.getBlock().getRegistryName().toString();
            looking = id + " @ " + bp.getX() + "," + bp.getY() + "," + bp.getZ() + " face=" + r.sideHit;
        } else if (r != null && r.typeOfHit == RayTraceResult.Type.ENTITY) {
            looking = "entity:" + r.entityHit.getName();
        }

        return "{"
                + "\"world\":true,"
                + "\"x\":" + fmt(pl.posX) + ",\"y\":" + fmt(pl.posY) + ",\"z\":" + fmt(pl.posZ) + ","
                + "\"yaw\":" + fmt(pl.rotationYaw) + ",\"pitch\":" + fmt(pl.rotationPitch) + ","
                + "\"health\":" + fmt(pl.getHealth()) + ","
                + "\"slot\":" + pl.inventory.currentItem + ","
                + "\"held\":\"" + esc(held) + "\","
                + "\"looking\":\"" + esc(looking) + "\""
                + "}";
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<net.minecraft.client.gui.GuiButton> buttons(net.minecraft.client.gui.GuiScreen s) {
        return (java.util.List<net.minecraft.client.gui.GuiButton>) net.minecraftforge.fml.relauncher.ReflectionHelper
                .getPrivateValue(net.minecraft.client.gui.GuiScreen.class, s, "buttonList", "field_146292_n");
    }

    private static String describeGui() {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.gui.GuiScreen s = mc.currentScreen;
        if (s == null) return "{\"screen\":null}";
        StringBuilder sb = new StringBuilder("{\"screen\":\"").append(esc(s.getClass().getName())).append('"')
                .append(",\"size\":\"").append(s.width).append('x').append(s.height).append('"');
        if (s instanceof net.minecraft.client.gui.inventory.GuiContainer) {
            net.minecraft.inventory.Container c = ((net.minecraft.client.gui.inventory.GuiContainer) s).inventorySlots;
            sb.append(",\"slots\":").append(c.inventorySlots.size()).append(",\"items\":[");
            boolean first = true;
            for (int i = 0; i < c.inventorySlots.size(); i++) {
                net.minecraft.inventory.Slot sl = c.inventorySlots.get(i);
                ItemStack st = sl.getStack();
                String inv = sl.inventory == mc.player.inventory ? "player" : "container";
                if (st.isEmpty() && !"container".equals(inv)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("\"").append(i).append(':').append(inv).append(':')
                        .append(st.isEmpty() ? "empty" : esc(st.getDisplayName()) + " x" + st.getCount()).append('"');
            }
            sb.append(']');
        }
        sb.append(",\"buttons\":[");
        boolean first = true;
        for (net.minecraft.client.gui.GuiButton b : buttons(s)) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\"").append(b.id).append(':').append(esc(b.displayString)).append(b.enabled ? "" : " (off)").append('"');
        }
        return sb.append("]}").toString();
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
