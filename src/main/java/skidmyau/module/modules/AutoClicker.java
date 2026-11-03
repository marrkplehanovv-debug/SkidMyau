package skidmyau.module.modules;

import skidmyau.event.EventTarget;
import skidmyau.event.types.EventType;
import skidmyau.event.types.Priority;
import skidmyau.ai.ClickProcess;
import skidmyau.events.LeftClickMouseEvent;
import skidmyau.events.TickEvent;
import skidmyau.module.Module;
import skidmyau.util.*;
import skidmyau.property.properties.BooleanProperty;
import skidmyau.property.properties.FloatProperty;
import skidmyau.property.properties.IntProperty;
import skidmyau.property.properties.ModeProperty;
import skidmyau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.util.Objects;

public class AutoClicker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean clickPending = false;
    private long clickDelay = 0L;
    private boolean blockHitPending = false;
    private long blockHitDelay = 0L;
    private final ClickProcess clickProcess = new ClickProcess();
    public final ModeProperty clickMode = new ModeProperty(
            "click-mode", 0, new String[]{"LEGACY", "NORMAL", "BUTTERFLY", "DOUBLE", "DRAG"});
    public final PercentProperty clickVariance = new PercentProperty("click-variance", 25);
    public final FloatProperty clickAimGate = new FloatProperty("click-aim-gate", 6.0F, 0.0F, 20.0F);
    public final IntProperty clickReaction = new IntProperty("click-reaction", 2, 0, 8);
    public final PercentProperty clickMiss = new PercentProperty("click-miss", 0);
    public final PercentProperty clickPause = new PercentProperty("click-pause", 3);
    public final IntProperty minCPS = new IntProperty("min-cps", 8, 1, 20);
    public final IntProperty maxCPS = new IntProperty("max-cps", 12, 1, 20);
    public final BooleanProperty blockHit = new BooleanProperty("block-hit", false);
    public final FloatProperty blockHitTicks = new FloatProperty("block-hit-ticks", 1.5F, 1.0F, 20.0F, this.blockHit::getValue);
    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", true);
    public final BooleanProperty allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
    public final BooleanProperty breakBlocks = new BooleanProperty("break-blocks", true);
    public final FloatProperty range = new FloatProperty("range", 3.0F, 3.0F, 8.0F, this.breakBlocks::getValue);
    public final FloatProperty hitBoxVertical = new FloatProperty("hit-box-vertical", 0.1F, 0.0F, 1.0F, this.breakBlocks::getValue);
    public final FloatProperty hitBoxHorizontal = new FloatProperty("hit-box-horizontal", 0.2F, 0.0F, 1.0F, this.breakBlocks::getValue);

    private long getNextClickDelay() {
        if (this.clickMode.getValue() != 0) {
            int delay = this.clickProcess.nextDelayMs();
            if (delay > 0) {
                return delay;
            }
        }
        return 1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
    }

    private long getBlockHitDelay() {
        return (long) (50.0F * this.blockHitTicks.getValue());
    }

    private boolean isBreakingBlock() {
        return mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }

    private boolean canClick() {
        if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            if (this.breakBlocks.getValue() && this.isBreakingBlock() && !this.hasValidTarget()) {
                GameType gameType12 = mc.playerController.getCurrentGameType();
                return gameType12 != GameType.SURVIVAL && gameType12 != GameType.CREATIVE;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    private boolean isValidTarget(EntityPlayer entityPlayer) {
        if (entityPlayer != mc.thePlayer && entityPlayer != mc.thePlayer.ridingEntity) {
            if (entityPlayer == mc.getRenderViewEntity() || entityPlayer == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityPlayer.deathTime > 0) {
                return false;
            } else {
                float borderSize = entityPlayer.getCollisionBorderSize();
                return RotationUtil.rayTrace(entityPlayer.getEntityBoundingBox().expand(
                        borderSize + this.hitBoxHorizontal.getValue(),
                        borderSize + this.hitBoxVertical.getValue(),
                        borderSize + this.hitBoxHorizontal.getValue()
                ), mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, this.range.getValue()) != null;
            }
        } else {
            return false;
        }
    }

    private boolean hasValidTarget() {
        return mc.theWorld
                .loadedEntityList
                .stream()
                .filter(e -> e instanceof EntityPlayer)
                .map(e -> (EntityPlayer) e)
                .anyMatch(this::isValidTarget);
    }

    /** Наведён ли прицел на игрока в пределах click-aim-gate градусов. */
    private boolean isAimingAtTarget() {
        if (mc.theWorld == null || mc.thePlayer == null) {
            return false;
        }
        float gate = Math.max(1.0F, this.clickAimGate.getValue());
        double range = Math.max(3.0, (double) this.range.getValue());
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) object;
            if (player == mc.thePlayer || player.deathTime > 0) {
                continue;
            }
            if ((double) mc.thePlayer.getDistanceToEntity(player) > range + 1.0) {
                continue;
            }
            float[] rotations = RotationUtil.getRotationsToBox(
                    player.getEntityBoundingBox(),
                    mc.thePlayer.rotationYaw,
                    mc.thePlayer.rotationPitch,
                    180.0F,
                    0.0F
            );
            float error = Math.abs(MathHelper.wrapAngleTo180_float(
                    rotations[0] - mc.thePlayer.rotationYaw))
                    + Math.abs(rotations[1] - mc.thePlayer.rotationPitch);
            if (error <= gate) {
                return true;
            }
        }
        return false;
    }

    public AutoClicker() {
        super("AutoClicker", false);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            if (this.clickDelay > 0L) {
                this.clickDelay -= 50L;
            }
            if (this.blockHitDelay > 0L) {
                this.blockHitDelay -= 50L;
            }
            if (mc.currentScreen != null) {
                this.clickPending = false;
                this.blockHitPending = false;
            } else {
                if (this.clickPending) {
                    this.clickPending = false;
                    KeyBindUtil.updateKeyState(mc.gameSettings.keyBindAttack.getKeyCode());
                }
                if (this.blockHitPending) {
                    this.blockHitPending = false;
                    KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
                }
                if (this.isEnabled() && this.canClick() && mc.gameSettings.keyBindAttack.isKeyDown()) {
                    boolean aiClick = this.clickMode.getValue() != 0;
                    if (aiClick) {
                        this.clickProcess.configure(
                                this.clickMode.getValue(), this.minCPS.getValue(), this.maxCPS.getValue(),
                                this.clickVariance.getValue(), this.clickMiss.getValue(), this.clickPause.getValue());
                    }
                    boolean allowed = true;
                    if (aiClick) {
                        int tick = mc.thePlayer.ticksExisted;
                        boolean aiming = this.isAimingAtTarget();
                        this.clickProcess.updateAim(tick, aiming, this.clickReaction.getValue());
                        allowed = this.clickProcess.canClick(
                                tick, aiming, this.clickAimGate.getValue(), this.clickReaction.getValue());
                    }
                    if (allowed && !mc.thePlayer.isUsingItem()) {
                        while (this.clickDelay <= 0L) {
                            this.clickPending = true;
                            this.clickDelay = this.clickDelay + this.getNextClickDelay();
                            AiRotation.notifyClick();
                            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                            KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindAttack.getKeyCode());
                        }
                    } else if (this.clickDelay < -50L) {
                        // Клик заблокирован (гейт/реакция/пауза) — не копим
                        // очередь кликов, иначе после разблокировки пойдёт залп.
                        this.clickDelay = -50L;
                    }
                    if (this.blockHit.getValue()
                            && this.blockHitDelay <= 0L
                            && mc.gameSettings.keyBindUseItem.isKeyDown()
                            && ItemUtil.isHoldingSword()) {
                        this.blockHitPending = true;
                        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                        if (!mc.thePlayer.isUsingItem()) {
                            this.blockHitDelay = this.blockHitDelay + this.getBlockHitDelay();
                            KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());
                        }
                    }
                }
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onCLick(LeftClickMouseEvent event) {
        if (this.isEnabled() && !event.isCancelled()) {
            if (!this.clickPending) {
                this.clickDelay = this.clickDelay + this.getNextClickDelay();
            }
        }
    }

    @Override
    public void onEnabled() {
        this.clickDelay = 0L;
        this.blockHitDelay = 0L;
    }

    @Override
    public void verifyValue(String mode) {
        if (this.minCPS.getName().equals(mode)) {
            if (this.minCPS.getValue() > this.maxCPS.getValue()) {
                this.maxCPS.setValue(this.minCPS.getValue());
            }
        } else {
            if (this.maxCPS.getName().equals(mode) && this.minCPS.getValue() > this.maxCPS.getValue()) {
                this.minCPS.setValue(this.maxCPS.getValue());
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return Objects.equals(this.minCPS.getValue(), this.maxCPS.getValue())
                ? new String[]{this.minCPS.getValue().toString()}
                : new String[]{String.format("%d-%d", this.minCPS.getValue(), this.maxCPS.getValue())};
    }
}
