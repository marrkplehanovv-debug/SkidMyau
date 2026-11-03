"""Проверка модели: ошибка предсказания и детектор "человек/бот".

Детектор эмулирует то, что реально делает RotationPredictor в игре
(шум сигмы + квантование под чувствительность + перенос остатка) и учит
логистическую регрессию отличать такие дельты от человеческих.

Порог приёмки из плана: AUC <= 0.60, KS <= 0.05.
"""

import argparse
import os

import numpy as np

import dataset
import skm


def predict_all(model_dir, x):
    input_size, layers = skm.load_skm(os.path.join(model_dir, "angles-v2.skm"))
    meta = skm.load_meta(os.path.join(model_dir, "angles-v2.json"))
    assert input_size == dataset.INPUT_SIZE, "несовпадение числа фич"
    mean = np.asarray(meta["inputMean"])
    std = np.asarray(meta["inputStd"])
    std[std < 1e-6] = 1.0
    scale = np.asarray(meta["outputScale"])
    out = skm.forward(layers, (x - mean) / std)
    mu = out[:, :2] * scale[:2]
    sigma = np.exp(np.clip(out[:, 2:4], -6.0, 1.0)) * scale[:2] if out.shape[1] >= 4 \
        else np.zeros_like(mu)
    return mu, sigma, scale


# --------------------------------------------------------------------------
#  Симуляция игрового пайплайна
# --------------------------------------------------------------------------

def simulate(mu, sigma, steps, max_step=35.0, stochastic=1.0, seed=7):
    """Повторяет RotationPredictor: шум, clamp, квантование с остатком."""
    rng = np.random.default_rng(seed)
    out = np.zeros_like(mu)
    residual = np.zeros(2)
    for i in range(len(mu)):
        delta = mu[i] + sigma[i] * rng.standard_normal(2) * stochastic
        delta = np.clip(delta, -max_step, max_step)
        step = float(steps[i])
        if step > 1e-4:
            want = delta + residual
            quantized = np.round(want / step) * step
            residual = want - quantized
            residual[np.abs(residual) > step] = 0.0
            delta = quantized
        out[i] = delta
    return out


# --------------------------------------------------------------------------
#  Оконные статистики детектора
# --------------------------------------------------------------------------

def window_features(dyaw, dpitch, total_yaw, step):
    eps = 1e-6
    ay = np.abs(dyaw)
    ap = np.abs(dpitch)
    total = np.abs(total_yaw) + eps
    gain = ay / total
    feats = [
        ay.mean(), ay.std(), np.median(ay),
        ap.mean(), ap.std(), np.median(ap),
        dyaw.mean(), dpitch.mean(),
        np.mean(np.diff(dyaw) ** 2) if len(dyaw) > 1 else 0.0,
        np.corrcoef(dyaw[:-1], dyaw[1:])[0, 1] if len(dyaw) > 3 and dyaw.std() > eps else 0.0,
        np.mean(np.abs(dyaw) < 1e-3),
        np.mean(np.abs(dpitch) < 1e-3),
        float(((np.abs(dyaw.mean()) / (ay.mean() + eps)))) if ay.mean() > eps else 0.0,
        gain.mean(), gain.std(),
        np.percentile(ay, 90) / (np.percentile(ay, 10) + eps),
        (ay ** 2).mean(),
        float(np.mean(np.abs(np.round(dyaw / np.maximum(step, eps) + 1e-9)
                             - dyaw / np.maximum(step, eps)))),
        float(np.mean(np.abs(np.round(dpitch / np.maximum(step, eps) + 1e-9)
                             - dpitch / np.maximum(step, eps)))),
    ]
    return feats


def collect_windows(groups, delta_source, window=40, stride=20):
    feats, labels = [], []
    for group in groups:
        x = np.asarray(group.x)
        if len(x) < window:
            continue
        step = np.maximum(x[:, 11] * 0.5, 1e-4)
        total_yaw = x[:, 0] * 180.0
        dyaw, dpitch = delta_source(group, x)
        for start in range(0, len(x) - window + 1, stride):
            sl = slice(start, start + window)
            feats.append(window_features(dyaw[sl], dpitch[sl], total_yaw[sl], step[sl]))
    return np.asarray(feats)


def human_source(group, x):
    y = np.asarray(group.y)
    return y[:, 0], y[:, 1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--window", type=int, default=40)
    parser.add_argument("--val-fraction", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=1234)
    args = parser.parse_args()

    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import roc_auc_score
    from sklearn.preprocessing import StandardScaler
    from scipy.stats import ks_2samp

    train_files, val_files = dataset.split_sessions(
        args.data, args.val_fraction, args.seed)
    if not val_files:
        raise SystemExit("нужно минимум 2 сессии, чтобы отделить валидацию")

    def load(files):
        groups = []
        for path in files:
            name = os.path.splitext(os.path.basename(path))[0]
            groups.extend(dataset.build_groups(dataset.load_session(path), name))
        return groups

    train_groups, val_groups = load(train_files), load(val_files)
    print("групп: train %d / val %d" % (len(train_groups), len(val_groups)))

    # --- точность предсказания ---
    x_val = np.asarray([f for g in val_groups for f in g.x])
    y_val = np.asarray([v for g in val_groups for v in g.y])
    mu, sigma, scale = predict_all(args.model, x_val)
    mae = np.abs(mu - y_val).mean(axis=0)
    zero = np.abs(y_val).mean(axis=0)
    print("\nMAE yaw   %.3f°  (baseline %.3f°)" % (mae[0], zero[0]))
    print("MAE pitch %.3f°  (baseline %.3f°)" % (mae[1], zero[1]))
    print("MAE хуже baseline? %s" % ("ДА" if np.any(mae > zero) else "нет"))

    # --- детектор ---
    def bot_source(group, x):
        m, s, _ = predict_all(args.model, x)
        steps = np.maximum(x[:, 11] * 0.5, 1e-4)
        delta = simulate(m, s, steps)
        return delta[:, 0], delta[:, 1]

    human_train = collect_windows(train_groups, human_source, args.window)
    bot_train = collect_windows(train_groups, bot_source, args.window)
    human_val = collect_windows(val_groups, human_source, args.window)
    bot_val = collect_windows(val_groups, bot_source, args.window)
    if min(len(human_train), len(bot_train), len(human_val), len(bot_val)) == 0:
        raise SystemExit("слишком мало данных для окон %d тиков" % args.window)

    scaler = StandardScaler().fit(np.vstack([human_train, bot_train]))
    clf = LogisticRegression(max_iter=2000, C=0.5, class_weight="balanced")
    clf.fit(scaler.transform(np.vstack([human_train, bot_train])),
            np.r_[np.zeros(len(human_train)), np.ones(len(bot_train))])

    scores_human = clf.predict_proba(scaler.transform(human_val))[:, 1]
    scores_bot = clf.predict_proba(scaler.transform(bot_val))[:, 1]
    y_true = np.r_[np.zeros(len(scores_human)), np.ones(len(scores_bot))]
    y_score = np.r_[scores_human, scores_bot]
    auc = roc_auc_score(y_true, y_score)
    ks = ks_2samp(scores_human, scores_bot).statistic

    print("\nДетектор человек/бот на held-out сессиях:")
    print("  AUC = %.3f   (цель <= 0.60)" % auc)
    print("  KS  = %.3f   (цель <= 0.05)" % ks)
    verdict = "ПРОЙДЕНО" if auc <= 0.60 and ks <= 0.05 else "НЕ ПРОЙДЕНО"
    print("  вердикт: %s" % verdict)
    if auc > 0.75:
        print("  Подсказка: чаще всего выдаёт слишком узкая сигма и слишком")
        print("  'идеальная' пропорциональность delta ~ error. Проверь, что")
        print("  humanize/latency/overshoot включены при записи боевых данных.")


if __name__ == "__main__":
    main()
