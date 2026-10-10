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
 * Plan budowy jednego chunka (16x16), szachownica:
 *
 *   h = 0            : piasek na calym chunku
 *   h = 1 (pietro 0) : kaktus na polach A, doniczka na polach B
 *   h = 2            : piasek na doniczkach (nad kaktusami powietrze)
 *   h = 3 (pietro 1) : kaktus na polach B (na piasku z h=2), doniczka na polach A
 *   h = 4            : piasek na doniczkach
 *   ... i tak w kolko, pietra naprzemiennie.
 *
 * Jedno "pietro" = 2 wysokosci = 128 kaktusow + 128 doniczek + 128 piasku.
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

    /** Kolejnosc stawiania na jednej wysokosci: kaktusy, piasek, doniczki. */
    private static final Kind[] ORDER = {Kind.CACTUS, Kind.SAND, Kind.POT};

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
        if (h == 0) return Kind.SAND;
        int layer = (h - 1) / 2;
        boolean sandOnPots = ((h - 1) % 2) == 1;
        int parity = (x + z) & 1;          // 0 albo 1 (dziala tez dla ujemnych)
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

    /** Pierwsze brakujace miejsce wedlug planu (weze po rzedach, od dolu do gory). */
    Target next(ClientWorld w, Set<BlockPos> skipped) {
        for (int h = 0; h <= maxH(); h++) {
            for (Kind kind : ORDER) {
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
                if (missing(w, new BlockPos(x, baseY, z), Kind.SAND)) n++;
            }
        }
        return n;
    }
}
