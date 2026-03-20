FROM gradle:8-jdk17 AS build

WORKDIR /project

COPY settings.gradle.kts build.gradle.kts ./
COPY src/ src/
COPY demo/build.gradle.kts demo/
COPY demo/src/ demo/src/

RUN gradle :demo:installDist --no-daemon

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=build /project/demo/build/install/demo/ ./

EXPOSE 3000

CMD ["bin/demo"]
