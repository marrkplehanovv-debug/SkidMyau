"""Разбор сырых сессий и сборка фич.

Порядок и нормировка 26 фич здесь ОБЯЗАНЫ совпадать с
`skidmyau.ai.RotationPredictor#fillFeatures` — модель не хранит имена фич,
только индексы.
"""

import json
import math
import os
from dataclasses import dataclass, field

FEATURES = [
    "total_yaw",          # 0
    "total_pitch",        # 1
    "prev_dyaw",          # 2
    "prev_dpitch",        # 3
    "player_speed",       # 4
    "distance",           # 5
    "target_motion_x",    # 6
    "target_motion_y",    # 7
    "target_motion_z",    # 8
    "on_ground",          # 9
    "move_error",         # 10
    "mouse_step",         # 11
    "kind_entity",        # 12
    "kind_block",         # 13
    "kind_scaffold",      # 14
    "kind_idle",          # 15
    "face_u",             # 16
    "face_v",             # 17
    "face_width",         # 18
    "tower",              # 19
    "aim_error",          # 20
    "target_age",         # 21
    "hurt_time",          # 22
    "click_age",          # 23
    "pitch_confidence",   # 24
    "visible",            # 25
]

INPUT_SIZE = len(FEATURES)
OUTPUT_SIZE = 4  # mu_yaw, mu_pitch, log_sigma_yaw, log_sigma_pitch

KIND_IDLE, KIND_ENTITY, KIND_BLOCK, KIND_SCAFFOLD = 0, 1, 2, 3


def wrap180(angle):
    """MathHelper.wrapAngleTo180_float."""
    value = math.fmod(angle, 360.0)
    if value >= 180.0:
        value -= 360.0
    if value < -180.0:
        value += 360.0
    return value


@dataclass
class Group:
    """Непрерывный кусок одной цели: (сессия, прогон ключа)."""
    name: str
    x: list = field(default_factory=list)
    y: list = field(default_factory=list)


def load_session(path):
    rows = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    rows.sort(key=lambda r: r.get("tick", 0))
    return rows


def build_groups(rows, session_name, drop_skip=True, max_delta=60.0):
    """Собирает фичи/метки и режет сессию на группы по смене цели."""
    groups = []
    current = None
    prev_dyaw = 0.0
    prev_dpitch = 0.0
    last_key = None
    age = 0
    last_tick = None

    for row in rows:
        tick = row.get("tick", 0)
        key = row.get("key", 0) or 0
        kind = row.get("kind", KIND_IDLE)
        # Непрерывность: тик не пропущен и нет смены цели.
        continuous = last_tick is not None and tick == last_tick + 1 and key == last_key
        if key != last_key or not continuous:
            age = 0
            last_key = key
            if current is not None:
                groups.append(current)
                current = None
        age += 1
        last_tick = tick

        dyaw = row.get("dyaw", 0.0)
        dpitch = row.get("dpitch", 0.0)
        skip = bool(row.get("skip", True))

        if current is None:
            current = Group(name="%s#%d" % (session_name, len(groups)))

        if skip or abs(dyaw) > max_delta or abs(dpitch) > max_delta:
            prev_dyaw, prev_dpitch = dyaw, dpitch
            continue
        if kind == KIND_IDLE:
            prev_dyaw, prev_dpitch = dyaw, dpitch
            continue

        if current.x and not continuous:
            groups.append(current)
            current = Group(name="%s#%d" % (session_name, len(groups)))

        current.x.append(build_features(row, age, prev_dyaw, prev_dpitch))
        current.y.append((dyaw, dpitch))
        prev_dyaw, prev_dpitch = dyaw, dpitch

    if current is not None and current.x:
        groups.append(current)
    return [g for g in groups if len(g.x) >= 16]


def build_features(row, age, prev_dyaw, prev_dpitch):
    kind = row.get("kind", KIND_IDLE)
    yaw = row.get("yaw", 0.0)
    pitch = row.get("pitch", 0.0)
    target_yaw = row.get("tyaw", yaw)
    target_pitch = row.get("tpitch", pitch)
    total_yaw = wrap180(target_yaw - yaw)
    total_pitch = max(-90.0, min(90.0, target_pitch - pitch))

    pmx = row.get("pmx", 0.0)
    pmz = row.get("pmz", 0.0)
    speed = math.hypot(pmx, pmz)
    move_error = 0.0
    if speed > 0.01:
        move_yaw = math.degrees(math.atan2(-pmx, pmz))
        move_error = wrap180(move_yaw - target_yaw) / 180.0

    feats = [
        total_yaw / 180.0,
        total_pitch / 90.0,
        prev_dyaw / 30.0,
        prev_dpitch / 20.0,
        speed * 20.0,
        row.get("dist", 3.0),
        row.get("tmx", 0.0) * 20.0,
        row.get("tmy", 0.0) * 20.0,
        row.get("tmz", 0.0) * 20.0,
        1.0 if row.get("onGround") else 0.0,
        move_error,
        row.get("step", 0.0) / 0.5,
        1.0 if kind == KIND_ENTITY else 0.0,
        1.0 if kind == KIND_BLOCK else 0.0,
        1.0 if kind == KIND_SCAFFOLD else 0.0,
        1.0 if kind == KIND_IDLE else 0.0,
        (row.get("fu", 0.5) - 0.5) * 2.0,
        (row.get("fv", 0.5) - 0.5) * 2.0,
        row.get("faceW", 0.0) / 90.0,
        1.0 if row.get("tower") else 0.0,
        row.get("aimErr", 0.0) / 90.0,
        min(age, 40) / 20.0,
        row.get("hurt", 0) / 10.0,
        min(row.get("clk", 1000), 40) / 20.0,
        (90.0 - abs(pitch)) / 90.0,
        1.0 if row.get("vis", True) else 0.0,
    ]
    return feats


def load_all(data_dir, verbose=True):
    """Возвращает список групп из всех jsonl-файлов каталога."""
    groups = []
    files = sorted(
        os.path.join(data_dir, name)
        for name in os.listdir(data_dir)
        if name.endswith(".jsonl")
    )
    for path in files:
        session = os.path.splitext(os.path.basename(path))[0]
        rows = load_session(path)
        session_groups = build_groups(rows, session)
        if verbose:
            ticks = sum(len(g.x) for g in session_groups)
            print("  %-24s %6d строк -> %3d групп / %6d семплов"
                  % (session, len(rows), len(session_groups), ticks))
        groups.extend(session_groups)
    return groups


def split_sessions(data_dir, val_fraction=0.2, seed=1234):
    """Делит по ФАЙЛАМ (сессиям), а не по семплам — иначе утечка."""
    import random

    files = sorted(
        os.path.join(data_dir, name)
        for name in os.listdir(data_dir)
        if name.endswith(".jsonl")
    )
    rng = random.Random(seed)
    rng.shuffle(files)
    cut = max(1, int(round(len(files) * val_fraction)))
    return files[cut:], files[:cut]
