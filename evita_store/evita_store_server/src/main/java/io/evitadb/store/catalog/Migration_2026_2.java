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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

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
 * This migration therefore performs exactly one transformation: every `FilterIndexStoragePart` whose attribute type is
 * `String` (or `String[]`) has its histogram points re-keyed to NFD, with buckets that collide under NFD merged (their
 * record bitmaps unioned) and the whole point array re-sorted under the index's own comparator so it stays strictly
 * monotone (the invariant `InvertedIndex` asserts on load). ASCII-only parts are left untouched (NFD is the identity on
 * ASCII). Unique and sort storage parts are unchanged: uniqueness keeps its own standalone index, and the sort part's
 * value/cardinality section is redundant for view-mode parts, so it is ignored at load and dropped on the next flush.
 *
 * The shape follows {@link Migration_2025_6#upgradeFromStorageProtocolVersion_3_to_4} (the inline index-part rewrite
 * analogue), not the WAL-rewrite {@link Migration_2026_1}: per collection, re-key the affected filter parts, flush the
 * collection, then bump the catalog header to protocol 6. Crash-safety is inherited from the dispatch loop in
 * `DefaultCatalogPersistenceService.verifyAndUpgradeStorageFormat` — the header stays at 5 until the post-upgrade
 * action commits 6, so an interrupted upgrade simply re-runs.
 *
 * @deprecated removable once no catalog older than the 2026 release that introduced protocol 6 is in use.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
public interface Migration_2026_2 {

	/**
	 * Upgrades the catalog storage protocol version from version 5 to version 6 by re-keying every `String`-typed
	 * filter index histogram to Unicode NFD (merging NFD-colliding buckets and re-sorting under the index comparator).
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

			// every entity index of the collection (the reduced ones plus the global one)
			final Set<Integer> indexIds = new HashSet<>(entityCollectionHeader.usedEntityIndexPrimaryKeys());
			if (entityCollectionHeader.globalEntityIndexPrimaryKey() != null) {
				indexIds.add(entityCollectionHeader.globalEntityIndexPrimaryKey());
			}

			int rekeyedFilterIndexes = 0;
			for (final Integer indexPrimaryKey : indexIds) {
				final EntityIndexStoragePart indexPart = collectionStoragePartService.getStoragePart(
					catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
				);
				if (indexPart == null) {
					throw new GenericEvitaInternalError(
						"Entity index storage part for primary key " + indexPrimaryKey + " is missing!"
					);
				}
				for (final AttributeIndexStorageKey attributeIndexKey : indexPart.getAttributeIndexes()) {
					if (attributeIndexKey.indexType() == AttributeIndexType.FILTER
						&& rekeyFilterIndexToNfd(catalogVersion, indexPrimaryKey, attributeIndexKey, collectionStoragePartService)) {
						rekeyedFilterIndexes++;
					}
				}
			}

			if (rekeyedFilterIndexes > 0) {
				// at least one filter part of this collection changed - re-persist the collection and pick up its new location
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
					"Entity collection `" + entityCollectionHeader.entityType() + "`: re-keyed " + rekeyedFilterIndexes + " filter index(es) to NFD.",
					ConsoleColor.BRIGHT_BLUE
				);
			} else {
				// nothing changed (ASCII-only string keys, or no string filter parts) - keep the existing collection file
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
	 * Re-keys a single filter index's persisted histogram points to Unicode NFD when the attribute is `String`-typed.
	 * Points whose NFD form collides are merged (record bitmaps unioned) and the result is re-sorted under the index's
	 * own comparator so the persisted array stays strictly monotone. A part that does not change (ASCII-only keys, or a
	 * non-`String` type) is left untouched.
	 *
	 * @param catalogVersion              the catalog version being migrated
	 * @param indexPrimaryKey             the owning entity index primary key
	 * @param attributeIndexKey           the FILTER attribute storage key to re-key
	 * @param collectionStoragePartService persistence service of the owning entity collection
	 * @return {@code true} when the part was rewritten, {@code false} when it was left unchanged
	 */
	private static boolean rekeyFilterIndexToNfd(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nonnull OffsetIndexStoragePartPersistenceService collectionStoragePartService
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
		// only String keys changed normalization (raw -> NFD); every other type was already canonical in v5
		if (!String.class.isAssignableFrom(plainType)) {
			return false;
		}

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
	}

	/**
	 * Pure transform behind {@link #rekeyFilterIndexToNfd}: re-keys a filter histogram's points to Unicode NFD, merging
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
		final TreeMap<Serializable, RoaringBitmapWriter<RoaringBitmap>> mergedByNfd = new TreeMap<>(comparator);
		boolean anyKeyChanged = false;
		for (final ValueToRecordBitmap point : points) {
			final Serializable rawValue = point.getValue();
			// the caller gates this transform to String-typed filter parts, so every persisted key must be a String;
			// a non-String here would be silently mis-keyed by String.valueOf, so fail fast instead of coercing
			if (!(rawValue instanceof String)) {
				throw new GenericEvitaInternalError(
					"Filter histogram point under a String-typed attribute carries a non-String value `" +
						rawValue + "` (type " +
						(rawValue == null ? "null" : rawValue.getClass().getName()) +
						"); the v5 to v6 NFD re-key cannot proceed."
				);
			}
			final String rawString = (String) rawValue;
			final String nfdValue = Normalizer.normalize(rawString, Normalizer.Form.NFD);
			if (!nfdValue.equals(rawString)) {
				anyKeyChanged = true;
			}
			final RoaringBitmapWriter<RoaringBitmap> writer = mergedByNfd.computeIfAbsent(
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
		for (final Entry<Serializable, RoaringBitmapWriter<RoaringBitmap>> entry : mergedByNfd.entrySet()) {
			rekeyedPoints[i++] = new ValueToRecordBitmap(
				entry.getKey(),
				new BaseBitmap(entry.getValue().get())
			);
		}
		return rekeyedPoints;
	}

}
