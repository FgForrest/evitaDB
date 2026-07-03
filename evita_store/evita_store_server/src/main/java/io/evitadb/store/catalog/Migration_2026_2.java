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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.NumberUtils;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Migration logic for upgrading the catalog storage protocol from version 5 to version 6.
 *
 * Version 6 unifies the in-memory representation of a filterable attribute's value keys: `FilterIndex` and the
 * both-flagged `SortIndex` share one comparator-ordered `value → ValueToRecord` tree, and that tree's string keys are
 * normalized to Unicode **NFD** (the canonical form `SortIndex` already used). Before v6 the `FilterIndex` stored string
 * keys **raw** (`NO_NORMALIZATION`). Because the shared tree is rebuilt from the persisted filter histogram points on
 * load, and the engine now normalizes every incoming query value to NFD, a catalog written before v6 would suffer
 * silent non-ASCII lookup misses (and, for naturally-ordered non-localized strings, duplicate buckets on the next
 * write). NFD→raw is not invertible, so the re-key cannot be deferred to a lazy path — it is done once here.
 *
 * This migration therefore performs two value-key re-keyings, both driven by the same protocol bump:
 *
 * 1. **`String` filter parts** — every `FilterIndexStoragePart` whose attribute type is `String` (or `String[]`) has its
 *    histogram points re-keyed to NFD, with buckets that collide under NFD merged (their record bitmaps unioned) and the
 *    whole point array re-sorted under the index's own comparator so it stays strictly monotone (the invariant
 *    `InvertedIndex` asserts on load). ASCII-only parts are left untouched (NFD is the identity on ASCII).
 *
 * 2. **`BigDecimal` filter / sort parts** — version 6 also stops storing a filterable/sortable `BigDecimal` attribute's
 *    value keys as raw `BigDecimal` and instead stores the order-preserving scaled `int`
 *    (`NumberUtils.convertToInt(value, indexedDecimalPlaces)`) that the runtime now uses. The per-attribute
 *    `indexedDecimalPlaces` is frozen into each rewritten part: a legacy pre-v6 part does not carry it, so it is
 *    resolved from the entity schema here and persisted into the re-keyed part; every later index load then reads it
 *    back verbatim (it is no longer re-resolved from the schema on load). A pre-v6 catalog still holds raw `BigDecimal`
 *    keys, so:
 *    - every `BigDecimal` (or `BigDecimal[]`) `FilterIndexStoragePart` has its histogram points re-keyed to the scaled
 *      `Integer` (buckets that collapse to the same scaled int merged, bitmaps unioned, re-sorted under natural
 *      `Integer` order);
 *    - a **sort-only** `BigDecimal` attribute (a SORT part with NO FILTER part for the same `AttributeIndexKey` in the
 *      same entity index) is an `OwnerSortIndex` whose persisted `sortedRecordValues` / `valueCardinalities` ARE read
 *      back on load, so they are re-scaled to `Integer` and re-bucketed here.
 *
 * The decision NOT to touch a both-flagged `BigDecimal` attribute's sort part is taken from `AttributeIndexLoader`:
 * `fetchSort` runs in **view mode** whenever a FILTER part exists for the same key — the persisted sort
 * `sortedRecordValues` / `valueCardinalities` are then IGNORED (the slim view part carries none and a legacy full part
 * is discarded), the sort view being rebuilt from the shared filter tree (already re-keyed by step 2). Re-keying such a
 * sort part would be wasted work, so it is skipped. Unique parts are unchanged: uniqueness keeps its own standalone
 * index and stays exact `BigDecimal`.
 *
 * Independently of the re-keying, version 6 also **evicts the entity-id membership bitmaps** (`entityIds` and the
 * per-locale `entityIdsByLanguage`) out of every {@link EntityIndexStoragePart} manifest into a sibling
 * {@link EntityIdsStoragePart}, so that a later per-entity membership change rewrites only the small bitmaps record and
 * not the bulky manifest. Each legacy manifest (read with its inline bitmaps by the backward-compatible serializer) is
 * rewritten in the modern bitmap-less form and its bitmaps re-persisted as the sibling part; a manifest already evicted
 * by an earlier migration step is detected by its `null` inline carrier and left untouched. This makes the eager
 * rewrite the single point of eviction, so no stale inline bitmaps survive to resurface at runtime.
 *
 * The shape follows {@link Migration_2025_6#upgradeFromStorageProtocolVersion_3_to_4} (the inline index-part rewrite
 * analogue), not the WAL-rewrite {@link Migration_2026_1}: per collection, re-key the affected filter parts, flush the
 * collection, then bump the catalog header to protocol 6. Crash-safety is inherited from the dispatch loop in
 * `DefaultCatalogPersistenceService.verifyAndUpgradeStorageFormat` — the header stays at 5 until the post-upgrade
 * action commits 6, so an interrupted upgrade simply re-runs. The same property makes the `BigDecimal` scaling abort
 * safely: if a stored value does not fit `int` at the schema's `indexedDecimalPlaces` (`NumberUtils.convertToInt` throws
 * `ArithmeticException`), the migration fails loudly (wrapping the catalog / attribute / value context in a
 * `GenericEvitaInternalError`) before the header advances, so the catalog stays at protocol 5 and the upgrade can be
 * retried after the offending schema scale is corrected.
 *
 * @deprecated removable once no catalog older than the 2026 release that introduced protocol 6 is in use.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
public interface Migration_2026_2 {

	/**
	 * Upgrades the catalog storage protocol version from version 5 to version 6 by re-keying every `String`-typed
	 * filter index histogram to Unicode NFD (merging NFD-colliding buckets and re-sorting under the index comparator),
	 * re-scaling `BigDecimal` filter/sort keys to the order-preserving scaled `int`, and evicting every entity index's
	 * inline membership bitmaps into a sibling {@link EntityIdsStoragePart} (rewriting the manifest bitmap-less).
	 *
	 * @param catalogHeader                            header of the catalog being upgraded
	 * @param storagePartPersistenceService            catalog-level storage part persistence service
	 * @param entityCollectionPersistenceServiceFactory factory creating a persistence service for an entity collection
	 * @param postUpgradeAction                        action committing the upgraded (protocol 6) catalog header
	 */
	static void upgradeFromStorageProtocolVersion_5_to_6(
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService,
		@Nonnull Function<EntityCollectionFileHeader, DefaultEntityCollectionPersistenceService> entityCollectionPersistenceServiceFactory,
		@Nonnull Consumer<CatalogHeader<LogFileRecordReference, CollectionFileReference>> postUpgradeAction
	) {
		ConsoleWriter.writeLine(
			"Catalog `" + catalogHeader.catalogName() + "` uses storage protocol version 5; re-keying filter indexes to NFD (protocol 6).",
			ConsoleColor.BRIGHT_BLUE
		);

		final long catalogVersion = catalogHeader.version();
		final Collection<CollectionFileReference> entityTypeFileIndexes = catalogHeader.getEntityTypeFileIndexes();
		final HashMap<String, CollectionFileReference> newCollectionFileIndex = CollectionUtils.createHashMap(entityTypeFileIndexes.size());

		for (final CollectionFileReference entityTypeFileIndex : entityTypeFileIndexes) {
			final EntityCollectionFileHeader entityCollectionHeader = Objects.requireNonNull(
				storagePartPersistenceService.getStoragePart(
					catalogVersion, entityTypeFileIndex.entityTypePrimaryKey(), EntityCollectionFileHeader.class
				)
			);
			final DefaultEntityCollectionPersistenceService collectionPersistenceService = Objects.requireNonNull(
				entityCollectionPersistenceServiceFactory.apply(entityCollectionHeader)
			);
			final OffsetIndexStoragePartPersistenceService collectionStoragePartService =
				collectionPersistenceService.getStoragePartPersistenceService();

			// the entity schema (loaded once per collection) supplies the per-attribute `indexedDecimalPlaces` that drives
			// the BigDecimal scaled-int re-key; the schema is always stored under storage-part id 1
			final EntitySchema entitySchema = Objects.requireNonNull(
				collectionStoragePartService.getStoragePart(catalogVersion, 1, EntitySchemaStoragePart.class),
				"Entity schema storage part is missing for collection `" + entityCollectionHeader.entityType() + "`!"
			).entitySchema();

			// every entity index of the collection (the reduced ones plus the global one)
			final Set<Integer> indexIds = new HashSet<>(entityCollectionHeader.usedEntityIndexPrimaryKeys());
			if (entityCollectionHeader.globalEntityIndexPrimaryKey() != null) {
				indexIds.add(entityCollectionHeader.globalEntityIndexPrimaryKey());
			}

			int rekeyedIndexes = 0;
			int evictedManifests = 0;
			for (final Integer indexPrimaryKey : indexIds) {
				final EntityIndexStoragePart indexPart = collectionStoragePartService.getStoragePart(
					catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
				);
				if (indexPart == null) {
					throw new GenericEvitaInternalError(
						"Entity index storage part for primary key " + indexPrimaryKey + " is missing!"
					);
				}
				// the reference scope of every attribute indexed under this index is carried by the OWNING index key,
				// not by the per-attribute key: a legacy-rehydrated AttributeIndexKey has a null reference name (the old
				// AttributeKey form carried none), so a reference attribute would otherwise be looked up at entity level
				// and missed. Resolved once per index and threaded into the scale resolver below.
				final String referenceName = indexPart.getEntityIndexKey().referenceName();
				// the FILTER attribute keys of this index, used to tell sort-only BigDecimal parts (owner mode, persisted
				// values read back on load) from both-flagged ones (view mode, persisted values ignored on load)
				final Set<AttributeIndexKey> filterAttributeKeys = new HashSet<>();
				for (final AttributeIndexStorageKey attributeIndexKey : indexPart.getAttributeIndexes()) {
					if (attributeIndexKey.indexType() == AttributeIndexType.FILTER) {
						filterAttributeKeys.add(attributeIndexKey.attribute());
					}
				}
				for (final AttributeIndexStorageKey attributeIndexKey : indexPart.getAttributeIndexes()) {
					if (attributeIndexKey.indexType() == AttributeIndexType.FILTER
						&& rekeyFilterIndex(
							catalogVersion, indexPrimaryKey, attributeIndexKey,
							collectionStoragePartService, entitySchema, referenceName)) {
						rekeyedIndexes++;
					} else if (attributeIndexKey.indexType() == AttributeIndexType.SORT
						&& !filterAttributeKeys.contains(attributeIndexKey.attribute())
						&& rekeySortOnlyIndex(
							catalogVersion, indexPrimaryKey, attributeIndexKey,
							collectionStoragePartService, entitySchema, referenceName)) {
						rekeyedIndexes++;
					}
				}

				// evict the legacy inline entity-id bitmaps into a sibling EntityIdsStoragePart and rewrite
				// the manifest in the modern bitmap-less form. A native 2026.1 (protocol 5) manifest is read
				// by the backward-compatible serializer, which populates the inline carrier — a non-null
				// carrier is the precise "still inline" signal. A manifest already evicted by an earlier
				// migration step (e.g. Migration_2025_6) is read by the modern serializer, reports a null
				// carrier, and is left untouched so its existing sibling part is not clobbered.
				final Bitmap inlineEntityIds = indexPart.getEntityIds();
				if (inlineEntityIds != null) {
					collectionStoragePartService.putStoragePart(
						catalogVersion,
						new EntityIndexStoragePart(
							indexPart.getPrimaryKey(),
							indexPart.getVersion(),
							indexPart.getEntityIndexKey(),
							indexPart.getAttributeIndexes(),
							indexPart.getPriceIndexes(),
							indexPart.isHierarchyIndex(),
							indexPart.getFacetIndexes(),
							indexPart.getHistogramIndexes()
						)
					);
					// persist the evicted bitmaps as the sibling part (skip empty indexes — the loader falls
					// back to an empty bitmap when the sibling is absent)
					final Map<Locale, TransactionalBitmap> inlineByLanguage = indexPart.getEntityIdsByLanguage();
					if (!inlineEntityIds.isEmpty() || (inlineByLanguage != null && !inlineByLanguage.isEmpty())) {
						collectionStoragePartService.putStoragePart(
							catalogVersion,
							new EntityIdsStoragePart(
								indexPart.getPrimaryKey(),
								indexPart.getVersion(),
								inlineEntityIds,
								inlineByLanguage == null ? Map.of() : inlineByLanguage
							)
						);
					}
					evictedManifests++;
				}
			}

			if (rekeyedIndexes > 0 || evictedManifests > 0) {
				// at least one part of this collection changed (a re-keyed filter/sort index, or an entity
				// index manifest whose inline bitmaps were evicted) - re-persist the collection and pick up
				// its new location
				final OffsetIndexDescriptor offsetIndexDescriptor = collectionPersistenceService.flush(
					catalogVersion,
					new NoChangeHeaderInfoSupplier(entityCollectionHeader)
				);
				final EntityCollectionFileHeader newCollectionHeader = collectionPersistenceService.getEntityCollectionHeader();
				storagePartPersistenceService.putStoragePart(catalogVersion, newCollectionHeader);
				newCollectionFileIndex.put(
					entityTypeFileIndex.entityType(),
					new CollectionFileReference(
						entityTypeFileIndex.entityType(),
						entityTypeFileIndex.entityTypePrimaryKey(),
						entityTypeFileIndex.fileIndex(),
						offsetIndexDescriptor.fileLocation()
					)
				);
				ConsoleWriter.writeLine(
					"Entity collection `" + entityCollectionHeader.entityType() + "`: re-keyed " + rekeyedIndexes +
						" index(es) (NFD strings / scaled BigDecimals), evicted entity-id bitmaps from " +
						evictedManifests + " manifest(s).",
					ConsoleColor.BRIGHT_BLUE
				);
			} else {
				// nothing changed (ASCII-only string keys, no BigDecimal parts, manifests already evicted) -
				// keep the existing collection file
				newCollectionFileIndex.put(entityTypeFileIndex.entityType(), entityTypeFileIndex);
			}
		}

		// commit the upgraded catalog header (protocol 6); compressed keys are unchanged by this migration
		postUpgradeAction.accept(
			new CatalogHeader<>(
				6,
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
			"Catalog `" + catalogHeader.catalogName() + "` was successfully upgraded to the protocol version 6.",
			ConsoleColor.BRIGHT_BLUE, ConsoleDecoration.BOLD
		);
	}

	/**
	 * Re-keys a single FILTER index's persisted histogram points, branching on the stored attribute type:
	 *
	 * - `String` / `String[]` → Unicode NFD (points whose NFD form collides merged, bitmaps unioned, re-sorted under the
	 *   index's own comparator so the array stays strictly monotone). ASCII-only parts are left unchanged.
	 * - `BigDecimal` / `BigDecimal[]` → the order-preserving scaled `Integer`
	 *   (`NumberUtils.convertToInt(value, indexedDecimalPlaces)`); points that collapse to the same scaled int merged,
	 *   bitmaps unioned, re-sorted under natural `Integer` order. The scale itself is not persisted — it is re-resolved
	 *   from the entity schema on every later index load.
	 * - any other type → left untouched (already canonical in v5).
	 *
	 * @param catalogVersion              the catalog version being migrated
	 * @param indexPrimaryKey             the owning entity index primary key
	 * @param attributeIndexKey           the FILTER attribute storage key to re-key
	 * @param collectionStoragePartService persistence service of the owning entity collection
	 * @param entitySchema                the collection's entity schema (supplies `indexedDecimalPlaces` per attribute)
	 * @param referenceName               name of the reference owning this index's attributes (from the owning
	 *                                    {@link io.evitadb.index.EntityIndexKey}), or `null` for an entity-level index
	 * @return {@code true} when the part was rewritten, {@code false} when it was left unchanged
	 */
	private static boolean rekeyFilterIndex(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nonnull OffsetIndexStoragePartPersistenceService collectionStoragePartService,
		@Nonnull EntitySchema entitySchema,
		@Nullable String referenceName
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			indexPrimaryKey,
			AttributeIndexType.FILTER,
			attributeIndexKey.attribute(),
			collectionStoragePartService.getReadOnlyKeyCompressor()
		);
		final FilterIndexStoragePart part = collectionStoragePartService.getStoragePart(
			catalogVersion, primaryKey, FilterIndexStoragePart.class
		);
		if (part == null) {
			throw new GenericEvitaInternalError(
				"Filter index with id " + indexPrimaryKey + " with key " + attributeIndexKey.attribute() +
					" was not found in persistent storage!"
			);
		}
		final Class<?> attributeType = part.getAttributeType();
		final Class<?> plainType = attributeType.isArray() ? attributeType.getComponentType() : attributeType;

		if (String.class.isAssignableFrom(plainType)) {
			// merge points by their NFD key under the SAME comparator the index uses, so the rebuilt array matches the
			// load-time bucket ordering and identity (canonically-equivalent values already shared a bucket pre-migration)
			@SuppressWarnings({"unchecked", "rawtypes"})
			final Comparator<Serializable> comparator =
				(Comparator) FilterIndex.getComparator(part.getAttributeIndexKey(), plainType);
			final ValueToRecordBitmap[] rekeyedPoints = rekeyHistogramPointsToNfd(part.getHistogramPoints(), comparator);
			// null signals "nothing changed" (ASCII-only keys, no NFD collisions, no reordering)
			if (rekeyedPoints == null) {
				return false;
			}
			collectionStoragePartService.putStoragePart(
				catalogVersion,
				new FilterIndexStoragePart(
					part.getEntityIndexPrimaryKey(),
					part.getAttributeIndexKey(),
					attributeType,
					rekeyedPoints,
					part.getRangeIndex(),
					part.getStoragePartPK()
				)
			);
			return true;
		} else if (BigDecimal.class.isAssignableFrom(plainType)) {
			final int places = resolveIndexedDecimalPlaces(
				entitySchema, referenceName, attributeIndexKey.attribute().attributeName()
			);
			final ValueToRecordBitmap[] rekeyedPoints = rekeyHistogramPoints(
				() -> rekeyHistogramPointsToScaledInt(part.getHistogramPoints(), places),
				catalogVersion, attributeIndexKey, entitySchema.getName()
			);
			collectionStoragePartService.putStoragePart(
				catalogVersion,
				new FilterIndexStoragePart(
					part.getEntityIndexPrimaryKey(),
					part.getAttributeIndexKey(),
					attributeType,
					rekeyedPoints,
					part.getRangeIndex(),
					places,
					part.getStoragePartPK()
				)
			);
			return true;
		} else {
			// every other type was already canonical in v5 (no normalization / scaling change)
			return false;
		}
	}

	/**
	 * Re-keys a single **sort-only** `BigDecimal` SORT index (an `OwnerSortIndex` whose persisted
	 * `sortedRecordValues` / `valueCardinalities` ARE read back on load) from raw `BigDecimal` to the order-preserving
	 * scaled `Integer`. The flat `sortedRecords` record-id blocks are kept as-is; only the distinct value side and its
	 * cardinality map are re-scaled and re-bucketed (cardinalities of values that collapse to the same scaled int are
	 * summed), and the new `indexedDecimalPlaces` field is populated. A non-`BigDecimal` sort part (or one with no
	 * BigDecimal values) is left untouched. Compound sort attributes never reach this branch with a bare `BigDecimal`
	 * value — their values are wrapped `ComparableArray`s — so they pass through unchanged.
	 *
	 * @param catalogVersion              the catalog version being migrated
	 * @param indexPrimaryKey             the owning entity index primary key
	 * @param attributeIndexKey           the SORT attribute storage key to re-key
	 * @param collectionStoragePartService persistence service of the owning entity collection
	 * @param entitySchema                the collection's entity schema (supplies `indexedDecimalPlaces` per attribute)
	 * @param referenceName               name of the reference owning this index's attributes (from the owning
	 *                                    {@link io.evitadb.index.EntityIndexKey}), or `null` for an entity-level index
	 * @return {@code true} when the part was rewritten, {@code false} when it was left unchanged
	 */
	private static boolean rekeySortOnlyIndex(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nonnull OffsetIndexStoragePartPersistenceService collectionStoragePartService,
		@Nonnull EntitySchema entitySchema,
		@Nullable String referenceName
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			indexPrimaryKey,
			AttributeIndexType.SORT,
			attributeIndexKey.attribute(),
			collectionStoragePartService.getReadOnlyKeyCompressor()
		);
		final SortIndexStoragePart part = collectionStoragePartService.getStoragePart(
			catalogVersion, primaryKey, SortIndexStoragePart.class
		);
		if (part == null) {
			throw new GenericEvitaInternalError(
				"Sort index with id " + indexPrimaryKey + " with key " + attributeIndexKey.attribute() +
					" was not found in persistent storage!"
			);
		}
		final Serializable[] sortedValues = part.getSortedRecordsValues();
		// only a single-attribute BigDecimal sort index stores bare BigDecimal value keys; everything else (already-scaled
		// Integers from a re-run, or compound ComparableArray values) is left as-is
		boolean anyBigDecimal = false;
		for (final Serializable value : sortedValues) {
			if (value instanceof BigDecimal) {
				anyBigDecimal = true;
				break;
			}
		}
		if (!anyBigDecimal) {
			return false;
		}

		final int places = resolveIndexedDecimalPlaces(
			entitySchema, referenceName, attributeIndexKey.attribute().attributeName()
		);
		final ScaledSortValues scaled = rekeyHistogramPoints(
			() -> rekeySortedValuesToScaledInt(sortedValues, part.getValueCardinalities(), places),
			catalogVersion, attributeIndexKey, entitySchema.getName()
		);
		collectionStoragePartService.putStoragePart(
			catalogVersion,
			new SortIndexStoragePart(
				part.getEntityIndexPrimaryKey(),
				part.getAttributeIndexKey(),
				part.getComparatorBase(),
				part.getSortedRecords(),
				scaled.sortedRecordValues(),
				scaled.valueCardinalities(),
				places,
				part.getStoragePartPK()
			)
		);
		return true;
	}

	/**
	 * Resolves the `indexedDecimalPlaces` scale for the attribute identified by `referenceName` + `attributeName` from
	 * the supplied entity schema, returning `0` for every non-`BigDecimal` attribute type. This is used **only** while
	 * re-keying pre-v6 raw-`BigDecimal` filter/sort parts into the frozen scaled-int form; once a part carries the frozen
	 * `indexedDecimalPlaces`, the steady-state loader reads that scale verbatim from the storage part instead of
	 * re-resolving it from the schema.
	 *
	 * The `referenceName` is the scope of the OWNING entity index (see
	 * {@link io.evitadb.index.EntityIndexKey#referenceName()}), not the per-attribute storage key — a legacy-rehydrated
	 * `AttributeIndexKey` carries a `null` reference name, so the scope must be taken from the owning index. A
	 * reference-scoped entity index (`REFERENCED_ENTITY_TYPE` / `REFERENCED_ENTITY` / `…_GROUP_…`) holds BOTH the
	 * reference's own attributes AND copies of the source entity's entity-level attributes; once the per-attribute key
	 * has lost its reference name the two are indistinguishable, so the lookup tries the **reference scope first** and
	 * **falls back to the entity level**. A reflected reference whose target is not wired up yet at migration time cannot
	 * answer attribute queries (its inherited attributes come from the unavailable target), so its reference-scope lookup
	 * is skipped and the resolution falls back to the entity level.
	 *
	 * @param entitySchema  the collection's entity schema
	 * @param referenceName name of the reference owning this index's attributes, or `null` for an entity-level index
	 * @param attributeName name of the indexed attribute
	 * @return the schema's `indexedDecimalPlaces` for that attribute
	 * @throws GenericEvitaInternalError when the attribute is missing from both the reference and the entity schema
	 */
	private static int resolveIndexedDecimalPlaces(
		@Nonnull EntitySchema entitySchema,
		@Nullable String referenceName,
		@Nonnull String attributeName
	) {
		// reference-scoped index: prefer the reference's own attribute, then fall back to the entity-level attribute
		// (the reference index also holds copies of the source entity's entity-level attributes)
		if (referenceName != null) {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
				.orElseThrow(() -> new GenericEvitaInternalError(
					"Reference `" + referenceName + "` referenced by attribute index `" + attributeName +
						"` is missing from entity schema `" + entitySchema.getName() + "`!"
				));
			// a reflected reference whose target is not yet available throws on getAttribute (its attributes are
			// inherited from the unavailable target); skip the reference-scope lookup and resolve at entity level
			final boolean referenceAttributesAvailable =
				!(referenceSchema instanceof ReflectedReferenceSchemaContract reflected)
					|| reflected.isReflectedReferenceAvailable();
			if (referenceAttributesAvailable) {
				final Optional<? extends AttributeSchemaContract> referenceAttribute =
					referenceSchema.getAttribute(attributeName);
				if (referenceAttribute.isPresent()) {
					return referenceAttribute.get().getIndexedDecimalPlaces();
				}
			}
		}
		// entity-level attribute (a GLOBAL index, or an entity-attribute copy held by a reference index)
		return entitySchema.getAttribute(attributeName)
			.orElseThrow(() -> new GenericEvitaInternalError(
				"Attribute `" + attributeName + "`" +
					(referenceName == null ? "" : " of reference `" + referenceName + "` (nor at entity level)") +
					" is missing from entity schema `" + entitySchema.getName() + "`!"
			))
			.getIndexedDecimalPlaces();
	}

	/**
	 * Runs a `BigDecimal` re-key transform, translating the `ArithmeticException` that `NumberUtils.convertToInt` throws
	 * on an `int` overflow into a loud `GenericEvitaInternalError` carrying the catalog / attribute / value context.
	 * Because the migration aborts before the catalog header advances to protocol 6, the catalog stays at protocol 5 and
	 * the upgrade can be retried after the offending schema scale is corrected.
	 *
	 * @param transform         the pure scaling transform to run
	 * @param catalogVersion    the catalog version being migrated (for the error context)
	 * @param attributeIndexKey the attribute being re-keyed (for the error context)
	 * @param entityType        the entity type being migrated (for the error context)
	 * @return the transform result
	 */
	private static <T> T rekeyHistogramPoints(
		@Nonnull Supplier<T> transform,
		long catalogVersion,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nonnull String entityType
	) {
		try {
			return transform.get();
		} catch (ArithmeticException ex) {
			throw new GenericEvitaInternalError(
				"BigDecimal value of attribute `" + attributeIndexKey.attribute().attributeName() +
					"` (entity `" + entityType + "`, catalog version " + catalogVersion +
					") does not fit an int at the schema's decimal places during the v5 to v6 scaled-int re-key: " +
					ex.getMessage(),
				ex
			);
		}
	}

	/**
	 * Pure transform: re-keys a `BigDecimal` filter histogram's points to their order-preserving scaled `Integer`
	 * (`NumberUtils.convertToInt(value, indexedDecimalPlaces)`), merging points that collapse to the same scaled int
	 * (their record bitmaps unioned) and re-ordering the result under natural `Integer` order so it stays strictly
	 * monotone. A point whose value is already an `Integer` (a re-run) passes through unchanged, making the transform
	 * idempotent; only `BigDecimal` values are scaled.
	 *
	 * Exposed for deterministic offline testing of the scaling / merge / ordering logic, which the network-dependent
	 * end-to-end backward-compatibility test cannot exercise in a sandbox.
	 *
	 * @param points               the persisted histogram points (raw `BigDecimal` keys, ordered by natural order)
	 * @param indexedDecimalPlaces the attribute's decimal-places scale
	 * @return the re-keyed, merged, re-ordered points (scaled `Integer` keys)
	 */
	@Nonnull
	static ValueToRecordBitmap[] rekeyHistogramPointsToScaledInt(
		@Nonnull ValueToRecordBitmap[] points,
		int indexedDecimalPlaces
	) {
		final TreeMap<Integer, RoaringBitmapWriter<PersistentRoaringBitmap>> mergedByScaledInt = new TreeMap<>();
		for (final ValueToRecordBitmap point : points) {
			final Serializable rawValue = point.getValue();
			// idempotent guard: an already-scaled Integer (re-run) keeps its key; only a real BigDecimal is scaled
			final int scaledKey;
			if (rawValue instanceof BigDecimal bd) {
				scaledKey = NumberUtils.convertToInt(bd, indexedDecimalPlaces);
			} else if (rawValue instanceof Integer alreadyScaled) {
				scaledKey = alreadyScaled;
			} else {
				throw new GenericEvitaInternalError(
					"Filter histogram point under a BigDecimal-typed attribute carries a value `" + rawValue +
						"` of unexpected type " + rawValue.getClass().getName() + "; " +
						"the v5 to v6 scaled-int re-key cannot proceed."
				);
			}
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = mergedByScaledInt.computeIfAbsent(
				scaledKey, __ -> RoaringBitmapBackedBitmap.buildWriter()
			);
			final OfInt recordIterator = point.getRecordIds().iterator();
			while (recordIterator.hasNext()) {
				writer.add(recordIterator.nextInt());
			}
		}

		final ValueToRecordBitmap[] rekeyedPoints = new ValueToRecordBitmap[mergedByScaledInt.size()];
		int i = 0;
		for (final Entry<Integer, RoaringBitmapWriter<PersistentRoaringBitmap>> entry : mergedByScaledInt.entrySet()) {
			rekeyedPoints[i++] = new ValueToRecordBitmap(
				entry.getKey(),
				new BaseBitmap(entry.getValue().get())
			);
		}
		return rekeyedPoints;
	}

	/**
	 * Pure transform: re-keys a sort-only `BigDecimal` index's distinct value side to scaled `Integer`. Re-scales every
	 * `sortedRecordValues` entry (`NumberUtils.convertToInt` for a real `BigDecimal`, an already-scaled `Integer` passing
	 * through for idempotency) and re-buckets `valueCardinalities`, summing the counts of values that collapse to the
	 * same scaled int. The result's value array is the distinct scaled ints in natural order, paralleling the unchanged
	 * `sortedRecords` blocks; the cardinality map carries only the entries whose summed count is greater than one (the
	 * "cardinality 1 is implied" storage convention).
	 *
	 * Exposed for deterministic offline testing alongside the filter transform.
	 *
	 * @param sortedRecordValues   the persisted distinct sort values (raw `BigDecimal`, natural order)
	 * @param valueCardinalities   counts for values shared by more than one record (keyed by the raw values)
	 * @param indexedDecimalPlaces the attribute's decimal-places scale
	 * @return the re-scaled distinct values + re-bucketed cardinalities
	 */
	@Nonnull
	static ScaledSortValues rekeySortedValuesToScaledInt(
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> valueCardinalities,
		int indexedDecimalPlaces
	) {
		// insertion order = the natural scaled-int order, because the input values are already sorted and scaling is
		// monotone; a LinkedHashMap preserves that order while summing cardinalities of colliding values
		final LinkedHashMap<Integer, Integer> cardinalityByScaledInt = new LinkedHashMap<>(sortedRecordValues.length);
		for (final Serializable rawValue : sortedRecordValues) {
			final int scaledKey;
			if (rawValue instanceof BigDecimal bd) {
				scaledKey = NumberUtils.convertToInt(bd, indexedDecimalPlaces);
			} else if (rawValue instanceof Integer alreadyScaled) {
				scaledKey = alreadyScaled;
			} else {
				throw new GenericEvitaInternalError(
					"Sort value under a BigDecimal-typed attribute carries a value `" + rawValue +
						"` of unexpected type " + (rawValue == null ? "null" : rawValue.getClass().getName()) +
						"; the v5 to v6 scaled-int re-key cannot proceed."
				);
			}
			final int cardinality = valueCardinalities.getOrDefault(rawValue, 1);
			cardinalityByScaledInt.merge(scaledKey, cardinality, Integer::sum);
		}

		final Serializable[] rekeyedValues = new Serializable[cardinalityByScaledInt.size()];
		final Map<Serializable, Integer> rekeyedCardinalities =
			CollectionUtils.createHashMap(cardinalityByScaledInt.size());
		int i = 0;
		for (final Entry<Integer, Integer> entry : cardinalityByScaledInt.entrySet()) {
			rekeyedValues[i++] = entry.getKey();
			// keep the sparse "cardinality 1 is implied" convention: only values shared by more than one record are mapped
			if (entry.getValue() > 1) {
				rekeyedCardinalities.put(entry.getKey(), entry.getValue());
			}
		}
		return new ScaledSortValues(rekeyedValues, rekeyedCardinalities);
	}

	/**
	 * Carrier for the re-scaled sort value side produced by {@link #rekeySortedValuesToScaledInt}.
	 *
	 * @param sortedRecordValues the distinct scaled `Integer` sort values in natural order
	 * @param valueCardinalities counts for scaled values shared by more than one record (cardinality `1` implicit)
	 */
	record ScaledSortValues(
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> valueCardinalities
	) {
	}

	/**
	 * Pure transform behind {@link #rekeyFilterIndex}: re-keys a filter histogram's points to Unicode NFD, merging
	 * points that collide under NFD (their record bitmaps unioned) and re-sorting the result under the supplied
	 * comparator so it stays strictly monotone. Returns {@code null} when nothing changes — every key is already its own
	 * NFD form (e.g. ASCII) and no two keys collapse into one bucket — so the caller can skip rewriting the part.
	 *
	 * Exposed for deterministic offline testing of the delicate normalization / merge / ordering logic, which the
	 * network-dependent end-to-end backward-compatibility test cannot exercise in a sandbox.
	 *
	 * @param points     the persisted histogram points (already ordered by `comparator`, keys raw/pre-NFD)
	 * @param comparator the index's value comparator (a localized collator or natural order)
	 * @return the re-keyed, merged, re-sorted points, or {@code null} when the input is already canonical
	 */
	@Nullable
	static ValueToRecordBitmap[] rekeyHistogramPointsToNfd(
		@Nonnull ValueToRecordBitmap[] points,
		@Nonnull Comparator<Serializable> comparator
	) {
		final TreeMap<Serializable, RoaringBitmapWriter<PersistentRoaringBitmap>> mergedByNfd = new TreeMap<>(comparator);
		boolean anyKeyChanged = false;
		for (final ValueToRecordBitmap point : points) {
			final Serializable rawValue = point.getValue();
			// the caller gates this transform to String-typed filter parts, so every persisted key must be a String;
			// a non-String here would be silently mis-keyed by String.valueOf, so fail fast instead of coercing
			if (!(rawValue instanceof String rawString)) {
				throw new GenericEvitaInternalError(
					"Filter histogram point under a String-typed attribute carries a non-String value `" +
						rawValue + "` (type " + rawValue.getClass().getName() + ");" +
						" the v5 to v6 NFD re-key cannot proceed."
				);
			}
			final String nfdValue = Normalizer.normalize(rawString, Normalizer.Form.NFD);
			if (!nfdValue.equals(rawString)) {
				anyKeyChanged = true;
			}
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = mergedByNfd.computeIfAbsent(
				nfdValue, __ -> RoaringBitmapBackedBitmap.buildWriter()
			);
			final OfInt recordIterator = point.getRecordIds().iterator();
			while (recordIterator.hasNext()) {
				writer.add(recordIterator.nextInt());
			}
		}

		// nothing to do when NFD is the identity on every key AND no two keys collapsed into one bucket
		if (!anyKeyChanged && mergedByNfd.size() == points.length) {
			return null;
		}

		final ValueToRecordBitmap[] rekeyedPoints = new ValueToRecordBitmap[mergedByNfd.size()];
		int i = 0;
		for (final Entry<Serializable, RoaringBitmapWriter<PersistentRoaringBitmap>> entry : mergedByNfd.entrySet()) {
			rekeyedPoints[i++] = new ValueToRecordBitmap(
				entry.getKey(),
				new BaseBitmap(entry.getValue().get())
			);
		}
		return rekeyedPoints;
	}

}
