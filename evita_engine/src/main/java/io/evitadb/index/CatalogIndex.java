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

package io.evitadb.index;

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges.ContainerChangesMemento;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex.CatalogIndexChanges;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;
import static io.evitadb.index.attribute.AttributeIndex.verifyLocalizedAttribute;
import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.ofNullable;

/**
 * This class represents main data structure that keeps all information connected with shared catalog data, that could
 * be used for searching, sorting or another computational task upon these data.
 *
 * There is **one instance per {@link Scope}**, not one per catalog: the `LIVE` one exists for the whole life of the
 * catalog, and the `ARCHIVED` one is created lazily the first time something globally unique is indexed in that scope
 * - see `Catalog#getCatalogIndex(Scope)`. Anything counting or iterating catalog indexes must go over the scopes;
 * treating "the catalog index" as a single object is how `Catalog#countIndexes` came to undercount.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CatalogIndex implements
	Index<CatalogIndexKey>, TransactionalLayerProducer<CatalogIndexChanges, CatalogIndex>,
	IndexDataStructure
{
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Type of the index.
	 */
	@Getter protected final CatalogIndexKey indexKey;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	protected final TransactionalBoolean dirty;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link UniqueIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeKey, GlobalUniqueIndex> uniqueIndex;
	/**
	 * Version of the entity index that gets increased with each atomic change in the index (incremented by one when
	 * transaction is committed and anything in this index was changed).
	 */
	@Getter private final int version;
	/**
	 * Query / update counters and last-activity stamps of this index — see {@link IndexActivity}.
	 *
	 * Threaded **by reference** through the reconstruction constructor, so both the commit-time merge copy and
	 * {@link #createShallowCopyWithResetDirtyFlag()} keep counting into the very same holder, while a reload from disk
	 * starts a fresh one. It is the one piece of state here that is neither transactional nor persisted.
	 */
	@Nonnull private final IndexActivity activity;

	public CatalogIndex(@Nonnull Scope scope) {
		this.version = 1;
		this.indexKey = new CatalogIndexKey(scope);
		this.dirty = new TransactionalBoolean();
		// a brand-new index has been neither queried nor updated yet
		this.activity = new IndexActivity();
		this.uniqueIndex = new TransactionalMap<>(new HashMap<>(), GlobalUniqueIndex.class, Function.identity());
	}

	/**
	 * Reconstructs a catalog index from persisted or committed state.
	 *
	 * @param version     version this index carries forward
	 * @param indexKey    the key identifying this index — a bare scope
	 * @param uniqueIndex the global unique indexes this index holds
	 * @param activity    the activity holder to keep counting into — the copied index's own instance on either copy
	 *                    path, a fresh one when loading from disk; see {@link IndexActivity}
	 */
	public CatalogIndex(
		int version,
		@Nonnull CatalogIndexKey indexKey,
		@Nonnull Map<AttributeKey, GlobalUniqueIndex> uniqueIndex,
		@Nonnull IndexActivity activity
	) {
		this.version = version;
		this.indexKey = indexKey;
		this.dirty = new TransactionalBoolean();
		this.activity = activity;
		this.uniqueIndex = new TransactionalMap<>(uniqueIndex, GlobalUniqueIndex.class, Function.identity());
	}

	@Nonnull
	@Override
	public IndexActivity getActivity() {
		return this.activity;
	}

	/**
	 * Produces a fresh {@link CatalogIndex} wrapper (fresh {@link #dirty} flag, fresh {@link #uniqueIndex} map) that
	 * shares the very same {@link GlobalUniqueIndex} child instances. Global unique indexes hold no back-reference to
	 * anything version-scoped (their entity-type resolution is supplied per call by an
	 * {@link EntityTypeClassifierResolver}), so there is nothing per-version to detach in a child — reusing the same
	 * instances is safe.
	 *
	 * The **fresh `dirty` flag is the reason this method cannot be replaced by forwarding the original index.** Outside
	 * a transaction — i.e. while the catalog is still warming up — {@link #dirty} is written straight into the base
	 * value, and no production code path ever calls {@link #resetDirty()}: the flag is a latch for the lifetime of the
	 * instance. Both callers (going live and catalog rename) run right after the catalog has been flushed, so the latch
	 * is stale by then, yet forwarding the same instance would carry it into the new catalog version and spuriously bump
	 * {@link #version} on the first commit that follows. Every other producer of a `CatalogIndex` — this method,
	 * {@link #createCopyWithMergedTransactionalMemory(CatalogIndexChanges, TransactionalLayerMaintainer)} on the
	 * transactional path, and loading from disk — starts from a fresh flag.
	 *
	 * @return the fresh wrapper sharing the same child global unique indexes
	 */
	@Nonnull
	public CatalogIndex createShallowCopyWithResetDirtyFlag() {
		// `forEach` into a pre-sized map, never `entrySet()`: asking a `HashMap` for a view parks it on the map for
		// the lifetime of the index - see `documentation/developer/heap-size-testing.md`, trap 6
		final Map<AttributeKey, GlobalUniqueIndex> copy = CollectionUtils.createHashMap(this.uniqueIndex.size());
		this.uniqueIndex.forEach(copy::put);
		// the activity holder travels by reference, exactly as on the transactional copy: going live and renaming a
		// catalog both carry the same logical index forward, and neither is a catalog load
		return new CatalogIndex(this.version, this.indexKey, copy, this.activity);
	}

	@Override
	public void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		if (this.dirty.isTrue()) {
			trappedChanges.addChangeToStore(createStoragePart());
		}
		// `forEach`, never `entrySet()`: a `HashMap` keeps the view it hands out, and this runs on the flush path -
		// see `documentation/developer/heap-size-testing.md`, trap 6
		this.uniqueIndex.forEach((attributeKey, uniqueIndex) ->
			// granular flush: a PAGED index emits its changed leaf pages + freed-page removals + a paged root; a SINGLE
			// index emits the inline root (and collapse removals if it just shrank from PAGED). See
			// GlobalUniqueIndex#appendStorageParts.
			uniqueIndex.appendStorageParts(attributeKey, trappedChanges)
		);
	}

	/**
	 * Estimates the heap this catalog index occupies, in bytes.
	 *
	 * The index owns its key, its dirty latch and the map of {@link GlobalUniqueIndex} instances - each of which
	 * prices itself. {@link #indexKey} is charged as the record object alone, because the {@link Scope} it holds is an
	 * enum constant shared by the whole JVM; the map keys likewise, because an {@link AttributeKey}'s name comes from
	 * the catalog schema and its locale is JVM-interned, so only the key record itself belongs to this index.
	 *
	 * Walking a global unique index's value tree is `O(values / blockSize)`, so this belongs to the index detail call
	 * rather than to anything polled.
	 *
	 * {@link #activity} is charged in full even though it is shared with the superseded versions of this same logical
	 * index - only one version is ever walked, and the predecessor is garbage-in-waiting, so reporting the four longs
	 * as shared would show them belonging to nobody (accounting rule 2).
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// id and version, then the indexKey / dirty / uniqueIndex / activity slots
		final long attributeKey = layout.sizeOfObject(2L * layout.referenceSize());
		return layout.sizeOfObject(Long.BYTES + Integer.BYTES + 4L * layout.referenceSize())
			// the key record holds a single reference, to a JVM-shared enum constant
			+ layout.sizeOfObject(layout.referenceSize())
			// the activity holder: four longs and nothing else, since its CAS updaters are static
			+ layout.sizeOfObject(4L * Long.BYTES)
			+ this.dirty.getHeapSizeInBytes()
			+ this.uniqueIndex.getHeapSizeInBytes(
				key -> attributeKey, GlobalUniqueIndex::getHeapSizeInBytes
			);
	}

	/**
	 * Method inserts new unique attribute to the index.
	 *
	 * @throws UniqueValueViolationException when value is not unique
	 */
	public void insertUniqueAttribute(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalAttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId,
		@Nonnull EntityTypeClassifierResolver resolver
	) {
		final GlobalUniqueIndex theUniqueIndex = this.uniqueIndex.computeIfAbsent(
			createAttributeKey(attributeSchema, allowedLocales, getIndexKey().scope(), locale, value),
			lookupKey -> {
				final GlobalUniqueIndex newUniqueIndex = new GlobalUniqueIndex(
					this.getIndexKey().scope(), lookupKey, attributeSchema.getType()
				);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newUniqueIndex));
				this.dirty.setToTrue();
				return newUniqueIndex;
			}
		);
		theUniqueIndex.registerUniqueKey(value, entitySchema.getName(), locale, recordId, resolver);
	}

	/**
	 * Method removes existing unique attribute from the index.
	 *
	 * @throws IllegalArgumentException when passed value doesn't match the unique value associated with the record key
	 */
	public void removeUniqueAttribute(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalAttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId,
		@Nonnull EntityTypeClassifierResolver resolver
	) {
		final AttributeKey lookupKey = createAttributeKey(attributeSchema, allowedLocales, getIndexKey().scope(), locale, value);
		final GlobalUniqueIndex theUniqueIndex = this.uniqueIndex.get(lookupKey);
		notNull(theUniqueIndex, "Unique index for attribute `" + attributeSchema.getName() + "` not found!");
		theUniqueIndex.unregisterUniqueKey(value, entitySchema.getName(), locale, recordId, resolver);

		if (theUniqueIndex.isEmpty()) {
			Assert.isPremiseValid(theUniqueIndex == this.uniqueIndex.remove(lookupKey), "Expected unique index was not removed!");
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theUniqueIndex));
			this.dirty.setToTrue();
		}
	}

	/**
	 * Returns {@link GlobalUniqueIndex} for passed `attributeName`, if it's present.
	 */
	@Nullable
	public GlobalUniqueIndex getGlobalUniqueIndex(@Nonnull GlobalAttributeSchemaContract attributeSchema, @Nullable Locale locale) {
		final boolean uniqueGloballyWithinLocale = attributeSchema.isUniqueGloballyWithinLocaleInScope(getIndexKey().scope());
		Assert.isTrue(
			locale != null || !uniqueGloballyWithinLocale,
			() -> new EntityLocaleMissingException(attributeSchema.getName())
		);
		return this.uniqueIndex.get(
			uniqueGloballyWithinLocale ?
				new AttributeKey(attributeSchema.getName(), locale) :
				new AttributeKey(attributeSchema.getName())
		);
	}

	/**
	 * Returns every {@link GlobalUniqueIndex} this catalog index holds, keyed by the attribute - and, for an attribute
	 * that is unique globally only within a locale, the locale - it covers.
	 *
	 * Unlike {@link #getGlobalUniqueIndex(GlobalAttributeSchemaContract, Locale)} this needs no schema to address an
	 * index, which is what lets a caller enumerate the indexes rather than ask for one it already knows about. The
	 * locale-scoped indexes cannot be reached any other way without knowing the catalog's locale set, which lives in
	 * the data rather than in the schema.
	 *
	 * The returned map is an unmodifiable view over the live map, not a copy: its size is bounded by
	 * (globally-unique attributes × locales) and therefore by the schema, never by the catalog's data volume.
	 *
	 * @return unmodifiable view of the global unique indexes
	 */
	@Nonnull
	public Map<AttributeKey, GlobalUniqueIndex> getGlobalUniqueIndexes() {
		return Collections.unmodifiableMap(this.uniqueIndex);
	}

	/**
	 * Returns true if index contains no data whatsoever.
	 */
	public boolean isEmpty() {
		return this.uniqueIndex.isEmpty();
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Override
	public void resetDirty() {
		this.dirty.reset();
		for (GlobalUniqueIndex theUniqueIndex : this.uniqueIndex.values()) {
			theUniqueIndex.resetDirty();
		}
	}

	@Nullable
	@Override
	public CatalogIndexChanges createLayer() {
		return isTransactionAvailable() ? new CatalogIndexChanges() : null;
	}

	@Nonnull
	@Override
	public CatalogIndex createCopyWithMergedTransactionalMemory(@Nullable CatalogIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		final CatalogIndex newCatalogIndex = new CatalogIndex(
			this.version + (wasDirty ? 1 : 0),
			this.indexKey,
			transactionalLayer.getStateCopyWithCommittedChanges(this.uniqueIndex),
			// the very same holder, not a copy: this is one logical index carried into the next catalog version
			this.activity
		);
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return newCatalogIndex;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirty.removeLayer(transactionalLayer);
		this.uniqueIndex.removeLayer(transactionalLayer);

		final CatalogIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/**
	 * This class collects changes in {@link #uniqueIndex} transactional maps.
	 */
	public static class CatalogIndexChanges implements Snapshotable<CatalogIndexChanges.CatalogIndexChangesMemento> {
		private final TransactionalContainerChanges<GlobalUniqueIndex, GlobalUniqueIndex> uniqueIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull GlobalUniqueIndex uniqueIndex) {
			this.uniqueIndexChanges.addCreatedItem(uniqueIndex);
		}

		public void addRemovedItem(@Nonnull GlobalUniqueIndex uniqueIndex) {
			this.uniqueIndexChanges.addRemovedItem(uniqueIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.uniqueIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.uniqueIndexChanges.cleanAll(transactionalLayer);
		}

		@Nonnull
		@Override
		public CatalogIndexChangesMemento snapshot() {
			return new CatalogIndexChangesMemento(this.uniqueIndexChanges.snapshot());
		}

		@Override
		public void restore(@Nonnull CatalogIndexChangesMemento memento) {
			this.uniqueIndexChanges.restore(memento.uniqueIndexChanges());
		}

		/**
		 * Memento bundling the savepoint state of every {@link TransactionalContainerChanges} this aggregate tracks.
		 *
		 * @param uniqueIndexChanges snapshot of the global-unique-index created/removed bookkeeping
		 */
		public record CatalogIndexChangesMemento(
			@Nonnull ContainerChangesMemento<GlobalUniqueIndex> uniqueIndexChanges
		) {
		}

	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Method creates and verifies validity of attribute key from passed arguments.
	 */
	@Nonnull
	private static AttributeKey createAttributeKey(
		@Nonnull GlobalAttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		if (attributeSchema.isLocalized()) {
			verifyLocalizedAttribute(attributeSchema.getName(), allowedLocales, locale, value);
		}
		if (attributeSchema.isUniqueGloballyWithinLocaleInScope(scope)) {
			return new AttributeKey(attributeSchema.getName(), locale);
		} else {
			return new AttributeKey(attributeSchema.getName());
		}
	}

	/**
	 * Method creates container that is possible to serialize with Kryo and store
	 * into persistent storage.
	 */
	@Nonnull
	private StoragePart createStoragePart() {
		// `forEach` into a pre-sized set, never `keySet()`: outside a transaction that accessor hands out the backing
		// `HashMap`'s own view, which the map then keeps for the lifetime of the index - see
		// `documentation/developer/heap-size-testing.md`, trap 6. The copy is also the safer thing to hand a storage
		// part: it is serialized after this call returns, and a live view would let a later write change what gets
		// written
		final Set<AttributeKey> attributeKeys = CollectionUtils.createHashSet(this.uniqueIndex.size());
		this.uniqueIndex.forEach((attributeKey, uniqueIndex) -> attributeKeys.add(attributeKey));
		return new CatalogIndexStoragePart(this.version, this.indexKey, attributeKeys);
	}

}
