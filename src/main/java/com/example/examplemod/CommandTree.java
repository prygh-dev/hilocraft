package com.example.examplemod;

import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * /tree                           - default params
 * /tree <height> <levels> <forks> - custom params
 * /tree undo                      - undo last generation
 *
 * height: trunk height (default 10)
 * levels: fork recursion depth (default 4)
 * forks:  branches per fork point (default 3)
 */
public class CommandTree extends CommandBase {

    @Override
    public String getName() {
        return "tree2";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/tree2 [height] [levels] [forks] OR /tree2 undo";
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
        World world = player.world;
        java.util.UUID uuid = player.getUniqueID();

        // /tree undo
        if (args.length >= 1 && args[0].equalsIgnoreCase("undo")) {
            int restored = WorldGenBranchingTree.undo(world, uuid);
            if (restored < 0) {
                msg(sender, TextFormatting.RED, "Nothing to undo.");
            } else {
                int remaining = WorldGenBranchingTree.undoCount(uuid);
                msg(sender, TextFormatting.GREEN,
                        "Undone! Restored " + restored + " blocks. ("
                                + remaining + " undo" + (remaining == 1 ? "" : "s") + " remaining)");
            }
            return;
        }

        // Parse params
        BlockPos pos = player.getPosition();
        int height = 10;
        int levels = 4;
        int forks = 3;

        try {
            if (args.length >= 1) height = parseInt(args[0], 3, 100);
            if (args.length >= 2) levels = parseInt(args[1], 1, 6);
            if (args.length >= 3) forks = parseInt(args[2], 2, 5);
        } catch (NumberInvalidException e) {
            throw new CommandException("Invalid number: " + e.getMessage());
        }

        msg(sender, TextFormatting.GRAY,
                "Generating tree (h=" + height + " levels=" + levels + " forks=" + forks + ")...");

        WorldGenBranchingTree generator = new WorldGenBranchingTree(true, height, levels, forks);
        boolean success = generator.generate(world, new Random(), pos);

        if (success) {
            WorldGenBranchingTree.commitGeneration(uuid, generator.getSnapshots());
            int blocks = generator.getSnapshots().size();
            int undosLeft = WorldGenBranchingTree.undoCount(uuid);
            msg(sender, TextFormatting.GREEN,
                    "Tree generated! (" + blocks + " blocks, "
                            + undosLeft + " undo" + (undosLeft == 1 ? "" : "s") + " available)");
        } else {
            msg(sender, TextFormatting.RED, "Generation failed.");
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "undo");
        }
        return Collections.emptyList();
    }

    private void msg(ICommandSender sender, TextFormatting color, String text) {
        TextComponentString tc = new TextComponentString(text);
        tc.getStyle().setColor(color);
        sender.sendMessage(tc);
    }
}