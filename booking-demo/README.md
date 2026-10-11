# Booking demo provider

Independent Spring Boot process targeting Java 21, port 8081. Run from the repository root with `.\mvnw.cmd -pl booking-demo spring-boot:run`. Health: `http://127.0.0.1:8081/actuator/health`. Compilation, packaging and isolated health startup passed on Java 21 on 2026-10-11.

Phase 5 implements inventory, scoped all-inclusive quotes, expiring holds, idempotent submission and status lookup backed by a flushed/atomically replaced local ledger with exclusive ownership. Use `PROVIDER_STATE_FILE` to choose an isolated ledger; the default is `runtime-data/booking-demo/ledger.json` relative to the working directory. Run from the repository root. Keep the server on loopback: no remote authentication protocol is implemented.

The catalog is simulated INR stays in six Indian destinations; no payment or real reservations. Provider rechecks price, expiry, inventory and key/payload identity before recording a confirmation. The backend uses the ordinary wire contracts and explicit user authorization. See [booking flow, APIs, setup and verification](../docs/phase5-booking.md). `scripts/verify-phase5.ps1` exercises the packaged provider and real-MySQL backend plus full live/browser regression on disposable resources.
