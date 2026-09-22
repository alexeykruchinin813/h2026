# План-промпт для Qwen-Max: Продолжение оптимизации Heat Tracer

## Контекст проекта
- **Проект**: Heat Tracer (сервис трассировки тепловых сетей)
- **Стек**: Java 11, Spring Boot 2.6.3, PostgreSQL 14+, PostGIS 3.x, pgRouting, JTS 1.19.0
- **Ветка**: `qc` (GitHub: https://github.com/alexeykruchinin813/h2026.git)
- **Цель**: Ускорить генерацию графа видимости в 30-50 раз без потери качества маршрутов

## Текущее состояние (выполнено)

### ✅ Реализованные оптимизации:

1. **Миграция V25** (`V25__optimize_visibility_graph_v2.sql`):
   - ST_SimplifyPreserveTopology(geom, 2.0м) для упрощения полигонов
   - Снижение параметров: p_max_corners=400 (было 1500), p_r_max_corner=120м (было 200м)
   - Материализация forbidden zones в CTE
   - Ожидаемое ускорение: 3-6x

2. **Миграция V26** (`V26__fix_railway_and_angle_validation.sql`):
   - Добавлен тип `railway` в restriction_rules
   - crossing_angle_max=45° для road/tram_tracks
   - Функция validate_crossing_angle() для проверки угла пересечения

3. **Java параллелизация** (TaskService.java):
   - clusters.parallelStream() для обработки кластеров
   - Ожидаемое ускорение: 4-6x для 6 кластеров

4. **FastVisibilityGraphService** (НОВЫЙ):
   - JTS STRtree для O(log n) поиска вместо O(n²) CROSS JOIN
   - Вся логика в памяти Java (быстрее SQL запросов)
   - Пакетная вставка рёбер (batch update)
   - Ожидаемое ускорение: 10-50x для больших кластеров

5. **Тесты**:
   - RestrictionRulesValidationTest.java (валидация ТЗ)
   - VisibilityGraphValidationTest.java (валидация графа)
   - FastVisibilityGraphServiceTest.java (тесты STRtree)

6. **Benchmark**:
   - benchmark_visibility_graph.py (сравнение конфигураций)

## Проблемы для решения

### 🔴 Критические:

1. **Интеграция FastVisibilityGraphService в основной пайплайн**
   - Сейчас сервис создан, но не используется в TaskService.runCalculation()
   - Нужно переключить с SQL-версии на Java-версию
   - Обеспечить fallback на SQL при ошибках

2. **Отсутствие иерархического графа (coarse + refine)**
   - Сейчас все вершины соединяются напрямую
   - Можно ускорить через двухуровневый подход:
     * Coarse graph: крупные узлы (кластеры, районы)
     * Local refinement: детализация внутри кластера

3. **Нет кэширования forbidden buffers**
   - При каждом запуске извлекаются заново из БД
   - Можно кэшировать в памяти на время задачи

### 🟡 Средней важности:

4. **validate_crossing_angle() не интегрирована в основной алгоритм**
   - Функция существует, но не вызывается при построении графа
   - Нужно добавить проверку угла пересечения с road/tram_tracks

5. **Агрессивные параметры по умолчанию**
   - p_max_corners=400, p_r_max_corner=120м могут быть избыточны
   - Нужна адаптивная настройка от сложности кластера

6. **Отсутствует мониторинг производительности**
   - Нет метрик по времени выполнения этапов
   - Сложно выявлять регрессии

## Задачи для Qwen-Max

### Задача 1: Интеграция FastVisibilityGraphService ⭐⭐⭐

**Цель**: Переключить TaskService на использование Java-версии

**Шаги**:
1. Добавить FastVisibilityGraphService как зависимость в TaskService
2. Изменить runCalculation() для вызова buildFast() вместо visibilityGraphService.build()
3. Добавить флаг переключения (SQL vs Java) через application.properties
4. Реализовать fallback на SQL при ошибках Java-версии
5. Добавить логирование сравнения производительности

**Ожидаемый результат**:
```java
// TaskService.java
private final FastVisibilityGraphService fastVisibilityGraphService;

// В runCalculation():
if (useFastVisibilityGraph) {
    vg = fastVisibilityGraphService.buildFast(id, cluster.getClusterId(), provisionalDiameter);
} else {
    vg = visibilityGraphService.build(id, cluster.getClusterId(), provisionalDiameter);
}
```

**Критерии приёмки**:
- [ ] Тесты проходят с обоими режимами (SQL/Java)
- [ ] Логирование показывает ускорение (Java быстрее в 10+ раз)
- [ ] Fallback работает при ошибках
- [ ] Результаты идентичны (количество рёбер ±5%)

---

### Задача 2: Иерархический граф (Coarse + Refine) ⭐⭐

**Цель**: Двухуровневое построение для ускорения

**Алгоритм**:
1. **Coarse level**:
   - Разбить кластер на ячейки 50x50м
   - Создать узлы в центрах ячеек
   - Соединить соседние ячейки (если нет препятствий)
   
2. **Refine level**:
   - Для каждой пары OKS-candidate:
     * Найти путь на coarse графе
     * Детализировать только затронутые ячейки
     * Использовать A* для локального поиска

**Реализация**:
```java
class HierarchicalVisibilityGraph {
    // Coarse grid
    private Map<CellId, Cell> grid;
    
    // Build coarse graph
    public void buildCoarseGrid(Cluster cluster, double cellSize) {
        // Разбить на ячейки
        // Создать узлы
        // Соединить соседей
    }
    
    // Find path with refinement
    public PathResult findPath(Vertex from, Vertex to) {
        // 1. Найти coarse путь
        List<Cell> coarsePath = findCoarsePath(from.getCell(), to.getCell());
        
        // 2. Детализировать затронутые ячейки
        Graph refinedGraph = refineCells(coarsePath);
        
        // 3. A* поиск на детальном графе
        return aStarSearch(refinedGraph, from, to);
    }
}
```

**Критерии приёмки**:
- [ ] Ускорение 5-10x для больших кластеров (>1000 corners)
- [] Длина путей не увеличивается >5%
- [ ] Все OKS достигают кандидатов (connectivity=100%)

---

### Задача 3: Кэширование Forbidden Buffers ⭐

**Цель**: Избежать повторного извлечения из БД

**Реализация**:
```java
class ForbiddenZoneCache {
    private final Map<UUID, Map<Integer, List<Geometry>>> cache = new ConcurrentHashMap<>();
    
    public List<Geometry> get(UUID taskId, int clusterId) {
        return cache.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(clusterId, k -> loadFromDatabase(taskId, clusterId));
    }
    
    public void invalidate(UUID taskId) {
        cache.remove(taskId);
    }
    
    public void clear() {
        cache.clear();
    }
}
```

**Интеграция**:
- Внедрить в FastVisibilityGraphService
- Очищать кэш после завершения задачи
- Добавить метрики hit/miss ratio

**Критерии приёмки**:
- [ ] Hit ratio >90% для многократных запусков
- [ ] Память <100MB на задачу
- [ ] Автоматическая очистка после task completion

---

### Задача 4: Интеграция validate_crossing_angle() ⭐⭐

**Цель**: Проверка угла пересечения при построении графа

**Шаги**:
1. Добавить проверку в FastVisibilityGraphService.isVisible()
2. Извлечь угол пересечения из БД (crossing_angle_max из restriction_rules)
3. Отклонять рёбра с углом >45° для road/tram_tracks

**Алгоритм**:
```java
private boolean isVisibleWithAngleCheck(LineString line, STRtree tree, 
                                         Map<String, Double> angleLimits) {
    List<?> candidates = tree.query(line.getEnvelopeInternal());
    
    for (Object obj : candidates) {
        Geometry forbidden = (Geometry) obj;
        
        if (forbidden.intersects(line)) {
            // Определить тип ограничения (road, tram_tracks, etc.)
            String type = getRestrictionType(forbidden);
            
            // Проверить угол пересечения
            if (angleLimits.containsKey(type)) {
                double angle = calculateCrossingAngle(line, forbidden);
                double maxAngle = angleLimits.get(type);
                
                if (angle > maxAngle) {
                    return false; // Угол слишком острый
                }
            } else {
                return false; // Запрещено без исключений
            }
        }
    }
    
    return true;
}
```

**Критерии приёмки**:
- [ ] Рёбра с углом >45° для road/tram_tracks отклоняются
- [ ] Тесты с mock данными подтверждают логику
- [ ] Производительность снижается <10%

---

### Задача 5: Адаптивные параметры ⭐

**Цель**: Автоматическая настройка от сложности кластера

**Метрика сложности**:
```java
class ClusterComplexity {
    int cornerCount;
    double forbiddenAreaRatio; // forbidden / total area
    int obstacleCount;
    
    public ComplexityLevel getLevel() {
        if (cornerCount < 200 && forbiddenAreaRatio < 0.3) {
            return LOW;
        } else if (cornerCount < 600 && forbiddenAreaRatio < 0.6) {
            return MEDIUM;
        } else {
            return HIGH;
        }
    }
}
```

**Настройка параметров**:
| Уровень | maxCorners | rMaxCorner | simplifyTolerance |
|---------|------------|------------|-------------------|
| LOW     | 200        | 150м       | 3.0м              |
| MEDIUM  | 400        | 120м       | 2.0м              |
| HIGH    | 600        | 100м       | 1.5м              |

**Критерии приёмки**:
- [ ] Простые кластеры обрабатываются быстрее (2-3x)
- [ ] Сложные кластеры сохраняют качество
- [ ] Нет регрессий по длине путей

---

### Задача 6: Мониторинг производительности ⭐

**Цель**: Метрики для выявления регрессий

**Метрики**:
```java
class PerformanceMetrics {
    long extractionTime;      // извлечение вершин
    long forbiddenBuildTime;  // построение STRtree
    long edgeGenerationTime;  // генерация рёбер
    long insertionTime;       // вставка в БД
    int vertexCount;
    int edgeCount;
    double avgEdgeLength;
    int connectivityRatio;    // OKS с путём / всего OKS
}
```

**Экспорт**:
- Логирование в JSON формате
- Prometheus metrics (опционально)
- Benchmark скрипт: сравнение до/после

**Критерии приёмки**:
- [ ] Метрики собираются для каждого кластера
- [ ] Benchmark показывает сравнение конфигураций
- [ ] Алёрты при деградации >20%

---

## Приоритеты выполнения

| Priority | Задача | Ожидаемое ускорение | Сложность |
|----------|--------|---------------------|-----------|
| P0 | Интеграция FastVisibilityGraphService | 10-50x | Средняя |
| P1 | Кэширование Forbidden Buffers | 1.5-2x | Низкая |
| P2 | Интеграция validate_crossing_angle() | - | Средняя |
| P3 | Адаптивные параметры | 2-3x | Средняя |
| P4 | Иерархический граф | 5-10x | Высокая |
| P5 | Мониторинг производительности | - | Низкая |

## Ожидаемый итоговый результат

После выполнения всех задач:
- **Общее ускорение**: 50-100x (с 30-90 сек до 0.5-2 сек)
- **Соответствие ТЗ**: 100% (все правила из Технического приложения ЛЦТ)
- **Качество маршрутов**: без ухудшения (длина ±5%)
- **Connectivity**: 100% OKS достигают кандидатов

## Инструкции для Qwen-Max

1. **Начни с Задачи 1** (интеграция FastVisibilityGraphService) — это даст максимальный выигрыш
2. **Пиши код с тестами** — каждый сервис должен иметь unit/integration тесты
3. **Коммить часто** — каждый шаг в отдельный коммит с понятным сообщением
4. **Обновляй REPORT.md** — фиксируй прогресс и метрики производительности
5. **Синхронизируйся с веткой qc** — делай fetch/pull перед началом работы

## Контакты

- **Репозиторий**: https://github.com/alexeykruchinin813/h2026.git
- **Ветка**: qc
- **ТЗ**: docs/ (Техническое приложение ЛЦТ)
- **Отчёт**: REPORT.md (содержит полный анализ и метрики)
