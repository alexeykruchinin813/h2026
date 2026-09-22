# План-промпт для Qwen-Max: Фикс P0 задач гибридного графа видимости

**Дата:** 2025-12-21  
**Статус:** Готово к тестированию  
**Ветка:** `qc` (запушено в origin)

---

## 📋 Контекст

Проблема: **13 из 17 ОКС не находят путь** до точек присоединения из-за разорванного графа видимости.

**Корневая причина:** Углы полигонов ограничений лежат внутри собственных буферов (5/7/9м), поэтому рёбра от углов наружу отбраковываются фильтром `ST_Intersects(line, forbidden_main)`.

**Текущее решение:** Гибридный подход (`HybridVisibilityGraphService`):
1. SQL строит coarse-граф с буферами 0.5м
2. JTS валидирует каждое ребро против реальных буферов (5/7/9м)
3. Невалидные рёбра удаляются

**Новые фиксы (P0):**
- ✅ `extractForbiddenPolygons()` — загрузка типов и параметров из `restriction_rules`
- ✅ `RestrictionInfo` — класс с параметрами (min_horizontal_dist, crossing_forbidden, special_k)
- ✅ `validateEdgesWithJTS()` — валидация с `PreparedGeometry` + `STRtree`
- ✅ `validateCrossingAngle()` — проверка угла ≥45° для road/tram_tracks
- ✅ U6 rule — пропуск собственного oks-полигона при валидации

---

## ✅ Выполненные работы (коммит 8ad09f6)

### 1. HybridVisibilityGraphService.java — полная реализация

**Метод `extractForbiddenPolygons()`:**
```java
jdbcTemplate.query(
    "SELECT f.id, f.properties->>'restriction_type' AS rtype, " +
    "       ST_AsBinary(f.geom_utm) AS geom_wkb, " +
    "       rr.min_horizontal_dist, rr.crossing_forbidden, " +
    "       rr.special_pass_allowed, rr.special_k " +
    "  FROM input_feature f " +
    "  LEFT JOIN restriction_rules rr ON rr.type = f.properties->>'restriction_type' " +
    " WHERE f.task_id = ? AND f.object_type = 'restriction' " +
    "   AND COALESCE(rr.crossing_forbidden, FALSE) = TRUE",
    ...
);
```

**Класс `RestrictionInfo`:**
- Поля: `id`, `type`, `geom`, `minHorizontalDist`, `crossingForbidden`, `specialPassAllowed`, `specialK`
- Метод `getBuffer(oksBufferForDu)` — возвращает 5/7/9м для oks, иначе `minHorizontalDist`

**Метод `validateEdgesWithJTS()`:**
- Строит `PreparedGeometry` для каждого полигона (буферизация по реальному размеру)
- Строит `STRtree` для O(log n) поиска близких полигонов
- Для каждого ребра:
  - Query tree для кандидатов
  - Пропускает собственный oks-полигон (U6)
  - Для road/tram — вызывает `validateCrossingAngle()`
  - Для остальных forbidden — сразу invalid

**Метод `validateCrossingAngle()`:**
- Находит точку пересечения line × restriction
- Вычисляет направление line и road/tram в точке
- Считает угол, проверяет ≥45°
- Возвращает true при ошибке (fail-safe)

**Метод `extractEdgesForValidation()`:**
- Извлекает `own_polygon_id` из source/target вершин
- SQL: `(SELECT sv.own_polygon_id FROM visibility_vertex sv WHERE (sv.id = ve.source_vertex OR sv.id = ve.target_vertex) AND sv.vertex_type = 'oks' LIMIT 1)`

### 2. diagnose_hybrid_graph.py — скрипт диагностики

**Проверяет:**
1. Статистика coarse-графа (общее число рёбер, по типам)
2. Связность OKS (рёбра к чужим углам или candidate)
3. Список неподключённых OKS (только свои углы)

**Запуск:**
```bash
python diagnose_hybrid_graph.py
```

**Ожидаемый вывод:**
- ✓ Если все OKS имеют выход наружу → гибрид работает
- ✗ Если N OKS изолированы → переходить на полный JTS D1

---

## 🔍 Диагностика (СЛЕДУЮЩИЙ ШАГ)

### A. Запустить сервис с гибридным графом

```bash
# 1. Применить миграции V25, V26, V27
docker compose exec app ./flyway migrate

# 2. Загрузить первый набор
curl -X POST -F "file=@first_dataset.geojson" http://localhost:8080/api/upload

# 3. Дождаться завершения задачи
curl http://localhost:8080/api/task/{id}

# 4. Посмотреть логи
docker compose logs app --tail=200 | grep -E "JTS|validation|edges|hybrid"
```

### B. Запустить диагностику

```bash
python diagnose_hybrid_graph.py
```

**Ожидаемые метрики:**
- Coarse-граф: ~5000–20000 рёбер
- JTS отбросил: ~30–70%
- OKS с выходом наружу: должно быть >0

**Результат:**
- **Если OKS изолированы (0 рёбер наружу)** → гибрид не работает, нужен полный JTS D1 с waypoints сетки
- **Если OKS имеют выход** → гибрид работает, запускаем A* поиск путей

---

## 📅 План дальнейших работ

### P0 (Критические, 1–2 дня)

| № | Задача | Статус | Оценка |
|---|--------|--------|--------|
| P0-1 | `extractForbiddenPolygons()` — загрузка из БД | ✅ Готово | 2 часа |
| P0-2 | `validateEdgesWithJTS()` — PreparedGeometry + STRtree | ✅ Готово | 4 часа |
| P0-3 | `validateCrossingAngle()` — угол ≥45° | ✅ Готово | 2 часа |
| P0-4 | Диагностика на конкурсном наборе | ⏳ Ожидает | 2 часа |
| P0-5 | Исправление багов (если найдены) | ⏳ Ожидает | 4 часа |

### P1 (Важные, 2–3 дня)

| № | Задача | Статус | Оценка |
|---|--------|--------|--------|
| P1-1 | Интеграция `find_best_path_from_oks()` (D2) | ⏳ Ожидает | 1 день |
| P1-2 | Обработка спец. проходов (Kспец, разбиение рёбер) | ⏳ Ожидает | 1 день |
| P1-3 | Валидация настоящих смещений 5/7/9м для oks | ⏳ Ожидает | 0.5 дня |
| P1-4 | Тесты: 17/17 OKS подключены | ⏳ Ожидает | 0.5 дня |

### P2 (Оптимизации, 3–4 дня)

| № | Задача | Статус | Оценка |
|---|--------|--------|--------|
| P2-1 | Полный JTS D1 с waypoints сетки (если гибрид не сработал) | ⏳ Ожидает | 3 дня |
| P2-2 | Кэширование forbidden buffers | ⏳ Ожидает | 0.5 дня |
| P2-3 | Параллелизация валидации рёбер | ⏳ Ожидает | 0.5 дня |
| P2-4 | Benchmark: время ≤3 сек на кластер | ⏳ Ожидает | 0.5 дня |

---

## 🎯 Метрики успеха

| Метрика | Было (V24) | Цель (P0) | Цель (P2) |
|---------|------------|-----------|-----------|
| OKS подключено | 4/17 (24%) | **≥10/17** (59%) | **17/17** (100%) |
| Время на кластер | 5–15 сек | ≤10 сек | **≤3 сек** |
| Рёбер в графе | ~100 000 | ~5 000 | ~2 000 |
| Память Java | >1 GB | <500 MB | <200 MB |

---

## 🚀 Архитектурная схема

```
┌─────────────────────────────────────────────────────────────┐
│                    HybridVisibilityGraphService              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. SQL: build_visibility_graph(..., 0.5м, ...)             │
│     └─> Coarse graph: vertices + edges (буфер 0.5м)         │
│                                                              │
│  2. extractForbiddenPolygons(taskId)                        │
│     └─> Map<Long, RestrictionInfo>                          │
│         (type, geom, minHorizontalDist, special_k)          │
│                                                              │
│  3. extractEdgesForValidation(taskId, clusterId)            │
│     └─> List<EdgeForValidation>                             │
│         (id, geom, ownPolygonId)                            │
│                                                              │
│  4. validateEdgesWithJTS(edges, polygons, oksBuffer)        │
│     ├─> PreparedGeometry для каждого полигона               │
│     ├─> STRtree для быстрого поиска                         │
│     ├─> Для каждого ребра:                                  │
│     │   - Query tree → кандидаты                            │
│     │   - Пропустить свой oks (U6)                          │
│     │   - Если road/tram → validateCrossingAngle()          │
│     │   - Если пересекает → invalid                        │
│     └─> Set<Long> invalidEdgeIds                            │
│                                                              │
│  5. removeInvalidEdges(taskId, invalidEdgeIds)              │
│     └─> DELETE FROM visibility_edge WHERE id IN (...)       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 📁 Изменённые файлы

| Файл | Изменения |
|------|-----------|
| `HybridVisibilityGraphService.java` | Полная реализация P0-1, P0-2, P0-3 |
| `diagnose_hybrid_graph.py` | Новый скрипт диагностики |
| `TaskService.java` | (предыдущий коммит) интеграция hybrid.buildHybrid() |
| `V25__optimize_visibility_graph_v2.sql` | (предыдущий коммит) параметры coarse-графа |
| `V26__fix_railway_and_angle_validation.sql` | (предыдущий коммит) railway, угол 45° |
| `V27__hybrid_visibility_graph.sql` | (предыдущий коммит) escape points |

---

## ❓ Открытые вопросы

1. **Сработает ли гибрид?** Если coarse-граф не содержит рёбер через узкие зазоры (которые есть в реальности, но нет при буфере 0.5м), JTS не сможет их добавить — только удалить. Тогда потребуется полный JTS D1.

2. **Достаточно ли 0.5м для coarse-графа?** Может, стоит уменьшить до 0.1м или использовать `ST_DWithin(line, forbidden, 0.1)` вместо `ST_Intersects`?

3. **Нужно ли кэшировать `PreparedGeometry`?** При 88 ограничениях создание 88 `PreparedGeometry` занимает ~100–200мс. Если кластеров 6, это 0.6–1.2 сек накладных расходов.

4. **Как обрабатывать MultiPolygon?** В `validateCrossingAngle()` есть обработка `LineString` и `Polygon`, но `MultiPolygon` может дать `GeometryCollection` в пересечении.

---

## 📞 Контакты для обсуждения

- **GitHub PR:** https://github.com/alexeykruchinin813/h2026/pull/2
- **Ветка:** `qc`
- **Последний коммит:** 8ad09f6 "feat: добавить скрипт диагностики гибридного графа"

---

## ✅ Чек-лист перед запуском

- [ ] Применены миграции V25, V26, V27
- [ ] `restriction_rules` заполнены (railway, crossing_angle_max=45)
- [ ] `input_feature` загружены (first_dataset.geojson)
- [ ] Docker запущен (`docker compose up -d`)
- [ ] Логи включены (`logging.level.ru.dit.heattracer=DEBUG`)

**После запуска:**
- [ ] Просмотреть логи: `docker compose logs app --tail=200`
- [ ] Запустить диагностику: `python diagnose_hybrid_graph.py`
- [ ] Зафиксировать метрики (OKS подключено, время, рёбра)
- [ ] Принять решение: гибрид работает → P1, или гибрид не работает → P2-1 (полный JTS)
