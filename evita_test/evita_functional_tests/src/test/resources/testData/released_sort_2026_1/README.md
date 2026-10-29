These four parts were written and read back by the published **2026.1.20 jars**, using
`tools/fixtures/GenerateRelease2026_1SortParts.java`. Their leading UID is `-7076092972784353868`.
The compressed dictionary has `AttributeIndexKey(null, "validity", null)` at ID 1.

| Resource | Record IDs | Positional block lengths | Sparse entry order |
|---|---|---|---|
| unequal.bin | 1, 2, 3, 4, 5 | 2, 3 | second value, first value |
| singleton.bin | 10, 20, 21, 22 | 1, 3 | second value |
| compound.bin | 4, 5, 7, 3, 9, 12 | 2, 1, 3 | third value, first value |
| control.bin | 1, 2, 3, 4, 5 | 2, 3 | second value, first value |

The colliding ranges end at `2026-01-01T11:00+02:00` and `2026-01-01T10:00+01:00`.
The compound has a nullable range component and an integer component. The control uses a 2027 end.
`LinkedHashMap` deliberately supplies reversed sparse entries to the released writer: a released map
has no obligation to follow value order.

To regenerate, set `RELEASE_CP` to the classpath of the released 2026.1.20 engine and dependencies,
then run from the repository root (with `FIXTURE_CLASSES` naming a scratch directory):

```sh
javac -proc:none -cp "$RELEASE_CP" -d "$FIXTURE_CLASSES" \
  tools/fixtures/GenerateRelease2026_1SortParts.java
java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
  -cp "$FIXTURE_CLASSES:$RELEASE_CP" GenerateRelease2026_1SortParts \
  evita_test/evita_functional_tests/src/test/resources/testData/released_sort_2026_1
```

Use the released writer; the current writer cannot construct these released distinctions.

The 2025.5 reader test substitutes only that layout's UID and uses the old `AttributeKey` dictionary entry.
Its payload layout is identical; that adaptation is synthetic, while these committed bytes are real 2026.1.20 output.
The whole catalog is generated separately by `Release2026_1FixtureGenerator` from the
`Release2026_1FixtureRecipes.inheritedSortTwins()` recipe and stays at protocol 4.
