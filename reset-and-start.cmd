@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo   Heat Tracer: Full Reset, Rebuild & Start
echo ============================================================
echo.

REM 1. Остановка текущих контейнеров
echo [1/5] Stopping containers...
docker compose down
if %errorlevel% neq 0 (
    echo ERROR: Failed to stop containers.
    exit /b 1
)

REM 2. Удаление старых образов (чтобы гарантированно собрать новые)
echo [2/5] Removing old Docker images...
docker compose rm -f app
docker rmi heat-tracer-app 2>nul || echo (Image not found, skipping removal)

REM 3. Очистка таблицы миграций Flyway в базе данных
REM    Это критично, так как мы изменили структуру старых миграций (V14, V27)
echo [3/5] Cleaning Flyway migration history and dropping all tables...
docker compose up -d db
echo Waiting for DB to start (8 seconds)...
timeout /t 8 /nobreak >nul

REM    Используем правильные учётные данные из docker-compose.yml: heat_user / heat_db
REM    Удаляем таблицу flyway_schema_history и все основные таблицы
docker compose exec -T db psql -U heat_user -d heat_db -c "DROP TABLE IF EXISTS flyway_schema_history CASCADE;"
docker compose exec -T db psql -U heat_user -d heat_db -c "DROP TABLE IF EXISTS visibility_edge CASCADE;"
docker compose exec -T db psql -U heat_user -d heat_db -c "DROP TABLE IF EXISTS visibility_vertex CASCADE;"
docker compose exec -T db psql -U heat_user -d heat_db -c "DROP TABLE IF EXISTS input_feature CASCADE;"
docker compose exec -T db psql -U heat_user -d heat_db -c "DROP TABLE IF EXISTS restriction_rules CASCADE;"
docker compose exec -T db psql -U heat_user -d heat_db -c "DROP TABLE IF EXISTS task CASCADE;"
docker compose exec -T db psql -U heat_user -d heat_db -c "DROP TABLE IF EXISTS path_result CASCADE;"
docker compose exec -T db psql -U heat_user -d heat_db -c "DROP TABLE IF EXISTS cluster_analysis CASCADE;"

REM 4. Сборка нового образа приложения
echo [4/5] Building Docker image (app)...
docker compose build --no-cache app
if %errorlevel% neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

REM 5. Запуск всех сервисов
echo [5/5] Starting all services...
docker compose up -d

echo.
echo ============================================================
echo   Done! Services are starting.
echo   To view logs: docker compose logs -f app
echo   To check DB status: docker compose exec db psql -U heat_user -d heat_db -c "SELECT * FROM flyway_schema_history ORDER BY installed_on DESC LIMIT 5;"
echo ============================================================

endlocal
