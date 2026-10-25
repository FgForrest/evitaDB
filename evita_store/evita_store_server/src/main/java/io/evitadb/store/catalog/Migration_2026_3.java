/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */


package io.evitadb.store.catalog;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.dataType.array.CompositeLongArray;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.attribute.EntityReferenceWithLocale;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.LegacyOwnerSortIndexCorrection;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.catalog.VirtualReferencePartitionConversion.CollectionConversion;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.IOUtils.ExceptionThrowingRunnable;
import io.evitadb.utils.NumberUtils;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Upgrades a catalog from storage protocol version 6 to 7 by re-keying every persisted
 * {@link AttributeCardinalityIndex} onto the canonical form its sibling value tree keys on, every persisted
 * standalone unique index onto the key space of the filter index (see *Standalone unique indexes* below), by
 * rewriting every owner sort index that stores temporal values raw or holds localized texts in the order of the bare
 * collator, locales in the order of their language tag or date time ranges in the order of whole seconds (see *Owner
 * sort indexes* below), and by converting the single-owner reference partitions into rows of their registry (see
 * *Single-owner reference partitions* below) - one migration, published once.
 *
 * # What was wrong
 *
 * A cardinality index ref-counts how many owners contribute one entry to a shared filter index, so the entry is
 * dropped only when the last of them leaves. That only holds while the counter and the tree agree on what "the
 * same entry" is — and they did not: the counter keyed on the RAW attribute value while the tree keys on
 * {@link FilterIndex#getNormalizer(Class, int)}'s output, which is deliberately many-to-one (a `BigDecimal`
 * collapses to a scaled `int`, a `String` to NFD, an `OffsetDateTime` to an `Instant` that discards the
 * offset). All four of those folds are present in v2026.2.7, so every one of them can have damaged a catalog
 * this migration meets; only the millisecond truncation that today folds temporal values a SECOND way is
 * younger than 2026.2 and therefore cannot have.
 *
 * Two owners whose values differ only below that resolution therefore held TWO counter keys over ONE tree entry;
 * the first to move away drained its own counter to zero and removed the entry the other still needed, silently
 * unindexing it. From then on that owner is missing from filtering results, and the removal of its last
 * contribution fails the tree's `Sanity check - record not found!` premise and rolls its transaction back — while
 * an INSERT of any colliding value puts the entry back, so the state is recoverable rather than terminal.
 *
 * The engine now normalizes before the counter ({@link AttributeCardinalityIndex#normalizeKey}), so newly
 * written counters are already canonical. This migration brings the ones already on disk into that same form.
 *
 * A **localized** `String` attribute needed a second fix to reach the same place, because its value tree is
 * ordered by a collator and a collator equates strings `equals` keeps apart: two such values produced two
 * counters over one tree entry no matter how the keys were normalized. The index-key order now breaks that tie
 * (`EqualsConsistentLocalizedStringComparator`), so the counter and the tree agree there too.
 *
 * What this migration still cannot repair for that flavour is a record a PRE-tie-break engine filed under the
 * other spelling — its entity value names a bucket that no longer exists once the two spellings are distinct
 * keys. The audit below counts such a record as an orphaned counter, because its bucket map is keyed by `equals`
 * and the second spelling misses; that recommends the right action (reindex) for the wrong reason, so the tally
 * must not be read as a count of collated damage.
 *
 * # What it does
 *
 * Every key is mapped through the normalizer for its attribute, and counts whose keys collide are SUMMED. That
 * sum is precisely the state the fixed engine would have built: two owners contributing one shared entry become
 * a single key with a count of two, so the entry now survives until the second of them leaves.
 *
 * A part whose keys are already canonical (an `Integer` / `Boolean` / enum counter, or an ASCII-only `String`
 * one) is left untouched, so this costs nothing for the attribute types that were never affected.
 *
 * # What it deliberately does not do
 *
 * It does not repair a catalog whose tree entries are ALREADY missing — but it does REPORT one. Where an owner
 * was silently unindexed before the upgrade, summing the counters restores the right count over an entry that is
 * no longer there, and a later removal of that owner's last colliding contribution still fails the tree's premise
 * (an INSERT of another colliding value re-adds the entry, so the damage is self-limiting rather than terminal,
 * and the failing transaction rolls back).
 *
 * Because the summed counters state "record r belongs in bucket k" while the sibling {@link FilterIndexStoragePart}
 * states who is actually in that bucket, the difference between the two is computable from storage parts alone:
 * {@link #countOrphanedCounters} does exactly that and the upgrade logs an ERROR naming the collection, the
 * attributes and the number of missing entries, and recommending a reindex. Repair — putting the records back into
 * their buckets — is deliberately NOT attempted: it would mean an upgrade writing reconstructed user data with no
 * undo, and the reporting is what turns an otherwise cryptic later `Sanity check - record not found!` into an
 * actionable instruction. See {@link #countOrphanedCounters} for what the audit can and cannot see.
 *
 * It also never aborts the catalog upgrade over a single part. A `CARDINALITY` index can exist for an attribute
 * the owning entity schema cannot answer for — an inherited attribute of a reflected reference, or one the schema
 * no longer declares — and an earlier shape of this migration threw there, marking the whole catalog CORRUPTED on
 * boot. Such a part is now left exactly as it is, with a warning naming it; see {@link #resolveScale} for the
 * order the scale is established in, and why the sibling filter index's FROZEN scale is preferred over the
 * schema's current one.
 *
 * # Standalone unique indexes
 *
 * The catalog's {@link GlobalUniqueIndex} (`uniqueGlobally` / `uniqueGloballyWithinLocale`) and a collection's
 * {@link OwnerUniqueIndex} (a localized attribute unique across locales) used to store every value as it was written.
 * The engine now keys them exactly as the filter index does - through {@link FilterIndex#getNormalizer(Class, int)} at
 * the attribute's `indexedDecimalPlaces` - and persists each key as a value of the declared type
 * ({@link UniqueIndex#toPersistedValue}). Every persisted unique part, inline or paged, in every collection and in the
 * catalog file, is rewritten into that canonical form; a part already in it is left alone.
 *
 * Two persisted values of one part that now name one key and belong to different owners are a **collision**: no unique
 * index can hold one key for two owners, and which owner keeps the value is a decision only the data's author can
 * make. All collisions are collected by a pre-pass that writes nothing, and the upgrade is then refused with every
 * pair named, so the catalog stays exactly as the release that wrote it left it and can be fixed there.
 *
 * # Owner sort indexes
 *
 * An owner sort index - a sort-only attribute, every sortable attribute compound - of 2026.2 and every older release
 * differs from the one this version writes in four ways. It stored `OffsetDateTime`, `LocalDateTime` and `LocalTime`
 * values as they were written, while this version stores and compares their millisecond form. And it ordered a
 * localized `String` element by the bare collator, which equates two spellings such as `ab` with and without a
 * zero-width space between its letters, while this version breaks that tie - so a compound holding such a pair,
 * ordered by its next element, is stored in an order this version may reverse, and its pages fail to load as they are.
 * It ordered a `Locale` element by its language tag alone, which drops an ill-formed variant, so `en-US` with and
 * without one tie the same way. And it compared a `DateTimeRange` element at whole epoch seconds, an open bound derived
 * from the zone offset of the other one, while this version compares milliseconds with one constant for an open bound:
 * two ranges opening in the same second tie the same way, and two ranges open at one end whose other bounds carry
 * different offsets compare the other way, so even an index of a `DateTimeRange` alone is out of order.
 *
 * Every owner sort index of every entity index a collection lists that has a temporal, a localized `String`, a
 * `Locale` or a `DateTimeRange` element is read in full, and one holding a raw value or out of the order this version
 * compares its values in is rewritten whole in the current form and order ({@link LegacyOwnerSortIndexCorrection}): an
 * inline one as a new part, a paged one as new leaf pages above the persisted high-water, a new root, and the removal
 * of every persisted leaf page.
 * Apart from a paged index of `DateTimeRange` values alone, which it re-sorts at whole seconds, nothing on the load
 * path normalizes or re-sorts these values any more, so after this migration the catalog holds none of them in the old
 * form or order. A bucket in which the release merged two values its comparator equated - two spellings, two locales
 * of one tag, two ranges equal at whole seconds - stays merged, under the value it was stored with: it does not say
 * which record holds which value.
 *
 * # Single-owner reference partitions
 *
 * A catalog of 2026.2 represents every reference partition by an index object of its own; this version keeps a
 * partition of a reference indexed for filtering as a row of the registry on its referenced-type entity index while
 * it has a single owner. Once the indexes of a collection are re-keyed and its sort indexes rewritten,
 * {@link VirtualReferencePartitionConversion} converts its single-owner partitions into rows - after those steps,
 * because the conversion reads only the entity indexes the published collection header lists, reads them with the
 * parts those steps rewrote, and its final flush writes whatever they left unflushed. The re-key writes the
 * cardinality parts of the referenced-type and group indexes and the owner unique parts, the sort rewrite the owner
 * sort parts, and the conversion the registries and the removals of the partitions it converts. They meet only on a
 * partition object holding an owner unique part or an owner sort part - one of a reference that was indexed for
 * partitioning when the owner's attributes were written into it, or one carrying a sortable reference attribute: the
 * earlier steps rewrite the part, and the conversion, running later, removes it with the rest of the object.
 *
 * # Date-time values beyond the supported window
 *
 * Release 2026.2 and older accepted every `OffsetDateTime` and `LocalDateTime`, the JDK sentinels `MIN` / `MAX`
 * included (the gRPC converter of 2026.2 decoded every moment of year 9999 as `OffsetDateTime.MAX`, and every
 * moment of year 1 as `MIN`), and keyed their indexes by the `Instant` of such a value. This version keys a temporal
 * index by whole epoch milliseconds in a `long`, clamps such a value to the edge of that window as it enters
 * ({@link io.evitadb.dataType.EvitaDataTypes}), and fails to load or modify an index holding a key outside it. The
 * upgrade therefore moves every such key to the edge of the window, with the clamp
 * {@link LegacyOwnerSortIndexCorrection#clampToSupportedWindow} - in front of the normalizer wherever a key is
 * normalized: the filter trees (inline and paged, every entity index), the cardinality counters and both sides of
 * their audit, the standalone unique indexes, and the owner sort indexes. The entity bodies are not rewritten: the
 * reader of the version that wrote them clamps on read (`AttributeValueSerializer_2026_2`).
 *
 * Clamping can make two keys one, and so can the truncation to whole milliseconds the load path applies after it. In
 * a filter tree the buckets are clamped in place and the load path merges the adjacent buckets of an equal key,
 * uniting their records. A unique attribute cannot hold two owners at one key, so the pre-pass finds the owners of
 * different values that meet once clamped and truncated - in a standalone unique index and in a unique attribute
 * folded onto its filter tree - and refuses the upgrade like any other unique collision. A reference
 * partition keyed by a representative temporal value beyond the window cannot be re-keyed without renaming storage
 * parts, so the pre-pass refuses the upgrade over it too ({@link #refuseUpgradeOverOutOfWindowRepresentativeKeys}).
 *
 * # Publication
 *
 * Everything above only appends to the data files: a collection data store is flushed into records and an offset
 * index fragment no published header names, and the new collection headers and the re-keyed catalog-level parts wait
 * unflushed in the catalog data store. The post-upgrade action publishes all of it at once - the catalog header of
 * protocol 7 and one bootstrap record. A failure before it leaves the published state of protocol 6 as it was, and the
 * next start runs the whole migration again from it. No file is deleted on the way.
 *
 * Re-canonicalizing here heals keys written by older versions and must use the legacy function only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @deprecated removable once no catalog older than 2026.3 can be encountered — at which point no catalog can
 * still carry raw-keyed cardinality counters, raw-keyed unique indexes, temporal keys beyond the supported window,
 * owner sort indexes holding raw temporal values or ordered by the bare collator, a language tag or whole seconds, or
 * single-owner reference partitions held by index objects, and this migration has nothing left to do. Two readers do
 * not leave with it, each because records keep their `serialVersionUID` until they are rewritten - see each reader's
 * own deprecation note:
 *
 * - `AttributeCardinalityIndexStoragePartSerializer_2026_2`: a counter part this migration found already canonical is
 *   not rewritten;
 * - `AttributeValueSerializer_2026_2`: this migration rewrites no entity body at all, so every body 2026.2 wrote is
 *   read through it until its entity is next written.
 */
@Deprecated(since = "2026.3", forRemoval = true)
public interface Migration_2026_3 {

	/**
	 * Returned by {@link #countOrphanedCounters} in place of a count when the counter could not be checked against
	 * its shared attribute index at all — reported to the operator as "not checked" rather than silently as clean.
	 */
	int NOT_VERIFIABLE = -1;
	/**
	 * Upper bound on how many attribute names one diagnostic message spells out before it switches to a tally. A
	 * corrupted collection can hold hundreds; an unbounded list would push the remedy off the operator's screen.
	 */
	int MAX_REPORTED_ATTRIBUTES = 10;

	/**
	 * Re-keys every persisted attribute cardinality index and standalone unique index of every collection, and every
	 * global unique index of the catalog, onto its normalized form, rewrites every owner sort index storing temporal
	 * values raw, localized texts in the order of the bare collator, locales in the order of their language tag or
	 * date time ranges in the order of whole seconds, converts the single-owner reference partitions of every
	 * collection into registry rows, and commits the catalog header at protocol version 7. Refuses the upgrade, before
	 * writing anything, when two persisted unique values of different owners name one key - see
	 * {@link #refuseUpgradeOverUniqueKeyCollisions}.
	 *
	 * Every collection persistence service the upgrade opens through the factory is closed before it returns, whether
	 * it succeeds, refuses or fails - an open service keeps a handle of the collection's data file, which on some
	 * platforms keeps a later compaction from deleting the file it retires.
	 *
	 * @param catalogHeader                             the catalog header being upgraded
	 * @param storagePartPersistenceService             the catalog-level storage part service
	 * @param entityCollectionPersistenceServiceFactory builds a persistence service for one collection - a new
	 *                                                  instance on every call, closed by the upgrade
	 * @param postUpgradeAction                         commits the rewritten catalog header
	 */
	static void upgradeFromStorageProtocolVersion_6_to_7(
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService,
		@Nonnull Function<EntityCollectionFileHeader, DefaultEntityCollectionPersistenceService> entityCollectionPersistenceServiceFactory,
		@Nonnull Consumer<CatalogHeader<LogFileRecordReference, CollectionFileReference>> postUpgradeAction
	) {
		final List<DefaultEntityCollectionPersistenceService> openedServices =
			new ArrayList<>(catalogHeader.getEntityTypeFileIndexes().size());
		try {
			rekeyCatalog(
				catalogHeader,
				storagePartPersistenceService,
				// a service is recorded the moment it exists, so one opened right before a failure is closed as well
				entityCollectionHeader -> {
					final DefaultEntityCollectionPersistenceService service =
						entityCollectionPersistenceServiceFactory.apply(entityCollectionHeader);
					if (service != null) {
						openedServices.add(service);
					}
					return service;
				},
				postUpgradeAction
			);
		} finally {
			// a close deletes nothing: after a success it writes nothing either - every collection the upgrade
			// changed was flushed before its header was recorded - and after a failure it can only append what a
			// collection had not flushed yet, which no published header names; so one close after everything,
			// the publication included, covers every path. A close that fails is logged and the others still run
			IOUtils.closeQuietly(
				openedServices.stream()
					.map(service -> (ExceptionThrowingRunnable) service::close)
					.toArray(ExceptionThrowingRunnable[]::new)
			);
		}
	}

	/**
	 * Re-keys every persisted attribute cardinality index and standalone unique index of every collection, and every
	 * global unique index of the catalog, rewrites every owner sort index storing temporal values raw, localized texts
	 * in the order of the bare collator, locales in the order of their language tag or date time ranges in the order of
	 * whole seconds, converts the single-owner reference partitions of every collection into registry rows, and commits
	 * the catalog header at protocol version 7 - the work of {@link #upgradeFromStorageProtocolVersion_6_to_7}, which
	 * owns the collection persistence services it opens.
	 *
	 * @param catalogHeader                 the catalog header being upgraded
	 * @param storagePartPersistenceService the catalog-level storage part service
	 * @param collectionServiceFactory      opens the persistence service of one collection
	 * @param postUpgradeAction             commits the rewritten catalog header
	 */
	private static void rekeyCatalog(
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService,
		@Nonnull
		Function<EntityCollectionFileHeader, DefaultEntityCollectionPersistenceService> collectionServiceFactory,
		@Nonnull Consumer<CatalogHeader<LogFileRecordReference, CollectionFileReference>> postUpgradeAction
	) {
		ConsoleWriter.writeLine(
			"Catalog `" + catalogHeader.catalogName() + "` uses storage protocol version 6; re-keying attribute " +
				"cardinality indexes and standalone unique indexes to their canonical form, rewriting sort indexes " +
				"of temporal values, localized texts, locales and date time ranges, and converting single-owner " +
				"reference partitions into registry rows (protocol " +
				PersistenceService.STORAGE_PROTOCOL_VERSION + ").",
			ConsoleColor.BRIGHT_BLUE
		);

		final long catalogVersion = catalogHeader.version();
		final Collection<CollectionFileReference> entityTypeFileIndexes = catalogHeader.getEntityTypeFileIndexes();
		final HashMap<String, CollectionFileReference> newCollectionFileIndex =
			CollectionUtils.createHashMap(entityTypeFileIndexes.size());
		final EntityTypeClassifierResolver entityTypeResolver =
			new CatalogHeaderEntityTypeResolver(entityTypeFileIndexes);

		// every collection is opened once: the unique-key pre-pass below and the rewrite pass after it share them
		final List<CollectionUnderUpgrade> collections = new ArrayList<>(entityTypeFileIndexes.size());
		for (final CollectionFileReference entityTypeFileIndex : entityTypeFileIndexes) {
			// the stored header may name a data file generation a compaction has already replaced - the catalog
			// header is the copy that cannot lag, so the file is resolved from there
			final EntityCollectionFileHeader entityCollectionHeader = EntityCollectionHeaderReconciler.reconcile(
				catalogHeader.catalogName(),
				entityTypeFileIndex,
				Objects.requireNonNull(
					storagePartPersistenceService.getStoragePart(
						catalogVersion, entityTypeFileIndex.entityTypePrimaryKey(), EntityCollectionFileHeader.class
					)
				)
			);
			final DefaultEntityCollectionPersistenceService collectionPersistenceService = Objects.requireNonNull(
				collectionServiceFactory.apply(entityCollectionHeader)
			);
			final Set<Integer> indexIds = new HashSet<>(entityCollectionHeader.usedEntityIndexPrimaryKeys());
			if (entityCollectionHeader.globalEntityIndexPrimaryKey() != null) {
				indexIds.add(entityCollectionHeader.globalEntityIndexPrimaryKey());
			}
			// the entity schema supplies the per-attribute `indexedDecimalPlaces`; it is always stored under part id 1
			final EntitySchema entitySchema = Objects.requireNonNull(
				collectionPersistenceService.getStoragePartPersistenceService()
					.getStoragePart(catalogVersion, 1, EntitySchemaStoragePart.class),
				"Entity schema storage part is missing for collection `" + entityCollectionHeader.entityType() + "`!"
			).entitySchema();
			collections.add(
				new CollectionUnderUpgrade(
					entityTypeFileIndex, entityCollectionHeader, collectionPersistenceService, indexIds, entitySchema
				)
			);
		}
		// the conversion classifies the partitions against the schema the loaded catalog will have: a reflected
		// reference may inherit its indexing from the reference it reflects, which the stored schema alone does not
		// say - the re-key keeps reading the stored schema, as it always did
		final Map<String, EntitySchema> storedSchemas = CollectionUtils.createHashMap(collections.size());
		for (final CollectionUnderUpgrade collection : collections) {
			storedSchemas.put(collection.header().entityType(), collection.entitySchema());
		}
		final Function<String, Optional<? extends EntitySchemaContract>> schemaLookup =
			entityType -> Optional.ofNullable(storedSchemas.get(entityType));

		// THE PRE-PASS. The standalone unique trees are re-keyed onto the filter index's key space, and two stored
		// spellings of one value held by different owners would become one key with two owners - a state no unique
		// index can hold. Every such pair is found before a single byte is written, so a refusal leaves the catalog
		// exactly as the release that wrote it left it.
		final List<UniqueKeyCollision> collisions = new ArrayList<>();
		final List<GlobalUniqueIndexRekey> globalUniqueIndexRekeys = planGlobalUniqueIndexRekeys(
			catalogVersion, storagePartPersistenceService, entityTypeResolver, collections, collisions
		);
		final Map<String, List<OwnerUniqueIndexRekey>> ownerUniqueIndexRekeys =
			CollectionUtils.createHashMap(collections.size());
		for (final CollectionUnderUpgrade collection : collections) {
			ownerUniqueIndexRekeys.put(
				collection.header().entityType(),
				planOwnerUniqueIndexRekeys(catalogVersion, collection, collisions)
			);
		}
		// the filter trees holding a temporal key beyond the window of moments this version can hold are planned here
		// too: a unique attribute folded onto its filter tree is not covered by the unique pre-pass above, and two
		// owners whose keys meet once clamped and truncated are a collision just the same
		final Map<String, List<FilterIndexClamp>> filterIndexClamps = CollectionUtils.createHashMap(collections.size());
		final List<String> unrepairableRepresentativeKeys = new ArrayList<>(0);
		for (final CollectionUnderUpgrade collection : collections) {
			filterIndexClamps.put(
				collection.header().entityType(),
				planFilterIndexClamps(catalogVersion, collection, collisions)
			);
			findOutOfWindowRepresentativeKeys(catalogVersion, collection, unrepairableRepresentativeKeys);
		}
		if (!collisions.isEmpty()) {
			refuseUpgradeOverUniqueKeyCollisions(catalogHeader.catalogName(), collisions);
		}
		if (!unrepairableRepresentativeKeys.isEmpty()) {
			refuseUpgradeOverOutOfWindowRepresentativeKeys(
				catalogHeader.catalogName(), unrepairableRepresentativeKeys
			);
		}

		for (final CollectionUnderUpgrade collection : collections) {
			final CollectionFileReference entityTypeFileIndex = collection.fileReference();
			final EntityCollectionFileHeader entityCollectionHeader = collection.header();
			final DefaultEntityCollectionPersistenceService collectionPersistenceService = collection.service();
			final OffsetIndexStoragePartPersistenceService collectionStoragePartService =
				collectionPersistenceService.getStoragePartPersistenceService();

			final EntitySchema entitySchema = collection.entitySchema();

			// the filter trees first: the cardinality audit below compares each counter with the bucket it names, and
			// reads the tree as this step left it
			final List<FilterIndexClamp> clampedFilterIndexes =
				filterIndexClamps.get(entityCollectionHeader.entityType());
			for (final FilterIndexClamp clamp : clampedFilterIndexes) {
				for (final StoragePart part : clamp.parts()) {
					collectionStoragePartService.putStoragePart(catalogVersion, part);
				}
			}

			int rekeyedCounters = 0;
			int orphanedPairs = 0;
			final List<String> damagedAttributes = new ArrayList<>();
			final List<String> uncheckedAttributes = new ArrayList<>();
			for (final Integer indexPrimaryKey : collection.indexIds()) {
				final EntityIndexStoragePart indexPart = requireEntityIndexPart(
					collectionStoragePartService, catalogVersion, indexPrimaryKey
				);
				// the reference scope comes from the OWNING index key - a legacy-rehydrated AttributeIndexKey
				// carries a null reference name, so a reference attribute would otherwise be looked up at entity level
				final String referenceName = indexPart.getEntityIndexKey().referenceName();
				for (final AttributeIndexStorageKey attributeIndexKey : indexPart.getAttributeIndexes()) {
					if (attributeIndexKey.indexType() != AttributeIndexType.CARDINALITY) {
						continue;
					}
					final CounterOutcome outcome = rekeyCardinalityIndex(
						catalogVersion, indexPrimaryKey, attributeIndexKey,
						collectionStoragePartService, entitySchema, referenceName
					);
					if (outcome.rewritten()) {
						rekeyedCounters++;
					}
					if (outcome.orphanedPairs() == NOT_VERIFIABLE) {
						uncheckedAttributes.add(
							describeCounter(indexPrimaryKey, attributeIndexKey) +
								" (" + outcome.notCheckedReason() + ")"
						);
					} else if (outcome.orphanedPairs() > 0) {
						orphanedPairs += outcome.orphanedPairs();
						damagedAttributes.add(
							describeCounter(indexPrimaryKey, attributeIndexKey) +
								" (" + outcome.orphanedPairs() + " missing)"
						);
					}
				}
			}

			final List<OwnerUniqueIndexRekey> uniqueIndexRekeys =
				ownerUniqueIndexRekeys.get(entityCollectionHeader.entityType());
			for (final OwnerUniqueIndexRekey uniqueIndexRekey : uniqueIndexRekeys) {
				writeOwnerUniqueIndex(
					catalogVersion, entityCollectionHeader.entityType(), uniqueIndexRekey, collectionStoragePartService
				);
			}
			final boolean rekeyed = rekeyedCounters > 0 || !uniqueIndexRekeys.isEmpty();
			// the owner sort indexes in the form or order of an older release are rewritten before the conversion, for
			// two reasons: the conversion removes the partition objects it turns into rows, so a rewrite after it would
			// meet entity indexes no longer stored, and its final flush - the one the collection is published by -
			// writes the rewritten parts too. The rows it writes do not depend on the order: the registry normalizes
			// every sort value it receives
			final int rewrittenSortIndexes = rewriteLegacyOwnerSortIndexes(catalogVersion, collection);
			// whether the steps running before the conversion left anything unflushed in the data store - each such
			// step adds its own term here, the publication below does not need to know which of them wrote; a
			// collection with nothing but sort indexes to rewrite must still publish its new collection header, or
			// protocol 7 would be published over the legacy parts
			final boolean changedBeforeConversion =
				rekeyed || rewrittenSortIndexes > 0 || !clampedFilterIndexes.isEmpty();

			// the parts written so far are still unflushed - the conversion reads them like any other part (the offset
			// index serves a record before it is flushed), removes those of the partitions it converts, and its final
			// flush writes the rest together with its own
			final CollectionConversion conversion = VirtualReferencePartitionConversion.convertCollection(
				catalogVersion, entityCollectionHeader, collectionPersistenceService,
				EntityCollection.bindReflectedReferences(entitySchema, schemaLookup),
				VirtualReferencePartitionConversion.DEFAULT_FLUSH_THRESHOLD
			);

			// the reference published for the collection: the conversion's final flush when it wrote anything,
			// otherwise a flush of its own when an earlier step wrote anything, otherwise the published reference
			if (conversion.changed() || changedBeforeConversion) {
				final FileLocation collectionFileLocation;
				if (conversion.changed()) {
					// the final flush of the conversion wrote the parts of the earlier steps too, with a collection
					// header that no longer lists the converted partitions - flushing again would only write that
					// header once more
					collectionFileLocation = collectionPersistenceService.getEntityCollectionHeader().fileLocation();
				} else {
					collectionFileLocation = collectionPersistenceService.flush(
						catalogVersion,
						new NoChangeHeaderInfoSupplier(entityCollectionHeader)
					).fileLocation();
				}
				final EntityCollectionFileHeader newCollectionHeader =
					collectionPersistenceService.getEntityCollectionHeader();
				storagePartPersistenceService.putStoragePart(catalogVersion, newCollectionHeader);
				newCollectionFileIndex.put(
					entityTypeFileIndex.entityType(),
					new CollectionFileReference(
						entityTypeFileIndex.entityType(),
						entityTypeFileIndex.entityTypePrimaryKey(),
						entityTypeFileIndex.fileIndex(),
						collectionFileLocation
					)
				);
			} else {
				// nothing to do - no step changed anything in this collection: every counter, unique key and owner
				// sort index was already canonical, and it holds no single-owner partition to convert
				newCollectionFileIndex.put(entityTypeFileIndex.entityType(), entityTypeFileIndex);
			}
			if (rekeyed) {
				ConsoleWriter.writeLine(
					"Entity collection `" + entityCollectionHeader.entityType() + "`: re-keyed " + rekeyedCounters +
						" attribute cardinality index(es) and " + uniqueIndexRekeys.size() + " unique index(es).",
					ConsoleColor.BRIGHT_BLUE
				);
			}
			if (!clampedFilterIndexes.isEmpty()) {
				ConsoleWriter.writeLine(
					"Entity collection `" + entityCollectionHeader.entityType() + "`: moved the date-time keys of " +
						clampedFilterIndexes.size() + " filter index(es) that 2026.2 stored beyond the supported " +
						"date-time window to the edge of the window.",
					ConsoleColor.BRIGHT_BLUE
				);
			}
			if (rewrittenSortIndexes > 0) {
				ConsoleWriter.writeLine(
					"Entity collection `" + entityCollectionHeader.entityType() + "`: rewrote " + rewrittenSortIndexes +
						" sort index(es) an older release stored with raw temporal values or in the order of its " +
						"collator, language tags or whole seconds.",
					ConsoleColor.BRIGHT_BLUE
				);
			}
			VirtualReferencePartitionConversion.report(catalogHeader.catalogName(), conversion);

			reportPreExistingDamage(
				catalogHeader.catalogName(), entityCollectionHeader.entityType(),
				damagedAttributes, orphanedPairs, uncheckedAttributes
			);
		}

		// the catalog-level parts are flushed by the post-upgrade action, together with the new catalog header
		for (final GlobalUniqueIndexRekey uniqueIndexRekey : globalUniqueIndexRekeys) {
			writeGlobalUniqueIndex(catalogVersion, uniqueIndexRekey, storagePartPersistenceService, entityTypeResolver);
		}
		if (!globalUniqueIndexRekeys.isEmpty()) {
			ConsoleWriter.writeLine(
				"Catalog `" + catalogHeader.catalogName() + "`: re-keyed " + globalUniqueIndexRekeys.size() +
					" global unique index(es).",
				ConsoleColor.BRIGHT_BLUE
			);
		}

		// the single publication of everything above
		postUpgradeAction.accept(
			new CatalogHeader<>(
				PersistenceService.STORAGE_PROTOCOL_VERSION,
				catalogVersion,
				catalogHeader.walFileReference(),
				newCollectionFileIndex,
				catalogHeader.compressedKeys(),
				catalogHeader.catalogId(),
				catalogHeader.catalogName(),
				catalogHeader.catalogState(),
				catalogHeader.lastEntityCollectionPrimaryKey(),
				catalogHeader.activeRecordShare()
			)
		);

		ConsoleWriter.writeLine(
			"Catalog `" + catalogHeader.catalogName() + "` was successfully upgraded to the protocol version " +
				PersistenceService.STORAGE_PROTOCOL_VERSION + ".",
			ConsoleColor.BRIGHT_BLUE, ConsoleDecoration.BOLD
		);
	}

	/**
	 * Re-keys one persisted cardinality index, summing the counts of keys that collapse onto a single key.
	 *
	 * Summing is what makes the result correct rather than merely canonical: two owners that shared one tree entry
	 * held one counter key each, and the fixed engine would have held a single key counting two. Overwriting
	 * instead of summing would leave a count of one and drop the shared entry on the first departure — the very
	 * defect being repaired.
	 *
	 * Every counter is additionally audited against the entries it guards — see {@link #countOrphanedCounters} —
	 * so damage that predates the upgrade is reported rather than left to surface later as a bare index premise
	 * failure. The audit runs on the canonical form, which is the state the repaired counter is actually in.
	 *
	 * @return what happened to this part: whether it was rewritten, and how many of its counters point at index
	 * entries that are no longer there
	 */
	@Nonnull
	private static CounterOutcome rekeyCardinalityIndex(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nonnull OffsetIndexStoragePartPersistenceService collectionStoragePartService,
		@Nonnull EntitySchema entitySchema,
		@Nullable String referenceName
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			indexPrimaryKey,
			AttributeIndexType.CARDINALITY,
			attributeIndexKey.attribute(),
			collectionStoragePartService.getReadOnlyKeyCompressor()
		);
		final AttributeCardinalityIndexStoragePart part = collectionStoragePartService.getStoragePart(
			catalogVersion, primaryKey, AttributeCardinalityIndexStoragePart.class
		);
		if (part == null) {
			throw new GenericEvitaInternalError(
				"Cardinality index with id " + indexPrimaryKey + " with key " + attributeIndexKey.attribute() +
					" was not found in persistent storage!"
			);
		}

		final AttributeCardinalityIndex cardinalityIndex = part.getCardinalityIndex();
		final Class<? extends Serializable> valueType = cardinalityIndex.getValueType();
		// the counter is always constructed with the attribute's PLAIN type (array attributes are counted
		// element-wise), but unwrap defensively so an array-typed part cannot reach `getNormalizer` and throw
		final Class<?> plainType = valueType.isArray() ? valueType.getComponentType() : valueType;
		// read once and use twice: the sibling filter index is both the authority on the scale and the oracle the
		// audit compares the counters against
		final FilterIndexStoragePart filterPart = readSiblingFilterIndex(
			catalogVersion, indexPrimaryKey, attributeIndexKey, collectionStoragePartService
		);
		final OptionalInt indexedDecimalPlaces = resolveScale(
			entitySchema, referenceName, attributeIndexKey, filterPart, plainType
		);
		if (indexedDecimalPlaces.isEmpty()) {
			// a scale-dependent counter whose scale cannot be established - leave it exactly as it is rather than
			// re-key it at a guessed scale, which would silently move every key to the wrong bucket
			ConsoleWriter.writeLine(
				"Cardinality index for attribute `" + attributeIndexKey.attribute().attributeName() +
					"` of entity index " + indexPrimaryKey + " could not be re-keyed: its `indexedDecimalPlaces` is " +
					"not resolvable from either the sibling filter index or the entity schema. The counter keeps its " +
					"existing keys; re-index this attribute if its writes later fail an index premise.",
				ConsoleColor.BRIGHT_YELLOW
			);
			// its keys cannot be canonicalized, so they cannot be compared with the tree's either
			return new CounterOutcome(false, NOT_VERIFIABLE, "scale unresolved");
		}
		// the clamp runs on both sides of the audit as well: a counter and the bucket it names must agree on the key
		// of a value 2026.2 stored beyond the window of moments this version can hold
		final Function<Object, Serializable> normalizer = LegacyOwnerSortIndexCorrection.createClampingNormalizer(
			plainType, indexedDecimalPlaces.getAsInt()
		);

		final Map<AttributeCardinalityKey, Integer> rekeyed = rekeyCardinalities(
			cardinalityIndex.getCardinalities(), normalizer
		);
		final int orphanedPairs = countOrphanedCounters(
			rekeyed == null ? cardinalityIndex.getCardinalities() : rekeyed, filterPart, plainType, normalizer
		);
		if (rekeyed == null) {
			return new CounterOutcome(false, orphanedPairs, whyNotChecked(plainType, filterPart));
		}

		collectionStoragePartService.putStoragePart(
			catalogVersion,
			new AttributeCardinalityIndexStoragePart(
				part.getEntityIndexPrimaryKey(),
				part.getAttributeIndexKey(),
				new AttributeCardinalityIndex(valueType, rekeyed),
				part.getStoragePartPK()
			)
		);
		return new CounterOutcome(true, orphanedPairs, whyNotChecked(plainType, filterPart));
	}

	/**
	 * Establishes the scale to canonicalize this counter's keys at.
	 *
	 * # Why this is not simply a schema lookup
	 *
	 * It was, and the backward-compatibility fixtures rejected it: a `CARDINALITY` part can exist for an attribute
	 * the owning entity schema cannot answer for — an inherited attribute of a reflected reference, or one the
	 * schema no longer declares — and a schema-only lookup throws there, failing the whole catalog upgrade and
	 * leaving it CORRUPTED. A migration must degrade on an attribute it cannot price, never abort the catalog.
	 *
	 * # The order, and why
	 *
	 * 1. **Types with no scale** — only `BigDecimal` and `BigDecimalNumberRange` read `indexedDecimalPlaces` at all
	 *    ({@link FilterIndex#getNormalizer(Class, int)} ignores it everywhere else), so every other type answers
	 *    `0` immediately and never needs a schema or a sibling part. This alone covers the String/NFD and temporal
	 *    counters, which is most of what fails a schema lookup.
	 * 2. **The sibling FILTER part's scale** — authoritative because it is the scale the LOAD path will build that
	 *    tree's normalizer at: `AttributeIndexLoader` reads `getIndexedDecimalPlaces()` off the part verbatim and
	 *    never re-derives it from the schema. The counter's whole purpose is to agree with the tree beside it, so
	 *    it must be canonicalized at whatever scale that tree ends up using — which also means it survives a schema
	 *    that has since changed.
	 *
	 *    Read this as "the scale the part reports", NOT as "the scale the keys were written at": for a part still
	 *    in a pre-freeze layout the two differ. `FilterIndexStoragePartSerializer_2025_5` and `…_2026_1` read
	 *    formats that persisted no scale at all and hand back a part defaulted to `0`. That is harmless here
	 *    precisely BECAUSE the preference is defined against the loader rather than against the bytes — the loader
	 *    reads the same `0` and builds the same normalizer, so counter and tree still agree. (Such a blob can only
	 *    be a non-`BigDecimal` part — `Migration_2026_2` re-keys and rewrites every `BigDecimal` filter part with
	 *    the current serializer — but it CAN be a `BigDecimalNumberRange` one, which that migration leaves alone.)
	 * 3. **The entity schema** — the fallback for a counter with no surviving sibling filter part.
	 * 4. **Empty** — caller leaves the part untouched and says so.
	 *
	 * Visible to tests, which pin that order: a sibling scale that DISAGREES with the schema must win, and the
	 * inverse preference is not detectable from a catalog whose schema never changed.
	 *
	 * @param filterPart the sibling filter index, already read by {@link #readSiblingFilterIndex}, or `null` when
	 *                   this counter has none
	 * @return the scale to normalize at, or empty when it cannot be established for a type that needs one
	 */
	@Nonnull
	static OptionalInt resolveScale(
		@Nonnull EntitySchema entitySchema,
		@Nullable String referenceName,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nullable FilterIndexStoragePart filterPart,
		@Nonnull Class<?> plainType
	) {
		if (!BigDecimal.class.isAssignableFrom(plainType) && !BigDecimalNumberRange.class.isAssignableFrom(plainType)) {
			return OptionalInt.of(0);
		}
		if (filterPart != null) {
			return OptionalInt.of(filterPart.getIndexedDecimalPlaces());
		}
		// `Migration_2026_2` is deprecated ahead of this class: when it is removed, move `resolveIndexedDecimalPlaces`
		// here rather than deleting it - `resolveScale` and `resolveOwnerScale` both depend on it
		try {
			return OptionalInt.of(
				Migration_2026_2.resolveIndexedDecimalPlaces(
					entitySchema, referenceName, attributeIndexKey.attribute().attributeName()
				)
			);
		} catch (GenericEvitaInternalError ex) {
			// the attribute is not answerable from this schema (a reflected reference's inherited attribute, or one
			// the schema has dropped while its index lingers) - report it upwards rather than killing the upgrade
			return OptionalInt.empty();
		}
	}

	/**
	 * Maps every counter key through `normalizer` and SUMS the counts of keys that collapse onto one key.
	 *
	 * Summing is what makes the result correct rather than merely canonical. Two owners that shared one tree entry
	 * held one counter key each; the fixed engine would have held a single key counting two. Overwriting instead of
	 * summing would leave a count of one, and the entry would then be dropped on the first owner's departure —
	 * recreating the very defect being repaired.
	 *
	 * Extracted from the storage plumbing so it can be tested as the pure transform it is.
	 *
	 * @param cardinalities the persisted counter map, keyed on raw values
	 * @param normalizer    the canonicalizer for this attribute's type and scale
	 * @return the re-keyed map, or `null` when every key was already canonical and nothing needs rewriting
	 */
	@Nullable
	static Map<AttributeCardinalityKey, Integer> rekeyCardinalities(
		@Nonnull Map<AttributeCardinalityKey, Integer> cardinalities,
		@Nonnull Function<Object, Serializable> normalizer
	) {
		final Map<AttributeCardinalityKey, Integer> rekeyed = CollectionUtils.createHashMap(cardinalities.size());
		boolean changed = false;
		for (final Entry<AttributeCardinalityKey, Integer> entry : cardinalities.entrySet()) {
			final Serializable rawValue = entry.getKey().value();
			final Serializable normalizedValue = normalizer.apply(rawValue);
			if (!normalizedValue.equals(rawValue)) {
				changed = true;
			}
			rekeyed.merge(
				new AttributeCardinalityKey(entry.getKey().recordId(), normalizedValue),
				entry.getValue(),
				Integer::sum
			);
		}
		return changed ? rekeyed : null;
	}


	/**
	 * Reads the FILTER sibling of a cardinality counter — the shared value tree whose entries the counter
	 * ref-counts, and the only thing in storage that can say whether those entries still exist.
	 *
	 * The compressed part id is minted per (attribute key, index TYPE) pair, so an attribute that never had a
	 * filter index has no id for one at all, and asking a read-only key compressor for a missing id throws rather
	 * than minting one. `getIdIfExists` is therefore the only safe way to ask: calling
	 * {@link AttributeIndexStoragePart#computeUniquePartId} directly — as an earlier shape of {@link #resolveScale}
	 * did — would fail the whole catalog upgrade on such an attribute, which is exactly the outcome a migration
	 * must never produce over one unreadable part.
	 *
	 * @return the sibling filter index, or `null` when this counter has none
	 */
	@Nullable
	private static FilterIndexStoragePart readSiblingFilterIndex(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nonnull OffsetIndexStoragePartPersistenceService collectionStoragePartService
	) {
		final OptionalInt filterKeyId = collectionStoragePartService.getReadOnlyKeyCompressor()
			.getIdIfExists(new AttributeKeyWithIndexType(attributeIndexKey.attribute(), AttributeIndexType.FILTER));
		return filterKeyId.isPresent() ?
			collectionStoragePartService.getStoragePart(
				catalogVersion,
				NumberUtils.pack(indexPrimaryKey, filterKeyId.getAsInt()),
				FilterIndexStoragePart.class
			) :
			null;
	}

	/**
	 * Counts the canonical counters that name an index entry the shared value tree no longer holds — damage that
	 * predates this upgrade and that re-keying cannot undo.
	 *
	 * A counter key is `(recordId, value)` and both halves come from the same call as the tree's own entry
	 * (`ReferencedTypeEntityIndex#insertFilterAttribute` hands the identical `recordId` to
	 * {@link AttributeCardinalityIndex#addRecord} and to the filter index), so the two are directly comparable:
	 * every counter must have its record in the bucket it names, and one that does not is an entry that was
	 * removed while an owner still needed it.
	 *
	 * # What it deliberately does not look at, and why that is not laziness
	 *
	 * **A type that cannot collide is not checked at all** ({@link #isCollisionProne}): its normalizer is the
	 * identity or a bijection in the version that wrote this catalog, so it cannot hold two counter keys over one
	 * tree entry and there is nothing for the audit to find.
	 *
 * **Both sides are normalized, and that is load-bearing rather than tidy.** A stored bucket is canonical only
	 * under the normalizer of the version that WROTE it, which is not today's. The pointed case is
	 * `BigDecimalNumberRange`: `Migration_2026_2#rekeyFilterIndex` re-keys `String` and `BigDecimal` filter parts
	 * and returns `false` for everything else, and `getNormalizer` had no range branch at all before 2026.2 — so a
	 * catalog that reached protocol 6 by upgrade from 2026.1 still holds RAW, un-rescaled ranges, while
	 * `NumberRange` equality is defined on the SCALED thresholds. Comparing a rescaled counter key against
	 * them misses on every entry and reports an undamaged collection as damaged. Temporal values have the same
	 * shape of hazard from the other direction (a stored `Instant` is truncated to milliseconds only at LOAD, which
	 * reading storage parts directly bypasses).
	 *
	 * Putting the stored value through the very same normalizer removes the whole class of problem: the normalizer
	 * is idempotent, so a bucket that is already canonical passes through untouched, and one written under an older
	 * rule lands in exactly the key space the counter is now in. Two buckets that collapse onto one key must have
	 * their records UNIONED — overwriting would drop the records of the losing bucket and reintroduce the false
	 * alarm from the other side — which is also precisely what the load path does when it merges them.
	 *
	 * **A PAGED filter index is not checked** but is reported as such. Its buckets live in separate
	 * `FilterIndexLeafPagePart` records rather than in {@link FilterIndexStoragePart#getHistogramPoints()}, so
	 * answering the same question there is a separate piece of work. Note that {@link Migration_2026_2}'s silence
	 * about paging is NOT a precedent for silence here: paging does not exist in 2026.1, which wrote every
	 * protocol-5 catalog, but it does in 2026.2, which wrote every protocol-6 one.
	 *
	 * Visible to tests rather than private: the normalize-both-sides rule above is the one part of this migration
	 * the backward-compatibility fixtures cannot exercise, because no downloaded catalog holds a colliding pair.
	 * `Migration_2026_3_AuditTest` drives it directly instead.
	 *
	 * @param canonicalCounters the counter map AFTER re-keying — the state the repaired counter is actually in
	 * @param filterPart        the sibling value tree, or `null` when the counter has none
	 * @param plainType         the attribute's plain (non-array) type
	 * @param normalizer        the canonicalizer for this attribute, applied to the STORED bucket values too
	 * @return the number of `(record, value)` pairs whose entry is missing, or {@link #NOT_VERIFIABLE}
	 */
	static int countOrphanedCounters(
		@Nonnull Map<AttributeCardinalityKey, Integer> canonicalCounters,
		@Nullable FilterIndexStoragePart filterPart,
		@Nonnull Class<?> plainType,
		@Nonnull Function<Object, Serializable> normalizer
	) {
		if (!isCollisionProne(plainType)) {
			return 0;
		}
		if (filterPart == null || filterPart.isPaged()) {
			return NOT_VERIFIABLE;
		}
		final ValueToRecordBitmap[] points = filterPart.getHistogramPoints();
		final Map<Serializable, Bitmap> buckets = CollectionUtils.createHashMap(points.length);
		for (final ValueToRecordBitmap point : points) {
			buckets.merge(
				normalizer.apply(point.getValue()), point.getRecordIds(), Migration_2026_3::union
			);
		}
		int orphaned = 0;
		for (final AttributeCardinalityKey key : canonicalCounters.keySet()) {
			final Bitmap bucket = buckets.get(key.value());
			if (bucket == null || !bucket.contains(key.recordId())) {
				orphaned++;
			}
		}
		return orphaned;
	}

	/**
	 * Whether two distinct values of `plainType` could share one index key in a catalog this migration can meet —
	 * the precondition for the defect, and therefore for the audit having anything to find.
	 *
	 * Judged against the normalizer **2026.2** shipped, since that version wrote every protocol-6 catalog, not
	 * against today's. Four branches were many-to-one there: `String` folds to Unicode NFD, `BigDecimal` and
	 * {@link BigDecimalNumberRange} fold to an order-preserving scaled `int`, and `OffsetDateTime` folds through
	 * `toInstant()`, which discards the offset so that two spellings of one instant share a key. The rest are the
	 * identity or a bijection: `LocalDateTime` anchors at a CONSTANT offset, `Currency` and `Locale` wrap without
	 * merging, `LocalTime` had no branch of its own at all, and every remaining type passes through untouched.
	 *
	 * A type this admits is always comparable, because {@link #countOrphanedCounters} puts the stored bucket
	 * through the same normalizer as the counter rather than trusting it to already be canonical.
	 *
	 * Verified against the released sources rather than the changelog
	 * (`git show v2026.2.7:…/FilterIndex.java`): the millisecond truncation that makes the temporal branch
	 * many-to-one a SECOND way is absent from v2026.2.5, .6 and .7, but the offset collapse is present in all
	 * three — the two are separate mechanisms and only the first is unreleased.
	 */
	private static boolean isCollisionProne(@Nonnull Class<?> plainType) {
		return String.class.isAssignableFrom(plainType)
			|| BigDecimal.class.isAssignableFrom(plainType)
			|| BigDecimalNumberRange.class.isAssignableFrom(plainType)
			|| OffsetDateTime.class.isAssignableFrom(plainType);
	}

	/**
	 * Names, for the operator, the one reason this counter was not compared with the entries it guards — or `null`
	 * when it was. The two causes are materially different — an index shape the audit cannot walk, and an index
	 * that is not there at all — and a message listing both every time tells the reader nothing about the case in
	 * front of them.
	 *
	 * Visible to tests, which pin it against {@link #countOrphanedCounters}: a {@link #NOT_VERIFIABLE} count must
	 * always carry a reason, and a counter that WAS checked must never carry one — otherwise the report either
	 * names a cause it did not have or leaves the operator with an unexplained gap.
	 */
	@Nullable
	static String whyNotChecked(@Nonnull Class<?> plainType, @Nullable FilterIndexStoragePart filterPart) {
		if (!isCollisionProne(plainType)) {
			return null;
		} else if (filterPart == null) {
			return "no sibling filter index";
		} else if (filterPart.isPaged()) {
			return "filter index is stored in paged form";
		} else {
			return null;
		}
	}

	/**
	 * Unions the records of two stored buckets that normalize onto one key.
	 *
	 * Reached only when the part on disk was written under an older normalizer that kept them apart. Dropping
	 * either side would make the records of the losing bucket look orphaned, which is the false alarm
	 * {@link #countOrphanedCounters} exists to avoid; the load path merges the same pair the same way.
	 */
	@Nonnull
	private static Bitmap union(@Nonnull Bitmap left, @Nonnull Bitmap right) {
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (final Bitmap side : new Bitmap[]{left, right}) {
			final OfInt recordIterator = side.iterator();
			while (recordIterator.hasNext()) {
				writer.add(recordIterator.nextInt());
			}
		}
		return new BaseBitmap(writer.get());
	}

	/**
	 * Tells the operator what the upgrade found but could not fix, in the one place they are guaranteed to be
	 * looking: the boot that performed the migration.
	 *
	 * Damage is an ERROR carrying the remedy, because the alternative is that it surfaces weeks later as a bare
	 * `Sanity check - record not found!` on an unrelated write, reading as a regression in the new release.
	 * Counters that could not be checked are a WARNING that says so explicitly — a diagnostic that reports clean
	 * because it never looked would talk the operator out of investigating.
	 */
	private static void reportPreExistingDamage(
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull List<String> damagedAttributes,
		int orphanedPairs,
		@Nonnull List<String> uncheckedAttributes
	) {
		if (!damagedAttributes.isEmpty()) {
			final String message = "Catalog `" + catalogName + "`, entity collection `" + entityType + "`: " +
				damagedAttributes.size() + " attribute index(es) count " + orphanedPairs + " index entry(ies) that " +
				"are no longer present. Those entries were dropped before this upgrade by the defect it repairs " +
				"(evitaDB #1620), and the counters cannot reconstruct them. The stored entity data is intact - only " +
				"the search index is incomplete, so the affected records are missing from filtering results for " +
				"these attributes, and removing one of them can fail an index premise. REINDEX entity collection `" +
				entityType + "` of catalog `" + catalogName + "` to rebuild them. Affected attributes: " +
				formatAttributeList(damagedAttributes) + ".";
			ConsoleWriter.writeLine(message, ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD);
			LoggerFactory.getLogger(Migration_2026_3.class).error(message);
		}
		if (!uncheckedAttributes.isEmpty()) {
			final String message = "Catalog `" + catalogName + "`, entity collection `" + entityType + "`: " +
				uncheckedAttributes.size() + " attribute cardinality counter(s) could NOT be checked against the " +
				"index entries they count. They may or may not reference entries that are no longer present; the " +
				"absence of an error for them means only that nothing looked. Not checked, with the reason each: " +
				formatAttributeList(uncheckedAttributes) + ".";
			ConsoleWriter.writeLine(message, ConsoleColor.BRIGHT_YELLOW);
			LoggerFactory.getLogger(Migration_2026_3.class).warn(message);
		}
	}

	/**
	 * Joins attribute descriptions into one message fragment, capped at {@link #MAX_REPORTED_ATTRIBUTES} so the
	 * remedy at the end of the message cannot be pushed off the operator's screen by a long list.
	 *
	 * Visible to tests along with {@link #describeCounter}: the diagnostic message IS this migration's deliverable
	 * — it repairs the counters but reports the damage — so the shape an operator reads is pinned like any other
	 * output.
	 */
	@Nonnull
	static String formatAttributeList(@Nonnull List<String> attributes) {
		return attributes.size() <= MAX_REPORTED_ATTRIBUTES ?
			String.join(", ", attributes) :
			String.join(", ", attributes.subList(0, MAX_REPORTED_ATTRIBUTES)) +
				" and " + (attributes.size() - MAX_REPORTED_ATTRIBUTES) + " more";
	}

	/**
	 * Names one cardinality counter the way an operator can act on it — by the attribute it counts and the entity
	 * index that holds it, rather than by the compressed storage part id they have no way to resolve.
	 */
	@Nonnull
	static String describeCounter(int indexPrimaryKey, @Nonnull AttributeIndexStorageKey attributeIndexKey) {
		final AttributeIndexKey attribute = attributeIndexKey.attribute();
		return (attribute.referenceName() == null ? "" : attribute.referenceName() + ".") +
			attribute.attributeName() +
			(attribute.locale() == null ? "" : " [" + attribute.locale().toLanguageTag() + "]") +
			" in entity index " + indexPrimaryKey;
	}

	/**
	 * What happened to one persisted cardinality counter.
	 *
	 * @param rewritten     `true` when the part was re-keyed and written back; `false` when its keys were already
	 *                      canonical, or its scale could not be established
	 * @param orphanedPairs how many of its canonical counters name an index entry the shared value tree no longer
	 *                      holds, or {@link #NOT_VERIFIABLE} when that comparison could not be made at all
	 * @param notCheckedReason why the comparison could not be made, or `null` when it was made
	 */
	record CounterOutcome(boolean rewritten, int orphanedPairs, @Nullable String notCheckedReason) {
	}

	/**
	 * Maps every persisted value of one standalone unique index onto its canonical form - the value of the declared type
	 * naming the key {@link FilterIndex#getNormalizer(Class, int)} gives it, which is what the engine now persists
	 * (see {@link UniqueIndex#toPersistedValue}). Canonical values are equal exactly when they name one key.
	 *
	 * Two stored values that name one key become one value. That is harmless only when both are provably
	 * the same entry written twice: an array attribute holding both spellings for the same owner, which the engine
	 * folds onto one entry as well. Anything else is a **collision** - two owners claiming one value, or one owner
	 * claiming it under two locales of a locale-less key - and no unique index can hold it.
	 *
	 * Whether an equal owner proves "the same entry" depends on what the owner encodes, hence
	 * `ownerIdentifiesEntry`: a global unique index packs entity type, primary key AND locale into its payload, so an
	 * equal payload is the same entry; an owner unique index stores the record id alone, so an equal record id may
	 * still be two locales of a localized attribute, and is reported rather than folded.
	 *
	 * Extracted from the storage plumbing so it can be tested as the pure transform it is.
	 *
	 * @param values               the stored keys, in any order
	 * @param owners               the owner of each key, positionally aligned with `values` - a record id or a packed
	 *                             entity tuple
	 * @param ownerIdentifiesEntry `true` when an equal owner proves two keys are the same entry
	 * @param canonicalizer        maps a stored value onto its canonical form (see {@link #canonicalizerFor})
	 * @return `null` when every key is already canonical and the index needs no rewrite, otherwise the canonical keys
	 * with their owners and every colliding pair of positions in the input
	 */
	@Nullable
	static UniqueKeyRekeying rekeyUniqueKeys(
		@Nonnull Serializable[] values,
		@Nonnull long[] owners,
		boolean ownerIdentifiesEntry,
		@Nonnull Function<Serializable, Serializable> canonicalizer
	) {
		Assert.isPremiseValid(values.length == owners.length, "Values and owners must be positionally aligned!");
		final Map<Serializable, Integer> firstPositions = CollectionUtils.createHashMap(values.length);
		final List<Serializable> canonicalValues = new ArrayList<>(values.length);
		final CompositeLongArray canonicalOwners = new CompositeLongArray();
		final List<int[]> collisions = new ArrayList<>(0);
		boolean changed = false;
		for (int i = 0; i < values.length; i++) {
			final Serializable canonicalValue = canonicalizer.apply(values[i]);
			if (!canonicalValue.equals(values[i])) {
				changed = true;
			}
			final Integer firstPosition = firstPositions.putIfAbsent(canonicalValue, i);
			if (firstPosition == null) {
				canonicalValues.add(canonicalValue);
				canonicalOwners.add(owners[i]);
			} else if (!(ownerIdentifiesEntry && owners[firstPosition] == owners[i])) {
				collisions.add(new int[]{firstPosition, i});
			}
		}
		// keys that were all canonical already were distinct tree keys, so they can neither move nor collide
		return changed ?
			new UniqueKeyRekeying(
				canonicalValues.toArray(Serializable[]::new), canonicalOwners.toArray(), collisions
			) :
			null;
	}

	/**
	 * Returns the function mapping a persisted unique value of `plainType` onto its canonical form at
	 * `indexedDecimalPlaces` - see {@link UniqueIndex#toPersistedValue}. A temporal value beyond the window of moments
	 * this version can hold is first clamped to its edge, as the engine clamps it on the way in; two owners holding
	 * different such values then collide like any other pair of values that name one key.
	 *
	 * @param plainType            the plain (array-unwrapped) attribute type
	 * @param indexedDecimalPlaces the attribute schema's `indexedDecimalPlaces`
	 * @return the canonicalizer
	 */
	@Nonnull
	static Function<Serializable, Serializable> canonicalizerFor(@Nonnull Class<?> plainType, int indexedDecimalPlaces) {
		return value -> UniqueIndex.toPersistedValue(plainType, indexedDecimalPlaces, clampStoredValue(value));
	}

	/**
	 * Tells whether the canonical form of a unique value of `plainType` depends on `indexedDecimalPlaces` at all -
	 * only `BigDecimal` and `BigDecimalNumberRange` do (see {@link #resolveScale}).
	 */
	private static boolean isScaleDependent(@Nonnull Class<?> plainType) {
		return BigDecimal.class.isAssignableFrom(plainType) || BigDecimalNumberRange.class.isAssignableFrom(plainType);
	}

	/**
	 * Establishes the scale a global unique index of `attributeName` keys its values at: the `indexedDecimalPlaces`
	 * of the catalog attribute, read from the first entity schema that declares it - an entity schema carries a copy of
	 * every catalog attribute it uses, and reading the catalog schema itself would need a live catalog. Types without
	 * a scale answer `0` immediately.
	 *
	 * @return the scale, or empty when no collection declares a scale-dependent attribute the index exists for
	 */
	@Nonnull
	private static OptionalInt resolveGlobalScale(
		@Nonnull List<CollectionUnderUpgrade> collections,
		@Nonnull String attributeName,
		@Nonnull Class<?> plainType
	) {
		if (!isScaleDependent(plainType)) {
			return OptionalInt.of(0);
		}
		for (final CollectionUnderUpgrade collection : collections) {
			final Optional<EntityAttributeSchemaContract> attribute = collection.entitySchema().getAttribute(attributeName);
			if (attribute.isPresent()) {
				return OptionalInt.of(attribute.get().getIndexedDecimalPlaces());
			}
		}
		return OptionalInt.empty();
	}

	/**
	 * Establishes the scale an owner unique index of `attributeName` keys its values at - the attribute's
	 * `indexedDecimalPlaces` in the entity schema, at reference scope first when the owning index is a reference
	 * index. Types without a scale answer `0` immediately.
	 *
	 * @return the scale, or empty when the schema cannot answer for a scale-dependent attribute
	 */
	@Nonnull
	private static OptionalInt resolveOwnerScale(
		@Nonnull EntitySchema entitySchema,
		@Nullable String referenceName,
		@Nonnull String attributeName,
		@Nonnull Class<?> plainType
	) {
		if (!isScaleDependent(plainType)) {
			return OptionalInt.of(0);
		}
		// `Migration_2026_2` is deprecated ahead of this class: when it is removed, move `resolveIndexedDecimalPlaces`
		// here rather than deleting it - `resolveScale` and `resolveOwnerScale` both depend on it
		try {
			return OptionalInt.of(Migration_2026_2.resolveIndexedDecimalPlaces(entitySchema, referenceName, attributeName));
		} catch (GenericEvitaInternalError ex) {
			return OptionalInt.empty();
		}
	}

	/**
	 * Tells the operator that a unique index could not be re-keyed because its scale is unknown, and leaves it as is.
	 */
	private static void warnUnresolvedUniqueScale(@Nonnull String structure) {
		final String message = "The unique index of " + structure + " could not be re-keyed: its " +
			"`indexedDecimalPlaces` is not resolvable from any entity schema. It keeps its existing keys; re-index " +
			"the attribute if its writes or lookups later misbehave.";
		ConsoleWriter.writeLine(message, ConsoleColor.BRIGHT_YELLOW);
		LoggerFactory.getLogger(Migration_2026_3.class).warn(message);
	}

	/**
	 * Plans the re-key of every catalog-level {@link GlobalUniqueIndexStoragePart} - the trees of `uniqueGlobally` and
	 * `uniqueGloballyWithinLocale` attributes, in every scope - whose values are not all canonical, in either the
	 * inline or the paged shape. Nothing is written here; colliding keys are appended to `collisions`.
	 *
	 * @param catalogVersion     the catalog version being upgraded
	 * @param service            the catalog-level storage part service
	 * @param entityTypeResolver names the owners of colliding keys
	 * @param collections        the collections of the catalog, whose schemas supply the attribute scales
	 * @param collisions         receives every collision found
	 * @return the indexes that must be rewritten
	 */
	@Nonnull
	private static List<GlobalUniqueIndexRekey> planGlobalUniqueIndexRekeys(
		long catalogVersion,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService service,
		@Nonnull EntityTypeClassifierResolver entityTypeResolver,
		@Nonnull List<CollectionUnderUpgrade> collections,
		@Nonnull List<UniqueKeyCollision> collisions
	) {
		final List<GlobalUniqueIndexRekey> rekeys = new ArrayList<>(4);
		for (final Scope scope : Scope.values()) {
			final CatalogIndexStoragePart catalogIndexPart = service.getStoragePart(
				catalogVersion, CatalogIndexStoragePart.getStoragePartPKForScope(scope), CatalogIndexStoragePart.class
			);
			if (catalogIndexPart == null) {
				// no entity was ever indexed in this scope
				continue;
			}
			for (final AttributeKey attributeKey : catalogIndexPart.getSharedAttributeUniqueIndexes()) {
				final GlobalUniqueIndexStoragePart root = Objects.requireNonNull(
					service.getStoragePart(
						catalogVersion,
						GlobalUniqueIndexStoragePart.computeUniquePartId(
							scope, attributeKey, service.getReadOnlyKeyCompressor()
						),
						GlobalUniqueIndexStoragePart.class
					),
					"Shared unique index not found for attribute `" + attributeKey + "`!"
				);
				final Class<?> plainType = plainTypeOf(root.getType());
				final Locale locale = attributeKey.locale();
				final String structure = "catalog attribute `" + attributeKey.attributeName() + "`" +
					(locale == null ? "" : " in locale `" + locale.toLanguageTag() + "`") + " (scope " + scope + ")";
				final OptionalInt scale = resolveGlobalScale(collections, attributeKey.attributeName(), plainType);
				if (scale.isEmpty()) {
					warnUnresolvedUniqueScale(structure);
					continue;
				}
				final Serializable[] values;
				final long[] payloads;
				if (root.isPaged()) {
					final int streamId = service.getReadOnlyKeyCompressor().getId(
						new GlobalUniqueLeafStreamKey(scope, attributeKey)
					);
					final CompositeObjectArray<Serializable> pagedValues =
						new CompositeObjectArray<>(Serializable.class);
					final CompositeLongArray pagedPayloads = new CompositeLongArray();
					for (final int pageSequence : root.getLeafPageSequences()) {
						final GlobalUniqueIndexLeafPagePart leafPage = Objects.requireNonNull(
							service.getStoragePart(
								catalogVersion,
								GlobalUniqueIndexLeafPagePart.computeUniquePartId(streamId, pageSequence),
								GlobalUniqueIndexLeafPagePart.class
							),
							"Global unique index leaf page " + pageSequence + " for attribute `" + attributeKey +
								"` was not found in persistent storage!"
						);
						pagedValues.addAll(leafPage.getValues(), 0, leafPage.getValues().length);
						pagedPayloads.addAll(leafPage.getPayloads(), 0, leafPage.getPayloads().length);
					}
					values = pagedValues.toArray();
					payloads = pagedPayloads.toArray();
				} else {
					values = Objects.requireNonNull(root.getValues());
					payloads = Objects.requireNonNull(root.getPayloads());
				}

				final UniqueKeyRekeying rekeying = rekeyUniqueKeys(
					values, payloads, true, canonicalizerFor(plainType, scale.getAsInt())
				);
				if (rekeying == null) {
					continue;
				}
				for (final int[] collision : rekeying.collisions()) {
					collisions.add(
						new UniqueKeyCollision(
							structure,
							values[collision[0]],
							describeGlobalOwner(
								scope, attributeKey, root, scale.getAsInt(), values[collision[0]],
								payloads[collision[0]], entityTypeResolver
							),
							values[collision[1]],
							describeGlobalOwner(
								scope, attributeKey, root, scale.getAsInt(), values[collision[1]],
								payloads[collision[1]], entityTypeResolver
							)
						)
					);
				}
				rekeys.add(
					new GlobalUniqueIndexRekey(
						scope, attributeKey, root.getType(), scale.getAsInt(), root.getLocaleIndex(),
						rekeying.values(), rekeying.owners(), root.isPaged() ? root.getLeafPageSequences() : new int[0]
					)
				);
			}
		}
		return rekeys;
	}

	/**
	 * Plans the re-key of every standalone (owner) {@link UniqueIndexStoragePart} of one collection - a localized
	 * attribute unique across locales, in any entity index - whose values are not all canonical, in either the inline
	 * or the paged shape. A unique part with a FILTER sibling under the same key is a view folded onto the shared
	 * filter tree, holding no values of its own, and is skipped: the filter-tree pass ({@link #planFilterIndexClamps},
	 * through {@link FilterKeyCollisionDetector}) checks the owners of such a tree of an `OffsetDateTime`,
	 * `LocalDateTime` or `LocalTime` attribute, whose keys this version clamps or truncates. Nothing is written here;
	 * colliding keys are appended to `collisions`.
	 *
	 * @param catalogVersion the catalog version being upgraded
	 * @param collection     the collection whose entity indexes are inspected
	 * @param collisions     receives every collision found
	 * @return the indexes that must be rewritten
	 */
	@Nonnull
	private static List<OwnerUniqueIndexRekey> planOwnerUniqueIndexRekeys(
		long catalogVersion,
		@Nonnull CollectionUnderUpgrade collection,
		@Nonnull List<UniqueKeyCollision> collisions
	) {
		final OffsetIndexStoragePartPersistenceService service =
			collection.service().getStoragePartPersistenceService();
		final String entityType = collection.header().entityType();
		final List<OwnerUniqueIndexRekey> rekeys = new ArrayList<>(4);
		for (final Integer indexPrimaryKey : collection.indexIds()) {
			final EntityIndexStoragePart indexPart = service.getStoragePart(
				catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
			);
			if (indexPart == null) {
				// reported by the rewrite pass, which cannot proceed without it either
				continue;
			}
			final Set<AttributeIndexKey> foldedKeys = filterIndexKeys(indexPart);
			for (final AttributeIndexStorageKey storageKey : indexPart.getAttributeIndexes()) {
				if (
					storageKey.indexType() != AttributeIndexType.UNIQUE ||
						foldedKeys.contains(storageKey.attribute())
				) {
					continue;
				}
				final UniqueIndexStoragePart root = service.getStoragePart(
					catalogVersion,
					AttributeIndexStoragePart.computeUniquePartId(
						indexPrimaryKey, AttributeIndexType.UNIQUE, storageKey.attribute(),
						service.getReadOnlyKeyCompressor()
					),
					UniqueIndexStoragePart.class
				);
				if (root == null) {
					continue;
				}
				final AttributeIndexKey attributeIndexKey = root.getAttributeIndexKey();
				final Class<?> plainType = plainTypeOf(root.getType());
				final String structure = "attribute `" + describeAttribute(attributeIndexKey) + "` of entity `" +
					entityType + "`";
				final OptionalInt scale = resolveOwnerScale(
					collection.entitySchema(), indexPart.getEntityIndexKey().referenceName(),
					attributeIndexKey.attributeName(), plainType
				);
				if (scale.isEmpty()) {
					warnUnresolvedUniqueScale(structure);
					continue;
				}
				final Serializable[] values;
				final int[] recordIds;
				if (root.isPaged()) {
					final int streamId = service.getReadOnlyKeyCompressor().getId(
						new LeafStreamKey(
							indexPrimaryKey, new AttributeKeyWithIndexType(attributeIndexKey, AttributeIndexType.UNIQUE)
						)
					);
					final CompositeObjectArray<Serializable> pagedValues =
						new CompositeObjectArray<>(Serializable.class);
					final CompositeIntArray pagedRecordIds = new CompositeIntArray();
					for (final int pageSequence : root.getLeafPageSequences()) {
						final UniqueIndexLeafPagePart leafPage = Objects.requireNonNull(
							service.getStoragePart(
								catalogVersion,
								AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
								UniqueIndexLeafPagePart.class
							),
							"Unique index leaf page " + pageSequence + " for attribute `" + attributeIndexKey +
								"` of entity `" + entityType + "` was not found in persistent storage!"
						);
						pagedValues.addAll(leafPage.getValues(), 0, leafPage.getValues().length);
						pagedRecordIds.addAll(leafPage.getRecordIds(), 0, leafPage.getRecordIds().length);
					}
					values = pagedValues.toArray();
					recordIds = pagedRecordIds.toArray();
				} else if (root.getValues() == null || root.getRecordIds() == null) {
					// a slim part carries no values of its own - there is nothing to re-key
					continue;
				} else {
					values = root.getValues();
					recordIds = root.getRecordIds();
				}

				final long[] owners = new long[recordIds.length];
				for (int i = 0; i < recordIds.length; i++) {
					owners[i] = recordIds[i];
				}
				final UniqueKeyRekeying rekeying = rekeyUniqueKeys(
					values, owners, false, canonicalizerFor(plainType, scale.getAsInt())
				);
				if (rekeying == null) {
					continue;
				}
				final boolean globalIndex = indexPart.getEntityIndexKey().type() == EntityIndexType.GLOBAL;
				for (final int[] collision : rekeying.collisions()) {
					collisions.add(
						new UniqueKeyCollision(
							structure,
							values[collision[0]],
							describeOwnerRecord(entityType, indexPart, globalIndex, recordIds[collision[0]]),
							values[collision[1]],
							describeOwnerRecord(entityType, indexPart, globalIndex, recordIds[collision[1]])
						)
					);
				}
				final int[] canonicalRecordIds = new int[rekeying.owners().length];
				for (int i = 0; i < canonicalRecordIds.length; i++) {
					canonicalRecordIds[i] = Math.toIntExact(rekeying.owners()[i]);
				}
				rekeys.add(
					new OwnerUniqueIndexRekey(
						indexPrimaryKey, attributeIndexKey, root.getType(), scale.getAsInt(), rekeying.values(),
						canonicalRecordIds,
						root.isPaged() ? root.getLeafPageSequences() : new int[0]
					)
				);
			}
		}
		return rekeys;
	}

	/**
	 * Writes one re-keyed owner unique index back to its collection.
	 *
	 * The index is rebuilt through the engine's own write path - a fresh {@link OwnerUniqueIndex} registering every
	 * canonical key - so it is persisted in whichever shape the engine picks for its size: inline, or a paged root
	 * with one part per leaf. The rebuilt leaves are numbered afresh, so every leaf page of the old tree that no new
	 * leaf overwrites is removed; left behind, it would be an unreferenced record the offset index copies forward
	 * on every compaction.
	 *
	 * @param catalogVersion the catalog version being upgraded
	 * @param entityType     the entity type owning the index
	 * @param rekey          the planned re-key
	 * @param service        the collection's storage part service
	 */
	private static void writeOwnerUniqueIndex(
		long catalogVersion,
		@Nonnull String entityType,
		@Nonnull OwnerUniqueIndexRekey rekey,
		@Nonnull OffsetIndexStoragePartPersistenceService service
	) {
		final OwnerUniqueIndex rebuiltIndex = new OwnerUniqueIndex(
			entityType, rekey.attributeIndexKey(), rekey.type(), rekey.indexedDecimalPlaces()
		);
		for (int i = 0; i < rekey.values().length; i++) {
			rebuiltIndex.registerUniqueKey(rekey.values()[i], rekey.recordIds()[i]);
		}
		final TrappedChanges changes = new TrappedChanges();
		rebuiltIndex.appendStorageParts(rekey.entityIndexPrimaryKey(), changes);
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(rekey.attributeIndexKey(), AttributeIndexType.UNIQUE);
		for (final int pageSequence : staleLeafPageSequences(rekey.previousLeafPageSequences(), changes)) {
			changes.addChangeToStore(
				new UniqueIndexLeafPageRemoval(rekey.entityIndexPrimaryKey(), streamKey, pageSequence)
			);
		}
		drain(catalogVersion, changes, service);
	}

	/**
	 * Writes one re-keyed global unique index back to the catalog.
	 *
	 * Each stored payload packs the owner's entity type and locale through ids the index itself assigned, so the
	 * owners are decoded by an index restored from the canonical keys and the ORIGINAL payloads and locale map, and
	 * then re-registered into a fresh index, which persists in whichever shape the engine picks for its size. As with
	 * {@link #writeOwnerUniqueIndex}, every old leaf page no new leaf overwrites is removed.
	 *
	 * @param catalogVersion     the catalog version being upgraded
	 * @param rekey              the planned re-key
	 * @param service            the catalog-level storage part service
	 * @param entityTypeResolver translates between entity type names and the ids the payloads carry
	 */
	private static void writeGlobalUniqueIndex(
		long catalogVersion,
		@Nonnull GlobalUniqueIndexRekey rekey,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService service,
		@Nonnull EntityTypeClassifierResolver entityTypeResolver
	) {
		final GlobalUniqueIndex decodingIndex = new GlobalUniqueIndex(
			rekey.scope(), rekey.attributeKey(), rekey.type(), rekey.indexedDecimalPlaces(), rekey.values(),
			rekey.payloads(), rekey.localeIndex()
		);
		final GlobalUniqueIndex rebuiltIndex = new GlobalUniqueIndex(
			rekey.scope(), rekey.attributeKey(), rekey.type(), rekey.indexedDecimalPlaces()
		);
		for (final Serializable value : rekey.values()) {
			final EntityReferenceWithLocale owner = decodingIndex
				.getEntityReferenceByUniqueValue(value, null, entityTypeResolver)
				.orElseThrow(
					() -> new GenericEvitaInternalError(
						"A re-keyed value of attribute `" + rekey.attributeKey() + "` lost its owner!"
					)
				);
			rebuiltIndex.registerUniqueKey(
				value, owner.getType(), owner.locale(), owner.getPrimaryKey(), entityTypeResolver
			);
		}
		final TrappedChanges changes = new TrappedChanges();
		rebuiltIndex.appendStorageParts(rekey.attributeKey(), changes);
		for (final int pageSequence : staleLeafPageSequences(rekey.previousLeafPageSequences(), changes)) {
			changes.addChangeToStore(
				new GlobalUniqueIndexLeafPageRemoval(rekey.scope(), rekey.attributeKey(), pageSequence)
			);
		}
		drain(catalogVersion, changes, service);
	}

	/**
	 * Returns the leaf pages of the old tree that the rebuilt one does not reuse - every previous page sequence that
	 * the root among `changes` does not list.
	 *
	 * @param previousLeafPageSequences the leaf pages the old root listed (empty for an inline index)
	 * @param changes                   the parts the rebuilt index emitted, its root among them
	 * @return the page sequences to remove
	 */
	@Nonnull
	private static int[] staleLeafPageSequences(
		@Nonnull int[] previousLeafPageSequences,
		@Nonnull TrappedChanges changes
	) {
		if (previousLeafPageSequences.length == 0) {
			return previousLeafPageSequences;
		}
		int[] reusedPageSequences = new int[0];
		final Iterator<StoragePart> it = changes.getTrappedChangesIterator();
		while (it.hasNext()) {
			final StoragePart part = it.next();
			if (part instanceof UniqueIndexStoragePart ownerRoot && ownerRoot.isPaged()) {
				reusedPageSequences = ownerRoot.getLeafPageSequences();
			} else if (part instanceof GlobalUniqueIndexStoragePart globalRoot && globalRoot.isPaged()) {
				reusedPageSequences = globalRoot.getLeafPageSequences();
			}
		}
		final Set<Integer> reused = new HashSet<>(reusedPageSequences.length);
		for (final int pageSequence : reusedPageSequences) {
			reused.add(pageSequence);
		}
		final CompositeIntArray stale = new CompositeIntArray();
		for (final int pageSequence : previousLeafPageSequences) {
			if (!reused.contains(pageSequence)) {
				stale.add(pageSequence);
			}
		}
		return stale.toArray();
	}

	/**
	 * Rewrites every owner sort index of one collection that stores temporal values raw, localized texts in the order
	 * of the bare collator, locales in the order of their language tag or date time ranges in the order of whole
	 * seconds - see *Owner sort indexes* in the class documentation - into the collection data store, unflushed.
	 *
	 * Every entity index the collection lists is inspected - its global index, its referenced-type indexes and every
	 * partition object, with one owner or with several - and in each every sort index the load path restores as an
	 * owner: one with no filter index under the same key. A sort index with one is a view over that filter index and
	 * ignores the values its part may carry, so it has nothing to rewrite. Only a sort index with a temporal, a
	 * localized `String`, a `Locale` or a `DateTimeRange` element is read in full, and only one holding a raw value or
	 * out of the order of the current comparator is written - a catalog holding no such value pays the reads, never a
	 * write.
	 *
	 * The rewritten parts are put into the data store here and now, never left to a flush of an index flagged dirty:
	 * the conversion reads them as stored, and a flush writes them together with everything else the collection wrote.
	 *
	 * @param catalogVersion the catalog version being upgraded
	 * @param collection     the collection whose entity indexes are inspected
	 * @return how many sort indexes were rewritten
	 * @throws GenericEvitaInternalError when a listed entity index or a sort index it lists is missing, or the leaf
	 *                                   pages of a sort index to rewrite are not in the ascending order of the release
	 *                                   that wrote them
	 */
	private static int rewriteLegacyOwnerSortIndexes(
		long catalogVersion,
		@Nonnull CollectionUnderUpgrade collection
	) {
		final OffsetIndexStoragePartPersistenceService service =
			collection.service().getStoragePartPersistenceService();
		int rewritten = 0;
		for (final Integer indexPrimaryKey : collection.indexIds()) {
			final EntityIndexStoragePart indexPart = requireEntityIndexPart(service, catalogVersion, indexPrimaryKey);
			final Set<AttributeIndexKey> viewKeys = filterIndexKeys(indexPart);
			for (final AttributeIndexStorageKey storageKey : indexPart.getAttributeIndexes()) {
				if (storageKey.indexType() != AttributeIndexType.SORT || viewKeys.contains(storageKey.attribute())) {
					continue;
				}
				final SortIndexStoragePart root = service.getStoragePart(
					catalogVersion,
					AttributeIndexStoragePart.computeUniquePartId(
						indexPrimaryKey, AttributeIndexType.SORT, storageKey.attribute(),
						service.getReadOnlyKeyCompressor()
					),
					SortIndexStoragePart.class
				);
				if (root == null) {
					throw new GenericEvitaInternalError(
						"Sort index with id " + indexPrimaryKey + " with key " + storageKey.attribute() +
							" was not found in persistent storage!"
					);
				}
				if (
					!LegacyOwnerSortIndexCorrection.isApplicable(
						root.getComparatorBase(), root.getAttributeIndexKey().locale()
					)
				) {
					continue;
				}
				final ValueToRecord[][] perPageBuckets = root.isPaged() ?
					readSortIndexLeafPages(catalogVersion, indexPrimaryKey, root, service) : null;
				final TrappedChanges changes = new TrappedChanges();
				if (LegacyOwnerSortIndexCorrection.rewrite(root, perPageBuckets, changes)) {
					drain(catalogVersion, changes, service);
					rewritten++;
				}
			}
		}
		return rewritten;
	}

	/**
	 * Reads the buckets of every leaf page a paged owner sort index root lists, in the order it lists them - the way
	 * the load path reads them.
	 *
	 * @param catalogVersion  the catalog version being upgraded
	 * @param indexPrimaryKey the entity index holding the sort index
	 * @param root            the paged root of the sort index
	 * @param service         the collection's storage part service
	 * @return the buckets of every leaf page
	 * @throws GenericEvitaInternalError when a listed leaf page is missing
	 */
	@Nonnull
	private static ValueToRecord[][] readSortIndexLeafPages(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull SortIndexStoragePart root,
		@Nonnull OffsetIndexStoragePartPersistenceService service
	) {
		final int streamId = service.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(
				indexPrimaryKey, new AttributeKeyWithIndexType(root.getAttributeIndexKey(), AttributeIndexType.SORT)
			)
		);
		final int[] pageSequences = root.getLeafPageSequencesOrThrowException();
		final ValueToRecord[][] perPageBuckets = new ValueToRecord[pageSequences.length][];
		for (int i = 0; i < pageSequences.length; i++) {
			final SortIndexLeafPagePart leafPage = service.getStoragePart(
				catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequences[i]),
				SortIndexLeafPagePart.class
			);
			if (leafPage == null) {
				throw new GenericEvitaInternalError(
					"Sort index leaf page " + pageSequences[i] + " (stream " + streamId + ") for key " +
						root.getAttributeIndexKey() + " was not found in persistent storage!"
				);
			}
			perPageBuckets[i] = leafPage.getBuckets();
		}
		return perPageBuckets;
	}

	/**
	 * Reads the manifest of an entity index a collection header lists - its absence means the published catalog is
	 * damaged, and no step of the migration can proceed without it.
	 *
	 * @param service         the collection's storage part service
	 * @param catalogVersion  the catalog version being upgraded
	 * @param indexPrimaryKey the primary key of the entity index
	 * @return the manifest of the entity index
	 * @throws GenericEvitaInternalError when the manifest is missing
	 */
	@Nonnull
	private static EntityIndexStoragePart requireEntityIndexPart(
		@Nonnull OffsetIndexStoragePartPersistenceService service,
		long catalogVersion,
		int indexPrimaryKey
	) {
		final EntityIndexStoragePart indexPart = service.getStoragePart(
			catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
		);
		if (indexPart == null) {
			throw new GenericEvitaInternalError(
				"Entity index storage part for primary key " + indexPrimaryKey + " is missing!"
			);
		}
		return indexPart;
	}

	/**
	 * Returns the attributes of every filter index an entity index lists - the keys under which a unique or a sort
	 * index of the same entity index views the filter index rather than owning values of its own.
	 *
	 * @param indexPart the manifest of the entity index
	 * @return the attributes of its filter indexes
	 */
	@Nonnull
	private static Set<AttributeIndexKey> filterIndexKeys(@Nonnull EntityIndexStoragePart indexPart) {
		final Set<AttributeIndexKey> filterKeys = new HashSet<>();
		for (final AttributeIndexStorageKey storageKey : indexPart.getAttributeIndexes()) {
			if (storageKey.indexType() == AttributeIndexType.FILTER) {
				filterKeys.add(storageKey.attribute());
			}
		}
		return filterKeys;
	}

	/**
	 * Applies every part in `changes` to `service`: a removal instruction removes the part it names, anything else is
	 * stored - the same drain the regular flush performs.
	 *
	 * @param catalogVersion the catalog version being upgraded
	 * @param changes        the parts to apply, in order
	 * @param service        the storage part service to apply them to
	 */
	private static void drain(
		long catalogVersion,
		@Nonnull TrappedChanges changes,
		@Nonnull OffsetIndexStoragePartPersistenceService service
	) {
		final Iterator<StoragePart> it = changes.getTrappedChangesIterator();
		while (it.hasNext()) {
			final StoragePart part = it.next();
			if (part instanceof DeferredRemovalStoragePart removal) {
				service.removeStoragePart(
					catalogVersion,
					removal.computeUniquePartIdAndSet(service.getReadOnlyKeyCompressor()),
					removal.removedContainerType()
				);
			} else {
				service.putStoragePart(catalogVersion, part);
			}
		}
	}

	/**
	 * Plans the clamp of every filter index of one collection whose temporal keys lie beyond the window of moments this
	 * version can hold, in every entity index the collection lists. Release 2026.2 and older keyed a filter index by
	 * the `Instant` of a value without a range check, so the `OffsetDateTime.MAX` / `MIN` sentinels (what the 2026.2
	 * gRPC converter made of every moment of year 9999 / year 1) are stored under an `Instant` that has no epoch
	 * millisecond, and the load path fails at the first key it encodes. The keys are clamped in place - to the edge of
	 * the window, exactly as the engine clamps such a value on its way in - and never merged: clamping preserves the
	 * order, so keys that become equal are adjacent, and the load path already merges adjacent equal keys of a tree
	 * (inline or across the pages of a paged one), unioning their records.
	 *
	 * A unique attribute folded onto its filter tree has no unique index of its own for the unique pre-pass to see, so
	 * two owners whose different keys load as one - clamped onto one edge of the window, or truncated onto one
	 * millisecond - are a collision found here, appended to `collisions`. The trees of every `OffsetDateTime` and
	 * `LocalDateTime` attribute are checked, whether or not they hold a key beyond the window, and those of every
	 * unique `LocalTime` attribute, which can meet only through the truncation.
	 * Nothing is written here.
	 *
	 * @param catalogVersion the catalog version being upgraded
	 * @param collection     the collection whose entity indexes are inspected
	 * @param collisions     receives every collision found
	 * @return the clamp plans, one per filter index that holds a key beyond the window
	 */
	@Nonnull
	private static List<FilterIndexClamp> planFilterIndexClamps(
		long catalogVersion,
		@Nonnull CollectionUnderUpgrade collection,
		@Nonnull List<UniqueKeyCollision> collisions
	) {
		final OffsetIndexStoragePartPersistenceService service =
			collection.service().getStoragePartPersistenceService();
		final List<FilterIndexClamp> clamps = new ArrayList<>(4);
		for (final Integer indexPrimaryKey : collection.indexIds()) {
			final EntityIndexStoragePart indexPart = service.getStoragePart(
				catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
			);
			if (indexPart == null) {
				// reported by the rewrite pass, which cannot proceed without it either
				continue;
			}
			final Set<AttributeIndexKey> uniqueKeys = new HashSet<>();
			for (final AttributeIndexStorageKey storageKey : indexPart.getAttributeIndexes()) {
				if (storageKey.indexType() == AttributeIndexType.UNIQUE) {
					uniqueKeys.add(storageKey.attribute());
				}
			}
			for (final AttributeIndexStorageKey storageKey : indexPart.getAttributeIndexes()) {
				if (storageKey.indexType() != AttributeIndexType.FILTER) {
					continue;
				}
				final FilterIndexStoragePart root = service.getStoragePart(
					catalogVersion,
					AttributeIndexStoragePart.computeUniquePartId(
						indexPrimaryKey, AttributeIndexType.FILTER, storageKey.attribute(),
						service.getReadOnlyKeyCompressor()
					),
					FilterIndexStoragePart.class
				);
				if (root == null) {
					continue;
				}
				final Class<?> plainType = plainTypeOf(root.getAttributeType());
				final boolean unique = uniqueKeys.contains(storageKey.attribute());
				// a `LocalTime` key has no window to leave, but a unique one meets its twin through truncation alone
				final boolean truncatedUnique = unique && LocalTime.class.isAssignableFrom(plainType);
				if (!isClampedTemporal(plainType) && !truncatedUnique) {
					continue;
				}
				final FilterIndexClamp clamp;
				if (root.isPaged()) {
					clamp = planPagedFilterIndexClamp(
						catalogVersion, indexPrimaryKey, root, service, unique, collection, indexPart, collisions
					);
				} else {
					clamp = planInlineFilterIndexClamp(root, unique, collection, indexPart, collisions);
				}
				if (clamp != null) {
					clamps.add(clamp);
				}
			}
		}
		return clamps;
	}

	/**
	 * Tells whether a filter key of `plainType` can lie beyond the window of moments this version can hold - whether
	 * it is the `Instant` form of an `OffsetDateTime` or a `LocalDateTime`.
	 */
	private static boolean isClampedTemporal(@Nonnull Class<?> plainType) {
		return OffsetDateTime.class.isAssignableFrom(plainType) || LocalDateTime.class.isAssignableFrom(plainType);
	}

	/**
	 * Plans the clamp of an inline filter index, see {@link #planFilterIndexClamps}.
	 *
	 * @return the plan, or `null` when the index holds no key beyond the window
	 */
	@Nullable
	private static FilterIndexClamp planInlineFilterIndexClamp(
		@Nonnull FilterIndexStoragePart root,
		boolean unique,
		@Nonnull CollectionUnderUpgrade collection,
		@Nonnull EntityIndexStoragePart indexPart,
		@Nonnull List<UniqueKeyCollision> collisions
	) {
		final ValueToRecordBitmap[] points = root.getHistogramPoints();
		final ValueToRecordBitmap[] clamped = new ValueToRecordBitmap[points.length];
		final FilterKeyCollisionDetector detector = new FilterKeyCollisionDetector(unique, collection, indexPart, root);
		boolean changed = false;
		for (int i = 0; i < points.length; i++) {
			final ValueToRecord bucket = clampBucketKey(points[i]);
			detector.accept(points[i], collisions);
			clamped[i] = (ValueToRecordBitmap) bucket;
			changed |= bucket != points[i];
		}
		if (!changed) {
			return null;
		}
		return new FilterIndexClamp(
			List.of(
				new FilterIndexStoragePart(
					root.getEntityIndexPrimaryKey(), root.getAttributeIndexKey(), root.getAttributeType(), clamped,
					root.getRangeIndex(), root.getIndexedDecimalPlaces(), false, root.getHighWaterPageSequence(),
					root.getLeafPageSequences(), root.isRangePaged(), root.getRangeHighWaterPageSequence(),
					root.getRangeLeafPageSequences(), root.getNextValueId(), root.getInlineValueIds(),
					root.getStoragePartPK()
				)
			)
		);
	}

	/**
	 * Plans the clamp of a paged filter index, see {@link #planFilterIndexClamps}: every leaf page the root lists is
	 * read in the order it lists them, and a page holding a key beyond the window is planned to be written again.
	 *
	 * @return the plan, or `null` when the index holds no key beyond the window
	 * @throws GenericEvitaInternalError when a listed leaf page is missing
	 */
	@Nullable
	private static FilterIndexClamp planPagedFilterIndexClamp(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull FilterIndexStoragePart root,
		@Nonnull OffsetIndexStoragePartPersistenceService service,
		boolean unique,
		@Nonnull CollectionUnderUpgrade collection,
		@Nonnull EntityIndexStoragePart indexPart,
		@Nonnull List<UniqueKeyCollision> collisions
	) {
		final int streamId = service.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(
				indexPrimaryKey, new AttributeKeyWithIndexType(root.getAttributeIndexKey(), AttributeIndexType.FILTER)
			)
		);
		final FilterKeyCollisionDetector detector = new FilterKeyCollisionDetector(unique, collection, indexPart, root);
		final List<StoragePart> pages = new ArrayList<>(2);
		for (final int pageSequence : root.getLeafPageSequences()) {
			final long pagePk = AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence);
			final FilterIndexLeafPagePart page = service.getStoragePart(
				catalogVersion, pagePk, FilterIndexLeafPagePart.class
			);
			if (page == null) {
				throw new GenericEvitaInternalError(
					"Filter index leaf page " + pageSequence + " (stream " + streamId + ") for key " +
						root.getAttributeIndexKey() + " was not found in persistent storage!"
				);
			}
			final ValueToRecord[] buckets = page.getBuckets();
			final ValueToRecord[] clamped = new ValueToRecord[buckets.length];
			boolean changed = false;
			for (int i = 0; i < buckets.length; i++) {
				clamped[i] = clampBucketKey(buckets[i]);
				detector.accept(buckets[i], collisions);
				changed |= clamped[i] != buckets[i];
			}
			if (changed) {
				pages.add(new FilterIndexLeafPagePart(streamId, pageSequence, clamped, page.getValueIds(), pagePk));
			}
		}
		return pages.isEmpty() ? null : new FilterIndexClamp(pages);
	}

	/**
	 * Clamps a stored key or unique value to the window of moments this version can hold with the rule the engine
	 * applies on the way in, see {@link LegacyOwnerSortIndexCorrection#clampToSupportedWindow}. A value that is not an
	 * `OffsetDateTime`, a `LocalDateTime` or an `Instant` comes back as it is.
	 *
	 * @param value the value as stored
	 * @return the very instance `value` when it needs no clamping, otherwise the edge of the window it is clamped to
	 */
	@Nonnull
	private static Serializable clampStoredValue(@Nonnull Serializable value) {
		return Objects.requireNonNull(LegacyOwnerSortIndexCorrection.clampToSupportedWindow(value));
	}

	/**
	 * Clamps the key of one bucket of a filter tree to the window of moments this version can hold.
	 *
	 * @param bucket the bucket as persisted
	 * @return the bucket itself when its key lies inside the window, otherwise a bucket of the same kind holding the
	 * same records under the clamped key
	 */
	@Nonnull
	static ValueToRecord clampBucketKey(@Nonnull ValueToRecord bucket) {
		final Serializable key = bucket.getValue();
		final Serializable clamped = clampStoredValue(key);
		if (clamped == key) {
			return bucket;
		} else if (bucket instanceof ValueToRecordPrimitive primitive) {
			return new ValueToRecordPrimitive(clamped, primitive.getRecordId());
		} else {
			return new ValueToRecordBitmap(clamped, bucket.getRecordIds());
		}
	}

	/**
	 * Lists every reference partition - a reduced entity index keyed by a {@link RepresentativeReferenceKey} - of one
	 * collection whose representative attribute values hold a temporal value beyond the window of moments this version
	 * can hold. The key is persisted as a whole and the partition is found again by recomputing it from the values of
	 * the entity body, which this version reads clamped; a partition keyed by a value beyond the window would never be
	 * found again. Re-keying the partitions would mean renaming storage parts, so the upgrade is refused instead - see
	 * {@link #refuseUpgradeOverOutOfWindowRepresentativeKeys}.
	 *
	 * Nothing is written here.
	 *
	 * @param catalogVersion the catalog version being upgraded
	 * @param collection     the collection whose entity indexes are inspected
	 * @param offenders      receives one description per such partition, naming each of its values beyond the window
	 */
	private static void findOutOfWindowRepresentativeKeys(
		long catalogVersion,
		@Nonnull CollectionUnderUpgrade collection,
		@Nonnull List<String> offenders
	) {
		final OffsetIndexStoragePartPersistenceService service =
			collection.service().getStoragePartPersistenceService();
		for (final Integer indexPrimaryKey : collection.indexIds()) {
			final EntityIndexStoragePart indexPart = service.getStoragePart(
				catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
			);
			if (
				indexPart == null ||
					!(indexPart.getEntityIndexKey().discriminator() instanceof RepresentativeReferenceKey key)
			) {
				continue;
			}
			// one entry per partition, naming every value of its key that lies beyond the window
			final List<String> outOfWindowValues = new ArrayList<>(0);
			for (final Serializable value : key.representativeAttributeValues()) {
				if (LegacyOwnerSortIndexCorrection.clampToSupportedWindow(value) != value) {
					outOfWindowValues.add("`" + value + "`");
				}
			}
			if (!outOfWindowValues.isEmpty()) {
				offenders.add(
					"reference `" + key.referenceName() + "` to " + key.primaryKey() + " of `" +
						collection.header().entityType() + "` (" + indexPart.getEntityIndexKey().type() +
						" index, scope " + indexPart.getEntityIndexKey().scope() +
						") with the representative value(s) " + String.join(", ", outOfWindowValues)
				);
			}
		}
	}

	/**
	 * Refuses the upgrade because a reference partition is keyed by a representative attribute value beyond the window
	 * of date-time values this version can hold, telling the operator which partitions and what to do about them.
	 * Nothing has been published when this runs: the catalog stays on storage protocol version 6, exactly as the
	 * release that wrote it left it, and no data is damaged.
	 *
	 * @param catalogName the name of the catalog being upgraded
	 * @param offenders   a description of every partition found by the pre-pass
	 * @throws ObsoleteStorageProtocolException always
	 */
	private static void refuseUpgradeOverOutOfWindowRepresentativeKeys(
		@Nonnull String catalogName,
		@Nonnull List<String> offenders
	) {
		final String message = "Catalog `" + catalogName + "` cannot be upgraded to storage protocol version " +
			PersistenceService.STORAGE_PROTOCOL_VERSION + ": " + offenders.size() + " reference partition(s) are " +
			"keyed by a representative attribute value beyond the supported date-time window (such as " +
			"`OffsetDateTime.MAX`). This version holds such a value at the edge of the window, so it would look for " +
			"each partition under a key that does not exist and could never find it again. Nothing has been " +
			"published - the catalog is unchanged and stays on storage protocol version 6. Start the evitaDB version " +
			"that wrote this catalog on it, change the representative attribute value of each reference below to a " +
			"date-time inside the window (a gRPC client must use a year after 1 and before 9999: that version " +
			"decodes every moment of year 9999 as `OffsetDateTime.MAX` and of year 1 as `OffsetDateTime.MIN` " +
			"again), and then start this version again. Partitions: " +
			formatAttributeList(offenders) + ".";
		ConsoleWriter.writeLine(message, ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD);
		LoggerFactory.getLogger(Migration_2026_3.class).error(message);
		throw new ObsoleteStorageProtocolException(
			message,
			"Catalog `" + catalogName + "` holds reference partitions keyed by a date-time value beyond the " +
				"supported window and cannot be upgraded until they are changed - see the server log."
		);
	}

	/**
	 * Refuses the upgrade because two stored spellings of one unique value are held by different owners, telling the
	 * operator exactly which values and what to do about them. Nothing has been written when this runs, so the catalog
	 * stays loadable by the release that wrote it, which is where the data has to be fixed.
	 *
	 * @param catalogName the name of the catalog being upgraded
	 * @param collisions  every collision found by the pre-pass
	 * @throws ObsoleteStorageProtocolException always
	 */
	private static void refuseUpgradeOverUniqueKeyCollisions(
		@Nonnull String catalogName,
		@Nonnull List<UniqueKeyCollision> collisions
	) {
		final List<String> described = new ArrayList<>(collisions.size());
		for (final UniqueKeyCollision collision : collisions) {
			described.add(collision.describe());
		}
		final String message = "Catalog `" + catalogName + "` cannot be upgraded to storage protocol version " +
			PersistenceService.STORAGE_PROTOCOL_VERSION + ": " +
			collisions.size() + " unique value(s) are stored in two spellings held by different owners. This version " +
			"compares unique values exactly as it compares filtered ones, so it treats as ONE unique value the same " +
			"instant written at two offsets, two Unicode spellings of one text (for example a precomposed `\\u010D` " +
			"and a `c` followed by the combining caron `\\u030C`), two decimals equal at the attribute's indexed " +
			"decimal places, two date-time values of one type that differ only below the millisecond, which this " +
			"version truncates to whole milliseconds, and two date-time values of one type beyond the supported " +
			"date-time window (such as `OffsetDateTime.MAX` and `OffsetDateTime.MAX.minusDays(1)`) or one beyond it " +
			"and one inside the millisecond at its edge, which this version holds at the same edge of the window; " +
			"each pair below would become one value with two owners. Nothing has been written - " +
			"the catalog is unchanged. Start the evitaDB version that wrote this catalog on it, change or remove one " +
			"value of every pair, and then start this version again. Collisions: " +
			formatAttributeList(described) + ".";
		ConsoleWriter.writeLine(message, ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD);
		LoggerFactory.getLogger(Migration_2026_3.class).error(message);
		throw new ObsoleteStorageProtocolException(
			message,
			"Catalog `" + catalogName + "` holds unique values that are equal once compared like filtered values and " +
				"cannot be upgraded until they are made distinct - see the server log."
		);
	}

	/**
	 * Names the owner of one stored key of a global unique index, decoding its packed payload through an index that
	 * holds that single entry.
	 */
	@Nonnull
	private static String describeGlobalOwner(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull GlobalUniqueIndexStoragePart root,
		int indexedDecimalPlaces,
		@Nonnull Serializable value,
		long payload,
		@Nonnull EntityTypeClassifierResolver entityTypeResolver
	) {
		// the value is clamped like the key it is re-keyed to: the index below keys it by epoch milliseconds, which a
		// value beyond the supported window of moments does not have
		final Serializable clamped = clampStoredValue(value);
		return new GlobalUniqueIndex(
			scope, attributeKey, root.getType(), indexedDecimalPlaces, new Serializable[]{clamped}, new long[]{payload},
			root.getLocaleIndex()
		)
			.getEntityReferenceByUniqueValue(clamped, null, entityTypeResolver)
			.map(
				owner -> "`" + owner.getType() + "` " + owner.getPrimaryKey() +
					(owner.locale() == null ? "" : " in locale `" + owner.locale().toLanguageTag() + "`")
			)
			.orElse("an unknown owner");
	}

	/**
	 * Names the owner of one stored key of an owner unique index. In the collection's global entity index the record
	 * id is the entity primary key; in any other index it is only meaningful together with that index.
	 */
	@Nonnull
	private static String describeOwnerRecord(
		@Nonnull String entityType,
		@Nonnull EntityIndexStoragePart indexPart,
		boolean globalIndex,
		int recordId
	) {
		return globalIndex ?
			"`" + entityType + "` " + recordId :
			"record " + recordId + " of index " + indexPart.getEntityIndexKey() + " of `" + entityType + "`";
	}

	/**
	 * Names an attribute index key the way an operator recognizes it: reference, attribute and locale.
	 */
	@Nonnull
	private static String describeAttribute(@Nonnull AttributeIndexKey attributeIndexKey) {
		return (attributeIndexKey.referenceName() == null ? "" : attributeIndexKey.referenceName() + ".") +
			attributeIndexKey.attributeName() +
			(attributeIndexKey.locale() == null ? "" : " [" + attributeIndexKey.locale().toLanguageTag() + "]");
	}

	/**
	 * Resolves the plain (array-unwrapped) type of a stored unique index.
	 */
	@Nonnull
	private static Class<?> plainTypeOf(@Nonnull Class<?> type) {
		return type.isArray() ? type.getComponentType() : type;
	}

	/**
	 * Renders a stored value with every non-ASCII character escaped, so two spellings that print identically are
	 * told apart in the refusal message.
	 */
	@Nonnull
	static String escapeSpelling(@Nonnull Serializable value) {
		final String text = String.valueOf(value);
		final StringBuilder sb = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (c < 128) {
				sb.append(c);
			} else {
				sb.append(String.format("\\u%04X", (int) c));
			}
		}
		return sb.toString();
	}

	/**
	 * One collection the upgrade works on, opened once and shared by the unique-key pre-pass and the rewrite pass.
	 *
	 * @param fileReference the collection's entry in the catalog header
	 * @param header        the reconciled collection header
	 * @param service       the collection's persistence service
	 * @param indexIds      primary keys of every entity index of the collection
	 * @param entitySchema  the collection's schema, which supplies the attributes' `indexedDecimalPlaces`
	 */
	record CollectionUnderUpgrade(
		@Nonnull CollectionFileReference fileReference,
		@Nonnull EntityCollectionFileHeader header,
		@Nonnull DefaultEntityCollectionPersistenceService service,
		@Nonnull Set<Integer> indexIds,
		@Nonnull EntitySchema entitySchema
	) {
	}

	/**
	 * The outcome of {@link #rekeyUniqueKeys}.
	 *
	 * @param values     the distinct canonical keys
	 * @param owners     the owner of each key, positionally aligned with `values`
	 * @param collisions every pair of input positions whose keys collapsed onto one key under different owners
	 */
	record UniqueKeyRekeying(
		@Nonnull Serializable[] values,
		@Nonnull long[] owners,
		@Nonnull List<int[]> collisions
	) {
	}

	/**
	 * A planned clamp of one filter index: the parts to write - the inline root, or the leaf pages that hold a key
	 * beyond the window of moments this version can hold.
	 *
	 * @param parts the parts to write
	 */
	record FilterIndexClamp(@Nonnull List<StoragePart> parts) {
	}

	/**
	 * Watches the buckets of one filter tree in the order they are stored in and finds, in a unique attribute, two
	 * adjacent buckets of different owners that load under one key. The clamp and the load path's normalization both
	 * preserve the order, so keys that become equal are adjacent, and a comparison with the previous bucket sees them
	 * all.
	 *
	 * The keys are compared as the load path will build the tree - the clamp followed by
	 * {@link FilterIndex#getNormalizer(Class, int)}, which truncates to whole milliseconds - because that is where two
	 * buckets merge, and every pair of different owners meeting under that key is reported, whichever step makes the
	 * keys equal: `OffsetDateTime.MAX` and `OffsetDateTime.MAX.minusDays(1)` are both clamped onto the last millisecond
	 * of the window, a stored value one nanosecond after the edge `OffsetDateTime.MIN` is clamped onto lies inside the
	 * window yet truncates onto that edge, and two values 2026.2 kept apart below the millisecond truncate onto one.
	 */
	final class FilterKeyCollisionDetector {
		private final boolean unique;
		/** Names the tree in a collision the refusal reports. */
		private final String structure;
		private final String entityType;
		private final EntityIndexStoragePart indexPart;
		/** Computes the key the load path will give a stored bucket: the clamp, then the filter normalizer. */
		private final Function<Object, Serializable> normalizer;
		/** The bucket taken by the previous call, `null` before the first one. */
		@Nullable private ValueToRecord previous;
		/** The key {@link #normalizer} gave {@link #previous}. */
		@Nullable private Serializable previousKey;

		/**
		 * @param unique     whether the attribute is unique, and so may hold one owner per key only
		 * @param collection the collection owning the tree
		 * @param indexPart  the entity index holding the tree
		 * @param root       the root of the filter tree
		 */
		FilterKeyCollisionDetector(
			boolean unique,
			@Nonnull CollectionUnderUpgrade collection,
			@Nonnull EntityIndexStoragePart indexPart,
			@Nonnull FilterIndexStoragePart root
		) {
			this.unique = unique;
			this.entityType = collection.header().entityType();
			this.structure = "attribute `" + describeAttribute(root.getAttributeIndexKey()) + "` of entity `" +
				this.entityType + "`";
			this.indexPart = indexPart;
			this.normalizer = LegacyOwnerSortIndexCorrection.createClampingNormalizer(
				plainTypeOf(root.getAttributeType()), root.getIndexedDecimalPlaces()
			);
		}

		/**
		 * Takes the next bucket of the tree and appends a collision to `collisions` when it loads under the key of the
		 * previous one and the two hold different records.
		 *
		 * @param bucket     the bucket as persisted
		 * @param collisions receives the collision
		 */
		void accept(
			@Nonnull ValueToRecord bucket,
			@Nonnull List<UniqueKeyCollision> collisions
		) {
			if (!this.unique) {
				// a key of an attribute that is not unique may hold any number of owners - there is nothing to compare
				return;
			}
			final Serializable key = this.normalizer.apply(bucket.getValue());
			if (
				this.previous != null &&
					Objects.equals(this.previousKey, key) &&
					!this.previous.recordSetEquals(bucket)
			) {
				final boolean globalIndex = this.indexPart.getEntityIndexKey().type() == EntityIndexType.GLOBAL;
				collisions.add(
					new UniqueKeyCollision(
						this.structure,
						this.previous.getValue(),
						describeOwnerRecord(
							this.entityType, this.indexPart, globalIndex, this.previous.getRecordIds().getFirst()
						),
						bucket.getValue(),
						describeOwnerRecord(
							this.entityType, this.indexPart, globalIndex, bucket.getRecordIds().getFirst()
						)
					)
				);
			}
			this.previous = bucket;
			this.previousKey = key;
		}
	}

	/**
	 * A planned rewrite of one owner unique index.
	 *
	 * @param entityIndexPrimaryKey     the entity index holding it
	 * @param attributeIndexKey         the attribute it indexes
	 * @param type                      the attribute type the stored part declares
	 * @param indexedDecimalPlaces      the scale the attribute's `BigDecimal` values are keyed at
	 * @param values                    the distinct canonical keys
	 * @param recordIds                 the owning record of each key
	 * @param previousLeafPageSequences the leaf pages the old root listed, empty for an inline index
	 */
	record OwnerUniqueIndexRekey(
		int entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> type,
		int indexedDecimalPlaces,
		@Nonnull Serializable[] values,
		@Nonnull int[] recordIds,
		@Nonnull int[] previousLeafPageSequences
	) {
	}

	/**
	 * A planned rewrite of one global unique index.
	 *
	 * @param scope                     the scope of the catalog index holding it
	 * @param attributeKey              the attribute it indexes
	 * @param type                      the attribute type the stored part declares
	 * @param indexedDecimalPlaces      the scale the attribute's `BigDecimal` values are keyed at
	 * @param localeIndex               the locale ids the stored payloads refer to
	 * @param values                    the distinct canonical keys
	 * @param payloads                  the stored packed owner of each key
	 * @param previousLeafPageSequences the leaf pages the old root listed, empty for an inline index
	 */
	record GlobalUniqueIndexRekey(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> type,
		int indexedDecimalPlaces,
		@Nonnull Map<Integer, Locale> localeIndex,
		@Nonnull Serializable[] values,
		@Nonnull long[] payloads,
		@Nonnull int[] previousLeafPageSequences
	) {
	}

	/**
	 * Two stored spellings of one unique value held by different owners.
	 *
	 * @param structure      the unique index holding them, described for an operator
	 * @param firstSpelling  the first stored spelling
	 * @param firstOwner     the owner of the first spelling, described for an operator
	 * @param secondSpelling the second stored spelling
	 * @param secondOwner    the owner of the second spelling, described for an operator
	 */
	record UniqueKeyCollision(
		@Nonnull String structure,
		@Nonnull Serializable firstSpelling,
		@Nonnull String firstOwner,
		@Nonnull Serializable secondSpelling,
		@Nonnull String secondOwner
	) {

		/**
		 * Describes the collision in one line of the refusal message, with both spellings escaped.
		 *
		 * @return the description
		 */
		@Nonnull
		String describe() {
			return this.structure + ": `" + escapeSpelling(this.firstSpelling) + "` held by " + this.firstOwner +
				" and `" + escapeSpelling(this.secondSpelling) + "` held by " + this.secondOwner;
		}
	}

	/**
	 * Translates between entity type names and the compact ids a global unique index packs into its payloads, from
	 * the collections the catalog header lists. An id the header does not list (an entry left behind by a removed
	 * collection) maps to a `#<id>` placeholder and back, so such an entry is carried over unchanged.
	 */
	final class CatalogHeaderEntityTypeResolver implements EntityTypeClassifierResolver {
		private static final String UNKNOWN_TYPE_PREFIX = "#";
		private final Map<Integer, String> entityTypesByPrimaryKey;
		private final Map<String, Integer> primaryKeysByEntityType;

		/**
		 * Indexes the collections listed by the catalog header.
		 *
		 * @param collections the catalog header's collection references
		 */
		CatalogHeaderEntityTypeResolver(@Nonnull Collection<CollectionFileReference> collections) {
			this.entityTypesByPrimaryKey = CollectionUtils.createHashMap(collections.size());
			this.primaryKeysByEntityType = CollectionUtils.createHashMap(collections.size());
			for (final CollectionFileReference collection : collections) {
				this.entityTypesByPrimaryKey.put(collection.entityTypePrimaryKey(), collection.entityType());
				this.primaryKeysByEntityType.put(collection.entityType(), collection.entityTypePrimaryKey());
			}
		}

		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			final Integer primaryKey = this.primaryKeysByEntityType.get(entityType);
			return primaryKey == null ?
				Integer.parseInt(entityType.substring(UNKNOWN_TYPE_PREFIX.length())) :
				primaryKey;
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			final String entityType = this.entityTypesByPrimaryKey.get(entityTypePrimaryKey);
			return entityType == null ? UNKNOWN_TYPE_PREFIX + entityTypePrimaryKey : entityType;
		}
	}

}
