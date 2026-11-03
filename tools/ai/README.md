# AI rotation tooling

Офлайн-часть ИИ-ротации SkidMyau. Мод сам ничего не обучает: он только
пишет сырые сессии и грузит готовые веса из
`src/main/resources/assets/skidmyau/ai/`.

```
raw/*.jsonl  --(train.py)-->  angles-v2.skm + angles-v2.json
                              aimpoint-v1.skm + aimpoint-v1.json
                                   |
                              (validate.py)  -> MAE + детектор "человек/бот"
```

## 1. Сбор данных

1. Собери мод, зайди на сервер **без читов** (важно: нужна настоящая
   человеческая моторика, синтетика бесполезна).
2. Включи модуль `AI` и свойство `record`.
3. Играй 10–20 сессий по 10–15 минут: арена PvP, слизни (обучение на
   слизнях — как в плане), мосты/скаффолд, обычные удары по блокам.
   Разные `mouse-sensitivity` тоже полезны.
4. Файлы появятся в `./config/SkidMyau/ai/raw/<session>.jsonl`.

Фильтр `record-kind` позволяет писать только сущности (`ENTITY`) или
только блоки (`BLOCK`) — но для полной модели лучше `ALL`.

## 2. Обучение

```bash
pip install -r requirements.txt

python train.py \
  --data "C:/.../config/SkidMyau/ai/raw" \
  --out  "../src/main/resources/assets/skidmyau/ai" \
  --epochs 60
```

Что делает `train.py`:

* читает все `*.jsonl`, режет на группы `(сессия, key)` — цели не
  перемешиваются между train/val (иначе утечка и завышенные метрики);
* собирает те же 26 фич, что и `RotationPredictor.fillFeatures` (порядок
  менять нельзя — он зашит в Java);
* учит MLP 26→128→64→32→4 с Gaussian NLL (мю + logσ по каждой оси);
* фолдит BatchNorm в линейные слои и пишет `.skm` (формат `SKM1`, LE);
* пишет `.json` с нормализацией и `outputScale` (σ в градусах).

## 3. Проверка

```bash
python validate.py --data "C:/.../raw" --model "../src/main/resources/assets/skidmyau/ai"
```

Считает MAE по осям на held-out сессиях и обучает простой детектор
«человек/бот» на оконных статистиках дельт. Критерий приёмки из
`docs/AI_ROTATION_PLAN.md`: **AUC ≤ 0.60, KS ≤ 0.05**.

## 4. Установка весов

Просто положи `angles-v2.skm/.json` и `aimpoint-v1.skm/.json` в
`src/main/resources/assets/skidmyau/ai/` и пересобери мод. Имена файлов
задаются свойствами `model` и `aim-model` у модуля `AI`.

Без весов работает `BOOTSTRAP` — баллистическая доводка с человеческим
профилем. Она нужна только чтобы проверить интеграцию, а не чтобы играть.
