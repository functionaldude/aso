# ASO frontend

Angular 22, Angular Material, strict TypeScript, standalone components, signals, SCSS, and Vitest.

Run from the repository root with the pinned Node/npm toolchain:

```bash
./gradlew frontendStart
./gradlew frontendTest frontendLint frontendFormatCheck
./gradlew frontendFormat
```

Development uses port 4200 and proxies API requests to Spring on port 8080. The production build is packaged in the Spring Boot jar. See the root README for full setup, database configuration, and checks.
