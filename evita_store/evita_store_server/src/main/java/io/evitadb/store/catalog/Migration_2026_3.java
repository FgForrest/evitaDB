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

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
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
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Upgrades a catalog from storage protocol version 6 to 7 by re-keying every persisted
 * {@link AttributeCardinalityIndex} onto the canonical form its sibling value tree keys on.
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
 * @author Claude (defect A investigation), FG Forrest a.s. (c) 2026
 * @deprecated removable once no catalog older than 2026.3 can be encountered — at which point no catalog can
 * still carry raw-keyed cardinality counters, and neither this migration nor
 * `AttributeCardinalityIndexStoragePartSerializer_2026_2` has anything left to do
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
	 * Re-keys every persisted attribute cardinality index of every collection onto its normalized form and
	 * commits the catalog header at protocol version 7.
	 *
	 * @param catalogHeader                             the catalog header being upgraded
	 * @param storagePartPersistenceService             the catalog-level storage part service
	 * @param entityCollectionPersistenceServiceFactory builds a persistence service for one collection
	 * @param postUpgradeAction                         commits the rewritten catalog header
	 */
	static void upgradeFromStorageProtocolVersion_6_to_7(
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService,
		@Nonnull Function<EntityCollectionFileHeader, DefaultEntityCollectionPersistenceService> entityCollectionPersistenceServiceFactory,
		@Nonnull Consumer<CatalogHeader<LogFileRecordReference, CollectionFileReference>> postUpgradeAction
	) {
		ConsoleWriter.writeLine(
			"Catalog `" + catalogHeader.catalogName() + "` uses storage protocol version 6; re-keying attribute " +
				"cardinality indexes to their canonical form (protocol 7).",
			ConsoleColor.BRIGHT_BLUE
		);

		final long catalogVersion = catalogHeader.version();
		final Collection<CollectionFileReference> entityTypeFileIndexes = catalogHeader.getEntityTypeFileIndexes();
		final HashMap<String, CollectionFileReference> newCollectionFileIndex =
			CollectionUtils.createHashMap(entityTypeFileIndexes.size());

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
				entityCollectionPersistenceServiceFactory.apply(entityCollectionHeader)
			);
			final OffsetIndexStoragePartPersistenceService collectionStoragePartService =
				collectionPersistenceService.getStoragePartPersistenceService();

			// the entity schema supplies the per-attribute `indexedDecimalPlaces`; it is always stored under part id 1
			final EntitySchema entitySchema = Objects.requireNonNull(
				collectionStoragePartService.getStoragePart(catalogVersion, 1, EntitySchemaStoragePart.class),
				"Entity schema storage part is missing for collection `" + entityCollectionHeader.entityType() + "`!"
			).entitySchema();

			final Set<Integer> indexIds = new HashSet<>(entityCollectionHeader.usedEntityIndexPrimaryKeys());
			if (entityCollectionHeader.globalEntityIndexPrimaryKey() != null) {
				indexIds.add(entityCollectionHeader.globalEntityIndexPrimaryKey());
			}

			int rekeyedCounters = 0;
			int orphanedPairs = 0;
			final List<String> damagedAttributes = new ArrayList<>();
			final List<String> uncheckedAttributes = new ArrayList<>();
			for (final Integer indexPrimaryKey : indexIds) {
				final EntityIndexStoragePart indexPart = collectionStoragePartService.getStoragePart(
					catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
				);
				if (indexPart == null) {
					throw new GenericEvitaInternalError(
						"Entity index storage part for primary key " + indexPrimaryKey + " is missing!"
					);
				}
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

			if (rekeyedCounters > 0) {
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
					"Entity collection `" + entityCollectionHeader.entityType() + "`: re-keyed " + rekeyedCounters +
						" attribute cardinality index(es).",
					ConsoleColor.BRIGHT_BLUE
				);
			} else {
				// nothing to do - every counter of this collection was already canonical
				newCollectionFileIndex.put(entityTypeFileIndex.entityType(), entityTypeFileIndex);
			}

			reportPreExistingDamage(
				catalogHeader.catalogName(), entityCollectionHeader.entityType(),
				damagedAttributes, orphanedPairs, uncheckedAttributes
			);
		}

		postUpgradeAction.accept(
			new CatalogHeader<>(
				7,
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
			"Catalog `" + catalogHeader.catalogName() + "` was successfully upgraded to the protocol version 7.",
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
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(
			plainType, indexedDecimalPlaces.getAsInt()
		);

		final Map<AttributeCardinalityKey, Integer> rekeyed = rekeyCardinalities(
			cardinalityIndex.getCardinalities(), normalizer
		);
		final int orphanedPairs = countOrphanedCounters(
			rekeyed == null ? cardinalityIndex.getCardinalities() : rekeyed, filterPart, plainType
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
	 * 2. **The sibling FILTER part's FROZEN scale** — authoritative rather than merely available: it is by
	 *    construction the scale the shared value tree's keys were encoded at, and the counter's whole purpose is to
	 *    agree with that tree. It also survives a schema that has since changed.
	 * 3. **The entity schema** — the fallback for a counter with no surviving sibling filter part.
	 * 4. **Empty** — caller leaves the part untouched and says so.
	 *
	 * @param filterPart the sibling filter index, already read by {@link #readSiblingFilterIndex}, or `null` when
	 *                   this counter has none
	 * @return the scale to normalize at, or empty when it cannot be established for a type that needs one
	 */
	@Nonnull
	private static OptionalInt resolveScale(
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
	 * **A type that CAN collide but whose stored key space is not reconstructible here is reported as not checked**
	 * ({@link #isAuditable}). `OffsetDateTime` is that case and the reason the two predicates are not one:
	 * 2026.2 normalized it with a bare `toInstant()`, which already discards the offset and is therefore
	 * many-to-one, so a protocol-6 catalog genuinely can carry temporal damage — but the audit still cannot see it.
	 * Today's normalizer additionally truncates to milliseconds, and the truncation of what is already on disk
	 * happens at LOAD rather than in storage (`2026-09-04-millisecond-temporal-precision`), which this migration
	 * bypasses by reading storage parts directly; worse, a catalog that reached protocol 6 by upgrade from 2026.1
	 * can hold temporal buckets that are not `Instant` at all, re-anchored only on load by
	 * `FilterIndexStoragePartSerializer_2026_1`. There is thus no single key space both sides can be expressed in
	 * from here, and a comparison across two of them would report healthy counters as damaged. Telling an operator
	 * to reindex on a false alarm is worse than telling them nothing — so it says, explicitly, that it did not look.
	 *
	 * **A PAGED filter index is not checked** but is reported as such. Its buckets live in separate
	 * `FilterIndexLeafPagePart` records rather than in {@link FilterIndexStoragePart#getHistogramPoints()}, so
	 * answering the same question there is a separate piece of work. Note that {@link Migration_2026_2}'s silence
	 * about paging is NOT a precedent for silence here: paging does not exist in 2026.1, which wrote every
	 * protocol-5 catalog, but it does in 2026.2, which wrote every protocol-6 one.
	 *
	 * @param canonicalCounters the counter map AFTER re-keying — the state the repaired counter is actually in
	 * @param filterPart        the sibling value tree, or `null` when the counter has none
	 * @param plainType         the attribute's plain (non-array) type
	 * @return the number of `(record, value)` pairs whose entry is missing, or {@link #NOT_VERIFIABLE}
	 */
	private static int countOrphanedCounters(
		@Nonnull Map<AttributeCardinalityKey, Integer> canonicalCounters,
		@Nullable FilterIndexStoragePart filterPart,
		@Nonnull Class<?> plainType
	) {
		if (!isCollisionProne(plainType)) {
			return 0;
		}
		if (!isAuditable(plainType) || filterPart == null || filterPart.isPaged()) {
			return NOT_VERIFIABLE;
		}
		final ValueToRecordBitmap[] points = filterPart.getHistogramPoints();
		final Map<Serializable, Bitmap> buckets = CollectionUtils.createHashMap(points.length);
		for (final ValueToRecordBitmap point : points) {
			buckets.put(point.getValue(), point.getRecordIds());
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
	 * when it was. The three causes are materially different (a type the audit cannot express, an index shape it
	 * cannot walk, and an index that is not there at all) and a message that lists all three every time tells the
	 * reader nothing about the case in front of them.
	 */
	@Nullable
	private static String whyNotChecked(@Nonnull Class<?> plainType, @Nullable FilterIndexStoragePart filterPart) {
		if (!isCollisionProne(plainType)) {
			return null;
		} else if (!isAuditable(plainType)) {
			return "offset-bearing timestamps have no key space this migration can compare in";
		} else if (filterPart == null) {
			return "no sibling filter index";
		} else if (filterPart.isPaged()) {
			return "filter index is stored in paged form";
		} else {
			return null;
		}
	}

	/**
	 * Whether a counter of `plainType` can be compared with its stored tree entries at all — that is, whether the
	 * normalizer this migration applies produces the very keys the part on disk was written with.
	 *
	 * True for the three types whose normalizer is byte-for-byte what 2026.2 applied, so both sides of the
	 * comparison land in one key space. False for `OffsetDateTime`, which {@link #isCollisionProne} admits and
	 * this deliberately does not — {@link #countOrphanedCounters} carries the reasoning, and the caller reports
	 * the difference as "not checked" rather than as clean.
	 */
	private static boolean isAuditable(@Nonnull Class<?> plainType) {
		return String.class.isAssignableFrom(plainType)
			|| BigDecimal.class.isAssignableFrom(plainType)
			|| BigDecimalNumberRange.class.isAssignableFrom(plainType);
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
	 */
	@Nonnull
	private static String formatAttributeList(@Nonnull List<String> attributes) {
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
	private static String describeCounter(int indexPrimaryKey, @Nonnull AttributeIndexStorageKey attributeIndexKey) {
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

}
