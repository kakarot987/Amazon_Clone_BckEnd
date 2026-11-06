FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/libs/app.jar /app/app.jar
EXPOSE 9999
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
