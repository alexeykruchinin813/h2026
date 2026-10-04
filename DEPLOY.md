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
```

---

## 🔧 Шаг 2: Установка и настройка Yandex Cloud CLI

### 2.1. Установка (Windows, PowerShell)

```powershell
iex (New-Object System.Net.WebClient).DownloadString('https://storage.yandexcloud.net/yandexcloud-yc/install.ps1')
```

На вопрос `Add yc installation dir to your PATH? [Y/n]` ответьте `Y`.
**Перезапустите PowerShell** после установки.

Проверьте:
```powershell
yc --version
```

### 2.2. Авторизация

```powershell
yc init
```

Мастер задаст несколько вопросов:
1. Откроется ссылка для авторизации — войдите в Yandex Cloud, скопируйте код и вставьте в терминал.
2. Выберите облако.
3. Выберите каталог.
4. На вопрос про зону доступности — `Y`, затем выберите `ru-central1-a`.

Проверьте, что профиль создан:
```powershell
yc config list
```

---

## 📦 Шаг 3: Создание Container Registry и загрузка образов

### 3.1. Создайте реестр

```powershell
yc container registry create --name heat-tracer-registry
```

**Скопируйте `id` из вывода команды** — он понадобится дальше.
Обозначим его как `<ID_РЕЕСТРА>`.

### 3.2. Настройте Docker для работы с реестром

```powershell
yc container registry configure-docker
```

Проверьте связку:
```powershell
echo cr.yandex | docker-credential-yc get
```
Должно вернуть JSON с `Username` и `Secret`.

### 3.3. Соберите и загрузите образ приложения

> ⚠️ **Важно:** если у вас Docker 26+, используйте старый билдер, чтобы избежать
> ошибки `Cannot read manifest data` при push.

```powershell
# Отключаем BuildKit для текущей сессии
$env:DOCKER_BUILDKIT=0

# Сборка образа
docker build -t heat-tracer-app:latest .

# Тегирование для реестра
docker tag heat-tracer-app:latest cr.yandex/<ID_РЕЕСТРА>/heat-tracer-app:latest

# Загрузка
docker push cr.yandex/<ID_РЕЕСТРА>/heat-tracer-app:latest
```

### 3.4. Загрузите образ БД

```powershell
docker pull nickblah/pgrouting:16-postgis-3.6-pgrouting-4.0.1
docker tag nickblah/pgrouting:16-postgis-3.6-pgrouting-4.0.1 `
  cr.yandex/<ID_РЕЕСТРА>/pgrouting:16-postgis-3.6-pgrouting-4.0.1
docker push cr.yandex/<ID_РЕЕСТРА>/pgrouting:16-postgis-3.6-pgrouting-4.0.1
```

---

## 🔑 Шаг 4: Создание сервисного аккаунта

ВМ будет скачивать образы из реестра от имени сервисного аккаунта.

```powershell
# Создаем сервисный аккаунт
yc iam service-account create --name coi-puller-sa

# Даем ему право скачивать образы
yc container registry add-access-binding <ID_РЕЕСТРА> `
  --role container-registry.images.puller `
  --service-account-name coi-puller-sa
```

---

## 🔐 Шаг 5: SSH-ключ для доступа к ВМ

Если у вас ещё нет SSH-ключа:

```powershell
mkdir $env:USERPROFILE\.ssh -ErrorAction SilentlyContinue
ssh-keygen -t ed25519 -f $env:USERPROFILE\.ssh\id_ed25519
```

На оба вопроса о passphrase просто нажмите **Enter**.

Проверьте, что файлы появились:
```powershell
dir $env:USERPROFILE\.ssh
```
Должны быть `id_ed25519` и `id_ed25519.pub`.

---

## 📝 Шаг 6: Подготовка docker-compose для COI

В репозитории уже есть файл **`docker-compose.coi.yaml`**. Откройте его
и замените `<ID_РЕЕСТРА>` на ваш реальный ID:

```yaml
services:
  db:
    image: "cr.yandex/<ID_РЕЕСТРА>/pgrouting:16-postgis-3.6-pgrouting-4.0.1"
    ...
  app:
    image: "cr.yandex/<ID_РЕЕСТРА>/heat-tracer-app:latest"
    ...

x-yc-disks:
  - device_name: pgdata-disk
    fs_type: ext4
    host_path: /home/yc-user/pgdata
```

> 💡 В `docker-compose.coi.yaml` данные PostgreSQL монтируются в
> `/home/yc-user/pgdata` — это путь на отдельном диске, который мы
> подключим к ВМ. Так база переживёт пересоздание виртуальной машины.

---

## 🖥️ Шаг 7: Создание виртуальной машины

### 7.1. Узнайте имя подсети

```powershell
yc vpc subnet list
```

Скопируйте **NAME** подсети в зоне `ru-central1-a`. По умолчанию это
`default-ru-central1-a`.

### 7.2. Создайте ВМ

Выполните команду, заменив `<имя_подсети>` на имя из шага 7.1:

```powershell
yc compute instance create-with-container `
  --name heat-tracer-vm `
  --zone ru-central1-a `
  --ssh-key $env:USERPROFILE\.ssh\id_ed25519.pub `
  --create-boot-disk size=30 `
  --create-disk name=pgdata-disk,size=20,device-name=pgdata-disk `
  --network-interface subnet-name=<имя_подсети>,nat-ip-version=ipv4 `
  --service-account-name coi-puller-sa `
  --docker-compose-file docker-compose.coi.yaml
```

**Параметры:**
- `--create-boot-disk size=30` — загрузочный диск (минимум 30 ГБ для COI).
- `--create-disk name=pgdata-disk,size=20,device-name=pgdata-disk` —
  дополнительный диск для данных PostgreSQL.
- `--service-account-name coi-puller-sa` — сервисный аккаунт из шага 4.
- `--docker-compose-file docker-compose.coi.yaml` — спецификация контейнеров.

Создание занимает 1–3 минуты. По завершении **скопируйте публичный IP**
из поля `primary_v4_address.one_to_one_nat.address`.

---

## ✅ Шаг 8: Проверка работы

### 8.1. Подключение к ВМ

```powershell
ssh -i $env:USERPROFILE\.ssh\id_ed25519 yc-user@<ПУБЛИЧНЫЙ_IP>
```

### 8.2. Проверка контейнеров

```bash
sudo docker ps
```

Должны быть запущены:
- `heat-tracer-db` (healthy)
- `heat-tracer-app`

### 8.3. Проверка подключения к БД

```bash
sudo docker logs heat-tracer-app | grep "DB CONNECTION"
```

Должна быть строка:
```
=== DB CONNECTION OK ===
PostgreSQL : PostgreSQL 16.15 ...
PostGIS    : 3.6 ...
pgRouting  : 4.0.1
```

### 8.4. Проверка API снаружи

Откройте **второе окно PowerShell** (SSH-сессию не закрывайте):

```powershell
curl.exe -X POST http://<ПУБЛИЧНЫЙ_IP>:8080/api/upload `
  -F "file=@src/test/resources/first_dataset.geojson"
```

Ожидаемый ответ:
```json
{"taskId":"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx","status":"RUNNING"}
```

Проверьте статус (замените `<taskId>`):
```powershell
curl.exe http://<ПУБЛИЧНЫЙ_IP>:8080/api/task/<taskId>
```

Когда `status` станет `DONE`, скачайте результат:
```powershell
curl.exe -o result.geojson http://<ПУБЛИЧНЫЙ_IP>:8080/api/task/<taskId>/result
```

---

## 🌐 Доступ к Swagger UI

После запуска приложения Swagger доступен по адресу:

```
http://<ПУБЛИЧНЫЙ_IP>:8080/swagger-ui.html
```

или

```
http://<ПУБЛИЧНЫЙ_IP>:8080/swagger-ui/index.html
```

---

## 🔄 Обновление приложения

Чтобы развернуть новую версию приложения:

1. Внесите изменения в код.
2. Пересоберите образ:
   ```powershell
   $env:DOCKER_BUILDKIT=0
   docker build -t heat-tracer-app:latest .
   docker tag heat-tracer-app:latest cr.yandex/<ID_РЕЕСТРА>/heat-tracer-app:latest
   docker push cr.yandex/<ID_РЕЕСТРА>/heat-tracer-app:latest
   ```
3. Обновите контейнер на ВМ:
   ```powershell
   yc compute instance update-container heat-tracer-vm `
     --docker-compose-file docker-compose.coi.yaml
   ```

> 💡 Данные PostgreSQL на диске `pgdata-disk` не затрагиваются при обновлении
> приложения — миграции Flyway применятся автоматически.

---

## 🛑 Остановка и удаление

### Остановить ВМ (данные на диске сохранятся)

```powershell
yc compute instance stop heat-tracer-vm
```

> ⚠️ **Важно:** при остановке ВМ **динамический публичный IP освобождается**,
> и при следующем запуске ВМ получит новый адрес. Если нужен постоянный —
> сделайте IP статическим в консоли: **Virtual Private Cloud → Публичные IP-адреса**.

### Запустить ВМ обратно

```powershell
yc compute instance start heat-tracer-vm
```

### Полностью удалить (вместе с дисками)

```powershell
yc compute instance delete heat-tracer-vm
yc compute disk delete --name pgdata-disk
```

---

## 🧯 Возможные проблемы

### `Cannot read manifest data` при `docker push`

Docker 26+ использует новый формат манифестов, который Yandex Container
Registry не читает. Решение — пересобрать без BuildKit:

```powershell
$env:DOCKER_BUILDKIT=0
docker build -t heat-tracer-app:latest .
```

### `docker-credential-yc: cannot run executable`

Не установлен или не найден Yandex Cloud CLI. Установите (шаг 2) и
**перезапустите PowerShell**, затем Docker Desktop.

### `profile 'default' not found`

Не выполнен `yc init`. Выполните (шаг 2.2).

### Ссылка на Swagger не открывается

Скорее всего, порт `8080` закрыт в Security Group. Откройте консоль
Yandex Cloud → VPC → Security Groups → найдите группу подсети →
добавьте входящее правило TCP `8080` с источника `0.0.0.0/0`.

### `=== DB CONNECTION FAILED ===` в логах

Проверьте логи контейнера БД:
```bash
sudo docker logs heat-tracer-db
```
Если видите ошибки монтирования — проверьте, что диск `pgdata-disk`
примонтирован в `/home/yc-user/pgdata` (см. `x-yc-disks` в compose-файле).

---

## 📊 Архитектура развертывания

```
┌────────────────────────────────────────────────────────────┐
│  ВМ heat-tracer-vm (COI, 2 vCPU, 2 GB RAM)                 │
│                                                            │
│  ┌─────────────────────┐    ┌──────────────────────────┐   │
│  │ heat-tracer-db      │    │ heat-tracer-app          │   │
│  │ PostgreSQL 16       │◄───┤ Java 11 + Spring Boot    │   │
│  │ PostGIS 3.6         │    │ Tomcat :8080             │   │
│  │ pgRouting 4.0.1     │    │ Flyway миграции          │   │
│  │ :5432               │    │                          │   │
│  └──────────┬──────────┘    └──────────────────────────┘   │
│             │                                              │
│             ▼                                              │
│  ┌──────────────────────────────────────────┐              │
│  │ Диск pgdata-disk (20 GB)                 │              │
│  │ /home/yc-user/pgdata                     │              │
│  └──────────────────────────────────────────┘              │
└────────────────────────────────────────────────────────────┘
                          │
                          ▼ Публичный IP :8080
                    Swagger UI / API
```

---

## 📚 Полезные ссылки

- [Yandex Cloud CLI — установка](https://yandex.cloud/ru/docs/cli/operations/install-cli)
- [Container Registry — начало работы](https://yandex.cloud/ru/docs/container-registry/quickstart/)
- [Container Optimized Image — документация](https://yandex.cloud/ru/docs/container-registry/concepts/coi)
- [Создание ВМ с Docker-контейнером](https://yandex.cloud/ru/docs/compute/operations/vm-create/create-with-container)
```

---

### 💡 Что стоит проверить/добавить перед коммитом

1. **Замените `<URL_ВАШЕГО_РЕПОЗИТОРИЯ>`** в Шаге 1 на реальный URL.
2. **Убедитесь, что `docker-compose.coi.yaml` действительно лежит в корне репозитория** и содержит placeholder `<ID_РЕЕСТРА>` (а не ваш конкретный ID — иначе каждый пользователь будет пушить в ваш реестр).
3. **Добавьте в `.gitignore`** (если ещё нет):
   ```
result.geojson
*.log
.env
   ```
4. **Убедитесь, что `docker-compose.yml`** (локальный, для разработки) и **`docker-compose.coi.yaml`** (для облака) — это два разных файла, и оба лежат в репозитории. В README упомяните, что первый — для локальной разработки, второй — для деплоя.
