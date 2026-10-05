# Проверка документации

Дата: 2026-10-05. Проверяется проект контрактов и документов; backend в пакет не входит.

| Проверка | Результат |
|---|---|
| OpenAPI 3.1.1, openapi-spec-validator | Passed |
| Уникальность operationId и параметры path | 22 операции, Passed |
| Примеры схем, параметров и HTTP response | 15 примеров, Passed |
| JSON Schema Draft 2020-12 событий | 3 event-примера, Passed |
| AsyncAPI, официальный @asyncapi/parser | Valid, 0 errors |
| Соответствие AsyncAPI payload и JSON Schema | Passed |
| Markdown links и anchors | 40 локальных ссылок, Passed |
| Mermaid CLI, построение PNG | 11 схем, Passed |
| Визуальная выборочная проверка | Upload sequence, основная ERD и C3 просмотрены |
| Structurizr DSL | Согласованность связей проверена по тексту; full parser/render не выполнялся |

AsyncAPI parser сообщил информационную рекомендацию перейти с поддерживаемой 3.0.0 на 3.1.0. Это не ошибка контракта.

## Что не подтверждено

Нет проверки работающего backend, интеграций S3/RabbitMQ, нагрузочных результатов, миграций SQL и соответствия Java-кода контракту. Лимиты в requirements.md — предложенные настройки. Следующие проверки находятся в review-checklist.md.
