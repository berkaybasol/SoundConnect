# 1. Build aşaması
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY src/main/java/com/berkayb/soundconnect /app

RUN chmod +x gradlew
RUN ./gradlew clean build -x check -x test

# 2. Run aşaması (daha küçük image)
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]