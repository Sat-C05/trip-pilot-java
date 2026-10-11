# Local infrastructure — direct MySQL

Use direct local MySQL without Docker, per the project development preference. The Windows workspace uses portable MySQL 8.4.11 on localhost:13306; the backend listens on localhost:8002 and the frontend proxy targets that port. The existing system MySQL service on 3306 is preserved. Compose files are unused reference material; do not install, start or repair Docker for this project.

For a fresh Windows setup, extract the official MySQL 8.4 ZIP to runtime-data/tools/mysql-8.4.11-winx64 or supply -BinaryDirectory to scripts/start-local-mysql.ps1. In PowerShell from the project root:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\start-local-mysql.ps1
. .\scripts\use-local-environment.ps1
```

The helper creates only runtime-data/local-mysql, generates random root/application credentials, binds localhost, and refuses occupied ports or unidentified existing data. The environment helper loads connection values without displaying them and selects portable Java 21 when present. stop-local-mysql.ps1 checks executable/start time/port ownership before graceful shutdown and preserves database data. Data, recovery configuration and downloaded runtimes are ignored by Git.

No model container or application container is used. Runtime integration is Phase 2 work; production deployment is deferred. See the root README for application startup and verification.