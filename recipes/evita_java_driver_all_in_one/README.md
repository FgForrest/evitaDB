# Shaded Java driver

Use this recipe when your application has dependency conflicts with gRPC, Armeria, Netty, Jackson
or Guava. Otherwise, depend on `io.evitadb:evita_java_driver` directly.

Copy this directory and build it with JDK 17+ and Maven:

```shell
mvn -Drevision=<evitaDB version> install
```

You must pass a version matching a released `io.evitadb:evita_java_driver`; there is no default.
The shaded jar is written to `target/evita_java_driver_all_in_one-<evitaDB version>.jar` and installed
in your local Maven repository. Running it also requires JDK 17+.

To publish it in your own repository, configure `distributionManagement` in the POM and credentials
in your Maven settings, then use `deploy` instead of `install`. You may change the recipe's `groupId`
to your own namespace.
