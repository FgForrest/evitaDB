# Module Boundaries

## The storage boundary

**Physical contact with catalog storage happens only in `evita_store`.** Everything a catalog is made of on disk —
listing its folder, reading a file's length, opening, writing, deleting or renaming one — is the storage module's
work and nobody else's. `evita_api` and `evita_engine` describe *what* is stored and *what it means*; only
`evita_store` knows that it is a file, where it sits, or what it is called.

This is what keeps the storage format replaceable. Every rule below follows from it, and the boundary is worth more
than the convenience of any single shortcut across it.

### What counts as physical contact

Execution of any of these outside `evita_store`:

- `File#listFiles`, `File#length`, `File#isFile`, `File#exists`, `File#mkdirs`, `File#delete`
- `Path#toFile`, and the `java.nio.file.Files` methods that touch a real path
- `FileInputStream` / `FileOutputStream` / `RandomAccessFile` / `FileChannel`

### What does not

- **Naming `Path` or `File` in a signature.** An SPI method may take or return one; it just may not act on it.
- **Parsing a file name that has already been read.** String work on a name is format knowledge, not IO — though it
  is a smell worth a second look, because format knowledge usually wants to sit next to the code that reads it.
- **The `evita_common` file utilities** — `FileUtils`, `IOUtils`, `FolderLock`, `RandomAccessFileInputStream`. These
  *are* the primitives the rule is written in terms of. Calling them from `evita_store` is the intended use;
  calling them from `evita_engine` to reach catalog files is the violation this rule exists to catch.

## SPI types define contracts, never perform IO

`io.evitadb.spi.store.**` lives in `evita_engine` but belongs to the storage layer's *interface*. It holds records,
interfaces, enums and file-name constants — the vocabulary both sides speak. It must contain **no executed IO at
all**: an SPI record is a data carrier that the storage module fills in and the engine reads.

The tell is a `static measure(...)` / `read(...)` / `scan(...)` on an SPI record. If a type in `spi.store` has a
method that *produces* an instance of itself by looking at a disk, that method is in the wrong module — move it to
`evita_store` and leave the record behind.

## Concerns this rule does not govern

Not every file in the codebase is catalog storage, and these are deliberately outside its scope:

- **Configuration** — `EvitaConfiguration` reads config paths at startup.
- **Exports, backups and the work directory** — `FileManagementService`, `EvitaManagement`, `TrafficRecorderTask`.
  These handle files the engine *produces for a user*, not files a catalog is made of, and they have their own
  service boundary.

If a new case is genuinely one of these, say so in a comment at the site. If the argument has to be made at length,
it is probably the other thing.

## Checking

```shell
PAT='listFiles\(|\.toFile\(\)|RandomAccessFile|FileChannel'
PAT="$PAT"'|new FileInputStream|new FileOutputStream'
PAT="$PAT"'|Files\.(exists|list|walk|readAll|newInput|newOutput|createDirect|delete|copy|move|size)'

rg -l --glob 'evita_engine/**/src/main/**/*.java' --glob '!**/generated/**' "$PAT"
```

Every hit must be either an interface naming a type it never touches, or one of the concerns named above. Anything
else is a violation. Run the same query against `evita_api` — it should return configuration and nothing more.

## Fixing a violation

Split the type rather than moving it wholesale. The data carrier usually has to stay where it is, because the SPI
method that returns it lives in `evita_engine` and cannot depend on `evita_store`.

The worked example is `CatalogStorageFootprint`: the record and its nested `DataStoreGenerations` stayed in
`spi.store.catalog.persistence`, while the two `measure(...)` overloads and the storage-prefix discovery that
listed directories and read file lengths moved to `CatalogStorageFootprintMeasurer` in `evita_store_server`. When
you do this, **rewrite any javadoc claiming the record can only be produced by its own factory** — after the split
that enclosure is gone, and the invariant is upheld by the measurer instead. See
`documentation/adr/2026-08-10-catalog-and-collection-statistics/README.md`.
