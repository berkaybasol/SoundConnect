FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY . /app

RUN chmod +x gradlew
RUN ./gradlew clean build -x check -x test

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]