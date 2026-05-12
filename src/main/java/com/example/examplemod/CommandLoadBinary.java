package com.example.examplemod;

import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStoneSlab;

import java.io.*;
import java.util.*;

public class CommandLoadBinary extends CommandBase {

    private static final int MAGIC = 0x4C494441;

    private HashMap<String, HashMap<Integer, CubicChunkColumn>> cube_cache = new HashMap<>();

    private double check_gl_return_0_if_none(String chunkkey, int localxzkey) {
        if(cube_cache.containsKey(chunkkey)) {
            HashMap<Integer, CubicChunkColumn> chunkinf = cube_cache.get(chunkkey);
            if(chunkinf.containsKey(localxzkey)) {
                return chunkinf.get(localxzkey).ground_level;
            }
        }
        return 0;
    }

    private int unsavedCubes = 0;
    private static final int SAVE_INTERVAL = 14;

    final int yOffset = 3; //used to be 4. Sea level was off by a block so changed it to 3

    double minX = Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;


    WorldServer world;
    ICubicWorldServer cubicWorld;
    CubeProviderServer cubeProvider;


    @Override public String getName() { return "loadbinary"; }
    @Override public int getRequiredPermissionLevel() { return 2; }
    @Override public String getUsage(ICommandSender sender) {
        return "/loadbinary <filepath>";
    }

    private static double roundToHalf(double v) {
        return Math.round(v * 2.0) / 2.0;
    }



    class CubicChunkColumn {
        HashMap<Integer, ArrayList<MC_Coord>> col_blocks;
        double ground_level;

        public CubicChunkColumn() {
            this.col_blocks = new HashMap<>();
            //blocks belonging to the same cubic chunk go in here.
            //HashMap of cubicY -> list of blocks
            //get smallest cubicY in list, add blocks in this list to the to-gen list
                //then add the blocks in the same cubicY from the other XZ coords in this cubicXZ
                    //finally, call set block on the complete list
        }
        void addBlock(MC_Coord b, boolean is_ground) {
            ArrayList<MC_Coord> colblocks = this.col_blocks.computeIfAbsent(b.mcY >> 4, k -> new ArrayList<>());
            if(colblocks.contains(b)) {
                return;
            }
            colblocks.add(b);
            if(is_ground) {
                this.ground_level = b.mcY;
            }
        }
        boolean has_y_level(Integer y_chunk) {
            return col_blocks.containsKey(y_chunk);
        }
        ArrayList<MC_Coord> pull_y_level(Integer y_chunk) {
            return col_blocks.remove(y_chunk);
        }
        Integer getLowestYlevel() {
            if(this.col_blocks.isEmpty()) { return null; }
            return Collections.min(this.col_blocks.keySet());
        }

    }

    class MC_Coord {
        int mcX, mcZ, mcY;
        String blockName;
        String block_variant = "";
        boolean above_ground;
        double height_above_ground;
        boolean is_street;
        boolean is_building;
        boolean is_slab;
        double dtm;

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(other == null || getClass() != other.getClass()) return false;
            MC_Coord othercoord = (MC_Coord) other;

            return this.mcX == othercoord.mcX && this.mcZ == othercoord.mcZ && this.mcY == othercoord.mcY;
        }
        @Override
        public int hashCode() {
            return Objects.hash(mcX, mcY, mcZ);
        }

        public MC_Coord(int mcX, int mcZ, int mcY) {
            //...
            this.mcX = mcX;
            this.mcZ = mcZ;
            this.mcY = mcY;
        }

        public MC_Coord(double x, double y, double dtm, double h, boolean top_h, boolean is_street, boolean is_building) {

            this.above_ground = roundToHalf(h - dtm) > 0;
            this.height_above_ground = roundToHalf(h - dtm);
            this.is_street = is_street;
            this.is_building = is_building;
            this.dtm = dtm;

            this.mcX = (int) Math.round((x - minX) * 2.0);
            this.mcZ = (int) Math.round((maxY - y) * 2.0);

            if(top_h && this.is_building || top_h && is_street || top_h && this.height_above_ground >= 3.0) {
            //if(top_h && (this.is_building || this.is_street)) {
                double slabY = roundToHalf(h * 2.0) + yOffset; //0.625 -> 1.25 -> 1.5
                this.mcY = (int) Math.ceil(slabY); //slab at 2.0
                this.is_slab = Math.abs(slabY % 1.0 - 0.5) < 0.001;
            }
            else {
                if((h*2.0) % 1.0 < 0.125) {
                    this.mcY = (int) Math.round(h * 2.0) + yOffset;
                } else {
                    this.mcY = (int) Math.ceil(h * 2.0) + yOffset;  //0.125 -> 0.25 -> 1.0
                }
                this.is_slab = false;
            }

            if(this.mcY <= yOffset) {
                this.blockName = "minecraft:water";
                this.is_slab = false;
            }
            else if(!this.above_ground && !this.is_street) {
                this.blockName = "minecraft:grass";
                this.is_slab = false;
            }
            else if(this.is_street && !this.is_building) {
                if(this.is_slab) {
                    this.blockName = "minecraft:stone_slab";
                    this.block_variant = "netherbrick";
                }
                else {
                    this.blockName = "minecraft:stone";
                }
            }
            else if(this.is_building) {
                if(this.is_slab) {
                    this.blockName = "minecraft:stone_slab";
                }
                else {
                    this.blockName = "minecraft:double_stone_slab";
                }
            }
            else {
                this.blockName = "minecraft:grass";
                this.is_slab = false;
            }

            /*
            this.blockName = this.mcY <= yOffset ? "minecraft:water"
                    : !this.above_ground && !this.is_street? "minecraft:grass"
                    : (this.is_street && !this.is_building) && this.is_slab ? "minecraft:stone_slab"
                    : (this.is_street || this.is_building) ? "minecraft:double_stone_slab"
                    : (this.height_above_ground > 0.0 && this.is_slab) ? "minecraft:stone_slab"
                    : (this.height_above_ground > 0.0) ? "minecraft:double_stone_slab"
                    : "minecraft:grass";

             */
        }

        void setBlockName(String blockName) {
            this.blockName = blockName;
        }
    }


    private static String packKey(int x, int z) {
        return x + "," + z;
    }
    private static int getLocalXZkey(int local_x, int local_z) { return local_x * 16 + local_z; }


    int n = 0;

    private void flushCube(String key, MinecraftServer server, ICommandSender sender, int row, int rowCount) {

        HashMap<Integer, CubicChunkColumn> xzMap = cube_cache.get(key);

        // Parse cube coords back from the key
        String[] parts = key.split(",");
        int cubeX = Integer.parseInt(parts[0]);
        int cubeZ = Integer.parseInt(parts[1]);

        for(Integer localXZ : xzMap.keySet()) {

            //get cubicY of ground level -> smallest key in list
            CubicChunkColumn col = xzMap.get(localXZ);

            Integer lowest_ycube_key;
            while((lowest_ycube_key = col.getLowestYlevel()) != null) {
                //pull it out
                ArrayList<MC_Coord> next_to_set = col.pull_y_level(lowest_ycube_key);
                for(Integer localXZ2 : xzMap.keySet()) {
                    if(localXZ2.equals(localXZ)) continue;
                    CubicChunkColumn col2 = xzMap.get(localXZ2);
                    if (col2.has_y_level(lowest_ycube_key)) {
                        next_to_set.addAll(col2.pull_y_level(lowest_ycube_key));
                    }
                }
                //at this point, next_to_set should have all the blocks in this cubic chunk
                //also, those blocks were removed from the hashmap
                //However, ground_level of each column is still set, because we'll need that later to fill in the steep slope side-view holes
                //we'll do that by doing a second pass, checking neighbor-block dtm values

                //TODO: select chunk and set blocks here...
                ICube cube = cubeProvider.getCube(cubeX, lowest_ycube_key, cubeZ);
                BlockPos base = cube.getCoords().getMinBlockPos();

                for (MC_Coord b : next_to_set) {
                    int mcX = b.mcX & 15; //local mc coords
                    int mcY = b.mcY & 15;
                    int mcZ = b.mcZ & 15;

                    Block block = Block.getBlockFromName(b.blockName);
                    if (block == null) continue;

                    if(n == 1000000) {
                        notifyCommandListener(sender, this, "setting block " + (base.getX() + mcX) + ", " + (base.getZ() + mcZ));
                        n = 0;
                    }
                    IBlockState state = block.getDefaultState();
                    if(b.is_slab) {
                        state = state.withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM);
                    }
                    if(b.block_variant.equalsIgnoreCase("netherbrick")) {
                        state = state.withProperty(BlockStoneSlab.VARIANT, BlockStoneSlab.EnumType.NETHERBRICK);
                    }

                    //world.setBlockState(base.add(mcX, mcY, mcZ), state, 3);
                    //touchedCubes.add(cubeX + "," + lowest_ycube_key + "," + cubeZ);
                    cube.setBlockState(base.add(mcX, mcY, mcZ), state);
                    n++;
                }
                unsavedCubes++;
                if (unsavedCubes >= SAVE_INTERVAL) {
                    cubeProvider.saveChunks(true);
                    cubicWorld.unloadOldCubes();
                    unsavedCubes = 0;
                    int pct = (int)(100.0 * row / rowCount);
                    notifyCommandListener(sender, this, "[loadbinary] " + pct + "%% - " + row + " / " + rowCount + " rows processed.");

                }
            }

            //HashMap of cubicY -> list of blocks
            //get smallest cubicY in list, add blocks in this list to the to-gen list
            //then add the blocks in the same cubicY from the other XZ coords in this cubicXZ
            //finally, call set block on the complete list

        }

    }


    private String addToCache(MC_Coord block_data, boolean is_top) {
        int cubeX = block_data.mcX >> 4;
        int cubeZ = block_data.mcZ >> 4;
        //int cubeY = block_data.mcY >> 4;

        int localX = block_data.mcX & 15;
        int localZ = block_data.mcZ & 15;
        int xzKey = localX * 16 + localZ;
        String key = packKey(cubeX, cubeZ);

        cube_cache.computeIfAbsent(key, k -> new HashMap<>())
                .computeIfAbsent(xzKey, k -> new CubicChunkColumn())
                .addBlock(block_data, !block_data.above_ground);

        return key;

    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        if (args.length < 1) throw new CommandException(getUsage(sender));

        File file = new File(args[0]);
        if (!file.exists()) throw new CommandException("File not found: " + args[0]);


        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {

            int magic = in.readInt();
            if (magic != MAGIC) throw new CommandException("Bad magic number.");
            int rowCount = in.readInt();

            for (int row = 0; row < rowCount; row++) {
                double x = in.readDouble();
                double y = in.readDouble();
                in.readDouble(); // dtm
                in.readDouble(); // oh
                in.readDouble(); //dsm
                in.readDouble(); // mrr
                in.readFloat(); //is_street
                in.readFloat(); //is_building
                in.readFloat(); //is_building_edge
                in.readFloat();  // metres down



                if (x < minX) minX = x;
                if (y > maxY) maxY = y;
            }
        } catch (IOException e) {
            throw new CommandException("IO error: " + e.getMessage());
        }
        notifyCommandListener(sender, this, "minX=" + minX + " maxY=" + maxY);


        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {

            int magic = in.readInt();
            if (magic != MAGIC)
                throw new CommandException("Bad magic number.");

            this.world = server.getWorld(0);
            this.cubicWorld = (ICubicWorldServer) world;
            this.cubeProvider = (CubeProviderServer) cubicWorld.getCubeCache();




            int rowCount = in.readInt();
            notifyCommandListener(sender, this, "Row count: " + rowCount);

            for (int row = 0; row < rowCount; row++) {
                double x   = in.readDouble();
                double y   = in.readDouble();
                double  dtm = in.readDouble();
                double  oh  = in.readDouble();
                double dsm = in.readDouble();
                double  mrr = in.readDouble();
                boolean is_street = in.readFloat() != 0.0f;
                boolean is_building = in.readFloat() != 0.0f;
                boolean is_building_edge = in.readFloat() != 0.0f;
                double wall_depth_below = in.readFloat();  // metres


                if(is_street && !is_building && dtm > 2) {
                    //dtm = roundToHalf(dtm * 2.0) / 2.0;
                    oh = 0.0;
                }


                //add column to cube dict
                float step = 0.5f;

                if(dsm < 0.5) continue;

                final int ROOF_THICKNESS = 2;  // number of blocks below the roof to fill

                if(is_building && !is_building_edge && wall_depth_below == 0.0) {
                    double h_ground = dtm;
                    double h_roof   = dtm + oh;

                    MC_Coord ground = this.new MC_Coord(x, y, dtm, h_ground, false, is_street, is_building);
                    addToCache(ground, false);

                    // Place ROOF_THICKNESS blocks downward from the roof
                    String cubekey = null;
                    for(int i = 0; i < ROOF_THICKNESS; i++) {
                        double h = h_roof - (i * 0.5);
                        if(h <= h_ground) break;  // don't go below the ground block
                        boolean is_top = (i == 0);  // only the topmost block uses slab logic

                        MC_Coord block = this.new MC_Coord(x, y, dtm, h, is_top, is_street, is_building);
                        cubekey = addToCache(block, is_top);
                    }

                    if (cubekey != null && cube_cache.get(cubekey).size() >= 256) {
                        flushCube(cubekey, server, sender, row, rowCount);
                    }

                    /*
                    // Fully interior, no wall — just ground + roof
                    double h_ground = dtm;
                    double h_roof   = dtm + oh;

                    MC_Coord ground = this.new MC_Coord(x, y, dtm, h_ground, false, is_street, is_building);
                    MC_Coord roof   = this.new MC_Coord(x, y, dtm, h_roof,   true,  is_street, is_building);

                    addToCache(ground, false);
                    String cubekey = addToCache(roof, true);
                    if (cube_cache.get(cubekey).size() >= 256) {
                        flushCube(cubekey, server, sender, row, rowCount);
                    }*/
                } else if(is_building && wall_depth_below > 0.0) {
                    // Has a wall — place ground, then column from (roof - wall_depth_below) up to roof
                    double h_ground = dtm;
                    double h_roof   = dtm + oh;
                    double h_wall_bottom = h_roof - wall_depth_below;

                    MC_Coord ground = this.new MC_Coord(x, y, dtm, h_ground, false, is_street, is_building);
                    addToCache(ground, false);

                    int wallSteps = (int) Math.round(wall_depth_below * 2.0) + 1;
                    String cubekey = null;
                    for(int i = 0; i < wallSteps; i++) {
                        double h = h_wall_bottom + (i * 0.5);
                        boolean is_top = (i == wallSteps - 1);
                        MC_Coord block = this.new MC_Coord(x, y, dtm, h, is_top, is_street, is_building);
                        cubekey = addToCache(block, is_top);
                    }
                    if (cubekey != null && cube_cache.get(cubekey).size() >= 256) {
                        flushCube(cubekey, server, sender, row, rowCount);
                    }

                } else {

                    int halfSteps = (int) Math.round((oh + 0.5) * 2.0);
                    for (int i = 0; i < halfSteps; i++) {
                        double h = dtm + (i * 0.5);
                        boolean is_top = (i == halfSteps - 1);

                        MC_Coord block_data = this.new MC_Coord(x, y, dtm, h, is_top, is_street, is_building);
                        String cubekey = addToCache(block_data, is_top);

                        //TODO might have to add a check here to make sure we're at the end of the column (at object height)
                        // if not, then skip the cube cache size check (do "continue")

                        //notifyCommandListener(sender, this, cube_cache.get(key).size() + "");

                        if (is_top && cube_cache.get(cubekey).size() >= 256) {
                            flushCube(cubekey, server, sender, row, rowCount);
                        }

                    }
                }
            }
            //finally, iterate over xz coords one last time to calculate neighbor dtms and set blocks
            for (Map.Entry<String, HashMap<Integer, CubicChunkColumn>> outerEntry : cube_cache.entrySet()) {
                String xz_chunk = outerEntry.getKey(); // The first set of keys
                HashMap<Integer, CubicChunkColumn> innerMap = outerEntry.getValue();

                String[] parts = xz_chunk.split(",");
                int cubeX = Integer.parseInt(parts[0]);
                int cubeZ = Integer.parseInt(parts[1]);


                for (Integer localXZ : innerMap.keySet()) { // The second set of keys

                    // If you need the value as well:
                    double glevel = innerMap.get(localXZ).ground_level;
                    int localZ = localXZ & 15;
                    int localX = localXZ >> 4;
                    int globalX = cubeX*16 + localX;
                    int globalZ = cubeZ*16 + localZ;

                    //find neighbor ground levels...
                    int n1_cubeX = localX - 1 < 0 ? cubeX - 1 : cubeX; int n1_localX = localX - 1 < 0 ? 15 : localX - 1;
                    int n1_cubeZ = cubeZ; int n1_localZ = localZ;
                    double n1gl = check_gl_return_0_if_none(packKey(n1_cubeX, n1_cubeZ), getLocalXZkey(n1_localX, n1_localZ));

                    int n2_cubeX = localX + 1 > 15 ? cubeX + 1 : cubeX; int n2_localX = localX + 1 > 15 ? 0 : localX + 1;
                    int n2_cubeZ = cubeZ; int n2_localZ = localZ;
                    double n2gl = check_gl_return_0_if_none(packKey(n2_cubeX, n2_cubeZ), getLocalXZkey(n2_localX, n2_localZ));

                    int n3_cubeX = cubeX; int n3_localX = localX;
                    int n3_cubeZ = localZ - 1 < 0 ? cubeZ - 1 : cubeZ; int n3_localZ = localZ - 1 < 0 ? 15 : localZ - 1;
                    double n3gl = check_gl_return_0_if_none(packKey(n3_cubeX, n3_cubeZ), getLocalXZkey(n3_localX, n3_localZ));

                    int n4_cubeX = cubeX; int n4_localX = localX;
                    int n4_cubeZ = localZ + 1 > 15 ? cubeZ + 1 : cubeZ; int n4_localZ = localZ + 1 > 15 ? 0 : localZ + 1;
                    double n4gl = check_gl_return_0_if_none(packKey(n4_cubeX, n4_cubeZ), getLocalXZkey(n4_localX, n4_localZ));

                    double[] ngl = {n1gl, n2gl, n3gl, n4gl};
                    Arrays.sort(ngl);
                    double min_neighbor_glevel = ngl[0];

                    double dirt_blocks_below = glevel - min_neighbor_glevel - 1;
                    if(dirt_blocks_below > 0) {
                        //create additional blocks, adding them to the additional_blocks list for this chunk
                        for(float i = 0; i < dirt_blocks_below; i++) {
                            int nnn = (int) Math.round(glevel-i-1);
                            if(nnn <= 0) break;

                            MC_Coord b = this.new MC_Coord(globalX, globalZ, nnn);
                            b.setBlockName("minecraft:dirt");
                            //MC_Coord b = this.new MC_Coord(globalX, globalZ, glevel, glevel-i-0.5, false, false, false);
                            cube_cache.get(xz_chunk).get(localXZ).addBlock(b, false);
                            //System.out.println(cube_cache.get(xz_chunk).get(localXZ).col_blocks.size());
                        }
                    }
                }
                //place the additional blocks for this chunk
                HashMap<Integer, CubicChunkColumn> updated_inner_map = cube_cache.get(xz_chunk);
                for(Integer localXZ : updated_inner_map.keySet()) {
                    CubicChunkColumn col = updated_inner_map.get(localXZ);

                    Integer lowest_ycube_key;
                    while((lowest_ycube_key = col.getLowestYlevel()) != null) {
                        //pull it out
                        ArrayList<MC_Coord> next_to_set = col.pull_y_level(lowest_ycube_key);
                        for(Integer localXZ2 : updated_inner_map.keySet()) {
                            if(localXZ2.equals(localXZ)) continue;
                            CubicChunkColumn col2 = updated_inner_map.get(localXZ2);
                            if (col2.has_y_level(lowest_ycube_key)) {
                                next_to_set.addAll(col2.pull_y_level(lowest_ycube_key));
                            }
                        }
                        //at this point, next_to_set should have all the blocks in this cubic chunk
                        //also, those blocks were removed from the hashmap
                        //However, ground_level of each column is still set, because we'll need that later to fill in the steep slope side-view holes
                        //we'll do that by doing a second pass, checking neighbor-block dtm values

                        //TODO: select chunk and set blocks here...
                        ICube cube = cubeProvider.getCube(cubeX, lowest_ycube_key, cubeZ);
                        BlockPos base = cube.getCoords().getMinBlockPos();

                        for (MC_Coord b : next_to_set) {
                            int mcX = b.mcX & 15; //local mc coords
                            int mcY = b.mcY & 15;
                            int mcZ = b.mcZ & 15;

                            Block block = Block.getBlockFromName(b.blockName);
                            if (block == null) continue;

                            //notifyCommandListener(sender, this, "setting ground filler");

                            IBlockState state = block.getDefaultState();
                            if(b.is_slab) {
                                state = state.withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM);
                            }
                            if(b.block_variant.equalsIgnoreCase("netherbrick")) {
                                state = state.withProperty(BlockStoneSlab.VARIANT, BlockStoneSlab.EnumType.NETHERBRICK);
                            }
                            //TODO...

                            cube.setBlockState(base.add(mcX, mcY, mcZ), state);
                            //world.setBlockState(base.add(mcX, mcY, mcZ), state, 3);
                            //touchedCubes.add(cubeX + "," + lowest_ycube_key + "," + cubeZ);
                        }

                        unsavedCubes++;
                        if (unsavedCubes >= SAVE_INTERVAL) {
                            cubeProvider.saveChunks(true);
                            cubicWorld.unloadOldCubes();
                            unsavedCubes = 0;
                        }
                    }

                    //HashMap of cubicY -> list of blocks
                    //get smallest cubicY in list, add blocks in this list to the to-gen list
                    //then add the blocks in the same cubicY from the other XZ coords in this cubicXZ
                    //finally, call set block on the complete list


                }

            }

            /*
            notifyCommandListener(sender, this, "Notifying FP2 about " + touchedCubes.size() + " cubes...");
            for (String cubeKey : touchedCubes) {
                String[] cparts = cubeKey.split(",");
                int cx = Integer.parseInt(cparts[0]);
                int cy = Integer.parseInt(cparts[1]);
                int cz = Integer.parseInt(cparts[2]);
                BlockPos notifyPos = new BlockPos(cx * 16, cy * 16, cz * 16);
                IBlockState st = world.getBlockState(notifyPos);
                world.notifyBlockUpdate(notifyPos, st, st, 3);
            }
            cubeProvider.saveChunks(true);
             */

            notifyCommandListener(sender, this, "Done reading " + rowCount + " rows.");

        } catch (IOException e) {
            throw new CommandException("IO error: " + e.getMessage());
        }
    }
}