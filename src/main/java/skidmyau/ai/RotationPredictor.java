package skidmyau.ai;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import skidmyau.events.UpdateEvent;
import skidmyau.module.modules.AiRotation;
import skidmyau.util.RotationUtil;

import java.util.Random;

/**
 * Ядро ИИ-ротации.
 *
 * Модель предсказывает не абсолютный угол, а СЛЕДУЮЩУЮ тик-дельту yaw/pitch
 * (как AiAngleSmooth в LiquidBounce). Поверх предсказания накладываются
 * человеческие модификаторы: латентность захвата, overshoot, провал после
 * урона, стохастичность выхода и квантование под чувствительность мыши.
 *
 * Модели может не быть (она обучается офлайн) — тогда работает bootstrap:
 * баллистическая доводка с человеческим профилем. Это нужно, чтобы интеграцию
 * можно было тестировать до появления весов.
 */
public final class RotationPredictor {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private final Random random = new Random();
    private final float[] features = new float[26];
    private final float[] output = new float[8];

    private int lastTick = Integer.MIN_VALUE;
    private long lastKey = Long.MIN_VALUE;
    private float lastDeltaYaw;
    private float lastDeltaPitch;
    private float residualYaw;
    private float residualPitch;
    private int reactionLeft;
    private int targetSeen;
    private float overshoot = 1.0F;
    private boolean stalled;

    public void reset() {
        this.lastTick = Integer.MIN_VALUE;
        this.lastKey = Long.MIN_VALUE;
        this.lastDeltaYaw = 0.0F;
        this.lastDeltaPitch = 0.0F;
        this.residualYaw = 0.0F;
        this.residualPitch = 0.0F;
        this.reactionLeft = 0;
        this.targetSeen = 0;
        this.overshoot = 1.0F;
        this.stalled = false;
    }

    /**
     * Применяет предсказанную ротацию к событию.
     *
     * @return true, если ротация применена (модуль не должен вызывать свой fallback)
     */
    public boolean apply(UpdateEvent event, AiTarget target, int priority, AiRotation cfg) {
        if (mc.thePlayer == null || cfg == null || target == null
                || target.kind == AiTarget.KIND_IDLE) {
            return false;
        }
        int tick = mc.thePlayer.ticksExisted;
        if (this.lastTick == tick) {
            // Уже применяли в этом тике — не двигаем второй раз.
            return true;
        }
        this.stalled = false;

        boolean newTarget = target.key != this.lastKey;
        if (newTarget) {
            this.lastKey = target.key;
            this.targetSeen = 0;
            this.overshoot = 1.0F;
            this.lastDeltaYaw = 0.0F;
            this.lastDeltaPitch = 0.0F;
            if (target.kind == AiTarget.KIND_ENTITY) {
                this.reactionLeft = cfg.sampleLatency(this.random);
                // Человек перелетает новую цель и потом корректируется.
                float os = cfg.getOvershoot() / 100.0F;
                this.overshoot = 1.0F + this.random.nextFloat() * os * 0.55F;
            } else {
                this.reactionLeft = 0;
            }
        }
        this.targetSeen++;

        float currentYaw = event.getNewYaw();
        float currentPitch = event.getNewPitch();
        float totalYaw = MathHelper.wrapAngleTo180_float(target.yaw - currentYaw);
        float totalPitch = MathHelper.clamp_float(target.pitch - currentPitch, -90.0F, 90.0F);

        MlpModel model = cfg.getAngleModelForKind(target.kind);
        boolean usedModel = false;
        float deltaYaw;
        float deltaPitch;
        float sigmaYaw = 0.0F;
        float sigmaPitch = 0.0F;

        if (model != null && (model.getMeta().inputSize == 6 || model.getMeta().inputSize == 26)) {
            this.fillFeatures(totalYaw, totalPitch, target);
            model.predict(this.features, this.output);
            MlpModel.Meta meta = model.getMeta();
            float scaleYaw = meta.outputScale.length > 0 ? meta.outputScale[0] : 30.0F;
            float scalePitch = meta.outputScale.length > 1 ? meta.outputScale[1] : 20.0F;
            deltaYaw = this.output[0] * scaleYaw;
            deltaPitch = this.output[1] * scalePitch;
            if (meta.outputSize >= 4) {
                sigmaYaw = exponential(this.output[2]) * scaleYaw;
                sigmaPitch = exponential(this.output[3]) * scalePitch;
            }
            usedModel = true;
        } else if (cfg.isBootstrapAllowed()) {
            float absYaw = Math.abs(totalYaw);
            float gain;
            if (absYaw > 45.0F) {
                gain = 0.28F + this.random.nextFloat() * 0.10F;
            } else if (absYaw > 15.0F) {
                gain = 0.24F + this.random.nextFloat() * 0.09F;
            } else {
                gain = 0.16F + this.random.nextFloat() * 0.12F;
            }
            deltaYaw = totalYaw * gain;
            deltaPitch = totalPitch * gain * (0.85F + this.random.nextFloat() * 0.15F);
        } else {
            return false;
        }

        float humanize = cfg.getHumanize() / 100.0F;

        // Реакция на появление/смену цели: человек не отвечает мгновенно.
        if (this.reactionLeft > 0) {
            this.reactionLeft--;
            deltaYaw *= 0.12F;
            deltaPitch *= 0.12F;
        }

        // После полученного урона трекинг на пару тиков проседает.
        if (mc.thePlayer.hurtTime > 0) {
            float drag = 1.0F - (cfg.getHurtDrag() / 100.0F) * humanize;
            deltaYaw *= drag;
            deltaPitch *= drag;
        }

        // Overshoot только на первых тиках захвата.
        if (this.targetSeen <= 2 && Math.abs(totalYaw) > 12.0F) {
            deltaYaw *= 1.0F + (this.overshoot - 1.0F) * humanize;
        }

        // Стохастичность выхода модели: там, где человек непредсказуем.
        float stochastic = cfg.getStochastic() / 100.0F;
        if (usedModel && stochastic > 0.0F) {
            if (sigmaYaw > 0.0F) {
                deltaYaw += (float) this.random.nextGaussian() * sigmaYaw * stochastic;
            }
            if (sigmaPitch > 0.0F) {
                deltaPitch += (float) this.random.nextGaussian() * sigmaPitch * stochastic;
            }
        }

        // Защита от "залипания": модель/бутстрап не должны вечно не доводить цель.
        if (Math.abs(totalYaw) > 3.0F && Math.abs(deltaYaw) < 0.01F) {
            deltaYaw = totalYaw * 0.10F;
            this.stalled = true;
        }
        if (Math.abs(totalPitch) > 3.0F && Math.abs(deltaPitch) < 0.01F) {
            deltaPitch = totalPitch * 0.10F;
            this.stalled = true;
        }

        // Физический предел человека за один тик.
        float maxStep = cfg.getMaxStep();
        deltaYaw = MathHelper.clamp_float(deltaYaw, -maxStep, maxStep);
        deltaPitch = MathHelper.clamp_float(deltaPitch, -maxStep, maxStep);

        // Квантование под реальную чувствительность мыши + перенос остатка.
        float step = RotationUtil.getMouseStep();
        deltaYaw = this.quantize(deltaYaw, step, true);
        deltaPitch = this.quantize(deltaPitch, step, false);

        float newYaw = currentYaw + deltaYaw;
        float newPitch = MathHelper.clamp_float(currentPitch + deltaPitch, -90.0F, 90.0F);
        event.setRotation(newYaw, newPitch, priority);

        this.lastDeltaYaw = MathHelper.wrapAngleTo180_float(newYaw - currentYaw);
        this.lastDeltaPitch = newPitch - currentPitch;
        this.lastTick = tick;
        return true;
    }

    private void fillFeatures(float totalYaw, float totalPitch, AiTarget target) {
        // v1 (6 фич) — совместимо с CombatSample из LiquidBounce.
        this.features[0] = totalYaw / 180.0F;
        this.features[1] = totalPitch / 90.0F;
        this.features[2] = this.lastDeltaYaw / 30.0F;
        this.features[3] = this.lastDeltaPitch / 20.0F;

        double playerDx = mc.thePlayer.posX - mc.thePlayer.prevPosX;
        double playerDz = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
        float playerSpeed = (float) Math.sqrt(playerDx * playerDx + playerDz * playerDz);
        this.features[4] = playerSpeed * 20.0F;
        this.features[5] = (float) target.distance;

        // v2 (26 фич) — блоки, клики, контекст.
        this.features[6] = (float) target.motionX * 20.0F;
        this.features[7] = (float) target.motionY * 20.0F;
        this.features[8] = (float) target.motionZ * 20.0F;
        this.features[9] = mc.thePlayer.onGround ? 1.0F : 0.0F;

        float moveYaw = (float) Math.toDegrees(Math.atan2(-playerDx, playerDz));
        float moveError = moveYaw == 0.0F && playerSpeed < 0.01F
                ? 0.0F
                : MathHelper.wrapAngleTo180_float(moveYaw - target.yaw);
        this.features[10] = moveError / 180.0F;
        this.features[11] = RotationUtil.getMouseStep() / 0.5F;

        this.features[12] = target.kind == AiTarget.KIND_ENTITY ? 1.0F : 0.0F;
        this.features[13] = target.kind == AiTarget.KIND_BLOCK ? 1.0F : 0.0F;
        this.features[14] = target.kind == AiTarget.KIND_SCAFFOLD ? 1.0F : 0.0F;
        this.features[15] = target.kind == AiTarget.KIND_IDLE ? 1.0F : 0.0F;
        this.features[16] = (target.faceU - 0.5F) * 2.0F;
        this.features[17] = (target.faceV - 0.5F) * 2.0F;
        this.features[18] = target.faceWidth / 90.0F;
        this.features[19] = target.tower ? 1.0F : 0.0F;
        this.features[20] = target.aimError / 90.0F;
        this.features[21] = Math.min(this.targetSeen, 40) / 20.0F;
        this.features[22] = target.hurtTime / 10.0F;
        this.features[23] = Math.min((mc.thePlayer.ticksExisted - AiRotation.getLastClickTick()), 40) / 20.0F;
        this.features[24] = (90.0F - Math.abs(mc.thePlayer.rotationPitch)) / 90.0F;
        this.features[25] = target.visible ? 1.0F : 0.0F;
    }

    private static float exponential(float value) {
        float clamped = MathHelper.clamp_float(value, -5.0F, 1.0F);
        return (float) Math.exp(clamped);
    }

    private float quantize(float delta, float step, boolean yaw) {
        if (step <= 1.0E-4F) {
            return delta;
        }
        float residual = yaw ? this.residualYaw : this.residualPitch;
        float want = delta + residual;
        float quantized = Math.round(want / step) * step;
        float nextResidual = want - quantized;
        if (Math.abs(nextResidual) > step) {
            nextResidual = 0.0F;
        }
        if (yaw) {
            this.residualYaw = nextResidual;
        } else {
            this.residualPitch = nextResidual;
        }
        return quantized;
    }

    public float getLastDeltaYaw() {
        return this.lastDeltaYaw;
    }

    public float getLastDeltaPitch() {
        return this.lastDeltaPitch;
    }

    public boolean isStalled() {
        return this.stalled;
    }
}
