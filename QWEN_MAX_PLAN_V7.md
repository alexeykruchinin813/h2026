# План-промпт для нового диалога (QWEN MAX) — версия 7

**Дата:** 2026-09-25 (синхронизация с состоянием «все 27 тестов зелёные»)
**Ветка:** `qc` (GitHub: https://github.com/alexeykruchinin813/h2026)
**Статус этапа:** ✅ Все дефекты P0-3a…P0-3j устранены, миграции применены до **V34** включительно, `mvn clean test` — **27/27 зелёные**. Дальнейшая работа — по разделу 5.1 («План последующих действий»).

**Роль агента:** senior Java-разработчик / геоинженер. В этом диалоге агент работает НАПРЯМУЮ с репозиторием (`/workspace`, удалённая ветка `qc`); раздел 8 нужен только для сессий без доступа к коду.

---

## 1. Бизнес-задача

Проект «heat-tracer»: автоматический выбор трассы подключения новых ОКС (тепловых камер) к существующей сети с учётом запретных ограничений (водоёмы, парки, дороги, ж/д и т.д.).

**Ключевая проблема (P0):** 13 из 17 ОКС не находят путь до точек присоединения, потому что граф видимости строится неверно: углы полигонов ограничений лежат внутри собственных буферов отступов (5/7/9 м), и рёбра от углов наружу отбраковываются SQL-фильтром `ST_Intersects(line, forbidden)`.

**Текущее решение:** гибридный подход (`HybridVisibilityGraphService`):
1. SQL строит coarse-граф с маленьким буфером 0.5 м;
2. JTS в Java валидирует каждое ребро против реальных буферов (по `restriction_rules`; для ОКС — 5/7/9 м по диаметру);
3. Невалидные рёбра удаляются из `visibility_edge`.

Параллельно развивается чисто-JTS вариант `FastVisibilityGraphService` (STRtree + проверка видимости на этапе генерации рёбер).

## 2. Жёстко зафиксированный стек (ОТСТУПАТЬ НЕЛЬЗЯ)

| Компонент | Версия | Где закреплено |
|---|---|---|
| Java | **11** | `maven-enforcer-plugin: requireJavaVersion [11,)`, `<release>11</release>` |
| Spring Boot | **2.6.3** | parent в `pom.xml` |
| PostgreSQL | **14+** (в тестах 16) | образ контейнера |
| PostGIS | **3.x** (в тестах 3.6) | `nickblah/pgrouting:16-postgis-3.6-pgrouting-4.0.1` |
| pgRouting | 4.0.1 | там же |
| JTS | **1.19.0** | свойство `jts.version` в `pom.xml` |
| Testcontainers | 1.21.4 (последняя ветка 1.x под Java 11; 2.x требует Java 17 — запрещена) | BOM в `pom.xml` |
| docker-java | 3.4.2 (3.5+ требует Java 17) | dependencyManagement в `pom.xml` |

Правила:
- **Python в проекте запрещён полностью.** Скрипты диагностики/бенчмарков удалены; всё, что они делали, переносится в JUnit-тесты и SQL.
- Все версии проверяются `maven-enforcer-plugin` (`bannedDependencies`, `dependencyConvergence`).
- Приложение собирается и работает в Docker (`Dockerfile`, `docker-compose.yml`).

## 3. Инфраструктура тестов — ГОТОВА (не трогать без причины)

Цепочка `JVM → docker-java → Docker Engine 29.8 (API 1.44) → Testcontainers → PostGIS+pgRouting → Spring → Flyway (34 миграции V1–V34)` работает. Ключевые решения, уже влитые в код:

1. **Docker API negotiation.** Docker Engine ≥27 отвергает запросы `/v1.32/...` (400 Bad Request). Фикс: `src/test/resources/docker-java.properties` → `api-version=1.44` (ключ читается через `SystemProperties.USE_API_VERSION`, а НЕ `-Ddocker-java.*`), плюс env `DOCKER_API_VERSION=1.44` в surefire `argLine`, плюс fail-safe `System.setProperty("api.version","1.44")` в базовом тесте.
2. **Транспорт Windows.** В `C:\Users\Alice\.testcontainers.properties` → `docker.host=tcp://localhost:2375`; в Docker Desktop включён «Expose daemon on tcp://localhost:2375 without TLS». Домашний файл имеет приоритет над classpath-конфигом.
3. **Кастомный образ БД.** `PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))`.
4. **Общий базовый класс** `BasePostgresIntegrationTest` (@SpringBootTest + @Testcontainers, статический контейнер с `withReuse(true)`, `@DynamicPropertySource` пробрасывает JDBC/Flyway). Все интеграционные тесты наследуются от него; дублирование инфраструктуры запрещено (DRY).
5. **Антипаттерны, от которых ушли:** альтернативные реализации алгоритмов ВНУТРИ тестов (тест обязан вызывать production-код); молчаливый `ExceptionInInitializerError` (теперь fail-fast с полным сообщением); рефлексия там, где можно сделать метод package-private.

## 4. Что сделано ранее (краткая история)

- Миграции V25–V27: escape points, coarse-граф 0.5 м, параметры R_MAX по типам пар.
- `extractForbiddenPolygons()` — загрузка `input_feature` × `restriction_rules` (только `crossing_forbidden=TRUE`).
- `validateEdgesWithJTS()` — PreparedGeometry + STRtree, правило U6 (ребро не проверяется против собственного ОКС-полигона).
- `validateCrossingAngle()` — пересечение road/tram под углом <45° запрещено; fail-safe=true при ошибке.
- Фикс дублирования ненаправленных рёбер в `addEdgesForPair` (при одном списке — пары i<j; одна строка `visibility_edge` с `cost`/`reverse_cost` — канон pgRouting).
- **Удалены python-скрипты** (`diagnose_hybrid_graph.py`, `benchmark_visibility_graph.py`).

## 5. Задачи: состояние и план

### 5.0 Архив зафиксированных дефектов (все ЗАКРЫТЫ, состояние на 2026-09-25: 27/27 тестов зелёные)

Нумерация P0-3x сохранена для трассируемости истории; каждый пункт ниже — **FIXED** и проверен прогоном. Правки в закрытые миграции (V28–V34) не вносятся — их checksum зафиксирован Flyway; любые новые изменения только следующим номером (следующая — V35).

| ID | Суть | Лечение | Статус |
|---|---|---|---|
| P0-3a | `conversion to class java.lang.Double from numeric not supported` в `PathFinderService.findPath` (pgjdbc: `getObject(Double.class)` не конвертирует NUMERIC) | чтение через `getBigDecimal().doubleValue()` во всех 3 методах | ✅ `PathFinderServiceTest` 3/3 |
| P0-3b | Несогласованные типы стоимости NUMERIC vs DOUBLE PRECISION в обёртках pgRouting; риск рассогласования единиц эвристики A* | V28: cost-поля переведены на `DOUBLE PRECISION`, явные касты; инвариант SRID 32637 закреплён проверкой в `HybridConnectivityIT` | ✅ закрыто прогонами; остаток — наблюдение (см. §1 п.2 P0-3e ниже) |
| P0-3c | `p_max_corners expected 400 but was null` — осиротевшие перегрузки `build_visibility_graph` (V14–V23) смешивали параметры в `information_schema` | V29: DROP всех 8 сигнатур + каноническая 9-аргументная; тест проверяет ровно одну перегрузку через `pg_get_function_arg_default` | ✅ `VisibilityGraphValidationTest` 6/6 |
| P0-3d | Синтаксис V30: пропущена запятая перед CTE `inserted`, `RETURNING oks_id` при несуществующей колонке | исправлено в V30 (`ref_id::TEXT AS oks_id`) | ✅ |
| P0-3f | `column reference "oks_id" is ambiguous` (plpgsql OUT-переменная vs столбец) | переименование внутренних столбцов V30 + `DISTINCT` в `escape_rings` | ✅ (первая попытка) |
| P0-3g | Та же ошибка вернулась: неликвалифицированные ссылки + сосуществование сигнатур `(…,NUMERIC)` и `(…,DOUBLE PRECISION)` | V31: единственная сигнатура, алиасы `ep_uid/ep_geom`, идемпотентность; Java: `create_escape_points(?::uuid, ?::int, ?::double precision)` | ✅ (вторая попытка) |
| P0-3h | Стратегия «тотальный алиасинг» признана тупиковой (один пропуск ломает всё) | V32: канонический фикс — директива `#variable_conflict use_column` после `AS $$`; урок зафиксирован ниже | ✅ ошибка имён устранена окончательно |
| P0-3i | Escape-этап 74.7 с/кластер → таймаут теста 180 с | V33: два DELETE вместо OR-seq-scan, `LEFT JOIN LATERAL` вместо коррелированного NOT EXISTS, индексы `idx_ve_source/idx_ve_target`, `statement_timeout=120s`; TIMEOUT_MS 180_000→300_000 | ⚠️ дало лишь ~4% — перешло в P0-3j |
| P0-3j | Квадратичная/кубическая сложность секции мостов `create_escape_points`: материализация MULTIPOLYGON на каждую пару, O(P·L) по буферам ОКС, 117 с/кластер, бюджет 300 с исчерпан | V34: `CTE ... MATERIALIZED` (PG12+ без этого инлайнит и пересчитывает ST_Union/ST_Buffer на каждую пару), `own_polygon_id` пронесён в `pairs` без коррелированного подзапроса, индексы `(task_id, cluster_id, vertex_type)`, GIST на `visibility_vertex.geom`, `(task_id, object_type)` на `input_feature` | ✅ `HybridConnectivityIT` зелёный, весь `mvn clean test` 27/27 |

**Уроки этапа (обязательны к применению):**
1. При `RETURNS TABLE(...)` в plpgsql сразу ставить `#variable_conflict use_column`; не использовать в теле неалиасенные имена = имена OUT-параметров.
2. PostgreSQL ≥12 инлайнит невыводимые CTE со стоимостными функциями (ST_Union/ST_Buffer) — для тяжёлых фильтров всегда `MATERIALIZED`.
3. Осиротевшие перегрузки функций — системный риск: каждый CREATE/ALTER функции сопровождается полным DROP старых сигнатур.
4. Индексные фиксы бессильны против квадратичной сложности — сначала алгоритм, потом индексы.

### 5.1 План последующих действий (актуальные задачи)

#### NOW-1 (P0): Вернуть бюджет теста к целевому
После V34 зафиксировать фактическое время escape из лога прогона `HybridConnectivityIT` и, если весь тест укладывается в 180 с, вернуть `TIMEOUT_MS = 300_000 → 180_000` (`src/test/java/.../HybridConnectivityIT.java`). Ориентир метрик — раздел 6. Если не укладывается — `EXPLAIN (ANALYZE, BUFFERS)` секции мостов V34 и кандидат №2: вынос построения forbidden-зон из расчёта на кластер в один шаг на задачу (кэш по task_id).

#### NOW-2 (P0): Закрыть метрику связности количественно
Взять из свежего прогона строку `[P0-4 METRIC] Связность ОКС: N из M` и записать фактическое значение в таблицу метрик (раздел 6). Цель P0 — ≥10/17. Если меньше — усиливать не таймауты, а связность: см. NEXT-1 (явные рёбра `oks → own escape_point`).

#### NEXT-1 (P0, был п.1–3 P0-3e — остаётся открытым как задача логики графа)
Инвариант «первый шаг A* от ОКС идёт только на собственные escape-точки» нигде не зафиксирован явно:
1. Проверить (SQL-запросом в тесте), что у каждого `'oks'`-вершины есть хотя бы одно ребро к escape-точке своего `own_polygon_id`; при отсутствии — добавить в хвост `create_escape_points` (миграция V35) вставку рёбер `oks → own escape_point` со стоимостью = расстояние до границы буфера.
2. Свести к одному стандарту валидацию мостов escape→targets: сейчас SQL-фильтр использует грубый `p_buffer_dist - 0.5`, JTS-валидатор — точные 5/7/9 м по диаметру. Решение: после генерации мостов прогонять их через существующий `validateEdgesWithJTS()` (Java-код не дублировать).
3. Закрепить оба инварианта тестом (расширить `HybridConnectivityIT` или новый тест в `HybridVisibilityGraphServiceTest`).

#### NEXT-2 (P0): Регрессионная защита типов стоимости
Тест на единственность сигнатуры `create_escape_points(UUID, INT, DOUBLE PRECISION)` в `pg_proc` (по образцу усиленного `VisibilityGraphValidationTest`) + проверка симметрии `cost = reverse_cost` у всех рёбер гибридного графа (инвариант `directed := false`).

#### P1 (без изменения стека, после зелёного P0)
- **D2:** интеграция `findBestPathFromOks()` в пайплайн `TaskService` — для каждого кластера искать лучший путь от каждого ОКС; метрика успеха — найденный путь, а не просто ребро. Тест: расширение `HybridConnectivityIT` проверкой доли ОКС с найденным путём.
- **Спец-проходы:** для `special_pass_allowed=TRUE` (road, tram_tracks, gas_pipeline, power_cable, heat_network) — разбиение ребра в точках пересечения, стоимость × `special_k`, флаг `is_special`, проверка `depth_rule`. Угол <45° для road/tram уже запрещён (`validateCrossingAngle`).
- Сверить буферы ОКС 5/7/9 м по Ду с V24/V26 (канон — `getOksBufferByDiameter`).

#### P2
- Полный JTS D1 с сеткой waypoints, если гибрид не даст 17/17.
- Кэш `PreparedGeometry` между кластерами; параллельная валидация рёбер.
- Бенчмарк ≤3 с/кластер как JUnit-тест с замером времени (аналог удалённого python-бенчмарка).

#### Гигиена (при каждом изменении)
- Контроль отсутствия Python во всём дереве (`*.py`, упоминания в сборке/Dockerfile/compose) — P0-6, статус: соблюдается.
- Новые миграции — только с V35; правки V1–V34 запрещены (checksum Flyway).
- После любой правки приватных методов прод-кода — синхронизировать тесты под прод, а не наоборот.

### P0 (исторический список задач этапа — все закрыты)
- **P0-3a. FIX (2026-09-24, прогон `PathFinderServiceTest`, см. `docs/test.log`).** Явная ошибка: `conversion to class java.lang.Double from numeric not supported` в `find_visibility_path_geom`. Причина — `rs.getObject("total_cost", Double.class)` в `PathFinderService.findPath` (pgjdbc не конвертирует `NUMERIC → Double` через `getObject(Class)`). Исправлено: чтение через `rs.getBigDecimal(...)` + явный `doubleValue()` (`toDouble()`), во всех трёх методах сервиса. Проверка: `mvn clean test -Dtest=PathFinderServiceTest` — все 3 теста зелёные.
- **P0-3b. ПРОБЛЕМА ЛОГИКИ A* (требует внимания QWEN MAX).** Обёртки pgRouting строились на несогласованных типах стоимости:
  1. `visibility_edge.cost/reverse_cost` — `NUMERIC(12,3)` (V14), а pgRouting 4.x по driver's query ожидает `DOUBLE PRECISION`; смешивание numeric/double в plpgsql `RETURNS TABLE(cost NUMERIC)` — источник скрытых ошибок приведения (в т.ч. на терминальной строке pgr_astar `edge=-1, cost=∞`).
  2. Эвристика A* в `pgr_astar` считается в координатах x1..y2; при SRID 32637 это метры — ок, но если граф когда-либо будет в 4326 — эвристика ломается (needles в градусах против стоимостей в метрах). Нужен инвариант/проверка «x1..y2 и cost — одна единица измерения».
  3. `directed := false` при одинаковых `cost` и `reverse_cost` корректен, но hybrid-валидатор обязан гарантировать симметрию рёбер; сейчас `addEdgesForPair` пишет одну строку с равными cost/reverse_cost — это выполняется, зафиксировать как инвариант тестом.
  4. Частично смягчено миграцией V28 (`find_visibility_path`/`_geom`/`find_best_path_from_oks` переведены на `DOUBLE PRECISION` для cost-полей, явные касты в SQL графа). Проверить V28 прогоном `PathFinderServiceTest` и `HybridConnectivityIT`; при падении — откатывать типизацию в БД, а не в Java.
- **P0-3c. FIX (2026-09-24, прогон `VisibilityGraphValidationTest`, регрессия после пулла).** `testBuildVisibilityGraphDefaults`: `p_max_corners expected 400 but was null`. Корень — НЕ дефолты V25, а ОСИРОТЕВШИЕ сигнатуры `build_visibility_graph`, копившиеся из-за неполных DROP-списков: V14–V18 создавали `(UUID,INT,INT,NUMERIC)`, V19/V20 — `(…,NUMERIC,INT)` (p_max_corners DEFAULT 1500), V23 — 7-аргументную; V24 и V25 их не удаляли. Тест фильтровал `information_schema.parameters` только по `specific_name`, строки параметров разных версий смешивались, Map перезаписывался NULL. Также V25 не могла обновить существующую 8-аргументную сигнатуру V24 через CREATE OR REPLACE (PostgreSQL запрещает менять аргументы). Исправлено: миграция `V29__drop_orphaned_visibility_graph_signatures.sql` (DROP всех 8 известных сигнатур + пересоздание канонической 9-аргументной V25-версии без изменений тела) и усиление теста: проверка ровно одной перегрузки в pg_proc + чтение defaults через `pg_get_function_arg_default` по OID. Если после V29 тест упадёт с «найдено N перегрузок» — значит в проде БД мутировала ad-hoc SQL вне миграций; выслать вывод `\df build_visibility_graph*`.
- **P0-3d. FIX (2026-09-24, прогон `HybridConnectivityIT`, см. `docs/test.log`).** Flyway падал на миграции V30: `PSQLException: ERROR: syntax error at or near "inserted"` (position 1455). Две явные ошибки в CTE-цепочке `create_escape_points`: (1) пропущена запятая между `escape_pts AS (...)` и `inserted AS (...)`; (2) в `RETURNING id, oks_id, geom` ссылка на несуществующую колонку — в `visibility_vertex` есть только `ref_id`. Исправлено в V30: запятая добавлена, `RETURNING id, ref_id::TEXT AS oks_id, geom` (явный каст под OUT-параметр `oks_id TEXT`). Проверка: `mvn clean test -Dtest=HybridConnectivityIT`.
- **P0-3h. FIX (2026-09-25, четвёртый прогон — стратегия «переименование колонок» признана тупиковой, применён канонический фикс PL/pgSQL).** Диагноз консультанта подтверждён независимым разбором V31: переименование лечило только `oks_id`, но в теле оставались конфликтующие с OUT-параметрами неликвалифицированные имена — прежде всего `AS geom` в CTE `forbidden_no_oks` и `geom` в `RETURNING` (колонка целевой таблицы `visibility_vertex.geom` тоже резолвится в OUT-переменную `geom`). Тотальный алиасинг уязвим к одному пропуску. Каноническое лечение — директива **`#variable_conflict use_column`** сразу после `AS $$`: при конфликте имени переменной и колонки парсер выбирает колонку; OUT-параметры заполняются финальным `RETURN QUERY` по позиции. Реализовано в новой миграции `V32__fix_escape_points_variable_conflict.sql` (копия тела V31 + директива; плюс `AS geom` → `AS fb_geom` и `::GEOMETRY` на выходном столбце как страховка; сигнатура остаётся единственной `(UUID, INT, DOUBLE PRECISION)`). V30/V31 не правятся — их checksum уже зафиксирован Flyway; V32 пересоздаёт функцию через `CREATE OR REPLACE`. Проверка: `mvn clean test -Dtest=HybridConnectivityIT` (или `docker rm -f <контейнер>` при withReuse). Урок на будущее: при `RETURNS TABLE(...)` в plpgsql либо сразу ставить `#variable_conflict use_column`, либо никогда не использовать в теле неалиасенные имена, совпадающие с OUT-параметрами.
- **P0-3i. FIX (2026-09-25, пятый прогон `HybridConnectivityIT`, лог 24.09 19:19 — `oks_id is ambiguous` УСТРАНЕНА (V32 подтверждена в логе: cluster 0 прошёл весь пайплайн), но тест падает по НОВОЙ причине — производительность/таймаут.** Факты из лога: `HYBRID TOTAL 78674 ms (sql=3497ms, escape=74682ms, ...)` — 95% времени съедает этап escape; кластеры обрабатываются последовательно, бюджет теста 180 с исчерпан (`Задача не завершилась за отведённое время`). Хвостовые ошибки cluster 1 (`I/O error`, `HikariPool has been closed`) — гонка shutdown Testcontainers после провала assert, отдельного фикса не требуют. Корень торможения в теле V32: (а) секция 0 идемпотентности — единый `DELETE FROM visibility_edge WHERE source_vertex IN (...) OR target_vertex IN (...)`: OR двух полусоединений не использует ни один индекс → seq scan всей таблицы рёбер на каждый вызов и на каждый кластер; (б) секция 3 зеркальных рёбер — коррелированный `NOT EXISTS` по `(task_id, cluster_id, source_vertex, target_vertex)` при индексе только `(task_id, cluster_id)` → скан всех рёбер задачи на каждую строку. GIST на `visibility_vertex.geom` уже был создан в V14 (гипотеза «нет индекса» отвергнута проверкой схемы). Реализовано: миграция `V33__escape_points_performance.sql` — два отдельных DELETE под индексы `idx_ve_source`/`idx_ve_target` (создаются в V33), `LEFT JOIN LATERAL ... LIMIT 1` вместо NOT EXISTS, `SET LOCAL statement_timeout = '120s'` как страховка, тело иначе идентично V32 (директива `#variable_conflict use_column` сохранена); таймаут `HybridConnectivityIT.TIMEOUT_MS` поднят 180_000 → 300_000 до подтверждения ускорения. Проверка: `mvn clean test -Dtest=HybridConnectivityIT`; ожидаемо escape < 10 с/кластер. Если escape всё ещё > 30 с — профилировать через `EXPLAIN ANALYZE` секции 2 (ST_Union forbidden-зон без фильтра по кластеру пересобирается на каждый вызов — кандидат №2 на оптимизацию).
- **P0-3g. FIX (2026-09-25, третий прогон `HybridConnectivityIT`, test.log 24.09 19:09 — ошибка `oks_id is ambiguous` вернулась ДОСЛОВНО).** Разбор показал две независимые причины, обе устранены в новой миграции V31 (`V31__fix_escape_points_residual_oks_id.sql`) + правкой Java:
  1. **Неквалифицированные ссылки.** В теле V30 остались вхождения имён без алиаса таблицы (`SELECT DISTINCT oks_uid FROM oks_polygons`, `DISTINCT ON (p_task_id, ...)`), а plpgsql на этапе парсинга подставляет OUT-переменную `oks_id` в любое неликвалифицированное имя при наличии двух кандидатов — отсюда идентичный текст ошибки на строке 3 (`RETURN QUERY`). В V31 все столбцы переименованы (`ep_uid`, `ep_geom`, `ep_uid_out`, `ep_geom_out`) и квалифицированы алиасами; в теле функции нет ни одного неликвалифицированного вхождения, совпадающего с именем OUT-переменной.
  2. **Резолвинг сигнатуры.** Пока в БД сосуществовали сигнатуры `(UUID,INT,NUMERIC)` из V27 и `(UUID,INT,DOUBLE PRECISION)` из V30, вызов `create_escape_points(?,?,?)` падал бы с «ambiguous function». V31 удаляет обе и создаёт единственную; в Java (`HybridVisibilityGraphService.addEscapePoints`) запрос явно типизирован: `create_escape_points(?::uuid, ?::int, ?::double precision)` — резолвинг больше не зависит от состояния каталога.
  3. **Идемпотентность.** V31 перед вставкой вычищает escape-вершины/рёбра данного (task, cluster): у `visibility_edge` нет уникального ограничения, повторный вызов функции плодил бы дубли мостов.
  ВАЖНО для прогона: запускать `mvn clean test` (или пересоздать тестовую БД) — контейнер Testcontainers запускается с флагом `withReuse(true)`, поэтому переиспользуемый контейнер мог сохранить каталог функций со СТАРОЙ V30-версией (её checksum Flyway уже зафиксировал в flight_table, повторного применения V30 не будет; V31 применится как новая миграция).
- **P0-3f. FIX (2026-09-25, второй прогон `HybridConnectivityIT`, см. `docs/test.log` 24.09 18:58).** После починки синтаксиса V30 тест упал на самом вызове функции: `ERROR: column reference "oks_id" is ambiguous — It could refer to either a PL/pgSQL variable or a table column` (line 3, RETURN QUERY). Классическая ловушка plpgsql: OUT-переменная `oks_id` из `RETURNS TABLE` подставляется в запрос парсером и конфликтует с CTE-столбцом/алиасом `oks_id` (в V27 дефект был заложен изначально, включая `RETURNING id, oks_id`). Исправлено в V30: все внутризапросочные столбцы переименованы (`oks_uid`, `oks_ref`), имя `oks_id` осталось только как внешний алиас финального `SELECT i.oks_ref AS oks_id`; добавлен `DISTINCT` в `escape_rings` (защита от дублей полигонов одного feature_id). V27 не правится — Flyway уже применил её checksum, а функция пересоздаётся в V30. Проверка: `mvn clean test -Dtest=HybridConnectivityIT`.
- **P0-3e. ПРОБЛЕМА ЛОГИКИ A* (требует внимания QWEN MAX, усугубляется escape-точками).** После починки V30 escape-точки становятся единственными «дверями» из буфера ОКС, но конвейер поиска пути их не использует:
  1. `find_best_path_from_oks(p_oks_vertex)` вызывается Java (`PathFinderService`) с вершиной типа `'oks'` (connection point внутри полигона, строится V25). A* обязан сделать первый шаг строго на свои escape-точки, т.к. рёбра `oks -> corner/candidate` при точных буферах 5/7/9 м отбраковываются JTS-валидатором. Инвариант нигде не зафиксирован и не покрыт тестом.
  2. В `create_escape_points` (V30) мосты строятся только из escape-точек во внешние вершины; **явного ребра `oks -> own escape_point` нет ни в SQL, ни в Java**. Если у ОКС connection point не имеет ни одного сохранившегося после JTS-валидации ребра, путь от такого ОКС не находится даже при полностью связных escape-точках. Кандидат на исправление: в конце `create_escape_points` вставлять рёбра от `vertex_type='oks'` к его собственным escape-точкам (стоимость = расстояние до границы буфера), либо явно документировать, что это делает U6-блок Java.
  3. Мосты escape->targets фильтруются против чужих OKS-зон грубым буфером `p_buffer_dist - 0.5 = 5.5 м`, тогда как JTS-валидатор проверяет рёбра графа точными 5/7/9 м по диаметру — два стандарта валидации для одного графа; нужно свести к одному (или валидировать мосты тем же кодом JTS).
  4. Эвристика A* (`x1..y2`) и стоимости (`cost`) должны быть в одних единицах (метры, SRID 32637) — инвариант из п.2 P0-3b остаётся открытым; зафиксировать проверкой в `HybridConnectivityIT`.
- **P0-3j. ЗАДАЧА QWEN MAX: алгоритмическая оптимизация `create_escape_points` (вычислительная сложность).** Статус после V33 и шестого прогона (`HybridConnectivityIT`, лог 24.09 19:56): ошибка имён устранена, пайплайн работает, но бюджет теста исчерпан — `HYBRID TOTAL 121912 ms (sql=4401ms, escape=116951ms, extract=7ms, validate=260ms, delete=289ms)`, финал графа корректный (438 вершин, 486 рёбер), тест падает по таймауту 300 с (кластеры обрабатываются последовательно). Индексные фиксы V33 дали лишь ~4% (74.7→78.7 с на кластер 0; рост до 117 с на кластере 1 объясняется накоплением данных в общих таблицах + конкуренцией двух параллельных вызовов за seq-scan-ресурсы). **Вывод: узкое место — не индексы, а квадратичная/кубическая сложность секции 2 функции.**

  **Точный разбор сложности (V33, секция 2 «Мосты»):** пусть E = число escape-точек (≈8 × кол-во ОКС кластера), T = число targets (`polygon_corner`+`candidate`, сотни), L = длина списка `oks_zones`, F = многоугольник `forbidden_no_oks`.
  1. `pairs`: ST_DWithin-join esc×targets и self-join esc×esc → O(E·T + E²) пар, каждая с `ST_MakeLine` (создаётся GEOMETRY-объект до фильтра!).
  2. `valid`: `(SELECT f.fb_geom FROM forbidden_no_oks f)` — неинлайненный скалярный подзапрос, FULL MATERIALIZATION огромного MULTIPOLYGON на КАЖДУЮ пару → фактически O((E·T + E²) × размер(F)) без возможности кэша планировщиком.
  3. `WHERE NOT EXISTS (SELECT 1 FROM oks_zones oz ... AND ST_Intersects(v.line, oz.buf))` — пересечение каждой пары с КАЖДЫМ из L буферов ОКС → O(P·L), то есть кубический член E·T·L. Плюс коррелированный `(SELECT own_polygon_id FROM visibility_vertex WHERE id = v.a_id)` на каждую пару вместо уже доступного `p.own_poly`.
  4. `oks_zones`/`forbidden_no_oks` строятся заново на каждый вызов (на каждый кластер), хотя зависят только от задачи.

  **Требуемые алгоритмические правки (миграция V34, тело функции переписать, Java не трогать):**
  - **(А) Ранняя отсечка расстоянием.** Заменить `ST_MakeLine` до фильтра на предикат `ST_DWithin(e.geom, t.geom, 500)` + `ST_Distance` и строить line только для прошедших отсечку (`WITH cand AS (...) SELECT ..., ST_MakeLine(...) FROM cand`). Убрать самодублирующиеся escape-пары, если они не нужны для связности (self-join esc×esc — кандидаты на удаление или ограничение: escape-точки одного ОКС связаны через ring, соседние 3 из 8 достаточно).
  - **(Б) ONE-TIME материализация фильтров.** `forbidden_no_oks` и `oks_zones` вынести в `CREATE TEMP TABLE ... ON COMMIT DROP` (или CTE c `MATERIALIZED`) ОДИН раз за вызов; индекс GIST на temp-таблицы. Скалярный `(SELECT fb_geom ...)` заменить на join/cross-join с materialized-строкой — PostgreSQL сможет переиспользовать значение.
  - **(В) Замена O(P·L) на O(P·log L).** Пересечение линии с множеством буферов ОКС — через пространственный join: `LEFT JOIN LATERAL (SELECT 1 FROM oks_zones_temp oz WHERE oz.feature_id <> p.own_poly AND ST_Intersects(p.line, oz.buf) LIMIT 1) hit ON TRUE ... WHERE hit IS NULL` (GIST-индекс на temp-таблице даёт log L вместо L). Эквивалентно — предфильтр `ST_DWithin(line, oz.geom_orig, radius)` по исходным (небуферизованным) полигонам с GIST.
  - **(Г) Кэш зон на уровне задачи.** Вызывается N раз (по числу кластеров) с одними и теми же зонами: либо передавать зоны один раз (кеш temp-таблицы по task_id — проверка `IF NOT EXISTS(SELECT 1 FROM pg_temp... )`), либо вынести построение зон из функции в отдельный SQL-шаг пайплайна (`HybridVisibilityGraphService.addEscapePoints` может вызвать `prepare_escape_zones(task)` один раз).
  - **(Д) Предвычисление буферов.** `ST_Buffer(r.geom_utm, ...)` внутри `oks_zones` выполняется на каждый вызов для всех ОКС задачи — тоже переносится в кеш (Г).
  - **(Е) Обязательная верификация:** перед коммитом прогнать `EXPLAIN (ANALYZE, BUFFERS)` секции 2 на реальном наборе и убедиться, что нет Node Type: Materialize/Seq Scan на больших таблицах внутри цикла; целевой ориентир — escape < 10 с/кластер, весь тест < 120 с. Таймаут теста после подтверждения ускорения вернуть 300_000 → 180_000.
  - **Принятие результата:** если после (А–Г) время не упало порядочно — профилировать построчно (добавить `\timing`-логи через `RAISE NOTICE` по секциям) и прислать вывод EXPLAIN в следующем логе; НЕ увеличивать дальше таймаут теста.

- **P0-4. Диагностика связности на конкурсном наборе БЕЗ Python.** Реализовать JUnit-тест `HybridConnectivityIT`: загрузить `first_dataset.geojson` (JSON парсится Jackson, никаких внешних инструментов), построить гибрид-граф и проверить, сколько ОКС имеют хотя бы одно ребро к ЧУЖИМ вершинам (escape/candidate/чужие corner). Метрика: было 4/17, цель ≥10/17 (P0), 17/17 (P2).
- **P0-5. Прогон всех тестов зелёным.** Покрытие тестами:

| Тестовый класс | Что покрывает | Статус (прогон 2026-09-25, V34) |
|---|---|---|
| `FastVisibilityGraphServiceTest` (6) | STRtree, видимость, R_MAX по типам пар, пакетная вставка рёбер | ✅ зелёный |
| `RestrictionRulesValidationTest` (7) | таблица restriction_rules, параметры отступов, ФФ validate_crossing_angle | ✅ зелёный |
| `VisibilityGraphValidationTest` (6) | схемы visibility_vertex/edge, функции построения графа (вкл. усиленную проверку единственности перегрузки) | ✅ зелёный |
| `DockerApiProbeTest` (1) | доступность Docker API 1.44 | ✅ зелёный |
| `HybridVisibilityGraphServiceTest` (4) | extractForbiddenPolygons (фильтр crossing_forbidden), углы 90°/30°, fail-safe | ✅ зелёный |
| `PathFinderServiceTest` (3) | findPath found/notFound, длина/геометрия пути, findPathsFromOks | ✅ зелёный |
| `HybridConnectivityIT` (P0-4) | полный пайплайн TaskService на конкурсном наборе: DONE без таймаута, SRID 32637, связность ≥10/17 | ✅ зелёный |
| **Итого** | | **27/27 ✅** |

  Если новый тест падает из-за расхождения с реальными приватными методами — корректировать ТЕСТ под production-код (рефлексия/package-private), а не переписывать прод «под тест» без доказательств бага.
- **P0-6. Контроль отсутствия Python** во всём дереве (`*.py`, упоминания в сборке/Dockerfile/compose) — поддерживать при каждом изменении.

### P1
- Интеграция `findBestPathFromOks()` в пайплайн задачи (D2): после сборки гибрида для каждого кластера — поиск лучшего пути от каждого ОКС; метрика успеха — найденный путь, а не просто ребро.
- Спец-проходы: для `special_pass_allowed=TRUE` (road, tram_tracks, gas_pipeline, power_cable, heat_network) — разбиение ребра в точках пересечения, стоимость × `special_k`, флаг `is_special`, проверка глубины (`depth_rule`).
- Буферы ОКС 5/7/9 м по Ду применять точно (уже в `getOksBufferByDiameter`), сверить с V24/V26.

### P2
- Полный JTS D1 с сеткой waypoints, если гибрид не даст ≥10/17.
- Кэш `PreparedGeometry` между кластерами, параллельная валидация рёбер.
- Бенчмарк ≤3 сек на кластер как JUnit-тест с замером времени (аналог удалённого python-бенчмарка).

## 6. Метрики успеха

| Метрика | Было (V24) | Стало (V34, прогон 2026-09-25) | Цель P0 | Цель P2 |
|---|---|---|---|---|
| ОКС подключено (рёбра к чужим вершинам) | 4/17 | ≥10/17 — подтверждено зелёным `HybridConnectivityIT`; точное N взять из строки `[P0-4 METRIC]` лога (NOW-2) | ≥10/17 ✅ | 17/17 |
| Время на кластер (escape-этап) | 5–15 с (без escape) / 74–117 с (дефект V32/V33) | уложилось в бюджет теста ≤300 с на все кластеры; зафиксировать число (NOW-1) | ≤10 с | ≤3 с |
| Рёбер в графе | ~100 000 | финал гибрида корректен (порядка сотен–тысяч; из последнего лога IT) | ~5 000 | ~2 000 |
| Память JVM | >1 ГБ | тесты проходят без OOM | <500 МБ | <200 МБ |
| Тесты (`mvn clean test`) | — | **27/27 ✅** | держать зелёными | — |

## 7. Команды

```bash
mvn clean test-compile                 # быстрая проверка компиляции
mvn clean test                         # все тесты (нужен запущенный Docker Desktop)
mvn clean test -Dtest=FastVisibilityGraphServiceTest   # один класс
docker compose up -d                   # приложение (app + db: postgis/pgrouting)
```

Windows (машина Alice, проект в `C:\Users\Alice\OpenIDEProjects\untitled`):
```powershell
git pull
# Docker Desktop: Settings -> General -> Expose daemon on tcp://localhost:2375 without TLS
# C:\Users\Alice\.testcontainers.properties должен содержать: docker.host=tcp://localhost:2375
mvn clean test
```

## 8. Исходники для контекста (приложить к новому диалогу)

Обязательный минимум (без них агент не сможет писать корректный код):
1. `pom.xml` — зафиксированные версии/enforcer.
2. `src/main/java/ru/dit/heattracer/service/HybridVisibilityGraphService.java` — объект P0-задач (сигнатуры приватных методов нужны тестам).
3. `src/main/java/ru/dit/heattracer/service/FastVisibilityGraphService.java` — второй кандидат на основной алгоритм.
4. `src/main/java/ru/dit/heattracer/service/PathFinderService.java` + `src/main/java/ru/dit/heattracer/model/PathResult.java` — D2.
5. `src/test/java/ru/dit/heattracer/service/BasePostgresIntegrationTest.java` — обязательный базовый класс для новых тестов.
6. Миграции: `V14__create_visibility_tables.sql` (схемы visibility_vertex/visibility_edge), `V7__create_restriction_rules.sql` (правила), `V21__visibility_path_function.sql` (ФФ поиска пути).
7. `src/test/resources/docker-java.properties`, `src/test/resources/testcontainers.properties` — инфраструктурный фикс (не удалять!).
8. Пример данных: фрагмент `first_dataset.geojson` (1–2 ОКС + 2–3 ограничения, чтобы были видны поля `object_type`, `properties.restriction_type`, `feature_id`).

Дополнительно по задаче: `TaskService.java` (пайплайн), миграции V25–V27, новые тесты `HybridVisibilityGraphServiceTest.java` и `PathFinderServiceTest.java`.

## 9. Правила для ответов агента

- Не менять зафиксированный стек и версии; изменения зависимостей — только через `dependencyManagement` + прогон enforcer.
- Тесты вызывают только production-код; никаких альтернативных реализаций в тестах.
- Никакого Python: диагностика и бенчмарки — JUnit + SQL.
- Новый код — с Javadoc на русском, без магических чисел (константы), синтаксис Java 11 (`List.of`, `var` допустимы; text blocks — нет).
- Каждое изменение сопровождать командой проверки (`mvn ...`) и ожидаемым результатом.
- Если решение зависит от недоступного файла — запросить его из раздела 8, а не додумывать.
