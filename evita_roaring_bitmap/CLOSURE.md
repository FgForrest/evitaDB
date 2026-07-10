# Vendor Closure — evita_roaring_bitmap

Authoritative list of upstream RoaringBitmap classes to vendor, computed by `jdeps -verbose:class -filter:none`
on fork commit `f27cd538` (= upstream v1.6.12). The raw class list is in `CLOSURE.txt` (delete both before the final PR).

**Method:** seeded from the reshaped entrypoints + evitaDB's API surface, transitive-closed over the dependency
graph, with `org.roaringbitmap.buffer.*` and `org.roaringbitmap.insights.*` excluded as edges (not followed).

**Seeds:** `RoaringBitmap`, `CopyOnWriteRoaringBitmapV2` (folded), `RoaringBitmapWriter`, `PeekableIntIterator`,
`BatchIterator`, `RoaringBatchIterator`, `IntIterator`, `ImmutableBitmapDataProvider`,
`longlong.Roaring64Bitmap`, `longlong.ImmutableLongBitmapDataProvider`.

## Size: 74 classes
- `org.roaringbitmap` — 45
- `org.roaringbitmap.art` — 18 (full package; pulled by Roaring64Bitmap's adaptive radix tree)
- `org.roaringbitmap.longlong` — 11

## Excluded (intentional drops)
- **Whole packages:** `buffer` (Mutable/Immutable/Mappeable — not ported per reshape), `insights`.
- **`FrozenRoaringBitmap`** — issue calls it reference-only.
- **`RangeBitmap`** (+ nested) — not reachable from evitaDB's usage.
- **`FastRankRoaringBitmap`** — a second RoaringBitmap subclass; conflicts with the single-class goal. See surgery.

## P1 surgery required (sever excluded-package/class edges)

### Strip `buffer` references from these 6 in-closure classes
`Container`, `ArrayContainer`, `BitmapContainer`, `RunContainer`, `RoaringBitmap`(→`PersistentRoaringBitmap`),
`RoaringBitmapWriter`. These carry `toMappeableContainer()`-style conversions and (on `RoaringBitmap`) the
`ImmutableRoaringBitmap` ctor + `toMutableRoaringBitmap()`. Remove those members; they are unused by evitaDB.

### Strip `FastRankRoaringBitmap` references
- `RoaringBitmapWriter`: remove `fastRank()` (both decls ~L83/L206) + the `FastRankRoaringBitmapWizard` inner class (~L221-232).
- `BitSetUtil` (in closure): remove its `FastRankRoaringBitmap` reference.

## Fold (single-class reshape)
- `CopyOnWriteRoaringBitmapV2` is in the closure as a seed but is **not** vendored as a separate class: its
  ~25 `@Override` method bodies + added fields/helpers/static factories are transplanted into
  `PersistentRoaringBitmap` (the renamed base), then its file is deleted.
- `Roaring64Bitmap` → `PersistentLongRoaringBitmap`.

## Validation
This list is the starting point; the authority is a green `mvn compile` in P1. Any class the compiler still
demands (jdeps can miss reflection/generic-only refs) is added here and re-vendored.
