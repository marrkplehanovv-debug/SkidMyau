package skidmyau.module.modules;

import skidmyau.SkidMyau;
import skidmyau.event.EventTarget;
import skidmyau.event.types.EventType;
import skidmyau.event.types.Priority;
import skidmyau.events.*;
import skidmyau.management.RotationState;
import skidmyau.mixin.IAccessorRenderManager;
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
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
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

public class Scaffold extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double[] aimOffsets = new double[]{0.25, 0.5, 0.75};
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
    private Vec3 debugEye = null;
    private Vec3 debugHit = null;
    private BlockPos debugSupport = null;
    private EnumFacing debugSide = null;
    private BlockPos debugPlacePos = null;
    private BlockPos debugTarget = null;
    private boolean debugValid = false;
    private String debugReason = "";
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
    public final IntProperty clickCpsMin = new IntProperty("click-cps-min", 10, 1, 20, () -> this.clickMode.getValue() != 0);
    public final IntProperty clickCpsMax = new IntProperty("click-cps-max", 14, 1, 20, () -> this.clickMode.getValue() != 0);
    public final BooleanProperty debug = new BooleanProperty("debug", false);
    public final IntProperty placeAhead = new IntProperty("place-ahead", 10, 0, 25);
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

    /**
     * Правило постановки: блок никогда не ставится выше ног.
     * Ниже ног - обычный мост, можно всегда.
     * Ровно на уровне ног ("под себя, чтобы подняться") - только если это
     * реальный прыжок (свой или скаффолда) или под ногами есть настоящий зазор.
     * Одной включённой настройки keep-y/tower для этого недостаточно.
     */
    private boolean isPlacementAllowed(BlockPos placed) {
        int feetY = MathHelper.floor_double(mc.thePlayer.posY);
        if (placed.getY() < feetY) {
            return true;
        }
        if (placed.getY() > feetY) {
            return false;
        }
        return this.isPlayerJump() || this.hasGapBelow() || this.isScaffoldJump();
    }

    private boolean hasGapBelow() {
        double feet = mc.thePlayer.posY - Math.floor(mc.thePlayer.posY);
        if (feet > 0.05) {
            return true;
        }
        return BlockUtil.isReplaceable(new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ)
        ));
    }

    /**
     * Идёт реальный подъём (tower/telly): блок надо ставить ПОД ноги на текущей
     * высоте, иначе башня не растёт.
     *
     * keep-y сюда НЕ входит: он как раз должен держать один и тот же уровень,
     * даже когда игрок прыгает.
     */
    private boolean isTowerAscending() {
        int towerMode = this.tower.getValue();
        if (towerMode == 3) {
            return mc.gameSettings.keyBindJump.isKeyDown() || this.towering;
        }
        if (towerMode != 0) {
            // VANILLA/EXTRA: цикл прыжка уже запущен в onStrafe.
            return this.towerTick > 0;
        }
        // keep-y TELLY тоже поднимает вверх.
        return this.keepY.getValue() == 3 && this.stage > 0;
    }

    /**
     * Прыжок, который делает сам скаффолд (tower/telly/keep-y) прямо сейчас.
     * Это НЕ просто включённая настройка: иначе клиент считает прыжком игрока
     * любой тик и выбирает грань на уровень выше.
     */
    private boolean isScaffoldJump() {
        return this.isTowerAscending()
                || this.towering
                || this.isTowering()
                || (this.keepY.getValue() != 0 && this.stage > 0 && !mc.thePlayer.onGround);
    }

    /**
     * Игрок прыгнул сам (не скаффолд) и сейчас летит вверх.
     */
    private boolean isPlayerJump() {
        return !mc.thePlayer.onGround
                && mc.thePlayer.motionY > 0.0
                && !this.isScaffoldJump();
    }

    /**
     * Скор одной грани опоры.
     * Приоритет: грань перед камерой игрока (а не перед "клиентским" взглядом модуля),
     * которую реально видно из предсказанных глаз. Грань за камерой не отбрасывается:
     * у цели должна быть хотя бы одна грань, иначе чит выбирает клетку выше и
     * true-sight её отбивает. NaN = грань не достать.
     */
    private double scoreFace(BlockPos support, EnumFacing facing, Vec3 eye, Vec3 look, double reach, boolean visible) {
        Vec3 dir = faceCenter(support, facing).subtract(eye);
        double length = dir.lengthVector();
        if (length > reach + 1.0) {
            return Double.NaN;
        }
        double dot = length < 1.0E-6
                ? 0.0
                : (dir.xCoord * look.xCoord + dir.yCoord * look.yCoord + dir.zCoord * look.zCoord) / length;
        // Грань перед камерой приоритетнее, но грань за спиной тоже допустима
        // (telly/backwards, а также грань у самых ног при взгляде вперёд).
        double score = dot * 4.0 + (dot > 0.0 ? 6.0 : -2.0);
        // Видимая грань всегда важнее невидимой: именно невидимая "грань чуть выше"
        // (луч упирается в верхнюю грань того же блока) и ломала true-sight.
        score += visible ? 25.0 : -10.0;
        return score;
    }

    private static Vec3 faceCenter(BlockPos support, EnumFacing facing) {
        return new Vec3(
                (double) support.getX() + 0.5 + (double) facing.getFrontOffsetX() * 0.5,
                (double) support.getY() + 0.5 + (double) facing.getFrontOffsetY() * 0.5,
                (double) support.getZ() + 0.5 + (double) facing.getFrontOffsetZ() * 0.5
        );
    }

    /**
     * Реальный взгляд игрока (камера), а не ротация, которую отправляет модуль.
     * rotationYaw/rotationPitch во время PRE - это как раз то, что игрок видит у себя.
     */
    private float getPlayerYaw() {
        return mc.getRenderViewEntity() != null
                ? mc.getRenderViewEntity().rotationYaw
                : mc.thePlayer.rotationYaw;
    }

    private float getPlayerPitch() {
        return mc.getRenderViewEntity() != null
                ? mc.getRenderViewEntity().rotationPitch
                : mc.thePlayer.rotationPitch;
    }

    private boolean isFaceVisible(Vec3 eye, BlockPos pos, EnumFacing facing, Vec3 dir, double reach) {
        float[] rotations = RotationUtil.getRotationsToRaw(
                dir.xCoord, dir.yCoord, dir.zCoord, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch
        );
        MovingObjectPosition mop = RotationUtil.rayTraceFrom(eye, rotations[0], rotations[1], reach);
        return mop != null
                && mop.typeOfHit == MovingObjectType.BLOCK
                && mop.getBlockPos().equals(pos)
                && mop.sideHit == facing;
    }

    /**
     * Уровень ног для моста.
     *
     * Обычный мост и keep-y всегда держат уровень последнего наземного тика
     * (startY), даже если игрок прыгнул. Иначе в прыжке блок уезжал на уровень
     * выше, и keep-y "поднимал" мост вместо того, чтобы держать высоту.
     *
     * Только реальный подъём (tower/telly) берёт текущие ноги, чтобы блок
     * ставился ПОД игрока и можно было подниматься.
     */
    private int getTargetFeetLevel() {
        int floor = MathHelper.floor_double(mc.thePlayer.posY);
        // Свой прыжок и подъём башни ставят блок прямо под ноги, чтобы подняться.
        // В остальном в воздухе держим уровень последнего наземного тика (startY),
        // чтобы мост не уезжал ни вверх при прыжке, ни вниз при падении (keep-y).
        if (this.isTowerAscending() || this.isPlayerJump()) {
            return floor;
        }
        int level = mc.thePlayer.onGround ? floor : this.startY;
        if (level > 255 || floor - level > 6) {
            level = floor;
        }
        return level;
    }

    /**
     * Куда чит ставит блок.
     *
     * Базовая цель - клетка ПОД игроком на уровне моста: туда игрок наступит
     * или туда упадёт. Это даёт ровный мост в одну клетку и не оставляет
     * "взлётной полосы" из лишних блоков впереди.
     *
     * Если под игроком уже стоит блок, разрешаем поставить РОВНО ОДНУ клетку
     * впереди и только когда игрок прошёл середину своей клетки в сторону
     * движения. Так блок появляется заранее, но мост не убегает вперёд.
     *
     * place-ahead оставлен как запас дальности: им ограничивается, насколько
     * далеко вперёд вообще разрешено смотреть.
     */
    private BlockPos getTargetPos() {
        int targetY = this.getTargetFeetLevel() - 1;
        int cellX = MathHelper.floor_double(mc.thePlayer.posX);
        int cellZ = MathHelper.floor_double(mc.thePlayer.posZ);
        // Клетка под игроком - главный приоритет (tower/keep-y/край/падение).
        double motionX = mc.thePlayer.motionX;
        double motionZ = mc.thePlayer.motionZ;
        double speed = Math.sqrt(motionX * motionX + motionZ * motionZ);
        // Стоим на месте и не прыгаем - не ставим блок в никуда (лишняя постановка).
        // Прыжок игрока/башни - исключение: блок нужен прямо под ногами для подъёма.
        if (speed <= 0.05 && !this.isPlayerJump() && !this.isTowerAscending()) {
            return null;
        }
        BlockPos ownCell = new BlockPos(cellX, targetY, cellZ);
        if (this.isPlaceableTarget(ownCell)) {
            return ownCell;
        }
        // Пред-постановка одной клетки впереди: только когда игрок уже прошёл
        // середину текущей клетки по ведущей оси движения.
        if (Math.abs(motionX) >= Math.abs(motionZ)) {
            double local = mc.thePlayer.posX - (double) cellX;
            if (motionX >= 0.0 ? local < 0.5 : local > 0.5) {
                return null;
            }
            cellX += motionX >= 0.0 ? 1 : -1;
        } else {
            double local = mc.thePlayer.posZ - (double) cellZ;
            if (motionZ >= 0.0 ? local < 0.5 : local > 0.5) {
                return null;
            }
            cellZ += motionZ >= 0.0 ? 1 : -1;
        }
        BlockPos nextCell = new BlockPos(cellX, targetY, cellZ);
        return this.isPlaceableTarget(nextCell) ? nextCell : null;
    }

    private boolean isPlaceableTarget(BlockPos pos) {
        return BlockUtil.isReplaceable(pos)
                && !BlockUtil.isInteractable(pos)
                && !this.intersectsPlayer(pos);
    }

    private BlockData getBlockData() {
        BlockPos targetPos = this.getTargetPos();
        if (targetPos == null) {
            this.stableBlockData = null;
            this.stableBlockDataAge = 0;
            return null;
        }
        if (this.stableBlockData != null
                && this.stableBlockDataAge < 3
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
        // Ставим только в предсказанную клетку. Любая другая - лишний блок.
        if (!placed.equals(targetPos)) {
            return false;
        }
        if (!BlockUtil.isReplaceable(placed)) {
            return false;
        }
        if (!this.isPlacementAllowed(placed)) {
            return false;
        }
        if (this.intersectsPlayer(placed)) {
            return false;
        }
        double reach = (double) mc.playerController.getBlockReachDistance() + 0.5;
        if (mc.thePlayer.getDistance(
                (double) support.getX() + 0.5, (double) support.getY() + 0.5, (double) support.getZ() + 0.5
        ) > reach) {
            return false;
        }
        return this.stage == 0 || this.shouldKeepY || support.getY() < this.startY;
    }

    /**
     * Ищет ОПОРУ и ГРАНЬ для РОВНО ОДНОЙ предсказанной клетки (targetPos).
     * Никаких соседних клеток: из-за старого поиска "любая опора рядом"
     * и появлялись лишние блоки / мост шириной в 2 блока.
     */
    private BlockData findBlockData(BlockPos targetPos) {
        if (targetPos == null
                || !BlockUtil.isReplaceable(targetPos)
                || BlockUtil.isInteractable(targetPos)
                || !this.isPlacementAllowed(targetPos)
                || this.intersectsPlayer(targetPos)) {
            return null;
        }
        Vec3 eye = this.getPredictedEyes();
        Vec3 look = RotationUtil.getLookVec(this.getPlayerYaw(), this.getPlayerPitch());
        double reach = (double) mc.playerController.getBlockReachDistance();
        BlockData best = null;
        double bestScore = -Double.MAX_VALUE;
        for (EnumFacing facing : EnumFacing.VALUES) {
            // Клик по нижней грани опоры сверху не используем.
            if (facing == EnumFacing.DOWN) {
                continue;
            }
            BlockPos support = targetPos.offset(facing.getOpposite());
            if (BlockUtil.isReplaceable(support) || BlockUtil.isInteractable(support)) {
                continue;
            }
            if (mc.thePlayer.getDistance(
                    (double) support.getX() + 0.5, (double) support.getY() + 0.5, (double) support.getZ() + 0.5
            ) > reach + 0.5) {
                continue;
            }
            if (this.stage != 0 && !this.shouldKeepY && support.getY() >= this.startY) {
                continue;
            }
            double faceScore = this.scoreFace(
                    support, facing, eye, look, reach,
                    this.isFaceVisible(eye, support, facing, faceCenter(support, facing).subtract(eye), reach)
            );
            if (Double.isNaN(faceScore)) {
                continue;
            }
            if (best == null || faceScore > bestScore) {
                bestScore = faceScore;
                best = new BlockData(support, facing);
            }
        }
        return best;
    }

    private void place(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        if (this.placedThisTick || vec3 == null) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        // 15 мс допуска: тик иногда чуть короче 50 мс, иначе теряется целый тик постановки.
        boolean ready = currentTime + 15L >= this.nextClickTime;
        boolean urgent = this.isUrgentPlacement()
                && currentTime - this.lastPlaceTime >= Math.max(50L, (long) this.placeDelay.getValue());
        if (!ready && !urgent) {
            return;
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
     * Задержка до следующего "клика". База берётся из CPS-диапазона
     * (как в LiquidBounce), а режим задаёт рисунок: бабочка, дабл-клик, драг.
     * place-delay остаётся нижней границей, тик (50 мс) - физическим пределом.
     */
    private long getClickDelay() {
        long base = Math.max(50L, (long) this.placeDelay.getValue());
        int mode = this.clickMode.getValue();
        if (mode == 0) {
            return base;
        }
        double minCps = this.clickCpsMin.getValue();
        double maxCps = this.clickCpsMax.getValue();
        if (maxCps < minCps) {
            maxCps = minCps;
        }
        double avgPeriod = 1000.0 / ((minCps + maxCps) * 0.5);
        double fastPeriod = 1000.0 / maxCps;
        long delay;
        switch (mode) {
            case 1:
                // Butterfly: два "пальца" - короткий интервал, потом длинный
                this.clickPatternIndex++;
                delay = Math.round(this.clickPatternIndex % 2 == 0 ? avgPeriod * 1.35 : avgPeriod * 0.65);
                break;
            case 2:
                // DoubleClick: быстрый дубль и пауза
                this.clickPatternIndex++;
                delay = Math.round(this.clickPatternIndex % 3 == 2 ? avgPeriod * 1.6 : avgPeriod * 0.7);
                break;
            case 3:
                // DragClick: серия быстрых кликов, затем отдых (средний CPS сохраняется)
                if (this.dragBurst <= 0) {
                    this.dragBurst = (int) RandomUtil.nextLong(4L, 8L);
                    delay = Math.round(fastPeriod * this.dragBurst * 2.0);
                } else {
                    this.dragBurst--;
                    delay = Math.round(fastPeriod * 0.9);
                }
                break;
            default:
                delay = Math.round(avgPeriod);
        }
        delay += (long) RandomUtil.nextLong(-5L, 5L);
        return Math.max(base, Math.max(40L, delay));
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
                if (this.keepY.getValue() == 2 && this.stage > 0 && !mc.thePlayer.onGround) {
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
                    if (aim != null && aim.hitVec != null) {
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
            this.recordDebugRayOnly(yaw, pitch);
            return;
        }
        this.placeTarget(blockData, hitVec, yaw, pitch);
    }

    /**
     * Постановка в предсказанную клетку.
     *
     * true-sight разрешение выдаёт PRE-рейкаст из ПРЕДСКАЗАННЫХ глаз (aim.hitVec) -
     * та же точка, из которой строился прицел. POST-рейкаст из сдвинутой за тик
     * позиции давал ложные отказы ("other face"), и у края блок вообще не ставился.
     */
    private void placeTarget(BlockData target, Vec3 aimHit, float yaw, float pitch) {
        double reach = (double) mc.playerController.getBlockReachDistance();
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = RotationUtil.getLookVec(yaw, pitch);
        MovingObjectPosition mop = RotationUtil.rayTraceFrom(eye, yaw, pitch, reach);
        // Дебаг всегда показывает ПРЕДСКАЗАННУЮ грань, а не то, куда случайно попал луч.
        BlockPos support = target.blockPos();
        EnumFacing side = target.facing();
        BlockPos placePos = support.offset(side);
        this.debugEye = eye;
        this.debugHit = mop != null && mop.typeOfHit == MovingObjectType.BLOCK
                ? mop.hitVec
                : eye.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        this.debugSupport = support;
        this.debugSide = side;
        this.debugPlacePos = placePos;
        this.debugTarget = support;
        this.debugValid = false;
        if (!ItemUtil.isHoldingBlock() || this.blockCount <= 0) {
            this.debugReason = "no block in hand";
            return;
        }
        if (!BlockUtil.isReplaceable(placePos) || BlockUtil.isInteractable(placePos)) {
            this.debugReason = "place pos not free";
            return;
        }
        if (this.intersectsPlayer(placePos)) {
            this.debugReason = "place pos inside player";
            return;
        }
        if (!this.isPlacementAllowed(placePos)) {
            this.debugReason = "place pos not allowed";
            return;
        }
        if (BlockUtil.isReplaceable(support) || BlockUtil.isInteractable(support)) {
            this.debugReason = "support not solid";
            return;
        }
        BlockPos targetPos = this.getTargetPos();
        if (placePos.distanceSqToCenter(
                (double) targetPos.getX() + 0.5, (double) targetPos.getY() + 0.5, (double) targetPos.getZ() + 0.5
        ) > 16.0) {
            this.debugReason = "place pos too far from target";
            return;
        }
        Vec3 hit;
        if (this.trueSight.getValue()) {
            // Граница видна из предсказанных глаз (PRE-рейкаст) - иначе не ставим.
            if (aimHit == null) {
                this.debugReason = "true-sight: face not visible";
                return;
            }
        }
        // Вектор попадания пакета считаем от текущих глаз и отправляемой ротации -
        // иначе RotationPlace/AimModulo360 фиксирует несоответствие ротации и вектора.
        hit = BlockUtil.getHitVec(support, side, yaw, pitch);
        if (hit == null) {
            this.debugReason = "no hit vec";
            return;
        }
        this.debugValid = true;
        this.debugReason = "ok";
        this.place(support, side, hit);
    }

    /**
     * Луч рисуем всегда, даже когда цели нет: видно, куда реально смотрит сервер.
     */
    private void recordDebugRayOnly(float yaw, float pitch) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = RotationUtil.getLookVec(yaw, pitch);
        double reach = (double) mc.playerController.getBlockReachDistance();
        this.debugEye = eye;
        this.debugHit = eye.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        this.debugSupport = null;
        this.debugSide = null;
        this.debugPlacePos = null;
        this.debugTarget = null;
        this.debugValid = false;
        this.debugReason = "no target";
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
        double dy = 0.0;
        if (!mc.thePlayer.onGround) {
            dy = mc.thePlayer.motionY;
            if (!mc.thePlayer.capabilities.isFlying && !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava()) {
                // гравитация применяется до движения в этом же тике
                dy = (dy - 0.08) * 0.98;
            }
        }
        return new Vec3(
                mc.thePlayer.posX + mc.thePlayer.motionX,
                mc.thePlayer.posY + dy + (double) mc.thePlayer.getEyeHeight(),
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
        AimResult preferred = null;
        if (this.aimResult != null && pos.equals(this.aimResultPos) && facing == this.aimResultFacing) {
            preferred = this.checkAimPoint(
                    pos, facing, this.aimResult.point, desiredYaw, desiredPitch, yawLimit, pitchLimit, reach, eye
            );
            if (preferred != null && preferred.visible) {
                this.aimResult = preferred;
                return preferred;
            }
        }
        if (mode <= 2) {
            AimResult fixedPoint = this.checkAimPoint(
                    pos, facing, this.preferredAimPoint(pos, facing, mode),
                    desiredYaw, desiredPitch, yawLimit, pitchLimit, reach, eye
            );
            if (fixedPoint != null) {
                if (fixedPoint.visible) {
                    return this.rememberAim(pos, facing, fixedPoint);
                }
                if (preferred == null) {
                    preferred = fixedPoint;
                }
            }
        }
        float moveYaw = this.getCurrentYaw();
        float reverseYaw = RotationUtil.wrapAngleDiff(moveYaw - 180.0F, desiredYaw);
        float diagonalYaw = RotationUtil.wrapAngleDiff(
                moveYaw - 135.0F * ((moveYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F), desiredYaw
        );
        float playerYaw = this.getPlayerYaw();
        AimResult best = null;
        float bestScore = Float.MAX_VALUE;
        for (double[] point : Scaffold.aimPoints.get(facing)) {
            AimResult result = this.checkAimPoint(
                    pos, facing, point, desiredYaw, desiredPitch, yawLimit, pitchLimit, reach, eye
            );
            if (result == null || !result.visible) {
                continue;
            }
            // Center/Random/Stabilized: не дёргаем голову на другой конец грани,
            // если лучшая точка требует заметного отворота от взгляда игрока.
            // Точки одной грани рядом, поэтому нормальная доводка проходит этот фильтр.
            if (mode <= 2) {
                float deviation = Math.abs(MathHelper.wrapAngleTo180_float(result.yaw - desiredYaw))
                        + Math.abs(result.pitch - desiredPitch);
                if (deviation > 30.0F) {
                    continue;
                }
            }
            float score = this.scoreAimPoint(mode, result, point, facing, reverseYaw, diagonalYaw, playerYaw, currentYaw, currentPitch);
            if (best == null || score < bestScore) {
                bestScore = score;
                best = result;
            }
        }
        // Если видимой точки нет, всё равно наводимся на грань: иначе прицел "паркуется"
        // и постановка ждёт лишние тики. Реальную постановку всё равно решает POST-рейкаст.
        AimResult chosen = best != null ? best : preferred;
        return chosen == null ? null : this.rememberAim(pos, facing, chosen);
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
        boolean visible = mop != null
                && mop.typeOfHit == MovingObjectType.BLOCK
                && mop.getBlockPos().equals(pos)
                && mop.sideHit == facing;
        return new AimResult(rotations[0], rotations[1], visible ? mop.hitVec : null, point, visible);
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
        // Center: берем точку, куда смотрит САМ игрок, зажатую внутрь грани.
        // Тогда ротация минимальна, а луч гарантированно попадает в нужную грань.
        double[] lookPoint = this.lookPointOnFace(pos, facing);
        return lookPoint != null ? lookPoint : Scaffold.faceCenters.get(facing);
    }

    /**
     * Пересечение взгляда игрока с плоскостью нужной грани, зажатое внутрь грани.
     * Если игрок смотрит мимо грани - возвращает null, и берётся центр грани.
     * Возвращает точку в локальных координатах блока (как facePoint).
     */
    private double[] lookPointOnFace(BlockPos pos, EnumFacing facing) {
        Vec3 eye = this.getPredictedEyes();
        Vec3 look = RotationUtil.getLookVec(this.getPlayerYaw(), this.getPlayerPitch());
        double plane;
        double denom;
        switch (facing) {
            case NORTH:
                plane = pos.getZ();
                denom = look.zCoord;
                break;
            case SOUTH:
                plane = pos.getZ() + 1.0;
                denom = look.zCoord;
                break;
            case WEST:
                plane = pos.getX();
                denom = look.xCoord;
                break;
            case EAST:
                plane = pos.getX() + 1.0;
                denom = look.xCoord;
                break;
            case UP:
                plane = pos.getY() + 1.0;
                denom = look.yCoord;
                break;
            case DOWN:
                plane = pos.getY();
                denom = look.yCoord;
                break;
            default:
                return null;
        }
        if (Math.abs(denom) < 1.0E-4) {
            return null;
        }
        double eyeCoord = facing == EnumFacing.NORTH || facing == EnumFacing.SOUTH
                ? eye.zCoord
                : facing == EnumFacing.WEST || facing == EnumFacing.EAST ? eye.xCoord : eye.yCoord;
        double t = (plane - eyeCoord) / denom;
        if (t <= 0.0) {
            return null;
        }
        double hitX = eye.xCoord + look.xCoord * t;
        double hitY = eye.yCoord + look.yCoord * t;
        double hitZ = eye.zCoord + look.zCoord * t;
        double rawU;
        double rawV;
        switch (facing) {
            case NORTH:
            case SOUTH:
                rawU = hitX - (double) pos.getX();
                rawV = hitY - (double) pos.getY();
                break;
            case WEST:
            case EAST:
                rawU = hitZ - (double) pos.getZ();
                rawV = hitY - (double) pos.getY();
                break;
            default:
                rawU = hitX - (double) pos.getX();
                rawV = hitZ - (double) pos.getZ();
        }
        // Игрок смотрит не в эту грань (пересечение ушло далеко за её пределы):
        // тогда точка взгляда не годится, иначе прицел уходит "чуть выше" грани.
        // В этом случае вызывающий код возьмёт центр грани.
        if (rawU < 0.0 || rawU > 1.0 || rawV < 0.0 || rawV > 1.0) {
            return null;
        }
        // Отступ от краёв: луч не должен скользить по кромке грани,
        // иначе он "перепрыгивает" на соседнюю грань/блок (визуально - прицел чуть выше).
        double lo = 0.2;
        double hi = 0.8;
        // По вертикали держимся ближе к середине грани - это убирает наведение "чуть выше".
        double vlo = 0.3;
        double vhi = 0.7;
        switch (facing) {
            case NORTH:
            case SOUTH:
                return new double[]{
                        MathHelper.clamp_double(rawU, lo, hi),
                        MathHelper.clamp_double(rawV, vlo, vhi),
                        plane - (double) pos.getZ()
                };
            case WEST:
            case EAST:
                return new double[]{
                        plane - (double) pos.getX(),
                        MathHelper.clamp_double(rawV, vlo, vhi),
                        MathHelper.clamp_double(rawU, lo, hi)
                };
            default:
                return new double[]{
                        MathHelper.clamp_double(rawU, lo, hi),
                        plane - (double) pos.getY(),
                        MathHelper.clamp_double(rawV, lo, hi)
                };
        }
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
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || !this.debug.getValue()) {
            return;
        }
        IAccessorRenderManager rm = (IAccessorRenderManager) mc.getRenderManager();
        double rx = rm.getRenderPosX();
        double ry = rm.getRenderPosY();
        double rz = rm.getRenderPosZ();
        RenderUtil.enableRenderState();
        if (this.debugSupport != null && this.debugSide != null) {
            // Блок, в который смотрит true-sight
            RenderUtil.drawBoundingBox(
                    new AxisAlignedBB(
                            this.debugSupport.getX(), this.debugSupport.getY(), this.debugSupport.getZ(),
                            this.debugSupport.getX() + 1.0, this.debugSupport.getY() + 1.0, this.debugSupport.getZ() + 1.0
                    ).offset(-rx, -ry, -rz),
                    255, 215, 60, 200, 2.0F
            );
            // Грань, в которую уйдёт блок
            RenderUtil.drawFilledBox(
                    Scaffold.faceBox(this.debugSupport, this.debugSide).offset(-rx, -ry, -rz),
                    255, 215, 60
            );
        }
        if (this.debugPlacePos != null) {
            int red = this.debugValid ? 90 : 255;
            int green = this.debugValid ? 255 : 70;
            RenderUtil.drawBoundingBox(
                    new AxisAlignedBB(
                            this.debugPlacePos.getX(), this.debugPlacePos.getY(), this.debugPlacePos.getZ(),
                            this.debugPlacePos.getX() + 1.0, this.debugPlacePos.getY() + 1.0, this.debugPlacePos.getZ() + 1.0
                    ).offset(-rx, -ry, -rz),
                    red, green, 80, this.debugValid ? 200 : 110, 2.0F
            );
        }
        if (this.debugEye != null && this.debugHit != null) {
            // Линия от головы до предполагаемого блока (то, что реально видит сервер)
            int red = this.debugValid ? 90 : 255;
            int green = this.debugValid ? 255 : 70;
            this.drawDebugLine(
                    new Vec3(this.debugEye.xCoord - rx, this.debugEye.yCoord - ry, this.debugEye.zCoord - rz),
                    new Vec3(this.debugHit.xCoord - rx, this.debugHit.yCoord - ry, this.debugHit.zCoord - rz),
                    red, green, 80
            );
        }
        RenderUtil.disableRenderState();
    }

    private void drawDebugLine(Vec3 from, Vec3 to, int red, int green, int blue) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        GL11.glLineWidth(2.0F);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        renderer.begin(3, DefaultVertexFormats.POSITION_COLOR);
        renderer.pos(from.xCoord, from.yCoord, from.zCoord).color(red, green, blue, 255).endVertex();
        renderer.pos(to.xCoord, to.yCoord, to.zCoord).color(red, green, blue, 255).endVertex();
        tessellator.draw();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.0F);
    }

    private static AxisAlignedBB faceBox(BlockPos pos, EnumFacing side) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        double e = 0.01;
        switch (side) {
            case UP:
                return new AxisAlignedBB(x, y + 1.0 - e, z, x + 1.0, y + 1.0 + e, z + 1.0);
            case DOWN:
                return new AxisAlignedBB(x, y - e, z, x + 1.0, y + e, z + 1.0);
            case NORTH:
                return new AxisAlignedBB(x, y, z - e, x + 1.0, y + 1.0, z + e);
            case SOUTH:
                return new AxisAlignedBB(x, y, z + 1.0 - e, x + 1.0, y + 1.0, z + 1.0 + e);
            case WEST:
                return new AxisAlignedBB(x - e, y, z, x + e, y + 1.0, z + 1.0);
            case EAST:
                return new AxisAlignedBB(x + 1.0 - e, y, z, x + 1.0 + e, y + 1.0, z + 1.0);
            default:
                return new AxisAlignedBB(x, y, z, x + 1.0, y + 1.0, z + 1.0);
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
            if (this.debug.getValue()) {
                this.renderDebugText();
            }
        }
    }

    private void renderDebugText() {
        ScaledResolution resolution = new ScaledResolution(mc);
        float x = 4.0F;
        float y = (float) resolution.getScaledHeight() - 10.0F;
        String[] lines = new String[]{
                String.format("true-sight: %s", this.debugReason),
                String.format("eye %.2f %.2f %.2f", this.debugEye == null ? 0.0 : this.debugEye.xCoord,
                        this.debugEye == null ? 0.0 : this.debugEye.yCoord,
                        this.debugEye == null ? 0.0 : this.debugEye.zCoord),
                String.format("target: %s side: %s", this.debugTarget == null ? "-" : this.debugTarget.toString(),
                        this.debugSide == null ? "-" : this.debugSide.name()),
                String.format("place: %s", this.debugPlacePos == null ? "-" : this.debugPlacePos.toString()),
                String.format("click: %d ms | keep-y %d stage %d keep %s rot %d",
                        Math.max(0L, this.nextClickTime - System.currentTimeMillis()),
                        this.keepY.getValue(), this.stage, this.shouldKeepY, this.rotationTick)
        };
        for (String line : lines) {
            y -= (float) (mc.fontRendererObj.FONT_HEIGHT + 1);
            mc.fontRendererObj.drawString(line, x, y, Color.WHITE.getRGB(), true);
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
    public void verifyValue(String mode) {
        if (this.clickCpsMin.getName().equals(mode) && this.clickCpsMin.getValue() > this.clickCpsMax.getValue()) {
            this.clickCpsMax.setValue(this.clickCpsMin.getValue());
        } else if (this.clickCpsMax.getName().equals(mode) && this.clickCpsMax.getValue() < this.clickCpsMin.getValue()) {
            this.clickCpsMin.setValue(this.clickCpsMax.getValue());
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
        private final boolean visible;

        public AimResult(float yaw, float pitch, Vec3 hitVec, double[] point, boolean visible) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.hitVec = hitVec;
            this.point = point;
            this.visible = visible;
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
