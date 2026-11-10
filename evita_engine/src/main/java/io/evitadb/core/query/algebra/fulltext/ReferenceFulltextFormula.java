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

package io.evitadb.core.query.algebra.fulltext;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ValueMatch;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.CollectionUtils;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Matches every retained query word within one reference target and actual owner partition.
 * Each word keeps symbolic value matches: enrollment is resolved separately for each field inside that partition.
 * Duplicate owner/target pairs share membership, including their documented positive and negative over-matches.
 * The hidden union formula supplies ranking inputs only and is never exposed as another tree score source.
 * This query-local preparation never enters a formula tree: its resolved results enter as content-keyed constants.
 * It therefore carries no dictionary or partition dependencies of its own, only the union preparation's identity.
 * The computed result contains matching partition keys for discovery, never an unused union of their owners.
 */
public final class ReferenceFulltextFormula extends AbstractFormula implements NonCacheableFormula {
	/** Stable structural identity of this formula. */
	private static final long CLASS_ID = 7810615274461357437L;
	/** Locale and scope's current field generations. */
	@Nonnull private final FulltextIndex index;
	/** Retained expansions, with the shared union policy and split structure. */
	@Nonnull private final FulltextFormula union;
	/** Resolves qualifying targets to actual reference partitions. */
	@Nonnull private final Function<int[], ReferencePartitionSplit> partitions;
	/** Resolves materialized partition identities. */
	@Nonnull private final IntFunction<ReferencePartition> partitionAccessor;
	/** Per-word target maps, built once without materializing an owner universe. */
	@Nullable private List<Map<Integer, List<FieldMatch>>> words;
	/** Signed referenced keys (zero and negative included) matching every word symbolically, sorted ascending. */
	@Nullable private int[] targets;
	/** Read-aware split initialized only when partition discovery is requested. */
	@Nullable private ReferencePartitionSplit split;

	/**
	 * Captures one scope's retained preparation and read-aware partition resolvers.
	 * @param index current locale index
	 * @param union shared retained expansions and ranking policy
	 * @param partitions splits real partitions of the qualifying targets
	 * @param partitionAccessor resolves materialized partitions of the owner collection
	 */
	public ReferenceFulltextFormula(
		@Nonnull FulltextIndex index, @Nonnull FulltextFormula union,
		@Nonnull Function<int[], ReferencePartitionSplit> partitions,
		@Nonnull IntFunction<ReferencePartition> partitionAccessor
	) {
		this.index = index;
		this.union = union;
		this.partitions = partitions;
		this.partitionAccessor = partitionAccessor;
		initFields();
	}

	/**
	 * Supplies the shared preparation only to the masked owner score accessor.
	 * @return retained union scoring formula, hidden from membership's inner formula tree
	 */
	@Nonnull
	FulltextFormula getUnion() {
		return this.union;
	}

	/**
	 * Resolves the conjunction of words inside one real partition, never against enrollment alone.
	 * @param target signed primary key of the referenced entity
	 * @param owners owners of this materialized partition
	 * @return matching owners, empty when some word has no match
	 */
	@Nonnull
	public Bitmap resolve(int target, @Nonnull Bitmap owners) {
		prepareMatches();
		PersistentRoaringBitmap result = null;
		for (final Map<Integer, List<FieldMatch>> word : this.words) {
			final List<FieldMatch> matches = word.get(target);
			if (matches == null) {
				return EmptyBitmap.INSTANCE;
			}
			final PersistentRoaringBitmap alternatives = new PersistentRoaringBitmap();
			for (final FieldMatch match : matches) {
				alternatives.or(match.resolve(owners));
			}
			if (result == null) {
				result = alternatives;
			} else {
				result.and(alternatives);
			}
			if (result.isEmpty()) {
				return EmptyBitmap.INSTANCE;
			}
		}
		return result == null ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
	}

	/**
	 * Tests one virtual owner using only enrollment and symbolic subset membership checks.
	 * @param target signed primary key of the referenced entity
	 * @param owner owner of the virtual row
	 * @return whether every word matches this owner at this target
	 */
	public boolean matches(int target, int owner) {
		prepareMatches();
		if (this.words.isEmpty()) {
			return false;
		}
		for (final Map<Integer, List<FieldMatch>> word : this.words) {
			final List<FieldMatch> matches = word.get(target);
			if (matches == null) {
				return false;
			}
			boolean matched = false;
			for (final FieldMatch match : matches) {
				if (match.matches(owner)) {
					matched = true;
					break;
				}
			}
			if (!matched) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns the owner-aware positive discovery result.
	 * @return matching partition primary keys, never target or owner primary keys
	 */
	@Nonnull
	public Bitmap getMatchingPartitions() {
		return compute();
	}

	/** Builds each word's symbolic target map once and intersects their target keys. */
	private void prepareMatches() {
		if (this.words != null) {
			return;
		}
		this.words = new ArrayList<>(this.union.getTokens().length);
		Set<Integer> qualifying = null;
		for (final FulltextFormula.ExpandedTerm[] token : this.union.getTokens()) {
			final Map<Integer, Set<String>> terms = CollectionUtils.createLinkedHashMap(token.length);
			for (final FulltextFormula.ExpandedTerm term : token) {
				terms.computeIfAbsent(term.fieldId(), ignored -> CollectionUtils.createHashSet(4)).add(term.term());
			}
			final Map<Integer, List<FieldMatch>> matches = CollectionUtils.createHashMap(terms.size());
			for (final Map.Entry<Integer, Set<String>> field : terms.entrySet()) {
				final ReferenceFulltextValueIndex dictionary = this.index.getReferenceValues(field.getKey());
				if (dictionary == null) {
					throw new GenericEvitaInternalError(
						"Retained reference fulltext field " + this.index.getFieldKey(field.getKey()) +
							" (id " + field.getKey() + ") has no reference dictionary!");
				}
				final Map<Integer, FieldMatch> combined = CollectionUtils.createHashMap(4);
				for (final ValueMatch match : dictionary.lookup(field.getValue())) {
					combined.computeIfAbsent(match.targetPK(), ignored -> new FieldMatch(dictionary)).add(match);
				}
				for (final Map.Entry<Integer, FieldMatch> entry : combined.entrySet()) {
					matches.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>(2)).add(entry.getValue());
				}
			}
			this.words.add(matches);
			if (qualifying == null) {
				qualifying = CollectionUtils.createHashSet(matches.size());
				qualifying.addAll(matches.keySet());
			} else {
				qualifying.retainAll(matches.keySet());
			}
		}
		this.targets = new int[qualifying == null ? 0 : qualifying.size()];
		if (qualifying != null) {
			int offset = 0;
			for (final int target : qualifying) {
				this.targets[offset++] = target;
			}
		}
		Arrays.sort(this.targets);
	}

	@Override
	protected Bitmap computeInternal() {
		final ReferencePartitionSplit split = getPartitionSplit();
		final BaseBitmap matching = new BaseBitmap();
		for (final int pk : split.getMaterializedPrimaryKeys().getArray()) {
			final ReferencePartition partition = this.partitionAccessor.apply(pk);
			final Bitmap resolved = resolve(partition.getReferenceKey().primaryKey(), partition.getAllPrimaryKeys());
			if (!resolved.isEmpty()) {
				matching.add(pk);
			}
		}
		for (int i = 0; i < split.getVirtualCount(); i++) {
			if (matches(split.getVirtualTargetPrimaryKey(i), split.getVirtualOwnerPrimaryKey(i))) {
				matching.add(split.getVirtualPrimaryKey(i));
			}
		}
		return matching.isEmpty() ? EmptyBitmap.INSTANCE : matching;
	}

	@Override
	public int getEstimatedCardinality() {
		return this.union.getEstimatedCardinality();
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	protected long getEstimatedCostInternal() {
		return this.union.getEstimatedCost();
	}

	@Override
	protected long getCostInternal() {
		return getEstimatedCostInternal();
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Reference fulltext formula has no inner formulas!");
	}

	/**
	 * Resolves actual partitions once, after symbolic target qualification.
	 * @return partition split of this scope's qualifying targets
	 */
	@Nonnull
	private ReferencePartitionSplit getPartitionSplit() {
		if (this.split == null) {
			prepareMatches();
			this.split = this.partitions.apply(this.targets);
		}
		return this.split;
	}

	@Nonnull
	@Override
	protected long[] gatherBitmapIdsInternal() {
		return this.union.gatherTransactionalIds();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return this.union.getHash();
	}

	/**
	 * Combined alternatives for one word, target and field. ALL dominates; otherwise HOLDERS are unioned and
	 * EXCEPT exclusions are intersected, keeping partition work independent of the matching value count.
	 */
	private static final class FieldMatch {
		/** Field generation whose enrollment defines ALL and EXCEPT. */
		@Nonnull private final ReferenceFulltextValueIndex field;
		/** Whether a value matched in the ALL form; ALL dominates, so both owner sets are then null. */
		private boolean all;
		/** Union of the HOLDERS owner sets, null when no such value matched. */
		@Nullable private PersistentRoaringBitmap holders;
		/** Intersection of the EXCEPT exclusion sets - owners every such value excludes; null when none matched. */
		@Nullable private PersistentRoaringBitmap except;

		/**
		 * Captures the field whose enrollment defines ALL and EXCEPT.
		 * @param field current reference attribute generation
		 */
		private FieldMatch(@Nonnull ReferenceFulltextValueIndex field) {
			this.field = field;
		}

		/**
		 * Combines another value's alternatives without resolving an owner universe.
		 * @param match one symbolic value match at this target
		 */
		private void add(@Nonnull ValueMatch match) {
			if (this.all) {
				return;
			}
			switch (match.form()) {
				case ALL -> {
					this.all = true;
					this.holders = null;
					this.except = null;
				}
				case HOLDERS -> {
					if (this.holders == null) {
						this.holders = new PersistentRoaringBitmap();
					}
					this.holders.or(RoaringBitmapBackedBitmap.getRoaringBitmap(match.owners()));
				}
				case EXCEPT -> {
					if (this.except == null) {
						this.except = new PersistentRoaringBitmap();
						this.except.or(RoaringBitmapBackedBitmap.getRoaringBitmap(match.owners()));
					} else {
						this.except.and(RoaringBitmapBackedBitmap.getRoaringBitmap(match.owners()));
					}
				}
				default -> throw new GenericEvitaInternalError("Unsupported membership form `" + match.form() + "`!");
			}
		}

		/**
		 * Resolves combined alternatives inside an actual partition.
		 * @param owners actual materialized partition owners
		 * @return newly owned matching owner set
		 */
		@Nonnull
		private PersistentRoaringBitmap resolve(@Nonnull Bitmap owners) {
			final PersistentRoaringBitmap result = new PersistentRoaringBitmap();
			if (this.all || this.except != null) {
				result.or(RoaringBitmapBackedBitmap.getRoaringBitmap(this.field.getEnrolledOwners(owners)));
				if (!this.all) {
					result.andNot(this.except);
				}
			}
			if (!this.all && this.holders != null) {
				result.or(PersistentRoaringBitmap.and(
					RoaringBitmapBackedBitmap.getRoaringBitmap(owners), this.holders));
			}
			return result;
		}

		/**
		 * Tests one owner without allocating bitmaps.
		 * @param owner owner of an actual virtual row
		 * @return whether this field supplies the word
		 */
		private boolean matches(int owner) {
			// ALL, or EXCEPT not excluding the owner, needs enrollment; an explicit HOLDERS owner needs nothing more
			return (this.all || this.except != null && !this.except.contains(owner)) && this.field.isEnrolled(owner) ||
				!this.all && this.holders != null && this.holders.contains(owner);
		}
	}
}
