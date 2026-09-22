@echo off
echo ==========================================
echo Heat Tracer: Full Reset, Rebuild and Start
echo ==========================================

echo [1/6] Stopping containers and removing volumes...
docker compose down -v

echo [2/6] Removing old Docker images...
docker rmi heat-tracer-app 2>nul || echo "Image not found, skipping"

echo [3/6] Removing dangling volumes (cleanup)...
docker volume prune -f

echo [4/6] Building app image (no cache)...
docker compose build --no-cache app

echo [5/6] Starting all services...
docker compose up -d

echo [6/6] Waiting for DB to be ready (10 sec)...
timeout /t 10 /nobreak >nul

echo ==========================================
echo Done! Check logs: docker compose logs -f app
echo ==========================================
EOF

# 2. Коммитим исправление
git add reset-and-start.cmd
git commit -m "fix: полное удаление томов БД в скрипте сброса"

# 3. Пушим в ветку qc
git push origin qc