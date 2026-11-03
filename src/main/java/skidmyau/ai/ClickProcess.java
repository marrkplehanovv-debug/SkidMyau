package skidmyau.ai;

import net.minecraft.util.MathHelper;

import java.util.Random;

/**
 * Модель человеческих кликов.
 *
 * Не равномерный random(minCPS,maxCPS): интервалы — гамма-распределение,
 * целевой CPS медленно дрейфует (усталость/возбуждение), есть паузы на
 * переприцел и задержка реакции на появление цели. Плюс редкие "промахи".
 *
 * В 1.8.9 ваниль обрабатывает максимум один удар за тик, поэтому эффективный
 * CPS <= 20, а сервер видит интервалы кратными 50 мс — здесь и моделируются
 * tick-gaps, а не микросекунды.
 */
public final class ClickProcess {
    public static final int MODE_LEGACY = 0;
    public static final int MODE_NORMAL = 1;
    public static final int MODE_BUTTERFLY = 2;
    public static final int MODE_DOUBLE = 3;
    public static final int MODE_DRAG = 4;

    private final Random random = new Random();
    private int mode = MODE_LEGACY;
    private double cps = 10.0;
    private double cpsTarget = 10.0;
    private double cpsMin = 8.0;
    private double cpsMax = 12.0;
    private float variance = 0.25F;
    private float missChance = 0.0F;
    private float pauseChance = 0.03F;
    private int pauseUntil;
    private int reactionUntil;
    private boolean wasAiming;

    public void configure(int mode, float minCps, float maxCps, float variancePercent,
                          float missPercent, float pausePercent) {
        if (mode != this.mode) {
            this.mode = mode;
            this.cpsTarget = this.sampleCpsTarget();
            this.cps = this.cpsTarget;
        }
        this.cpsMin = Math.max(1.0F, minCps);
        this.cpsMax = Math.max(this.cpsMin, maxCps);
        this.variance = MathHelper.clamp_float(variancePercent / 100.0F, 0.05F, 0.9F);
        this.missChance = MathHelper.clamp_float(missPercent / 100.0F, 0.0F, 0.5F);
        this.pauseChance = MathHelper.clamp_float(pausePercent / 100.0F, 0.0F, 0.3F);
    }

    private double sampleCpsTarget() {
        if (this.mode == MODE_DOUBLE) {
            return this.cpsMin + (this.cpsMax - this.cpsMin) * 0.75;
        }
        if (this.mode == MODE_DRAG) {
            return this.cpsMax - (this.cpsMax - this.cpsMin) * 0.15;
        }
        return this.cpsMin + this.random.nextDouble() * (this.cpsMax - this.cpsMin);
    }

    /** Пауза/реакция: вызывать каждый тик. */
    public void updateAim(int tick, boolean aiming, int reactionTicks) {
        if (aiming && !this.wasAiming) {
            this.reactionUntil = tick + Math.max(0, reactionTicks);
        }
        this.wasAiming = aiming;
    }

    public boolean canClick(int tick, boolean aiming, float aimGate, int reactionTicks) {
        if (tick < this.pauseUntil || tick < this.reactionUntil) {
            return false;
        }
        if (aimGate > 0.0F && !aiming) {
            // Человек иногда всё равно кликает мимо.
            return this.random.nextFloat() < this.missChance * 0.5F;
        }
        if (this.random.nextFloat() < this.missChance * 0.1F) {
            return false;
        }
        return true;
    }

    public int nextDelayMs() {
        if (this.mode == MODE_LEGACY) {
            return 0;
        }
        // OU-дрейф целевого CPS: усталость и микроколебания.
        this.cps += (this.cpsTarget - this.cps) * 0.08
                + this.random.nextGaussian() * 0.12 * (1.0 + this.variance);
        if (this.cps < this.cpsMin * 0.85) {
            this.cps = this.cpsMin * 0.85;
        }
        if (this.cps > Math.min(20.0, this.cpsMax * 1.1)) {
            this.cps = Math.min(20.0, this.cpsMax * 1.1);
        }

        double mean = 1000.0 / Math.max(1.0, this.cps);
        double shape = this.shape();
        double ms = gamma(shape) * (mean / shape);

        if (this.mode == MODE_DOUBLE) {
            // Пары: короткий гэп внутри пары, длинный между парами.
            ms = this.random.nextFloat() < 0.55F
                    ? 28.0 + this.random.nextDouble() * 34.0
                    : 95.0 + this.random.nextDouble() * 90.0;
        } else if (this.mode == MODE_DRAG) {
            this.cpsTarget = Math.max(this.cpsMin, this.cpsTarget - 0.02);
        } else if (this.mode == MODE_BUTTERFLY && this.random.nextFloat() < 0.04F) {
            ms *= 0.55;
        }

        if (this.random.nextFloat() < this.pauseChance) {
            ms += 120.0 + this.random.nextDouble() * 260.0;
        }
        // Нельзя чаще одного раза за тик.
        return (int) Math.max(50.0, Math.min(600.0, Math.round(ms)));
    }

    private double shape() {
        switch (this.mode) {
            case MODE_BUTTERFLY:
                return 6.0 + this.variance * 8.0;
            case MODE_DRAG:
                return 3.0 + this.variance * 5.0;
            case MODE_DOUBLE:
                return 5.0 + this.variance * 6.0;
            case MODE_NORMAL:
            default:
                return 12.0 + this.variance * 12.0;
        }
    }

    /** Marsaglia-Tsang; для k<1 используем буст. */
    private double gamma(double k) {
        if (k < 1.0) {
            return gamma(k + 1.0) * Math.pow(this.random.nextDouble(), 1.0 / k);
        }
        double d = k - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x = this.random.nextGaussian();
            double v = 1.0 + c * x;
            if (v <= 0.0) {
                continue;
            }
            v = v * v * v;
            double u = this.random.nextDouble();
            if (u < 1.0 - 0.0331 * x * x * x * x) {
                return d * v;
            }
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    /** Пауза после "промаха"/переприцела. */
    public void notifyMiss(int tick) {
        if (this.random.nextFloat() < 0.6F) {
            this.pauseUntil = tick + 2 + this.random.nextInt(4);
        }
    }

    public int getMode() {
        return this.mode;
    }

    public double getCps() {
        return this.cps;
    }
}
