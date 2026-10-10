package pl.cactusbuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * G = start / pauza (wznawia od pierwszego brakujacego miejsca w chunku, na ktorym stoisz)
 * H = zapomnij sesje (nowy poziom podloza przy nastepnym starcie)
 *
 * Gdy HP spadnie, praca sie PAUZUJE (nie konczy): mod schodzi z kaktusa, je refy,
 * czeka na leczenie i sam wznawia budowe. Jesli masz wlasny auto-eat w kliencie,
 * ustaw BUILTIN_EAT = false - mod i tak pauzuje, dopoki trwa jedzenie / HP jest niskie.
 */
public class CactusBuilderClient implements ClientModInitializer {

    // ====== USTAWIENIA ======
    private static final int MAX_LAYERS = 8;        // ile pieter (po 2 wysokosci) planowac
    private static final int PLACE_DELAY = 2;       // ticki miedzy blokami (+ losowo 0-1)
    private static final double REACH = 4.3;        // zasieg stawiania
    private static final double APPROACH = 3.4;     // jak blisko podejsc, gdy cel jest za daleko
    private static final int RESTOCK_SLOT = 8;      // slot paska, do ktorego dobiera przedmioty
    private static final int MAX_ATTEMPTS = 6;      // tyle prob na jedno miejsce, potem pomija
    private static final int MAX_WALK_TICKS = 300;  // tyle tickow na dojscie do celu
    private static final int PER_LAYER = 128;       // 256 pol / 2 (szachownica)
    private static final float MAX_TURN = 25f;      // max obrot glowy na tick (stopnie)

    // leczenie
    private static final float MIN_HEALTH = 6.0f;   // ponizej tego: pauza i leczenie
    private static final float RESUME_HEALTH = 14.0f; // od tego wznawia budowe
    private static final boolean BUILTIN_EAT = true;  // false = zostaw jedzenie swojemu auto-eatowi
    private static final Item HEAL_ITEM = Items.GOLDEN_APPLE; // "refy"
    private static final int EAT_SLOT = 8;          // gdzie wrzucic refy, gdy leza w ekwipunku
    private static final int HEAL_TIMEOUT = 200;    // ticki bez wzrostu HP -> stop
    // ========================

    private enum State { IDLE, RUNNING }
    private enum Res { DONE, WAIT, NO_SUPPORT, NO_ITEM }

    private static final Random RNG = new Random();

    private static KeyBinding toggleKey, resetKey;

    private static State state = State.IDLE;
    private static String status = "Pauza";

    private static ChunkPlan plan;
    private static ChunkPos sessionChunk;
    private static int sessionBaseY;
    private static ClientPlayerEntity runPlayer;

    private static ChunkPlan.Target target;
    private static final Map<BlockPos, Integer> attempts = new HashMap<>();
    private static final Set<BlockPos> skipped = new HashSet<>();
    private static int rounds, cooldown, walkTicks, hudTimer, ticks;
    private static boolean walking;

    // leczenie
    private static boolean healing, eatStarted;
    private static float lastHp;
    private static int lastHealTick;

    private static int[] remaining = new int[3]; // kaktus, piasek, doniczka
    private static int baseMissing = 256;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category =
                KeyBinding.Category.create(Identifier.of("cactusbuilder", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cactusbuilder.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
        resetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cactusbuilder.reset", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

        ClientTickEvents.END_CLIENT_TICK.register(CactusBuilderClient::tick);
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> drawHud(ctx));
    }

    // ---------------------------------------------------------------- tick

    private static void tick(MinecraftClient mc) {
        ticks++;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            state = State.IDLE;
            target = null;
            return;
        }
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;

        while (resetKey.wasPressed()) {
            sessionChunk = null;
            plan = null;
            if (state == State.RUNNING) stop(mc, "Sesja wyczyszczona");
            status = "Sesja wyczyszczona";
        }
        while (toggleKey.wasPressed()) {
            if (state == State.IDLE) start(mc); else stop(mc, "Pauza");
        }

        if (plan != null && ++hudTimer >= 20) {
            hudTimer = 0;
            remaining = plan.remaining(w);
            baseMissing = plan.missingBase(w);
        }

        if (state != State.RUNNING) return;

        // zgon / respawn / zmiana swiata
        if (!p.isAlive() || p != runPlayer) { stop(mc, "Zgon lub respawn"); return; }
        if (!plan.contains(p.getX(), p.getZ(), 3.0)) { stop(mc, "Wyszedles poza chunk"); return; }

        // pauza na leczenie / jedzenie
        if (handleHealing(mc, p, w)) return;
        status = "Buduje";

        // aktualny cel
        if (target != null && !w.getBlockState(target.pos()).isReplaceable()) {
            target = null; // postawiony (liczniki prob zostaja - lapia "ghost bloki")
        }
        if (target == null) {
            target = plan.next(w, skipped);
            walkTicks = 0;
            if (target == null) {
                if (!skipped.isEmpty() && rounds < 2) {
                    rounds++;
                    skipped.clear();
                    attempts.clear();
                    return;
                }
                int left = remaining[0] + remaining[1] + remaining[2];
                stop(mc, left == 0 ? "Chunk gotowy" : "Nic wiecej nie da sie postawic");
                return;
            }
        }

        BlockPos pos = target.pos();
        Vec3d center = Vec3d.ofCenter(pos);
        double dist = p.getEyePos().distanceTo(center);

        // stoisz w miejscu, gdzie ma byc blok - odejdz
        if (p.getBoundingBox().intersects(new Box(pos))) {
            Vec3d away = new Vec3d(p.getX() - center.x, 0, p.getZ() - center.z);
            if (away.lengthSquared() < 1.0E-4) away = new Vec3d(1, 0, 0);
            walkTo(mc, new Vec3d(p.getX(), p.getY(), p.getZ()).add(away.normalize().multiply(2.0)));
            return;
        }

        // za daleko - podejdz (z histereza)
        if (dist > REACH || (walking && dist > APPROACH)) {
            if (++walkTicks > MAX_WALK_TICKS) { skip(pos); return; }
            walkTo(mc, center);
            return;
        }
        stopWalking(mc);

        if (cooldown-- > 0) return;
        cooldown = PLACE_DELAY + RNG.nextInt(2);

        Res r = place(mc, target);
        switch (r) {
            case WAIT -> { }
            case NO_ITEM -> stop(mc, "Brak: " + target.kind().item.getName().getString()
                    + " - uzupelnij i wcisnij G");
            case DONE, NO_SUPPORT -> {
                if (attempts.merge(pos, 1, Integer::sum) > MAX_ATTEMPTS) skip(pos);
            }
        }
    }

    private static void skip(BlockPos pos) {
        skipped.add(pos);
        attempts.remove(pos);
        target = null;
    }

    private static void start(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ChunkPos chunk = new ChunkPos(p.getBlockPos());
        int baseY;
        if (sessionChunk != null && sessionChunk.equals(chunk)) {
            baseY = sessionBaseY;                 // wznowienie - ten sam chunk i poziom
        } else {
            baseY = p.getBlockPos().getY();       // nowy chunk - podloze na wysokosci stop
            sessionChunk = chunk;
            sessionBaseY = baseY;
        }
        plan = new ChunkPlan(chunk, baseY, MAX_LAYERS);
        skipped.clear();
        attempts.clear();
        target = null;
        rounds = 0;
        cooldown = 0;
        hudTimer = 20;
        healing = false;
        eatStarted = false;
        lastHp = p.getHealth();
        lastHealTick = ticks;
        runPlayer = p;
        state = State.RUNNING;
        status = "Buduje";
    }

    private static void stop(MinecraftClient mc, String why) {
        state = State.IDLE;
        status = why;
        target = null;
        healing = false;
        eatStarted = false;
        stopWalking(mc);
        mc.options.useKey.setPressed(false);
        if (mc.player != null) mc.player.sendMessage(Text.literal("[CactusBuilder] " + why), true);
    }

    // --------------------------------------------------------- leczenie

    /** true = w tym ticku nie budujemy (pauza). */
    private static boolean handleHealing(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        float hp = p.getHealth();
        if (hp > lastHp + 0.01f) lastHealTick = ticks;   // HP rosnie
        lastHp = hp;

        if (!healing && hp <= MIN_HEALTH) {
            healing = true;
            lastHealTick = ticks;
        }
        if (healing && hp >= RESUME_HEALTH) {
            healing = false;
            mc.options.useKey.setPressed(false);
        }

        if (healing) {
            status = "Pauza: leczenie";
            stopWalking(mc);
            
