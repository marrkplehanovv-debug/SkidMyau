package skidmyau.module.modules;

import skidmyau.SkidMyau;
import skidmyau.ai.AiTarget;
import skidmyau.event.EventTarget;
import skidmyau.event.types.EventType;
import skidmyau.event.types.Priority;
import skidmyau.events.LeftClickMouseEvent;
import skidmyau.events.LoadWorldEvent;
import skidmyau.events.PacketEvent;
import skidmyau.events.Render3DEvent;
import skidmyau.events.RightClickMouseEvent;
import skidmyau.events.TickEvent;
import skidmyau.events.UpdateEvent;
import skidmyau.mixin.IAccessorRenderManager;
import skidmyau.module.Module;
import skidmyau.property.properties.BooleanProperty;
import skidmyau.property.properties.FloatProperty;
import skidmyau.property.properties.IntProperty;
import skidmyau.property.properties.ModeProperty;
import skidmyau.util.ChatUtil;
import skidmyau.util.RenderUtil;
import skidmyau.util.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.Locale;
import java.util.Random;

/**
 * Тренажёр для сбора человеческих семплов (аналог DebugCombatTrainerRecorder в LiquidBounce).
 *
 * Модуль сам спавнит цели вокруг игрока:
 *  - ENTITY: клиентского слизня, по которому надо навестись и ударить ЛКМ;
 *  - BLOCK:  реальный блок/грань, на который надо навестись и ПКМ поставить блок;
 *  - MIXED:  чередует оба режима.
 *
 * Игрок стоит на месте и сам двигает мышью. AiRotation в это время пишет
 * yaw/pitch-дельты в JSONL и показывает счётчик семплов в чате.
 */
public class AiTrainer extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int KIND_NONE = 0;
    private static final int KIND_ENTITY = 1;
    private static final int KIND_BLOCK = 2;

    private final Random random = new Random();
    private final AiTarget context = new AiTarget();

    private int nextEntityId = -2000000;
    private int targetKind = KIND_NONE;
    private int targetIndex;
    private int targetTicks;
    private int respawnTicks;
    private int targetStartSamples;
    private long lastWarnTime;

    private Entity targetEntity;
    private Vec3 entityAnchor;

    private BlockPos targetBlock;
    private EnumFacing targetFace;
    private Vec3 targetPoint;
    private float targetU = 0.5F;
    private float targetV = 0.5F;

    private boolean previousAiEnabled;
    private boolean previousRecord;

    public final ModeProperty mode = new ModeProperty(
            "mode", 0, new String[]{"ENTITY", "BLOCK", "MIXED"});
    public final FloatProperty distance = new FloatProperty("distance", 2.8F, 1.5F, 4.5F);
    public final FloatProperty yawSpread = new FloatProperty("yaw-spread", 70.0F, 10.0F, 180.0F);
    public final FloatProperty pitchSpread = new FloatProperty("pitch-spread", 20.0F, 0.0F, 60.0F);
    public final IntProperty respawnDelay = new IntProperty("respawn-delay", 10, 0, 100);
    public final IntProperty timeout = new IntProperty("timeout", 120, 20, 600);
    public final BooleanProperty highlight = new BooleanProperty("highlight", true);
    public final BooleanProperty sound = new BooleanProperty("sound", true);
    public final BooleanProperty autoRecord = new BooleanProperty("auto-record", true);
    public final BooleanProperty chatStats = new BooleanProperty("chat-stats", true);

    public AiTrainer() {
        super("AITrainer", false);
    }

    // ------------------------------------------------------------------
    //  Включение / выключение
    // ------------------------------------------------------------------

    @Override
    public void onEnabled() {
        AiRotation ai = AiRotation.get();
        if (ai != null) {
            this.previousAiEnabled = ai.isEnabled();
            this.previousRecord = ai.record.getValue();
            ai.setTrainerActive(true);
            if (!ai.isEnabled()) {
                ai.setEnabled(true);
            }
            if (this.autoRecord.getValue()) {
                ai.record.setValue(true);
            }
            this.targetStartSamples = ai.getSamplesWritten();
        }

        this.clearTarget();
        this.targetIndex = 0;
        this.respawnTicks = 10;
        this.lastWarnTime = 0L;

        ChatUtil.sendFormatted(String.format(
                "%s&aAITrainer&r: режим &e%s&r, дистанция &e%.1f&r",
                SkidMyau.clientName, this.mode.getModeString(), this.distance.getValue()));
        ChatUtil.sendFormatted(String.format(
                "%sСтой на месте. Цель появится через секунду. %s",
                SkidMyau.clientName,
                this.mode.getValue() == 1
                        ? "Наведись на подсвеченную грань и нажми &eПКМ&r (блок в руке)."
                        : "Наведись на слизня и ударь &eЛКМ&r."));
        if (AiRotation.get() != null && !AiRotation.get().record.getValue()) {
            ChatUtil.sendFormatted(String.format(
                    "%s&cВнимание:&r запись выключена (AI.record = false), семплы не сохраняются.",
                    SkidMyau.clientName));
        }
        if (SkidMyau.moduleManager != null) {
            KillAura killAura = (KillAura) SkidMyau.moduleManager.modules.get(KillAura.class);
            if (killAura != null && killAura.isEnabled()) {
                ChatUtil.sendFormatted(String.format(
                        "%s&eСовет:&r выключи KillAura/Scaffold, иначе в датасет попадёт ИИ-наведение, а не мышь.",
                        SkidMyau.clientName));
            }
        }
    }

    @Override
    public void onDisabled() {
        this.clearTarget();

        AiRotation ai = AiRotation.get();
        if (ai != null) {
            int total = ai.getSamplesWritten();
            ai.setTrainerActive(false);
            ai.noteTrainerIdle();

            if (this.autoRecord.getValue() && !this.previousRecord) {
                ai.record.setValue(false);
            }
            if (!this.previousAiEnabled && ai.isEnabled()) {
                ai.setEnabled(false);
            }

            if (this.chatStats.getValue()) {
                ChatUtil.sendFormatted(String.format(
                        "%sAITrainer выключен. Целей: &e%d&r, семплов за сессию: &a%d&r.",
                        SkidMyau.clientName, this.targetIndex, total));
            }
        }
    }

    // ------------------------------------------------------------------
    //  Тик: держим цель, заполняем контекст рекордера
    // ------------------------------------------------------------------

    @EventTarget(Priority.HIGHEST)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        AiRotation ai = AiRotation.get();

        if (this.targetKind == KIND_NONE) {
            if (ai != null) {
                ai.noteTrainerIdle();
            }
            return;
        }

        this.targetTicks++;

        if (this.targetKind == KIND_ENTITY) {
            if (this.targetEntity == null || this.targetEntity.isDead
                    || mc.theWorld.getEntityByID(this.targetEntity.getEntityId()) == null) {
                this.finish(false, "потеряна");
                return;
            }

            // Клиентская сущность не должна ни падать, ни отпрыгивать.
            this.targetEntity.setPosition(
                    this.entityAnchor.xCoord, this.entityAnchor.yCoord, this.entityAnchor.zCoord);
            this.targetEntity.motionX = 0.0;
            this.targetEntity.motionY = 0.0;
            this.targetEntity.motionZ = 0.0;
            this.targetEntity.onGround = true;

            if (this.targetTicks > this.timeout.getValue()) {
                this.finish(false, "таймаут");
                return;
            }
            if (ai != null) {
                this.fillEntityTarget(this.targetEntity);
                ai.noteTrainerTarget(this.context);
            }
            return;
        }

        if (this.targetBlock == null || this.targetFace == null) {
            this.finish(false, "потеряна");
            return;
        }
        if (mc.theWorld.isAirBlock(this.targetBlock)) {
            // Блок сломали/заменили — цель больше не актуальна.
            this.finish(false, "блок исчез");
            return;
        }
        if (this.targetTicks > this.timeout.getValue()) {
            this.finish(false, "таймаут");
            return;
        }
        if (ai != null) {
            this.fillBlockTarget();
            ai.noteTrainerTarget(this.context);
        }
    }

    // ------------------------------------------------------------------
    //  Спавн целей
    // ------------------------------------------------------------------

    /**
     * Spawn делаем в начале клиентского тика, а не внутри EntityPlayerSP.onUpdate:
     * так мы не меняем список сущностей, пока World.updateEntities его перебирает.
     */
    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || this.targetKind != KIND_NONE) {
            return;
        }
        if (this.respawnTicks > 0) {
            this.respawnTicks--;
            return;
        }
        this.spawnTarget();
    }

    private void spawnTarget() {
        int requested;
        if (this.mode.getValue() == 1) {
            requested = KIND_BLOCK;
        } else if (this.mode.getValue() == 0) {
            requested = KIND_ENTITY;
        } else {
            requested = this.targetIndex % 2 == 0 ? KIND_ENTITY : KIND_BLOCK;
        }

        boolean spawned = requested == KIND_ENTITY
                ? this.spawnEntityTarget()
                : this.spawnBlockTarget();

        // В MIXED режиме пробуем второй тип, если первый не нашёл места.
        if (!spawned && this.mode.getValue() == 2) {
            spawned = requested == KIND_ENTITY
                    ? this.spawnBlockTarget()
                    : this.spawnEntityTarget();
        }
        if (!spawned) {
            this.respawnTicks = 10;
            long now = System.currentTimeMillis();
            if (now - this.lastWarnTime > 5000L) {
                this.lastWarnTime = now;
                ChatUtil.sendFormatted(String.format(
                        "%s&eAITrainer:&r не нашёл место для цели. Встань у стены/пола "
                                + "и убедись, что чанки загружены.",
                        SkidMyau.clientName));
            }
            return;
        }

        this.targetTicks = 0;
        this.targetIndex++;
        AiRotation ai = AiRotation.get();
        this.targetStartSamples = ai == null ? 0 : ai.getSamplesWritten();

        if (this.sound.getValue()) {
            if (this.targetKind == KIND_ENTITY && this.targetEntity != null) {
                mc.theWorld.playSoundEffect(
                        this.targetEntity.posX, this.targetEntity.posY, this.targetEntity.posZ,
                        "random.orb", 0.7F, 1.2F);
            } else if (this.targetPoint != null) {
                mc.theWorld.playSoundEffect(
                        this.targetPoint.xCoord, this.targetPoint.yCoord, this.targetPoint.zCoord,
                        "random.orb", 0.7F, 1.2F);
            }
        }
    }

    /** Слизень в конусе перед игроком, как в LiquidBounce. */
    private boolean spawnEntityTarget() {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double baseDistance = Math.max(1.6, this.distance.getValue());

        for (int attempt = 0; attempt < 80; attempt++) {
            float yaw = mc.thePlayer.rotationYaw + this.randomRange(-this.yawSpread.getValue(), this.yawSpread.getValue());
            float pitch = MathHelper.clamp_float(
                    mc.thePlayer.rotationPitch + this.randomRange(-this.pitchSpread.getValue(), this.pitchSpread.getValue()),
                    -60.0F, 45.0F);
            double dist = baseDistance * (0.85 + this.random.nextDouble() * 0.3);

            Vec3 look = this.lookVector(yaw, pitch);
            Vec3 point = eye.addVector(look.xCoord * dist, look.yCoord * dist, look.zCoord * dist);
            if (point.yCoord < mc.thePlayer.posY + 0.3) {
                point = new Vec3(point.xCoord, mc.thePlayer.posY + 0.9, point.zCoord);
            }

            // Между глазом и точкой не должно быть стены.
            if (mc.theWorld.rayTraceBlocks(eye, point) != null) {
                continue;
            }
            if (!this.isAir(point)) {
                continue;
            }
            if (this.horizontalDistance(point) < 1.5) {
                continue;
            }

            EntitySlime slime = new EntitySlime(mc.theWorld);
            slime.setEntityId(this.nextEntityId--);
            slime.setNoAI(true);
            slime.setSilent(true);
            slime.setInvisible(false);
            slime.setHealth(slime.getMaxHealth());
            slime.setPosition(
                    point.xCoord, point.yCoord - (double) slime.height * 0.5, point.zCoord);
            slime.prevPosX = slime.lastTickPosX = slime.posX;
            slime.prevPosY = slime.lastTickPosY = slime.posY;
            slime.prevPosZ = slime.lastTickPosZ = slime.posZ;
            slime.motionX = 0.0;
            slime.motionY = 0.0;
            slime.motionZ = 0.0;

            if (!mc.theWorld.spawnEntityInWorld(slime)) {
                continue;
            }

            this.targetEntity = slime;
            this.entityAnchor = new Vec3(slime.posX, slime.posY, slime.posZ);
            this.targetBlock = null;
            this.targetFace = null;
            this.targetPoint = null;
            this.targetKind = KIND_ENTITY;
            return true;
        }
        return false;
    }

    /** Реальный блок с открытой гранью: именно по ней надо кликнуть ПКМ. */
    private boolean spawnBlockTarget() {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double reach = Math.min(
                (double) this.distance.getValue() + 0.3,
                (double) mc.playerController.getBlockReachDistance() - 0.2);
        double minDistance = 1.5;

        for (int attempt = 0; attempt < 100; attempt++) {
            float yaw = mc.thePlayer.rotationYaw + this.randomRange(-this.yawSpread.getValue(), this.yawSpread.getValue());
            // Смотрим вниз чаще: пол под ногами есть почти всегда.
            float pitch = MathHelper.clamp_float(
                    this.randomRange(-62.0F, 32.0F), -85.0F, 85.0F);

            MovingObjectPosition hit = RotationUtil.rayTrace(yaw, pitch, reach, 1.0F);
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                continue;
            }
            BlockPos pos = hit.getBlockPos();
            EnumFacing side = hit.sideHit;
            if (pos == null || side == null) {
                continue;
            }
            BlockPos placePos = pos.offset(side);
            if (!mc.theWorld.isAirBlock(placePos)) {
                continue;
            }
            IBlockState state = mc.theWorld.getBlockState(pos);
            Block block = state.getBlock();
            if (block.getMaterial().isLiquid() || !block.getMaterial().isSolid()) {
                continue;
            }

            double dist = eye.distanceTo(hit.hitVec);
            if (dist < minDistance || dist > reach) {
                continue;
            }

            // Точка на грани, подальше от краёв.
            float u = 0.3F + this.random.nextFloat() * 0.4F;
            float v = 0.3F + this.random.nextFloat() * 0.4F;
            Vec3 point = this.facePoint(pos, side, u, v);
            float[] rot = this.rotationsTo(eye, point);

            MovingObjectPosition verify = RotationUtil.rayTrace(rot[0], rot[1], reach, 1.0F);
            if (verify == null || verify.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                    || !pos.equals(verify.getBlockPos()) || verify.sideHit != side) {
                continue;
            }

            this.targetBlock = pos;
            this.targetFace = side;
            this.targetPoint = point;
            this.targetU = u;
            this.targetV = v;
            this.targetEntity = null;
            this.entityAnchor = null;
            this.targetKind = KIND_BLOCK;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Контекст для рекордера
    // ------------------------------------------------------------------

    private void fillEntityTarget(Entity entity) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB box = entity.getEntityBoundingBox();
        double tx = (box.minX + box.maxX) * 0.5;
        double ty = box.minY + (box.maxY - box.minY) * 0.6;
        double tz = (box.minZ + box.maxZ) * 0.5;

        float[] rot = this.rotationsTo(eye, new Vec3(tx, ty, tz));
        this.context.reset(AiTarget.KIND_ENTITY);
        this.context.yaw = rot[0];
        this.context.pitch = rot[1];
        this.context.motionX = entity.posX - entity.prevPosX;
        this.context.motionY = entity.posY - entity.prevPosY;
        this.context.motionZ = entity.posZ - entity.prevPosZ;
        this.context.hurtTime = entity instanceof EntityLivingBase
                ? ((EntityLivingBase) entity).hurtTime : 0;
        this.context.key = entity.getEntityId();

        double dx = tx - eye.xCoord;
        double dy = ty - eye.yCoord;
        double dz = tz - eye.zCoord;
        this.context.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        this.context.faceWidth = (float) Math.toDegrees(
                Math.atan2((double) Math.max(entity.width, 0.4F),
                        Math.max(0.5, this.context.distance)));
        this.context.faceU = 0.5F;
        this.context.faceV = 0.5F;
        this.context.aimError = Math.abs(MathHelper.wrapAngleTo180_float(
                rot[0] - mc.thePlayer.rotationYaw))
                + Math.abs(rot[1] - mc.thePlayer.rotationPitch);
        this.context.visible = RotationUtil.rayTrace(
                box, rot[0], rot[1], Math.max(1.0, this.context.distance) + 0.5) != null;
    }

    private void fillBlockTarget() {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 point = this.targetPoint != null
                ? this.targetPoint
                : this.facePoint(this.targetBlock, this.targetFace, this.targetU, this.targetV);
        float[] rot = this.rotationsTo(eye, point);

        this.context.reset(AiTarget.KIND_BLOCK);
        this.context.yaw = rot[0];
        this.context.pitch = rot[1];
        this.context.hurtTime = mc.thePlayer.hurtTime;
        this.context.faceU = this.targetU;
        this.context.faceV = this.targetV;
        this.context.tower = false;
        this.context.key = ((long) this.targetBlock.hashCode() << 4)
                | (long) this.targetFace.getIndex();

        double dx = point.xCoord - eye.xCoord;
        double dy = point.yCoord - eye.yCoord;
        double dz = point.zCoord - eye.zCoord;
        this.context.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        this.context.faceWidth = (float) Math.toDegrees(
                Math.atan2(1.0, Math.max(0.5, this.context.distance)));
        this.context.aimError = Math.abs(MathHelper.wrapAngleTo180_float(
                rot[0] - mc.thePlayer.rotationYaw))
                + Math.abs(rot[1] - mc.thePlayer.rotationPitch);
        this.context.visible = true;
    }

    // ------------------------------------------------------------------
    //  Завершение цели
    // ------------------------------------------------------------------

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }

        if (this.targetEntity != null && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            Entity entity = packet.getEntityFromWorld(mc.theWorld);
            if (entity == this.targetEntity || (entity != null
                    && entity.getEntityId() == this.targetEntity.getEntityId())) {
                // Сущность существует только на клиенте: пакет на сервер отправлять нельзя.
                event.setCancelled(true);
                if (packet.getAction() == C02PacketUseEntity.Action.ATTACK) {
                    this.finish(true, "удар");
                }
            }
            return;
        }

        if (this.targetBlock != null && event.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement packet = (C08PacketPlayerBlockPlacement) event.getPacket();
            if (this.targetBlock.equals(packet.getPosition())
                    && packet.getPlacedBlockDirection() == this.targetFace.getIndex()) {
                this.finish(true, "блок поставлен");
            }
        }
    }

    /** Страховка: даже если по какой-то причине C08 не ушёл, ПКМ по нужной грани считаем успехом. */
    @EventTarget(Priority.HIGH)
    public void onRightClick(RightClickMouseEvent event) {
        if (!this.isEnabled() || this.targetKind != KIND_BLOCK
                || this.targetBlock == null || this.targetFace == null) {
            return;
        }
        MovingObjectPosition hit = RotationUtil.rayTrace(
                mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch,
                (double) mc.playerController.getBlockReachDistance(), 1.0F);
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && this.targetBlock.equals(hit.getBlockPos())
                && hit.sideHit == this.targetFace) {
            this.finish(true, "ПКМ по грани");
        }
    }

    @EventTarget(Priority.HIGH)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        // Просто отмечаем клик для фичи clickAge. Саму цель завершаем по C02,
        // чтобы сначала отменить пакет на несуществующую сущность.
        AiRotation.notifyClick();
    }

    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        if (this.isEnabled()) {
            // Старый мир уже выгружен — сущность просто забываем, removeEntity не зовём.
            this.clearTarget(false);
            this.respawnTicks = Math.max(5, this.respawnDelay.getValue());
        }
    }

    private void finish(boolean hit, String reason) {
        AiRotation ai = AiRotation.get();
        int gained = ai == null ? 0 : ai.getSamplesWritten() - this.targetStartSamples;
        int total = ai == null ? 0 : ai.getSamplesWritten();
        int index = this.targetIndex;

        this.clearTarget();
        this.respawnTicks = this.respawnDelay.getValue();
        if (ai != null) {
            ai.noteTrainerIdle();
        }

        if (this.sound.getValue()) {
            mc.theWorld.playSoundEffect(
                    mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                    hit ? "random.levelup" : "random.click", 0.5F, hit ? 1.4F : 0.8F);
        }

        if (this.chatStats.getValue()) {
            ChatUtil.sendFormatted(String.format(Locale.US,
                    "%sAITrainer: цель &e#%d&r %s&r (&7%s&r), семплов &a+%d&r, всего &a%d&r",
                    SkidMyau.clientName, index,
                    hit ? "&aвыполнена" : "&cпропущена",
                    reason, Math.max(0, gained), total));
        }
    }

    private void clearTarget() {
        this.clearTarget(true);
    }

    private void clearTarget(boolean removeEntity) {
        if (removeEntity && this.targetEntity != null && mc.theWorld != null) {
            mc.theWorld.removeEntity(this.targetEntity);
        }
        this.targetEntity = null;
        this.entityAnchor = null;
        this.targetBlock = null;
        this.targetFace = null;
        this.targetPoint = null;
        this.targetU = 0.5F;
        this.targetV = 0.5F;
        this.targetKind = KIND_NONE;
        this.targetTicks = 0;
    }

    // ------------------------------------------------------------------
    //  Рендер
    // ------------------------------------------------------------------

    @EventTarget(Priority.MEDIUM)
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || !this.highlight.getValue()) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        RenderUtil.enableRenderState();
        if (this.targetKind == KIND_ENTITY && this.targetEntity != null) {
            RenderUtil.drawEntityBoundingBox(this.targetEntity, 80, 220, 255, 180, 2.0F, 0.1);
            if (this.entityAnchor != null) {
                this.drawPoint(this.entityAnchor, 0.08, 255, 220, 80);
            }
        } else if (this.targetKind == KIND_BLOCK && this.targetBlock != null) {
            RenderUtil.drawBlockBoundingBox(this.targetBlock, 1.0, 80, 220, 255, 150, 2.0F);
            BlockPos placePos = this.targetBlock.offset(this.targetFace);
            RenderUtil.drawBlockBoundingBox(placePos, 1.0, 255, 200, 80, 90, 1.5F);
            if (this.targetPoint != null) {
                this.drawPoint(this.targetPoint, 0.07, 255, 240, 120);
            }
        }
        RenderUtil.disableRenderState();
    }

    private void drawPoint(Vec3 point, double size, int red, int green, int blue) {
        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
        AxisAlignedBB box = new AxisAlignedBB(
                point.xCoord - size, point.yCoord - size, point.zCoord - size,
                point.xCoord + size, point.yCoord + size, point.zCoord + size)
                .offset(-renderX, -renderY, -renderZ);
        RenderUtil.drawFilledBox(box, red, green, blue);
        RenderUtil.drawBoundingBox(box, red, green, blue, 220, 2.0F);
    }

    // ------------------------------------------------------------------
    //  Математика
    // ------------------------------------------------------------------

    private float randomRange(float min, float max) {
        return min + this.random.nextFloat() * (max - min);
    }

    private Vec3 lookVector(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(
                -Math.sin(yawRad) * cosPitch,
                -Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch);
    }

    private float[] rotationsTo(Vec3 eye, Vec3 point) {
        double dx = point.xCoord - eye.xCoord;
        double dy = point.yCoord - eye.yCoord;
        double dz = point.zCoord - eye.zCoord;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    private boolean isAir(Vec3 point) {
        BlockPos pos = new BlockPos(
                MathHelper.floor_double(point.xCoord),
                MathHelper.floor_double(point.yCoord),
                MathHelper.floor_double(point.zCoord));
        return mc.theWorld.isAirBlock(pos);
    }

    private double horizontalDistance(Vec3 point) {
        double dx = point.xCoord - mc.thePlayer.posX;
        double dz = point.zCoord - mc.thePlayer.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Vec3 facePoint(BlockPos pos, EnumFacing side, float u, float v) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        switch (side) {
            case UP:
                return new Vec3(x + u, y + 1.0, z + v);
            case DOWN:
                return new Vec3(x + u, y, z + v);
            case NORTH:
                return new Vec3(x + u, y + v, z);
            case SOUTH:
                return new Vec3(x + u, y + v, z + 1.0);
            case WEST:
                return new Vec3(x, y + v, z + u);
            case EAST:
                return new Vec3(x + 1.0, y + v, z + u);
            default:
                return new Vec3(x + 0.5, y + 0.5, z + 0.5);
        }
    }

    @Override
    public String[] getSuffix() {
        if (!this.isEnabled()) {
            return new String[0];
        }
        String kind = this.targetKind == KIND_ENTITY
                ? "E" : this.targetKind == KIND_BLOCK ? "B" : "-";
        AiRotation ai = AiRotation.get();
        int samples = ai == null ? 0 : ai.getSamplesWritten();
        return new String[]{kind + " #" + this.targetIndex, "REC " + samples};
    }
}
