FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh mvnw -q -B dependency:go-offline
COPY src src
RUN sh mvnw -q -B -DskipTests package && mv target/epptrack-*.jar app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home nonroot
WORKDIR /app
COPY --from=build --chown=nonroot /src/app.jar app.jar
USER nonroot
EXPOSE 8080
ENV THYMELEAF_CACHE=true
ENTRYPOINT ["java", "-jar", "app.jar"]
