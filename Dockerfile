
# One Dockerfile for all FinCore services
FROM maven:3.9.9-eclipse-temurin-21 AS build

ARG MODULE
WORKDIR /src

COPY . .

# -am also builds required modules such as platform-commons
RUN mvn -B -U -pl ${MODULE} -am -DskipTests -DskipITs package

FROM eclipse-temurin:21-jre-alpine

ARG MODULE

RUN addgroup -S fincore && adduser -S fincore -G fincore

WORKDIR /app

COPY --from=build /src/${MODULE}/target/${MODULE}-*.jar /app/app.jar

USER fincore

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
