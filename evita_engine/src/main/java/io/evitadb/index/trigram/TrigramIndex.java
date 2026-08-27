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

package io.evitadb.index.trigram;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueLifecycleSink;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Maps each trigram of one attribute's values to the {@link io.evitadb.index.invertedIndex.ValueIdAllocator value
 * ids} of the values that contain it, so `attributeContains` and `attributeEndsWith` can find their candidates by
 * intersecting a handful of postings instead of scanning every distinct value of the attribute.
 *
 * # What it holds, and what it deliberately does not
 *
 * ```text
 * trigram (63-bit packed long) -> value ids of the values containing it
 * ```
 *
 * Membership only: no positions, no frequencies, no scoring. Positions were the obvious next optimization and were
 * cancelled rather than deferred - measured on production corpora, trigram intersection is nearly exact (worst case
 * 0.36 false candidates per true match), so exact verification of the surviving candidates costs less than
 * maintaining position lists would.
 *
 * The index keys its postings by **value id** rather than by entity primary key, which is the whole reason it can
 * afford to exist. Entity-keyed postings would be rewritten on every entity that happens to share a value; value-id
 * postings change ONLY when a distinct value is born or dies, so churn over existing values - the overwhelming
 * majority of writes - costs the index literally nothing. On the measured corpora the same choice also compresses
 * the postings by up to 21x, because a dense id space is what a Roaring bitmap is good at and a sparse primary-key
 * space is not.
 *
 * A value shorter than {@link TrigramCodec#MINIMAL_INDEXABLE_LENGTH} code points contributes no trigram and is
 * therefore simply absent here; a query pattern that short cannot be served by this index and falls back to the
 * scan. That is not a gap to be closed with bigrams - a bigram tier posts against so much of the corpus that
 * verifying its candidates costs more than the scan it replaces.
 *
 * # Hosting
 *
 * One instance per `(attribute, locale)` of the **global** entity index, and never one per reduced index. A reduced
 * index answers by composing this index's value ids with the global per-value record sets and intersecting the
 * result with its own entity ids, so the postings are paid for once per catalog rather than once per reduced index -
 * of which a large catalog has hundreds of thousands.
 *
 * # Derived state
 *
 * The index is **not persisted**. Everything it holds is a pure function of the shared value tree's distinct values
 * and their value ids, both of which the tree itself persists, so it is rebuilt from the reloaded tree on catalog
 * load - the same treatment the tree's own `valueId -> (leafId, slot)` directory gets, and for the same reason: it
 * keeps the whole substring-index feature's storage surface at zero.
 *
 * # Transactional behaviour
 *
 * MVCC is owned by the B+ tree inside {@link TrigramPostingStore}: a write inside a transaction lands in the tree's
 * own node layers and the commit merge rebuilds only the path it touched. This index adds one thing on top - a
 * {@link #dirty} flag, so an index no transaction touched is carried forward BY REFERENCE, keeping its identity
 * (which is what lets a consumer key a cache on it) and sparing the tree rebuild entirely. Outside a transaction
 * (the warm-up bulk path) the tree is written directly, exactly as every other index does.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class TrigramIndex implements
	VoidTransactionMemoryProducer<TrigramIndex>,
	ValueLifecycleSink {

	/**
	 * The name this index registers under in the shared value tree's
	 * {@link io.evitadb.index.invertedIndex.ValueIdConsumerRegistry} - the answer an operator gets when they ask what
	 * a tree's id column is being paid for.
	 */
	public static final String VALUE_ID_CONSUMER_NAME = "trigram-substring-index";

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * The attribute and locale this index serves. Held for diagnostics only - it names the index in error messages
	 * and in `toString`, which is what makes a divergence traceable to one attribute rather than to "some trigram
	 * index". The key instance belongs to the map that files this index under it.
	 */
	@Nonnull @Getter private final AttributeIndexKey attributeIndexKey;

	/**
	 * Tracks whether this index was written to inside the current transaction, so an untouched one can be carried
	 * forward by reference instead of being rebuilt - the same gate {@link io.evitadb.index.range.RangeIndex} and
	 * the shared value tree put in front of their own merges.
	 */
	@Nonnull private final TransactionalBoolean dirty;

	/**
	 * The `trigram -> posting` table. Versioned by the transactional B+ tree inside it rather than by this class -
	 * see the class javadoc.
	 */
	@Nonnull private final TrigramPostingStore store;

	/**
	 * Creates an empty index for one attribute and locale.
	 *
	 * @param attributeIndexKey the attribute and locale this index serves
	 */
	public TrigramIndex(@Nonnull AttributeIndexKey attributeIndexKey) {
		this(attributeIndexKey, new TrigramPostingStore());
	}

	/**
	 * Adopts an already-built table - the constructor the commit merge and the load-time rebuild produce their
	 * result with.
	 *
	 * @param attributeIndexKey the attribute and locale this index serves
	 * @param store             the table to publish
	 */
	private TrigramIndex(@Nonnull AttributeIndexKey attributeIndexKey, @Nonnull TrigramPostingStore store) {
		this.attributeIndexKey = attributeIndexKey;
		this.store = store;
		this.dirty = new TransactionalBoolean();
	}

	/**
	 * Rebuilds the index from a shared value tree that has just come back from disk.
	 *
	 * This is the whole of the index's "load path": every posting is re-derived from the tree's own distinct values
	 * and the value ids the tree persisted alongside them, so the ids a query resolves after a restart are the same
	 * ids it would have resolved before one. Nothing about the result depends on how the tree was persisted, which is
	 * what makes the paged and the inline shapes indistinguishable from here.
	 *
	 * Runs outside any transaction, on the single thread loading the catalog, which is what lets
	 * {@link TrigramPostingAccumulator} build the whole table in bulk instead of growing it one membership at a
	 * time: nothing else can see the table until it is handed over, so the copy-on-write the write path owes every
	 * published posting is not owed here. That is the difference between 76.5 s and 3.7 s on the measured flagship
	 * attribute - see the accumulator for the numbers and for what it costs in transient heap.
	 *
	 * @param attributeIndexKey the attribute and locale the tree belongs to
	 * @param sharedValueTree   the reloaded tree, already carrying its value ids
	 * @return the rebuilt index
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the tree carries no value ids at all, or when it
	 * hands out a value that is not a `String`
	 */
	@Nonnull
	public static TrigramIndex rebuildFrom(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull InvertedIndex sharedValueTree
	) {
		return new TrigramIndex(attributeIndexKey, TrigramPostingAccumulator.accumulate(sharedValueTree));
	}

	/**
	 * Re-derives the substring-search accelerators of a whole attribute index from the shared value trees it was just
	 * loaded with — the entry point of the load path, since nothing about this index is persisted.
	 *
	 * Only entity-level attributes are considered, because {@link FilterIndexCapability#SUBSTRING} is refused on a
	 * reference attribute at schema-mutation time; and only attributes the schema STILL declares it for. Attaching
	 * the value id consumer here restores the registration the tree does not persist — the ids themselves came back
	 * inside the pages, which is why this cannot fall foul of the tree's empty-at-attach premise.
	 *
	 * An attribute whose capability was withdrawn therefore gets no accelerator back — but its cost does not leave
	 * with it. The loader restores a tree's value id column, allocator and directory from the persisted shape alone
	 * (it must not consult the schema, because the column is engine infrastructure that outlives whichever consumer
	 * asked for it), so such a tree comes back id-carrying with an EMPTY consumer registry, and nothing reclaims that
	 * column today; the restore dirties no page either, so it is read back identically on every later load. A cleanup,
	 * when one is written, has to key on a tree that carries ids with no consumer left rather than on unregistering
	 * the name — {@link InvertedIndex#detachValueIdConsumer(String)} returns silently when no registry was ever
	 * created.
	 *
	 * A tree that turns out to be unusable — populated, capability declared, yet carrying no ids — FAILS THE LOAD.
	 * See the comment at the site for why degrading to a silently absent accelerator is the worse of the two.
	 *
	 * Runs on the single-writer catalog load path with no transaction open, which is what makes both the attach and
	 * the `O(values)` walk behind it legal here.
	 *
	 * @param entitySchema       the schema saying which attributes declare the capability
	 * @param scope              the scope of the index being loaded
	 * @param sharedValueIndexes the reloaded shared value trees, keyed by attribute and locale
	 * @return one index per `(attribute, locale)` that declares the capability, empty when none does
	 * @throws io.evitadb.exception.GenericEvitaInternalError when an attribute whose schema declares the capability
	 * comes back with a tree that carries no value ids
	 */
	@Nonnull
	public static Map<AttributeIndexKey, TrigramIndex> rebuildAll(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Scope scope,
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndexes
	) {
		Map<AttributeIndexKey, TrigramIndex> rebuilt = null;
		for (final Entry<AttributeIndexKey, InvertedIndex> entry : sharedValueIndexes.entrySet()) {
			final AttributeIndexKey key = entry.getKey();
			if (key.referenceName() != null) {
				// a skip rather than a refusal, unlike the unusable-tree case below: names resolve against the ENTITY
				// schema here, and a reference attribute may legitimately share a name with an entity one, so a
				// reference-scoped key says nothing about a divergence. The write side states the same decision as a
				// premise instead - see GlobalEntityIndex#maintainsTrigramIndex - which is the half that would fail
				// loudly if the schema restriction on reference attributes were ever lifted without teaching this loop
				continue;
			}
			final AttributeSchemaContract attributeSchema = entitySchema
				.getAttribute(key.attributeName())
				.orElse(null);
			if (attributeSchema == null
				|| !attributeSchema.getFilterCapabilitiesInScope(scope).contains(FilterIndexCapability.SUBSTRING)) {
				continue;
			}
			final InvertedIndex sharedValueTree = entry.getValue();
			// deliberately NOT wrapped in a catch: a tree this rebuild cannot use means the persisted state and the
			// schema disagree, and skipping it would open the catalog with an accelerator silently missing - every
			// substring query against that attribute would then quietly match fewer entities than it should. Failing
			// the load says so at the one moment an operator can still act on it
			sharedValueTree.attachValueIdConsumer(VALUE_ID_CONSUMER_NAME);
			if (rebuilt == null) {
				rebuilt = CollectionUtils.createHashMap(sharedValueIndexes.size());
			}
			rebuilt.put(key, rebuildFrom(key, sharedValueTree));
		}
		return rebuilt == null ? Map.of() : rebuilt;
	}

	@Override
	public void valueCreated(int valueId, @Nonnull Serializable normalizedValue) {
		final long[] trigrams = TrigramCodec.extractUniqueTrigramsOfValue(normalizedValue);
		for (int i = 0; i < trigrams.length; i++) {
			addValueId(trigrams[i], valueId);
		}
	}

	@Override
	public void valueRemoved(int valueId, @Nonnull Serializable normalizedValue) {
		final long[] trigrams = TrigramCodec.extractUniqueTrigramsOfValue(normalizedValue);
		for (int i = 0; i < trigrams.length; i++) {
			removeValueId(trigrams[i], valueId);
		}
	}

	/**
	 * Returns the value ids of every value that contains the given trigram.
	 *
	 * The result is READ-ONLY: for a large posting it shares the index's own bitmap rather than copying it, so
	 * mutating it would corrupt every index version that shares that posting.
	 *
	 * @param trigram the packed trigram, as {@link TrigramCodec#pack} produces it
	 * @return the ids of the values containing that trigram, empty when no value does
	 */
	@Nonnull
	public Bitmap getValueIdsOf(long trigram) {
		return TrigramPostings.asBitmap(this.store.get(trigram));
	}

	/**
	 * Returns how many values contain the given trigram - the figure an intersection orders its postings by, and the
	 * one that decides whether the trigram path is worth taking at all.
	 *
	 * Answered without materializing the posting, so it is safe to ask for every trigram of a pattern before
	 * committing to any of them.
	 *
	 * @param trigram the packed trigram
	 * @return the number of value ids posting against it, `0` when none do
	 */
	public int cardinalityOf(long trigram) {
		return TrigramPostings.cardinality(this.store.get(trigram));
	}

	/**
	 * Returns how many trigrams post against at least one value id, as the caller's transaction sees them.
	 *
	 * `O(1)` - it is the tree's own size counter, and a trigram that lost its last value id is deleted rather than
	 * parked, so the count needs no correction.
	 *
	 * @return the number of live trigram keys
	 */
	public int getTrigramCount() {
		return this.store.liveKeyCount();
	}

	/**
	 * @return whether no trigram posts against any value id
	 */
	public boolean isEmpty() {
		return getTrigramCount() == 0;
	}

	/**
	 * Returns the heap this index occupies, in bytes - its own object and the whole posting table.
	 *
	 * Walking the tree's node graph and pricing every posting makes this `O(trigram keys)` rather than a counter
	 * read, so it is an index-detail operation and never something a query path may call.
	 *
	 * The per-transaction node layers the tree registers are deliberately NOT counted: they belong to the
	 * transaction that created them and disappear on commit or rollback, exactly as
	 * {@link io.evitadb.index.invertedIndex.ValueIdAllocator#getHeapSizeInBytes()} rules for its own layer.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the id, plus the attributeIndexKey / dirty / store slots - the key record itself belongs to the map that
		// files this index under it, and the attribute name and locale belong to the schema
		return layout.sizeOfObject(Long.BYTES + 3L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			+ this.store.heapSizeInBytes();
	}

	@Override
	public String toString() {
		return "TrigramIndex(" + this.attributeIndexKey + ", trigrams=" + getTrigramCount() + ')';
	}

	/*
		TRANSACTIONAL MEMORY implementation
	 */

	@Nonnull
	@Override
	public TrigramIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// consume the dirty layer first: an index no transaction touched is carried forward by reference, keeping its
		// identity - what lets a dependent cache key on this instance - and sparing the tree merge entirely
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		return isDirty
			? new TrigramIndex(
				this.attributeIndexKey, this.store.createCopyWithMergedTransactionalMemory(transactionalLayer)
			)
			: this;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirty.removeLayer(transactionalLayer);
		this.store.removeLayer(transactionalLayer);
	}

	/**
	 * Adds one value id to a trigram's posting.
	 *
	 * @param trigram the packed trigram
	 * @param valueId the value id to add
	 */
	private void addValueId(long trigram, int valueId) {
		// the tree is transaction-aware on both halves, so this reads and writes the caller's own view; the posting
		// that comes back is never mutated in place, which is what makes it safe to have come out of a shared node
		this.store.put(trigram, TrigramPostings.add(this.store.get(trigram), valueId));
		this.dirty.setToTrue();
	}

	/**
	 * Removes one value id from a trigram's posting. A posting that loses its last value id takes its trigram out of
	 * the table with it - see {@link TrigramPostingStore#put}.
	 *
	 * A trigram with no posting at all is refused here rather than handed to {@link TrigramPostings#remove} as an
	 * empty one, because the two are different divergences with different causes - the whole key is gone, versus one
	 * id missing from a live posting - and only the second is what that method's refusal describes. Both throw
	 * {@link io.evitadb.exception.GenericEvitaInternalError}, so no caller sees a difference; the operator does.
	 *
	 * @param trigram the packed trigram
	 * @param valueId the value id to remove
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the trigram holds no posting
	 */
	private void removeValueId(long trigram, int valueId) {
		final Object posting = this.store.get(trigram);
		Assert.isPremiseValid(
			posting != null,
			() -> "Trigram `" + TrigramCodec.toDisplayString(trigram) + "` holds no posting at all, yet value id " +
				valueId + " is being removed from it - the trigram index and the shared value tree have diverged."
		);
		this.store.put(trigram, TrigramPostings.remove(posting, valueId, trigram));
		this.dirty.setToTrue();
	}

}
