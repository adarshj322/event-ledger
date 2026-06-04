# Multi-stage build for Account Service
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw package -DskipTests -q -pl account-service -am

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/account-service/target/*.jar app.jar
EXPOSE 8081
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java", "-jar", "app.jar"]
