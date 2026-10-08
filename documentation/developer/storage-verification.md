# Verifying that a build reads existing storage

`tools/verify-storage.sh` checks that the current checkout reads an existing evitaDB storage directory completely and
correctly. Unit tests prove a reader against data they wrote themselves. This tool proves it against data that already
exists: a production backup, a restored customer catalog, the output of an older version. Reach for it in these cases:

- **Any change to the read path:** `ObservableInput`, `StorageRecord`, the WAL suppliers, a serializer.
- **A storage format change or a migration.**
- **Before a hotfix ships to a release branch.** A reader defect does not show on fresh test data, only where records
  happen to land on real files.

It was written for #1687. There the compressed-record reader miscounted its stream offset after every refill of its
raw buffer, and the WAL reader then refused intact transactions. Every unit test passed. The offset was wrong after
1.45 million of the 90 million records on the datasets at hand, and two production WALs stalled a CDC catch-up
silently.

## Three levels

```shell
tools/verify-storage.sh --storage <storage directory>            # all three levels
tools/verify-storage.sh --storage <dir> --records --wal          # any subset
```

Each level exercises a layer the one below it cannot reach.

### `--records`: every record against an independent oracle

`StorageRecordVerifier` reads every `.wal`, `.collection` and `.catalog` file twice.

- **The oracle** parses the record framing byte by byte and checks every CRC32C. It inflates compressed payloads with
  its own `Inflater`. None of the engine's reading code is involved. The numbers that define the format (record
  overhead, control-byte bits, WAL framing sizes, file suffixes) come from the production constants the writer uses,
  so the oracle cannot drift from the format it checks.
- **The reader under test** is `ObservableInput` with `StorageRecord`, in the access patterns the engine uses:

| Pattern | What it is | Where the engine does it |
|---|---|---|
| `data-seq` | a data file, record after record from offset 0, no seeking | sequential scans |
| `data-seek` | a data file, one seek per record | fetches through the offset index |
| `wal-seq` | a WAL walked transaction by transaction, no seeking | the forward WAL supplier; CDC catch-up |

After every record, the payload bytes must match the oracle's, and `total()` must equal the oracle's file offset. For
a WAL transaction, the measured size of the leading record must also match its framing: that is the premise the WAL
supplier checks, and #1687 broke it.

Every file is verified once per buffer size (`--buffer-sizes`, default `16384,4096`). The buffer size decides where
records straddle the reader's buffer edges, so a second size multiplies the edge cases the same bytes produce. 16384 is
the engine's size.

### `--wal`: replay through the production reader

`WalReplayVerifier` opens every catalog WAL with `CatalogWriteAheadLog` and drains it twice per start version:

- **The greedy stream:** change data capture catches up with it, and it ends quietly on a read failure.
- **The strict stream:** trunk incorporation uses it, and it throws instead.

Every mutation is deserialized by Kryo. Replays start from the first version and from `--wal-start-points` more
versions spread over the log (default 30). The start matters: a reader seeks to its first transaction and then reads
on sequentially, so the start version decides where every later record lands in its buffer. A replay is complete when
it delivers every version up to the last one, each transaction with exactly the mutations it declares.

The engine's own log (`evitaDB_<n>.wal`) has a different reader. Only `--records` covers it.

### `--entities`: through the deserializers

`EntityFetchVerifier` starts the engine on a copy of the storage directory. Loading the catalogs deserializes:

- the catalog headers,
- the schemas,
- every entity index,
- the WAL behind the last published state, which is replayed.

It then fetches every entity of every collection in every scope, with attributes in all locales, associated data,
prices, references with their attributes, and the parent. That deserializes every entity storage part.

For each collection and scope it prints the entity count and a SHA-256 digest of a canonical text of the content. These
lines carry no timing, so the output of two builds can be compared with `diff`. A page that fails is re-fetched entity
by entity, so the failure names the primary keys it hit.

## Reading the result

The script ends with `VERIFICATION PASSED` (exit 0) or `VERIFICATION FAILED` (exit 1). Each level also prints its own
`# SUMMARY` line.

- **`--records`**
  - `status=FAILED` on a file line means at least one of these:
    - the reader threw,
    - its payload differed from the oracle's,
    - `total()` differed from the file offset (`offsetMismatches`),
    - a WAL framing measurement disagreed (`framingMismatches`).
  - The first offset mismatch is quoted with both values. A difference of a whole multiple of the buffer size is the
    #1687 signature.
  - `ORACLE:` in the failure means the oracle itself could not parse the bytes, for example a checksum mismatch in
    the middle of a file. That is a statement about the data, not the reader. Check it before suspecting the code.
  - `tornTail` is not a failure. It is the unfinished last record or transaction a crash leaves behind. Nothing
    references it (see `.claude/rules/durability-model.md`), and the WAL reader stops in front of it too.
- **`--wal`:** an `INCOMPLETE` replay names the start version, the last version delivered, and the exception if
  there was one. A greedy replay that ends early with `failure=null` is the silent stall.
- **`--entities`:**
  - A catalog that does not reach `ALIVE` counts as a failure.
  - So does a collection whose `fetched` is below `expected`; its `samples` hold the first failing primary keys.

## Comparing two builds of a class

`--shadow-source <File.java>` compiles a source file in front of the checkout's classes. The file is typically an
older or a modified version of a class from the read path. The same data is then read with that class instead of the
checkout's. It is repeatable, and the sources may use Lombok.

This is how to prove that a fix fixes something, and that it changes nothing else:

```shell
git show <commit>^:evita_store/evita_store_key_value/src/main/java/io/evitadb/store/kryo/ObservableInput.java \
  > /tmp/previous/ObservableInput.java

tools/verify-storage.sh --storage <dir> > fixed.log
tools/verify-storage.sh --storage <dir> --skip-build --shadow-source /tmp/previous/ObservableInput.java > previous.log
diff <(grep '^COLLECTION' fixed.log) <(grep '^COLLECTION' previous.log)
```

A change confined to the reader's bookkeeping must leave the payloads and the entity digests identical, and it must
move only what it claims to move. On #1687:

- **`--records`:** the pre-fix reader produced 1,451,646 offset mismatches and the fixed one none, over byte-identical
  payloads.
- **`--wal`:** 10 replays were incomplete on two production WALs with the pre-fix reader, and none with the fix.
- **`--entities`:** 133 of 133 collection digests were identical between the two builds.

**Run the counterfactual before trusting a green result.** A verification that passes against the very defect it is
meant to catch proves nothing. With the pre-fix `ObservableInput` shadowed in, the tool must fail.

## Things to know

- **The source directory is never written to.** `--wal` and `--entities` work on copies in the work directory, which
  is deleted afterwards unless `--keep-work` is given. The copies matter:
  - Opening a log truncates a torn tail.
  - An engine started on storage written by an older version upgrades the storage protocol of what it opens, even in
    read-only mode.
- **Data written by another version may not open at all.** A release build refuses an engine state that a newer
  development build wrote (`StoredVersionNotSupportedException`), and `--entities` then fails before any catalog
  loads. Verify such storage from a checkout of the version that wrote it or a newer one. `--records` reads only the
  framing and works across versions, as long as the WAL files carry cumulative checksums (storage protocol 5 and
  newer).
- **The tool compiles against the checkout it lives in.** It builds the storage modules first (`--skip-build` reuses
  them) and uses their `target/classes`, never the snapshot jars in the local Maven repository, which may come from
  another checkout.
- **Reads never cross the split of a chained record.** A record larger than the writer's buffer is stored as a chain
  of physical records, split between two values. `--records` clips its reads at those split points, because no
  serializer read crosses one. Running `StorageRecordVerifier` directly with `-Dunaligned=true` drops the clipping;
  the reader then misaligns on chained records by design.
- **Size the heap to the catalog.** `--entities` holds the whole catalog in memory, like the engine does: 8g suffices
  for a few hundred MB of data, while a 6 GB catalog loaded with `--heap 24g`.
- **It reads, it does not repair.** A failure tells you which record, transaction or entity is affected. What to do
  about it is a separate decision.
