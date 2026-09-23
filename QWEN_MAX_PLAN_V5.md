# План-промпт для Qwen-Max: Гибридный граф видимости — статус и следующие шаги

**Дата:** 2026-09-24
**Статус:** Тестовая инфраструктура полностью рабочая, 6/6 тестов зелёные
**Ветка:** `qc` (запушено в origin)
**Предыдущий план:** `QWEN_MAX_PLAN_V4.md` (P0-1..P0-3 выполнены, P0-4/P0-5 частично)

---

## 📋 Контекст проблемы (без изменений)

**Проблема:** 13 из 17 ОКС не находят путь до точек присоединения из-за разорванного графа видимости.

**Корневая причина:** Углы полигонов ограничений лежат внутри собственных буферов (5/7/9м), поэтому рёбра от углов наружу отбраковываются фильтром `ST_Intersects(line, forbidden_main)`.

**Решение:** Гибридный подход (`HybridVisibilityGraphService`): SQL coarse-граф (буфер 0.5м) → JTS-валидация рёбер против реальных буферов → удаление невалидных.

---

## ✅ Что уже сделано (подтверждено прогонами)

### 1. Функциональность P0 (коммиты 8ad09f6, 2a67969)
- `extractForbiddenPolygons()` — загрузка типов/параметров из `restriction_rules`
- `RestrictionInfo` — min_horizontal_dist, crossing_forbidden, special_k, getBuffer() 5/7/9м
- `validateEdgesWithJTS()` — PreparedGeometry + STRtree
- `validateCrossingAngle()` — угол ≥45° для road/tram_tracks, fail-safe
- U6 rule — пропуск собственного oks-полигона при валидации
- `diagnose_hybrid_graph.py` — скрипт диагностики связности OKS

### 2. Тестовая инфраструктура (итерации d5362e7 → e5d8c7b) — ЗАКРЫТО ✅
Все грабли пройдены, зафиксировать как решённые, **к ним не возвращаться**:

| Проблема | Решение | Где закреплено |
|---|---|---|
| `ExceptionInInitializerError` в тестах | Docker API-version negotiation: Docker Engine 29.x отвергает `/v1.32/info` (400 Bad Request) | `docker-java.properties` (`api.version=1.44`) + страховка в статик-блоке `BasePostgresIntegrationTest` |
| Windows npipe-транспорт ломает запросы | TCP: `docker.host=tcp://localhost:2375` | `src/test/resources/testcontainers.properties` (+ Docker Desktop: Expose daemon on tcp://2375 without TLS) |
| Конфликт Jackson 2.13 vs docker-java | Jackson BOM 2.18.4 в `dependencyManagement` | `pom.xml` |
| Дублирование Testcontainers boilerplate | Абстрактный `BasePostgresIntegrationTest`: Docker-барьер с понятным сообщением, общий контейнер pgrouting, `@DynamicPropertySource`, отказ от рефлексии (package-private методы) | `src/test/java/.../BasePostgresIntegrationTest.java` |
| «Not a compatible substitute for postgres» | `DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres")` | `BasePostgresIntegrationTest` |
| Дубли рёбер (1→2 и 2→1) при вызове addEdgesForPair одним списком | Защита `sameList` → только i<j; граф неориентированный, обратное направление через `reverse_cost` (вариант A) | `FastVisibilityGraphService.addEdgesForPair` |

### 3. Текущее состояние тестов
```
Tests run: 6, Failures: 0, Errors: 0 — BUILD SUCCESS (подтвердить финальным прогоном)
```
Цепочка работает целиком: Docker API 1.44 → Testcontainers 1.21.4 → контейнер
`nickblah/pgrouting:16-postgis-3.6-pgrouting-4.0.1` → Flyway (27 миграций) → Spring контекст → тесты.

### 4. Зафиксированный стек (ТЗ, менять нельзя!)
Java 11 (Spring Boot 2.6.3 требует; локально допускается JDK 17 для сборки, enforcer `[11,)`),
Spring Boot 2.6.3, PostgreSQL 14+, PostGIS 3.x, pgRouting, JTS 1.19.0.
Testcontainers жёстко 1.21.4 (ветка 2.x требует Java 17 — недоступна).
maven-enforcer-plugin контролирует совместимость (`bannedDependencies`, `dependencyConvergence`).

---

## ⚠️ Открытые вопросы / технические долг

1. **Проверить симметричные вызовы в production-коде.** После фикса `addEdgesForPair` убедиться, что основной сборщик графа (`buildVisibilityGraph` и др.) нигде не вызывает метод дважды с зеркальными списками (A,B) и (B,A) — иначе дубли вернутся. Прогнать все тесты: `mvn clean test`.
2. **`testcontainers.reuse.enable=true`** в `C:\Users\Alice\.testcontainers.properties` — ускорит прогоны (~2 сек + старт контейнера на запуск). Не блокер.
3. **Ротация секретов:** оба засвеченных токена (ghp_..., github_pat_...) отозвать/перевыпустить; PAT передавать только через secure storage, не в чат.
4. В логах безвредны: `SocketException: Socket closed` (Ryuk закрывает стрим логов), предупреждение про reuse.

---

## 🔍 СЛЕДУЮЩИЙ ШАГ: P0-4 — Диагностика гибридного графа на конкурсном наборе

Это то, ради всего остального: проверить, что гибрид реально соединяет OKS.

### A. Полный цикл на реальном наборе
```bash
# 1. Поднять стенд
docker compose up -d
# 2. Миграции V25-V27 применены Flyway при старте — проверить лог
# 3. Загрузить первый набор
curl -X POST -F "file=@first_dataset.geojson" http://localhost:8080/api/upload
# 4. Дождаться завершения задачи
curl http://localhost:8080/api/task/{id}
# 5. Логи гибридной валидации
docker compose logs app --tail=300 | grep -E "JTS|validation|edges|hybrid"
```

### B. Диагностика графа
```bash
python diagnose_hybrid_graph.py
```

**Ожидаемые метрики:**
- Coarse-граф: ~5 000–20 000 рёбер
- JTS отбросил: ~30–70%
- **OKS с выходом наружу: >0** (было 4/17)

**Решение по результату:**
- OKS изолированы (0 рёбер наружу) → гибрид не работает → переход на P2-1 (полный JTS D1 с waypoints сетки)
- OKS имеют выход → гибрид работает → запускаем A* поиск путей (P1-1)

---

## 📅 Обновлённый план работ

### P0 (Критические)
| № | Задача | Статус | Оценка |
|---|--------|--------|--------|
| P0-1 | `extractForbiddenPolygons()` | ✅ Готово | — |
| P0-2 | `validateEdgesWithJTS()` (PreparedGeometry + STRtree) | ✅ Готово | — |
| P0-3 | `validateCrossingAngle()` ≥45° | ✅ Готово | — |
| P0-4 | Диагностика на конкурсном наборе | ⏳ **ТЕКУЩИЙ ШАГ** | 2 часа |
| P0-5 | Исправление багов, найденных диагностикой | ⏳ Ожидает | 4 часа |
| P0-6 | Восстановление CI-тестов в Docker-контейнере (были отключены из-за docker.sock) | ⏳ Ожидает | 2 часа |

### P1 (Важные, 2–3 дня)
| № | Задача | Статус | Оценка |
|---|--------|--------|--------|
| P1-1 | Интеграция `find_best_path_from_oks()` (A*, D2) | ⏳ Ожидает | 1 день |
| P1-2 | Спец. проходы (Kспец, разбиение рёбер) | ⏳ Ожидает | 1 день |
| P1-3 | Валидация смещений 5/7/9м для oks | ⏳ Ожидает | 0.5 дня |
| P1-4 | Тест: 17/17 OKS подключены (интеграционный, поверх BasePostgresIntegrationTest) | ⏳ Ожидает | 0.5 дня |

### P2 (Оптимизации, 3–4 дня)
| № | Задача | Статус | Оценка |
|---|--------|--------|--------|
| P2-1 | Полный JTS D1 с waypoints сетки (если гибрид не сработал в P0-4) | ⏳ Резерв | 3 дня |
| P2-2 | Кэширование forbidden buffers / PreparedGeometry | ⏳ Ожидает | 0.5 дня |
| P2-3 | Параллелизация валидации рёбер | ⏳ Ожидает | 0.5 дня |
| P2-4 | Benchmark: ≤3 сек на кластер | ⏳ Ожидает | 0.5 дня |

---

## 🎯 Метрики успеха
| Метрика | Было (V24) | Цель (P0) | Цель (P2) |
|---------|------------|-----------|-----------|
| OKS подключено | 4/17 (24%) | **≥10/17** (59%) | **17/17** (100%) |
| Время на кластер | 5–15 сек | ≤10 сек | **≤3 сек** |
| Рёбер в графе | ~100 000 | ~5 000 | ~2 000 |
| Память Java | >1 GB | <500 MB | <200 MB |
| mvn clean test | ❌ (упадал на инициализации) | ✅ зелёные | ✅ в CI |

---

## 🚀 Архитектура HybridVisibilityGraphService (без изменений)
```
1. SQL build_visibility_graph(..., 0.5м) → coarse-граф
2. extractForbiddenPolygons(taskId)      → Map<Long, RestrictionInfo>
3. extractEdgesForValidation(taskId, clusterId) → List<EdgeForValidation> (id, geom, ownPolygonId)
4. validateEdgesWithJTS(...)             → Set<Long> invalidEdgeIds
   ├─ PreparedGeometry + STRtree
   ├─ U6: пропуск своего oks-полигона
   └─ road/tram → validateCrossingAngle() (≥45°, fail-safe)
5. removeInvalidEdges(taskId, ids)       → DELETE FROM visibility_edge
```

---

## 📁 Ключевые файлы
| Файл | Назначение |
|------|-----------|
| `service/HybridVisibilityGraphService.java` | Гибридная валидация (P0-1..P0-3) |
| `service/FastVisibilityGraphService.java` | Быстрый граф на STRtree; `addEdgesForPair` — фикс дублей |
| `test/.../BasePostgresIntegrationTest.java` | Общая Testcontainers-инфраструктура (не трогать без причины) |
| `test/resources/testcontainers.properties` | `docker.host=tcp://localhost:2375` |
| `test/resources/docker-java.properties` | `api.version=1.44` |
| `pom.xml` | Зафиксированный стек + enforcer-правила |
| `diagnose_hybrid_graph.py` | Диагностика связности OKS (P0-4) |

---

## ❓ Открытые вопросы (архитектурные, из V4 — остаются)
1. Если coarse-граф не содержит рёбер через узкие реальные зазоры — JTS их не добавит, только удалит → нужен P2-1.
2. Достаточно ли 0.5м для coarse? Варианты: 0.1м или `ST_DWithin(line, forbidden, 0.1)`.
3. Кэширование `PreparedGeometry` (88 полигонов ≈ 100–200мс × кластеры).
4. MultiPolygon → `GeometryCollection` в пересечении в `validateCrossingAngle()`.

---

## ✅ Чек-лист перед прогоном P0-4
- [ ] `git pull` (HEAD ≥ e5d8c7b), `mvn clean test` — все тесты зелёные
- [ ] Docker Desktop запущен, TCP 2375 доступен (`Invoke-RestMethod http://localhost:2375/version`)
- [ ] C:\Users\Alice\.testcontainers.properties НЕ содержит npipe/transport.type (только docker.host и опц. reuse)
- [ ] `restriction_rules` заполнены (railway, crossing_angle_max=45)
- [ ] first_dataset.geojson готов к загрузке
- [ ] Логи DEBUG: `logging.level.ru.dit.heattracer=DEBUG`

**После прогона:** зафиксировать метрики (OKS подключено, время, число рёбер) → решение: P1 (гибрид работает) или P2-1 (полный JTS).

---

## 🔗 Ссылки
- GitHub PR: https://github.com/alexeykruchinin813/h2026/pull/2
- Ветка: `qc`, история фиксов инфраструктуры: `d5362e7 → bad5891 → c3c2382 → 4bbdcf8 → 8157566 → 89d3b54 → 76f941a → 02d4004 → 8618609 → d0fb837 → e5d8c7b`
- Лог последнего прогона: `docs/test.log`
