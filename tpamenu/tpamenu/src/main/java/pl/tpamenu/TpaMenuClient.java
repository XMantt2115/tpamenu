package pl.tpamenu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class TpaMenuClient implements ClientModInitializer {

    // Komendy, którymi akceptujesz cudzy teleport do siebie
    private static final Set<String> ACCEPT_COMMANDS =
            Set.of("tpaccept", "etpaccept", "tpyes", "tpayes", "etpyes");

    // Fragmenty wiadomości serwera oznaczające, że ktoś chce się do Ciebie teleportować
    private static final List<String> KEYWORDS = List.of(
            "wants to teleport to you", "requested to teleport to you",
            "request to teleport to you", "teleport request from",
            "sent you a teleport request", "sent you a tpa",
            "teleported to you", "teleporting to you",
            "chce się do ciebie teleportować", "chce sie do ciebie teleportowac",
            "prośbę o teleportację", "prosbe o teleportacje",
            "teleportował się do ciebie", "teleportowal sie do ciebie"
    );

    // Wiadomości, które ignorujemy (to Ty wysyłasz prośbę albo wygasła)
    private static final List<String> EXCLUDE = List.of(
            "you sent", "request sent", "wysłano", "wyslano", "wysłałeś", "wyslales",
            "expired", "wygasł", "wygasl", "denied", "odrzuc"
    );

    private static final long ENTRY_TTL_MS = 5 * 60_000; // wpis znika po 5 min
    private static final long ARM_MS = 15_000;           // czas czekania po /tpaccept
    private static final double NEAR = 6.0;
    private static final double FAR = 12.0;

    // nick -> czas dodania
    private static final LinkedHashMap<String, Long> REQUESTS = new LinkedHashMap<>();
    private static KeyBinding openKey;

    private long armedUntil = 0;
    private final Map<UUID, Double> lastDist = new HashMap<>();

    @Override
    public void onInitializeClient() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tpamenu.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.tpamenu"));

        // /tpaccept
        ClientSendMessageEvents.COMMAND.register(command -> {
            String[] parts = command.trim().split("\\s+");
            if (parts.length >= 1 && ACCEPT_COMMANDS.contains(parts[0].toLowerCase())) {
                if (parts.length >= 2) {
                    addRequest(parts[1]);
                } else {
                    armedUntil = System.currentTimeMillis() + ARM_MS;
                }
            }
        });

        // Wiadomości z serwera
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = message.getString();
            String lower = text.toLowerCase();
            for (String ex : EXCLUDE) {
                if (lower.contains(ex)) return;
            }
            boolean match = false;
            for (String k : KEYWORDS) {
                if (lower.contains(k)) { match = true; break; }
            }
            if (!match) return;
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                String nick = findNick(client, text);
                if (nick != null) addRequest(nick);
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Klawisz X
            while (openKey.wasPressed()) {
                if (client.currentScreen == null && client.player != null) {
                    client.setScreen(new TpaScreen());
                }
            }

            // Wykrywanie gracza, który pojawił się tuż przy Tobie po /tpaccept
            if (client.player == null || client.world == null) {
                lastDist.clear();
                return;
            }
            boolean armed = System.currentTimeMillis() < armedUntil;
            Map<UUID, Double> now = new HashMap<>();

            for (AbstractClientPlayerEntity other : client.world.getPlayers()) {
                if (other == client.player) continue;
                double dist = other.distanceTo(client.player);
                now.put(other.getUuid(), dist);

                Double prev = lastDist.get(other.getUuid());
                if (armed && dist < NEAR && (prev == null || prev > FAR)) {
                    addRequest(other.getName().getString());
                    armedUntil = 0;
                    armed = false;
                }
            }
            lastDist.clear();
            lastDist.putAll(now);
        });
    }

    private static void addRequest(String nick) {
        REQUESTS.remove(nick);
        REQUESTS.put(nick, System.currentTimeMillis());
    }

    private static List<String> activeRequests() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = REQUESTS.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > ENTRY_TTL_MS) it.remove();
        }
        List<String> list = new ArrayList<>(REQUESTS.keySet());
        Collections.reverse(list); // najnowsi na górze
        return list;
    }

    private static String findNick(MinecraftClient client, String text) {
        if (client.getNetworkHandler() == null) return null;
        String self = client.getSession().getUsername();
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            String name = entry.getProfile().getName();
            if (name == null || name.equalsIgnoreCase(self)) continue;
            Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
            if (p.matcher(text).find()) return name;
        }
        return null;
    }

    // ---------------- GUI ----------------
    public static class TpaScreen extends Screen {

        public TpaScreen() {
            super(Text.literal("Gracze teleportujący się do Ciebie"));
        }

        @Override
        protected void init() {
            List<String> nicks = activeRequests();
            int shown = Math.min(nicks.size(), 8);
            int x = this.width / 2 - 100;

            for (int i = 0; i < shown; i++) {
                String nick = nicks.get(i);
                this.addDrawableChild(ButtonWidget.builder(
                        Text.literal("Sprawdź: " + nick),
                        button -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.player != null && mc.player.networkHandler != null) {
                                mc.player.networkHandler.sendChatCommand("stats " + nick);
                            }
                            this.close();
                        }
                ).dimensions(x, 40 + i * 24, 200, 20).build());
            }

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Wyczyść listę"),
                    button -> {
                        REQUESTS.clear();
                        this.clearAndInit();
                    }
            ).dimensions(x, this.height - 40, 200, 20).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
            if (activeRequests().isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal("Brak graczy na liście"), this.width / 2, 50, 0xAAAAAA);
            }
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}
