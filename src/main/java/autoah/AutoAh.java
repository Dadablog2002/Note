package autoah;

import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.LiteralText;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class AutoAh implements ClientModInitializer {

    static class Cfg {
        String price = "1000";
        int count = 64;
        int hotbarSlot = 1;
        int timerSeconds = 300;
        String openCommand = "ah";
        String sellCommand = "ah sell {price}";
        int myLotsSlot = 49;
        int[] cancelSlots = {0};
        int moveMs = 450;
    }

    static Cfg cfg = new Cfg();
    static boolean running = false;
    static final Deque<Step> queue = new ArrayDeque<>();
    static KeyBinding toggleKey;
    static final Random rnd = new Random();

    static boolean moving = false;
    static double sx, sy, tx, ty, bend;
    static long t0;
    static int dur;

    static void moveTo(double x, double y) {
        MinecraftClient mc = MinecraftClient.getInstance();
        sx = mc.mouse.getX(); sy = mc.mouse.getY();
        tx = x; ty = y;
        dur = cfg.moveMs + rnd.nextInt(200);
        bend = (rnd.nextDouble() - 0.5) * 60;
        t0 = System.currentTimeMillis();
        moving = true;
    }

    static void frame() {
        if (!moving) return;
        double t = Math.min(1.0, (System.currentTimeMillis() - t0) / (double) dur);
        double s = t * t * (3 - 2 * t);
        double dx = tx - sx, dy = ty - sy;
        double len = Math.max(1, Math.hypot(dx, dy));
        double off = Math.sin(Math.PI * t) * bend;
        double x = sx + dx * s + (-dy / len) * off;
        double y = sy + dy * s + (dx / len) * off;
        GLFW.glfwSetCursorPos(MinecraftClient.getInstance().getWindow().getHandle(), x, y);
        if (t >= 1.0) moving = false;
    }

    interface Step { boolean tick(MinecraftClient mc); }

    static class Wait implements Step {
        int left; Wait(int ticks) { left = ticks; }
        public boolean tick(MinecraftClient mc) { return --left <= 0; }
    }

    static class Cmd implements Step {
        String c; boolean sent; int w = 10;
        Cmd(String c) { this.c = c; }
        public boolean tick(MinecraftClient mc) {
            if (!sent) {
                String s = c.replace("{price}", cfg.price).replace("{count}", String.valueOf(cfg.count));
                mc.player.sendChatMessage("/" + s);
                sent = true;
            }
            return --w <= 0;
        }
    }

    static class WaitScreen implements Step {
        int timeout = 100;
        public boolean tick(MinecraftClient mc) {
            if (mc.currentScreen instanceof GenericContainerScreen) return true;
            if (--timeout <= 0) { fail(mc, "Меню не відкрилось"); }
            return false;
        }
    }

    static class Hotbar implements Step {
        public boolean tick(MinecraftClient mc) {
            mc.player.inventory.selectedSlot = Math.max(0, Math.min(8, cfg.hotbarSlot - 1));
            return true;
        }
    }

    static class Close implements Step {
        public boolean tick(MinecraftClient mc) { mc.player.closeHandledScreen(); return true; }
    }

    static class Click implements Step {
        int slot; int phase = 0; int w = 12;
        Click(int slot) { this.slot = slot; }
        public boolean tick(MinecraftClient mc) {
            if (!(mc.currentScreen instanceof GenericContainerScreen)) return false;
            GenericContainerScreen scr = (GenericContainerScreen) mc.currentScreen;
            GenericContainerScreenHandler h = scr.getScreenHandler();
            if (slot >= h.slots.size()) return true;
            if (phase == 0) {
                int bgH = 114 + h.getRows() * 18;
                int left = (mc.getWindow().getScaledWidth() - 176) / 2;
                int top = (mc.getWindow().getScaledHeight() - bgH) / 2;
                double scale = mc.getWindow().getScaleFactor();
                double px = (left + h.slots.get(slot).x + 8) * scale;
                double py = (top + h.slots.get(slot).y + 8) * scale;
                moveTo(px, py);
                phase = 1;
            } else if (phase == 1 && !moving) {
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                phase = 2;
            } else if (phase == 2) {
                return --w <= 0;
            }
            return false;
        }
    }

    static void fail(MinecraftClient mc, String msg) {
        queue.clear();
        if (mc.player != null) {
            mc.player.closeHandledScreen();
            mc.player.sendMessage(new LiteralText("§c[AutoAH] " + msg + ", повтор через 5с"), false);
        }
        queue.add(new Wait(100));
        queue.add(mc2 -> { buildCycle(); return true; });
    }

    static void buildCycle() {
        queue.add(new Cmd(cfg.openCommand));
        queue.add(new WaitScreen());
        queue.add(new Wait(10));
        queue.add(new Click(cfg.myLotsSlot));
        queue.add(new Wait(15));
        for (int s : cfg.cancelSlots) {
            queue.add(new Click(s));
            queue.add(new Wait(10 + rnd.nextInt(10)));
        }
        queue.add(new Close());
        queue.add(new Wait(10));
        queue.add(new Hotbar());
        queue.add(new Cmd(cfg.sellCommand));
        queue.add(new Wait(Math.max(1, cfg.timerSeconds) * 20));
        queue.add(mc -> { buildCycle(); return true; });
    }

    static void loadCfg() {
        File f = new File(MinecraftClient.getInstance().runDirectory, "config/autoah.json");
        Gson g = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (f.exists()) cfg = g.fromJson(new String(Files.readAllBytes(f.toPath())), Cfg.class);
            else { f.getParentFile().mkdirs(); Files.write(f.toPath(), g.toJson(cfg).getBytes()); }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("AutoAH вкл/викл", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "AutoAH"));

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) ->
                ScreenEvents.afterRender(screen).register((s, m, mx, my, d) -> frame()));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.wasPressed()) {
                running = !running;
                queue.clear(); moving = false;
                if (running) { loadCfg(); buildCycle(); }
                if (mc.player != null)
                    mc.player.sendMessage(new LiteralText("[AutoAH] " + (running ? "§aУВІМКНЕНО" : "§cВИМКНЕНО")), true);
            }
            if (!running || mc.player == null) return;
            Step s = queue.peek();
            if (s != null && s.tick(mc)) queue.poll();
        });
    }
}