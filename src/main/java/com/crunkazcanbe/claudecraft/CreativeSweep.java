package com.crunkazcanbe.claudecraft;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Her crash hunt, automated (2026-10-02): open the creative inventory and go through EVERY tab of every mod, scrolling
 * each page so every item is really drawn — that's where packs used to crash for her. For every item it also reads the
 * advanced tooltip and checks the model: a crash is caught and written down (with the item) instead of closing the
 * game; pink-black (missing texture) and invisible (model with nothing in it) items are listed per tab.
 * Report: logs/claudecraft-sweep.txt. A crash in DRAWING still crashes the game — its crash report names the item.
 */
public final class CreativeSweep {
    static final CreativeSweep INSTANCE = new CreativeSweep();

    private boolean running;
    private List<CreativeTabs> tabs;
    private int tabIdx, row, rows, wait, items, missing, invisible, tooltip;
    private PrintWriter out;
    private String status = "not started";
    private long started;

    private static Method setTab;
    private static Field tabPage, scroll;

    String start() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return "ERR not in a world";
        if (!mc.player.isCreative()) return "ERR switch to creative first (/gamemode 1)";
        if (running) return "ERR already running: " + status;
        try {
            if (setTab == null) {
                setTab = ReflectionHelper.findMethod(GuiContainerCreative.class, "setCurrentCreativeTab", "func_147050_b", CreativeTabs.class);
                tabPage = ReflectionHelper.findField(GuiContainerCreative.class, "tabPage");
                scroll = ReflectionHelper.findField(GuiContainerCreative.class, "currentScroll", "field_147067_x");
            }
            File f = new File(mc.mcDataDir, "logs/claudecraft-sweep.txt");
            out = new PrintWriter(new FileWriter(f, false), true);
        } catch (Throwable t) { return "ERR " + t; }
        tabs = new ArrayList<>();
        for (CreativeTabs t : CreativeTabs.CREATIVE_TAB_ARRAY)
            if (t != null && t != CreativeTabs.SEARCH && t != CreativeTabs.INVENTORY && t != CreativeTabs.HOTBAR) tabs.add(t);
        tabIdx = -1; row = rows = wait = items = missing = invisible = tooltip = 0;
        started = System.currentTimeMillis();
        out.println("Creative sweep " + new java.util.Date() + " — " + tabs.size() + " tabs");
        mc.displayGuiScreen(new GuiContainerCreative(mc.player));
        running = true;
        nextTab();
        return "OK sweeping " + tabs.size() + " tabs";
    }

    String status() {
        return (running ? "RUNNING " : "DONE ") + status + String.format(Locale.ROOT, " | items %d, pink %d, invisible %d, tooltip-crash %d, %ds",
                items, missing, invisible, tooltip, (System.currentTimeMillis() - started) / 1000);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (!running || e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) { finish("left the world"); return; }
        if (!(mc.currentScreen instanceof GuiContainerCreative)) mc.displayGuiScreen(new GuiContainerCreative(mc.player));
        if (wait-- > 0) return;
        GuiContainerCreative g = (GuiContainerCreative) mc.currentScreen;
        if (row < rows) {                                    // next page of this tab — the frame in between draws it
            row++;
            float f = rows == 0 ? 0 : row / (float) rows;
            try { scroll.setFloat(g, f); } catch (Throwable ignored) {}
            ((GuiContainerCreative.ContainerCreative) g.inventorySlots).scrollTo(f);
            wait = 1;
            return;
        }
        nextTab();
    }

    private void nextTab() {
        Minecraft mc = Minecraft.getMinecraft();
        if (++tabIdx >= tabs.size()) { finish("all tabs"); return; }
        CreativeTabs t = tabs.get(tabIdx);
        if (!(mc.currentScreen instanceof GuiContainerCreative)) mc.displayGuiScreen(new GuiContainerCreative(mc.player));
        GuiContainerCreative g = (GuiContainerCreative) mc.currentScreen;
        String label;
        try { label = I18n.format(t.getTranslatedTabLabel()); } catch (Throwable x) { label = t.getTabLabel(); }
        status = "tab " + (tabIdx + 1) + "/" + tabs.size() + " " + label;
        try {
            tabPage.setInt(null, t.getTabPage());
            setTab.invoke(g, t);
        } catch (Throwable x) {
            out.println("\n## " + label + " — COULD NOT OPEN THIS TAB: " + cause(x));
            return;
        }
        NonNullList<ItemStack> list = ((GuiContainerCreative.ContainerCreative) g.inventorySlots).itemList;
        out.println("\n## " + label + " [" + t.getTabLabel() + "] — " + list.size() + " items" + (label.contains(".") ? "   (TAB NAME NOT TRANSLATED)" : ""));
        for (ItemStack s : list) check(mc, s);
        out.flush();
        rows = Math.max(0, (list.size() + 8) / 9 - 5);
        row = 0;
        try { scroll.setFloat(g, 0); } catch (Throwable ignored) {}
        ((GuiContainerCreative.ContainerCreative) g.inventorySlots).scrollTo(0);
        wait = 2;
    }

    private void check(Minecraft mc, ItemStack s) {
        items++;
        String id = "?";
        try { ResourceLocation r = s.getItem().getRegistryName(); id = (r == null ? "?" : r.toString()) + "@" + s.getMetadata(); } catch (Throwable ignored) {}
        String name;
        try { name = s.getDisplayName(); } catch (Throwable x) { name = "(name crashed: " + cause(x) + ")"; }
        try { s.getTooltip(mc.player, ITooltipFlag.TooltipFlags.ADVANCED); }
        catch (Throwable x) { tooltip++; out.println("  TOOLTIP CRASH  " + id + "  " + name + "  — " + cause(x)); }
        try {
            IBakedModel m = mc.getRenderItem().getItemModelWithOverrides(s, null, mc.player);
            if (m == mc.getRenderItem().getItemModelMesher().getModelManager().getMissingModel()) {
                missing++; out.println("  PINK (no model)   " + id + "  " + name); return;
            }
            if (m.isBuiltInRenderer()) return;                // drawn by code — can't judge from the model
            TextureAtlasSprite miss = mc.getTextureMapBlocks().getMissingSprite();
            int quads = 0; boolean pink = false;
            for (EnumFacing f : new EnumFacing[]{null, EnumFacing.UP, EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST})
                for (BakedQuad q : m.getQuads(null, f, 0L)) { quads++; if (q.getSprite() == miss) pink = true; }
            if (quads == 0) { invisible++; out.println("  INVISIBLE        " + id + "  " + name); }
            else if (pink) { missing++; out.println("  PINK (texture)   " + id + "  " + name); }
        } catch (Throwable x) { tooltip++; out.println("  MODEL CRASH      " + id + "  " + name + "  — " + cause(x)); }
    }

    private void finish(String why) {
        running = false;
        status = "finished (" + why + ")";
        if (out != null) {
            out.println(String.format(Locale.ROOT, "\nDONE (%s): %d items in %d tabs — pink %d, invisible %d, tooltip/model crashes %d, %d s",
                    why, items, Math.max(0, Math.min(tabIdx, tabs.size())), missing, invisible, tooltip, (System.currentTimeMillis() - started) / 1000));
            out.close();
        }
        Minecraft.getMinecraft().displayGuiScreen(null);
    }

    private static String cause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        StackTraceElement[] st = t.getStackTrace();
        return t.getClass().getSimpleName() + ": " + t.getMessage() + (st.length > 0 ? " @ " + st[0] : "");
    }
}
