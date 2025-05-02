FROM eclipse-temurin:21-jdk as builder

WORKDIR /app
COPY . .

RUN ./gradlew clean build -x test

# --------- Runtime kısmı -----------
FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY --from=builder /app/build/libs/SoundConnect-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]