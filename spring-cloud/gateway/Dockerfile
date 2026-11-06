FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/libs/app.jar /app/app.jar
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
