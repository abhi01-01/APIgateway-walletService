## Stage 1: Build the application
#FROM maven:3.9.6-eclipse-temurin-21 AS build
#WORKDIR /app
#
## Copy only the pom.xml first to cache the Maven dependencies
#COPY pom.xml .
#RUN mvn dependency:go-offline -B
#
## Copy the source code and build the JAR, skipping tests to speed up local booting
#COPY src ./src
#RUN mvn clean package -DskipTests
#
## Stage 2: Create the highly optimized runtime image
#FROM eclipse-temurin:21-jre-jammy
#WORKDIR /app
#
## Copy the built JAR from the 'build' stage
#COPY --from=build /app/target/*.jar app.jar
#
## Expose the port the Gateway runs on
#EXPOSE 8080
#
## Run the application
##ENTRYPOINT ["java", "-jar", "app.jar"]
#
## Restrict heap to 256MB, leaving 212MB for native memory, thread stacks, and Metaspace, because Render free provides only 512MB RAM
#ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx256m", "-Xss512k", "-XX:MaxMetaspaceSize=128m", "-jar", "app.jar"]


#-----------------------------------------


## Stage 1: The Builder Environment (Heavy)
#FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
#
## Set the working directory
#WORKDIR /app
#
## Cache dependencies to optimize CI/CD pipeline speed
#COPY pom.xml .
#RUN mvn dependency:go-offline -B
#
## Copy source code and compile the immutable artifact
#COPY src ./src
#RUN mvn clean package -DskipTests
#
## Stage 2: The Distroless Runtime Environment (Ultra-Lightweight & Secure)
## Google's Distroless images contain NO shell (sh/bash), NO package managers, and NO OS utilities.
#FROM gcr.io/distroless/java21-debian12:nonroot
#
## Run as an unprivileged user (User ID 65532)
#USER nonroot:nonroot
#
#WORKDIR /app
#
## Extract ONLY the compiled JAR from the builder stage
#COPY --from=builder /app/target/APIgateway-0.0.1-SNAPSHOT.jar ./api-gateway.jar
#
## Expose the standardized edge port
#EXPOSE 8080
#
## Execute the Gateway natively
#ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx256m", "-Xss512k", "-XX:MaxMetaspaceSize=128m", "-jar", "api-gateway.jar"]

# moving to minimal JRE image for health check at deployment (bin/sh)
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 appgroup \
    && useradd \
        --system \
        --uid 10001 \
        --gid appgroup \
        --no-create-home \
        appuser

COPY --from=builder /app/target/*.jar /app/api-gateway.jar

USER 10001:10001

EXPOSE 8080 8082

ENTRYPOINT ["java", "-jar", "/app/api-gateway.jar"]