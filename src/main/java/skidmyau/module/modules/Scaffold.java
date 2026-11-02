package skidmyau.module.modules;

import skidmyau.SkidMyau;
import skidmyau.event.EventTarget;
import skidmyau.event.types.EventType;
import skidmyau.event.types.Priority;
import skidmyau.events.*;
import skidmyau.management.RotationState;
import skidmyau.module.Module;
import skidmyau.property.properties.BooleanProperty;
import skidmyau.property.properties.IntProperty;
import skidmyau.property.properties.ModeProperty;
import skidmyau.property.properties.PercentProperty;
import skidmyau.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;

public class Scaffold extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double[] aimOffsets = new double[]{0.1, 0.3, 0.5, 0.7, 0.9};
    private static final java.util.EnumMap<EnumFacing, double[][]> aimPoints = new java.util.EnumMap<>(EnumFacing.class);
    private static final java.util.EnumMap<EnumFacing, double[]> faceCenters = new java.util.EnumMap<>(EnumFacing.class);

    static {
        for (EnumFacing facing : EnumFacing.VALUES) {
            Scaffold.aimPoints.put(facing, Scaffold.buildAimPoints(facing));
            Scaffold.faceCenters.put(facing, Scaffold.facePoint(facing, 0.5, 0.5, Scaffold.faceFixed(facing)));
        }
    }

    private int rotationTick = 0;
    private int lastSlot = -1;
    private int blockCount = -1;
    private float yaw = -180.0F;
    private float pitch = 0.0F;
    private int towerTick = 0;
    private int towerDelay = 0;
    private int stage = 0;
    private int startY = 256;
    private boolean shouldKeepY = false;
    private boolean towering = false;
    private EnumFacing targetFacing = null;
    private BlockData pendingBlockData = null;
    private Vec3 pendingHitVec = null;
    private boolean placedThisTick = false;
    private long lastPlaceTime = 0L;
    private BlockPos randomPointPos = null;
    private EnumFacing randomPointFacing = null;
    private double[] randomPoint = null;
    private BlockPos aimResultPos = null;
    private EnumFacing aimResultFacing = null;
    private AimResult aimResult = null;
    private BlockData stableBlockData = null;
    private int stableBlockDataAge = 0;
    private float lastAimYaw = 0.0F;
    private float lastAimPitch = 0.0F;
    private int lastAimTick = 0;
    private int tickCounter = 0;
    private long nextClickTime = 0L;
    private int clickPatternIndex = 0;
    private int dragBurst = 0;
    public final ModeProperty rotationMode = new ModeProperty("rotations", 2, new String[]{"NONE", "DEFAULT", "BACKWARDS", "SIDEWAYS"});
    public final ModeProperty aimMode = new ModeProperty(
            "rotation-mode",
            0,
            new String[]{"Center", "Random", "Stabilized", "NearestRotation", "ReverseYaw", "DiagonalYaw", "AngleYaw", "EdgePoint"},
            this::hasRotation
    );
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT"});
    public final ModeProperty sprintMode = new ModeProperty("sprint", 0, new String[]{"NONE", "VANILLA"});
    public final PercentProperty groundMotion = new PercentProperty("ground-motion", 100);
    public final PercentProperty airMotion = new PercentProperty("air-motion", 100);
    public final PercentProperty speedMotion = new PercentProperty("speed-motion", 100);
    public final ModeProperty tower = new ModeProperty("tower", 0, new String[]{"NONE", "VANILLA", "EXTRA", "TELLY"});
    public final ModeProperty keepY = new ModeProperty("keep-y", 0, new String[]{"NONE", "VANILLA", "EXTRA", "TELLY"});
    public final BooleanProperty keepYonPress = new BooleanProperty("keep-y-on-press", false, () -> this.keepY.getValue() != 0);
    public final BooleanProperty disableWhileJumpActive = new BooleanProperty("no-keep-y-on-jump-potion", false, () -> this.keepY.getValue() != 0);
    public final IntProperty placeDelay = new IntProperty("place-delay", 50, 0, 500);
    public final ModeProperty clickMode = new ModeProperty(
            "click-mode",
            0,
            new String[]{"Off", "Butterfly", "DoubleClick", "DragClick"}
    );
    public final IntProperty placeAhead = new IntProperty("place-ahead", 2, 0, 4);
    public final BooleanProperty trueSight = new BooleanProperty("true-sight", true);
    public final IntProperty rotationSpeed = new IntProperty("rotation-speed", 180, 1, 180, this::hasRotation);
    public final IntProperty pitchSpeed = new IntProperty("pitch-speed", 90, 1, 90, this::hasRotation);
    public final PercentProperty smoothness = new PercentProperty("rotation-smoothness", 0, this::hasRotation);
    public final IntProperty yawLimit = new IntProperty("yaw-limit", 180, 0, 180, this::hasRotation);
    public final IntProperty pitchLimit = new IntProperty("pitch-limit", 90, 0, 90, this::hasRotation);
    public final BooleanProperty safeWalk = new BooleanProperty("safe-walk", true);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", false);
    public final BooleanProperty blockCounter = new BooleanProperty("block-counter", true);

    private boolean hasRotation() {
        return this.rotationMode.getValue() != 0;
    }

    private boolean shouldStopSprint() {
        if (this.isTowering()) {
            return false;
        } else {
            boolean stage = this.keepY.getValue() == 1 || this.keepY.getValue() == 2;
            return (!stage || this.stage <= 0) && this.sprintMode.getValue() == 0;
        }
    }

    private boolean canPlace() {
        BedNuker bedNuker = (BedNuker) SkidMyau.moduleManager.modules.get(BedNuker.class);
        if (bedNuker.isEnabled() && bedNuker.isReady()) {
            return false;
        } else {
            LongJump longJump = (LongJump) SkidMyau.moduleManager.modules.get(LongJump.class);
            return !longJump.isEnabled() || !longJump.isAutoMode() || longJump.isJumping();
        }
    }

    private EnumFacing getBestFacing(BlockPos blockPos1, BlockPos blockPos3) {
        double offset = 0.0;
        EnumFacing enumFacing = null;
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (facing != EnumFacing.DOWN) {
                BlockPos pos = blockPos1.offset(facing);
                if (pos.getY() <= blockPos3.getY()) {
                    double distance = pos.distanceSqToCenter((double) blockPos3.getX() + 0.5, (double) blockPos3.getY() + 0.5, (double) blockPos3.getZ() + 0.5);
                    if (enumFacing == null || distance < offset || distance == offset && facing == EnumFacing.UP) {
                        offset = distance;
                        enumFacing = facing;
                    }
                }
            }
        }
        return enumFacing;
    }

    private BlockPos getTargetPos() {
        double ahead = (double) this.placeAhead.getValue();
        double predictedX = mc.thePlayer.posX + mc.thePlayer.motionX * ahead;
        double predictedZ = mc.thePlayer.posZ + mc.thePlayer.motionZ * ahead;
        int startY = MathHelper.floor_double(mc.thePlayer.posY);
        return new BlockPos(
                MathHelper.floor_double(predictedX),
                (this.stage != 0 && !this.shouldKeepY ? Math.min(startY, this.startY) : startY) - 1,
                MathHelper.floor_double(predictedZ)
        );
    }

    private BlockData getBlockData() {
        BlockPos targetPos = this.getTargetPos();
        if (this.stableBlockData != null
                && this.stableBlockDataAge < 5
                && this.isBlockDataValid(this.stableBlockData, targetPos)) {
            this.stableBlockDataAge++;
            return this.stableBlockData;
        }
        BlockData blockData = this.findBlockData(targetPos);
        this.stableBlockData = blockData;
        this.stableBlockDataAge = 0;
        return blockData;
    }

    private boolean isBlockDataValid(BlockData blockData, BlockPos targetPos) {
        BlockPos support = blockData.blockPos();
        if (BlockUtil.isReplaceable(support) || BlockUtil.isInteractable(support)) {
            return false;
        }
        BlockPos placed = support.offset(blockData.facing());
        if (!BlockUtil.isReplaceable(placed)) {
            return false;
        }
        double reach = (double) mc.playerController.getBlockReachDistance() + 0.5;
        if (mc.thePlayer.getDistance(
                (double) support.getX() + 0.5, (double) support.getY() + 0.5, (double) support.getZ() + 0.5
        ) > reach) {
            return false;
        }
        if (placed.distanceSqToCenter(
                (double) targetPos.getX() + 0.5, (double) targetPos.getY() + 0.5, (double) targetPos.getZ() + 0.5
        ) > 9.0) {
            return false;
        }
        return this.stage == 0 || this.shouldKeepY || support.getY() < this.startY;
    }

    private BlockData findBlockData(BlockPos targetPos) {
        if (!BlockUtil.isReplaceable(targetPos)) {
            return null;
        } else {
            ArrayList<BlockPos> positions = new ArrayList<>();
            for (int x = -4; x <= 4; x++) {
                for (int y = -4; y <= 0; y++) {
                    for (int z = -4; z <= 4; z++) {
                        BlockPos pos = targetPos.add(x, y, z);
                        if (!BlockUtil.isReplaceable(pos)
                                && !BlockUtil.isInteractable(pos)
                                && !(
                                mc.thePlayer.getDistance((double) pos.getX() + 0.5, (double) pos.getY() + 0.5, (double) pos.getZ() + 0.5)
                                        > (double) mc.playerController.getBlockReachDistance()
                        )
                                && (this.stage == 0 || this.shouldKeepY || pos.getY() < this.startY)) {
                            for (EnumFacing facing : EnumFacing.VALUES) {
                                if (facing != EnumFacing.DOWN) {
                                    BlockPos blockPos = pos.offset(facing);
                                    if (BlockUtil.isReplaceable(blockPos)) {
                                        positions.add(pos);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (positions.isEmpty()) {
                return null;
            } else {
                positions.sort(
                        Comparator.comparingDouble(
                                o -> o.distanceSqToCenter((double) targetPos.getX() + 0.5, (double) targetPos.getY() + 0.5, (double) targetPos.getZ() + 0.5)
                        )
                );
                BlockPos blockPos = positions.get(0);
                EnumFacing facing = this.getBestFacing(blockPos, targetPos);
                return facing == null ? null : new BlockData(blockPos, facing);
            }
        }
    }

    private void place(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        if (this.placedThisTick || vec3 == null) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        if (currentTime < this.nextClickTime) {
            // У края (под ногами воздух) не ждём паузу паттерна - иначе теряем блок.
            long minDelay = Math.max(50L, (long) this.placeDelay.getValue());
            if (!this.isUrgentPlacement() || currentTime - this.lastPlaceTime < minDelay) {
                return;
            }
        }
        if (ItemUtil.isHoldingBlock() && this.blockCount > 0) {
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.inventory.getCurrentItem(), blockPos, enumFacing, vec3)) {
                this.lastPlaceTime = currentTime;
                this.placedThisTick = true;
                this.nextClickTime = currentTime + this.getClickDelay();
                if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                    this.blockCount--;
                }
                if (this.swing.getValue()) {
                    mc.thePlayer.swingItem();
                } else {
                    PacketUtil.sendPacket(new C0APacketAnimation());
                }
            }
        }
    }

    /**
     * Задержка до следующего "клика" в зависимости от выбранной симуляции.
     * place-delay остаётся минимальным интервалом, режим задаёт рисунок.
     */
    private long getClickDelay() {
        long base = Math.max(0L, (long) this.placeDelay.getValue());
        switch (this.clickMode.getValue()) {
            case 1:
                // Butterfly: ровные частые клики двумя "пальцами" - 1 тик / 2 тика
                this.clickPatternIndex++;
                return Math.max(base, this.clickPatternIndex % 2 == 0
                        ? RandomUtil.nextLong(90L, 110L)
                        : RandomUtil.nextLong(45L, 55L));
            case 2:
                // DoubleClick: пара быстрых кликов и пауза
                this.clickPatternIndex++;
                return Math.max(base, this.clickPatternIndex % 3 == 2
                        ? RandomUtil.nextLong(170L, 250L)
                        : RandomUtil.nextLong(45L, 60L));
            case 3:
                // DragClick: серия быстрых кликов, затем отдых
                if (this.dragBurst <= 0) {
                    this.dragBurst = (int) RandomUtil.nextLong(4L, 8L);
                    return Math.max(base, RandomUtil.nextLong(300L, 500L));
                }
                this.dragBurst--;
                return Math.max(base, RandomUtil.nextLong(45L, 55L));
            default:
                return base;
        }
    }

    /**
     * Игрок стоит над пустотой - блок нужен прямо сейчас, паттерн кликов не должен тормозить.
     */
    private boolean isUrgentPlacement() {
        return BlockUtil.isReplaceable(new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ)
        ));
    }

    private EnumFacing yawToFacing(float yaw) {
        if (yaw < -135.0F || yaw > 135.0F) {
            return EnumFacing.NORTH;
        } else if (yaw < -45.0F) {
            return EnumFacing.EAST;
        } else {
            return yaw < 45.0F ? EnumFacing.SOUTH : EnumFacing.WEST;
        }
    }

    private double distanceToEdge(EnumFacing enumFacing) {
        switch (enumFacing) {
            case NORTH:
                return mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
            case EAST:
                return Math.ceil(mc.thePlayer.posX) - mc.thePlayer.posX;
            case SOUTH:
                return Math.ceil(mc.thePlayer.posZ) - mc.thePlayer.posZ;
            case WEST:
            default:
                return mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        }
    }

    private float getSpeed() {
        if (!mc.thePlayer.onGround) {
            return (float) this.airMotion.getValue() / 100.0F;
        } else {
            return MoveUtil.getSpeedLevel() > 0
                    ? (float) this.speedMotion.getValue() / 100.0F
                    : (float) this.groundMotion.getValue() / 100.0F;
        }
    }

    private double getRandomOffset() {
        return 0.2155 - RandomUtil.nextDouble(1.0E-4, 9.0E-4);
    }

    private float getCurrentYaw() {
        return MoveUtil.adjustYaw(
                mc.thePlayer.rotationYaw, (float) MoveUtil.getForwardValue(), (float) MoveUtil.getLeftValue()
        );
    }

    private boolean isDiagonal(float yaw) {
        float absYaw = Math.abs(yaw % 90.0F);
        return absYaw > 20.0F && absYaw < 70.0F;
    }

    private boolean isTowering() {
        if (mc.thePlayer.onGround && MoveUtil.isForwardPressed() && !PlayerUtil.isAirAbove()) {
            boolean keepY = this.keepY.getValue() == 3;
            boolean tower = this.tower.getValue() == 3;
            return keepY && this.stage > 0 || tower && mc.gameSettings.keyBindJump.isKeyDown();
        } else {
            return false;
        }
    }

    public Scaffold() {
        super("Scaffold", false);
    }

    public int getSlot() {
        return this.lastSlot;
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            this.placedThisTick = false;
            this.tickCounter++;
            if (this.rotationTick > 0) {
                this.rotationTick--;
            }
            if (mc.thePlayer.onGround) {
                if (this.stage > 0) {
                    this.stage--;
                }
                if (this.stage < 0) {
                    this.stage++;
                }
                if (this.stage == 0
                        && this.keepY.getValue() != 0
                        && (!(Boolean) this.keepYonPress.getValue() || PlayerUtil.isUsingItem())
                        && (!this.disableWhileJumpActive.getValue() || !mc.thePlayer.isPotionActive(Potion.jump))
                        && !mc.gameSettings.keyBindJump.isKeyDown()) {
                    this.stage = 1;
                }
                this.startY = this.shouldKeepY ? this.startY : MathHelper.floor_double(mc.thePlayer.posY);
                this.shouldKeepY = false;
                this.towering = false;
            }
            if (this.canPlace()) {
                ItemStack stack = mc.thePlayer.getHeldItem();
                int count = ItemUtil.isBlock(stack) ? stack.stackSize : 0;
                this.blockCount = Math.min(this.blockCount, count);
                if (this.blockCount <= 0) {
                    int slot = mc.thePlayer.inventory.currentItem;
                    if (this.blockCount == 0) {
                        slot--;
                    }
                    for (int i = slot; i > slot - 9; i--) {
                        int hotbarSlot = (i % 9 + 9) % 9;
                        ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(hotbarSlot);
                        if (ItemUtil.isBlock(candidate)) {
                            mc.thePlayer.inventory.currentItem = hotbarSlot;
                            this.blockCount = candidate.stackSize;
                            break;
                        }
                    }
                }
                float currentYaw = this.getCurrentYaw();
                float yawDiffTo180 = RotationUtil.wrapAngleDiff(currentYaw - 180.0F, event.getYaw());
                float diagonalYaw = this.isDiagonal(currentYaw)
                        ? yawDiffTo180
                        : RotationUtil.wrapAngleDiff(currentYaw - 135.0F * ((currentYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F), event.getYaw());
                float desiredYaw = event.getYaw();
                float desiredPitch = event.getPitch();
                switch (this.rotationMode.getValue()) {
                    case 1:
                        desiredYaw = diagonalYaw;
                        desiredPitch = 85.0F;
                        break;
                    case 2:
                        desiredYaw = yawDiffTo180;
                        desiredPitch = 85.0F;
                        break;
                    case 3:
                        desiredYaw = diagonalYaw;
                        desiredPitch = 85.0F;
                }
                this.yaw = desiredYaw;
                this.pitch = desiredPitch;
                BlockData blockData = this.getBlockData();
                if (this.targetFacing != null) {
                    int playerBlockX = MathHelper.floor_double(mc.thePlayer.posX);
                    int playerBlockY = MathHelper.floor_double(mc.thePlayer.posY);
                    int playerBlockZ = MathHelper.floor_double(mc.thePlayer.posZ);
                    blockData = new BlockData(new BlockPos(playerBlockX, playerBlockY - 1, playerBlockZ), this.targetFacing);
                    this.targetFacing = null;
                } else if (this.keepY.getValue() == 2 && this.stage > 0 && !mc.thePlayer.onGround) {
                    int nextBlockY = MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.motionY);
                    if (nextBlockY <= this.startY && mc.thePlayer.posY > (double) (this.startY + 1)) {
                        this.shouldKeepY = true;
                        blockData = this.getBlockData();
                    }
                }
                AimResult aim = this.findAim(blockData, desiredYaw, desiredPitch, event.getYaw(), event.getPitch(), this.rotationMode.getValue() != 0);
                float sentYaw = event.getYaw();
                float sentPitch = event.getPitch();
                if (this.rotationMode.getValue() != 0) {
                    float targetYaw;
                    float targetPitch;
                    if (aim != null) {
                        targetYaw = aim.yaw;
                        targetPitch = aim.pitch;
                        this.lastAimYaw = aim.yaw;
                        this.lastAimPitch = aim.pitch;
                        this.lastAimTick = this.tickCounter;
                    } else if (this.lastAimTick > 0 && this.tickCounter - this.lastAimTick <= 6) {
                        targetYaw = this.lastAimYaw;
                        targetPitch = this.lastAimPitch;
                    } else if (this.isTowering() || this.towering) {
                        float yawDelta = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - event.getYaw());
                        targetYaw = event.getYaw() + yawDelta * RandomUtil.nextFloat(0.98F, 0.99F);
                        targetPitch = RandomUtil.nextFloat(30.0F, 80.0F);
                        this.rotationTick = 3;
                        this.towering = true;
                    } else {
                        targetYaw = this.yaw;
                        targetPitch = this.pitch;
                    }
                    float[] stepped = this.stepRotation(event.getYaw(), event.getPitch(), targetYaw, targetPitch);
                    event.setRotation(stepped[0], stepped[1], 3);
                    if (this.moveFix.getValue() == 1) {
                        event.setPervRotation(stepped[0], 3);
                    }
                    sentYaw = stepped[0];
                    sentPitch = stepped[1];
                }
                this.yaw = sentYaw;
                this.pitch = sentPitch;
                this.pendingBlockData = null;
                this.pendingHitVec = null;
                if (blockData != null) {
                    this.pendingBlockData = blockData;
                    if (aim != null) {
                        this.pendingHitVec = aim.hitVec;
                    } else if (!this.trueSight.getValue()) {
                        this.pendingHitVec = BlockUtil.getHitVec(blockData.blockPos(), blockData.facing(), this.yaw, this.pitch);
                    }
                }
            }
        }
    }

    @EventTarget
    public void onPostUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST) {
            this.placePending(event.getYaw(), event.getPitch());
        }
    }

    private void placePending(float yaw, float pitch) {
        BlockData blockData = this.pendingBlockData;
        Vec3 hitVec = this.pendingHitVec;
        boolean hasTarget = blockData != null;
        this.pendingBlockData = null;
        this.pendingHitVec = null;
        if (!hasTarget) {
            return;
        }
        if (this.trueSight.getValue()) {
            // Ставим ровно то, что реально видно отправленной ротацией:
            // клик происходит, как только луч попадает в блок, а не после "доводки" прицела.
            this.placeByRayTrace(yaw, pitch, blockData);
            return;
        }
        if (hitVec == null) {
            return;
        }
        this.place(blockData.blockPos(), blockData.facing(), hitVec);
    }

    private void placeByRayTrace(float yaw, float pitch, BlockData target) {
        if (!ItemUtil.isHoldingBlock() || this.blockCount <= 0) {
            return;
        }
        double reach = (double) mc.playerController.getBlockReachDistance();
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        MovingObjectPosition mop = RotationUtil.rayTraceFrom(eye, yaw, pitch, reach);
        if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK) {
            return;
        }
        BlockPos support = mop.getBlockPos();
        EnumFacing side = mop.sideHit;
        BlockPos placePos = support.offset(side);
        if (!BlockUtil.isReplaceable(placePos) || BlockUtil.isInteractable(placePos)) {
            return;
        }
        if (this.intersectsPlayer(placePos)) {
            return;
        }
        if (placePos.getY() > MathHelper.floor_double(mc.thePlayer.posY) - 1) {
            return;
        }
        BlockPos targetPos = this.getTargetPos();
        if (placePos.distanceSqToCenter(
                (double) targetPos.getX() + 0.5, (double) targetPos.getY() + 0.5, (double) targetPos.getZ() + 0.5
        ) > 16.0) {
            return;
        }
        if (BlockUtil.isReplaceable(support) || BlockUtil.isInteractable(support)) {
            return;
        }
        BlockPos expected = target.blockPos();
        if (support.distanceSqToCenter(
                (double) expected.getX() + 0.5, (double) expected.getY() + 0.5, (double) expected.getZ() + 0.5
        ) > 25.0) {
            return;
        }
        this.place(support, side, mop.hitVec);
    }

    private boolean intersectsPlayer(BlockPos pos) {
        AxisAlignedBB block = new AxisAlignedBB(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1
        );
        return mc.thePlayer.getEntityBoundingBox().intersectsWith(block);
    }

    private float[] stepRotation(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
        float yawDelta = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDelta = MathHelper.wrapAngleTo180_float(targetPitch - currentPitch);
        float newYaw = currentYaw + this.computeStep(yawDelta, (float) this.rotationSpeed.getValue());
        float newPitch = currentPitch + this.computeStep(pitchDelta, (float) this.pitchSpeed.getValue());
        return new float[]{
                MathHelper.wrapAngleTo180_float(newYaw),
                MathHelper.clamp_float(newPitch, -90.0F, 90.0F)
        };
    }

    /**
     * Возвращает на сколько градусов нужно повернуть за этот тик.
     * Если до цели ближе чем maxSpeed - доворачиваем ровно до неё,
     * иначе идём шагом maxSpeed (умноженным на плавность).
     */
    private float computeStep(float delta, float maxSpeed) {
        float absDelta = Math.abs(delta);
        if (absDelta < 1.0E-4F) {
            return 0.0F;
        }
        float smooth = (float) this.smoothness.getValue() / 100.0F;
        if (smooth <= 0.0F || absDelta <= 6.0F) {
            return Math.copySign(Math.min(absDelta, maxSpeed), delta);
        }
        float step = Math.min(absDelta, maxSpeed) * (1.0F - 0.6F * smooth);
        step = Math.max(step, Math.min(absDelta, 6.0F));
        return Math.copySign(step, delta);
    }

    /**
     * Глаза на момент POST (после применения движения за этот тик).
     * C08 уходит уже из новой позиции, поэтому прицел надо считать от неё,
     * иначе PRE и POST рейкасты не совпадают и блок ставится только стоя на краю.
     */
    private Vec3 getPredictedEyes() {
        return new Vec3(
                mc.thePlayer.posX + mc.thePlayer.motionX,
                mc.thePlayer.posY + mc.thePlayer.motionY + (double) mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ + mc.thePlayer.motionZ
        );
    }

    /**
     * Ищет точку на нужной грани блока, попадание в которую даёт валидный рейкаст.
     * Первым проверяется центр грани (самая устойчивая точка), затем внутренние
     * точки, и только в конце - полная сетка.
     */
    private AimResult findAim(BlockData blockData, float desiredYaw, float desiredPitch, float currentYaw, float currentPitch, boolean applyLimits) {
        if (blockData == null) {
            return null;
        }
        BlockPos pos = blockData.blockPos();
        EnumFacing facing = blockData.facing();
        double reach = (double) mc.playerController.getBlockReachDistance();
        Vec3 eye = this.getPredictedEyes();
        float yawLimit = applyLimits ? (float) this.yawLimit.getValue() : 180.0F;
        float pitchLimit = applyLimits ? (float) this.pitchLimit.getValue() : 90.0F;
        int mode = this.aimMode.getValue();
        if (this.aimResult != null && pos.equals(this.aimResultPos) && facing == this.aimResultFacing) {
            AimResult cached = this.checkAimPoint(
                    pos, facing, this.aimResult.point, desiredYaw, desiredPitch, yawLimit, pitchLimit, reach, eye
            );
            if (cached != null) {
                this.aimResult = cached;
                return cached;
            }
        }
        if (mode <= 2) {
            AimResult preferred = this.checkAimPoint(
                    pos, facing, this.preferredAimPoint(pos, facing, mode),
                    desiredYaw, desiredPitch, yawLimit, pitchLimit, reach, eye
            );
            if (preferred != null) {
                return this.rememberAim(pos, facing, preferred);
            }
        }
        float moveYaw = this.getCurrentYaw();
        float reverseYaw = RotationUtil.wrapAngleDiff(moveYaw - 180.0F, desiredYaw);
        float diagonalYaw = RotationUtil.wrapAngleDiff(
                moveYaw - 135.0F * ((moveYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F), desiredYaw
        );
        float playerYaw = mc.thePlayer.rotationYaw;
        AimResult best = null;
        float bestScore = Float.MAX_VALUE;
        for (double[] point : Scaffold.aimPoints.get(facing)) {
            AimResult result = this.checkAimPoint(
                    pos, facing, point, desiredYaw, desiredPitch, yawLimit, pitchLimit, reach, eye
            );
            if (result == null) {
                continue;
            }
            float score = this.scoreAimPoint(mode, result, point, facing, reverseYaw, diagonalYaw, playerYaw, currentYaw, currentPitch);
            if (best == null || score < bestScore) {
                bestScore = score;
                best = result;
            }
        }
        return best == null ? null : this.rememberAim(pos, facing, best);
    }

    private AimResult rememberAim(BlockPos pos, EnumFacing facing, AimResult aim) {
        this.aimResultPos = pos;
        this.aimResultFacing = facing;
        this.aimResult = aim;
        return aim;
    }

    private AimResult checkAimPoint(
            BlockPos pos, EnumFacing facing, double[] point,
            float desiredYaw, float desiredPitch, float yawLimit, float pitchLimit,
            double reach, Vec3 eye
    ) {
        double targetX = (double) pos.getX() + point[0];
        double targetY = (double) pos.getY() + point[1];
        double targetZ = (double) pos.getZ() + point[2];
        float[] rotations = RotationUtil.getRotationsToRaw(
                targetX - eye.xCoord, targetY - eye.yCoord, targetZ - eye.zCoord, desiredYaw, desiredPitch
        );
        if (Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - desiredYaw)) > yawLimit) {
            return null;
        }
        if (Math.abs(rotations[1]) > pitchLimit) {
            return null;
        }
        MovingObjectPosition mop = RotationUtil.rayTraceFrom(eye, rotations[0], rotations[1], reach);
        if (mop != null
                && mop.typeOfHit == MovingObjectType.BLOCK
                && mop.getBlockPos().equals(pos)
                && mop.sideHit == facing) {
            return new AimResult(rotations[0], rotations[1], mop.hitVec, point);
        }
        return null;
    }

    private float scoreAimPoint(
            int mode, AimResult aim, double[] point, EnumFacing facing,
            float reverseYaw, float diagonalYaw, float playerYaw, float currentYaw, float currentPitch
    ) {
        switch (mode) {
            case 3:
                return Math.abs(MathHelper.wrapAngleTo180_float(aim.yaw - currentYaw)) + Math.abs(aim.pitch - currentPitch);
            case 4:
                return Math.abs(MathHelper.wrapAngleTo180_float(aim.yaw - reverseYaw));
            case 5:
                return Math.abs(MathHelper.wrapAngleTo180_float(aim.yaw - diagonalYaw));
            case 6:
                return Math.abs(MathHelper.wrapAngleTo180_float(aim.yaw - playerYaw));
            case 7:
                return -(float) Scaffold.facePointDistance(point, facing);
            default:
                return (float) Scaffold.facePointDistance(point, facing);
        }
    }

    private double[] preferredAimPoint(BlockPos pos, EnumFacing facing, int mode) {
        double fixed = Scaffold.faceFixed(facing);
        if (mode == 1) {
            if (this.randomPoint == null || !pos.equals(this.randomPointPos) || facing != this.randomPointFacing) {
                this.randomPointPos = pos;
                this.randomPointFacing = facing;
                this.randomPoint = Scaffold.facePoint(
                        facing,
                        RandomUtil.nextDouble(0.2, 0.8),
                        RandomUtil.nextDouble(0.2, 0.8),
                        fixed
                );
            }
            return this.randomPoint;
        }
        if (mode == 2) {
            long hash = (long) pos.getX() * 73856093L
                    ^ (long) pos.getY() * 19349663L
                    ^ (long) pos.getZ() * 83492791L
                    ^ (long) facing.ordinal() * 2654435761L;
            double u = 0.2 + 0.6 * (double) ((hash >>> 8) & 0xFFL) / 255.0;
            double v = 0.2 + 0.6 * (double) ((hash >>> 24) & 0xFFL) / 255.0;
            return Scaffold.facePoint(facing, u, v, fixed);
        }
        return Scaffold.faceCenters.get(facing);
    }

    private static double facePointDistance(double[] point, EnumFacing facing) {
        double du;
        double dv;
        switch (facing) {
            case NORTH:
            case SOUTH:
                du = point[0] - 0.5;
                dv = point[1] - 0.5;
                break;
            case EAST:
            case WEST:
                du = point[2] - 0.5;
                dv = point[1] - 0.5;
                break;
            default:
                du = point[0] - 0.5;
                dv = point[2] - 0.5;
        }
        return Math.sqrt(du * du + dv * dv);
    }

    private static double faceFixed(EnumFacing facing) {
        return facing == EnumFacing.UP || facing == EnumFacing.SOUTH || facing == EnumFacing.EAST ? 1.0 : 0.0;
    }

    private static double[] facePoint(EnumFacing facing, double u, double v, double fixed) {
        switch (facing) {
            case NORTH:
                return new double[]{u, v, fixed};
            case SOUTH:
                return new double[]{u, v, fixed};
            case WEST:
                return new double[]{fixed, v, u};
            case EAST:
                return new double[]{fixed, v, u};
            case UP:
                return new double[]{u, fixed, v};
            case DOWN:
                return new double[]{u, fixed, v};
            default:
                return new double[]{u, v, fixed};
        }
    }

    private static double[][] buildAimPoints(EnumFacing facing) {
        double fixed = Scaffold.faceFixed(facing);
        ArrayList<double[]> points = new ArrayList<>();
        for (double u : aimOffsets) {
            for (double v : aimOffsets) {
                points.add(Scaffold.facePoint(facing, u, v, fixed));
            }
        }
        return points.toArray(new double[0][]);
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (this.isEnabled()) {
            if (!mc.thePlayer.isCollidedHorizontally
                    && mc.thePlayer.hurtTime <= 5
                    && !mc.thePlayer.isPotionActive(Potion.jump)
                    && mc.gameSettings.keyBindJump.isKeyDown()
                    && ItemUtil.isHoldingBlock()) {
                int yState = (int) (mc.thePlayer.posY % 1.0 * 100.0);
                switch (this.tower.getValue()) {
                    case 1:
                        switch (this.towerTick) {
                            case 0:
                                if (mc.thePlayer.onGround) {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY = -0.0784000015258789;
                                }
                                return;
                            case 1:
                                if (yState == 0 && PlayerUtil.isAirBelow()) {
                                    this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                    this.towerTick = 2;
                                    mc.thePlayer.motionY = 0.42F;
                                    if (MoveUtil.isForwardPressed()) {
                                        MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
                                    } else {
                                        MoveUtil.setSpeed(0.0);
                                        event.setForward(0.0F);
                                        event.setStrafe(0.0F);
                                    }
                                    return;
                                } else {
                                    this.towerTick = 0;
                                    return;
                                }
                            case 2:
                                this.towerTick = 3;
                                mc.thePlayer.motionY = 0.75 - mc.thePlayer.posY % 1.0;
                                return;
                            case 3:
                                this.towerTick = 1;
                                mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                                return;
                            default:
                                this.towerTick = 0;
                                return;
                        }
                    case 2:
                        switch (this.towerTick) {
                            case 0:
                                if (mc.thePlayer.onGround) {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY = -0.0784000015258789;
                                }
                                return;
                            case 1:
                                if (yState == 0 && PlayerUtil.isAirBelow()) {
                                    this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                    if (!MoveUtil.isForwardPressed()) {
                                        this.towerDelay = 2;
                                        MoveUtil.setSpeed(0.0);
                                        event.setForward(0.0F);
                                        event.setStrafe(0.0F);
                                        EnumFacing facing = this.yawToFacing(MathHelper.wrapAngleTo180_float(this.yaw - 180.0F));
                                        double distance = this.distanceToEdge(facing);
                                        if (distance > 0.1) {
                                            if (mc.thePlayer.onGround) {
                                                Vec3i directionVec = facing.getDirectionVec();
                                                double offset = Math.min(this.getRandomOffset(), distance - 0.05);
                                                double jitter = RandomUtil.nextDouble(0.02, 0.03);
                                                AxisAlignedBB nextBox = mc.thePlayer
                                                        .getEntityBoundingBox()
                                                        .offset((double) directionVec.getX() * (offset - jitter), 0.0, (double) directionVec.getZ() * (offset - jitter));
                                                if (mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, nextBox).isEmpty()) {
                                                    mc.thePlayer.motionY = -0.0784000015258789;
                                                    mc.thePlayer
                                                            .setPosition(nextBox.minX + (nextBox.maxX - nextBox.minX) / 2.0, nextBox.minY, nextBox.minZ + (nextBox.maxZ - nextBox.minZ) / 2.0);
                                                }
                                                return;
                                            }
                                        } else {
                                            this.towerTick = 2;
                                            this.targetFacing = facing;
                                            mc.thePlayer.motionY = 0.42F;
                                        }
                                        return;
                                    } else {
                                        this.towerTick = 2;
                                        this.towerDelay++;
                                        mc.thePlayer.motionY = 0.42F;
                                        MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
                                        return;
                                    }
                                } else {
                                    this.towerTick = 0;
                                    this.towerDelay = 0;
                                    return;
                                }
                            case 2:
                                this.towerTick = 3;
                                mc.thePlayer.motionY = mc.thePlayer.motionY - RandomUtil.nextDouble(0.00101, 0.00109);
                                return;
                            case 3:
                                if (this.towerDelay >= 4) {
                                    this.towerTick = 4;
                                    this.towerDelay = 0;
                                } else {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                                }
                                return;
                            case 4:
                                this.towerTick = 5;
                                return;
                            case 5:
                                if (!PlayerUtil.isAirBelow()) {
                                    this.towerTick = 0;
                                } else {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY -= 0.08;
                                    mc.thePlayer.motionY *= 0.98F;
                                    mc.thePlayer.motionY -= 0.08;
                                    mc.thePlayer.motionY *= 0.98F;
                                }
                                return;
                            default:
                                this.towerTick = 0;
                                this.towerDelay = 0;
                                return;
                        }
                    default:
                        this.towerTick = 0;
                        this.towerDelay = 0;
                }
            } else {
                this.towerTick = 0;
                this.towerDelay = 0;
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.moveFix.getValue() == 1
                    && RotationState.isActived()
                    && RotationState.getPriority() == 3.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
            if (mc.thePlayer.onGround && this.stage > 0 && MoveUtil.isForwardPressed()) {
                mc.thePlayer.movementInput.jump = true;
            }
        }
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.isEnabled()) {
            float speed = this.getSpeed();
            if (speed != 1.0F) {
                if (mc.thePlayer.movementInput.moveForward != 0.0F && mc.thePlayer.movementInput.moveStrafe != 0.0F) {
                    mc.thePlayer.movementInput.moveForward = mc.thePlayer.movementInput.moveForward * (1.0F / (float) Math.sqrt(2.0));
                    mc.thePlayer.movementInput.moveStrafe = mc.thePlayer.movementInput.moveStrafe * (1.0F / (float) Math.sqrt(2.0));
                }
                mc.thePlayer.movementInput.moveForward *= speed;
                mc.thePlayer.movementInput.moveStrafe *= speed;
            }
            if (this.shouldStopSprint()) {
                mc.thePlayer.setSprinting(false);
            }
        }
    }

    @EventTarget
    public void onSafeWalk(SafeWalkEvent event) {
        if (this.isEnabled() && this.safeWalk.getValue()) {
            if (mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0 && PlayerUtil.canMove(mc.thePlayer.motionX, mc.thePlayer.motionZ, -1.0)) {
                event.setSafeWalk(true);
            }
        }
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (this.isEnabled()) {
            if (this.blockCounter.getValue()) {
                int count = 0;
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                    if (stack != null && stack.stackSize > 0) {
                        Item item = stack.getItem();
                        if (item instanceof ItemBlock) {
                            Block block = ((ItemBlock) item).getBlock();
                            if (!BlockUtil.isInteractable(block) && BlockUtil.isSolid(block)) {
                                count += stack.stackSize;
                            }
                        }
                    }
                }
                HUD hud = (HUD) SkidMyau.moduleManager.modules.get(HUD.class);
                float scale = hud.scale.getValue();
                GlStateManager.pushMatrix();
                GlStateManager.scale(scale, scale, 0.0F);
                GlStateManager.disableDepth();
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                mc.fontRendererObj
                        .drawString(
                                String.format("%d block%s left", count, count != 1 ? "s" : ""),
                                ((float) new ScaledResolution(mc).getScaledWidth() / 2.0F + (float) mc.fontRendererObj.FONT_HEIGHT * 1.5F) / scale,
                                (float) new ScaledResolution(mc).getScaledHeight() / 2.0F / scale - (float) mc.fontRendererObj.FONT_HEIGHT / 2.0F + 1.0F,
                                (count > 0 ? Color.WHITE.getRGB() : new Color(255, 85, 85).getRGB()) | -1090519040,
                                hud.shadow.getValue()
                        );
                GlStateManager.disableBlend();
                GlStateManager.enableDepth();
                GlStateManager.popMatrix();
            }
        }
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) {
            this.lastSlot = event.setSlot(this.lastSlot);
            event.setCancelled(true);
        }
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer != null) {
            this.lastSlot = mc.thePlayer.inventory.currentItem;
        } else {
            this.lastSlot = -1;
        }
        this.blockCount = -1;
        this.rotationTick = 3;
        this.yaw = -180.0F;
        this.pitch = 0.0F;
        this.towerTick = 0;
        this.towerDelay = 0;
        this.towering = false;
        this.pendingBlockData = null;
        this.pendingHitVec = null;
        this.placedThisTick = false;
        this.lastPlaceTime = 0L;
        this.randomPointPos = null;
        this.randomPointFacing = null;
        this.randomPoint = null;
        this.aimResultPos = null;
        this.aimResultFacing = null;
        this.aimResult = null;
        this.stableBlockData = null;
        this.stableBlockDataAge = 0;
        this.lastAimTick = 0;
        this.tickCounter = 0;
        this.nextClickTime = 0L;
        this.clickPatternIndex = 0;
        this.dragBurst = 0;
    }

    @Override
    public void onDisabled() {
        this.pendingBlockData = null;
        this.pendingHitVec = null;
        this.placedThisTick = false;
        if (mc.thePlayer != null && this.lastSlot != -1) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
    }

    public static class AimResult {
        private final float yaw;
        private final float pitch;
        private final Vec3 hitVec;
        private final double[] point;

        public AimResult(float yaw, float pitch, Vec3 hitVec, double[] point) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.hitVec = hitVec;
            this.point = point;
        }
    }

    public static class BlockData {
        private final BlockPos blockPos;
        private final EnumFacing facing;

        public BlockData(BlockPos blockPos, EnumFacing enumFacing) {
            this.blockPos = blockPos;
            this.facing = enumFacing;
        }

        public BlockPos blockPos() {
            return this.blockPos;
        }

        public EnumFacing facing() {
            return this.facing;
        }
    }
}
