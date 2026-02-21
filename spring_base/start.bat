@echo off
echo Starting Spring Boot application with auto-reload enabled...
echo.
echo Once the app is running, any changes to Java files or resources will trigger an automatic restart. (-DskipTests is used to speed up the startup by skipping tests)
echo.
mvnw spring-boot:run -DskipTests
pause
