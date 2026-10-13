# Build stage
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline
COPY src ./src
RUN ./mvnw package -DskipTests

# Runtime stage
FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    wget \
    python3 \
    python3-pip \
    ca-certificates \
    fonts-noto-color-emoji \
    fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
RUN wget -O /usr/local/bin/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/download/2026.07.04/yt-dlp_linux \
    && wget -O /tmp/yt-dlp.sha256 https://github.com/yt-dlp/yt-dlp/releases/download/2026.07.04/yt-dlp_linux.sha256 \
    && cd /tmp && sha256sum -c yt-dlp.sha256 \
    && chmod a+rx /usr/local/bin/yt-dlp
RUN pip3 install --break-system-packages --no-cache-dir whisper-ctranslate2
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
