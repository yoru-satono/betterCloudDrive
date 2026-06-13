FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /workspace

COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl betterclouddrive-start -am package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /workspace/betterclouddrive-start/target/betterclouddrive-start.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
