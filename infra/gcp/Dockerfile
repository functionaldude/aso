FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699
WORKDIR /app
COPY aso.jar /app/aso.jar
USER 10001:10001
ENV ASO_ADDRESS=0.0.0.0 ASO_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/aso.jar"]
