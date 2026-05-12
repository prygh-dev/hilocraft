package com.example.examplemod;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStoneSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.util.*;

/**
 * /pathpaint              - toggle on/off with current block
 * /pathpaint off          - turn off
 * /pathpaint block <name> - set the block type (e.g. "stone", "grass", "dirt")
 * /pathpaint radius <n>   - set brush radius (default 1)
 * /pathpaint undo         - undo the last painting session
 *
 * While active, replaces the top surface block under the player as they move.
 */
public class CommandPathPaint extends CommandBase {

    private static final Set<UUID> activePainters = new HashSet<>();
    private static final Map<UUID, IBlockState> painterBlocks = new HashMap<>();
    private static final Map<UUID, Integer> painterRadius = new HashMap<>();
    private static final Map<UUID, BlockPos> lastPositions = new HashMap<>();

    // Undo: current session snapshots (while painting) and committed history
    private static final Map<UUID, List<BlockSnapshot>> currentSession = new HashMap<>();
    private static final Map<UUID, Deque<List<BlockSnapshot>>> undoHistory = new HashMap<>();
    private static final Map<UUID, Set<BlockPos>> sessionTouched = new HashMap<>();
    private static final int MAX_UNDO_DEPTH = 5;

    private static boolean listenerRegistered = false;

    private static final IBlockState DEFAULT_BLOCK =
            Blocks.STONE_SLAB.getDefaultState()
                    .withProperty(BlockStoneSlab.VARIANT, BlockStoneSlab.EnumType.NETHERBRICK)
                    .withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM);

    private static class BlockSnapshot {
        final BlockPos pos;
        final IBlockState previousState;

        BlockSnapshot(BlockPos pos, IBlockState previousState) {
            this.pos = pos;
            this.previousState = previousState;
        }
    }

    @Override
    public String getName() {
        return "pathpaint";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/pathpaint [off|undo|block <name>|radius <n>]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayer)) {
            sender.sendMessage(new TextComponentString("Must be a player."));
            return;
        }

        EntityPlayer player = (EntityPlayer) sender;
        UUID uuid = player.getUniqueID();

        if (args.length >= 1) {
            switch (args[0].toLowerCase()) {

                // /pathpaint off
                case "off":
                    stopPainting(uuid);
                    msg(sender, TextFormatting.RED, "Path painting disabled.");
                    return;

                // /pathpaint undo
                case "undo":
                    // If currently painting, commit the session first
                    if (activePainters.contains(uuid)) {
                        stopPainting(uuid);
                        msg(sender, TextFormatting.GRAY, "Painting stopped.");
                    }

                    Deque<List<BlockSnapshot>> stack = undoHistory.get(uuid);
                    if (stack == null || stack.isEmpty()) {
                        msg(sender, TextFormatting.RED, "Nothing to undo.");
                        return;
                    }

                    List<BlockSnapshot> snapshots = stack.removeLast();
                    World world = player.world;
                    for (int i = snapshots.size() - 1; i >= 0; i--) {
                        BlockSnapshot snap = snapshots.get(i);
                        world.setBlockState(snap.pos, snap.previousState, 3);
                    }

                    int remaining = stack.size();
                    msg(sender, TextFormatting.GREEN,
                            "Undone! Restored " + snapshots.size() + " blocks. ("
                                    + remaining + " undo" + (remaining == 1 ? "" : "s") + " remaining)");
                    return;

                // /pathpaint block <name>
                case "block":
                    if (args.length < 2) {
                        throw new WrongUsageException("/pathpaint block <name> — e.g. stone, grass, dirt, cobblestone");
                    }

                    String blockName = args[1];
                    Block block = Block.getBlockFromName(blockName);
                    if (block == null) {
                        // Try with minecraft: prefix
                        block = Block.getBlockFromName("minecraft:" + blockName);
                    }
                    if (block == null) {
                        msg(sender, TextFormatting.RED, "Unknown block: " + blockName);
                        return;
                    }

                    IBlockState state = block.getDefaultState();

                    // If metadata is provided as a third arg, apply it
                    if (args.length >= 3) {
                        try {
                            int meta = Integer.parseInt(args[2]);
                            state = block.getStateFromMeta(meta);
                        } catch (NumberFormatException e) {
                            msg(sender, TextFormatting.RED, "Invalid metadata: " + args[2]);
                            return;
                        }
                    }

                    painterBlocks.put(uuid, state);
                    msg(sender, TextFormatting.GREEN, "Paint block set to: " + block.getRegistryName()
                            + (args.length >= 3 ? ":" + args[2] : ""));
                    return;

                // /pathpaint radius <n>
                case "radius":
                    if (args.length < 2) {
                        throw new WrongUsageException("/pathpaint radius <1-10>");
                    }
                    int r = parseInt(args[1], 1, 10);
                    painterRadius.put(uuid, r);
                    msg(sender, TextFormatting.GREEN, "Brush radius set to " + r + ".");
                    return;

                default:
                    break;
            }
        }

        // Toggle
        if (activePainters.contains(uuid)) {
            stopPainting(uuid);
            msg(sender, TextFormatting.RED, "Path painting disabled.");
        } else {
            activePainters.add(uuid);
            painterBlocks.putIfAbsent(uuid, DEFAULT_BLOCK);
            painterRadius.putIfAbsent(uuid, 1);
            currentSession.put(uuid, new ArrayList<>());
            sessionTouched.put(uuid, new HashSet<>());

            IBlockState paintBlock = painterBlocks.get(uuid);
            String blockInfo = paintBlock.getBlock().getRegistryName().toString();
            int radius = painterRadius.getOrDefault(uuid, 1);

            msg(sender, TextFormatting.GREEN,
                    "Painting with " + blockInfo + " (radius " + radius + "). "
                            + "Run around to paint! /pathpaint off to stop.");

            if (!listenerRegistered) {
                MinecraftForge.EVENT_BUS.register(new TickListener());
                listenerRegistered = true;
            }
        }
    }

    /**
     * Stops painting and commits the current session to undo history.
     */
    private void stopPainting(UUID uuid) {
        activePainters.remove(uuid);
        lastPositions.remove(uuid);

        List<BlockSnapshot> session = currentSession.remove(uuid);
        sessionTouched.remove(uuid);

        if (session != null && !session.isEmpty()) {
            Deque<List<BlockSnapshot>> stack =
                    undoHistory.computeIfAbsent(uuid, k -> new ArrayDeque<>());
            stack.addLast(session);
            while (stack.size() > MAX_UNDO_DEPTH) {
                stack.removeFirst();
            }
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "off", "undo", "block", "radius");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("block")) {
            return getListOfStringsMatchingLastWord(args,
                    Block.REGISTRY.getKeys().stream()
                            .map(r -> r.getResourcePath())
                            .toArray(String[]::new));
        }
        return Collections.emptyList();
    }

    private void msg(ICommandSender sender, TextFormatting color, String text) {
        TextComponentString tc = new TextComponentString(text);
        tc.getStyle().setColor(color);
        sender.sendMessage(tc);
    }

    // ========== TICK LISTENER ==========

    public static class TickListener {

        @SubscribeEvent
        public void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            EntityPlayer player = event.player;
            if (player.world.isRemote) return;

            UUID uuid = player.getUniqueID();
            if (!activePainters.contains(uuid)) return;

            BlockPos feetPos = player.getPosition();
            BlockPos groundPos = feetPos.down();

            BlockPos lastPos = lastPositions.get(uuid);
            if (lastPos != null && lastPos.equals(groundPos)) return;
            lastPositions.put(uuid, groundPos);

            World world = player.world;
            IBlockState paintBlock = painterBlocks.getOrDefault(uuid, DEFAULT_BLOCK);
            int radius = painterRadius.getOrDefault(uuid, 1);

            List<BlockSnapshot> session = currentSession.get(uuid);
            Set<BlockPos> touched = sessionTouched.get(uuid);
            if (session == null || touched == null) return;

            for (int dx = -radius + 1; dx < radius; dx++) {
                for (int dz = -radius + 1; dz < radius; dz++) {
                    if (dx * dx + dz * dz >= radius * radius) continue;

                    BlockPos checkPos = groundPos.add(dx, 0, dz);
                    BlockPos surface = findSurface(world, checkPos);

                    if (surface != null) {
                        // Snapshot only the first time we touch each position
                        if (!touched.contains(surface)) {
                            session.add(new BlockSnapshot(surface, world.getBlockState(surface)));
                            touched.add(surface);
                        }
                        world.setBlockState(surface, paintBlock, 3);
                    }
                }
            }
        }

        private BlockPos findSurface(World world, BlockPos pos) {
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos check = pos.add(0, dy, 0);
                IBlockState state = world.getBlockState(check);
                IBlockState above = world.getBlockState(check.up());

                if (state.isFullBlock() && !above.isFullBlock()) {
                    return check;
                }
            }
            return null;
        }
    }
}