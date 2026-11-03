package skidmyau.ai;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Небольшая MLP-модель, которую мы обучаем офлайн (Python/PyTorch) и грузим
 * из ресурсов мода. Формат описан в docs/AI_ROTATION_PLAN.md, §5.4.
 *
 * Никаких зависимостей: только float[] и ByteBuffer. BatchNorm уже сфолжен
 * в линейные слои на этапе экспорта, поэтому здесь только Linear + activation.
 */
public final class MlpModel {
    private static final byte ACT_LINEAR = 0;
    private static final byte ACT_RELU = 1;
    private static final byte ACT_TANH = 2;

    public static final class Meta {
        public String name = "unknown";
        public int inputSize = 0;
        public int outputSize = 0;
        public String[] features = new String[0];
        public float[] inputMean = new float[0];
        public float[] inputStd = new float[0];
        public float[] outputScale = new float[0];
        public boolean stochastic = false;
    }

    private final Meta meta;
    private final int[] inSizes;
    private final int[] outSizes;
    private final byte[] activations;
    private final float[][] weights;
    private final float[][] biases;
    private final float[] bufA;
    private final float[] bufB;
    private final float[] normalized;

    private MlpModel(Meta meta, int[] inSizes, int[] outSizes, byte[] activations,
                     float[][] weights, float[][] biases) {
        this.meta = meta;
        this.inSizes = inSizes;
        this.outSizes = outSizes;
        this.activations = activations;
        this.weights = weights;
        this.biases = biases;
        int max = 1;
        for (int size : inSizes) {
            max = Math.max(max, size);
        }
        for (int size : outSizes) {
            max = Math.max(max, size);
        }
        this.bufA = new float[max];
        this.bufB = new float[max];
        this.normalized = new float[meta.inputSize];
    }

    public Meta getMeta() {
        return this.meta;
    }

    public static MlpModel load(String modelName, String metaName) {
        if (modelName == null || modelName.isEmpty()) {
            return null;
        }
        // Не даём выйти за пределы папки ресурсов.
        String safe = modelName.replace('\\', '/').replace("..", "").replace("/", "");
        String base = "/assets/skidmyau/ai/" + safe;
        try {
            byte[] raw = readAll(MlpModel.class.getResourceAsStream(base + ".skm"));
            if (raw == null) {
                return null;
            }
            Meta meta = null;
            InputStream metaStream = MlpModel.class.getResourceAsStream(base + ".json");
            if (metaStream != null) {
                meta = new Gson().fromJson(
                        new InputStreamReader(metaStream, StandardCharsets.UTF_8), Meta.class);
            }
            return parse(raw, meta);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } finally {
            stream.close();
        }
    }

    private static MlpModel parse(byte[] raw, Meta meta) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 16) {
            return null;
        }
        if (buffer.get() != 'S' || buffer.get() != 'K' || buffer.get() != 'M' || buffer.get() != '1') {
            return null;
        }
        int version = buffer.getInt();
        if (version != 1) {
            return null;
        }
        int inputSize = buffer.getInt();
        int layerCount = buffer.getInt();
        if (inputSize <= 0 || inputSize > 512 || layerCount <= 0 || layerCount > 32) {
            return null;
        }
        if (meta == null) {
            meta = new Meta();
        }
        meta.inputSize = inputSize;

        int[] inSizes = new int[layerCount];
        int[] outSizes = new int[layerCount];
        byte[] activations = new byte[layerCount];
        float[][] weights = new float[layerCount][];
        float[][] biases = new float[layerCount][];

        int currentIn = inputSize;
        for (int layer = 0; layer < layerCount; layer++) {
            int outSize = buffer.getInt();
            byte activation = buffer.get();
            if (outSize <= 0 || outSize > 4096) {
                return null;
            }
            long weightCount = (long) outSize * (long) currentIn;
            if (weightCount > 8_000_000L || buffer.remaining() < weightCount * 4L + outSize * 4L) {
                return null;
            }
            float[] w = new float[(int) weightCount];
            for (int i = 0; i < w.length; i++) {
                w[i] = buffer.getFloat();
            }
            float[] b = new float[outSize];
            for (int i = 0; i < outSize; i++) {
                b[i] = buffer.getFloat();
            }
            inSizes[layer] = currentIn;
            outSizes[layer] = outSize;
            activations[layer] = activation;
            weights[layer] = w;
            biases[layer] = b;
            currentIn = outSize;
        }
        meta.outputSize = currentIn;
        if (meta.inputMean.length != inputSize) {
            meta.inputMean = new float[inputSize];
        }
        if (meta.inputStd.length != inputSize) {
            meta.inputStd = new float[inputSize];
            for (int i = 0; i < inputSize; i++) {
                meta.inputStd[i] = 1.0F;
            }
        }
        if (meta.outputScale.length != currentIn) {
            meta.outputScale = new float[currentIn];
            for (int i = 0; i < currentIn; i++) {
                meta.outputScale[i] = 1.0F;
            }
        }
        for (int i = 0; i < meta.inputStd.length; i++) {
            if (meta.inputStd[i] <= 1.0E-6F) {
                meta.inputStd[i] = 1.0F;
            }
        }
        return new MlpModel(meta, inSizes, outSizes, activations, weights, biases);
    }

    /**
     * @param raw   сырые фичи (до нормализации)
     * @param out   массив минимум на outputSize
     */
    public void predict(float[] raw, float[] out) {
        float[] src = this.bufA;
        float[] dst = this.bufB;
        int inputSize = this.meta.inputSize;
        for (int i = 0; i < inputSize; i++) {
            float value = i < raw.length ? raw[i] : 0.0F;
            this.normalized[i] = (value - this.meta.inputMean[i]) / this.meta.inputStd[i];
        }
        System.arraycopy(this.normalized, 0, src, 0, inputSize);
        int currentSize = inputSize;
        for (int layer = 0; layer < this.weights.length; layer++) {
            int outSize = this.outSizes[layer];
            int inSize = this.inSizes[layer];
            float[] w = this.weights[layer];
            float[] b = this.biases[layer];
            for (int o = 0; o < outSize; o++) {
                float sum = b[o];
                int base = o * inSize;
                for (int i = 0; i < inSize; i++) {
                    sum += w[base + i] * src[i];
                }
                byte activation = this.activations[layer];
                if (activation == ACT_RELU) {
                    if (sum < 0.0F) {
                        sum = 0.0F;
                    }
                } else if (activation == ACT_TANH) {
                    sum = (float) Math.tanh(sum);
                }
                dst[o] = sum;
            }
            float[] swap = src;
            src = dst;
            dst = swap;
            currentSize = outSize;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = i < currentSize ? src[i] : 0.0F;
        }
    }
}
