
Short answer: yes, you need a few local tools installed first. No paid web service or API key is required for this project.

You need these installed on your machine

Docker Desktop (with Docker Compose)
Java 21 JDK
Maven 3.8+ (important: mvn was not found in your terminal earlier)
Node.js LTS + npm (for Angular frontend)
Optional: Git
You can install from terminal on Windows (recommended)

winget install -e --id Docker.DockerDesktop
winget install -e --id EclipseAdoptium.Temurin.21.JDK
winget install -e --id Apache.Maven
winget install -e --id OpenJS.NodeJS.LTS
Then restart terminal and verify

docker --version
docker compose version
java -version
mvn -version
node -v
npm -v
Run the whole project

Start infra/services with docker compose from project root:
docker-compose up -d
Build Java services (if running them outside containers):
mvn clean package -DskipTests
Start frontend:
cd angular_base
npm install
npm run dev