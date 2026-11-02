FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
ENV MAVEN_OPTS=-Xmx512m
COPY pom.xml .
COPY services services
RUN --mount=type=cache,id=sinhan-maven,target=/root/.m2,sharing=locked mvn -B -ntp package -DskipTests
ARG SERVICE
RUN cp services/${SERVICE}/target/${SERVICE}-0.1.0.jar /app.jar
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
ENTRYPOINT ["java","-Xms64m","-Xmx256m","-jar","app.jar"]
