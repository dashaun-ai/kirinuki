# Build stage
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline
COPY src ./src
RUN ./mvnw package -DskipTests
RUN ./mvnw dependency:copy-dependencies -DoutputDirectory=target/dependency -q
RUN java -cp "target/dependency/*" com.microsoft.playwright.CLI install chromium

# Runtime stage
FROM eclipse-temurin:25-jre-noble
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    wget \
    ca-certificates \
    libglib2.0-0 \
    libnss3 \
    libnspr4 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxkbcommon0 \
    libxcomposite1 \
    libxdamage1 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libpango-1.0-0 \
    libcairo2 \
    libasound2 \
    libatspi2.0-0 \
    fonts-noto-color-emoji \
    fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
RUN wget -O /usr/local/bin/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/download/2026.07.04/yt-dlp_linux \
    && chmod a+rx /usr/local/bin/yt-dlp
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
COPY --from=builder /root/.cache/ms-playwright /opt/ms-playwright
RUN chown -R app:app /opt/ms-playwright
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
