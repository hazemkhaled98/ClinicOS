FROM eclipse-temurin:25-jre AS extract
WORKDIR /workspace
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=extract --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=extract --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /workspace/extracted/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
