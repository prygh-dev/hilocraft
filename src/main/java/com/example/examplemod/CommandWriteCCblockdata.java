package com.example.examplemod;

import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

public class CommandWriteCCblockdata extends CommandBase {

    // The currently selected cube, set by /wccbd select
    private ICube currentCube = null;
    // The cube provider, grabbed once per select
    private CubeProviderServer cubeProvider = null;

    private WorldServer world = null;
    private ICubicWorldServer cubicWorld;

    @Override
    public String getName() {
        return "wccbd";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/wccbd <select|setblock|save>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // op level
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        if (args.length == 0) {
            throw new CommandException("Usage: " + getUsage(sender));
        }

        switch (args[0].toLowerCase()) {

            case "select": {
                // /wccbd select <cubeX> <cubeY> <cubeZ>
                if (args.length != 4) {
                    throw new CommandException("Usage: /wccbd select <cubeX> <cubeY> <cubeZ>");
                }
                int cubeX = parseInt(args[1]);
                int cubeY = parseInt(args[2]);
                int cubeZ = parseInt(args[3]);

                if(world == null) {
                    world = server.getWorld(0); // dimension 0 = overworld
                    cubicWorld = (ICubicWorldServer) world;
                }

                cubeProvider = (CubeProviderServer) cubicWorld.getCubeCache();

                // getCube loads or creates the cube at these cube coordinates.
                // This does touch disk but does NOT require the player to be nearby.
                // CAUTION: verify exact method signature against your CC version —
                // it may require a Requirement enum arg like Requirement.LOAD_OR_GENERATE
                currentCube = cubeProvider.getCube(cubeX, cubeY, cubeZ);

                notifyCommandListener(sender, this,
                        "Selected cube (" + cubeX + ", " + cubeY + ", " + cubeZ + ")");
                break;
            }

            case "setblock": {
                // /wccbd setblock <localX> <localY> <localZ> <block_id> [meta]
                if (args.length < 5) {
                    throw new CommandException(
                            "Usage: /wccbd setblock <localX> <localY> <localZ> <block_id> [meta]");
                }
                if (currentCube == null) {
                    throw new CommandException("No cube selected. Run /wccbd select first.");
                }

                int localX = parseInt(args[1]);
                int localY = parseInt(args[2]);
                int localZ = parseInt(args[3]);
                String blockId = args[4];
                int meta = args.length >= 6 ? parseInt(args[5]) : 0;

                if (localX < 0 || localX > 15 || localY < 0 || localY > 15
                        || localZ < 0 || localZ > 15) {
                    throw new CommandException(
                            "Local coordinates must be 0-15. Got: "
                                    + localX + " " + localY + " " + localZ);
                }

                Block block = Block.getBlockFromName(blockId);
                if (block == null) {
                    throw new CommandException("Unknown block: " + blockId);
                }

                IBlockState state = block.getStateFromMeta(meta);

                // Compute the absolute BlockPos from the cube's position + local offsets
                // ICube.getCoords() returns the cube's base BlockPos (min corner)
                // CAUTION: verify getCoords() or getX()/getY()/getZ() against your CC version
                BlockPos cubeBase = currentCube.getCoords().getMinBlockPos();
                BlockPos targetPos = cubeBase.add(localX, localY, localZ);

                // setBlockState returns the previous state (or null if unchanged)
                currentCube.setBlockState(targetPos, state);

                /*notifyCommandListener(sender, this,
                        "Set block " + blockId + " at local ("
                                + localX + ", " + localY + ", " + localZ + ")");
                */
                break;

            }
            case "save": {
                // /wccbd save
                if (currentCube == null) {
                    throw new CommandException("No cube selected. Run /wccbd select first.");
                }
                if (cubeProvider == null) {
                    throw new CommandException("Cube provider not initialized.");
                }

                // Writes the cube NBT directly to the .3dr file without requiring
                // the chunk to be loaded in the normal game sense.
                // CAUTION: verify exact method name — may be saveCube() or saveChunk()
                //cubeProvider.saveCube(currentCube);
                cubeProvider.saveChunks(true);
                //cubicWorld.unloadOldCubes();

                notifyCommandListener(sender, this, "Cube saved to disk.");
                break;
            }
            case "setbatch": {
                // /wccbd setbatch lx1,ly1,lz1,blockname1;lx2,ly2,lz2,blockname2;...
                if (args.length != 2) {
                    throw new CommandException("Usage: /wccbd setbatch <encoded_blocks>");
                }
                if (currentCube == null) {
                    throw new CommandException("No cube selected. Run /wccbd select first.");
                }

                String[] entries = args[1].split(";");
                int placed = 0;

                for (String entry : entries) {
                    String[] parts = entry.split(",");
                    if (parts.length < 4) continue;

                    int lx = Integer.parseInt(parts[0]);
                    int ly = Integer.parseInt(parts[1]);
                    int lz = Integer.parseInt(parts[2]);
                    String blockId = parts[3];
                    int meta = parts.length >= 5 ? Integer.parseInt(parts[4]) : 0;

                    if (lx < 0 || lx > 15 || ly < 0 || ly > 15 || lz < 0 || lz > 15) continue;

                    Block block = Block.getBlockFromName(blockId);
                    if (block == null) continue;

                    IBlockState state = block.getStateFromMeta(meta);
                    BlockPos cubeBase = currentCube.getCoords().getMinBlockPos();
                    BlockPos targetPos = cubeBase.add(lx, ly, lz);
                    currentCube.setBlockState(targetPos, state);
                    placed++;
                }

                notifyCommandListener(sender, this, "Placed " + placed + " blocks in batch.");
                break;
            }

            default:
                throw new CommandException("Unknown subcommand: " + args[0]
                        + ". Use select, setblock, or save.");
        }
    }
}
