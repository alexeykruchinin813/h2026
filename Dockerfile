# Этап 1: Сборка JAR-файла с помощью Maven
FROM maven:3.8.6-openjdk-11 AS build
WORKDIR /app

# Копируем settings.xml с зеркалом Huawei
COPY settings.xml /root/.m2/settings.xml

# Копируем только pom.xml и скачиваем зависимости (кэшируется)
COPY pom.xml .
RUN mvn dependency:go-offline -s /root/.m2/settings.xml

# Копируем исходный код и собираем приложение
COPY src ./src
RUN mvn clean package -DskipTests -s /root/.m2/settings.xml

# Этап 2: Запуск приложения на Eclipse Temurin 11 JRE
FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]