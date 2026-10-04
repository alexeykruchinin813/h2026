# DEPLOY.md — Развертывание Heat Tracer в Yandex Cloud

Пошаговая инструкция для развертывания приложения **Heat Tracer** в Yandex Cloud
с использованием Container Optimized Image (COI) и Docker Compose.

В результате вы получите работающую ВМ, на которой запущены два контейнера:
- **heat-tracer-db** — PostgreSQL 16 + PostGIS 3.6 + pgRouting 4.0.1
- **heat-tracer-app** — Java 11 + Spring Boot 2.6.3

---

## 📋 Требования

Перед началом убедитесь, что у вас установлено:

| Инструмент | Версия | Как проверить |
|---|---|---|
| Docker Desktop | 20.x+ | `docker --version` |
| Yandex Cloud CLI (`yc`) | 1.38+ | `yc --version` |
| Git | любая | `git --version` |
| SSH-клиент | встроен в Windows 10+ | `ssh -V` |

Также нужен **аккаунт в Yandex Cloud** с активным платежным аккаунтом
и созданным каталогом (folder).

---

## 🚀 Шаг 1: Клонирование репозитория

```bash
git clone <URL_ВАШЕГО_РЕПОЗИТОРИЯ>
cd <имя-папки-репозитория>