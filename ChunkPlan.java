package pl.cactusbuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.Set;

/**
 * Plan budowy jednego chunka (16x16), szachownica (pola A i B):
 *
 *   h = 0            : piasek TYLKO na polach A (pod kaktusami), pola B puste
 *   h = 1 (pietro 0) : kaktus na polach A, doniczka na polach B (przy boku kaktusa)
 *   h = 2            : piasek na doniczkach (nad kaktusami powietrze)
 *   h = 3 (pietro 1) : kaktus na polach B (na piasku z h=2), doniczka na polach A
 *   h = 4            : piasek na doniczkach
 *   ... i tak w kolko, pietra naprzemiennie.
 *
 * Kolejnosc: najpierw piasek pod kaktusy, potem wszystkie kaktusy pietra,
 * potem komorka po komorce: doniczka i od razu piasek na niej.
 */
final class ChunkPlan {

    enum Kind {
        CACTUS(Items.CACTUS, Blocks.CACTUS),
        SAND(Items.SAND, Blocks.SAND),
        POT(Items.FLOWER_POT, Blocks.FLOWER_POT);

        final Item item;
        final Block block;

        Kind(Item item, Block block) {
            this.item = item;
            this.block = block;
        }
    }

    record Target(BlockPos pos, Kind kind) {}

    final ChunkPos chunk;
    final int minX, minZ, baseY, maxLayers;

    ChunkPlan(ChunkPos chunk, int baseY, int maxLayers) {
        this.chunk = chunk;
        this.minX = chunk.getStartX();
        this.minZ = chunk.getStartZ();
        this.baseY = baseY;
        this.maxLayers = maxLayers;
    }

    int maxH() {
        return 2 * maxLayers;
    }

    boolean contains(double x, double z, double margin) {
        return x >= minX - margin && x < minX + 16 + margin
                && z >= minZ - margin && z < minZ + 16 + margin;
    }

    /** Co ma stac na (x, z) na wysokosci h nad podlozem. null = powietrze. */
    Kind expected(int x, int z, int h) {
        int parity = (x + z) & 1;          // 0 albo 1 (dziala tez dla ujemnych)
        if (h == 0) return parity == 0 ? Kind.SAND : null;   // podloze: szachownica pod kaktusami
        int layer = (h - 1) / 2;
        boolean sandOnPots = ((h - 1) % 2) == 1;
        int cactusParity = layer & 1;      // ktore pola maja kaktusy na tym pietrze
        if (!sandOnPots) {
            return parity == cactusParity ? Kind.CACTUS : Kind.POT;
        }
        return parity != cactusParity ? Kind.SAND : null;
    }

    private static boolean missing(ClientWorld w, BlockPos pos, Kind kind) {
        BlockState st = w.getBlockState(pos);
        return !st.isOf(kind.block) && st.isReplaceable();
    }

    private Target scan(ClientWorld w, Set<BlockPos> skipped, int h, Kind kind) {
        for (int zi = 0; zi < 16; zi++) {
            for (int xi = 0; xi < 16; xi++) {
                int x = minX + ((zi & 1) == 0 ? xi : 15 - xi);
                int z = minZ + zi;
                if (expected(x, z, h) != kind) continue;
                BlockPos pos = new BlockPos(x, baseY + h, z);
                if (skipped.contains(pos)) continue;
                if (missing(w, pos, kind)) return new Target(pos, kind);
            }
        }
        return null;
    }

    /** Nastepne brakujace miejsce wedlug planu. */
    Target next(ClientWorld w, Set<BlockPos> skipped) {
        for (int layer = 0; layer < maxLayers; layer++) {
            int hCactus = 2 * layer + 1;

            if (layer == 0) {                                   // piasek pod pierwsze kaktusy
                Target base = scan(w, skipped, 0, Kind.SAND);
                if (base != null) return base;
            }

            Target cacti = scan(w, skipped, hCactus, Kind.CACTUS);   // wszystkie kaktusy pietra
            if (cacti != null) return cacti;

            // doniczka + od razu piasek na niej, komorka po komorce
            for (int zi = 0; zi < 16; zi++) {
                for (int xi = 0; xi < 16; xi++) {
                    int x = minX + ((zi & 1) == 0 ? xi : 15 - xi);
                    int z = minZ + zi;
                    if (expected(x, z, hCactus) != Kind.POT) continue;
                    BlockPos pot = new BlockPos(x, baseY + hCactus, z);
                    BlockPos sand = pot.up();
                    if (skipped.contains(pot)) continue;
                    if (missing(w, pot, Kind.POT)) return new Target(pot, Kind.POT);
                    if (!skipped.contains(sand) && missing(w, sand, Kind.SAND)) {
                        return new Target(sand, Kind.SAND);
                    }
                }
            }
        }
        return null;
    }

    /** Ile jeszcze brakuje do konca planu: indeks = Kind.ordinal() (kaktus, piasek, doniczka). */
    int[] remaining(ClientWorld w) {
        int[] r = new int[3];
        for (int h = 0; h <= maxH(); h++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int x = minX; x < minX + 16; x++) {
                    Kind k = expected(x, z, h);
                    if (k != null && missing(w, new BlockPos(x, baseY + h, z), k)) r[k.ordinal()]++;
                }
            }
        }
        return r;
    }

    /** Ile brakuje piasku na samym podlozu (h = 0). */
    int missingBase(ClientWorld w) {
        int n = 0;
        for (int z = minZ; z < minZ + 16; z++) {
            for (int x = minX; x < minX + 16; x++) {
                if (expected(x, z, 0) == Kind.SAND
                        && missing(w, new BlockPos(x, baseY, z), Kind.SAND)) n++;
            }
        }
        return n;
    }
}
