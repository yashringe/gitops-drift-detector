FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/target/gitops-drift-detector-*.jar app.jar
USER app
# CLI by default:   docker run -v ~/.kube:/home/app/.kube:ro -v $PWD/k8s:/manifests:ro IMAGE --drift.manifests=/manifests
# Server mode:      add --spring.profiles.active=server and expose 8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
