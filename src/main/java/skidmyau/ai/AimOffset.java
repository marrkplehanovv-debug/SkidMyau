package skidmyau.ai;

import net.minecraft.util.MathHelper;

import java.util.Random;

/**
 * Точка прицеливания на грани блока.
 *
 * Человек не целится в геометрический центр и не выбирает точку заново каждый
 * тик: он один раз выбирает удобную точку, а потом она медленно дрейфует.
 * Здесь это и реализовано — выбор точки + Ornstein-Uhlenbeck дрейф.
 *
 * Режимы:
 *   0 CENTER  — строго центр (безопасно для плейсмента);
 *   1 SOFT    — узкое нормальное распределение вокруг центра;
 *   2 LEARNED — если загружена aimpoint-модель, берём её мю/сигму;
 *   3 RANDOM  — шире, но всё равно усечённое нормальное, не uniform.
 */
public final class AimOffset {
    private final Random random = new Random();
    private float u = 0.5F;
    private float v = 0.5F;
    private float targetU = 0.5F;
    private float targetV = 0.5F;
    private int resample;
    private long key = Long.MIN_VALUE;
    private float lastDrift;

    public void reset() {
        this.key = Long.MIN_VALUE;
        this.u = 0.5F;
        this.v = 0.5F;
        this.targetU = 0.5F;
        this.targetV = 0.5F;
        this.resample = 0;
    }

    /**
     * @return {u, v} в локальных координатах грани (0..1)
     */
    public float[] update(long key, int mode, float scalePercent, int resampleTicks,
                          float driftPercent, float faceWidth, float distance,
                          MlpModel aimModel) {
        if (mode == 0) {
            this.u = 0.5F;
            this.v = 0.5F;
            return new float[]{0.5F, 0.5F};
        }
        if (key != this.key) {
            this.key = key;
            this.resample = 0;
        }
        this.lastDrift = driftPercent;
        if (--this.resample <= 0) {
            this.resample = Math.max(10, resampleTicks + this.random.nextInt(20) - 10);
            this.sample(mode, scalePercent, faceWidth, distance, aimModel);
        }

        float drift = MathHelper.clamp_float(driftPercent / 100.0F, 0.0F, 1.0F);
        float theta = 0.10F + 0.25F * drift;
        this.u += (this.targetU - this.u) * theta;
        this.v += (this.targetV - this.v) * theta;
        if (drift > 0.0F) {
            this.u += (float) this.random.nextGaussian() * 0.0035F * drift;
            this.v += (float) this.random.nextGaussian() * 0.0035F * drift;
        }
        this.u = MathHelper.clamp_float(this.u, 0.12F, 0.88F);
        this.v = MathHelper.clamp_float(this.v, 0.12F, 0.88F);
        return new float[]{this.u, this.v};
    }

    private void sample(int mode, float scalePercent, float faceWidth, float distance, MlpModel aimModel) {
        float scale = MathHelper.clamp_float(scalePercent / 100.0F, 0.0F, 1.0F);
        float sigma;
        if (mode == 3) {
            sigma = 0.10F + 0.20F * scale;
        } else if (mode == 2 && aimModel != null && aimModel.getMeta().inputSize == 8) {
            // Вход AimPointNet: контекст грани + куда мы смотрели до этого.
            float[] in = new float[8];
            in[0] = MathHelper.clamp_float(distance / 6.0F, 0.0F, 2.0F);
            in[1] = faceWidth / 90.0F;
            in[2] = (this.u - 0.5F) * 2.0F;
            in[3] = (this.v - 0.5F) * 2.0F;
            double mx = mcMotionX();
            double mz = mcMotionZ();
            in[4] = (float) Math.sqrt(mx * mx + mz * mz) * 20.0F;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            in[5] = mc.thePlayer != null && mc.thePlayer.onGround ? 1.0F : 0.0F;
            in[6] = scale;
            in[7] = MathHelper.clamp_float(this.lastDrift / 100.0F, 0.0F, 1.0F);
            float[] out = new float[4];
            aimModel.predict(in, out);
            MlpModel.Meta meta = aimModel.getMeta();
            float scaleU = meta.outputScale.length > 0 ? meta.outputScale[0] : 0.2F;
            float scaleV = meta.outputScale.length > 1 ? meta.outputScale[1] : 0.2F;
            float muU = 0.5F + out[0] * scaleU;
            float muV = 0.5F + out[1] * scaleV;
            float su = (float) Math.exp(MathHelper.clamp_float(out[2], -6.0F, -1.0F));
            float sv = (float) Math.exp(MathHelper.clamp_float(out[3], -6.0F, -1.0F));
            this.targetU = MathHelper.clamp_float(
                    muU + (float) this.random.nextGaussian() * su, 0.12F, 0.88F);
            this.targetV = MathHelper.clamp_float(
                    muV + (float) this.random.nextGaussian() * sv, 0.12F, 0.88F);
            return;
        } else {
            sigma = 0.04F + 0.09F * scale;
        }
        this.targetU = MathHelper.clamp_float(
                0.5F + (float) this.random.nextGaussian() * sigma, 0.15F, 0.85F);
        this.targetV = MathHelper.clamp_float(
                0.5F + (float) this.random.nextGaussian() * sigma, 0.15F, 0.85F);
    }

    private static double mcMotionX() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        return mc.thePlayer == null ? 0.0 : mc.thePlayer.motionX;
    }

    private static double mcMotionZ() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        return mc.thePlayer == null ? 0.0 : mc.thePlayer.motionZ;
    }

    /**
     * Угловые смещения от центра грани к выбранной точке.
     * faceWidth — видимая угловая ширина одной грани (1 блок) в градусах.
     */
    public static float[] toAngles(float u, float v, float faceWidth) {
        float dyaw = (u - 0.5F) * faceWidth;
        float dpitch = (v - 0.5F) * faceWidth;
        return new float[]{dyaw, dpitch};
    }
}
