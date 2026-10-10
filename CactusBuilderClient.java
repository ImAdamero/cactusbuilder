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

            if (ticks - lastHealTick > HEAL_TIMEOUT) {
                stop(mc, "HP nie rosnie - koniec");
                return true;
            }

            if (p.isUsingItem()) {            // jedzenie trwa (nasze albo auto-eata)
                eatStarted = true;
                moveToSafeSpot(mc, p, w);
                return true;
            }
            if (eatStarted) {                 // wlasnie skonczylo sie jesc
                eatStarted = false;
                mc.options.useKey.setPressed(false);
            }

            moveToSafeSpot(mc, p, w);
            if (BUILTIN_EAT && !p.hasStatusEffect(StatusEffects.REGENERATION)) {
                eat(mc, p);
            }
            return true;
        }

        // jedzenie przez auto-eat z klienta, gdy HP nie jest jeszcze niskie
        if (p.isUsingItem()) {
            status = "Pauza: jedzenie";
            stopWalking(mc);
            return true;
        }
        return false;
    }

    private static void eat(MinecraftClient mc, ClientPlayerEntity p) {
        int slot = findHotbar(p, HEAL_ITEM);
        if (slot < 0) {
            for (int i = 9; i < 36; i++) {
                if (p.getInventory().getStack(i).isOf(HEAL_ITEM)) {
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i,
                            EAT_SLOT, SlotActionType.SWAP, p);
                    return;
                }
            }
            stop(mc, "Brak refow do jedzenia");
            return;
        }
        p.getInventory().setSelectedSlot(slot);
        mc.options.useKey.setPressed(true);
    }

    private static boolean cactusAt(ClientWorld w, BlockPos pos) {
        return w.getBlockState(pos).isOf(Blocks.CACTUS);
    }

    /** Czy komorka (stopy) jest przy kaktusie - czyli boli. */
    private static boolean unsafe(ClientWorld w, BlockPos c) {
        if (cactusAt(w, c) || cactusAt(w, c.up())) return true;
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (cactusAt(w, c.offset(d)) || cactusAt(w, c.up().offset(d))) return true;
        }
        return false;
    }

    /** Podczas leczenia zejdz z/od kaktusa, zeby nie tracic HP. */
    private static void moveToSafeSpot(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        BlockPos here = p.getBlockPos();
        if (!unsafe(w, here)) return;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos c = here.offset(d);
            if (!solid(w, c) && !solid(w, c.up()) && solid(w, c.down())
                    && !cactusAt(w, c.down()) && !unsafe(w, c)) {
                walkTo(mc, Vec3d.ofBottomCenter(c));
                return;
            }
        }
    }

    // ------------------------------------------------------------- ruch

    private static boolean solid(ClientWorld w, BlockPos pos) {
        return !w.getBlockState(pos).getCollisionShape(w, pos).isEmpty();
    }

    private static void walkTo(MinecraftClient mc, Vec3d goal) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        double dx = goal.x - p.getX();
        double dz = goal.z - p.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);

        // plynny obrot glowy
        float desired = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float diff = MathHelper.wrapDegrees(desired - p.getYaw());
        if (len > 0.01) {
            p.setYaw(p.getYaw() + MathHelper.clamp(diff, -MAX_TURN, MAX_TURN));
        }
        boolean aligned = Math.abs(diff) < 50f;

        mc.options.forwardKey.setPressed(len > 0.15 && aligned);
        walking = true;

        // auto-skok na przeszkode wysokosci 1 (np. kaktus, potem piasek)
        boolean jump = false;
        if (len > 0.01 && aligned && p.isOnGround()) {
            double nx = dx / len, nz = dz / len;
            BlockPos ahead = BlockPos.ofFloored(p.getX() + nx * 0.7, p.getY(), p.getZ() + nz * 0.7);
            boolean blocked = solid(w, ahead);
            boolean room = !solid(w, ahead.up()) && !solid(w, ahead.up(2));
            jump = blocked && room;
        }
        mc.options.jumpKey.setPressed(jump);
    }

    private static void stopWalking(MinecraftClient mc) {
        walking = false;
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    // -------------------------------------------------------- stawianie

    private static int findHotbar(ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static Res place(MinecraftClient mc, ChunkPlan.Target t) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        BlockPos pos = t.pos();
        Item item = t.kind().item;

        // warunki podparcia (zeby piasek nie spadl, a kaktus mogl stac)
        BlockState below = w.getBlockState(pos.down());
        switch (t.kind()) {
            case SAND -> {
                if (below.isAir() || below.isReplaceable() || !below.getFluidState().isEmpty())
                    return Res.NO_SUPPORT;
            }
            case CACTUS -> {
                if (!(below.isOf(Blocks.SAND) || below.isOf(Blocks.RED_SAND) || below.isOf(Blocks.CACTUS)))
                    return Res.NO_SUPPORT;
            }
            default -> { }
        }

        // przedmiot na pasku / dobranie z ekwipunku
        int slot = findHotbar(p, item);
        if (slot < 0) {
            for (int i = 9; i < 36; i++) {
                if (p.getInventory().getStack(i).isOf(item)) {
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i,
                            RESTOCK_SLOT, SlotActionType.SWAP, p);
                    return Res.WAIT;
                }
            }
            return Res.NO_ITEM;
        }

        // sasiad, o ktory mozna oprzec klikniecie
        for (Direction d : Direction.values()) {
            BlockPos nb = pos.offset(d);
            BlockState ns = w.getBlockState(nb);
            if (ns.isAir() || ns.isReplaceable() || !ns.getFluidState().isEmpty()) continue;
            if (item == Items.CACTUS && ns.isOf(Blocks.FLOWER_POT)) continue; // nie wsadzaj kaktusa do doniczki

            Direction face = d.getOpposite();
            Vec3d hitPos = Vec3d.ofCenter(nb).add(Vec3d.of(face.getVector()).multiply(0.5));
            BlockHitResult hit = new BlockHitResult(hitPos, face, nb, false);

            p.getInventory().setSelectedSlot(slot);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            p.swingHand(Hand.MAIN_HAND);
            return Res.DONE;
        }
        return Res.NO_SUPPORT;
    }

    // -------------------------------------------------------------- HUD

    private static int count(ClientPlayerEntity p, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    private static void drawHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        ClientPlayerEntity p = mc.player;

        int sand = count(p, Items.SAND);
        int pots = count(p, Items.FLOWER_POT);
        int cacti = count(p, Items.CACTUS);
        int refs = count(p, HEAL_ITEM);

        int missingBase = plan != null ? baseMissing : 256;
        int layers = Math.min(Math.min(cacti / PER_LAYER, pots / PER_LAYER),
                Math.max(0, sand - missingBase) / PER_LAYER);

        int x = 6, y = 6, step = 11;
        ctx.drawTextWithShadow(mc.textRenderer, "Cactus Builder: " + status, x, y, 0xFFFFFF55);
        y += step;
        ctx.drawTextWithShadow(mc.textRenderer, "Piasek: " + sand, x, y, 0xFFFFFFFF);
        y += step;
        ctx.drawTextWithShadow(mc.textRenderer, "Doniczki: " + pots, x, y, 0xFFFFFFFF);
        y += step;
        ctx.drawTextWithShadow(mc.textRenderer, "Kaktusy: " + cacti, x, y, 0xFFFFFFFF);
        y += step;
        ctx.drawTextWithShadow(mc.textRenderer, "Refy: " + refs, x, y, 0xFFFFFFFF);
        y += step;
        ctx.drawTextWithShadow(mc.textRenderer,
                "Pieter z eq: " + layers + " (podloze: " + missingBase + " piasku)", x, y, 0xFF55FF55);
        y += step;

        if (plan != null) {
            ctx.drawTextWithShadow(mc.textRenderer,
                    "Do konca (" + MAX_LAYERS + " pieter): piasek " + remaining[1]
                            + ", doniczki " + remaining[2] + ", kaktusy " + remaining[0],
                    x, y, 0xFFAAAAAA);
            y += step;
            ctx.drawTextWithShadow(mc.textRenderer,
                    "Chunk " + plan.chunk.x + "," + plan.chunk.z
                            + "  pominiete: " + skipped.size(), x, y, 0xFFAAAAAA);
        }
    }
}
