FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --home-dir /app lueerp && mkdir -p /app/data && chown -R lueerp:lueerp /app
COPY --from=build --chown=lueerp:lueerp /build/target/lue-erp-backend-0.0.1-SNAPSHOT.jar /app/app.jar
USER lueerp
EXPOSE 8080
ENTRYPOINT ["java", "-Xms256m", "-Xmx768m", "-jar", "/app/app.jar"]
