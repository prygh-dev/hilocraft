package com.example.examplemod;

import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockOldLeaf;
import net.minecraft.block.BlockOldLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockVine;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenAbstractTree;

import java.util.*;

/**
 * Banyan tree generator v3 for Forge 1.12.2.
 *
 * Key design:
 *  - Trunk is a bundle of thin twisted strands, not a solid column
 *  - Major branches are explicitly placed paths with clear separation
 *  - Space colonization used only for fine canopy branching
 *  - Pillar roots are prominent and clearly visible
 */
public class WorldGenBanyanSC extends WorldGenAbstractTree {

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

    // ========== TYPES ==========

    /**
     * A single point along a branch path.
     * Used for both explicit branches and space-colonization nodes.
     */
    private static class BranchNode {
        double x, y, z;
        BranchNode parent;
        double thickness;
        boolean isTip = false;

        BranchNode(double x, double y, double z, BranchNode parent, double thickness) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.thickness = thickness;
        }
    }

    private static class Attractor {
        double x, y, z;

        Attractor(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    // ========== PARAMETERS ==========

    private final int height;
    private final int canopyRadius;
    private final int attractorCount;
    private final double startElev; // branch angle near trunk (degrees from horizontal)
    private final double endElev;   // branch angle at tips (degrees from horizontal)

    private Random rand;
    private double originX, originZ;
    private int groundY;

    private static final IBlockState LOG_STATE =
            Blocks.LOG.getDefaultState()
                    .withProperty(BlockOldLog.VARIANT, BlockPlanks.EnumType.JUNGLE)
                    .withProperty(BlockLog.LOG_AXIS, BlockLog.EnumAxis.NONE);

    private static final IBlockState LEAF_STATE =
            Blocks.LEAVES.getDefaultState()
                    .withProperty(BlockOldLeaf.VARIANT, BlockPlanks.EnumType.JUNGLE)
                    .withProperty(BlockOldLeaf.CHECK_DECAY, Boolean.FALSE)
                    .withProperty(BlockOldLeaf.DECAYABLE, Boolean.FALSE);

    public WorldGenBanyanSC(boolean notify, int height, int canopyRadius, int attractorCount,
                            double startElev, double endElev) {
        super(notify);
        this.height = height;
        this.canopyRadius = canopyRadius;
        this.attractorCount = attractorCount;
        this.startElev = startElev;
        this.endElev = endElev;
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
        this.originX = pos.getX() + 0.5;
        this.originZ = pos.getZ() + 0.5;
        this.groundY = pos.getY();

        List<BranchNode> allNodes = new ArrayList<>();

        int trunkHeight = Math.max(4, height / 3);

        // ================================================
        // 1. MULTI-STRAND TRUNK
        //    5-8 thin columns arranged in a ring, each
        //    with slight helical twist. Creates the
        //    "bundle of aerial roots" look with gaps.
        // ================================================
        int strandCount = 5 + rand.nextInt(4); // 5-8
        double trunkRadius = 1.5 + height * 0.06; // ring radius scales with tree size
        List<BranchNode> strandTops = new ArrayList<>();

        for (int s = 0; s < strandCount; s++) {
            double baseAngle = (Math.PI * 2 * s) / strandCount;

            // Each strand spirals slightly as it goes up
            double twistRate = (rand.nextDouble() - 0.5) * 0.08;
            // Each strand leans slightly inward or outward
            double radialDrift = (rand.nextDouble() - 0.5) * 0.03;

            BranchNode prev = null;

            for (int y = 0; y <= trunkHeight; y++) {
                double angle = baseAngle + twistRate * y;
                double r = trunkRadius + radialDrift * y;

                // Strands converge slightly toward the top
                double convergeFactor = 1.0 - 0.3 * ((double) y / trunkHeight);
                r *= convergeFactor;

                double sx = originX + Math.cos(angle) * r;
                double sz = originZ + Math.sin(angle) * r;
                double sy = groundY + y;

                BranchNode node = new BranchNode(sx, sy, sz, prev, 1.0);
                allNodes.add(node);
                prev = node;
            }

            strandTops.add(prev);

            // Add occasional cross-connections between adjacent strands
            // (makes the trunk look fused in places)
            if (s > 0 && rand.nextDouble() < 0.4) {
                int connectY = 2 + rand.nextInt(Math.max(1, trunkHeight - 3));
                // We'll add horizontal bridges after all strands are built
            }
        }

        // Horizontal bridges between strands at a few heights
        for (int bridgeAttempt = 0; bridgeAttempt < strandCount / 2; bridgeAttempt++) {
            int by = 1 + rand.nextInt(Math.max(1, trunkHeight - 1));

            // Find two strands and connect them at this height
            int s1 = rand.nextInt(strandCount);
            int s2 = (s1 + 1) % strandCount;

            double angle1 = (Math.PI * 2 * s1) / strandCount;
            double angle2 = (Math.PI * 2 * s2) / strandCount;
            double convergeFactor = 1.0 - 0.3 * ((double) by / trunkHeight);
            double r = trunkRadius * convergeFactor;

            double x1 = originX + Math.cos(angle1) * r;
            double z1 = originZ + Math.sin(angle1) * r;
            double x2 = originX + Math.cos(angle2) * r;
            double z2 = originZ + Math.sin(angle2) * r;

            // Place blocks along the bridge
            int steps = (int) (Math.sqrt((x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1)) * 2);
            steps = Math.max(1, steps);
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                placeBlock(new BlockPos(
                        lerp(x1, x2, t),
                        groundY + by,
                        lerp(z1, z2, t)
                ), LOG_STATE);
            }
        }

        // Fill the very base with a solid ring for stability
        for (int dx = -(int) trunkRadius - 1; dx <= (int) trunkRadius + 1; dx++) {
            for (int dz = -(int) trunkRadius - 1; dz <= (int) trunkRadius + 1; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= trunkRadius + 0.5 && dist >= trunkRadius - 1.5) {
                    placeBlock(new BlockPos(originX + dx, groundY, originZ + dz), LOG_STATE);
                }
            }
        }

        // ================================================
        // 2. MAJOR BRANCHES
        //    3-5 primary branches emerge from strand tops.
        //    Each goes mostly upward with some outward lean.
        //    They're clearly separated with air between them.
        // ================================================
        int majorBranchCount = 3 + rand.nextInt(3); // 3-5
        double branchAngleStep = (Math.PI * 2) / majorBranchCount;
        double branchStartAngle = rand.nextDouble() * Math.PI * 2;

        List<BranchNode> majorBranchTips = new ArrayList<>();

        for (int b = 0; b < majorBranchCount; b++) {
            double angle = branchStartAngle + branchAngleStep * b
                    + (rand.nextDouble() - 0.5) * 0.5;

            // Pick a strand top to branch from (or close to one)
            BranchNode branchBase = strandTops.get(b % strandTops.size());

            // Major branch: starts steep, gradually flattens
            // startElev = angle near trunk (vertical), endElev = angle at tips (horizontal)
            double branchStartElev = startElev + rand.nextDouble() * 15;
            double branchEndElev   = endElev   + rand.nextDouble() * 15;

            int branchLen = 4 + rand.nextInt(height / 3);
            double thickness = 1.8;
            BranchNode prev = branchBase;

            for (int i = 0; i < branchLen; i++) {
                // Interpolate elevation angle along the branch
                double t = (double) i / branchLen;
                double elevDeg = lerp(branchStartElev, branchEndElev, t);
                double elevRad = Math.toRadians(elevDeg);

                double cosElev = Math.cos(elevRad);
                double sinElev = Math.sin(elevRad);

                double dx = Math.cos(angle) * cosElev * 1.3;
                double dy = sinElev * 1.3;
                double dz = Math.sin(angle) * cosElev * 1.3;

                // Random wander for organic feel
                dx += (rand.nextDouble() - 0.5) * 0.4;
                dy += (rand.nextDouble() - 0.5) * 0.2;
                dz += (rand.nextDouble() - 0.5) * 0.4;

                // Taper thickness
                double localThick = lerp(thickness, 1.0, t);

                BranchNode node = new BranchNode(
                        prev.x + dx, prev.y + dy, prev.z + dz,
                        prev, localThick
                );
                allNodes.add(node);
                prev = node;

                // Spawn sub-branches partway along
                if (i > branchLen / 3 && rand.nextDouble() < 0.35) {
                    BranchNode subTip = growSubBranch(prev, angle, allNodes);
                    if (subTip != null) {
                        majorBranchTips.add(subTip);
                    }
                }
            }

            prev.isTip = true;
            majorBranchTips.add(prev);
        }

        // ================================================
        // 3. FINE CANOPY BRANCHING (Space Colonization)
        //    Attractors fill a hemispherical dome:
        //    tallest at center, curving down at the edges,
        //    like a real banyan canopy.
        // ================================================
        List<BranchNode> scNodes = new ArrayList<>(majorBranchTips);
        List<Attractor> attractors = new ArrayList<>();

        // Find the average height of branch tips as the dome base
        double avgTipY = 0;
        for (BranchNode tip : majorBranchTips) {
            avgTipY += tip.y;
        }
        avgTipY /= majorBranchTips.size();

        // Dome parameters
        double domeBaseY = avgTipY - 2;            // bottom of canopy
        double domeHeight = canopyRadius * 0.6;    // dome is ~60% as tall as it is wide

        int fineAttractors = attractorCount / 3;
        for (int i = 0; i < fineAttractors; i++) {
            double angle = rand.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(rand.nextDouble()) * canopyRadius;

            double ax = originX + Math.cos(angle) * r;
            double az = originZ + Math.sin(angle) * r;

            // Hemisphere curve: max height at center, zero at edge
            double normalizedR = r / canopyRadius; // 0 at center, 1 at edge
            double domeCeiling = domeHeight * Math.sqrt(Math.max(0, 1.0 - normalizedR * normalizedR));

            // Place attractor randomly within the dome shell at this radius
            double ay = domeBaseY + rand.nextDouble() * domeCeiling;

            attractors.add(new Attractor(ax, ay, az));
        }

        // Run space colonization from branch tips only
        double scInfluence = 10.0;
        double scKill = 3.0;     // larger = sparser branching
        double scStep = 1.8;     // larger = longer segments

        for (int iter = 0; iter < 150 && !attractors.isEmpty(); iter++) {
            Map<BranchNode, double[]> growthDirs = new HashMap<>();

            for (Attractor a : attractors) {
                BranchNode closest = null;
                double minDist = Double.MAX_VALUE;

                for (BranchNode n : scNodes) {
                    double d = dist3(a.x, a.y, a.z, n.x, n.y, n.z);
                    if (d < minDist && d < scInfluence) {
                        minDist = d;
                        closest = n;
                    }
                }

                if (closest != null) {
                    double[] dir = growthDirs.computeIfAbsent(closest, k -> new double[3]);
                    double dx = a.x - closest.x;
                    double dy = a.y - closest.y;
                    double dz = a.z - closest.z;
                    double mag = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (mag > 0) {
                        dir[0] += dx / mag;
                        dir[1] += dy / mag;
                        dir[2] += dz / mag;
                    }
                }
            }

            List<BranchNode> newNodes = new ArrayList<>();
            for (Map.Entry<BranchNode, double[]> entry : growthDirs.entrySet()) {
                BranchNode n = entry.getKey();
                double[] dir = entry.getValue();

                double mag = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
                if (mag < 1e-6) continue;

                BranchNode node = new BranchNode(
                        n.x + (dir[0] / mag) * scStep,
                        n.y + (dir[1] / mag) * scStep,
                        n.z + (dir[2] / mag) * scStep,
                        n, 0.7
                );
                newNodes.add(node);
            }

            scNodes.addAll(newNodes);
            allNodes.addAll(newNodes);

            attractors.removeIf(a -> {
                for (BranchNode n : newNodes) {
                    if (dist3(a.x, a.y, a.z, n.x, n.y, n.z) < scKill) return true;
                }
                return false;
            });
        }

        // ================================================
        // 4. RENDER ALL BRANCHES
        //    Draw lines between each node and its parent.
        //    Thin branches = single block. No giant blobs.
        // ================================================
        for (BranchNode n : allNodes) {
            if (n.parent != null) {
                drawBranch(n, n.parent, n.thickness);
            }
        }

        // ================================================
        // 5. LEAVES at tips
        // ================================================
        Set<BranchNode> parentSet = new HashSet<>();
        for (BranchNode n : allNodes) {
            if (n.parent != null) parentSet.add(n.parent);
        }

        // Only place leaves on tips that are in the canopy zone
        double canopyFloor = groundY + trunkHeight + 3;

        for (BranchNode n : allNodes) {
            if (!parentSet.contains(n) && n.y >= canopyFloor) {
                placeLeafCluster(new BlockPos(n.x, n.y, n.z), 3);
            }
        }

        // ================================================
        // 6. PILLAR ROOTS (log blocks)
        //    Thick structural roots that drop from major
        //    branches near the trunk down to the ground.
        //    These form the characteristic secondary trunks.
        // ================================================
        for (BranchNode n : allNodes) {
            if (n.y < groundY + trunkHeight) continue;
            if (n.thickness < 0.9) continue;

            double distFromCenter = Math.sqrt(
                    (n.x - originX) * (n.x - originX) +
                            (n.z - originZ) * (n.z - originZ)
            );

            // Pillar roots spawn close to the trunk (within ~2x trunk radius)
            // Probability decreases with distance from center
            double maxPillarDist = trunkRadius * 3 + 5;
            if (distFromCenter > maxPillarDist) continue;

            // Higher chance near trunk, lower further out
            double chance = 0.08 * (1.0 - distFromCenter / maxPillarDist);
            if (rand.nextDouble() > chance) continue;

            growPillarRoot(n.x, n.y, n.z);
        }

        // ================================================
        // 7. HANGING VINES (vine blocks)
        //    Thin aerial roots on outer branches, creating
        //    the curtain/drape effect. Uses Minecraft vine
        //    blocks, not log blocks.
        // ================================================
        for (BranchNode n : allNodes) {
            if (n.y < groundY + trunkHeight) continue;

            double distFromCenter = Math.sqrt(
                    (n.x - originX) * (n.x - originX) +
                            (n.z - originZ) * (n.z - originZ)
            );

            // Vines only on outer branches
            if (distFromCenter < canopyRadius * 0.3) continue;

            // Probability increases toward the edges
            double chance = 0.06 * (distFromCenter / canopyRadius);
            if (rand.nextDouble() > chance) continue;

            int vineLength = 4 + rand.nextInt(12);
            placeHangingVines(new BlockPos(n.x, n.y, n.z), vineLength);
        }

        // ================================================
        // 8. BUTTRESS ROOTS at base
        // ================================================
        int buttressCount = 3 + rand.nextInt(3);
        double buttressAngleStep = (Math.PI * 2) / buttressCount;
        double buttressStart = rand.nextDouble() * Math.PI * 2;

        for (int b = 0; b < buttressCount; b++) {
            double angle = buttressStart + buttressAngleStep * b;
            double reach = 2.0 + rand.nextDouble() * 2.5;
            int bh = 2 + rand.nextInt(3);

            for (int y = 0; y < bh; y++) {
                double progress = (double) y / bh;
                double curReach = reach * (1.0 - progress) + trunkRadius;
                int steps = (int) (curReach * 2);
                for (int s = 0; s <= steps; s++) {
                    double t = s / (double) Math.max(1, steps);
                    if (t * curReach < trunkRadius - 1) continue; // skip interior
                    double bx = originX + Math.cos(angle) * curReach * t;
                    double bz = originZ + Math.sin(angle) * curReach * t;
                    placeBlock(new BlockPos(bx, groundY + y, bz), LOG_STATE);
                }
            }
        }

        return true;
    }

    // ========== SUB-BRANCH GROWTH ==========

    /**
     * Grows a sub-branch off a major branch at a wider angle.
     * Returns the tip node.
     */
    private BranchNode growSubBranch(BranchNode base, double parentAngle,
                                     List<BranchNode> allNodes) {
        // Sub-branches splay outward more than the parent
        double angle = parentAngle + (rand.nextDouble() - 0.5) * 1.5;
        double elevDeg = 30 + rand.nextDouble() * 40; // 30-70° from horizontal
        double elevRad = Math.toRadians(elevDeg);

        int len = 3 + rand.nextInt(4);
        BranchNode prev = base;

        for (int i = 0; i < len; i++) {
            double dx = Math.cos(angle) * Math.cos(elevRad) * 1.2;
            double dy = Math.sin(elevRad) * 1.2;
            double dz = Math.sin(angle) * Math.cos(elevRad) * 1.2;

            dx += (rand.nextDouble() - 0.5) * 0.3;
            dz += (rand.nextDouble() - 0.5) * 0.3;

            BranchNode node = new BranchNode(
                    prev.x + dx, prev.y + dy, prev.z + dz,
                    prev, 0.8
            );
            allNodes.add(node);
            prev = node;
        }

        prev.isTip = true;
        return prev;
    }

    // ========== PILLAR ROOTS ==========

    private void growPillarRoot(double startX, double startY, double startZ) {
        double x = startX;
        double y = startY;
        double z = startZ;

        // Slight outward drift
        double radX = x - originX;
        double radZ = z - originZ;
        double radMag = Math.sqrt(radX * radX + radZ * radZ);

        double dx = (radMag > 0.1) ? (radX / radMag) * 0.1 : 0;
        double dz = (radMag > 0.1) ? (radZ / radMag) * 0.1 : 0;
        double dy = -1.0;

        while (y > groundY - 1) {
            double mag = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (mag < 1e-6) break;

            x += dx / mag;
            y += dy / mag;
            z += dz / mag;

            BlockPos bp = new BlockPos(x, y, z);

            if (!currentWorld.isAirBlock(bp) && !isLeaf(bp)) {
                // Hit ground — small trunk upward
                for (int i = 0; i < 2 + rand.nextInt(4); i++) {
                    BlockPos up = bp.up(i);
                    if (currentWorld.isAirBlock(up) || isLeaf(up)) {
                        placeBlock(up, LOG_STATE);
                    }
                }
                break;
            }

            placeBlock(bp, LOG_STATE);

            dy -= 0.02;
            dx += (rand.nextDouble() - 0.5) * 0.03;
            dz += (rand.nextDouble() - 0.5) * 0.03;
        }
    }

    // ========== HANGING VINES ==========

    /**
     * Places a column of Minecraft vine blocks hanging down from a branch.
     * The top vine attaches to the side of a solid block (log/leaf),
     * and subsequent vine blocks hang from the one above.
     */
    private void placeHangingVines(BlockPos branchPos, int maxLength) {
        // Try each horizontal direction to find a side to attach to
        for (EnumFacing face : EnumFacing.Plane.HORIZONTAL) {
            BlockPos sidePos = branchPos.offset(face);

            // The vine goes in the air block to the side of the branch
            if (!currentWorld.isAirBlock(sidePos)) continue;

            // The vine is attached back toward the branch
            // (from the vine's perspective, the solid block is in the opposite direction)
            IBlockState vineState = Blocks.VINE.getDefaultState()
                    .withProperty(BlockVine.getPropertyFor(face.getOpposite()), true);

            // Place vine column going down
            for (int i = 0; i < maxLength; i++) {
                BlockPos vinePos = sidePos.down(i);
                if (!currentWorld.isAirBlock(vinePos)) break;
                placeBlock(vinePos, vineState);
            }

            break; // only place on one side
        }
    }

    // ========== DRAWING ==========

    private void drawBranch(BranchNode a, BranchNode b, double thickness) {
        double d = dist3(a.x, a.y, a.z, b.x, b.y, b.z);
        if (d < 0.01) {
            placeLogAt(new BlockPos(a.x, a.y, a.z), thickness);
            return;
        }

        int steps = Math.max(1, (int) (d * 2));

        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double x = lerp(a.x, b.x, t);
            double y = lerp(a.y, b.y, t);
            double z = lerp(a.z, b.z, t);

            placeLogAt(new BlockPos(x, y, z), thickness);
        }
    }

    /**
     * Places log blocks. For thickness <= 1.2, just a single block.
     * For thicker values, a small cross or circle pattern.
     * Never creates giant spheres.
     */
    private void placeLogAt(BlockPos pos, double thickness) {
        placeBlock(pos, LOG_STATE);

        if (thickness > 1.2) {
            // Small cross pattern for slightly thick branches
            placeBlock(pos.add(1, 0, 0), LOG_STATE);
            placeBlock(pos.add(-1, 0, 0), LOG_STATE);
            placeBlock(pos.add(0, 0, 1), LOG_STATE);
            placeBlock(pos.add(0, 0, -1), LOG_STATE);
        }
        if (thickness > 1.6) {
            // Fill to a rough 3x3
            placeBlock(pos.add(1, 0, 1), LOG_STATE);
            placeBlock(pos.add(1, 0, -1), LOG_STATE);
            placeBlock(pos.add(-1, 0, 1), LOG_STATE);
            placeBlock(pos.add(-1, 0, -1), LOG_STATE);
        }
    }

    private void placeLeafCluster(BlockPos center, int r) {
        int r2 = r * r;
        for (int x = -r; x <= r; x++) {
            // Only place leaves ABOVE the branch (y >= 1)
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

    // ========== HELPERS ==========

    private boolean isLeaf(BlockPos pos) {
        return currentWorld.getBlockState(pos).getBlock().isLeaves(
                currentWorld.getBlockState(pos), currentWorld, pos);
    }

    private double dist3(double x1, double y1, double z1,
                         double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}