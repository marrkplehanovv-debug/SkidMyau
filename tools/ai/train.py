"""Обучение angles-v2 (MLP 26-128-64-32-4 + Gaussian NLL) и экспорт в .skm.

Формат .skm описан в MlpModel.java и docs/AI_ROTATION_PLAN.md §5.4:
    "SKM1" | u32 версия | u32 input | u32 layers
    для каждого слоя: u32 out | u8 act | f32[out*in] W | f32[out] b
Все числа little-endian, W — row-major по выходу.

BatchNorm фолдится в предыдущий Linear, поэтому в моделе остаются только
Linear + ReLU. Это и позволяет грузить её на Java 8 без зависимостей.
"""

import argparse
import json
import os
import struct

import numpy as np
import torch
import torch.nn as nn

import dataset
import skm


def parse_args():
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True, help="каталог с *.jsonl")
    parser.add_argument(
        "--out",
        default=os.path.join(here, "..", "..", "src", "main", "resources",
                             "assets", "skidmyau", "ai"),
    )
    parser.add_argument("--name", default="angles-v2")
    parser.add_argument("--epochs", type=int, default=60)
    parser.add_argument("--batch", type=int, default=512)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--val-fraction", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=1234)
    parser.add_argument("--hidden", default="128,64,32")
    parser.add_argument("--no-stochastic", action="store_true",
                        help="детерминированный выход (2 значения вместо 4)")
    return parser.parse_args()


class AngleNet(nn.Module):
    def __init__(self, input_size, hidden, outputs):
        super().__init__()
        layers = []
        last = input_size
        for size in hidden:
            layers.append(nn.Linear(last, size))
            layers.append(nn.BatchNorm1d(size))
            layers.append(nn.ReLU())
            last = size
        layers.append(nn.Linear(last, outputs))
        self.core = nn.Sequential(*layers)

    def forward(self, x):
        return self.core(x)


def gaussian_nll(pred, target, sigma_floor=0.02):
    mu = pred[:, :2]
    log_sigma = pred[:, 2:4].clamp(-6.0, 1.0)
    sigma = torch.exp(log_sigma).clamp_min(sigma_floor)
    residual = (target - mu) / sigma
    return (0.5 * residual.pow(2) + log_sigma).mean()


def main():
    args = parse_args()
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)

    print("Чтение данных:", args.data)
    train_files, val_files = dataset.split_sessions(
        args.data, args.val_fraction, args.seed)
    if not train_files:
        raise SystemExit("Не нашёл ни одного *.jsonl в %s" % args.data)
    print("train сессий: %d, val сессий: %d" % (len(train_files), len(val_files)))

    def load(files):
        groups = []
        for path in files:
            name = os.path.splitext(os.path.basename(path))[0]
            groups.extend(dataset.build_groups(dataset.load_session(path), name))
        if not groups:
            raise SystemExit("Нет валидных семплов после фильтрации")
        x = np.asarray([f for g in groups for f in g.x], dtype=np.float64)
        y = np.asarray([v for g in groups for v in g.y], dtype=np.float64)
        return x, y, groups

    x_train, y_train, train_groups = load(train_files)
    print("train семплов: %d из %d групп" % (len(x_train), len(train_groups)))
    if val_files:
        x_val, y_val, _ = load(val_files)
        print("val семплов:   %d" % len(x_val))
    else:
        x_val = y_val = None
        print("ВНИМАНИЕ: валидация отключена (мало сессий), риск переобучения")

    mean = x_train.mean(axis=0)
    std = x_train.std(axis=0)
    std[std < 1e-6] = 1.0
    out_scale = y_train.std(axis=0)
    out_scale[out_scale < 1e-3] = 1.0
    print("std дельт (град): yaw=%.3f pitch=%.3f" % (out_scale[0], out_scale[1]))

    def tensor(x, y=None):
        xn = torch.tensor((x - mean) / std, dtype=torch.float32)
        if y is None:
            return xn
        # метки нормируем на std, чтобы мю/сигма были в одном масштабе
        yn = torch.tensor(y / out_scale[:2], dtype=torch.float32)
        return xn, yn

    hidden = [int(v) for v in args.hidden.split(",") if v.strip()]
    outputs = 2 if args.no_stochastic else 4
    model = AngleNet(dataset.INPUT_SIZE, hidden, outputs)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)

    x_tr, y_tr = tensor(x_train, y_train)
    x_va, y_va = (tensor(x_val, y_val) if x_val is not None else (None, None))

    n = len(x_tr)
    steps = max(1, n // args.batch)
    best = float("inf")
    best_state = None

    for epoch in range(1, args.epochs + 1):
        model.train()
        order = torch.randperm(n)
        total = 0.0
        for step in range(steps):
            idx = order[step * args.batch:(step + 1) * args.batch]
            if len(idx) < 2:  # BatchNorm не умеет batch=1
                continue
            opt.zero_grad()
            pred = model(x_tr[idx])
            if args.no_stochastic:
                loss = nn.functional.mse_loss(pred, y_tr[idx])
            else:
                loss = gaussian_nll(pred, y_tr[idx])
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            total += float(loss) * len(idx)
        sched.step()

        message = "epoch %3d  train %.4f" % (epoch, total / max(1, steps * args.batch))
        if x_va is not None:
            model.eval()
            with torch.no_grad():
                pred_va = model(x_va)
                val_loss = float(
                    nn.functional.mse_loss(pred_va[:, :2], y_va)
                    if args.no_stochastic
                    else gaussian_nll(pred_va, y_va)
                )
                mae = (pred_va[:, :2] - y_va).abs().mean(dim=0) * torch.tensor(
                    out_scale[:2], dtype=torch.float32)
            message += "  val %.4f  MAE yaw %.2f° pitch %.2f°" % (
                val_loss, float(mae[0]), float(mae[1]))
            score = val_loss
        else:
            score = total
        if score < best:
            best = score
            best_state = {k: v.detach().clone() for k, v in model.state_dict().items()}
        if epoch % 5 == 0 or epoch == 1:
            print(message)

    if best_state is not None:
        model.load_state_dict(best_state)
    model.eval()

    layers = fold_and_extract(model, outputs)
    os.makedirs(args.out, exist_ok=True)
    skm_path = os.path.join(args.out, args.name + ".skm")
    skm.write_skm(skm_path, dataset.INPUT_SIZE, layers)
    skm.write_meta(os.path.join(args.out, args.name + ".json"), args.name,
                   mean, std, out_scale, outputs, dataset.FEATURES,
                   extra={"hidden": hidden})
    print("Готово: %s (%d байт)" % (skm_path, os.path.getsize(skm_path)))

    if x_val is not None:
        report_validation(model, args, mean, std, out_scale, x_val, y_val, outputs)


def fold_and_extract(model, outputs):
    layers = []
    core = list(model.core)
    i = 0
    while i < len(core):
        linear = core[i]
        weight = linear.weight.detach().numpy().astype(np.float64)
        bias = linear.bias.detach().numpy().astype(np.float64)
        activation = 0
        i += 1
        if i < len(core) and isinstance(core[i], nn.BatchNorm1d):
            bn = core[i]
            gamma = bn.weight.detach().numpy().astype(np.float64)
            beta = bn.bias.detach().numpy().astype(np.float64)
            mean = bn.running_mean.detach().numpy().astype(np.float64)
            var = bn.running_var.detach().numpy().astype(np.float64)
            scale = gamma / np.sqrt(var + bn.eps)
            weight = weight * scale[:, None]
            bias = (bias - mean) * scale + beta
            i += 1
        if i < len(core) and isinstance(core[i], nn.ReLU):
            activation = 1
            i += 1
        layers.append((weight.astype(np.float32), bias.astype(np.float32), activation))
    assert layers[-1][2] == 0, "выходной слой не должен иметь активации"
    assert layers[-1][0].shape[0] == outputs
    return layers


def report_validation(model, args, mean, std, out_scale, x_val, y_val, outputs):
    model.eval()
    with torch.no_grad():
        pred = model(torch.tensor((x_val - mean) / std, dtype=torch.float32))
        mu = pred[:, :2].numpy() * out_scale[:2]
    mae = np.abs(mu - y_val).mean(axis=0)
    zero = np.abs(y_val).mean(axis=0)
    print("\nВалидация (градусы за тик):")
    print("  MAE yaw   %.3f   (baseline 'не двигаться' %.3f)" % (mae[0], zero[0]))
    print("  MAE pitch %.3f   (baseline 'не двигаться' %.3f)" % (mae[1], zero[1]))
    if outputs >= 4:
        sigma = np.exp(np.clip(pred[:, 2:4].numpy(), -6, 1)) * out_scale[:2]
        print("  sigma yaw   med %.3f  p95 %.3f" % (np.median(sigma[:, 0]),
                                                    np.percentile(sigma[:, 0], 95)))
        print("  sigma pitch med %.3f  p95 %.3f" % (np.median(sigma[:, 1]),
                                                    np.percentile(sigma[:, 1], 95)))


if __name__ == "__main__":
    main()
