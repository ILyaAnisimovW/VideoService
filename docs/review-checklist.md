# Проверки реализации

Документы пока проверяются структурно. Ниже критерии для будущих интеграционных и системных тестов.

| Сценарий | Проверка |
|---|---|
| Create с тем же ключом | Одна сущность, тот же ответ |
| Ключ повторён с другим body | 409, существующая сущность не меняется |
| PATCH со старым ETag | 412; чужие изменения не потеряны |
| Обрыв multipart | Загружаются только недостающие части |
| Успешный S3 complete и падение API | Повтор восстанавливает одну SQL-запись job |
| Двойной claim | Один lease; второй JOB_BUSY |
| Worker умер | После expiry восстановление, новый attemptNo |
| Двойной result | Один terminal outcome, без дублирования assets |
| Старый result | STALE, публикации нет |
| Delete во время обработки | READY больше не становится доступным |
| PRIVATE чужого пользователя | 404 на metadata и создание playback |
| Manifest path traversal | Доступ за пределы accepted assets запрещён |
| BLOCKED / смена PRIVATE | Новые manifests запрещены, старый segment URL ограничен TTL |
| Частичный S3 delete | Task не DONE, выполняется retry |
| Broker недоступен | Outbox остаётся pending, работа восстанавливается |
| Poison message | Bounded retry/DLQ, без бесконечной requeue |
| Проверка выдачи | Пароли, provider upload id и raw media keys не выходят в public DTO |

## Нагрузочные эксперименты

Отдельно измерить каталог, выдачу playlists, конкурентную загрузку и обработку. Указать CPU/RAM, объём БД, размеры и разрешения источников, профиль нагрузки, длительность, commit SHA. Не переносить p95 каталога на длительность FFmpeg.

## CI документации

`python scripts/validate_docs.py`: OpenAPI schema, operationId uniqueness, локальные $ref, request/response/schema examples, JSON schemas событий и Markdown links. Mermaid строится отдельно с Mermaid CLI. Validation-report фиксирует фактически выполненные проверки.
