package skidmyau.module.modules;

import skidmyau.ai.AiTarget;
import skidmyau.ai.MlpModel;
import skidmyau.event.EventTarget;
import skidmyau.event.types.Priority;
import skidmyau.events.UpdateEvent;
import skidmyau.module.Module;
import skidmyau.property.properties.BooleanProperty;
import skidmyau.property.properties.IntProperty;
import skidmyau.property.properties.ModeProperty;
import skidmyau.property.properties.PercentProperty;
import skidmyau.property.properties.TextProperty;
import skidmyau.property.properties.FloatProperty;
import skidmyau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Настройки и инфраструктура ИИ-ротации.
 *
 * Сам модуль ничего не делает, пока его не выбрали как режим в Scaffold/KillAura:
 * он хранит общие свойства, грузит модель и умеет записывать семплы для обучения.
 */
public class AiRotation extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static AiRotation instance;
    private static long lastClickTick = -1000L;

    private final AiTarget context = new AiTarget();

    private volatile MlpModel angleModel;
    private volatile MlpModel aimModel;
    private volatile boolean modelsLoaded;

    private BufferedWriter writer;
    private String sessionId;
    private int linesWritten;
    private long lastFlush;
    private AiSample pending;

    public final ModeProperty rotation = new ModeProperty(
            "rotation", 1, new String[]{"BOOTSTRAP", "MODEL"});
    public final TextProperty model = new TextProperty("model", "angles-v2");
    public final TextProperty aimModelName = new TextProperty("aim-model", "aimpoint-v1");
    public final PercentProperty humanize = new PercentProperty("humanize", 70);
    public final IntProperty latency = new IntProperty("latency", 3, 0, 8);
    public final PercentProperty overshoot = new PercentProperty("overshoot", 12);
    public final PercentProperty hurtDrag = new PercentProperty("hurt-drag", 55);
    public final PercentProperty stochastic = new PercentProperty("stochastic", 100);
    public final FloatProperty maxStep = new FloatProperty("max-step", 35.0F, 5.0F, 60.0F);
    public final ModeProperty aimOffset = new ModeProperty(
            "aim-offset", 1, new String[]{"CENTER", "SOFT", "LEARNED", "RANDOM"});
    public final PercentProperty aimScale = new PercentProperty("aim-offset-scale", 40);
    public final PercentProperty aimDrift = new PercentProperty("aim-drift", 25);
    public final IntProperty aimResample = new IntProperty("aim-resample", 60, 10, 200);
    public final BooleanProperty record = new BooleanProperty("record", false);
    public final ModeProperty recordKind = new ModeProperty(
            "record-kind", 0, new String[]{"ALL", "ENTITY", "BLOCK"});
    public final BooleanProperty debug = new BooleanProperty("debug", false);

    public AiRotation() {
        super("AI", false);
        instance = this;
    }

    public static AiRotation get() {
        return instance;
    }

    public static long getLastClickTick() {
        return lastClickTick;
    }

    public static void notifyClick() {
        if (mc.thePlayer != null) {
            lastClickTick = mc.thePlayer.ticksExisted;
        }
    }

    /** Контекст последней цели — нужен рекордеру. */
    public void noteTarget(AiTarget target) {
        this.context.copyFrom(target);
    }

    public void noteIdle() {
        this.context.reset(AiTarget.KIND_IDLE);
        this.context.key = 0L;
    }

    public AiTarget getContext() {
        return this.context;
    }

    public boolean isRecording() {
        return this.isEnabled() && this.record.getValue();
    }

    private void ensureModels() {
        if (this.modelsLoaded) {
            return;
        }
        this.modelsLoaded = true;
        this.angleModel = MlpModel.load(this.model.getValue(), null);
        this.aimModel = MlpModel.load(this.aimModelName.getValue(), null);
    }

    public MlpModel getAngleModel() {
        this.ensureModels();
        return this.angleModel;
    }

    public MlpModel getAimModel() {
        this.ensureModels();
        return this.aimModel;
    }

    public boolean isBootstrapAllowed() {
        return this.rotation.getValue() == 0 || this.getAngleModel() == null;
    }

    public int sampleLatency(Random rng) {
        int max = this.latency.getValue();
        if (max <= 0) {
            return 0;
        }
        return 1 + rng.nextInt(max);
    }

    public float getHumanize() {
        return this.humanize.getValue();
    }

    public float getOvershoot() {
        return this.overshoot.getValue();
    }

    public float getHurtDrag() {
        return this.hurtDrag.getValue();
    }

    public float getStochastic() {
        return this.stochastic.getValue();
    }

    public float getMaxStep() {
        return this.maxStep.getValue();
    }

    @Override
    public String[] getSuffix() {
        if (this.record.getValue()) {
            return new String[]{"REC"};
        }
        return new String[]{this.getAngleModel() != null ? "ML" : "BOOT"};
    }

    @Override
    public void onEnabled() {
        this.ensureModels();
        ChatUtil.sendFormatted(String.format(
                "%sAI: %s, offsets %s",
                skidmyau.SkidMyau.clientName,
                this.angleModel != null ? "&amodel loaded" : "&ebootstrap (no model)",
                this.aimOffset.getModeString()));
    }

    @Override
    public void onDisabled() {
        this.closeWriter();
    }

    // ------------------------------------------------------------------
    //  Рекордер семплов (docs/AI_ROTATION_PLAN.md, §4.2)
    // ------------------------------------------------------------------

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != skidmyau.event.types.EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (!this.isEnabled() || !this.record.getValue()) {
            this.closeWriter();
            return;
        }
        if (this.writer == null && !this.openWriter()) {
            return;
        }
        int tick = mc.thePlayer.ticksExisted;
        AiSample previous = this.pending;
        AiSample current = new AiSample();
        current.tick = tick;
        current.yaw = event.getNewYaw();
        current.pitch = event.getNewPitch();
        current.prevYaw = previous != null ? previous.yaw : current.yaw;
        current.prevPitch = previous != null ? previous.pitch : current.pitch;
        current.tPrevYaw = this.lastDeltaYawForRecorder;
        current.tPrevPitch = this.lastDeltaPitchForRecorder;
        current.kind = this.context.kind;
        current.targetYaw = this.context.yaw;
        current.targetPitch = this.context.pitch;
        current.distance = this.context.distance;
        current.motionX = this.context.motionX;
        current.motionY = this.context.motionY;
        current.motionZ = this.context.motionZ;
        current.playerMotionX = mc.thePlayer.posX - mc.thePlayer.prevPosX;
        current.playerMotionY = mc.thePlayer.posY - mc.thePlayer.prevPosY;
        current.playerMotionZ = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
        current.onGround = mc.thePlayer.onGround;
        current.hurtTime = mc.thePlayer.hurtTime;
        current.faceU = this.context.faceU;
        current.faceV = this.context.faceV;
        current.faceWidth = this.context.faceWidth;
        current.tower = this.context.tower;
        current.aimError = this.context.aimError;
        current.visible = this.context.visible;
        current.step = skidmyau.util.RotationUtil.getMouseStep();
        current.key = this.context.key;
        current.clickAge = (int) Math.max(0L, (long) tick - lastClickTick);
        current.hasTarget = this.context.kind != AiTarget.KIND_IDLE;

        if (previous != null) {
            if (current.tick == previous.tick + 1 && previous.hasTarget
                    && current.hasTarget && current.kind == previous.kind) {
                previous.labelYaw = MathHelper.wrapAngleTo180_float(current.yaw - previous.yaw);
                previous.labelPitch = current.pitch - previous.pitch;
                previous.skip = false;
            } else {
                previous.skip = true;
            }
            this.write(previous);
        }
        this.lastDeltaYawForRecorder = previous == null
                ? 0.0F
                : MathHelper.wrapAngleTo180_float(current.yaw - previous.yaw);
        this.lastDeltaPitchForRecorder = previous == null ? 0.0F : current.pitch - previous.pitch;
        this.pending = current;
    }

    private float lastDeltaYawForRecorder;
    private float lastDeltaPitchForRecorder;

    private boolean openWriter() {
        try {
            File folder = new File("./config/SkidMyau/ai/raw");
            if (!folder.exists() && !folder.mkdirs()) {
                return false;
            }
            this.sessionId = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File file = new File(folder, this.sessionId + ".jsonl");
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8));
            this.linesWritten = 0;
            this.lastFlush = System.currentTimeMillis();
            ChatUtil.sendFormatted(String.format(
                    "%sAI: recording to &7%s&r", skidmyau.SkidMyau.clientName, file.getPath()));
            return true;
        } catch (Throwable throwable) {
            this.writer = null;
            return false;
        }
    }

    private void closeWriter() {
        if (this.writer != null) {
            try {
                this.writer.flush();
                this.writer.close();
            } catch (Throwable ignored) {
            }
            this.writer = null;
            this.pending = null;
        }
    }

    private void write(AiSample sample) {
        if (this.writer == null) {
            return;
        }
        int filter = this.recordKind.getValue();
        if (filter == 1 && sample.kind != AiTarget.KIND_ENTITY) {
            return;
        }
        if (filter == 2 && sample.kind != AiTarget.KIND_BLOCK
                && sample.kind != AiTarget.KIND_SCAFFOLD) {
            return;
        }
        try {
            this.writer.write(String.format(Locale.US,
                    "{\"v\":2,\"s\":\"%s\",\"tick\":%d,\"yaw\":%.6f,\"pitch\":%.6f,"
                            + "\"pyaw\":%.6f,\"ppitch\":%.6f,\"dyaw\":%.6f,\"dpitch\":%.6f,"
                            + "\"tyaw\":%.6f,\"tpitch\":%.6f,\"dist\":%.4f,"
                            + "\"pmx\":%.6f,\"pmy\":%.6f,\"pmz\":%.6f,"
                            + "\"tmx\":%.6f,\"tmy\":%.6f,\"tmz\":%.6f,"
                            + "\"onGround\":%b,\"hurt\":%d,\"fu\":%.4f,\"fv\":%.4f,"
                            + "\"faceW\":%.3f,\"tower\":%b,\"aimErr\":%.3f,\"vis\":%b,"
                            + "\"step\":%.6f,\"kind\":%d,\"key\":%d,\"clk\":%d,\"skip\":%b}%n",
                    this.sessionId, sample.tick, sample.yaw, sample.pitch,
                    sample.prevYaw, sample.prevPitch,
                    sample.labelYaw, sample.labelPitch,
                    sample.targetYaw, sample.targetPitch, sample.distance,
                    sample.playerMotionX, sample.playerMotionY, sample.playerMotionZ,
                    sample.motionX, sample.motionY, sample.motionZ,
                    sample.onGround, sample.hurtTime, sample.faceU, sample.faceV,
                    sample.faceWidth, sample.tower, sample.aimError, sample.visible,
                    sample.step, sample.kind, sample.key, sample.clickAge, sample.skip));
            this.linesWritten++;
            long now = System.currentTimeMillis();
            if (this.linesWritten % 100 == 0 || now - this.lastFlush > 2000L) {
                this.writer.flush();
                this.lastFlush = now;
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isDebug() {
        return this.debug.getValue();
    }

    private static final class AiSample {
        int tick;
        float yaw;
        float pitch;
        float prevYaw;
        float prevPitch;
        float labelYaw;
        float labelPitch;
        float tPrevYaw;
        float tPrevPitch;
        int kind;
        float targetYaw;
        float targetPitch;
        double distance;
        double motionX;
        double motionY;
        double motionZ;
        double playerMotionX;
        double playerMotionY;
        double playerMotionZ;
        boolean onGround;
        int hurtTime;
        float faceU;
        float faceV;
        float faceWidth;
        boolean tower;
        float aimError;
        boolean visible;
        float step;
        long key;
        int clickAge;
        boolean hasTarget;
        boolean skip = true;
    }
}
