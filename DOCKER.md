# Docker Setup Instructions

This Spring Boot application is now ready to be containerized and deployed with Docker.

## Prerequisites

- Docker installed and running
- Docker Compose (optional, for easier management)

## Building the Docker Image

```bash
docker build -t spring-boot-demo:latest .
```

## Running with Docker

### Option 1: Using Docker directly

```bash
docker run -p 8080:8080 spring-boot-demo:latest
```

The app will be available at: `http://localhost:8080/api/test/hello`

### Option 2: Using Docker Compose (Recommended)

```bash
docker-compose up --build
```

This will:
- Build the image if it doesn't exist
- Start the container as `spring-boot-demo`
- Expose port 8080
- Enable automatic restart policy
- Run health checks

To stop:
```bash
docker-compose down
```

## Files Included

- **Dockerfile**: Multi-stage build that compiles the app in Maven and runs it in a slim JRE image
- **.dockerignore**: Excludes unnecessary files from the Docker build context
- **docker-compose.yml**: Orchestration file with health checks and environment configuration

## Features

✅ Multi-stage build (small final image)  
✅ Alpine Linux base (lightweight)  
✅ Health checks configured  
✅ Port 8080 exposed  
✅ Automatic restart on failure  

## Checking Container Status

```bash
docker ps
docker logs spring-boot-demo
docker exec -it spring-boot-demo curl http://localhost:8080/api/test/hello
```
