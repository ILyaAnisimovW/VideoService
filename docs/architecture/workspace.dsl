// Proposed MVP architecture, not a description of an existing implementation.
// Scope: video on demand; uploads, adaptive playback, visibility, basic moderation.
// Excluded: live streaming, billing, recommendation ML, dedicated search cluster.
// Revision: worker claims/heartbeats through API; API rewrites private HLS playlists.
// Email verification/recovery is planned, outside the current HTTP contract.
// C1-C3 share one model. C3 zooms into Backend API only.
// Browser accesses storage only with narrowly scoped, expiring upload/playback grants.
// A private HLS grant covers manifests AND segments; signing one manifest is insufficient.
// API owns business metadata and processing state. Worker never accesses the API database.
// Queues: processing.jobs and processing.results, with bounded retries and DLQs.
// Upload completion verifies the uploaded object, then commits job + outbox atomically.
// Outbox delivery is at-least-once. Consumers must deduplicate and be idempotent.
// A worker result includes eventId, videoId, jobId, processingVersion and asset keys.
// Result application checks current processingVersion and deletion state in a transaction.
// Worker writes attempt-specific output keys; accepted outputs are registered by API.
// A sweeper removes abandoned uploads and unreferenced attempt outputs after a grace period.

workspace "Video Service" "Proposed C4 levels 1-3 for a Spring Boot video-on-demand service" {
    model {
        viewer = person "Viewer" "Discovers and watches permitted videos."
        creator = person "Creator" "Uploads videos and manages their metadata and visibility."
        moderator = person "Moderator" "Blocks inappropriate videos; user reports are a future extension."
        email = softwareSystem "Email Provider" "Planned external delivery of verification/recovery messages; outside MVP contract." {
            tags "External"
        }

        video = softwareSystem "Video Service" "Uploads, processes and delivers on-demand videos with access control." {
            web = container "Web Application" "Upload UI, catalog, video player and moderation UI." "Browser application / HLS player"
            api = container "Backend API" "Owns accounts, video metadata, upload sessions, authorization and processing state." "Java / Spring Boot" {
                http = component "HTTP API and Identity" "Controllers, account management and centralized request authentication. Resource authorization also remains in domain operations." "Spring MVC / Spring Security"
                catalog = component "Video Catalog" "Owns video metadata, visibility, moderation and publication rules. Exposes its API to other modules." "Spring beans / JPA"
                uploads = component "Upload Sessions" "Creates upload sessions, issues upload grants and verifies completion before requesting processing." "Spring beans"
                playback = component "Playback Access" "Checks viewer permissions, owns playback sessions and rewrites playlists with signed asset URLs." "Spring beans"
                processing = component "Processing Coordinator" "Owns jobs, leases, attempt numbers and processing versions; writes outbox and applies current results only." "Spring beans / transactions"
                relay = component "Outbox Publisher" "Publishes committed outbox records with broker confirms; duplicates are expected after interrupted delivery." "Scheduled publisher / AMQP"
                results = component "Processing Result Listener" "Validates incoming result messages and calls the coordinator; acknowledges only after successful state commit." "AMQP listener"
                storage = component "Object Storage Adapter" "Manages multipart uploads, reads playlists and signs segment URLs without proxying media segments." "S3-compatible SDK"
            }
            worker = container "Video Worker" "Claims jobs via API, renews leases, probes/transcodes media, writes attempt outputs and publishes results." "Java / FFmpeg / ffprobe"
            db = container "Metadata Database" "Accounts, videos, assets, upload sessions, jobs, outbox, deduplication, playback sessions, deletion tasks and idempotency." "PostgreSQL" {
                tags "Database"
            }
            broker = container "Message Broker" "Separate job and result queues; bounded retries and dead-letter queues." "RabbitMQ" {
                tags "Queue"
            }
            objects = container "Media Storage" "Private source objects, HLS manifests and segments, thumbnails and temporary attempt outputs." "S3-compatible object storage" {
                tags "Database"
            }
        }

        viewer -> video "Discovers and watches videos"
        creator -> video "Uploads and manages videos"
        moderator -> video "Reviews and moderates videos"
        video -> email "Requests account emails"

        viewer -> web "Browses and watches" "HTTPS"
        creator -> web "Uploads and manages videos" "HTTPS"
        moderator -> web "Reviews and moderates" "HTTPS"
        web -> api "Calls account, catalog, upload and playback endpoints" "HTTPS / JSON"
        web -> objects "Uploads source objects and reads permitted HLS assets" "HTTPS / scoped access grants"
        worker -> api "Claims jobs and renews processing leases" "HTTPS / worker JWT"
        worker -> http "Calls internal claim and heartbeat endpoints" "HTTPS / worker JWT"
        worker -> objects "Reads source objects and writes attempt outputs" "HTTPS / S3 API"
        broker -> worker "Delivers processing jobs" "AMQP / processing.jobs"
        worker -> broker "Publishes processing results" "AMQP / processing.results"

        web -> http "Calls HTTP endpoints" "HTTPS / JSON"
        http -> catalog "Uses catalog and moderation operations" "Java calls"
        http -> uploads "Creates and completes upload sessions" "Java calls"
        http -> playback "Requests permitted playback and rewritten manifests" "Java calls"
        http -> processing "Claims jobs and renews worker leases" "Module API"
        http -> db "Persists account data" "SQL / JDBC"
        http -> email "Requests verification and recovery email" "HTTPS / provider API"
        uploads -> catalog "Registers owned video metadata" "Module API"
        uploads -> storage "Issues upload grants and verifies source object" "Java calls"
        uploads -> processing "Requests processing after verified upload" "Module API"
        uploads -> db "Persists upload sessions" "SQL / JDBC"
        playback -> catalog "Checks visibility, moderation and published assets" "Module API"
        playback -> storage "Reads playlists and signs validated asset paths" "Java calls"
        playback -> db "Persists short-lived playback sessions" "SQL / JDBC"
        catalog -> db "Reads and writes metadata and asset records" "SQL / JDBC"
        processing -> catalog "Applies accepted processing outcome" "Module API"
        processing -> db "Commits jobs, outbox and result deduplication records" "SQL / JDBC"
        relay -> db "Reads outbox and records confirmed delivery" "SQL / JDBC"
        relay -> broker "Publishes committed jobs" "AMQP / processing.jobs"
        broker -> results "Delivers processing results" "AMQP / processing.results"
        results -> processing "Applies idempotent result with version check" "Module API"
        storage -> objects "Checks objects and creates scoped access grants" "HTTPS / S3 API"
    }

    views {
        systemContext video "C1_Context" {
            title "C1 - Video Service - System Context"
            include viewer creator moderator video email
            autoLayout tb
        }
        container video "C2_Containers" {
            title "C2 - Video Service - Containers"
            include *
            autoLayout tb
        }
        component api "C3_BackendComponents" {
            title "C3 - Backend API - Components"
            include *
            autoLayout tb
        }
        styles {
            element "Element" {
                color #ffffff
                fontSize 22
            }
            element "Person" {
                shape Person
                background #08427b
            }
            element "Software System" {
                background #1168bd
            }
            element "External" {
                background #777777
            }
            element "Container" {
                background #438dd5
            }
            element "Component" {
                background #85bbf0
                color #102030
            }
            element "Database" {
                shape Cylinder
            }
            element "Queue" {
                background #7450a0
            }
        }
    }
}
