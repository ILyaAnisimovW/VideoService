# Состояние реализации backend MVP

Код всех основных HTTP-сценариев и отдельного worker находится в репозитории. Компиляция и модульные тесты проходят, но сквозной прогон с инфраструктурой и измерение производительности пока не выполнены. Это различие важно: «написано» не означает «проверено в эксплуатации».

## Компоненты

- `module/auth` — регистрация, вход, пользовательский JWT и серверная проверка ACTIVE пользователя. Для `/internal/**` используется отдельный машинный JWT с другим секретом и audience.
- `app/catalog` — карточка видео, публичный каталог, личный список, изменение метаданных по `If-Match`, visibility и модерация.
- `app/upload` — приватный S3 multipart с прямой загрузкой частей браузером, idempotency, сверкой частей/ETag и восстановлением `INITIATING`/`COMPLETING`.
- `app/processing` — job, lease на 60 секунд, heartbeat, автоматический retry до трёх попыток, ручной retry с новой processingVersion, transactional outbox, RabbitMQ result consumer, проверка inventory/HLS и дедупликация `processed_events`. READY ставится только после проверки и одной SQL-транзакции с assets.
- `worker` — отдельное Spring Boot приложение без доступа к PostgreSQL. Оно получает server-owned source/output prefix и временные STS credentials, запускает `ffprobe` и `ffmpeg`, создаёт HLS VOD и thumbnail, публикует success/failure event. Временная STS policy разрешает чтение только исходника и запись только prefix текущей попытки. Подход с inline session policy соответствует [документации MinIO AssumeRole](https://github.com/minio/minio/blob/master/docs/sts/assume-role.md).
- `app/playback` — 15-минутная сессия с hash токена в БД, проверкой актуальных прав на каждый manifest, переписыванием master/variant playlist и подписанием отдельных сегментов на срок до 5 минут. Неподдерживаемые URI-теги и ссылки за пределы принятой rendition отвергаются.
- `app/deletion` и cleanup scheduler — немедленный tombstone `DELETING`, отмена job и отзыв playback-сессий; удаление файлов после 30-минутной grace period, проверка пустого prefix и retry с backoff.

Миграции: `app/src/main/resources/db/identity/V1__create_users.sql`, `app/src/main/resources/db/video/V2__upload_pipeline.sql`, `app/src/main/resources/db/video/V3__complete_mvp.sql`. Инфраструктура локального стенда — `docker-compose.yml`. Отдельный Web UI в Java-репозитории не создавался; сервис предоставляет документированный HTTP API для клиента/HLS player.

## Локальный запуск

Задайте в окружении или локальном `.env` (не коммитить) отдельные значения `POSTGRES_PASSWORD`, `JWT_SECRET`, `WORKER_JWT_SECRET`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`. Оба JWT секрета — не менее 32 UTF-8 байт. `MINIO_ROOT_*` используются только MinIO/init-контейнером, `S3_*` — отдельным API-пользователем MinIO; не задавайте одинаковые пары. При необходимости настройте `VIDEO_PLAYBACK_PUBLIC_BASE_URL` на внешний адрес API.

`docker compose up --build` запускает PostgreSQL, RabbitMQ, приватный bucket MinIO, API и отдельный worker с FFmpeg. Для браузерной загрузки в `infra/minio/cors.xml` разрешены `http://localhost:3000` и `http://localhost:5173`; другой origin надо настроить явно. Подписанные URL частей используют `http://localhost:9000`, а серверные S3-вызовы — внутренний адрес MinIO. Bucket остаётся private.

После регистрации передайте пользовательский JWT в `Authorization: Bearer ...`. Загрузка: `POST /api/v1/videos` → `POST /api/v1/videos/{id}/upload-sessions` → для каждой части `POST /api/v1/upload-sessions/{id}/parts/{n}/url` и прямой `PUT` по URL → `POST /api/v1/upload-sessions/{id}/complete` с номерами и ETag. Клиент отправляет точный размер части и все `requiredHeaders`. Затем наблюдайте `GET /api/v1/processing-jobs/{id}` до `SUCCEEDED` или `FAILED`. Для готового видео создайте `POST /api/v1/videos/{id}/playback-sessions` и откройте возвращённый `manifestUrl` HLS-плеером.

Проверки кода: `./gradlew :app:test :worker:test :module:auth:test`. Контракты документации: `python scripts/validate_docs.py` после установки `scripts/requirements-docs.txt`.

После запуска стенда можно выполнить сквозной smoke-тест с небольшим MP4 (до 16 MiB): поместите access JWT тестового пользователя в переменную `VIDEO_TEST_JWT` и запустите `scripts/smoke_mvp.ps1 -VideoPath <путь-к-mp4>`. Он проверяет multipart, job, готовый HLS master/variant и доступность сегмента. Не используйте реальные секреты в командной строке или логах.

## Пока не подтверждено

- Сквозной запуск и миграции на реальном PostgreSQL/MinIO/RabbitMQ/FFmpeg стенде, включая MinIO STS AssumeRole для отдельного API-пользователя.
- Нагрузочные показатели, предел 20 минут на обработку 2 GiB и отказоустойчивость нескольких API/worker реплик.
- Удаление версий объектов при включённом S3 versioning; локальный MinIO подразумевает bucket без versioning.
- Операционный мониторинг DLQ и восстановление `FAILED` deletion task администратором. Эти состояния сохраняются, но отдельной административной панели нет.
