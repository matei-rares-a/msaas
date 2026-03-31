# Spring Base Microservice

This package contains the Spring Boot backend service.

## Prerequisites

- Java 25 (matches `pom.xml`)
- Maven Wrapper (`mvnw`/`mvnw.cmd` is included)

## Run the Service

From `spring_base`, run one of the following.

### Windows (batch script)

```bat
start.bat
```

### Cross-platform (Maven Wrapper)

```bash
./mvnw spring-boot:run -DskipTests
```

On Windows PowerShell/CMD:

```bat
mvnw.cmd spring-boot:run -DskipTests
```

Service URL:
- http://localhost:8080

Sample endpoint:
- http://localhost:8080/api/test/hello

## Run Tests

### Windows (batch script)

```bat
test.bat
```

### Cross-platform (Maven Wrapper)

```bash
./mvnw test
```

On Windows PowerShell/CMD:

```bat
mvnw.cmd test
```

## Package Jar

```bash
./mvnw clean package
```

On Windows PowerShell/CMD:

```bat
mvnw.cmd clean package
```
