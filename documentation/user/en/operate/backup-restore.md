---
title: Backup & restore
perex: Learn how to back up and restore your data in evitaDB using various methods, including current snapshots, point-in-time snapshots, and full file system copies.
date: '24.8.2025'
author: 'Ing. Jan Novotný'
---

evitaDB offers multiple ways to back up your data. Ultimately, all such administrative operations will be accessible via all client APIs, but currently only the gRPC/Java API supports them (see [issue #627](https://github.com/FgForrest/evitaDB/issues/627)). There are three main ways to back up your data - the PIT (point-in-time) backup is available only when the *time-travel* option is enabled in the [configuration](configure.md#storage-configuration):

![Backup options in evitaLab](assets/backup-options.png "Backup options in evitaLab")

None of these backup options interferes with normal database operations; you can continue to read and write data while the backup is being created. Because of the append-only storage architecture, the backup process can run safely without blocking any operations. However, keep in mind that creating a backup may have some performance impact.

<Note type="info">

If you need to know more about the storage architecture and internals that these backup options are based on, please refer to the [storage model documentation](../deep-dive/storage-model.md#backup-and-restore).

</Note>

## Current snapshot

The current snapshot contains a copy of the current data and, optionally, the contents of the transaction log (WAL), if you request that option when the backup is created. The contents of the transaction log are copied in full, and they may contain operations already reflected in the snapshot as well as operations that haven't been applied yet. When you restore data from such a backup, the snapshot is restored first, and then all unprocessed operations from the transaction log are applied in the order they were originally executed. This way, you can restore the database to the exact state it was in at the moment of backup creation. If you don't include the transaction log in the backup, the database is restored to the state it was in at the time of snapshot creation but might miss some unprocessed updates. The benefit of this approach is that a snapshot backup without WAL is the smallest possible backup you can create.

## Point-in-time snapshot

A point-in-time (PIT) snapshot is a special type of backup that can be created only when the *time-travel* option is enabled in the [configuration](configure.md#storage-configuration). This type of backup contains a copy of the database at a specific point in the past. When you create such a backup, you specify the exact timestamp you want to back up. The system then creates a snapshot of the database as it was at that moment. This is particularly useful when you need to restore the database to a specific historical state, such as after accidental data deletion or corruption.

There is a limited time window in the past for which you can create PIT snapshots. Two independent limits bound that window, and whichever one is reached first decides how far back you can go.

The first is the retention period of the transaction log, configured in the [transaction configuration](configure.md#transaction-configuration) via the parameters *walFileSizeBytes* and *walFileCountKept*. The database keeps all historical data for all committed transactions that are still present in the transaction log (WAL) files. When a WAL file is deleted, all files with historical data referenced from that WAL file are deleted as well.

The second is *timeTravelSizeLimitBytes* in the [storage configuration](configure.md#storage-configuration), which caps how much disk space the retained history may occupy on top of the active data set (1 GB by default). When the retained history exceeds it, the oldest historical data is given up until it fits again. The two limits count different things and there is no fixed relation between them: WAL rotation is driven by appended mutation bytes, while the size limit is driven by the data file copies that <a href="../deep-dive/storage-model.md#cleaning-up-the-clutter">compaction</a> leaves behind. A catalog that compacts frequently can therefore exhaust its size budget long before its WAL files rotate.

If you try to create a PIT snapshot for a timestamp older than the oldest retained historical data, the operation will fail. By tuning these configuration parameters, you can control how far back in time you can create PIT snapshots. The period is highly dependent on database activity - the more updates you perform, the more historical data is generated, and the faster both the WAL files are rotated and the size budget is consumed.

<Note type="info">

You can include transaction log (WAL) files in the PIT snapshot backup as well, but this is intended only for debugging use cases. When you restore from a PIT snapshot that includes WAL files, the database is restored to the specified point in time, and then all operations from the included WAL files that were executed after that point are applied. You will end up with the current state of the database, just like with a current snapshot backup that includes WAL files.

</Note>

## Full file system copy

The full file system copy is the simplest way to back up the database. It copies and compresses the entire catalog storage directory, with files processed in the correct order. It might be quite large, but it contains all data, including historical data. When you restore from such a backup, the database is restored to the exact state it was in at the moment of backup creation. You can still perform PIT backups from a database restored this way, as all historical data is still present.

## Restoring a catalog to a past version

A catalog that is in service can be put back to the state it had at an earlier version in a single operation - the one behind the *Restore to this version* action in evitaLab. You name the catalog, the version (or the moment) you want it returned to, and optionally the catalog the result should be served under; everything else is done for you:

1. a point-in-time snapshot of the requested version is created,
2. the snapshot is restored into a temporary catalog,
3. the temporary catalog is loaded and its indexes are built,
4. the temporary catalog takes the place of the target catalog.

Only the last step is visible to your clients, and it is a pointer swap that takes the same negligible time whatever the size of the catalog. Everything before it happens beside the running catalog, which keeps answering queries and accepting writes throughout. Sessions open across the swap are closed and have to be reopened; a query already in flight always finishes.

The whole operation is tracked as a single background task, so a user interface can show its progress and report when the catalog is ready.

<Note type="warning">

**This operation destroys data, and none of it can be recovered afterwards.**

- The catalog being replaced is **removed together with its entire history**. Create a [full file system copy](#full-file-system-copy) first if you may want that state back.
- The restored catalog carries **no transaction log**. Its history begins at the moment it is restored, so it cannot itself be taken back to a version older than that. The log is excluded on purpose: a restore that included it would replay the log forward and end up at the state you are trying to leave.
- Writes committed to the replaced catalog after the selected version are discarded with it. This includes writes committed while the operation is running, as the catalog keeps accepting them until the swap.

</Note>

How far back you may go is bounded by the retained history, exactly as it is for a [point-in-time snapshot](#point-in-time-snapshot): asking for a version that is no longer retained fails immediately, before any work is done. Asking for no version at all copies the catalog as it currently stands, which needs no retained history and is a way of taking a defensive copy of a catalog under another name.

Naming a target catalog other than the source leaves the source untouched and puts the restored state under that name instead - replacing whatever catalog held it, or creating it when the name is free.

If the operation fails or is cancelled, the catalog it was going to replace is left exactly as it was, and the intermediate snapshot stays among the files available for download so the restore can be repeated by hand. A successful operation removes it.

The temporary catalog is named after the catalog being restored, followed by `_restore_` and eight hexadecimal characters — `myCatalog_restore_3f7a1c02`, say. It is created and removed by the operation itself, with one exception: **if the server is stopped or crashes while a restore is in progress, the temporary catalog survives the restart** and appears in the catalog listing like any other. It is safe to delete, and it is the only residue such a restart can leave. Two further details make the size of the snapshot worth checking before you start: the snapshot is written into the export directory, and that directory is [size-limited](configure.md#file-system-export-configuration) — a catalog whose snapshot alone exceeds the limit cannot be restored this way until the limit is raised.