FROM openjdk:23

EXPOSE 8761

ADD ./build/libs/*.jar app.jar

ENTRYPOINT ["java","-jar","/app.jar"]
