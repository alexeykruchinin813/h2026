# План-промпт для Qwen-Max: Решение проблемы связности графа видимости

**Дата:** 2025-01-XX  
**Статус:** Выполнены шаги 1-3 (гибридный подход SQL+JTS)  
**Следующий исполнитель:** Qwen-Max  
**Приоритет:** P0 — критическая проблема (13/17 OKS не находят путь)

---

## Контекст задачи

Проект Heat Tracer строит маршруты тепловых сетей от 17 перспективных зданий (OKS) до существующей сети. Граф видимости разорван в плотной застройке: **13 из 17 OKS не находят путь** до точки присоединения.

**Корневая причина:** В плотной застройке (14 OKS на 500×500м) буферы запрещённых зон (5/7/9м для OKS) сливаются в единый монолит, даже при уменьшении до 1м. Visibility graph становится несвязным.

---

## Что уже сделано (выполнено текущим исполнителем)

### ✅ Шаг 1: V27 миграция — SQL функции для гибридного подхода

**Файл:** `src/main/resources/db/migration/V27__hybrid_visibility_graph.sql`

**Функция `create_escape_points()`:**
- Создаёт точки на границе буфера OKS (6м = 5м + 1м запас)
- Тип вершины: `escape_point`
- Привязка к `own_polygon_id` для применения U6 rule extension

**Функция `validate_edge_jts()`:**
- Проверяет ребро против индивидуальных полигонов (не union)
- Исключает свой полигон OKS (U6 rule extension)
- Возвращает TRUE если ребро валидно

**Функция `getRestrictionType()`:**
- Заглушка для получения типа ограничения по ID
- **Требует доработки:** нужен SQL запрос к `input_feature.properties`

### ✅ Шаг 2: HybridVisibilityGraphService.java

**Файл:** `src/main/java/ru/dit/heattracer/service/HybridVisibilityGraphService.java`

**Алгоритм:**
1. SQL создаёт coarse-граф с минимальными буферами (0.5м)
2. Добавляет escape points через `create_escape_points()`
3. Извлекаёт рёбра для валидации
4. Извлекает forbidden polygons индивидуально (не union)
5. JTS валидирует каждое ребро против истинных буферов (5/7/9м по DU)
6. Удаляет невалидные рёбра

**Параметры:**
- `oksBufferExact = 5.0` (уточняется по DU: ≤100мм→5м, ≤200мм→7м, >200мм→9м)
- `escapeBufferDist = 6.0` (5м + 1м запас)
- `escapePointsPerPolygon = 8` (количество точек на полигон)

**Проблемы реализации:**
1. ❌ `getRestrictionType()` возвращает "unknown" — нужна загрузка из БД
2. ❌ `getMinHorizontalDist()` захардкожен на 1.0м — нужно читать из `restriction_rules`
3. ❌ Нет проверки угла пересечения для road/tram_tracks (ТЗ требует ≥45°)
4. ❌ Нет расчёта `special_k` для спец. проходов

### ✅ Шаг 3: Интеграция в TaskService

**Файл:** `src/main/java/ru/dit/heattracer/service/TaskService.java`

**Изменения:**
- Добавлен `hybridVisibilityGraphService` в конструктор
- Заменён вызов `visibilityGraphService.build()` на `hybridVisibilityGraphService.buildHybrid()`
- Обновлены логи: "Building hybrid visibility graphs..."

**Статус:** Закоммичено и запушено в ветку `qc`

---

## Что требуется сделать (задачи для Qwen-Max)

### 🔴 P0: Критические исправления (блокируют работу)

#### Задача 1: Исправить `getRestrictionType()` в HybridVisibilityGraphService

**Проблема:** Метод возвращает "unknown", из-за чего все рёбра валидируются с буфером 1.0м вместо правильных значений.

**Решение:**
```java
private String getRestrictionType(Long polygonId, Map<Long, Geometry> polygons) {
    // Нужно загрузить type из input_feature.properties
    // Вариант A: Передать Map<Long, String> с типами в validateEdgesWithJTS
    // Вариант B: Сделать SQL запрос в методе extractForbiddenPolygons
}
```

**Требуется:**
1. Изменить `extractForbiddenPolygons()` для возврата `Map<Long, RestrictionInfo>` где:
   ```java
   class RestrictionInfo {
       Geometry geom;
       String type;
       Double minHorizontalDist;
       Boolean specialPassAllowed;
       Double specialK;
   }
   ```
2. Обновить `validateEdgesWithJTS()` для использования правильной информации

**Ожидаемый результат:** Рёбра валидируются с правильными буферами по типу ограничения.

---

#### Задача 2: Исправить `getMinHorizontalDist()` — чтение из restriction_rules

**Проблема:** Метод захардкожен на 1.0м, игнорируя таблицу `restriction_rules`.

**Решение:**
```java
// Загрузить restriction_rules в память при старте задачи
Map<String, RestrictionRule> rules = jdbcTemplate.queryForMap(...);

// Использовать в валидации:
double bufferDist = rules.get(restrictType).getMinHorizontalDist();
```

**Требуется:**
1. Создать класс `RestrictionRule` с полями: `type`, `min_horizontal_dist`, `crossing_forbidden`, `special_pass_allowed`, `special_k`
2. Загрузить правила в `extractForbiddenPolygons()` или отдельным методом
3. Передать в `validateEdgesWithJTS()`

---

#### Задача 3: Проверка угла пересечения для road/tram_tracks (ТЗ п. 3.3)

**Требование ТЗ:** Угол пересечения с road/tram_tracks должен быть ≥45°.

**Реализация:**
```java
private boolean validateCrossingAngle(LineString line, Geometry roadGeom) {
    // 1. Найти точку пересечения
    Geometry intersection = line.intersection(roadGeom);
    
    // 2. Вычислить угол между линией и осью дороги
    // (нужна ось дороги — ST_Centerline или аппроксимация)
    
    // 3. Проверить угол ≥ 45°
    return angleDegrees >= 45.0;
}
```

**Требуется:**
1. Добавить проверку угла в `validateEdgesWithJTS()`
2. Отбраковывать рёбра с углом < 45°
3. Логировать отброшенные рёбра

---

### 🟡 P1: Важные улучшения (без них работает, но не по ТЗ)

#### Задача 4: Расчёт special_k для спец. проходов

**Требование ТЗ:** Для рёбер, пересекающих road/tram_tracks/gas/power/heat_network, применять коэффициент `Kспец = max(k)` из всех пересечений.

**Реализация:**
```java
double maxK = 1.0;
for (RestrictionInfo info : crossedRestrictions) {
    if (info.specialPassAllowed) {
        maxK = Math.max(maxK, info.specialK);
    }
}
edge.setSpecialK(maxK);
edge.setCrossings(crossedRestrictions);
```

**Требуется:**
1. Сохранять `special_k` в атрибуты ребра
2. Передавать в D2 (поиск пути) для расчёта стоимости

---

#### Задача 5: Интеграция validate_crossing_angle() из V26

**Файл:** `src/main/resources/db/migration/V26__fix_railway_and_angle_validation.sql`

**Функция `validate_crossing_angle(edge_id, max_angle)`** уже написана в SQL.

**Требуется:**
1. Вызывать функцию после построения графа
2. Удалять/помечать рёбра с углом > max_angle
3. Либо переписать на Java для производительности

---

#### Задача 6: Тестирование на конкурсном наборе

**Данные:** `first_dataset.geojson` (144 объекта, 17 OKS)

**Требуется:**
1. Запустить задачу с `hybridVisibilityGraphService`
2. Проверить: все 17 OKS нашли путь?
3. Если нет — диагностика: какие OKS не подключились, почему?
4. Замерить время: было (V24) vs стало (V27 hybrid)

**Метрики успеха:**
- ✅ 17/17 OKS подключены
- ✅ Время ≤ 3 сек на кластер (было 5-15 сек)
- ✅ Длина маршрутов не выросла >10%

---

### 🟢 P2: Оптимизации (по времени)

#### Задача 7: Кэширование restriction_rules

**Проблема:** Правила загружаются каждый раз заново.

**Решение:**
```java
@Cacheable("restrictionRules")
public Map<String, RestrictionRule> loadRules(UUID taskId) {
    // Загрузить один раз на задачу
}
```

---

#### Задача 8: Параллелизация JTS валидации

**Проблема:** Валидация рёбер последовательная.

**Решение:**
```java
List<Long> validIds = edges.parallelStream()
    .filter(edge -> validateEdge(edge, polygons))
    .map(e -> e.id)
    .collect(Collectors.toList());
```

**Ожидаемое ускорение:** 3-5x на многоядерных CPU.

---

#### Задача 9: STRtree для forbidden polygons

**Проблема:** Проверка каждого ребра против всех полигонов O(n*m).

**Решение:**
```java
STRtree polyTree = new STRtree();
for (Map.Entry<Long, Geometry> entry : polygons.entrySet()) {
    polyTree.insert(entry.getValue().getEnvelopeInternal(), entry.getKey());
}

// Быстрый поиск кандидатов:
List<Long> candidates = polyTree.query(line.getEnvelopeInternal());
```

**Ожидаемое ускорение:** 10-50x для больших кластеров.

---

## Архитектурное решение (итоговое)

```
┌─────────────────────────────────────────────────────────┐
│                  TaskService.runCalculation()           │
│  clusters.parallelStream().forEach(cluster -> {         │
│      hybridVisibilityGraphService.buildHybrid(...)      │
│  })                                                     │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│            HybridVisibilityGraphService                 │
│                                                         │
│  1. callSqlCoarseGraph() → build_visibility_graph()    │
│     (SQL, буферы 0.5м, p_max_corners=400)              │
│                                                         │
│  2. addEscapePoints() → create_escape_points()         │
│     (SQL, точки на границе 6м буфера)                  │
│                                                         │
│  3. extractEdgesForValidation()                        │
│     (JDBC, извлечение рёбер + WKB геометрии)           │
│                                                         │
│  4. extractForbiddenPolygons()                         │
│     (JDBC, Map<Long, RestrictionInfo>)                 │
│                                                         │
│  5. validateEdgesWithJTS() ← ТУТ ВАЛИДАЦИЯ             │
│     - Загрузка restriction_rules                       │
│     - Проверка per-polygon (не union!)                 │
│     - U6 rule: skip own OKS polygon                    │
│     - Угол ≥45° для road/tram_tracks                   │
│     - Расчёт special_k                                 │
│                                                         │
│  6. removeInvalidEdges()                               │
│     (DELETE FROM visibility_edge WHERE ...)            │
└─────────────────────────────────────────────────────────┘
```

---

## Границы применимости

**Когда гибридный подход НЕ поможет:**
1. Физический зазор между зданиями < 2м (труба не пролезет)
2. Все улицы перекрыты другими ограничениями (water+gas+power)
3. OKS полностью окружён другими OKS без выхода наружу

**Что делать в таких случаях:**
- Вернуть «нет пути» для данного OKS
- Пометить в выходных данных: `status = "UNREACHABLE"`
- Предложить пользователю ручную трассировку

---

## Ссылки на файлы

| Файл | Статус | Примечание |
|------|--------|------------|
| `V27__hybrid_visibility_graph.sql` | ✅ Готово | SQL функции для escape points |
| `HybridVisibilityGraphService.java` | ⚠️ Частично | Требуются исправления P0 |
| `TaskService.java` | ✅ Готово | Интегрирован hybrid service |
| `V26__fix_railway_and_angle_validation.sql` | ✅ Готово | Функция validate_crossing_angle() |
| `restriction_rules` таблица | ✅ Готово | V7 миграция |

---

## Ожидаемые результаты после выполнения

1. ✅ **17/17 OKS подключены** (было 4/17)
2. ✅ **Время генерации:** ≤3 сек на кластер (было 5-15 сек)
3. ✅ **Соответствие ТЗ:** углы ≥45°, Kспец, отступы 5/7/9м
4. ✅ **Логирование:** понятные ошибки для unreachable OKS

---

## Контакты для вопросов

- **Репозиторий:** https://github.com/alexeykruchinin813/h2026
- **Ветка:** `qc` (все изменения закоммичены и запушены)
- **Конкурсный набор:** `first_dataset.geojson` (144 объекта)

**Готов предоставить:**
- Доступ к Docker с PostgreSQL+PostGIS
- Дамп БД с текущим состоянием
- Логи последнего запуска
- Парное программирование в любом формате
