FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/campus-1.0.0.jar campus.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "campus.jar"]
