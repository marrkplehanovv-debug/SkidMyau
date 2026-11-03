"""Формат .skm: запись, чтение, forward.

Никакого torch — этот модуль должен работать и на машине без обучения.
Формат (см. MlpModel.java):

    "SKM1" | i32 version=1 | i32 input | i32 layers
    на слой: i32 out | u8 act | f32[out*in] W | f32[out] b

Всё little-endian, W row-major по выходу.
"""

import json
import struct

import numpy as np

ACT_LINEAR = 0
ACT_RELU = 1
ACT_TANH = 2


def write_skm(path, input_size, layers):
    """layers: [(W[out,in], b[out], act), ...] — уже без BatchNorm."""
    with open(path, "wb") as handle:
        handle.write(b"SKM1")
        handle.write(struct.pack("<i", 1))
        handle.write(struct.pack("<i", int(input_size)))
        handle.write(struct.pack("<i", len(layers)))
        for weight, bias, activation in layers:
            weight = np.asarray(weight, dtype=np.float32)
            bias = np.asarray(bias, dtype=np.float32)
            if weight.ndim != 2 or bias.ndim != 1:
                raise ValueError("слой должен быть матрицей + вектором")
            out_size, in_size = weight.shape
            if bias.shape[0] != out_size:
                raise ValueError("несогласованные размеры слоя")
            handle.write(struct.pack("<iB", int(out_size), int(activation)))
            handle.write(weight.astype("<f4").tobytes(order="C"))
            handle.write(bias.astype("<f4").tobytes(order="C"))


def load_skm(path):
    with open(path, "rb") as handle:
        blob = handle.read()
    if blob[:4] != b"SKM1":
        raise ValueError("плохой magic: %r" % blob[:4])
    offset = 4
    version, = struct.unpack_from("<i", blob, offset)
    offset += 4
    if version != 1:
        raise ValueError("неизвестная версия %d" % version)
    input_size, = struct.unpack_from("<i", blob, offset)
    offset += 4
    layer_count, = struct.unpack_from("<i", blob, offset)
    offset += 4
    layers = []
    current = input_size
    for _ in range(layer_count):
        out_size, act = struct.unpack_from("<iB", blob, offset)
        offset += 5
        count = out_size * current
        weight = np.frombuffer(blob, dtype="<f4", count=count, offset=offset)
        offset += count * 4
        bias = np.frombuffer(blob, dtype="<f4", count=out_size, offset=offset)
        offset += out_size * 4
        layers.append((
            weight.reshape(out_size, current).astype(np.float64),
            bias.astype(np.float64),
            act,
        ))
        current = out_size
    if offset != len(blob):
        raise ValueError("лишние байты в конце файла: %d" % (len(blob) - offset))
    return input_size, layers


def forward(layers, x):
    src = np.asarray(x, dtype=np.float64)
    for weight, bias, act in layers:
        src = src @ weight.T + bias
        if act == ACT_RELU:
            src = np.maximum(src, 0.0)
        elif act == ACT_TANH:
            src = np.tanh(src)
    return src


def write_meta(path, name, mean, std, out_scale, outputs, features, extra=None):
    std = np.asarray(std, dtype=np.float64).copy()
    std[std < 1e-6] = 1.0
    meta = {
        "name": name,
        "inputSize": int(len(mean)),
        "outputSize": int(outputs),
        "features": list(features),
        "inputMean": [float(v) for v in mean],
        "inputStd": [float(v) for v in std],
        "outputScale": [float(out_scale[0]), float(out_scale[1])]
        + [1.0] * (outputs - 2),
        "stochastic": outputs >= 4,
        "note": "outputScale in degrees; sigma = exp(log_sigma) * scale",
    }
    if extra:
        meta.update(extra)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(meta, handle, ensure_ascii=False, indent=2)
    return meta


def load_meta(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)
