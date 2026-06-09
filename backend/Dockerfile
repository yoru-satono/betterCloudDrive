FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the pre-built JAR (run 'mvn clean package -DskipTests' in backend/ first)
COPY betterclouddrive-start/target/betterclouddrive-start-*.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "-Djava.security.egd=file:/dev/./urandom", "app.jar"]
