FROM openjdk:23

EXPOSE 8888

ADD ./build/libs/*.jar app.jar

ENTRYPOINT ["java","-jar","/app.jar"]
