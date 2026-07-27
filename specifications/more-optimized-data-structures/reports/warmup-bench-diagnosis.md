# Diagnosis: unique/ALIVE churn bottleneck (25m49s at N=500k, -Xmx5g)

## Symptom
unique/ALIVE churn (25m49s) is 3.4x slower than range/ALIVE (7m41s) and chain/ALIVE (7m15s),
despite identical transaction machinery. Heap bump 5g->10g did NOT help (3m8s vs 3m17s per 200k ops).

## Root cause: allocation churn from FrontCodedStringColumn (the unique `url` String attribute)

### CPU profile (on-CPU, 5g) — 56% of CPU is GC
  GC (G1 scan/mark/copy)        55.9%   <- G1ParScan::trim_queue 29.7%, OopOopIterate (mark) ~15%
  evitaDB session/commit        42.9%
  OffsetIndex (ChampMap)        29.8%
  Kryo serialize/store parts    26.4%
  WAL/mutation recording         7.8%
  UniqueIndex/GlobalUnique       0.0%   <- the unique index itself is FREE

### Allocation profile (byte-weighted) — byte[] is 55% of all allocated bytes
  byte[] is 55.4% of bytes; of those byte[]:
    FrontCodedStringColumn.decodeAllBytes   42.2%
    FrontCodedStringColumn.decodeAt         21.1%
    Output.<init> (Kryo buffer)             10.6%
    FrontCodedStringColumn.encode            7.9%
    FrontCodedStringColumn.ensureCapacity    7.1%
    PageStreamRegistry.stage                 6.0%
    FrontCodedStringColumn.duplicate         2.1%
  => ~73% of byte[] churn is FrontCodedStringColumn (read decodeAt/decodeAllBytes + write encode)
  Other ~45% of bytes = MVCC index objects: OffsetIndex ChampMap nodes (LocationNode/BitmapLocationNode),
  FileLocation, RecordKey, VersionedValue, HashMap/ConcurrentHashMap nodes, transactional-layer keys.

## Why unique >> range/chain
unique stores String `url` -> FrontCodedStringColumn (front-coded string column, added #760).
range (IntegerNumberRange) and chain (Predecessor) use NON-string attributes -> no front-coded column churn.

## Why WARMING_UP unique churn is fast (13s) but ALIVE is 25m
WARMING_UP: in-memory transactional layer, single flush at goLive.
ALIVE: 5000 commits (100 ops/batch) -> each commit re-reads/re-encodes the front-coded string column.

## Heap is NOT the lever
5g->10g: ~same wall-clock. G1 total work ~constant (proportional to live set + garbage, not heap).
Fix must REDUCE allocations (esp. FrontCodedStringColumn decode/encode byte[] per op/commit),
not grow the heap.

## Flamegraph deliverables (open in browser)
  unique-alive-wall.html   - wall-clock (polluted by idle threads; see cpu instead)
  unique-alive-cpu.html    - on-CPU (shows 56% GC)
  unique-alive-alloc.html  - allocation by bytes (FrontCodedStringColumn dominates)
