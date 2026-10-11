FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY backend/pom.xml backend/pom.xml
COPY booking-demo/pom.xml booking-demo/pom.xml
COPY backend/src backend/src
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -ntp -pl backend -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 trippilot && useradd --uid 10001 --gid 10001 --no-create-home trippilot
WORKDIR /app
COPY --from=build /build/backend/target/backend-0.1.0-SNAPSHOT.jar /app/backend.jar
COPY infra/deployment/start-hosted.sh /app/start-hosted.sh
RUN sed -i 's/\r$//' /app/start-hosted.sh && chmod 755 /app/start-hosted.sh
USER 10001:10001
EXPOSE 10000
ENTRYPOINT ["/app/start-hosted.sh"]
