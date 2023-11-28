---
title: Troubleshoot
perex: |
    Stuff happens. And when it hits the fan, it's good to be prepared. This chapter is intended to give you some 
    knowledge and techniques for diagnosing the problems when they occur. Expect this article to be expanded as we get 
    smarter about the stuff that has already happened.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---

**Work in progress**

## Functional issues

### Query returns invalid result

### Web API is not responding

## Performance issues

### Query is slow

### Page rendering is slow

### Database is slow in overall

First, look at the [monitoring](../../operate/monitor.md) and verify:

- how many queries are processed per second
- what background jobs are running and for how long
- how many items are fetched from the disk storage
- how exhausted are thread pools
- what are the slowest queries and what part of query processing takes the most time

## Resource issues

### Data directory grows too much

evitaDB follows [append-only storage](https://en.wikipedia.org/wiki/Append-only) pattern and therefore the files of
the database only grows and continuously fill with the historical & dead data. In order to avoid disk exhaustion there
is a vacuuming process baked into the engine which asynchronously cleans the old data, that it knows it will never be
used or are out of scope of the historical records the owner wants to keep.

If the directory grows too much beyond the expected size of the working data-set, there are to things to check:

1. **what [settings](../../operate/configure.md) are set for vacuuming policy** - if the evitaDB is configured too much
   garbage in the data or to retain too long history for the records, the file might grew over your expectations
2. **whether the vacuuming process [runs regularly](../../operate/monitor.md)** - it there is high pressure on the 
   system or too many writes are processed, the vacuuming process might not keep up 

The amount of the old and garbage data along with vacuuming process statistics can be found in 
[monitoring dashboard](../../operate/monitor.md).

<Note type="warning">
The vacuuming process is not yet implemented - track [issue #41](https://github.com/FgForrest/evitaDB/issues/41).
When the vacuuming process is done exact configuration options and monitoring metrics will be added to this chapter.
</Note>

### Out of memory is reported

### evitaDB process gets killed by the operating system

