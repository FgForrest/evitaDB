/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.cardinality;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.function.Function;

/**
 * Represents a cardinality index that stores the cardinalities of keys.
 * The index allows adding and removing keys, and retrieving the cardinalities of all keys.
 *
 * The index allows us to track the number of occurrences of a key in indexes that allow multiple occurrences of
 * the record in the index. In order to correctly remove the key from the index, we need to know how many times
 * the key is present in the index and remove it only when the last occurrence is evicted. This is where the cardinality
 * index comes in.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class AttributeCardinalityIndex
	implements VoidTransactionMemoryProducer<AttributeCardinalityIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = -7416602590381722682L;
	/**
	 * Represents the type of values stored in this cardinality index.
	 */
	@Getter private final Class<? extends Serializable> valueType;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * A variable that holds the cardinalities of different entities.
	 *
	 * The {@link PersistentTransactionalMap} is a map-like data structure that allows concurrent access and
	 * modification of the cardinalities in a transactional manner. Each cardinality is associated with a
	 * AttributeCardinalityKey, which uniquely identifies the entity for which the cardinality is being stored.
	 * The map is plain-valued and mutated exclusively through `compute`/`computeIfPresent`/`remove`, so commit
	 * folds only the changed keys onto the persistent snapshot in `O(Δ·log N)` instead of rebuilding the whole
	 * map on every transaction.
	 */
	private final PersistentTransactionalMap<AttributeCardinalityKey, Integer> cardinalities;
	/**
	 * Canonicalizer for incoming keys, built on first use by {@link #normalizeKey(Serializable, int)} and kept for
	 * the life of this index — see that method for why the key must be canonical at all.
	 *
	 * Cached rather than built per call because {@link FilterIndex#getNormalizer(Class, int)} returns a *capturing*
	 * lambda for the `BigDecimal` and `BigDecimalNumberRange` branches (it closes over the scale), so building one
	 * per write would allocate on the hottest indexing path — once per reference attribute per upsert, and twice
	 * for an update, which does a remove followed by an insert. Every other branch hands back a non-capturing
	 * singleton and costs nothing either way.
	 *
	 * Transient: a normalizer is derived state, rebuilt on demand after a reload or a transactional copy.
	 */
	@SuppressWarnings("TransientFieldNotInitialized")
	@Nullable private transient CachedNormalizer normalizer;

	public AttributeCardinalityIndex(@Nonnull Class<? extends Serializable> valueType) {
		this.valueType = valueType;
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new PersistentTransactionalMap<>(CollectionUtils.createHashMap(16));
	}

	public AttributeCardinalityIndex(
		@Nonnull Class<? extends Serializable> valueType,
		@Nonnull Map<AttributeCardinalityKey, Integer> cardinalities
	) {
		this.valueType = valueType;
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new PersistentTransactionalMap<>(cardinalities);
	}

	/**
	 * Canonicalizes an attribute value into the key form this index must count it under. Every caller of
	 * {@link #addRecord} and {@link #removeRecord} is required to pass the result rather than the raw value.
	 *
	 * # Why the counter may not key on the raw value
	 *
	 * This index exists to ref-count how many owners contribute the *same* entry to a shared filter index, so the
	 * entry is dropped only when the last of them goes away. That only works while this index and the value tree
	 * agree on what "the same entry" means — and the tree does not key on the raw value: it keys on
	 * {@link FilterIndex#getNormalizer(Class, int)}'s output, which is deliberately **many-to-one** for several
	 * types (a `BigDecimal` collapses to a scaled `int`, a `String` to its Unicode NFD form, an `OffsetDateTime`
	 * to a millisecond `Instant` that discards the offset entirely). Two owners holding values that differ only
	 * below that resolution would otherwise occupy two counter keys but a single tree entry, and the first of them
	 * to move away would drain its own counter to zero, report `BOUNDARY_CROSSED`, and remove the entry the other
	 * owner still needs — silently unindexing it, and making that owner's own next write fail the value tree's
	 * `Sanity check - record not found!` premise from then on.
	 *
	 * Canonicalizing here closes that gap by construction: colliding values share one counter key, so the entry
	 * survives until the last contributor leaves. The normalizer is idempotent, so a value that already arrives
	 * canonical passes through unchanged.
	 *
	 * # What happens if the caller's scale has drifted from the tree's
	 *
	 * `indexedDecimalPlaces` comes from the live schema, while the tree's keys were encoded at the scale frozen
	 * into its own storage part. Those two can in principle diverge — `ModifyAttributeSchemaTypeMutation` carries
	 * `indexedDecimalPlaces`, so a schema change can move it.
	 *
	 * Note the guard that exists for this, {@link FilterIndex#assertIndexedDecimalPlacesUnchanged(int, int,
	 * String)}, does NOT protect this method: it runs inside the tree write, which happens only after the counter
	 * has already been mutated and only when the counter reports `BOUNDARY_CROSSED`. The counter is the first
	 * structure a drifted write touches, not the last.
	 *
	 * What makes drift non-silent is cruder and more reliable: a changed scale changes every non-zero key, so an
	 * insert finds no existing count and reports `BOUNDARY_CROSSED` (carrying the write on into the tree, where
	 * the guard does fire), and a removal finds no key at all and throws `Cardinality … is null`. Either way it
	 * fails loudly at the first drifted write rather than quietly miscounting. Do not restate this as "the two
	 * provably agree" — they are not proven equal here, they are proven not to diverge silently.
	 *
	 * @param value                the raw attribute value
	 * @param indexedDecimalPlaces the attribute's scale; consulted only when the normalizer is (re)built
	 * @return the canonical key to count this value under
	 */
	@Nonnull
	public Serializable normalizeKey(@Nonnull Serializable value, int indexedDecimalPlaces) {
		// read the pair through ONE reference so a reader can never combine a function built for one scale with
		// another scale's guard - the two were separate fields first, which left exactly that race open
		CachedNormalizer cached = this.normalizer;
		if (cached == null || cached.scale() != indexedDecimalPlaces) {
			cached = new CachedNormalizer(
				indexedDecimalPlaces,
				FilterIndex.getNormalizer(this.valueType, indexedDecimalPlaces)
			);
			// a benign race: two threads may each build an equivalent holder and one write wins. Both are correct
			// for the same scale, and the record's final fields make even an unsafely-published one fully visible
			this.normalizer = cached;
		}
		return cached.function().apply(value);
	}

	/**
	 * Returns cardinalities of all keys in the index.
	 * @return cardinalities of all keys in the index
	 */
	@Nonnull
	public Map<AttributeCardinalityKey, Integer> getCardinalities() {
		return this.cardinalities;
	}

	/**
	 * Increases cardinality of the given value by one. If the value was not present in the index before
	 * this call, it is added with cardinality 1 and `BOUNDARY_CROSSED` is returned so callers can
	 * propagate the new entry to downstream membership-only indexes. Otherwise the existing cardinality
	 * is incremented and `NO_BOUNDARY_CROSSING` is returned.
	 *
	 * @param value    value whose cardinality should be incremented
	 * @param recordId identifier of the owning record (cardinality is tracked per record)
	 * @return `BOUNDARY_CROSSED` if the cardinality went from 0 to 1, `NO_BOUNDARY_CROSSING` otherwise
	 */
	@Nonnull
	public CardinalityChange addRecord(@Nonnull Serializable value, int recordId) {
		assertValueCompatible(value);
		this.dirty.setToTrue();
		final int newCardinality = this.cardinalities.compute(
			new AttributeCardinalityKey(recordId, value),
			(k, v) -> v == null ? 1 : v + 1
		);
		return newCardinality == 1 ? CardinalityChange.BOUNDARY_CROSSED : CardinalityChange.NO_BOUNDARY_CROSSING;
	}

	/**
	 * Decreases cardinality of the given value by one. If the cardinality reaches zero the value is
	 * removed from the index and `BOUNDARY_CROSSED` is returned so callers can propagate the removal
	 * to downstream membership-only indexes. Otherwise the cardinality is decremented and
	 * `NO_BOUNDARY_CROSSING` is returned.
	 *
	 * @param value    value whose cardinality should be decremented
	 * @param recordId identifier of the owning record (cardinality is tracked per record)
	 * @return `BOUNDARY_CROSSED` if the cardinality dropped to 0, `NO_BOUNDARY_CROSSING` otherwise
	 */
	@Nonnull
	public CardinalityChange removeRecord(@Nonnull Serializable value, int recordId) {
		assertValueCompatible(value);
		this.dirty.setToTrue();
		final AttributeCardinalityKey cardinalityKey = new AttributeCardinalityKey(recordId, value);
		final Integer newValue = this.cardinalities.computeIfPresent(
			cardinalityKey,
			(k, v) -> v - 1
		);
		if (newValue == null) {
			throw new GenericEvitaInternalError("Cardinality of value `" + value + "` for record `" + recordId + "` is null");
		} else if (newValue == 0) {
			this.cardinalities.remove(cardinalityKey);
			return CardinalityChange.BOUNDARY_CROSSED;
		} else {
			return CardinalityChange.NO_BOUNDARY_CROSSING;
		}
	}

	/**
	 * Verifies that `value` is storable in this index. A value is compatible when it is an instance of the declared
	 * {@link #valueType}, or of the type that {@link FilterIndex#getNormalizer(Class, int)} encodes that declared
	 * type into — a scaled `Integer` for a `BigDecimal`, an `Instant` for either date-time type, a
	 * {@link io.evitadb.dataType.ComparableCurrency} / {@link io.evitadb.dataType.ComparableLocale} for their
	 * unordered originals.
	 *
	 * Both forms are admitted because callers are *required* to hand over the normalized key — the counter and the
	 * shared value tree must agree on what "the same entry" means, or a ref-count reaches zero while an entry is
	 * still needed (see `EntityIndex#cardinalityKeyNormalizer`) — while the normalizer's idempotence means a value
	 * that was already canonical arrives unchanged and must stay acceptable.
	 *
	 * @param value the value to validate
	 */
	private void assertValueCompatible(@Nonnull Serializable value) {
		Assert.isTrue(
			this.valueType.isInstance(value) ||
				FilterIndex.getNormalizedKeyType(this.valueType).isInstance(value),
			"Value of type `" + value.getClass() + "` is not compatible with this index that accepts only values of type `" + this.valueType + "`!"
		);
	}

	/**
	 * Returns TRUE if this contains no data.
	 * @return TRUE if this contains no data
	 */
	public boolean isEmpty() {
		return this.cardinalities.isEmpty();
	}

	/**
	 * Returns `true` if the index contents have been modified and need persistence.
	 */
	public boolean isDirty() {
		return this.dirty.isTrue();
	}

	/**
	 * Method creates container for storing chain index from memory to the persistent storage.
	 */
	@Nullable
	public AttributeCardinalityIndexStoragePart createStoragePart(int entityIndexPrimaryKey, @Nonnull AttributeIndexKey attribute) {
		if (this.dirty.isTrue()) {
			return new AttributeCardinalityIndexStoragePart(
				entityIndexPrimaryKey, attribute, this
			);
		} else {
			return null;
		}
	}

	/**
	 * Returns the heap this index occupies, in bytes — its own object, its dirty flag and the cardinality map with
	 * every key and boxed count it holds.
	 *
	 * # What is charged, and what is not
	 *
	 * Each {@link AttributeCardinalityKey} is charged **in full, including its value payload**. The value arrives as
	 * the caller's reference, so it could in principle be the same instance the sibling filter index was handed — but
	 * only a {@link io.evitadb.index.bPlusTree.BoxedObjectColumn} actually retains one, and the front-coded and
	 * primitive columns every common attribute type lands in copy the value out and keep nothing. Charging is
	 * therefore both the usual case and the higher of the two defensible figures.
	 *
	 * {@link #valueType} addresses a {@link Class}, which the JVM owns for the lifetime of its class loader.
	 *
	 * Walking the map is `O(entries)` rather than `O(1)`, so this belongs to the index detail call and must never be
	 * called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		final long keyShell = layout.sizeOfObject(Integer.BYTES + layout.referenceSize());
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		// the valueType / dirty / cardinalities / normalizer slots
		return layout.sizeOfObject(4L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			// the cached normalizer is built lazily on the first normalizeKey and is absent until then; when it is
			// there it costs its own record shell (a scale plus a reference) and the function instance the record
			// points at. A non-capturing branch of `FilterIndex#getNormalizer` hands out a shared singleton, so
			// charging it to every holder over-reports - the conservative direction this estimate is required to err in
			+ (this.normalizer == null ?
				0L :
				layout.sizeOfObject(Integer.BYTES + layout.referenceSize()) + layout.sizeOfObject(Integer.BYTES))
			+ this.cardinalities.getHeapSizeInBytes(
				key -> keyShell + IndexHeapSize.OWNED_KEY_SIZER.applyAsLong(key.value()),
				cardinality -> boxedInteger
			);
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.cardinalities.removeLayer(transactionalLayer);
		this.dirty.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public AttributeCardinalityIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			return new AttributeCardinalityIndex(
				this.valueType,
				transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalities)
			);
		} else {
			return this;
		}
	}

	/**
	 * The canonicalizer for incoming keys, paired with the scale it was built at.
	 *
	 * Held as one immutable reference rather than two fields so {@link #normalizeKey(Serializable, int)} reads a
	 * consistent pair without synchronization: a single reference write is atomic, and the record's final fields
	 * are safely visible even when it is published through a data race.
	 *
	 * @param scale    the `indexedDecimalPlaces` this function encodes at
	 * @param function the canonicalizer itself
	 */
	private record CachedNormalizer(
		int scale,
		@Nonnull Function<Object, Serializable> function
	) {
	}

	/**
	 * Represents a key used to uniquely identify a record and its associated value.
	 *
	 * @param recordId ID of the record
	 * @param value value of the record
	 */
	public record AttributeCardinalityKey(
		int recordId,
		@Nonnull Serializable value
	) {

		@Nonnull
		@Override
		public String toString() {
			return String.valueOf(this.recordId) + ':' + this.value;
		}
	}

}
