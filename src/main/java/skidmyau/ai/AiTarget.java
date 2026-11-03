package skidmyau.ai;

/**
 * Контекст текущей цели для предиктора и рекордера.
 * Переиспользуемый объект: заполняется модулем перед вызовом RotationPredictor.
 */
public final class AiTarget {
    public static final int KIND_IDLE = 0;
    public static final int KIND_ENTITY = 1;
    public static final int KIND_BLOCK = 2;
    public static final int KIND_SCAFFOLD = 3;

    public int kind = KIND_IDLE;
    /** Куда надо смотреть (финальная цель, без сглаживания). */
    public float yaw;
    public float pitch;
    /** Расстояние до цели в блоках. */
    public double distance = 3.0;
    /** Скорость цели за последний тик (блоков/тик). */
    public double motionX;
    public double motionY;
    public double motionZ;
    public int hurtTime;
    /** Локальные координаты точки на грани блока (0..1). */
    public float faceU = 0.5F;
    public float faceV = 0.5F;
    /** Видимая угловая ширина грани в градусах. */
    public float faceWidth;
    public boolean tower;
    /** Текущая угловая ошибка прицела до выбранной точки. */
    public float aimError;
    public boolean visible = true;
    /** Идентификатор цели: смена = новый захват (латентность/overshoot). */
    public long key;

    public AiTarget reset(int kind) {
        this.kind = kind;
        this.motionX = 0.0;
        this.motionY = 0.0;
        this.motionZ = 0.0;
        this.hurtTime = 0;
        this.faceU = 0.5F;
        this.faceV = 0.5F;
        this.faceWidth = 0.0F;
        this.tower = false;
        this.aimError = 0.0F;
        this.visible = true;
        return this;
    }

    public AiTarget copyFrom(AiTarget other) {
        this.kind = other.kind;
        this.yaw = other.yaw;
        this.pitch = other.pitch;
        this.distance = other.distance;
        this.motionX = other.motionX;
        this.motionY = other.motionY;
        this.motionZ = other.motionZ;
        this.hurtTime = other.hurtTime;
        this.faceU = other.faceU;
        this.faceV = other.faceV;
        this.faceWidth = other.faceWidth;
        this.tower = other.tower;
        this.aimError = other.aimError;
        this.visible = other.visible;
        this.key = other.key;
        return this;
    }
}
