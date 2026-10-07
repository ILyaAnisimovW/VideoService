FROM gradle:8.14-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle :worker:bootJar --no-daemon

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /worker
COPY --from=build /workspace/worker/build/libs/*.jar worker.jar
ENTRYPOINT ["java", "-jar", "worker.jar"]
