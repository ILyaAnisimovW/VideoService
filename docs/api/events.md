# Контракты сообщений

[AsyncAPI](asyncapi.yaml) описывает перспективу Backend API: send processing.jobs, receive processing.results. [JSON Schema](events.schema.json) — контракт тела сообщений и примеры для validators/worker. Все три типа имеют schemaVersion=1.

## Топология

| Тип | Routing key | Получатель | Результат |
|---|---|---|---|
| VideoProcessingRequested | processing.requested | Worker, processing.jobs | Claim по jobId/version |
| VideoProcessingSucceeded | processing.succeeded | API, processing.results | Проверка inventory и публикация READY |
| VideoProcessingFailed | processing.failed | API, processing.results | Retry того же job либо FAILED |

Durable direct exchange `video.processing`; durable queues `processing.jobs` и `processing.results`; persistent messages; publisher confirms + mandatory routing. Result queue binds succeeded и failed. Prefetch worker=1 на канал; число процессов определяется ресурсами CPU/RAM.

Retry queues и DLQ задаются при реализации: capped exponential backoff, например 5/30/120 секунд. Transport delivery retries не равны actual processing attempts. actual attempts считает SQL claim; maxAttempts=3. Для результатов ограничить transport retries, после лимита DLQ; scheduler восстановит зависший job по lease.

## Envelope

Общие поля: eventId UUID, eventType, schemaVersion=1, occurredAt UTC, traceId, videoId, jobId, processingVersion. Requested не содержит произвольный source URL: API выдаёт sourceKey/outputPrefix при claim. Технические credentials не передаются в очереди; worker получает scoped temporary credentials в защищённом HTTP-ответе claim (storageAccess), а не в сообщении.

Succeeded/Failed дополнительно содержат attemptNo и leaseId. Succeeded содержит durationMs и assets; assets относятся строго к выданному attempt prefix. Required inventory: ровно один MASTER_PLAYLIST, хотя бы один MEDIA_PLAYLIST, один THUMBNAIL; rendition quality уникальна; все URI плейлистов входят в проверенный prefix. JSON Schema описывает форму, а межполевая семантика проверяется приложением.

Failed содержит безопасный errorCode и retryable. Не отправлять stacktrace/локальные paths/секреты в публичную ошибку. Полный diagnostic хранится в logs с traceId.

## Согласованность и порядок

- Доставка at-least-once. Exactly-once не заявляется.
- Publish outbox: confirm без basic.return → можно отметить publishedAt. Если broker принял, а SQL update упал, повтор безопасен.
- Result consumer: schema → external asset verification → short SQL transaction → ack.
- `processed_events` закрывает повтор eventId. Проверка terminal job + attempt/version закрывает повтор outcome с новым eventId.
- Порядок сообщений не гарантирует бизнес-порядок; версия и lease важнее порядка доставки.
- При новом schemaVersion требуется явное обновление consumers. Не менять meaning existing fields. Новые поля с additionalProperties=false требуют новой договорённости/версии; это намеренно строгий MVP-контракт.
- Очистка технических записей учитывает срок жизни сообщений, DLQ и replay: произвольная retention без анализа нарушает dedup.

Внутренние claim и heartbeat endpoints находятся в [OpenAPI](openapi.yaml). Это транспорт управления lease; результата через HTTP нет, он приходит через RabbitMQ.
