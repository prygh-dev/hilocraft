package com.example.examplemod;

import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockOldLeaf;
import net.minecraft.block.BlockOldLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenAbstractTree;

import java.util.*;

/**
 * Recursive branching tree generator for Forge 1.12.2.
 *
 * Pattern: single trunk → forks into major branches → each forks again
 * into sub-branches → repeat for N levels. Branches get thinner and
 * shorter at each level.
 */
public class WorldGenBranchingTree extends WorldGenAbstractTree {

    // ========== UNDO ==========

    public static class BlockSnapshot {
        public final BlockPos pos;
        public final IBlockState previousState;

        public BlockSnapshot(BlockPos pos, IBlockState previousState) {
            this.pos = pos;
            this.previousState = previousState;
        }
    }

    private static final Map<UUID, Deque<List<BlockSnapshot>>> undoHistory = new HashMap<>();
    private static final int MAX_UNDO_DEPTH = 5;

    private List<BlockSnapshot> currentSnapshots;
    private Set<BlockPos> touchedPositions;
    private World currentWorld;

    public static void commitGeneration(UUID playerUUID, List<BlockSnapshot> snapshots) {
        Deque<List<BlockSnapshot>> stack = undoHistory.computeIfAbsent(playerUUID, k -> new ArrayDeque<>());
        stack.addLast(snapshots);
        while (stack.size() > MAX_UNDO_DEPTH) {
            stack.removeFirst();
        }
    }

    public static int undo(World world, UUID playerUUID) {
        Deque<List<BlockSnapshot>> stack = undoHistory.get(playerUUID);
        if (stack == null || stack.isEmpty()) return -1;
        List<BlockSnapshot> snapshots = stack.removeLast();
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            BlockSnapshot snap = snapshots.get(i);
            world.setBlockState(snap.pos, snap.previousState, 3);
        }
        return snapshots.size();
    }

    public static int undoCount(UUID playerUUID) {
        Deque<List<BlockSnapshot>> stack = undoHistory.get(playerUUID);
        return (stack == null) ? 0 : stack.size();
    }

    // ========== PARAMETERS ==========

    private final int trunkHeight;
    private final int forkLevels;       // how many recursive fork levels
    private final int forksPerBranch;   // how many sub-branches per fork

    private Random rand;

    private static final IBlockState LOG_STATE =
            Blocks.LOG.getDefaultState()
                    .withProperty(BlockOldLog.VARIANT, BlockPlanks.EnumType.JUNGLE)
                    .withProperty(BlockLog.LOG_AXIS, BlockLog.EnumAxis.NONE);

    private static final IBlockState LEAF_STATE =
            Blocks.LEAVES.getDefaultState()
                    .withProperty(BlockOldLeaf.VARIANT, BlockPlanks.EnumType.JUNGLE)
                    .withProperty(BlockOldLeaf.CHECK_DECAY, Boolean.FALSE)
                    .withProperty(BlockOldLeaf.DECAYABLE, Boolean.FALSE);

    public WorldGenBranchingTree(boolean notify, int trunkHeight, int forkLevels, int forksPerBranch) {
        super(notify);
        this.trunkHeight = trunkHeight;
        this.forkLevels = forkLevels;
        this.forksPerBranch = forksPerBranch;
    }

    // ========== TRACKED PLACEMENT ==========

    private void placeBlock(BlockPos pos, IBlockState state) {
        if (!touchedPositions.contains(pos)) {
            currentSnapshots.add(new BlockSnapshot(pos, currentWorld.getBlockState(pos)));
            touchedPositions.add(pos);
        }
        setBlockAndNotifyAdequately(currentWorld, pos, state);
    }

    public List<BlockSnapshot> getSnapshots() {
        return currentSnapshots;
    }

    // ========== MAIN GENERATE ==========

    @Override
    public boolean generate(World world, Random random, BlockPos pos) {
        this.rand = new Random(random.nextLong());
        this.currentWorld = world;
        this.currentSnapshots = new ArrayList<>();
        this.touchedPositions = new HashSet<>();

        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;

        // ================================================
        // 1. TRUNK — single column with slight lean
        // ================================================
        double trunkTopX = x;
        double trunkTopY = y;
        double trunkTopZ = z;

        double leanX = (rand.nextDouble() - 0.5) * 0.1;
        double leanZ = (rand.nextDouble() - 0.5) * 0.1;

        for (int i = 0; i <= trunkHeight; i++) {
            double t = (double) i / trunkHeight;
            double thickness = lerp(1.6, 1.0, t); // taper from base to top

            placeLogAt(new BlockPos(
                    x + leanX * i,
                    y + i,
                    z + leanZ * i
            ), thickness);

            trunkTopX = x + leanX * i;
            trunkTopY = y + i;
            trunkTopZ = z + leanZ * i;
        }

        // ================================================
        // 2. RECURSIVE BRANCHING from trunk top
        //    Direction: mostly upward at first, spreading
        //    outward at each fork level
        // ================================================
        double startAngle = rand.nextDouble() * Math.PI * 2;

        growBranches(
                trunkTopX, trunkTopY, trunkTopZ,
                0.0, 1.0, 0.0,     // initial direction: straight up
                startAngle,
                forkLevels,         // remaining fork levels
                trunkHeight * 0.6,  // initial branch length
                1.4                 // initial thickness
        );

        return true;
    }

    // ========== RECURSIVE BRANCHING ==========

    /**
     * Recursively grows branches.
     *
     * @param x, y, z     start position
     * @param dx, dy, dz  parent direction (normalized)
     * @param baseAngle   rotational offset around the trunk axis
     * @param levelsLeft  remaining fork levels (0 = place leaves, stop)
     * @param length      length of this branch segment
     * @param thickness   thickness of this branch
     */
    private void growBranches(double x, double y, double z,
                              double dx, double dy, double dz,
                              double baseAngle,
                              int levelsLeft, double length, double thickness) {

        if (levelsLeft <= 0) {
            // Terminal: place leaf cluster
            placeLeafCluster(new BlockPos(x, y, z), 3);
            return;
        }

        // How many sub-branches at this fork
        int forks = forksPerBranch + (rand.nextInt(2) - 1); // ±1 variation
        forks = Math.max(2, forks);

        // At the first fork level (from trunk top), spread branches evenly
        // At deeper levels, fork from the parent direction
        double angleStep = (Math.PI * 2) / forks;

        for (int f = 0; f < forks; f++) {
            // Rotation angle around the vertical/parent axis
            double forkAngle = baseAngle + angleStep * f + (rand.nextDouble() - 0.5) * 0.6;

            // Elevation: higher levels are more vertical, lower levels spread out
            // First level: 40-60° from horizontal (mostly up with some spread)
            // Deeper levels: 20-50° from horizontal (more spread)
            double elevMin, elevMax;
            if (levelsLeft == forkLevels) {
                // First fork from trunk
                elevMin = 35;
                elevMax = 65;
            } else {
                // Sub-forks: more horizontal spread
                elevMin = 15;
                elevMax = 50;
            }

            double elevDeg = elevMin + rand.nextDouble() * (elevMax - elevMin);
            double elevRad = Math.toRadians(elevDeg);

            // Build direction vector
            double branchDX = Math.cos(forkAngle) * Math.cos(elevRad);
            double branchDY = Math.sin(elevRad);
            double branchDZ = Math.sin(forkAngle) * Math.cos(elevRad);

            // Normalize
            double mag = Math.sqrt(branchDX * branchDX + branchDY * branchDY + branchDZ * branchDZ);
            branchDX /= mag;
            branchDY /= mag;
            branchDZ /= mag;

            // Branch length with some randomness, shorter at deeper levels
            double branchLen = length * (0.7 + rand.nextDouble() * 0.3);

            // Draw this branch segment
            double endX = x;
            double endY = y;
            double endZ = z;

            int steps = Math.max(1, (int) (branchLen * 2));
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps;

                // Add slight curve: random wander
                double wx = (rand.nextDouble() - 0.5) * 0.15;
                double wz = (rand.nextDouble() - 0.5) * 0.15;

                endX = x + (branchDX + wx) * branchLen * t;
                endY = y + branchDY * branchLen * t;
                endZ = z + (branchDZ + wz) * branchLen * t;

                // Taper thickness along the branch
                double localThick = lerp(thickness, thickness * 0.5, t);

                placeLogAt(new BlockPos(endX, endY, endZ), localThick);
            }

            // Recurse: fork again from the end of this branch
            growBranches(
                    endX, endY, endZ,
                    branchDX, branchDY, branchDZ,
                    forkAngle + (rand.nextDouble() - 0.5) * 0.8,
                    levelsLeft - 1,
                    branchLen * 0.65,   // shorter at each level
                    thickness * 0.6     // thinner at each level
            );
        }
    }

    // ========== DRAWING ==========

    private void placeLogAt(BlockPos pos, double thickness) {
        placeBlock(pos, LOG_STATE);

        if (thickness > 1.2) {
            placeBlock(pos.add(1, 0, 0), LOG_STATE);
            placeBlock(pos.add(-1, 0, 0), LOG_STATE);
            placeBlock(pos.add(0, 0, 1), LOG_STATE);
            placeBlock(pos.add(0, 0, -1), LOG_STATE);
        }
        if (thickness > 1.6) {
            placeBlock(pos.add(1, 0, 1), LOG_STATE);
            placeBlock(pos.add(1, 0, -1), LOG_STATE);
            placeBlock(pos.add(-1, 0, 1), LOG_STATE);
            placeBlock(pos.add(-1, 0, -1), LOG_STATE);
        }
    }

    private void placeLeafCluster(BlockPos center, int r) {
        int r2 = r * r;
        for (int x = -r; x <= r; x++) {
            for (int y = 1; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z <= r2) {
                        BlockPos bp = center.add(x, y, z);
                        if (currentWorld.isAirBlock(bp)) {
                            placeBlock(bp, LEAF_STATE);
                        }
                    }
                }
            }
        }
    }

    private double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}