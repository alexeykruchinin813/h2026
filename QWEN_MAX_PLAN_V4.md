# План-промпт V4: Состояние проекта и обновлённые задачи

**Дата:** 2026-09-24
**Статус:** Ожидает прогона тестов на машине разработчика (нужен Docker Desktop)
**Ветка:** `qc`

---

## 📋 Контекст проблемы

Пользователь не мог запустить интеграционные тесты:

```
mvn clean test -Dtest=FastVisibilityGraphServiceTest
...
[ERROR] ru.dit.heattracer.service.FastVisibilityGraphServiceTest ? ExceptionInInitializer
java.lang.ExceptionInInitializerError
    at FastVisibilityGraphServiceTest.<clinit>(FastVisibilityGraphServiceTest.java:40)
```

**Корневая причина:** Testcontainers **1.19.3** использует docker-java с транспортом,
несовместимым с новыми версиями Docker Engine/Desktop (API 25–28). Статический
инициализатор контейнера падает ещё до запуска Spring-контекста →
`ExceptionInInitializerError` в `<clinit>` строки 40 (`@Container static PostgreSQLContainer`).

---

## ✅ Выполненные исправления

### 1. pom.xml — обновление стека Testcontainers
- `org.testcontainers:postgresql`: **1.19.3 → 1.20.4**
- Явная фиксация `com.github.docker-java:docker-java-api:3.4.0` (test scope)
- Явная фиксация `com.github.docker-java:docker-java-transport-zerodep:3.4.0` (test scope)

### 2. FastVisibilityGraphServiceTest.java
- Добавлена проверка доступности Docker перед запуском контекста
  (`DockerClientFactory.instance().isDockerAvailable()` + `assumeTrue`) —
  без Docker тест корректно **скипается**, а не падает с непонятной ошибкой.
- Исправлен SQL загрузки данных под актуальную схему БД (миграции V14+):
  колонки `object_type`, `properties`, `geom_utm`; таблица `restriction_rules`.

### 3. FastVisibilityGraphService.java
- Устранены ошибки компиляции: удалены дубли методов, исправлены обращения
  к несуществующим объектам схемы (`restriction_buffer`, `geom_utm` там, где их нет).
- Переписан метод вставки рёбер графа под актуальную схему `visibility_edge`.
- Внутренние классы (`Vertex`, `Edge`, `VertexType`) сделаны `public` для доступа из тестов.

### 4. Репозиторий
- `target/` и `__pycache__/` выведены из индекса git, добавлены в `.gitignore`.

---

## 🔍 Что дальше: пошаговый план

### Шаг 1. Прогнать тесты локально (на машине с Docker Desktop)
```powershell
# Убедиться, что Docker Desktop запущен:
docker info

# Первый скачает образ postgis/postgis (~500 МБ), 2–5 минут:
mvn clean test -Dtest=FastVisibilityGraphServiceTest
```

**Ожидаемый результат:**
- ✅ Tests run: N, Failures: 0, Errors: 0 — гибрид/fast-граф работает
- ⚠️ "tests skipped" — Docker недоступен (проверьте `docker info`)
- ❌ Падение внутри теста (не `<clinit>`) — читать stack trace в `target/surefire-reports/`

### Шаг 2. Диагностика на конкурсном наборе (P0-4)
```bash
python diagnose_hybrid_graph.py
```
Метрики: coarse-рёбра ~5000–20000, JTS отбрасывает 30–70%, OKS с выходом наружу > 0.

### Шаг 3. Решение по развилке
- **OKS изолированы (0 рёбер наружу)** → переходить на полный JTS D1 с waypoints-сеткой (P2-1)
- **OKS имеют выход** → двигаться по P1

---

## 📅 Обновлённый список задач

| Приоритет | Задача | Статус | Оценка |
|-----------|--------|--------|--------|
| P0-1 | `extractForbiddenPolygons()` — загрузка из БД | ✅ Готово | — |
| P0-2 | `validateEdgesWithJTS()` — PreparedGeometry + STRtree | ✅ Готово | — |
| P0-3 | `validateCrossingAngle()` — угол ≥45° | ✅ Готово | — |
| P0-5a | Фикс стека Testcontainers/Docker (ExceptionInInitializerError) | ✅ Готово | — |
| P0-5b | Исправление ошибок компиляции FastVisibilityGraphService | ✅ Готово | — |
| **P0-4** | **Прогон тестов + диагностика на конкурсном наборе** | ⏳ **Ждёт: нужен Docker Desktop у пользователя** | 2 часа |
| P0-5c | Исправление багов, найденных прогоном | ⏳ Ожидает | 4 часа |
| P1-1 | Интеграция `find_best_path_from_oks()` (D2) | ⏳ Ожидает | 1 день |
| P1-2 | Спец. проходы (Kспец, разбиение рёбер) | ⏳ Ожидает | 1 день |
| P1-3 | Валидация смещений 5/7/9м для oks | ⏳ Ожидает | 0.5 дня |
| P1-4 | Тесты: 17/17 OKS подключены | ⏳ Ожидает | 0.5 дня |
| P2-1 | Полный JTS D1 с waypoints-сеткой (если гибрид не сработал) | ⏳ Ожидает | 3 дня |
| P2-2 | Кэширование forbidden buffers | ⏳ Ожидает | 0.5 дня |
| P2-3 | Параллелизация валидации рёбер | ⏳ Ожидает | 0.5 дня |
| P2-4 | Benchmark: ≤3 сек на кластер | ⏳ Ожидает | 0.5 дня |

---

## 🎯 Метрики успеха (без изменений из V3)

| Метрика | Было (V24) | Цель (P0) | Цель (P2) |
|---------|------------|-----------|-----------|
| OKS подключено | 4/17 (24%) | ≥10/17 (59%) | 17/17 (100%) |
| Время на кластер | 5–15 сек | ≤10 сек | ≤3 сек |
| Рёбер в графе | ~100 000 | ~5 000 | ~2 000 |
| Память Java | >1 GB | <500 MB | <200 MB |

---

## 🛠 Типичные проблемы запуска тестов (FAQ)

1. **`ExceptionInInitializerError` в `<clinit>`** — Docker не запущен или старая
   версия Testcontainers. Проверить: `docker info`; использовать стек из этого плана.
2. **Тест скипывается с сообщением "Docker is not available"** — Docker Desktop
   не запущен. Запустить Docker Desktop и повторить.
3. **Долгий первый запуск** — скачивается образ `postgis/postgis`; последующие
   запуски используют кэш.
4. **Порт 5432 занят локальной БД** — Testcontainers использует случайный порт,
   конфликтов быть не должно; при проблемах остановить локальную БД.
