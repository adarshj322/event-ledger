# Multi-stage build for Event Gateway
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw package -DskipTests -q -pl event-gateway -am

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/event-gateway/target/*.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java", "-jar", "app.jar"]
