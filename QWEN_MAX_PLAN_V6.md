# План-промпт для нового диалога (QWEN MAX) — версия 6

**Дата:** 2026-09-24
**Ветка:** `qc` (GitHub: https://github.com/alexeykruchinin813/h2026)
**Роль агента:** senior Java-разработчик / геоинженер. У агента НЕТ доступа к репозиторию — все необходимые исходники перечислены в разделе 8 и прикладываются к этому промпту.

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

Цепочка `JVM → docker-java → Docker Engine 29.8 (API 1.44) → Testcontainers → PostGIS+pgRouting → Spring → Flyway (27 миграций)` работает. Ключевые решения, уже влитые в код:

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

## 5. Задачи текущего этапа

### P0 (сейчас)
- **P0-4. Диагностика связности на конкурсном наборе БЕЗ Python.** Реализовать JUnit-тест `HybridConnectivityIT`: загрузить `first_dataset.geojson` (JSON парсится Jackson, никаких внешних инструментов), построить гибрид-граф и проверить, сколько ОКС имеют хотя бы одно ребро к ЧУЖИМ вершинам (escape/candidate/чужие corner). Метрика: было 4/17, цель ≥10/17 (P0), 17/17 (P2).
- **P0-5. Прогон всех тестов зелёным.** Покрытие тестами:

| Тестовый класс | Что покрывает | Статус |
|---|---|---|
| `FastVisibilityGraphServiceTest` (6) | STRtree, видимость, R_MAX по типам пар, пакетная вставка рёбер | ✅ зелёный |
| `RestrictionRulesValidationTest` (7) | таблица restriction_rules, параметры отступов, ФФ validate_crossing_angle | нужен прогон |
| `VisibilityGraphValidationTest` (6) | схемы visibility_vertex/edge, функции построения графа | нужен прогон |
| `DockerApiProbeTest` | доступность Docker API 1.44 | ✅ зелёный |
| `HybridVisibilityGraphServiceTest` (4, НОВЫЙ) | extractForbiddenPolygons (фильтр crossing_forbidden), углы 90°/30°, fail-safe | требует сверки сигнатур с продом |
| `PathFinderServiceTest` (3, НОВЫЙ) | findPath found/notFound, длина/геометрия пути, findPathsFromOks | требует прогона |
| `HybridConnectivityIT` (P0-4, ПЛАНИРУЕТСЯ) | 17 ОКС на конкурсном наборе | не написан |

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

| Метрика | Было (V24) | Цель P0 | Цель P2 |
|---|---|---|---|
| ОКС подключено | 4/17 | ≥10/17 | 17/17 |
| Время на кластер | 5–15 с | ≤10 с | ≤3 с |
| Рёбер в графе | ~100 000 | ~5 000 | ~2 000 |
| Память JVM | >1 ГБ | <500 МБ | <200 МБ |

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
