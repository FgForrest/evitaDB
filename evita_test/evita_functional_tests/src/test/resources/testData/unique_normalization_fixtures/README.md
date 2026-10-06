# Release-written unique index fixtures

Catalogs written by the released **v2026.2.18** engine (storage protocol 6), opened by
`io.evitadb.store.catalog.UniqueIndexReleaseUpgradeTest` to exercise the standalone unique index re-key of
`Migration_2026_3`:

- `unique_raw_keys` - raw unique values (precomposed text, decimals finer than the indexed scale), inline and
  paged, in the catalog file and in a collection file.
- `unique_collisions` - three pairs of values that the release kept apart and this version compares as one
  (two Unicode spellings of a global `code`, of a localized `url`, and two decimals equal at two decimal places).

They were produced by `UniqueFixtureGenerator.java.txt` (kept as text so the test build does not compile it - it
compiles against the release API only):

```shell
javac -proc:none -cp "<v2026.2.18 class path>" -d classes UniqueFixtureGenerator.java
java -cp "classes:<v2026.2.18 class path>" UniqueFixtureGenerator out/unique_raw_keys unique_raw_keys
java -cp "classes:<v2026.2.18 class path>" UniqueFixtureGenerator out/unique_collisions unique_collisions
```

The class path is the `target/classes` of a `v2026.2.18` checkout (`evita_common`, `evita_query`, `evita_api`,
`evita_roaring_bitmap`, `evita_engine`, the three `evita_store` modules and `evita_export_fs`) plus their runtime
dependencies. Copy each `out/<name>/storage` directory here, without its `.lock` file.
