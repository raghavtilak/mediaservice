# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy only Gradle files first (maximizes cache)
COPY gradlew .
RUN chmod +x gradlew

COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Cache Gradle downloads
RUN --mount=type=cache,target=/root/.gradle,id=gradle-cache-mediaservice \
    ./gradlew dependencies

# Now copy source
COPY src src

# Build the jar (reuse cache)
RUN --mount=type=cache,target=/root/.gradle,id=gradle-cache-mediaservice \
    ./gradlew bootJar
# Stage 2: Runtime
FROM eclipse-temurin:25-jre

# Install ffmpeg here 👇
RUN apt-get update && \
    apt-get install -y ffmpeg && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* \

WORKDIR /app
COPY --from=build /app/build/libs/*.jar media-service.jar
ENTRYPOINT ["java","-Duser.timezone=Asia/Kolkata", "-jar", "media-service.jar"]