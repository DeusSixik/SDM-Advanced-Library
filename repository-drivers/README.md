# Bundled repository runtime

This module contains no mod or Minecraft classes. Its shaded artifact is used by
the common compilation classpath and merged into each loader's production JAR.
The public repository API returns neither driver nor shaded Gson types.

Bundled components:

- MongoDB Java sync driver / core / BSON 5.6.5 — Apache License 2.0.
- H2 2.5.252 — MPL 2.0 or EPL 1.0.
- Gson 2.13.2 — Apache License 2.0.

Packages are relocated beneath `dev.sixik.sdmal.internal.driver` so another mod's
driver/Gson version cannot replace the repository runtime. License texts and
source references are included under `META-INF/sdmal/repository-licenses/`.

The production mod omits the global JDBC service descriptor. `Repositories.h2`
loads the bundled driver explicitly; external databases are supplied through the
standard JDBC API or a caller-owned DataSource.

H2 SQL URLs, MongoDB URIs, table names, and on-disk representations are configuration
data and are not changed by relocation.
