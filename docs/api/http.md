# HTTP API: правила и операции

**Источник контракта:** [openapi.yaml](openapi.yaml), OpenAPI 3.1.1. Все endpoints имеют префикс `/api/v1`. Это проект API, не автоматически извлечённая документация существующих controllers.

## Основные операции

| Метод | Путь без префикса | Назначение | Успех |
|---|---|---|---|
| POST | /auth/register | Регистрация USER | 201 |
| POST | /auth/login | JWT, 1 час | 200 |
| GET | /users/me | Текущий пользователь | 200 |
| GET | /users/me/videos | Свои видео всех состояний | 200 |
| POST | /videos | Метаданные, WAITING_UPLOAD | 201 |
| GET | /videos | Публичный готовый каталог | 200 |
| GET | /videos/{videoId} | Метаданные с проверкой видимости | 200 |
| PATCH | /videos/{videoId} | Название/описание/visibility | 200 |
| DELETE | /videos/{videoId} | Асинхронное удаление | 202 / 204 |
| PUT | /videos/{videoId}/moderation | CLEAR/BLOCKED; MODERATOR/ADMIN | 200 |
| GET | /videos/{videoId}/deletion | Статус удаления для владельца | 200 |
| POST | /videos/{videoId}/upload-sessions | Создать multipart-сессию | 201 |
| GET | /upload-sessions/{sessionId} | Состояние и загруженные части | 200 |
| POST | /upload-sessions/{sessionId}/parts/{partNumber}/url | Подписать PUT части | 200 |
| POST | /upload-sessions/{sessionId}/complete | Проверить и поставить обработку | 202 |
| DELETE | /upload-sessions/{sessionId} | Abort незавершённой загрузки | 204 |
| GET | /processing-jobs/{jobId} | Состояние задания для владельца | 200 |
| POST | /videos/{videoId}/processing-retries | Новая версия FAILED-видео | 202 |
| POST | /videos/{videoId}/playback-sessions | Разрешение на просмотр | 201 |
| GET | /playback-sessions/{sessionId}/manifests/{assetId} | Переписанный HLS-плейлист | 200 |
| POST | /internal/processing-jobs/{jobId}/claims | Claim worker | 201 |
| PUT | /internal/processing-jobs/{jobId}/lease | Heartbeat worker | 200 |

## Аутентификация и права

JWT пользовательский: Bearer token, role USER/MODERATOR/ADMIN, sub=userId. Worker JWT имеет другую audience и machine identity; пользовательский JWT не открывает `/internal/*`. Role/moderation проверяется сервером. PUBLIC/UNLISTED browsing допускает отсутствие token; если клиент отправил неверный token, ответ 401, а не анонимный fallback.

Пользовательский ответ не содержит passwordHash, providerUploadId, raw S3 keys, leaseId или outbox payload. Internal API и event schemas содержат технические ключи и доступны только worker/брокеру.

## Пагинация

Cursor pagination: `limit` 1..100, default 20; порядок `(createdAt DESC, id DESC)`. `nextCursor` — opaque token с последним tuple, endpoint/filter binding и подписью. Токен не даёт права доступа: фильтры прав применяются при каждом запросе. Неверный/чужой cursor — 400 INVALID_CURSOR. Пагинация не обещает snapshot между запросами.

## Optimistic locking

GET/PATCH/PUT moderation возвращают ETag вида `"7"`, соответствующий rowVersion. PATCH и PUT moderation требуют `If-Match`. Отсутствие — 428 PRECONDITION_REQUIRED; несовпадение — 412 VERSION_CONFLICT. Любое изменение Video, включая обработку и удаление, увеличивает rowVersion. DELETE идемпотентен и не требует If-Match.

PATCH: title/visibility не могут быть null; description=null очищает описание; отсутствующее поле не меняется. title trim + 1..120; description до 5000. Пустой объект и неизвестные поля дают 422. Эти поля не позволяют изменить ownerId, роль, processingStatus или moderationStatus.

## Идемпотентность

Idempotency-Key обязателен на create Video, create UploadSession, complete и manual processing retry. Scope: userId + operation + targetId + key. Fingerprint: method + canonical route + canonical JSON body. При том же fingerprint повтор возвращает исходные status/body/Location/ETag. При другом — 409 IDEMPOTENCY_KEY_REUSED. Пока первый запрос выполняется — 409 REQUEST_IN_PROGRESS с Retry-After.

Результат хранится 24 часа. Validation errors до начала операции не резервируют ключ. Transient 503 не фиксируется как окончательный результат: операция остаётся восстановимой. Срок записи в progress продлевается, пока действует загрузочная операция. Для upload complete отдельное ограничение session→job запрещает дубли даже после истечения idempotency record.

## Ошибки

```json
{
  "code": "VIDEO_NOT_READY",
  "message": "Видео ещё обрабатывается",
  "traceId": "0a9f64e4",
  "timestamp": "2026-10-05T16:00:00Z",
  "path": "/api/v1/videos/11111111-1111-4111-8111-111111111111/playback-sessions",
  "details": []
}
```

| HTTP | Условия |
|---|---|
| 400 | INVALID_CURSOR, MALFORMED_REQUEST |
| 401 | UNAUTHORIZED, INVALID_PLAYBACK_TOKEN |
| 403 | FORBIDDEN для роли/internal audience |
| 404 | NOT_FOUND, включая чужой PRIVATE и скрытые ресурсы |
| 409 | REQUEST_IN_PROGRESS, IDEMPOTENCY_KEY_REUSED, INVALID_STATE, VIDEO_NOT_READY, VIDEO_BLOCKED, JOB_BUSY, STALE_JOB, JOB_TERMINAL |
| 410 | UPLOAD_EXPIRED, PLAYBACK_EXPIRED, VIDEO_DELETED для владельца |
| 412 / 428 | VERSION_CONFLICT / PRECONDITION_REQUIRED |
| 422 | VALIDATION_FAILED, SOURCE_MISMATCH, INVALID_PARTS |
| 429 | RATE_LIMITED + Retry-After |
| 503 | DEPENDENCY_UNAVAILABLE, восстановимый сбой S3/DB/broker |

OpenAPI перечисляет ответы каждой операции. Дополнительно global rate limit возвращает 429. Все JSON responses имеют X-Trace-Id; signed URL/token response — Cache-Control: no-store. HLS success имеет content type application/vnd.apple.mpegurl и no-store.

## Пример запроса

```http
POST /api/v1/videos HTTP/1.1
Authorization: Bearer <access-token>
Idempotency-Key: create-video-demo-001
Content-Type: application/json

{"title":"Как работает Spring Boot","description":"Первый ролик","visibility":"PRIVATE"}
```

После создания вызвать upload-sessions с sizeBytes/contentType; после загрузки частей вызвать complete со списком partNumber/ETag. Реальные credentials и подписанные URL в примеры репозитория не помещать.
