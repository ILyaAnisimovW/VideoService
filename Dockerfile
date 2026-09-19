# ---- сборка ----
FROM gradle:8.9-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle :module:auth:bootJar --no-daemon

# ---- запуск ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/module/auth/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]