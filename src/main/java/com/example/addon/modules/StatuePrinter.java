package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Hand;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.FallingBlock;
import net.minecraft.util.math.Box;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * StatuePrinter
 *
 * 1. Load a structure (.nbt, vanilla structure block format).
 *    Every distinct block type in the structure is one "part" (beak/eyes/body/legs ... n parts).
 * 2. Randomly pick n distinct building blocks from the main inventory (slots 9-35),
 *    making sure every part gets a block type with enough blocks.
 * 3. SWAP the picked blocks onto hotbar slots 0..n-1 (part i lives in hotbar slot i).
 * 4. Print bottom-up. Placement is done by us:
 *    - the supporting block is chosen by us and is NEVER a replaceable block (snow layer, grass...),
 *      so a click can never replace the wrong cell;
 *    - the click (hit position + face) is built by hand and sent through the normal use-item-on path;
 *    - a block counts as done only when the world really contains the expected block;
 *    - after the queue is empty the whole structure is re-scanned and missing blocks are re-queued.
 * 5. When done, SWAP the same slots back to restore the inventory.
 */
public class StatuePrinter extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> file = sg.add(new StringSetting.Builder()
        .name("file").description("Name of the .nbt file inside meteor-client/statues/")
        .defaultValue("statue.nbt").build());

    private final Setting<Boolean> chatMessages = sg.add(new BoolSetting.Builder()
        .name("chat-messages").description("Show this module's info, warning and error messages in chat. Off = completely silent.")
        .defaultValue(true).build());

    private enum Anchor { CROSSHAIR, PLAYER, MANUAL }

    private final Setting<Anchor> anchor = sg.add(new EnumSetting.Builder<Anchor>()
        .name("anchor").description("CROSSHAIR: block you look at. PLAYER: the block you stand in. MANUAL: the origin setting.")
        .defaultValue(Anchor.PLAYER).build());

    private final Setting<BlockPos> origin = sg.add(new BlockPosSetting.Builder()
        .name("origin").description("Minimum corner (min x,y,z) of the structure")
        .defaultValue(BlockPos.ORIGIN).visible(() -> anchor.get() == Anchor.MANUAL).build());

    private final Setting<Boolean> centerOnAnchor = sg.add(new BoolSetting.Builder()
        .name("center-on-anchor").description("Shift the structure so its horizontal center sits on the anchor (e.g. on you) instead of its corner")
        .defaultValue(true).build());

    private final Setting<Integer> offsetX = sg.add(new IntSetting.Builder()
        .name("offset-x").description("Extra shift in blocks along the X axis (applied after centering)")
        .defaultValue(0).min(-100).max(100).sliderMin(-20).sliderMax(20).build());

    private final Setting<Integer> offsetY = sg.add(new IntSetting.Builder()
        .name("offset-y").description("Extra shift in blocks up/down (applied after centering)")
        .defaultValue(0).min(-100).max(100).sliderMin(-20).sliderMax(20).build());

    private final Setting<Integer> offsetZ = sg.add(new IntSetting.Builder()
        .name("offset-z").description("Extra shift in blocks along the Z axis (applied after centering)")
        .defaultValue(0).min(-100).max(100).sliderMin(-20).sliderMax(20).build());

    private final Setting<Integer> blocksPerTick = sg.add(new IntSetting.Builder()
        .name("blocks-per-tick").defaultValue(4).min(1).sliderMax(20).build());

    private final Setting<Integer> delay = sg.add(new IntSetting.Builder()
        .name("delay").description("Ticks between placements (1-2 recommended on 6b6t)")
        .defaultValue(1).min(0).sliderMax(10).build());

    private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range").description("Max distance from your eyes to the clicked face (the server may enforce its own, smaller limit)")
        .defaultValue(4.5).min(1).max(100).sliderMax(8).build());

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate").defaultValue(false).build());

    private final Setting<Integer> giveUpTicks = sg.add(new IntSetting.Builder()
        .name("give-up-ticks").description("If nothing can be placed for this many ticks, stop and restore inventory")
        .defaultValue(400).min(20).sliderMax(2000).build());

    private final Setting<Integer> retryDelay = sg.add(new IntSetting.Builder()
        .name("retry-delay").description("Ticks to wait for the server to confirm a block before trying it again")
        .defaultValue(4).min(1).sliderMax(20).build());

    private final Setting<Integer> maxRetries = sg.add(new IntSetting.Builder()
        .name("max-retries").description("Attempts per block per round before it is skipped")
        .defaultValue(5).min(1).sliderMax(20).build());

    private final Setting<Integer> margin = sg.add(new IntSetting.Builder()
        .name("safety-margin").description("Extra % of blocks every part must have in stock when blocks are chosen (covers lag and retries). If no combination fits, exact amounts are tried.")
        .defaultValue(10).min(0).sliderMax(50).build());

    private final Setting<Boolean> allowPartial = sg.add(new BoolSetting.Builder()
        .name("allow-partial-blocks").description("Also pick slabs, stairs, walls and other non full-cube blocks. Off = full cubes only.")
        .defaultValue(false).build());

    private enum PlaceMode { SUPPORT_FIRST, AIR_ONLY, SUPPORT_ONLY }

    private final Setting<PlaceMode> placeMode = sg.add(new EnumSetting.Builder<PlaceMode>()
        .name("place-mode").description("SUPPORT_FIRST: click a solid neighbour, fall back to air place. AIR_ONLY: always click the target cell itself (works without any neighbour). SUPPORT_ONLY: never air place.")
        .defaultValue(PlaceMode.SUPPORT_FIRST).build());

    // Order matters: each step is a 90 degree clockwise turn (seen from above).
    private enum Facing { SOUTH, WEST, NORTH, EAST }
    private enum FacingMode { TOWARD_PLAYER, SAME_AS_PLAYER }

    private final Setting<Boolean> alignToView = sg.add(new BoolSetting.Builder()
        .name("align-to-view").description("Rotate the structure according to the direction you are looking")
        .defaultValue(true).build());

    private final Setting<Facing> front = sg.add(new EnumSetting.Builder<Facing>()
        .name("front").description("Direction the FRONT of your statue faces in the saved .nbt file")
        .defaultValue(Facing.SOUTH).visible(alignToView::get).build());

    private final Setting<FacingMode> facingMode = sg.add(new EnumSetting.Builder<FacingMode>()
        .name("facing-mode").description("TOWARD_PLAYER: statue front faces you. SAME_AS_PLAYER: statue faces the same way you look")
        .defaultValue(FacingMode.TOWARD_PLAYER).visible(alignToView::get).build());

    private enum State { SETUP, PRINT, RETURN }

    private static class Placement {
        final BlockPos pos;
        final int part;
        int lastTry = Integer.MIN_VALUE / 2;   // tick of the last attempt
        int tries;                              // attempts in the current round
        double dist;                            // distance of the last attempt

        Placement(BlockPos pos, int part) { this.pos = pos; this.part = part; }
        BlockPos pos() { return pos; }
        int part() { return part; }
    }

    // Where and how we click to place a block.
    private record Support(BlockPos pos, Direction face, Vec3d hit) {}

    // A cell that can be placed right now, with the click to use and its distance.
    private record Candidate(Placement pl, Support sup, double dist) {}

    private Vec3d lastPlayerPos;
    private double okMax, failMin;   // farthest confirmed placement / nearest repeatedly failed one

    private State state;
    private final List<Placement> queue = new ArrayList<>();
    private final List<Placement> all = new ArrayList<>();   // whole structure, for verification sweeps
    private int partCount;
    private Item[] chosen;      // part -> picked item
    private int[] origSlot;     // part -> original inventory slot (9-35)
    private int[] partNeed;     // part -> number of blocks needed
    private int[] required;     // part -> number of blocks the chosen item must have in stock (need + margin)
    private int[] starveSince;  // part -> tick when its block first went missing (-1 = fine)
    private int step, timer, idleTicks, age, sweeps;
    private final Random rng = new Random();

    public StatuePrinter() {
        super(Categories.Misc, "fred-printer", "Picks n random inventory blocks and prints a statue with them.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) { toggle(); return; }

        BlockPos base;
        if (anchor.get() == Anchor.PLAYER) {
            base = mc.player.getBlockPos();   // the block you are standing in
        } else if (anchor.get() == Anchor.CROSSHAIR) {
            if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos hitPos = hit.getBlockPos();
                // Replaceable blocks (snow layer, tall grass, ...) vanish when a block is placed,
                // so treat them as air: the structure starts at their position, not above them.
                base = mc.world.getBlockState(hitPos).isReplaceable() ? hitPos : hitPos.up();
            } else { tellError("Not looking at a block."); toggle(); return; }
        } else base = origin.get();

        if (!loadStructure(base)) { toggle(); return; }
        if (!pickBlocks()) { toggle(); return; }

        step = 0; timer = 0; idleTicks = 0; age = 0; sweeps = 0;
        okMax = 0; failMin = Double.MAX_VALUE; lastPlayerPos = null;
        state = State.SETUP;
        tellInfo("Parts: %d / Blocks: %d - starting.", partCount, queue.size());
        if (anchor.get() == Anchor.PLAYER && centerOnAnchor.get()) {
            tellInfo("The statue surrounds you: cells you stand in are placed once you step out of the way.");
        }
    }

    // ---------- Load structure (part = distinct block type) ----------
    private boolean loadStructure(BlockPos base) {
        queue.clear();
        all.clear();
        Path path = MeteorClient.FOLDER.toPath().resolve("statues").resolve(file.get());
        if (!Files.exists(path)) { tellError("File not found: " + path); return false; }

        try {
            NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
            NbtList palette = root.getListOrEmpty("palette");
            NbtList blocks = root.getListOrEmpty("blocks");

            // palette state index -> part index (by block name, properties ignored)
            Map<String, Integer> partByName = new LinkedHashMap<>();
            int[] statePart = new int[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                String name = palette.getCompoundOrEmpty(i).getString("Name", "minecraft:air");
                if (name.equals("minecraft:air") || name.equals("minecraft:structure_void")
                    || name.equals("minecraft:jigsaw") || name.equals("minecraft:structure_block")) {
                    statePart[i] = -1;
                } else {
                    statePart[i] = partByName.computeIfAbsent(name, k -> partByName.size());
                }
            }

            // structure box size (used to keep the rotated structure anchored at its min corner)
            NbtList size = root.getListOrEmpty("size");
            int sizeX = Math.max(1, size.getInt(0, 1));
            int sizeZ = Math.max(1, size.getInt(2, 1));
            int steps = rotationSteps();

            // rotate the box corners to find the new min corner
            int[] c1 = {0, 0}, c2 = {sizeX - 1, sizeZ - 1};
            for (int s = 0; s < steps; s++) { rotateCw(c1); rotateCw(c2); }
            int minX = Math.min(c1[0], c2[0]);
            int minZ = Math.min(c1[1], c2[1]);

            // Translation: put the horizontal center of the (rotated) structure on the anchor, then add the offsets.
            int dimX = (steps % 2 == 0) ? sizeX : sizeZ;
            int dimZ = (steps % 2 == 0) ? sizeZ : sizeX;
            int shiftX = (centerOnAnchor.get() ? -((dimX - 1) / 2) : 0) + offsetX.get();
            int shiftZ = (centerOnAnchor.get() ? -((dimZ - 1) / 2) : 0) + offsetZ.get();
            int shiftY = offsetY.get();

            for (int i = 0; i < blocks.size(); i++) {
                NbtCompound b = blocks.getCompoundOrEmpty(i);
                int part = statePart[b.getInt("state", 0)];
                if (part < 0) continue;
                NbtList p = b.getListOrEmpty("pos");
                int[] xz = {p.getInt(0, 0), p.getInt(2, 0)};
                for (int s = 0; s < steps; s++) rotateCw(xz);
                queue.add(new Placement(base.add(xz[0] - minX + shiftX, p.getInt(1, 0) + shiftY, xz[1] - minZ + shiftZ), part));
            }

            partCount = partByName.size();
            if (partCount == 0) { tellError("Structure is empty."); return false; }
            if (partCount > 9) { tellError("More than 9 parts (hotbar limit): " + partCount); return false; }

            // blocks actually needed per part (positions that are already filled need nothing)
            partNeed = new int[partCount];
            for (Placement pl : queue) {
                if (mc.world.getBlockState(pl.pos()).isReplaceable()) partNeed[pl.part()]++;
            }

            // bottom layer first
            queue.sort(Comparator.<Placement>comparingInt(pl -> pl.pos().getY())
                .thenComparingInt(pl -> pl.pos().getX()).thenComparingInt(pl -> pl.pos().getZ()));
            all.addAll(queue);
            return true;
        } catch (Exception e) {
            tellError("Failed to load structure: " + e.getMessage());
            return false;
        }
    }

    // ---------- Rotation ----------
    // Number of 90-degree clockwise turns needed so the statue front points the wanted way.
    private int rotationSteps() {
        if (!alignToView.get()) return 0;
        // yaw 0 = south, 90 = west, 180 = north, -90 = east  (matches Facing order)
        int p = Math.floorMod((int) Math.floor((mc.player.getYaw() + 45f) / 90f), 4);
        int wanted = facingMode.get() == FacingMode.TOWARD_PLAYER ? (p + 2) % 4 : p;
        return Math.floorMod(wanted - front.get().ordinal(), 4);
    }

    // (x, z) -> (-z, x): rotates 90 degrees clockwise seen from above (north -> east)
    private static void rotateCw(int[] xz) {
        int x = xz[0];
        xz[0] = -xz[1];
        xz[1] = x;
    }

    // ---------- Pick n random block types from the inventory ----------
    private boolean pickBlocks() {
        Map<Item, Integer> firstSlot = new LinkedHashMap<>();
        Map<Item, Integer> total = new HashMap<>();   // item -> total count in slots 9-35
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack s = mc.player.getInventory().getStack(slot);
            if (s.isEmpty() || !isGoodBlock(s)) continue;
            firstSlot.putIfAbsent(s.getItem(), slot);
            total.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (firstSlot.size() < partCount) {
            tellError("Not enough block types (need %d, have %d)", partCount, firstSlot.size());
            return false;
        }

        List<Item> items = new ArrayList<>(firstSlot.keySet());

        // hardest parts (most blocks needed) are assigned first
        Integer[] order = new Integer[partCount];
        for (int i = 0; i < partCount; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(partNeed[b], partNeed[a]));

        // Selection check 1: every part gets its OWN block type whose total stock covers
        // the blocks it needs plus a safety margin (so printing can't run dry later).
        Item[] result = new Item[partCount];
        required = new int[partCount];
        boolean ok = false;
        if (margin.get() > 0) {
            for (int i = 0; i < partCount; i++) {
                required[i] = partNeed[i] + (int) Math.ceil(partNeed[i] * margin.get() / 100.0) + 2;
            }
            ok = assign(0, order, items, total, result, new HashSet<>());
            if (!ok) tellWarn("No combination with a %d%% safety margin - trying exact amounts.", margin.get());
        }

        // Selection check 2: fall back to the exact amounts if the margin made it impossible.
        if (!ok) {
            required = partNeed.clone();
            Arrays.fill(result, null);
            ok = assign(0, order, items, total, result, new HashSet<>());
        }

        if (!ok) {
            reportShortage(total);
            return false;
        }

        chosen = result;
        starveSince = new int[partCount];
        Arrays.fill(starveSince, -1);
        origSlot = new int[partCount];
        for (int i = 0; i < partCount; i++) {
            origSlot[i] = firstSlot.get(chosen[i]);
            tellInfo("Part %d -> %s (need %d, have %d)", i,
                chosen[i].getDefaultStack().getName().getString(),
                partNeed[i], total.get(chosen[i]));
        }
        return true;
    }

    // Explain exactly why no block combination works.
    private void reportShortage(Map<Item, Integer> total) {
        for (int i = 0; i < partCount; i++) tellInfo("Part %d needs %d blocks.", i, partNeed[i]);

        total.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).limit(5)
            .forEach(e -> tellInfo("Stock: %s x%d", e.getKey().getDefaultStack().getName().getString(), e.getValue()));

        int maxTotal = Collections.max(total.values());
        for (int i = 0; i < partCount; i++) {
            if (partNeed[i] > maxTotal) {
                tellError("Part %d needs %d blocks, but your largest stock of ONE block type is %d. A part is built from a single block type, so you need that many of the same block.",
                    i, partNeed[i], maxTotal);
                return;
            }
        }
        tellError("Every part fits some block type, but not all parts at the same time (each part needs its own different block type with enough stock).");
    }

    // How many of an item the player holds anywhere (hotbar + inventory).
    private int countInInventory(Item item) {
        int n = 0;
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack s = mc.player.getInventory().getStack(slot);
            if (s.getItem() == item) n += s.getCount();
        }
        return n;
    }

    // Random assignment of one distinct block type to every part.
    // Parts go from the largest need (fewest eligible types) to the smallest. Eligible sets are nested
    // (a bigger need is always eligible for fewer types), so picking uniformly among the still-free
    // eligible types at every step gives EVERY valid assignment exactly the same probability,
    // and a failed step means no valid assignment exists at all (no backtracking needed).
    private boolean assign(int k, Integer[] order, List<Item> items, Map<Item, Integer> total,
                           Item[] result, Set<Item> used) {
        used.clear();
        for (int part : order) {
            List<Item> cands = new ArrayList<>();
            for (Item it : items) {
                if (!used.contains(it) && total.get(it) >= required[part]) cands.add(it);
            }
            if (cands.isEmpty()) return false;
            Item pick = cands.get(rng.nextInt(cands.size()));
            result[part] = pick;
            used.add(pick);
        }
        return true;
    }

    private boolean isGoodBlock(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        if (b instanceof FallingBlock || b instanceof BlockEntityProvider) return false;
        var st = b.getDefaultState();
        if (allowPartial.get()) return !st.getCollisionShape(mc.world, BlockPos.ORIGIN).isEmpty();
        return st.isFullCube(mc.world, BlockPos.ORIGIN);
    }

    // ---------- Main loop ----------
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || state == null) return;

        switch (state) {
            case SETUP -> {   // one SWAP per tick: inventory -> hotbar slot "step"
                InvUtils.move().from(origSlot[step]).toHotbar(step);
                if (++step >= partCount) { state = State.PRINT; step = 0; }
            }
            case PRINT -> print();
            case RETURN -> {  // swap back: leftover blocks + original hotbar items restored
                InvUtils.move().from(step).to(origSlot[step]);
                if (++step >= partCount) { tellInfo("Done, inventory restored."); state = null; toggle(); }
            }
        }
    }

    private void print() {
        if (timer > 0) { timer--; return; }
        age++;

        // A cell is finished when the world really holds the expected block.
        // A cell taken by some other solid block cannot be filled: drop it (reported at the end).
        queue.removeIf(pl -> {
            var st = mc.world.getBlockState(pl.pos());
            if (st.getBlock() == expectedBlock(pl.part())) {
                if (pl.tries > 0) okMax = Math.max(okMax, pl.dist);   // server accepted it from this far
                return true;
            }
            return !st.isReplaceable();
        });

        if (queue.isEmpty()) {
            if (requeueMissing()) return;   // verification sweep found missing blocks
            finish();
            return;
        }

        Vec3d eye = mc.player.getEyePos();
        double maxDistSq = range.get() * range.get();
        int placed = 0;

        // Collect every cell that can be placed right now (in reach, has a solid neighbour to click).
        List<Candidate> cands = new ArrayList<>();
        for (Iterator<Placement> it = queue.iterator(); it.hasNext(); ) {
            Placement pl = it.next();
            if (age - pl.lastTry < retryDelay.get()) continue;         // waiting for server confirmation
            if (pl.tries >= maxRetries.get()) { failMin = Math.min(failMin, pl.dist); it.remove(); continue; } // skipped this round, re-checked by the sweep

            if (!mc.world.isPosLoaded(pl.pos())) continue;               // chunk not loaded: placing would only desync
            Support sup = findSupport(pl.pos(), eye, maxDistSq);
            if (sup == null) continue;                                  // out of reach or no solid neighbour yet
            if (mc.player.getBoundingBox().intersects(new Box(pl.pos()))) continue; // we are standing in it

            cands.add(new Candidate(pl, sup, eye.squaredDistanceTo(sup.hit())));
        }
        cands.sort(Comparator.comparingDouble(Candidate::dist));   // nearest first, like a printer

        for (Candidate c : cands) {
            if (placed >= blocksPerTick.get()) break;
            Placement pl = c.pl();
            Support sup = c.sup();

            ItemStack hs = mc.player.getInventory().getStack(pl.part());
            if (hs.isEmpty() || hs.getItem() != chosen[pl.part()]) {   // stack used up -> refill from inventory
                if (refill(pl.part())) { starveSince[pl.part()] = -1; return; }   // swap sent, check again next tick

                // Nothing to refill with. The client's stack count can briefly lag behind the server
                // (rejected placements are resynced a moment later), so wait before giving up.
                if (starveSince[pl.part()] < 0) starveSince[pl.part()] = age;
                if (age - starveSince[pl.part()] > 60) {
                    tellError("Out of blocks: %s (you hold %d of it).",
                        chosen[pl.part()].getDefaultStack().getName().getString(),
                        countInInventory(chosen[pl.part()]));
                    state = State.RETURN; step = 0; return;
                }
                continue;   // other parts can still be placed meanwhile
            }
            starveSince[pl.part()] = -1;

            pl.tries++;
            pl.lastTry = age;
            pl.dist = Math.sqrt(c.dist());
            placed++;
            interact(pl.part(), sup);   // stays in the queue until the world confirms the block
        }

        if (placed > 0) { idleTicks = 0; timer = delay.get(); }
        else {
            // Like a printer: while you walk around the statue the timeout keeps resetting.
            Vec3d now = mc.player.getEntityPos();
            if (lastPlayerPos != null && now.squaredDistanceTo(lastPlayerPos) > 0.01) idleTicks = 0;
            lastPlayerPos = now;

            idleTicks++;
            if (idleTicks == 40) {
                double nearest = Double.MAX_VALUE;
                for (Placement pl : queue) nearest = Math.min(nearest, eye.distanceTo(Vec3d.ofCenter(pl.pos())));
                tellWarn("Nothing placeable: %d blocks left, nearest is %.1f blocks away (range %.1f). Walk closer to them.",
                    queue.size(), nearest, range.get());
            } else if (idleTicks > giveUpTicks.get()) {
                tellWarn("Nothing placeable for %d ticks - giving up.", giveUpTicks.get());
                finish();
            }
        }
    }

    // ---------- Placement ----------
    // Pick a solid neighbour of target to click on. Replaceable blocks (snow layer, grass, ...),
    // fluids, air and block entities are never used, so the click can't replace the wrong cell.
    private Support findSupport(BlockPos target, Vec3d eye, double maxDistSq) {
        Support best = null;
        double bestDist = Double.MAX_VALUE;

        if (placeMode.get() != PlaceMode.AIR_ONLY) for (Direction d : Direction.values()) {
            BlockPos n = target.offset(d);
            var st = mc.world.getBlockState(n);
            if (st.isAir() || st.isReplaceable() || !st.getFluidState().isEmpty()) continue;
            if (st.getBlock() instanceof BlockEntityProvider) continue;

            Direction face = d.getOpposite();   // face of n that touches target
            Vec3d normal = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
            Vec3d hit = Vec3d.ofCenter(n).add(normal.multiply(0.5));

            if (eye.subtract(hit).dotProduct(normal) <= 0) continue;   // face points away from us
            double dist = eye.squaredDistanceTo(hit);
            if (dist > maxDistSq) continue;

            if (dist < bestDist) { bestDist = dist; best = new Support(n, face, hit); }
        }
        // Air place: click the (replaceable) target cell itself; the server places the block right there.
        if (best == null && placeMode.get() != PlaceMode.SUPPORT_ONLY) {
            Vec3d center = Vec3d.ofCenter(target);
            Vec3d d = eye.subtract(center);
            double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
            Direction face = (ay >= ax && ay >= az) ? (d.y >= 0 ? Direction.UP : Direction.DOWN)
                : (ax >= az) ? (d.x >= 0 ? Direction.EAST : Direction.WEST)
                : (d.z >= 0 ? Direction.SOUTH : Direction.NORTH);
            Vec3d hit = center.add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            if (eye.squaredDistanceTo(hit) <= maxDistSq) best = new Support(target, face, hit);
        }
        return best;
    }

    // Rotate (optional), select the hotbar slot, send the use-item-on click, swing, swap back.
    private void interact(int hotbarSlot, Support s) {
        Runnable action = () -> {
            if (!InvUtils.swap(hotbarSlot, true)) return;
            BlockHitResult hit = new BlockHitResult(s.hit(), s.face(), s.pos(), false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            InvUtils.swapBack();
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(s.hit()), Rotations.getPitch(s.hit()), 50, action);
        } else {
            action.run();
        }
    }

    // ---------- Verification ----------
    // Re-scan the whole structure; put every empty (replaceable) wrong cell back in the queue.
    private boolean requeueMissing() {
        if (sweeps >= 3) return false;

        int n = 0;
        for (Placement pl : all) {
            var st = mc.world.getBlockState(pl.pos());
            if (st.getBlock() != expectedBlock(pl.part()) && st.isReplaceable()) {
                pl.tries = 0;
                pl.lastTry = Integer.MIN_VALUE / 2;
                queue.add(pl);
                n++;
            }
        }
        if (n == 0) return false;

        sweeps++;
        idleTicks = 0;
        tellInfo("Verification %d: %d missing blocks re-queued.", sweeps, n);
        return true;
    }

    private void finish() {
        int missing = 0, wrong = 0;
        for (Placement pl : all) {
            var st = mc.world.getBlockState(pl.pos());
            if (st.getBlock() == expectedBlock(pl.part())) continue;
            if (st.isReplaceable()) missing++; else wrong++;
        }
        if (missing > 0 || wrong > 0) tellWarn("Finished: %d missing, %d blocked by other blocks.", missing, wrong);
        else tellInfo("All blocks verified.");
        if (failMin < Double.MAX_VALUE) {
            tellWarn("Blocks failed %d times starting at %.1f blocks away; confirmed ones reached %.1f blocks. The server probably rejects placements beyond roughly that distance - set range to about %.1f.",
                maxRetries.get(), failMin, okMax, Math.max(okMax, 1.0));
        }
        state = State.RETURN;
        step = 0;
    }

    // All chat output goes through these so one setting can silence it.
    private void tellInfo(String msg, Object... args)  { if (chatMessages.get()) info(msg, args); }
    private void tellWarn(String msg, Object... args)  { if (chatMessages.get()) warning(msg, args); }
    private void tellError(String msg, Object... args) { if (chatMessages.get()) error(msg, args); }

    // The block the given part is supposed to be made of.
    private Block expectedBlock(int part) {
        return ((BlockItem) chosen[part]).getBlock();
    }

    private boolean refill(int part) {
        for (int slot = 9; slot <= 35; slot++) {
            if (slot == origSlot[part]) continue;   // holds the original hotbar item, leave it
            ItemStack s = mc.player.getInventory().getStack(slot);
            if (!s.isEmpty() && s.getItem() == chosen[part]) {
                InvUtils.move().from(slot).toHotbar(part);
                return true;
            }
        }
        return false;
    }
}
