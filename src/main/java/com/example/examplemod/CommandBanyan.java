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

public class CommandBanyan extends CommandBase {

    @Override
    public String getName() {
        return "banyan";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/banyan [height] [radius] [attractors] [startAngle] [endAngle]\n"
                + "/banyan <x> <y> <z> [height] [radius] [attractors] [startAngle] [endAngle]\n"
                + "/banyan undo\n"
                + "Angles: startAngle = near trunk (default 65), endAngle = at tips (default 10)";
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

        // ===== UNDO =====
        if (args.length >= 1 && args[0].equalsIgnoreCase("undo")) {
            int available = WorldGenBanyanSC.undoCount(player.getUniqueID());
            if (available == 0) {
                msg(sender, TextFormatting.RED, "Nothing to undo.");
                return;
            }

            int restored = WorldGenBanyanSC.undo(world, player.getUniqueID());
            if (restored < 0) {
                msg(sender, TextFormatting.RED, "Nothing to undo.");
            } else {
                int remaining = WorldGenBanyanSC.undoCount(player.getUniqueID());
                msg(sender, TextFormatting.GREEN,
                        "Undone! Restored " + restored + " blocks. ("
                                + remaining + " undo" + (remaining == 1 ? "" : "s") + " remaining)");
            }
            return;
        }

        // ===== GENERATE =====
        BlockPos pos = player.getPosition();
        int height = 25;
        int radius = 30;
        int attractors = 600;
        double startAngle = 65;
        double endAngle = 10;

        try {
            if (args.length == 3 || args.length == 5) {
                height     = parseInt(args[0], 5, 200);
                radius     = parseInt(args[1], 5, 100);
                attractors = parseInt(args[2], 50, 5000);
                if (args.length == 5) {
                    startAngle = parseDouble(args[3], 0, 90);
                    endAngle   = parseDouble(args[4], 0, 90);
                }
            } else if (args.length == 6 || args.length == 8) {
                int x = parseInt(args[0]);
                int y = parseInt(args[1]);
                int z = parseInt(args[2]);
                pos = new BlockPos(x, y, z);
                height     = parseInt(args[3], 5, 200);
                radius     = parseInt(args[4], 5, 100);
                attractors = parseInt(args[5], 50, 5000);
                if (args.length == 8) {
                    startAngle = parseDouble(args[6], 0, 90);
                    endAngle   = parseDouble(args[7], 0, 90);
                }
            } else if (args.length != 0) {
                throw new WrongUsageException(getUsage(sender));
            }

            msg(sender, TextFormatting.GRAY,
                    "Generating banyan (h=" + height + " r=" + radius
                            + " a=" + attractors + " angles=" + (int)startAngle + "→" + (int)endAngle + ")...");

            WorldGenBanyanSC generator = new WorldGenBanyanSC(
                    true, height, radius, attractors, startAngle, endAngle);
            boolean success = generator.generate(world, new Random(), pos);

            if (success) {
                // Commit the snapshot for undo
                WorldGenBanyanSC.commitGeneration(
                        player.getUniqueID(),
                        generator.getSnapshots()
                );

                int blocks = generator.getSnapshots().size();
                int undosLeft = WorldGenBanyanSC.undoCount(player.getUniqueID());
                msg(sender, TextFormatting.GREEN,
                        "Banyan tree generated! (" + blocks + " blocks, "
                                + undosLeft + " undo" + (undosLeft == 1 ? "" : "s") + " available)");
            } else {
                msg(sender, TextFormatting.RED, "Generation failed.");
            }
        } catch (NumberInvalidException e) {
            throw new CommandException("Invalid number: " + e.getMessage());
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