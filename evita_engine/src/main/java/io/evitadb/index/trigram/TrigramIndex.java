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
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueLifecycleSink;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.Arrays;
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
 * One instance per `(attribute, locale)` of the **global** entity index, and never one per reduced index - so the
 * postings are paid for once per catalog rather than once per reduced index, of which a large catalog has hundreds
 * of thousands. A reduced index is meant to be served by composing this index's value ids with the global per-value
 * record sets and intersecting the result with its own entity ids; until that composition exists a reduced index
 * takes the ordinary bucket scan, which {@link TrigramSubstringSearch} states as the boundary it declines at.
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
 * # Thread safety
 *
 * `@NotThreadSafe` here carries the meaning it carries on the trees below it: no internal synchronization, and one
 * writer at a time. Many query threads DO read one instance concurrently - {@link TrigramSubstringSearch} is on the
 * query path - and that is the same shape {@link io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree} is read
 * in through {@link InvertedIndex} today. Every read method here allocates its own scratch, so nothing but the
 * underlying tree is shared between two readers.
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
	 * Runs outside any transaction and while nothing writes to `sharedValueTree` - but NOT necessarily on one
	 * thread. An archived global index is loaded from a leaf `ProgressingFuture`, so this can run on a pool thread,
	 * concurrently with the same work for other collections; the accumulator's concurrency note has the details.
	 * What lets {@link TrigramPostingAccumulator} build the whole table in bulk instead of growing it one membership
	 * at a time is that the table is unpublished - nothing else can see it until it is handed over - so the
	 * copy-on-write the write path owes every published posting is not owed here. On the measured flagship attribute
	 * that is the difference between a rebuild measured in minutes and one measured in seconds - see the accumulator
	 * for the numbers, and for what the bulk build costs in transient heap.
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
	 * Only entity-level attributes are considered, because {@link AttributeFilterAccelerator#SUBSTRING_SEARCH} is
	 * refused on a reference attribute at schema-mutation time; and only attributes the schema STILL declares it for.
	 * Attaching the value id consumer here restores the registration the tree does not persist — the ids themselves
	 * came back inside the pages, which is why this cannot fall foul of the tree's empty-at-attach premise.
	 *
	 * An attribute whose accelerator was withdrawn therefore gets no accelerator back — but its cost does not leave
	 * with it. The loader restores a tree's value id column, allocator and directory from the persisted shape alone
	 * (it must not consult the schema, because the column is engine infrastructure that outlives whichever consumer
	 * asked for it), so such a tree comes back id-carrying with an EMPTY consumer registry, and nothing reclaims that
	 * column today; the restore dirties no page either, so it is read back identically on every later load. A cleanup,
	 * when one is written, has to key on a tree that carries ids with no consumer left rather than on unregistering
	 * the name — {@link InvertedIndex#detachValueIdConsumer(String)} returns silently when no registry was ever
	 * created.
	 *
	 * A tree that turns out to be unusable — populated, accelerator declared, yet carrying no ids — FAILS THE LOAD.
	 * See the comment at the site for why degrading to a silently absent accelerator is the worse of the two.
	 *
	 * Runs on the single-writer catalog load path with no transaction open, which is what makes both the attach and
	 * the `O(values)` walk behind it legal here.
	 *
	 * @param entitySchema       the schema saying which attributes declare the accelerator
	 * @param scope              the scope of the index being loaded
	 * @param sharedValueIndexes the reloaded shared value trees, keyed by attribute and locale
	 * @return one index per `(attribute, locale)` that declares the accelerator, empty when none does
	 * @throws io.evitadb.exception.GenericEvitaInternalError when an attribute whose schema declares the accelerator
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
				|| !attributeSchema.getAcceleratorsInScope(scope).contains(AttributeFilterAccelerator.SUBSTRING_SEARCH)) {
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
	 * Reads the postings of every trigram in the pattern and hands them back ordered ascending by cardinality - or
	 * answers `null` when the pattern provably occurs in no value at all.
	 *
	 * This is the ONE place an accelerated query reads its postings. The cheapest posting's cardinality, which the
	 * returned carrier exposes as {@link PatternPostings#candidateUpperBound}, is the upper bound on how many
	 * candidates an intersection could produce and therefore the single number the decision to take this index at all
	 * is made on - so that decision is now made from the very carrier
	 * {@link #resolveCandidateValueIds(PatternPostings)} goes on to consume, instead of from a separate pricing pass
	 * that looked every posting up and threw it away. A pattern's trigrams are looked up once per query, not twice.
	 *
	 * A `null` is the strongest possible answer rather than a degenerate one: a trigram nothing posts against means no
	 * value contains the pattern, so the answer is already known and no intersection has to run. The pass is abandoned
	 * at the first such trigram, so the remaining ones are never looked up - the cheapest outcome this index can
	 * produce stays the cheapest.
	 *
	 * @param trigrams the pattern's trigrams, as {@link TrigramCodec#extractUniqueTrigrams} produces them
	 * @return the pattern's postings ordered by cardinality, or `null` when some trigram posts against nothing - and
	 * likewise when there are no trigrams at all, which a caller must have refused before reaching here
	 */
	@Nullable
	PatternPostings pricePattern(@Nonnull long[] trigrams) {
		final int trigramCount = trigrams.length;
		if (trigramCount == 0) {
			return null;
		}
		final Object[] postings = new Object[trigramCount];
		final int[] cardinalities = new int[trigramCount];
		int candidateUpperBound = Integer.MAX_VALUE;
		for (int i = 0; i < trigramCount; i++) {
			final Object posting = this.store.get(trigrams[i]);
			// covers the absent key and the (unreachable) emptied posting alike - the store drops a trigram that lost
			// its last value id rather than parking an empty posting under it
			final int cardinality = TrigramPostings.cardinality(posting);
			if (cardinality == 0) {
				return null;
			}
			postings[i] = posting;
			cardinalities[i] = cardinality;
			if (cardinality < candidateUpperBound) {
				candidateUpperBound = cardinality;
			}
		}
		// deliberately NOT ordered here: the gate needs only this minimum, and a declined pattern must not be charged
		// for an ordering it never uses - see the class javadoc of PatternPostings
		return new PatternPostings(postings, cardinalities, candidateUpperBound);
	}

	/**
	 * Intersects an already-read pattern's postings into the value ids that COULD hold the pattern they came from.
	 *
	 * The result is a superset of the true matches and must be verified exactly - membership is all this index holds,
	 * so a candidate whose value merely contains all the pattern's trigrams in some other arrangement survives to
	 * here. Measured on production corpora that costs 0.36 false candidates per true match in the worst case, which is
	 * the whole reason positions were cancelled rather than deferred (see the class javadoc).
	 *
	 * ## Two intersection paths, chosen by the smallest posting
	 *
	 * The postings are ordered ascending by cardinality here rather than by {@link #pricePattern}, so that a declined
	 * pattern never pays for it, and the representation of the FIRST one then decides the chain.
	 * An `int[]` means the candidate set starts at {@link TrigramPostings#SMALL_POSTING_THRESHOLD} ids or fewer
	 * and can only shrink, so it is kept as a small `int[]` and the remaining postings are probed for membership. A
	 * bitmap means the chain is a sequence of Roaring `and`s and the candidate array is materialized once, at the end.
	 *
	 * Neither branch may infer a later posting's REPRESENTATION from its cardinality - the promotion and demotion
	 * thresholds differ deliberately, so both forms occur across cardinalities 65..128. Both branches therefore
	 * dispatch on what each posting actually is; see {@link #intersectFromBitmapPosting}.
	 *
	 * No early exit is taken: stopping the intersection while candidates remain trades a Roaring `and` for a
	 * verification pass, and verification is the expensive half (55-87% of query cost on the measured corpora, and
	 * roughly twice that per candidate for non-ASCII values). The intersection is run to completion for that reason.
	 *
	 * Nothing this method touches is mutated - the postings it reads are the index's own and are shared with every
	 * version that has not rewritten them - and nothing it returns aliases one either, on either path.
	 *
	 * @param patternPostings the pattern's postings, as {@link #pricePattern} produced them
	 * @return the candidate value ids in ascending order, owned by the caller, empty when the pattern cannot occur in
	 * any value
	 */
	@Nonnull
	int[] resolveCandidateValueIds(@Nonnull PatternPostings patternPostings) {
		final Object[] postings = patternPostings.postings();
		final int trigramCount = postings.length;
		// ordering belongs to this half, not to the pricing half: it is what makes `postings[0]` the cheapest, and a
		// pattern the gate declined never gets here and never pays for it
		orderByCardinality(postings, patternPostings.cardinalities(), trigramCount);
		return postings[0] instanceof final int[] smallest
			? intersectFromSmallPosting(smallest, postings, trigramCount)
			: intersectFromBitmapPosting(postings, trigramCount);
	}

	/**
	 * The {@link #resolveCandidateValueIds(PatternPostings)} above, pricing the pattern itself rather than taking a
	 * carrier a caller already holds.
	 *
	 * A query path takes the two-step form instead, because the gate sits between the two halves and decides on
	 * {@link PatternPostings#candidateUpperBound} whether the intersection is worth running at all. This one-step form
	 * is for callers that have already committed to the intersection and hold nothing but the trigrams.
	 *
	 * @param trigrams the pattern's trigrams, as {@link TrigramCodec#extractUniqueTrigrams} produces them
	 * @return the candidate value ids in ascending order, owned by the caller, empty when the pattern cannot occur in
	 * any value
	 */
	@Nonnull
	public int[] resolveCandidateValueIds(@Nonnull long[] trigrams) {
		final PatternPostings patternPostings = pricePattern(trigrams);
		return patternPostings == null ?
			ArrayUtils.EMPTY_INT_ARRAY : resolveCandidateValueIds(patternPostings);
	}

	/**
	 * Runs the intersection when the cheapest posting is a sorted `int[]`: the candidate set starts as a copy of it and
	 * every further posting compacts that copy in place.
	 *
	 * The copy is taken UNCONDITIONALLY, before any filtering. Two things need it: the cheapest posting is the index's
	 * own array, shared by reference with every version that has not rewritten it, so compacting into it would corrupt
	 * them all; and a single-trigram pattern filters nothing at all, so without the copy this method would hand that
	 * very array out to the caller. One `Arrays.copyOf` of at most
	 * {@link TrigramPostings#SMALL_POSTING_THRESHOLD} ints is a small price for a returned array that is
	 * unambiguously the caller's, whichever intersection path produced it.
	 *
	 * @param smallest     the cheapest posting
	 * @param postings     every posting, ordered ascending by cardinality
	 * @param trigramCount how many postings there are
	 * @return the surviving candidate value ids in ascending order
	 */
	@Nonnull
	private static int[] intersectFromSmallPosting(
		@Nonnull int[] smallest, @Nonnull Object[] postings, int trigramCount
	) {
		final int[] candidates = Arrays.copyOf(smallest, smallest.length);
		int candidateCount = candidates.length;
		for (int i = 1; i < trigramCount && candidateCount > 0; i++) {
			candidateCount = retain(candidates, candidateCount, postings[i]);
		}
		return candidateCount == candidates.length ? candidates : Arrays.copyOf(candidates, candidateCount);
	}

	/**
	 * Runs the intersection when the cheapest posting is a bitmap: the chain is a sequence of Roaring `and`s, each of
	 * which allocates its own result rather than writing into either operand.
	 *
	 * ## A later posting may still be an `int[]`, and this must not assume otherwise
	 *
	 * The tempting reasoning - *the cheapest posting is a bitmap, and every later one is at least as large, so every
	 * later one is a bitmap too* - is FALSE, because a posting's representation is not a function of its cardinality.
	 * {@link TrigramPostings#SMALL_POSTING_DEMOTION_THRESHOLD} is deliberately half of
	 * {@link TrigramPostings#SMALL_POSTING_THRESHOLD}, so that a value id added and removed at the boundary does not
	 * rebuild the posting on every write. That hysteresis leaves an overlap: across cardinalities 65..128 a posting
	 * may legitimately be EITHER form - an `int[]` that has grown to 128 without ever promoting, or a bitmap that
	 * promoted at 129 and has since eroded without yet demoting.
	 *
	 * A pattern whose cheapest posting is an eroded bitmap of 100 and whose next posting is an `int[]` of 120 is
	 * therefore an ordinary, reachable state, and casting that `int[]` to a bitmap threw `ClassCastException` on a
	 * plain query. The small-posting path never had this bug because {@link #retain} always dispatched on the actual
	 * representation; only this branch reasoned from cardinality instead of looking.
	 *
	 * @param postings     every posting, ordered ascending by cardinality
	 * @param trigramCount how many postings there are
	 * @return the surviving candidate value ids in ascending order
	 */
	@Nonnull
	private static int[] intersectFromBitmapPosting(@Nonnull Object[] postings, int trigramCount) {
		PersistentRoaringBitmap accumulator = (PersistentRoaringBitmap) postings[0];
		for (int i = 1; i < trigramCount && !accumulator.isEmpty(); i++) {
			final Object posting = postings[i];
			// the small form is converted rather than the accumulator demoted: this arises only in the narrow
			// promotion/demotion overlap, so it is not worth a second accumulator representation
			accumulator = PersistentRoaringBitmap.and(
				accumulator,
				posting instanceof final int[] small
					? PersistentRoaringBitmap.bitmapOf(small)
					: (PersistentRoaringBitmap) posting
			);
		}
		// `toArray` reads the accumulator; when the loop never ran it is the index's own posting, which this leaves
		// untouched exactly as the contract on `getValueIdsOf` requires
		return accumulator.toArray();
	}

	/**
	 * Filters an ascending candidate array down to the ids the given posting also holds, in place.
	 *
	 * @param candidates     the ascending candidate ids, compacted in place
	 * @param candidateCount how many candidates are currently live
	 * @param posting        the posting to intersect with
	 * @return how many candidates survived
	 */
	private static int retain(@Nonnull int[] candidates, int candidateCount, @Nonnull Object posting) {
		int kept = 0;
		if (posting instanceof final int[] other) {
			// both sides are ascending, so one merge pass suffices
			int left = 0;
			int right = 0;
			while (left < candidateCount && right < other.length) {
				final int candidate = candidates[left];
				final int probe = other[right];
				if (candidate == probe) {
					candidates[kept++] = candidate;
					left++;
					right++;
				} else if (candidate < probe) {
					left++;
				} else {
					right++;
				}
			}
			return kept;
		}
		final PersistentRoaringBitmap bitmap = (PersistentRoaringBitmap) posting;
		for (int i = 0; i < candidateCount; i++) {
			final int candidate = candidates[i];
			if (bitmap.contains(candidate)) {
				candidates[kept++] = candidate;
			}
		}
		return kept;
	}

	/**
	 * Orders the postings ascending by cardinality. Insertion sort over the two parallel arrays: a pattern of 20 code
	 * points produces 18 trigrams, so a comparison sort's asymptotics are irrelevant and its allocation is not.
	 *
	 * @param postings      the postings to reorder in place
	 * @param cardinalities their cardinalities, reordered with them
	 * @param count         how many entries are live
	 */
	private static void orderByCardinality(@Nonnull Object[] postings, @Nonnull int[] cardinalities, int count) {
		for (int i = 1; i < count; i++) {
			final int cardinality = cardinalities[i];
			final Object posting = postings[i];
			int j = i - 1;
			while (j >= 0 && cardinalities[j] > cardinality) {
				cardinalities[j + 1] = cardinalities[j];
				postings[j + 1] = postings[j];
				j--;
			}
			cardinalities[j + 1] = cardinality;
			postings[j + 1] = posting;
		}
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
