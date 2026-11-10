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

package io.evitadb.index.fulltext;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.bPlusTree.OverflowRecords;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Distinct raw reference-attribute values of one field generation, with target provenance and sparse owner sets.
 * Counts are reference-row counts; duplicate array elements count once. There is no target-owner universe here:
 * matching forms resolve against the caller's actual reference partition and this generation's enrollment.
 *
 * Duplicate (owner, target) pairs deliberately share membership: words and sibling predicates may combine across
 * those rows, and negation may miss an owner. Different targets never share membership.
 * All mutable state belongs to transactional children, including warm-up journals and savepoint rollback.
 */
public final class ReferenceFulltextValueIndex
	implements TransactionalLayerProducer<ReferenceFulltextValueIndex.Changes, ReferenceFulltextValueIndex> {
	/** Leaf block size of every child tree; a stored page always holds fewer keys. */
	private static final int LEAF_CAPACITY = 256;
	/** Minimum keys of a child leaf, also the internal node block size, sized as the parent dictionary's trees. */
	private static final int MIN_LEAF_SIZE = LEAF_CAPACITY / 2 - 1;
	/** Minimum keys of an internal node of every child tree. */
	private static final int MIN_INTERNAL_NODE_SIZE = (int) (Math.ceil(MIN_LEAF_SIZE / 2.0) - 1);
	/** Stored form tag of {@link MembershipForm#ALL}, the implicit value of a link without a tag. */
	private static final int FORM_ALL = 0;
	/** Stored form tag of {@link MembershipForm#EXCEPT}. */
	private static final int FORM_EXCEPT = 1;
	/** Stored form tag of {@link MembershipForm#HOLDERS}. */
	private static final int FORM_HOLDERS = 2;

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/** Exact raw text to stable positive id. */
	@Nonnull final TransactionalBucketBPlusTree<String> values;
	/** Analyzed term to value ids. */
	@Nonnull final TransactionalBucketBPlusTree<String> terms;
	/** Value id to targets. */
	@Nonnull final TransactionalBucketBPlusTree<Integer> valueTargets;
	/** Target to values, bounding row-universe maintenance. */
	@Nonnull final TransactionalBucketBPlusTree<Integer> targetValues;
	/** Enrolled row count R(t). */
	@Nonnull final TransactionalBucketBPlusTree<Integer> targetCounts;
	/** Carrying row count C(v,t). */
	@Nonnull final TransactionalBucketBPlusTree<Long> holderCounts;
	/** Explicit form tags for non-ALL links, including empty EXCEPT. */
	@Nonnull final TransactionalBucketBPlusTree<Long> subsetForms;
	/** EXCEPT or HOLDERS owners according to subsetForms. */
	@Nonnull final TransactionalBucketBPlusTree<Long> ownerSubsets;
	/** Enrolled owners grouped into bounded 65,536-PK blocks. */
	@Nonnull final TransactionalBucketBPlusTree<Integer> enrolledOwners;
	/** Mints the stable value ids; its high-water is persisted as {@link ValueManifest#nextValueId()}. */
	@Nonnull final ValueIdAllocator allocator;

	/** Stable child stream identities; ordinals are persisted and append-only within this unreleased format. */
	public enum ChildKind {
		/** Exact raw text ids. */ RAW_VALUE_IDS,
		/** Analyzed term postings. */ TERM_VALUE_IDS,
		/** Forward adjacency. */ VALUE_TARGETS,
		/** Reverse adjacency. */ TARGET_VALUES,
		/** Row universe counts. */ TARGET_ROW_COUNTS,
		/** Carrying row counts. */ VALUE_ROW_COUNTS,
		/** Non-ALL interpretation tags. */ OWNER_FORMS,
		/** Exceptions or holders. */ OWNER_SETS,
		/** Blocked owner enrollment. */ ENROLLED_OWNER_BLOCKS;

		/**
		 * Identifies scalar payloads rather than adaptive record buckets.
		 * @return true for a single-long payload
		 */
		public boolean scalar() {
			return switch (this) {
				case RAW_VALUE_IDS, TARGET_ROW_COUNTS, VALUE_ROW_COUNTS, OWNER_FORMS -> true;
				case TERM_VALUE_IDS, VALUE_TARGETS, TARGET_VALUES, OWNER_SETS, ENROLLED_OWNER_BLOCKS -> false;
			};
		}
	}

	/**
	 * One bounded leaf payload with explicitly typed keys selected by its child kind.
	 * @param pageSequence stable sequence in this field/child stream
	 * @param kind child identity
	 * @param keys ascending raw string, integer or packed long keys
	 * @param scalars one positive scalar per key, empty for bucket streams
	 * @param records one sorted owner/value/target set per key, empty for scalar streams
	 */
	public record ValuePage(
		int pageSequence, @Nonnull ChildKind kind, @Nonnull Object[] keys,
		@Nonnull long[] scalars, @Nonnull Bitmap[] records
	) implements Serializable {
		@Serial private static final long serialVersionUID = 3187090550067696513L;
	}

	/**
	 * Persisted manifest of one child stream, in field-local child-kind order.
	 * @param highWater largest page sequence ever assigned
	 * @param sequences live leaves in key order
	 */
	public record ChildStream(int highWater, @Nonnull int[] sequences) implements Serializable {
		@Serial private static final long serialVersionUID = 5492300178245001926L;
	}

	/**
	 * Root metadata, deliberately independent of the highest live value id.
	 * @param nextValueId allocator high-water including dead holes
	 * @param streams one manifest per child kind in ordinal order
	 */
	public record ValueManifest(int nextValueId, @Nonnull ChildStream[] streams) implements Serializable {
		@Serial private static final long serialVersionUID = 7824993016204976281L;

		/**
		 * Rejects incomplete manifests before attempting to load their pages.
		 * @param nextValueId stable value id allocation position
		 * @param streams every child, in kind order
		 */
		public ValueManifest {
			Assert.isPremiseValid(nextValueId > 0 && streams.length == ChildKind.values().length,
				"A reference value manifest must describe every child and a positive next value id!");
		}
	}

	/** The meaning of a live value/target link's optional owner set. */
	public enum MembershipForm {
		/** Every enrolled owner of the actual partition. */
		ALL,
		/** Every enrolled partition owner except the stored owners. */
		EXCEPT,
		/** Only the stored owners. */
		HOLDERS
	}

	/**
	 * One live row supplied by the mutation's pre/post image; never stored by this dictionary.
	 * @param targetPK referenced entity primary key, which may be zero or negative
	 * @param values distinct non-null raw values, including tokenless text
	 */
	public record RowValues(int targetPK, @Nonnull Set<String> values) {
		/**
		 * Freezes the row values.
		 * @param targetPK referenced entity primary key, which may be zero or negative
		 * @param values distinct raw values
		 */
		public RowValues {
			values = Set.copyOf(values);
		}
	}

	/**
	 * Symbolic match, resolved only within an actual reference partition. Owner sets are read-only tree views.
	 * @param valueId stable raw value id
	 * @param targetPK target whose rows carry this value
	 * @param form interpretation of owners
	 * @param owners exceptions, holders, or empty for ALL
	 */
	public record ValueMatch(
		int valueId, int targetPK, @Nonnull MembershipForm form, @Nonnull Bitmap owners
	) { }

	/** Creates an empty generation. */
	public ReferenceFulltextValueIndex() {
		this(scalarTree(String.class), bucketTree(String.class), bucketTree(Integer.class),
			bucketTree(Integer.class), scalarTree(Integer.class), scalarTree(Long.class), scalarTree(Long.class),
			bucketTree(Long.class), bucketTree(Integer.class), new ValueIdAllocator());
	}

	/**
	 * Adopts children once, at merge or reload.
	 * @param values raw value ids
	 * @param terms analyzed postings
	 * @param valueTargets forward adjacency
	 * @param targetValues reverse adjacency
	 * @param targetCounts enrolled row counts
	 * @param holderCounts carrying row counts
	 * @param subsetForms non-ALL tags
	 * @param ownerSubsets sparse owner sets
	 * @param enrolledOwners enrollment blocks
	 * @param allocator stable id allocator
	 */
	private ReferenceFulltextValueIndex(
		@Nonnull TransactionalBucketBPlusTree<String> values,
		@Nonnull TransactionalBucketBPlusTree<String> terms,
		@Nonnull TransactionalBucketBPlusTree<Integer> valueTargets,
		@Nonnull TransactionalBucketBPlusTree<Integer> targetValues,
		@Nonnull TransactionalBucketBPlusTree<Integer> targetCounts,
		@Nonnull TransactionalBucketBPlusTree<Long> holderCounts,
		@Nonnull TransactionalBucketBPlusTree<Long> subsetForms,
		@Nonnull TransactionalBucketBPlusTree<Long> ownerSubsets,
		@Nonnull TransactionalBucketBPlusTree<Integer> enrolledOwners,
		@Nonnull ValueIdAllocator allocator
	) {
		this.values = values;
		this.terms = terms;
		this.valueTargets = valueTargets;
		this.targetValues = targetValues;
		this.targetCounts = targetCounts;
		this.holderCounts = holderCounts;
		this.subsetForms = subsetForms;
		this.ownerSubsets = ownerSubsets;
		this.enrolledOwners = enrolledOwners;
		this.allocator = allocator;
	}

	/**
	 * Tests enrollment independently of analyzed length, including owners with no values or only stop words.
	 * @param ownerPK owner to test
	 * @return whether this generation already contains the owner's complete rows
	 */
	public boolean isEnrolled(int ownerPK) {
		return this.enrolledOwners.getRecordsEqualTo(ownerPK >>> 16).contains(ownerPK);
	}

	/**
	 * Reconciles one owner's complete pre/post image. Fresh generations ignore the pre-image. Membership is set
	 * from final per-target multiplicities after the whole batch, so a later holder cannot remain in EXCEPT.
	 * @param ownerPK positive owner primary key
	 * @param before live rows immediately before this mutation
	 * @param after live rows immediately after this mutation
	 * @param enroll whether to retain enrollment, false on scope leave/entity removal
	 * @param analyzer this generation's index analyzer
	 * @return whether anything was written; false leaves every child and the transactional layer untouched
	 */
	boolean reconcile(
		int ownerPK, @Nonnull Collection<RowValues> before, @Nonnull Collection<RowValues> after,
		boolean enroll, @Nonnull CompiledFulltextAnalyzer analyzer
	) {
		Assert.isPremiseValid(ownerPK > 0, "Reference fulltext owners must have positive primary keys!");
		final boolean enrolled = isEnrolled(ownerPK);
		if (!enrolled && !enroll) {
			return false;
		}
		final Map<Integer, OwnerTarget> oldTargets = aggregate(enrolled ? before : List.of());
		final Map<Integer, OwnerTarget> newTargets = aggregate(enroll ? after : List.of());
		if (enroll == enrolled && oldTargets.equals(newTargets)) {
			return false;
		}
		Transaction.getOrCreateTransactionalMemoryLayer(this);
		final Set<Integer> targets = CollectionUtils.createHashSet(oldTargets.size() + newTargets.size());
		targets.addAll(oldTargets.keySet());
		targets.addAll(newTargets.keySet());
		final Map<Integer, String> dying = CollectionUtils.createHashMap(4);
		for (final int target : targets) {
			reconcileTarget(ownerPK, target, oldTargets.getOrDefault(target, OwnerTarget.EMPTY),
				newTargets.getOrDefault(target, OwnerTarget.EMPTY), analyzer, dying);
		}
		// Delay final deaths until every target is reconciled: moving a value must retain its id and analysis.
		for (final Map.Entry<Integer, String> candidate : dying.entrySet()) {
			if (this.valueTargets.getRecordsEqualTo(candidate.getKey()).isEmpty()) {
				this.values.removeLongRecord(candidate.getValue());
				for (final String term : analyze(candidate.getValue(), analyzer)) {
					this.terms.removeRecord(term, candidate.getKey());
				}
			}
		}
		if (enroll && !enrolled) {
			this.enrolledOwners.addRecord(ownerPK >>> 16, ownerPK);
		} else if (!enroll && enrolled) {
			this.enrolledOwners.removeRecord(ownerPK >>> 16, ownerPK);
		}
		return true;
	}

	/**
	 * Finds all raw values matching any supplied analyzed term, with one form per target/value.
	 * @param analyzedTerms retained exact/expanded terms of one query word
	 * @return symbolic matches, without enumerating any target's owners
	 */
	@Nonnull
	public List<ValueMatch> lookup(@Nonnull Set<String> analyzedTerms) {
		final BaseBitmap matching = new BaseBitmap();
		for (final String term : analyzedTerms) {
			matching.addAll(this.terms.getRecordsEqualTo(term).getArray());
		}
		final List<ValueMatch> result = new ArrayList<>();
		for (final int value : matching.getArray()) {
			for (final int target : this.valueTargets.getRecordsEqualTo(value).getArray()) {
				final long pair = NumberUtils.pack(value, target);
				final MembershipForm form = decodeForm(this.subsetForms.getLongRecordEqualTo(pair).orElse(FORM_ALL));
				result.add(new ValueMatch(value, target, form, this.ownerSubsets.getRecordsEqualTo(pair)));
			}
		}
		return result;
	}

	/**
	 * Resolves a symbolic match against the owners of its actual materialized or virtual reference partition.
	 * @param match a match returned by this generation
	 * @param partitionOwners owners of that target's actual body partition
	 * @return a newly owned bitmap of matching owners
	 */
	@Nonnull
	public Bitmap resolve(@Nonnull ValueMatch match, @Nonnull Bitmap partitionOwners) {
		final PersistentRoaringBitmap partition = RoaringBitmapBackedBitmap.getRoaringBitmap(partitionOwners);
		if (match.form() == MembershipForm.HOLDERS) {
			return new BaseBitmap(PersistentRoaringBitmap.and(
				partition, RoaringBitmapBackedBitmap.getRoaringBitmap(match.owners())));
		}
		final PersistentRoaringBitmap result = new PersistentRoaringBitmap();
		if (!partitionOwners.isEmpty()) {
			final int lastBlock = partitionOwners.getLast() >>> 16;
			final BucketCursor<Integer> blocks = this.enrolledOwners.cursor(partitionOwners.getFirst() >>> 16);
			while (blocks.next() && blocks.value() <= lastBlock) {
				result.or(PersistentRoaringBitmap.and(
					partition, RoaringBitmapBackedBitmap.getRoaringBitmap(blocks.records())));
			}
		}
		if (match.form() == MembershipForm.EXCEPT) {
			result.andNot(RoaringBitmapBackedBitmap.getRoaringBitmap(match.owners()));
		}
		return new BaseBitmap(result);
	}

	/**
	 * Decodes the persisted tag, refusing impossible internal states.
	 * @param tag the stored form tag of a value/target link, `FORM_ALL` when the link has none
	 * @return the membership form the tag stands for
	 */
	@Nonnull
	private static MembershipForm decodeForm(long tag) {
		return switch (Math.toIntExact(tag)) {
			case FORM_ALL -> MembershipForm.ALL;
			case FORM_EXCEPT -> MembershipForm.EXCEPT;
			case FORM_HOLDERS -> MembershipForm.HOLDERS;
			default -> throw new GenericEvitaInternalError("Unknown reference membership form: " + tag);
		};
	}

	/**
	 * Changes only one target using this owner's row multiplicities. No other owner's body is read.
	 * @param owner affected owner
	 * @param target affected target
	 * @param before previous owner multiplicities
	 * @param after final owner multiplicities
	 * @param analyzer generation analyzer for value birth/death
	 * @param dying candidates for final value death after all target changes
	 */
	private void reconcileTarget(
		int owner, int target, @Nonnull OwnerTarget before, @Nonnull OwnerTarget after,
		@Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull Map<Integer, String> dying
	) {
		if (before.equals(after)) {
			return;
		}
		final long oldRows = this.targetCounts.getLongRecordEqualTo(target).orElse(0L);
		final long rows = Math.addExact(Math.subtractExact(oldRows, before.rows()), after.rows());
		Assert.isPremiseValid(rows >= 0L, "Reference fulltext row count underflow!");
		final Set<String> changed = CollectionUtils.createHashSet(before.counts().size() + after.counts().size());
		changed.addAll(before.counts().keySet());
		changed.addAll(after.counts().keySet());
		final Map<Integer, String> rawValues = CollectionUtils.createHashMap(changed.size());
		final Set<Integer> affected = CollectionUtils.createHashSet(changed.size());
		for (final String raw : changed) {
			int value = (int) this.values.getLongRecordEqualTo(raw).orElse(0L);
			if (value == 0 && after.counts().containsKey(raw)) {
				value = this.allocator.allocate();
				this.values.addLongRecord(raw, value);
				for (final String term : analyze(raw, analyzer)) {
					this.terms.addRecord(term, value);
				}
			}
			Assert.isPremiseValid(value > 0, "An enrolled reference value must exist!");
			rawValues.put(value, raw);
			affected.add(value);
		}
		if (before.rows() != after.rows()) {
			// Copy ids before changing reverse adjacency, whose live views would otherwise be invalidated.
			for (final int value : this.targetValues.getRecordsEqualTo(target).getArray()) {
				affected.add(value);
			}
		}
		for (final int value : affected) {
			final String raw = rawValues.get(value);
			final long beforeCount = raw == null ? 0L : before.counts().getOrDefault(raw, 0L);
			final long afterCount = raw == null ? 0L : after.counts().getOrDefault(raw, 0L);
			final long pair = NumberUtils.pack(value, target);
			final long oldCount = this.holderCounts.getLongRecordEqualTo(pair).orElse(0L);
			final long count = Math.addExact(Math.subtractExact(oldCount, beforeCount), afterCount);
			Assert.isPremiseValid(count >= 0L && count <= rows, "Invalid reference fulltext holder count!");
			if (count == 0L) {
				this.holderCounts.removeLongRecord(pair);
				clearSubset(pair);
				this.valueTargets.removeRecord(value, target);
				this.targetValues.removeRecord(target, value);
				if (this.valueTargets.getRecordsEqualTo(value).isEmpty()) {
					Assert.isPremiseValid(raw != null, "A dying reference value must have its raw text!");
					dying.put(value, raw);
				}
			} else {
				setScalar(this.holderCounts, pair, count);
				if (oldCount == 0L) {
					this.valueTargets.addRecord(value, target);
					this.targetValues.addRecord(target, value);
				}
				if (count == rows) {
					// every row of the target carries the value: ALL keeps neither a tag nor a set
					clearSubset(pair);
				} else {
					// a link born partial starts as HOLDERS; an existing one keeps its tag, and untagged ALL demotes
					// to EXCEPT
					final long form = oldCount == 0L ? FORM_HOLDERS :
						this.subsetForms.getLongRecordEqualTo(pair).orElse(FORM_EXCEPT);
					setScalar(this.subsetForms, pair, form);
					// EXCEPT lists owners with rows to the target but none carrying the value, HOLDERS lists carriers
					final boolean member = form == FORM_EXCEPT ?
						after.rows() > 0L && afterCount == 0L : afterCount > 0L;
					if (member != this.ownerSubsets.getRecordsEqualTo(pair).contains(owner)) {
						if (member) {
							this.ownerSubsets.addRecord(pair, owner);
						} else {
							this.ownerSubsets.removeRecord(pair, owner);
						}
					}
				}
			}
		}
		if (rows == 0L) {
			this.targetCounts.removeLongRecord(target);
		} else {
			setScalar(this.targetCounts, target, rows);
		}
	}

	/**
	 * Drops both the tag and any set, including the legal empty EXCEPT representation.
	 * @param pair packed value/target identity
	 */
	private void clearSubset(long pair) {
		this.subsetForms.removeLongRecord(pair);
		final int[] owners = this.ownerSubsets.getRecordsEqualTo(pair).getArray();
		if (owners.length > 0) {
			this.ownerSubsets.removeRecord(pair, owners);
		}
	}

	/**
	 * Replaces an existing scalar or inserts its first value.
	 * @param tree scalar child
	 * @param key identity to replace
	 * @param value new positive payload
	 * @param <K> key type
	 */
	private static <K extends Comparable<K>> void setScalar(
		@Nonnull TransactionalBucketBPlusTree<K> tree, @Nonnull K key, long value
	) {
		if (tree.getLongRecordEqualTo(key).orElse(0L) != value) {
			tree.removeLongRecord(key);
			tree.addLongRecord(key, value);
		}
	}

	/**
	 * Prepares the distinct term set on raw value birth/death, never on a query.
	 * @param raw exact raw text
	 * @param analyzer generation analyzer
	 * @return distinct analyzed terms, possibly empty
	 */
	@Nonnull
	private static Set<String> analyze(@Nonnull String raw, @Nonnull CompiledFulltextAnalyzer analyzer) {
		final Set<String> result = CollectionUtils.createHashSet(8);
		analyzer.indexSurface().analyzeIndexed(raw,
			(term, folded, surface, start, end, increment) -> result.add(term));
		return result;
	}

	/**
	 * Counts this owner's rows and distinct values per target; retained only for this mutation.
	 * @param rows complete owner image
	 * @return per-target multiplicities
	 */
	@Nonnull
	private static Map<Integer, OwnerTarget> aggregate(@Nonnull Collection<RowValues> rows) {
		final Map<Integer, OwnerTarget> result = CollectionUtils.createHashMap(rows.size());
		for (final RowValues row : rows) {
			final OwnerTarget target = result.computeIfAbsent(row.targetPK(),
				ignored -> new OwnerTarget(0L, CollectionUtils.createHashMap(4)));
			for (final String value : row.values()) {
				target.counts().merge(value, 1L, Math::addExact);
			}
			result.put(row.targetPK(), new OwnerTarget(Math.addExact(target.rows(), 1L), target.counts()));
		}
		return result;
	}

	/**
	 * Transient multiplicities of an owner's rows to one target.
	 * @param rows live row count
	 * @param counts rows carrying each distinct raw value
	 */
	private record OwnerTarget(long rows, @Nonnull Map<String, Long> counts) {
		/** The image of an owner without rows to the target. */
		private static final OwnerTarget EMPTY = new OwnerTarget(0L, Map.of());
	}

	/**
	 * Creates a primitive scalar tree with the common bounded page layout.
	 * @param keyType exact raw key class
	 * @param <K> key type
	 * @return empty scalar tree
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static <K extends Comparable<K>> TransactionalBucketBPlusTree<K> scalarTree(@Nonnull Class<K> keyType) {
		return TransactionalBucketBPlusTree.withLongPayload(
			LEAF_CAPACITY, MIN_LEAF_SIZE, MIN_LEAF_SIZE, MIN_INTERNAL_NODE_SIZE, keyType, null,
			(ValueColumnFactory<K>) ValueColumnFactory.forKey(keyType, null));
	}

	/**
	 * Creates an adaptive bitmap-bucket tree with the common bounded page layout.
	 * @param keyType exact raw key class
	 * @param <K> key type
	 * @return empty bucket tree
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static <K extends Comparable<K>> TransactionalBucketBPlusTree<K> bucketTree(@Nonnull Class<K> keyType) {
		return new TransactionalBucketBPlusTree<>(
			LEAF_CAPACITY, MIN_LEAF_SIZE, MIN_LEAF_SIZE, MIN_INTERNAL_NODE_SIZE, keyType, null,
			(ValueColumnFactory<K>) ValueColumnFactory.forKey(keyType, null));
	}

	/**
	 * Derives a checked local registry stream number, reserving 0/1 for the parent dictionaries.
	 * @param fieldId immutable field generation id
	 * @param kind child identity
	 * @return nonnegative stream number, never reused by a later generation
	 */
	public static int streamNumber(int fieldId, @Nonnull ChildKind kind) {
		Assert.isPremiseValid(fieldId >= 0, "A reference stream requires a nonnegative field id!");
		return Math.addExact(2, Math.addExact(Math.multiplyExact(fieldId, ChildKind.values().length), kind.ordinal()));
	}

	/**
	 * Emits changed leaves of each child and stages its complete footprint in the parent-owned registry.
	 * @param fieldId owning field generation
	 * @param registry owning fulltext index registry
	 * @return one emission per child kind
	 */
	@Nonnull
	public List<PageEmission<ValuePage>> collectChangedPages(int fieldId, @Nonnull PageStreamRegistry registry) {
		final List<PageEmission<ValuePage>> result = new ArrayList<>(ChildKind.values().length);
		for (final ChildKind kind : ChildKind.values()) {
			result.add(collectTree(tree(kind), fieldId, kind, registry));
		}
		return result;
	}

	/**
	 * Captures allocation metadata after collecting child pages.
	 * @param fieldId owning generation
	 * @param registry owning page registry
	 * @return root manifest retaining high-water marks and every child page
	 */
	@Nonnull
	public ValueManifest manifest(int fieldId, @Nonnull PageStreamRegistry registry) {
		final ChildStream[] streams = new ChildStream[ChildKind.values().length];
		for (final ChildKind kind : ChildKind.values()) {
			final int stream = streamNumber(fieldId, kind);
			final List<? extends LeafPageHandle<?>> leaves = tree(kind).leafPageHandles();
			final int[] ordered = new int[leaves.size()];
			for (int i = 0; i < ordered.length; i++) {
				ordered[i] = leaves.get(i).getPageSequence();
			}
			streams[kind.ordinal()] = new ChildStream(registry.highWater(stream), ordered);
		}
		return new ValueManifest(this.allocator.getNextValueId(), streams);
	}

	/**
	 * Restores a cold-loaded dictionary's page streams in its parent's registry.
	 * @param fieldId owning generation
	 * @param registry owning registry
	 * @param manifest previously validated root metadata
	 */
	public void restorePageStreams(int fieldId, @Nonnull PageStreamRegistry registry, @Nonnull ValueManifest manifest) {
		for (final ChildKind kind : ChildKind.values()) {
			final ChildStream stream = manifest.streams()[kind.ordinal()];
			registry.restore(streamNumber(fieldId, kind), stream.highWater(), stream.sequences());
		}
	}

	/**
	 * Builds all children from actual persisted leaves, preserving raw ids without running analysis.
	 * @param manifest allocator and child page metadata
	 * @param pages child pages in child-kind order and then key order
	 * @return validated restored dictionary
	 */
	@Nonnull
	public static ReferenceFulltextValueIndex fromPersistedPages(
		@Nonnull ValueManifest manifest, @Nonnull List<List<ValuePage>> pages
	) {
		Assert.isPremiseValid(pages.size() == ChildKind.values().length, "Missing reference dictionary children!");
		final ReferenceFulltextValueIndex result = new ReferenceFulltextValueIndex(
			loadTree(String.class, ChildKind.RAW_VALUE_IDS, manifest, pages),
			loadTree(String.class, ChildKind.TERM_VALUE_IDS, manifest, pages),
			loadTree(Integer.class, ChildKind.VALUE_TARGETS, manifest, pages),
			loadTree(Integer.class, ChildKind.TARGET_VALUES, manifest, pages),
			loadTree(Integer.class, ChildKind.TARGET_ROW_COUNTS, manifest, pages),
			loadTree(Long.class, ChildKind.VALUE_ROW_COUNTS, manifest, pages),
			loadTree(Long.class, ChildKind.OWNER_FORMS, manifest, pages),
			loadTree(Long.class, ChildKind.OWNER_SETS, manifest, pages),
			loadTree(Integer.class, ChildKind.ENROLLED_OWNER_BLOCKS, manifest, pages),
			new ValueIdAllocator(manifest.nextValueId())
		);
		result.validateLoadedState();
		return result;
	}

	/**
	 * Selects the child without maintaining another mutable directory.
	 * @param kind child identity
	 * @return child tree
	 */
	@Nonnull
	private TransactionalBucketBPlusTree<?> tree(@Nonnull ChildKind kind) {
		return switch (kind) {
			case RAW_VALUE_IDS -> this.values;
			case TERM_VALUE_IDS -> this.terms;
			case VALUE_TARGETS -> this.valueTargets;
			case TARGET_VALUES -> this.targetValues;
			case TARGET_ROW_COUNTS -> this.targetCounts;
			case VALUE_ROW_COUNTS -> this.holderCounts;
			case OWNER_FORMS -> this.subsetForms;
			case OWNER_SETS -> this.ownerSubsets;
			case ENROLLED_OWNER_BLOCKS -> this.enrolledOwners;
		};
	}

	/**
	 * Copies only dirty leaves to stream payloads.
	 * @param tree child to collect
	 * @param fieldId owning generation
	 * @param kind child identity
	 * @param registry owning registry
	 * @param <K> child key type
	 * @return staged page emission
	 */
	@Nonnull
	private static <K extends Comparable<K>> PageEmission<ValuePage> collectTree(
		@Nonnull TransactionalBucketBPlusTree<K> tree, int fieldId, @Nonnull ChildKind kind,
		@Nonnull PageStreamRegistry registry
	) {
		return registry.collectChangedPages(streamNumber(fieldId, kind), tree.leafPageHandles(), (sequence, handle) -> {
			final List<Object> keys = new ArrayList<>();
			final List<Long> scalars = new ArrayList<>();
			final List<Bitmap> records = new ArrayList<>();
			final BucketCursor<K> cursor = handle.cursor();
			while (cursor.next()) {
				keys.add(cursor.value());
				if (kind.scalar()) {
					scalars.add(cursor.longRecordId());
				} else {
					records.add(new BaseBitmap(cursor.records()));
				}
			}
			final long[] payloads = new long[scalars.size()];
			for (int i = 0; i < payloads.length; i++) {
				payloads[i] = scalars.get(i);
			}
			return new ValuePage(sequence, kind, keys.toArray(), payloads, records.toArray(Bitmap[]::new));
		});
	}

	/**
	 * Restores one typed child, rejecting empty intermediate pages and overlapping or malformed keys.
	 * @param keyType expected key class
	 * @param kind expected child identity
	 * @param manifest persisted child descriptors
	 * @param children actual child payloads
	 * @param <K> key type
	 * @return assembled child with clean leaves
	 */
	@Nonnull
	private static <K extends Comparable<K>> TransactionalBucketBPlusTree<K> loadTree(
		@Nonnull Class<K> keyType, @Nonnull ChildKind kind, @Nonnull ValueManifest manifest,
		@Nonnull List<List<ValuePage>> children
	) {
		final ChildStream stream = manifest.streams()[kind.ordinal()];
		final List<ValuePage> pages = children.get(kind.ordinal());
		Assert.isPremiseValid(!pages.isEmpty() && pages.size() == stream.sequences().length,
			"Reference dictionary page count does not match its manifest!");
		final TransactionalBucketBPlusTree<K> prototype = kind.scalar() ? scalarTree(keyType) : bucketTree(keyType);
		final List<TransactionalBucketBPlusTree<K>> leaves = new ArrayList<>(pages.size());
		K previous = null;
		for (int i = 0; i < pages.size(); i++) {
			final ValuePage page = pages.get(i);
			Assert.isPremiseValid(page.kind() == kind && page.pageSequence() == stream.sequences()[i] &&
				page.pageSequence() >= 0 && page.pageSequence() <= stream.highWater(),
				"Reference dictionary page identity does not match its manifest!");
			final int count = page.keys().length;
			// a live leaf splits as soon as an insert fills it and merges only below capacity: no stored page is full
			Assert.isPremiseValid((count > 0 || pages.size() == 1) && count < LEAF_CAPACITY &&
				(kind.scalar() ? page.scalars().length == count && page.records().length == 0 :
					page.records().length == count && page.scalars().length == 0),
				"Invalid reference dictionary page columns!");
			final long[] payloads = new long[count];
			final Object[] overflow = new Object[count];
			for (int slot = 0; slot < count; slot++) {
				final K key = keyType.cast(page.keys()[slot]);
				Assert.isPremiseValid(key != null && (previous == null || previous.compareTo(key) < 0),
					"Reference dictionary keys must be strictly ordered!");
				previous = key;
				if (kind.scalar()) {
					Assert.isPremiseValid(page.scalars()[slot] > 0L, "Reference dictionary scalars must be positive!");
					payloads[slot] = page.scalars()[slot];
				} else {
					final Bitmap records = page.records()[slot];
					// value ids and owners are positive, reference targets may be zero or negative
					Assert.isPremiseValid(!records.isEmpty() &&
							(kind == ChildKind.VALUE_TARGETS || records.getFirst() > 0),
						"Reference dictionary buckets must contain positive ids!");
					if (records.size() == 1) {
						payloads[slot] = records.getFirst();
					} else {
						overflow[slot] = OverflowRecords.loadedRecordSet(new BaseBitmap(records));
					}
				}
			}
			final TransactionalBucketBPlusTree<K> leaf = kind.scalar() ? scalarTree(keyType) : bucketTree(keyType);
			if (count > 0) {
				leaf.bulkLoadPage(page.keys(), payloads, kind.scalar() ? null : overflow, null, null, count);
			}
			leaves.add(leaf);
		}
		if (pages.size() == 1 && pages.getFirst().keys().length == 0) {
			prototype.leafPageHandles().getFirst().setPageSequence(stream.sequences()[0]);
			return prototype;
		}
		return prototype.assembleFromSingleLeafTrees(leaves, stream.sequences(), "reference fulltext " + kind);
	}

	/** Validates persisted relations without compiling analyzers or reading owner bodies. */
	private void validateLoadedState() {
		final Set<Integer> ids = CollectionUtils.createHashSet(this.values.size());
		final BucketCursor<String> raw = this.values.cursor();
		while (raw.next()) {
			final long value = raw.longRecordId();
			Assert.isPremiseValid(value > 0 && value < this.allocator.getNextValueId() && ids.add((int) value),
				"Invalid or duplicate reference value id!");
			Assert.isPremiseValid(!this.valueTargets.getRecordsEqualTo((int) value).isEmpty(),
				"A reference raw value must have a live target!");
		}
		Assert.isPremiseValid(this.targetCounts.size() == 0 || this.enrolledOwners.size() > 0,
			"Live reference rows require enrolled owners!");
		final BucketCursor<Integer> enrollment = this.enrolledOwners.cursor();
		while (enrollment.next()) {
			for (final int owner : enrollment.records().getArray()) {
				Assert.isPremiseValid((owner >>> 16) == enrollment.value(), "Invalid reference enrollment block!");
			}
		}
		final BucketCursor<Integer> forward = this.valueTargets.cursor();
		while (forward.next()) {
			Assert.isPremiseValid(ids.contains(forward.value()), "Unknown reference value in adjacency!");
			for (final int target : forward.records().getArray()) {
				Assert.isPremiseValid(this.targetValues.getRecordsEqualTo(target).contains(forward.value()),
					"Missing reciprocal reference value adjacency!");
				final long pair = NumberUtils.pack(forward.value(), target);
				final long count = this.holderCounts.getLongRecordEqualTo(pair).orElse(0L);
				final long rows = this.targetCounts.getLongRecordEqualTo(target).orElse(0L);
				final MembershipForm form = decodeForm(this.subsetForms.getLongRecordEqualTo(pair).orElse(FORM_ALL));
				Assert.isPremiseValid(count > 0 && count <= rows &&
					(count == rows ? form == MembershipForm.ALL : form != MembershipForm.ALL),
					"Invalid reference counts or membership form!");
				final Bitmap owners = this.ownerSubsets.getRecordsEqualTo(pair);
				Assert.isPremiseValid(count != rows || owners.isEmpty(), "ALL cannot store an owner set!");
				Assert.isPremiseValid(form != MembershipForm.HOLDERS || !owners.isEmpty(),
					"HOLDERS must hold an owner!");
				Assert.isPremiseValid(switch (form) {
					case ALL -> owners.isEmpty();
					case HOLDERS -> owners.size() <= count;
					case EXCEPT -> owners.size() <= rows - count;
				}, "Reference owner cardinality exceeds its row count!");
				for (final int owner : owners.getArray()) {
					Assert.isPremiseValid(isEnrolled(owner), "An owner subset names an unenrolled owner!");
				}
			}
		}
		final BucketCursor<Integer> reverse = this.targetValues.cursor();
		while (reverse.next()) {
			for (final int value : reverse.records().getArray()) {
				Assert.isPremiseValid(this.valueTargets.getRecordsEqualTo(value).contains(reverse.value()),
					"Missing forward reference value adjacency!");
			}
		}
		for (final ChildKind kind : List.of(ChildKind.VALUE_ROW_COUNTS, ChildKind.OWNER_FORMS, ChildKind.OWNER_SETS)) {
			final BucketCursor<?> cursor = tree(kind).cursor();
			while (cursor.next()) {
				final long pair = (Long) cursor.value();
				Assert.isPremiseValid(this.valueTargets.getRecordsEqualTo(NumberUtils.unpackHigh(pair))
					.contains(NumberUtils.unpackLow(pair)), "Orphaned reference value count or owner set!");
			}
		}
		final BucketCursor<String> analyzed = this.terms.cursor();
		while (analyzed.next()) {
			for (final int value : analyzed.records().getArray()) {
				Assert.isPremiseValid(ids.contains(value), "A reference term names an unknown value!");
			}
		}
	}

	/**
	 * Reports every owned child and allocator, including empty trees and explicit form tags.
	 * @return owned heap in bytes
	 */
	public long getHeapSizeInBytes() {
		return VMLayout.current().sizeOfObject(Long.BYTES + 10L * VMLayout.current().referenceSize())
			+ this.values.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.terms.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.valueTargets.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.targetValues.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.targetCounts.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.holderCounts.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.subsetForms.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.ownerSubsets.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.enrolledOwners.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.allocator.getHeapSizeInBytes();
	}

	/** Marker layer; actual changes and their savepoint mementos belong to the children. */
	static final class Changes implements Snapshotable<Boolean> {
		@Override
		@Nonnull
		public Boolean snapshot() {
			return Boolean.TRUE;
		}

		@Override
		public void restore(@Nonnull Boolean memento) {
			// nothing to restore: every child restores its own savepoint memento
		}
	}

	@Nonnull
	@Override
	public Changes createLayer() {
		return new Changes();
	}

	@Nonnull
	@Override
	public ReferenceFulltextValueIndex createCopyWithMergedTransactionalMemory(
		@Nullable Changes layer, @Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return layer == null ? this : new ReferenceFulltextValueIndex(
			transactionalLayer.getStateCopyWithCommittedChanges(this.values),
			transactionalLayer.getStateCopyWithCommittedChanges(this.terms),
			transactionalLayer.getStateCopyWithCommittedChanges(this.valueTargets),
			transactionalLayer.getStateCopyWithCommittedChanges(this.targetValues),
			transactionalLayer.getStateCopyWithCommittedChanges(this.targetCounts),
			transactionalLayer.getStateCopyWithCommittedChanges(this.holderCounts),
			transactionalLayer.getStateCopyWithCommittedChanges(this.subsetForms),
			transactionalLayer.getStateCopyWithCommittedChanges(this.ownerSubsets),
			transactionalLayer.getStateCopyWithCommittedChanges(this.enrolledOwners),
			transactionalLayer.getStateCopyWithCommittedChanges(this.allocator)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.values.removeLayer(transactionalLayer);
		this.terms.removeLayer(transactionalLayer);
		this.valueTargets.removeLayer(transactionalLayer);
		this.targetValues.removeLayer(transactionalLayer);
		this.targetCounts.removeLayer(transactionalLayer);
		this.holderCounts.removeLayer(transactionalLayer);
		this.subsetForms.removeLayer(transactionalLayer);
		this.ownerSubsets.removeLayer(transactionalLayer);
		this.enrolledOwners.removeLayer(transactionalLayer);
		this.allocator.removeLayer(transactionalLayer);
	}

	@Override
	public boolean supportsWarmUpRollback() {
		return true;
	}
}
