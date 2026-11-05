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
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bPlusTree.OverflowRecords;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlockEmission;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.typo.FulltextDictionaryWalker;
import io.evitadb.index.fulltext.typo.FulltextDictionaryWalker.MatchVisitor;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.index.map.MapHeapSize;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockPart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.MemoryMeasuringConstants;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fulltext index of one partition — one (collection, locale, scope) — holding the term dictionary of every
 * searchable field in it, the impact of every posting, and the length of every indexed value.
 *
 * ## What it is made of
 *
 * - **The term dictionary**, one {@link TransactionalBucketBPlusTree} keyed by {@link FulltextTermKeys} — a field
 *   prefix followed by the analyzed term — whose bucket is the term's **posting list**: the primary keys of the
 *   entities whose field contains the term. The keys live in the front-coded string column, which is what the
 *   bucket tree selects for `String` keys, so neighbouring terms of one field share their stored prefix.
 * - **The surface dictionary**, a second bucket tree holding `(word phase, folded surface, stem, display)` keys
 *   and a primitive reference count per pair, with no postings. Profiles share entries by their words-phase
 *   fingerprint, mapped to a short fixed-width prefix in a persisted transactional table. The folded component is
 *   the index input after embedded lookup normalization, or the final token without stemming. NUL frames
 *   the components; NUL and SOH within tokens are escaped. Empty stem and display components mean folded identity.
 * - **The impacts**, one unsigned byte beside every posting, stored by the dictionary itself in its impact column
 *   so that the n-th impact of a term always belongs to its n-th posting — by construction, through every insert,
 *   split, merge and commit, rather than by bookkeeping kept in step from outside.
 * - **A {@link FieldLengthTable} per field**, the length of every indexed value in tokens.
 * - **Field ids**, assigned on first use of a {@link FulltextFieldKey}, which become the key prefix. The id of a field
 *   never changes for the life of the index, so a key once written always means the same field.
 *
 * ## Retired fields
 *
 * A field whose source stopped being searchable is **retired** ({@link #retireField(FulltextFieldKey)}): its key no
 * longer resolves to its id, its postings and lengths stay where they are, and nothing writes to it again. Should the
 * source become searchable once more, its key is registered anew under a fresh id and starts empty. Removing the old
 * postings would take every value the field ever indexed, which only a reindex has; leaving them reachable would let a
 * re-added field return entities whose values changed while it was not searchable. A retired field is dead weight until
 * a reindex drops it - a query over the re-added field may miss entities, but it never finds a wrong one.
 *
 * ## The impact byte
 *
 * The impact of a posting is BM25's term-frequency saturation with the field length normalized against a **pivot**,
 * quantized to `1..255` (see {@link #computeImpact(int, int, double)}). The pivot is a fixed parameter of the field,
 * not the corpus' average length: an average moves with every write, which would make every stored impact stale the
 * moment the next document arrives and make two replicas of one catalog disagree about a score. With a fixed pivot
 * the impact is a function of the query term and the document alone. The pivot is
 * supplied by the field schema at each write and recorded with its field generation.
 *
 * ## Why the key order matters
 *
 * Every key of a field is contiguous and ordered by term, so all the terms of one field — or all its terms sharing a
 * prefix, which is what a prefix query expands — are a single forward cursor walk from the encoded lower bound,
 * ending at the first key that no longer carries the prefix. {@link #forEachTerm(int, String, TermVisitor)} is that
 * walk.
 *
 * ## Multi-valued fields
 *
 * An array value - `String[]` - is indexed as **one text**: every element is analyzed on its own, and the term
 * frequencies and lengths of all of them are summed into one entry per (field, entity), exactly as if the elements
 * had been written one after another. This is how a multi-valued field behaves in Lucene's BM25, and it keeps the
 * length table and the impacts single-valued. Analyzing the elements separately keeps a token from ever spanning two
 * of them. The index stores no positions, so two adjacent elements cannot form a phrase match either; a later
 * position-aware feature would have to leave a gap between them.
 *
 * Because the entity has one entry, changing one element is a removal of the whole old array followed by an
 * addition of the whole new one - the same update protocol as for a single value.
 *
 * ## Transactional behaviour
 *
 * Each part versions itself: both dictionaries through their bucket trees' node layers (the impacts ride along in the
 * term dictionary's leaves, the reference counts in the surface dictionary's), the word-phase table as a
 * {@link TransactionalMap}, every length table through its own layer, and the field registry through this index's
 * {@link FulltextIndexChanges}, which holds the fields a transaction registered and retired. Every write also creates
 * that layer, so it doubles as the "written in this transaction" mark: an index no transaction wrote to is carried
 * forward as the same instance - keeping its identity, which is what a consumer keys a cache on - and none of its
 * parts is merged.
 *
 * Outside a transaction (the warm-up bulk path) everything is written in place, and each part journals its writes
 * into an open warm-up savepoint; the registry journals a field's registration and retirement, so a rolled-back entity
 * mutation that introduced a field leaves no field behind, and one that retired a field leaves it in service. Inside a
 * transaction a per-entity savepoint rewinds every part through its layer's memento.
 *
 * ## Persistence
 *
 * Both dictionaries are written in pages, one per leaf, the way the inverted index writes its buckets: a separate dirty
 * flag - "written since the last flush", on both paths - gates the flush, {@link #collectChangedPages()} and
 * {@link #collectChangedSurfaceFormPages()} emit changed leaves and removed pages. A {@link PageStreamRegistry}
 * keeps the page sequences and the
 * baseline the next flush diffs against, carried by reference through every merge. The field length tables are
 * written in pages of their own, one per 65,536-key block - see {@link #collectChangedLengthBlocks()}.
 *
 * Not thread-safe for writes - one writer at a time, as with every index structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class FulltextIndex implements TransactionalLayerProducer<FulltextIndexChanges, FulltextIndex> {

	/**
	 * Returned by {@link #getFieldId(FulltextFieldKey)} for a field the index has never seen, or has retired.
	 */
	public static final int UNKNOWN_FIELD_ID = -1;

	/**
	 * The highest field id the index assigns: the lower of what the term key prefix addresses and what a persisted
	 * length block can name. A field the index accepts must also be one it can flush - checking only the prefix
	 * would let the write succeed and fail every flush after it.
	 */
	public static final int MAX_FIELD_ID = Math.min(
		FulltextTermKeys.MAX_FIELD_ID, FulltextFieldLengthBlockPart.MAX_FIELD_ID
	);

	/**
	 * BM25's term-frequency saturation, `k1`. The customary value; it has not been tuned for evitaDB.
	 */
	public static final double BM25_K1 = 1.2;

	/**
	 * BM25's length normalization strength, `b`. The customary value; it has not been tuned for evitaDB.
	 */
	public static final double BM25_B = 0.75;

	/**
	 * Largest impact a posting can carry.
	 */
	public static final int MAX_IMPACT = 255;

	/**
	 * Maximum number of buckets (terms) in a leaf of the dictionary. The value the index-core measurements of the
	 * fulltext decision record were taken with, and the one the inverted index uses for its own value trees.
	 */
	private static final int VALUE_BLOCK_SIZE = 256;

	/**
	 * Minimum number of buckets in a leaf of the dictionary.
	 */
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;

	/**
	 * Minimum number of keys in an internal node of the dictionary.
	 */
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);

	/**
	 * Local key of the postings dictionary's page stream in {@link #pageStreamRegistry}. The storage stream id is
	 * assigned by the catalog's key compressor when pages are written.
	 */
	private static final int DICTIONARY_PAGE_STREAM = 0;

	/** Local page stream of the surface dictionary, separate from the postings dictionary. */
	private static final int SURFACE_FORM_PAGE_STREAM = 1;

	/**
	 * The field registry of an index without a field, shared by every such index.
	 */
	private static final Field[] NO_FIELDS = new Field[0];

	/**
	 * Identity of this instance in the transactional memory.
	 */
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * Field key to its id, for the fields committed with this instance (and, outside a transaction, registered since)
	 * that are not retired - a key resolves to one field at most.
	 */
	@Nonnull private final Map<FulltextFieldKey, Integer> fieldIds;

	/**
	 * Per-field state indexed by the field's id, for every field committed with this instance (and, outside a
	 * transaction, registered since) - retired ones included, whereas {@link #fieldIds} drops them. A transaction's
	 * own registrations follow them, in its {@link FulltextIndexChanges}. Copy-on-write: a field is registered a
	 * handful of times in the life of an index, so the array is replaced rather than grown, and its length is the
	 * field count.
	 */
	@Nonnull private Field[] fields;

	/**
	 * The term dictionary: key is {@link FulltextTermKeys#encode(int, String)}, bucket is the posting list, and every
	 * posting carries its impact.
	 */
	@Nonnull private final TransactionalBucketBPlusTree<String> dictionary;

	/** Surface keys carry one primitive reference count and no postings. */
	@Nonnull private final TransactionalBucketBPlusTree<String> surfaceForms;

	/** Persistent word-phase fingerprints mapped to short, reusable lane prefixes. */
	@Nonnull private final TransactionalMap<Long, WordPhaseId> wordPhaseIds;

	/**
	 * Whether anything was written to the index since its last flush collected it - the gate of the flush. Set by
	 * every write, inside a transaction and outside one alike, and cleared by {@link #resetDirty()} once the flush has
	 * collected the changes.
	 */
	@Nonnull private final TransactionalBoolean dirty;

	/**
	 * The page bookkeeping of both dictionaries: the page sequence allocator, high-water and pages on disk
	 * the next flush diffs against. Owner-resident and not transactional - single-writer flush bookkeeping, carried by
	 * reference into the committed copy at every merge, exactly as {@link io.evitadb.index.invertedIndex.InvertedIndex}
	 * carries its own.
	 */
	@Nonnull @Getter private final PageStreamRegistry pageStreamRegistry;

	/**
	 * Visitor of the terms a {@link #forEachTerm(int, String, TermVisitor)} walk reaches.
	 */
	@FunctionalInterface
	public interface TermVisitor {

		/**
		 * Visits one term of the walked field.
		 *
		 * @param term     the analyzed term, without the field prefix
		 * @param postings the term's posting list; a read-only view of the dictionary's own storage
		 * @param impacts  the impacts of the postings, the i-th belonging to the i-th posting; a read-only view of
		 *                 the dictionary's own storage, which is never written in place, so the view keeps what it
		 *                 showed when it was taken
		 * @return true to continue the walk, false to stop it
		 */
		boolean visit(@Nonnull String term, @Nonnull Bitmap postings, @Nonnull ImpactView impacts);

	}

	/**
	 * The state of one field. What it was registered with - its key, analyzer name and identities, pivot and text
	 * expression - never changes, and it is retired at most once; its length table is replaced by its committed copy
	 * at every commit that merges the index.
	 *
	 * @param key            the field's identity
	 * @param analyzerName   the analyzer name recorded at registration
	 * @param indexFingerprint the persistent index-chain identity
	 * @param wordPhaseId    the persistent index words-phase identity
	 * @param lengthPivot    the field's length pivot
	 * @param textExpression the EvitaEL expression the field's text was selected with from the stored values (the
	 *                       text expression of searchable associated data), `null` when the values are the text
	 *                       themselves; see {@link #getTextExpression(int)}
	 * @param retired        whether the field was retired - its key no longer resolves to it, and it takes no writes
	 * @param lengths        the lengths of the field's indexed values
	 */
	public record Field(
		@Nonnull FulltextFieldKey key,
		@Nonnull String analyzerName,
		long indexFingerprint,
		long wordPhaseId,
		double lengthPivot,
		@Nullable String textExpression,
		boolean retired,
		@Nonnull FieldLengthTable lengths
	) {

		/**
		 * Keeps an owned analyzer name so field heap accounting does not depend on cache or string interning.
		 *
		 * @param key the field identity
		 * @param analyzerName the recorded schema analyzer name
		 * @param indexFingerprint the index-chain identity
		 * @param wordPhaseId the index words-phase identity
		 * @param lengthPivot the impact normalization pivot
		 * @param textExpression the extraction expression, or null for text values
		 * @param retired whether this generation has stopped accepting writes
		 * @param lengths the field's value lengths
		 */
		public Field {
			analyzerName = new String(analyzerName.toCharArray());
		}

		/**
		 * Returns this field retired, with the very same length table.
		 *
		 * @return the retired copy
		 */
		@Nonnull
		Field retire() {
			return new Field(this.key, this.analyzerName, this.indexFingerprint, this.wordPhaseId,
				this.lengthPivot, this.textExpression, true, this.lengths);
		}

	}

	/**
	 * Creates an empty locale index. Every write supplies the field's current analysis and pivot.
	 */
	public FulltextIndex() {
		this.fieldIds = CollectionUtils.createHashMap(8);
		this.fields = NO_FIELDS;
		this.dictionary = createDictionary();
		this.surfaceForms = createSurfaceForms();
		this.wordPhaseIds = new TransactionalMap<>(CollectionUtils.createHashMap(8));
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	/**
	 * Adopts already-merged parts - the constructor the commit merge builds the next version with.
	 *
	 * @param fieldIds           key to id of every field not retired
	 * @param fields             per-field state by id
	 * @param dictionary         the term dictionary
	 * @param surfaceForms the surface dictionary with reference counts
	 * @param wordPhaseIds the persistent short word-phase identities
	 * @param pageStreamRegistry the page bookkeeping, carried by reference from the version being merged
	 */
	private FulltextIndex(
		@Nonnull Map<FulltextFieldKey, Integer> fieldIds,
		@Nonnull Field[] fields,
		@Nonnull TransactionalBucketBPlusTree<String> dictionary,
		@Nonnull TransactionalBucketBPlusTree<String> surfaceForms,
		@Nonnull Map<Long, WordPhaseId> wordPhaseIds,
		@Nonnull PageStreamRegistry pageStreamRegistry
	) {
		this.fieldIds = fieldIds;
		this.fields = fields;
		this.dictionary = dictionary;
		this.surfaceForms = surfaceForms;
		final Map<Long, WordPhaseId> ownedIds = CollectionUtils.createHashMap(Math.max(8, wordPhaseIds.size()));
		wordPhaseIds.forEach(ownedIds::put);
		this.wordPhaseIds = new TransactionalMap<>(ownedIds);
		// the merge runs after the flush collected this version's changes, so the committed copy starts clean
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = pageStreamRegistry;
	}

	/**
	 * Restores an index from its persisted parts: the field registry with every field's lengths, the term
	 * dictionary's leaf pages in key order, and the surface dictionary's leaf pages in key order with the word-phase
	 * table its keys are prefixed by. Each page of either dictionary becomes one leaf, so the restored dictionaries
	 * have the leaf boundaries they were written with, and the page bookkeeping is restored with them - every leaf
	 * keeps its page sequence and none is dirty, so the first flush after the load writes nothing for an unchanged
	 * dictionary.
	 *
	 * An empty dictionary of either kind is persisted as the single empty page of its root leaf, and restored as such.
	 *
	 * @param fields                the registered fields in id order, retired ones included, each with its length
	 *                              table
	 * @param orderedPageSequences  the term dictionary's page sequences in key order, as the last flush listed them
	 * @param pages                 the pages, positionally aligned with `orderedPageSequences`
	 * @param highWaterPageSequence the highest page sequence the term dictionary ever allocated
	 * @param wordPhaseIds          the word-phase fingerprint to short surface-key prefix table
	 * @param surfacePageSequences  the surface dictionary's page sequences in key order, as the last flush listed them
	 * @param surfacePages          the surface pages, positionally aligned with `surfacePageSequences`
	 * @param surfaceHighWater      the highest page sequence the surface dictionary ever allocated
	 * @return the restored index, clean
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the pages of either dictionary do not match their
	 *                                                        list, overlap, or an empty page is not its dictionary's
	 *                                                        only one; when a field has a non-positive or non-finite
	 *                                                        pivot; when two fields of one key are persisted as not
	 *                                                        retired; when two word phases share an id or an id cannot
	 *                                                        be encoded; when a surface key names no persisted word
	 *                                                        phase or is malformed or not canonically encoded; or when
	 *                                                        a field not retired names no persisted word phase
	 */
	@Nonnull
	public static FulltextIndex fromPersistedPages(
		@Nonnull List<Field> fields,
		@Nonnull int[] orderedPageSequences,
		@Nonnull DictionaryPage[] pages,
		int highWaterPageSequence,
		@Nonnull Map<Long, Integer> wordPhaseIds,
		@Nonnull int[] surfacePageSequences,
		@Nonnull SurfaceFormPage[] surfacePages,
		int surfaceHighWater
	) {
		Assert.isPremiseValid(
			orderedPageSequences.length == pages.length,
			() -> "The dictionary lists " + orderedPageSequences.length + " pages but " + pages.length +
				" were passed!"
		);
		Assert.isPremiseValid(orderedPageSequences.length > 0, "A persisted dictionary lists at least one page!");
		final TransactionalBucketBPlusTree<String> dictionary;
		if (pages.length == 1 && pages[0].buckets().length == 0) {
			// the empty root leaf keeps its page, so it is not written again until it holds something
			dictionary = createDictionary();
			dictionary.leafPageHandles().get(0).setPageSequence(orderedPageSequences[0]);
		} else {
			final List<TransactionalBucketBPlusTree<String>> pageTrees = new ArrayList<>(pages.length);
			for (int i = 0; i < pages.length; i++) {
				pageTrees.add(loadPage(pages[i], orderedPageSequences[i]));
			}
			dictionary = createDictionary().assembleFromSingleLeafTrees(
				pageTrees, orderedPageSequences, "fulltext dictionary"
			);
		}
		final PageStreamRegistry pageStreamRegistry = PageStreamRegistry.restoredFrom(
			DICTIONARY_PAGE_STREAM, highWaterPageSequence, dictionary.leafPageHandles()
		);
		final Map<Long, WordPhaseId> restoredIds = restoreWordPhaseIds(wordPhaseIds);
		for (final SurfaceFormPage page : surfacePages) {
			for (final String key : page.keys()) {
				final int phaseId = FulltextTermKeys.fieldIdOf(key);
				Assert.isPremiseValid(restoredIds.containsValue(new WordPhaseId(phaseId)),
					"A surface key must name a persisted word phase!");
				final int foldedEnd = surfaceFoldedEnd(key);
				Assert.isPremiseValid(key.length() > FulltextTermKeys.FIELD_PREFIX_WIDTH
					&& key.charAt(FulltextTermKeys.FIELD_PREFIX_WIDTH) == '\0'
					&& foldedEnd >= 0 && surfaceStemEnd(key, foldedEnd) >= 0,
					"A surface key must delimit its phase, folded form and stem!");
				validateSurfaceKey(key, foldedEnd);
			}
		}
		final Map<FulltextFieldKey, Integer> fieldIds = CollectionUtils.createHashMap(fields.size());
		for (int fieldId = 0; fieldId < fields.size(); fieldId++) {
			final Field field = fields.get(fieldId);
			assertPivotValid(field.lengthPivot());
			if (!field.retired()) {
				Assert.isPremiseValid(restoredIds.containsKey(field.wordPhaseId()),
					"A current field must name a persisted word phase!");
				final Integer previous = fieldIds.put(field.key(), fieldId);
				Assert.isPremiseValid(
					previous == null, () -> "Fulltext field " + field.key() + " is persisted twice as not retired!"
				);
			}
		}
		return new FulltextIndex(
			fieldIds, fields.toArray(NO_FIELDS), dictionary,
			restoreSurfaceForms(surfacePageSequences, surfacePages, surfaceHighWater, pageStreamRegistry),
			restoredIds, pageStreamRegistry
		);
	}

	/**
	 * Builds the single-leaf tree of one persisted dictionary page.
	 *
	 * @param page         the page
	 * @param pageSequence the sequence the page list gives it
	 * @return the tree, holding exactly the page's buckets with their impacts
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<String> loadPage(@Nonnull DictionaryPage page, int pageSequence) {
		Assert.isPremiseValid(
			page.pageSequence() == pageSequence,
			() -> "Dictionary page " + page.pageSequence() + " is listed as page " + pageSequence + "!"
		);
		final ValueToRecord[] buckets = page.buckets();
		Assert.isPremiseValid(
			buckets.length > 0,
			() -> "Dictionary page " + pageSequence + " is empty, but only the sole page of an empty dictionary may be!"
		);
		final Object[] keys = new Object[buckets.length];
		final long[] payloads = new long[buckets.length];
		Object[] overflow = null;
		for (int i = 0; i < buckets.length; i++) {
			keys[i] = buckets[i].getValue();
			final Bitmap recordIds = buckets[i].getRecordIds();
			if (recordIds.size() == 1) {
				payloads[i] = recordIds.getFirst();
			} else {
				if (overflow == null) {
					overflow = new Object[buckets.length];
				}
				// the record tier is chosen here, so a small bucket never builds a bitmap only to be demoted again
				overflow[i] = OverflowRecords.loadedRecordSet(recordIds);
			}
		}
		final TransactionalBucketBPlusTree<String> pageTree = createDictionary();
		pageTree.bulkLoadPage(keys, payloads, overflow, null, page.impacts(), buckets.length);
		return pageTree;
	}

	/**
	 * Creates an empty dictionary carrying impacts - the one shape every dictionary, and every page loaded into one,
	 * has.
	 *
	 * @return the empty dictionary
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static TransactionalBucketBPlusTree<String> createDictionary() {
		// natural `String` order - UTF-16 code-unit order, not code-point order: the field prefix relies on it, see
		// FulltextTermKeys
		final TransactionalBucketBPlusTree<String> dictionary = new TransactionalBucketBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			String.class,
			null,
			(ValueColumnFactory<String>) ValueColumnFactory.forKey(String.class, null)
		);
		dictionary.enableImpacts();
		return dictionary;
	}

	/**
	 * Computes the impact byte of a posting: BM25's saturation of the term frequency, with the field length
	 * normalized against the pivot, scaled to `1..`{@link #MAX_IMPACT}. Never `0`, so a stored impact can always be
	 * told from an absent one.
	 *
	 * @param termFrequency how many times the term occurs in the value, at least one; it may exceed `length`, since
	 *                      stacked variants of one position each count
	 * @param length        length of the value in tokens, at least one
	 * @param lengthPivot   the field's length pivot
	 * @return the impact as an unsigned value
	 */
	public static int computeImpact(int termFrequency, int length, double lengthPivot) {
		final double normalizedLength = length / lengthPivot;
		final double saturated = termFrequency /
			(termFrequency + BM25_K1 * (1.0 - BM25_B + BM25_B * normalizedLength));
		final int scaled = (int) Math.round(saturated * MAX_IMPACT);
		return Math.max(1, Math.min(scaled, MAX_IMPACT));
	}

	/**
	 * Registers a field with its built-with identity. An index-chain or pivot change retires the previous field;
	 * an identical request preserves its id. A current field keeps the text expression it was registered with,
	 * whatever is passed; only a newly registered field records the passed one.
	 *
	 * @param fieldKey the searchable field
	 * @param analyzerName the schema analyzer name recorded for this generation
	 * @param indexFingerprint the identity of the chain building its postings
	 * @param wordPhaseId the identity of the index words phase
	 * @param lengthPivot the positive finite pivot used to compute impacts
	 * @param textExpression the recorded extraction expression, or null for text values
	 * @return the current generation's field id
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the pivot is not positive and finite, or the index
	 *                                                        has no field id left to assign
	 */
	public int getOrAssignFieldId(
		@Nonnull FulltextFieldKey fieldKey, @Nonnull String analyzerName, long indexFingerprint, long wordPhaseId,
		double lengthPivot, @Nullable String textExpression
	) {
		assertPivotValid(lengthPivot);
		final int existing = getFieldId(fieldKey);
		if (existing != UNKNOWN_FIELD_ID) {
			if (isFieldCurrent(existing, indexFingerprint, lengthPivot)) {
				return existing;
			}
			retireField(fieldKey);
		}
		final FulltextIndexChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		// a new field changes what the index persists even before it holds a value
		this.dirty.setToTrue();
		final int fieldId = getFieldCount();
		Assert.isPremiseValid(
			fieldId <= MAX_FIELD_ID,
			() -> "The fulltext index cannot address more than " + (MAX_FIELD_ID + 1) + " fields!"
		);
		assignWordPhaseId(wordPhaseId);
		final Field field = new Field(fieldKey, analyzerName, indexFingerprint, wordPhaseId,
			lengthPivot, textExpression, false, new FieldLengthTable());
		if (layer != null) {
			layer.addField(field);
		} else {
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				// an absolute restore: no field from this id on, whatever was registered after it
				savepoint.push(() -> unregisterFieldsFrom(fieldId));
			}
			this.fieldIds.put(fieldKey, fieldId);
			final Field[] registered = Arrays.copyOf(this.fields, fieldId + 1);
			registered[fieldId] = field;
			this.fields = registered;
		}
		return fieldId;
	}

	/**
	 * Returns the id of a field without assigning one.
	 *
	 * @param fieldKey identity of the searchable field
	 * @return the field's id, or {@link #UNKNOWN_FIELD_ID} when the index has never seen the field or has retired it
	 */
	public int getFieldId(@Nonnull FulltextFieldKey fieldKey) {
		final FulltextIndexChanges layer = Transaction.getTransactionalMemoryLayerIfExists(this);
		final Integer existing = this.fieldIds.get(fieldKey);
		if (existing != null && (layer == null || !layer.isRetired(existing))) {
			return existing;
		}
		if (layer != null) {
			// at most one of the fields the transaction registered under the key is not retired
			final List<Field> added = layer.getAddedFields();
			for (int i = 0; i < added.size(); i++) {
				final int fieldId = this.fields.length + i;
				if (added.get(i).key().equals(fieldKey) && !layer.isRetired(fieldId)) {
					return fieldId;
				}
			}
		}
		return UNKNOWN_FIELD_ID;
	}

	/**
	 * Retires the field of a key: the key stops resolving to it, its postings and lengths stay as they are, and the
	 * next write of the key registers a fresh, empty field ({@link #getOrAssignFieldId}) - see the class
	 * documentation for why the old postings are kept.
	 *
	 * @param fieldKey identity of the searchable field
	 * @return true when a field was retired, false when the key resolved to none
	 */
	public boolean retireField(@Nonnull FulltextFieldKey fieldKey) {
		final int fieldId = getFieldId(fieldKey);
		if (fieldId == UNKNOWN_FIELD_ID) {
			return false;
		}
		final FulltextIndexChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		// the field registry is persisted, and it changed
		this.dirty.setToTrue();
		if (layer != null) {
			layer.retireField(fieldId);
		} else {
			final Field field = this.fields[fieldId];
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				savepoint.push(() -> unretireField(fieldId, field));
			}
			this.fieldIds.remove(fieldKey);
			replaceField(fieldId, field.retire());
		}
		releaseWordPhaseIdIfUnused(fieldAt(fieldId).wordPhaseId());
		return true;
	}

	/**
	 * Returns the identity of a field.
	 *
	 * @param fieldId id of the field
	 * @return the field's key, or null when no field carries the id
	 */
	@Nullable
	public FulltextFieldKey getFieldKey(int fieldId) {
		return fieldId >= 0 && fieldId < getFieldCount() ? fieldAt(fieldId).key() : null;
	}

	/**
	 * Returns whether a field was retired.
	 *
	 * @param fieldId id of the field
	 * @return true when the field was retired
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the index never assigned the id
	 */
	public boolean isFieldRetired(int fieldId) {
		assertFieldKnown(fieldId);
		if (fieldAt(fieldId).retired()) {
			return true;
		}
		final FulltextIndexChanges layer = Transaction.getTransactionalMemoryLayerIfExists(this);
		return layer != null && layer.isRetired(fieldId);
	}

	/**
	 * Returns the length pivot of a field.
	 *
	 * @param fieldId id of the field
	 * @return the pivot its impacts are computed against
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the index never assigned the id
	 */
	public double getLengthPivot(int fieldId) {
		assertFieldKnown(fieldId);
		return fieldAt(fieldId).lengthPivot();
	}

	/**
	 * Returns the EvitaEL text expression a field was registered with - the way its text is selected from the stored
	 * values of searchable associated data. A field keeps extracting with this expression even after the schema
	 * declares another one: removing a value re-extracts its text to find the postings to drop, so extracting
	 * differently than the add did would leave stale postings behind. The field keeps its expression until it is
	 * rebuilt (#409).
	 *
	 * @param fieldId id of the field
	 * @return the expression text, `null` when the field's values are the text themselves
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the index never assigned the id
	 */
	@Nullable
	public String getTextExpression(int fieldId) {
		assertFieldKnown(fieldId);
		return fieldAt(fieldId).textExpression();
	}

	/**
	 * Returns the number of fields the index has assigned an id to.
	 *
	 * @return the field count
	 */
	public int getFieldCount() {
		final FulltextIndexChanges layer = Transaction.getTransactionalMemoryLayerIfExists(this);
		return layer == null ? this.fields.length : this.fields.length + layer.getAddedFields().size();
	}

	/**
	 * Returns the analyzer name recorded for a field generation.
	 *
	 * @param fieldId the registered field id
	 * @return its analyzer name
	 */
	@Nonnull
	public String getAnalyzerName(int fieldId) {
		assertFieldKnown(fieldId);
		return fieldAt(fieldId).analyzerName();
	}

	/**
	 * Returns the index-chain fingerprint recorded for a field generation.
	 *
	 * @param fieldId the registered field id
	 * @return its persistent index fingerprint
	 */
	public long getIndexFingerprint(int fieldId) {
		assertFieldKnown(fieldId);
		return fieldAt(fieldId).indexFingerprint();
	}

	/**
	 * Returns the words-phase identity recorded for a field generation.
	 *
	 * @param fieldId the registered field id
	 * @return its persistent words-phase identity
	 */
	public long getWordPhaseId(int fieldId) {
		assertFieldKnown(fieldId);
		return fieldAt(fieldId).wordPhaseId();
	}

	/**
	 * Checks whether a field's stored postings can be used with the current schema analysis and pivot.
	 *
	 * @param fieldId the registered field id
	 * @param fingerprint the current index-chain fingerprint
	 * @param lengthPivot the current schema pivot
	 * @return true when the generation is active and both identities match
	 */
	public boolean isFieldCurrent(int fieldId, long fingerprint, double lengthPivot) {
		assertFieldKnown(fieldId);
		final Field field = fieldAt(fieldId);
		return !isFieldRetired(fieldId) && field.indexFingerprint() == fingerprint &&
			Double.compare(field.lengthPivot(), lengthPivot) == 0;
	}

	/**
	 * Checks whether an entity has a value indexed in a field, as the caller's transaction sees it. A value that
	 * analyzed to no token was indexed as nothing and does not count.
	 *
	 * @param fieldId    the registered field id
	 * @param primaryKey primary key of the entity
	 * @return true when the field holds a length for the entity
	 */
	public boolean hasIndexedValue(int fieldId, int primaryKey) {
		assertFieldKnown(fieldId);
		return fieldAt(fieldId).lengths().getEncoded(primaryKey) != 0;
	}

	/**
	 * Returns the sequences of the dictionary pages on disk once the flush in progress is durable - the pages a dropped
	 * index must reclaim. See {@link PageStreamRegistry#pendingLivePageSequences(int)}.
	 *
	 * @return the page sequences, empty for an index never flushed
	 */
	@Nonnull
	public int[] getPersistedDictionaryPages() {
		return this.pageStreamRegistry.pendingLivePageSequences(DICTIONARY_PAGE_STREAM);
	}

	/**
	 * Returns the keys of the length-table blocks of a field the last flush left on disk - the blocks a dropped index
	 * must reclaim. See {@link FieldLengthTable#getPersistedBlockKeys()}.
	 *
	 * @param fieldId id of a field this index assigned
	 * @return keys of the blocks on disk, ascending; the caller must not modify the array
	 */
	@Nonnull
	public int[] getPersistedLengthBlocks(int fieldId) {
		assertFieldKnown(fieldId);
		return fieldAt(fieldId).lengths().getPersistedBlockKeys();
	}

	/**
	 * Adds all texts of a field value using its current schema analysis, retiring a stale generation first.
	 * An empty value set does nothing, including no registration or reconciliation.
	 *
	 * @param fieldKey the searchable field
	 * @param primaryKey the entity primary key
	 * @param analyzer the compiled current schema analyzer
	 * @param lengthPivot the schema pivot used for impacts
	 * @param textExpression the extraction expression, or null for text values
	 * @param values all texts of this entity's field
	 */
	public void addValue(
		@Nonnull FulltextFieldKey fieldKey, int primaryKey, @Nonnull CompiledFulltextAnalyzer analyzer,
		double lengthPivot, @Nullable String textExpression, @Nonnull String... values
	) {
		if (values.length == 0) {
			return;
		}
		final int fieldId = getOrAssignFieldId(fieldKey, analyzer.index().getAnalyzerName(),
			analyzer.fingerprint().index(), analyzer.fingerprint().wordPhase(), lengthPivot, textExpression);
		indexValues(fieldId, primaryKey, analyzer, values);
	}

	/**
	 * Removes an old value only when its field still has the current index identity. A stale generation is retired
	 * with its postings untouched; removing an entity absent from the fresh generation is a no-op.
	 * An empty value set does nothing, including no reconciliation.
	 *
	 * @param fieldKey the searchable field
	 * @param primaryKey the entity primary key
	 * @param analyzer the compiled current schema analyzer
	 * @param lengthPivot the current schema pivot
	 * @param values the texts previously stored for this entity
	 */
	public void removeValue(
		@Nonnull FulltextFieldKey fieldKey, int primaryKey, @Nonnull CompiledFulltextAnalyzer analyzer,
		double lengthPivot, @Nonnull String... values
	) {
		if (values.length == 0) {
			return;
		}
		final int fieldId = getFieldId(fieldKey);
		if (fieldId == UNKNOWN_FIELD_ID) {
			return;
		}
		if (!isFieldCurrent(fieldId, analyzer.fingerprint().index(), lengthPivot)) {
			retireField(fieldKey);
			return;
		}
		unindexValues(fieldId, primaryKey, analyzer, values);
	}

	/**
	 * Analyzes and indexes the texts of a current field generation.
	 *
	 * @param fieldId the registered current field generation
	 * @param analyzer the compiled index chain that built its postings
	 * @param primaryKey primary key of the entity
	 * @param values     the elements of the value
	 */
	private void indexValues(
		int fieldId, int primaryKey, @Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull String... values
	) {
		final Field field = fieldAt(fieldId);
		Assert.isPremiseValid(
			field.lengths().getEncoded(primaryKey) == 0,
			() -> "Entity " + primaryKey + " already has a value of fulltext field " + field.key() + " indexed; " +
				"remove it before indexing another."
		);
		final AnalyzedValue analyzed = analyze(analyzer, values);
		if (analyzed.length() == 0) {
			return;
		}
		markWritten();
		final double pivot = field.lengthPivot();
		for (final Map.Entry<String, int[]> entry : analyzed.termFrequencies().entrySet()) {
			final int impact = computeImpact(entry.getValue()[0], analyzed.length(), pivot);
			this.dictionary.addRecord(FulltextTermKeys.encode(fieldId, entry.getKey()), primaryKey, (byte) impact);
		}
		updateSurfaceForms(field.wordPhaseId(), analyzed.surfacePairs(), 1);
		field.lengths().put(primaryKey, analyzed.length());
	}

	/**
	 * Removes the old texts from a current field generation, leaving an absent entity untouched.
	 *
	 * @param fieldId the registered current field generation
	 * @param analyzer the compiled index chain that built its postings
	 * @param primaryKey primary key of the entity
	 * @param values     the elements of the value that was indexed
	 */
	private void unindexValues(
		int fieldId, int primaryKey, @Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull String... values
	) {
		if (fieldAt(fieldId).lengths().getEncoded(primaryKey) == 0) {
			return;
		}
		final AnalyzedValue analyzed = analyze(analyzer, values);
		if (analyzed.length() == 0) {
			// the mirror of addValue: a value without tokens was indexed as nothing, so there is nothing to remove -
			// and touching the length table here would erase the length of whatever the entity really has indexed
			return;
		}
		markWritten();
		for (final String term : analyzed.termFrequencies().keySet()) {
			this.dictionary.removeRecord(FulltextTermKeys.encode(fieldId, term), primaryKey);
		}
		updateSurfaceForms(fieldAt(fieldId).wordPhaseId(), analyzed.surfacePairs(), -1);
		fieldAt(fieldId).lengths().remove(primaryKey);
	}

	/**
	 * Walks, in ascending term order, every term of a field that starts with the passed prefix, until the visitor
	 * asks to stop. An empty prefix walks every term of the field. Keys of other fields are never reached: the walk
	 * starts at the encoded lower bound and ends at the first key without the encoded prefix.
	 *
	 * The walk runs a cursor over the dictionary's live leaves, so the visitor must not write to this index while
	 * it runs.
	 *
	 * @param fieldId    id of the field
	 * @param termPrefix prefix the visited terms share; empty for all terms of the field
	 * @param visitor    receives each term, its posting list and its impacts
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the index never assigned the field id
	 */
	public void forEachTerm(int fieldId, @Nonnull String termPrefix, @Nonnull TermVisitor visitor) {
		assertFieldKnown(fieldId);
		final String keyPrefix = FulltextTermKeys.encode(fieldId, termPrefix);
		final BucketCursor<String> cursor = this.dictionary.cursor(keyPrefix);
		while (cursor.next()) {
			final String key = cursor.value();
			if (!FulltextTermKeys.belongsTo(key, keyPrefix)) {
				// keys are ordered by (field, term), so the first key without the prefix ends the run
				return;
			}
			// read from the leaf the cursor stands on - no second descent, and no copy of committed impacts
			if (!visitor.visit(FulltextTermKeys.termOf(key), cursor.records(), cursor.impacts())) {
				return;
			}
		}
	}

	/**
	 * Visits the accepted terms reached by a reusable typo walker in one field, in ascending dictionary order.
	 * Every well-formed accepted term is visited. For terms containing an unpaired surrogate, the rules in
	 * {@link FulltextDictionaryWalker} apply. The walker was built for the bare term and may be reused for other
	 * fields; it never includes the field prefix in edit distance. The visitor must not write to this index during
	 * the walk.
	 *
	 * @param fieldId id of the field to search
	 * @param walker finite or open-ended walker built once for the query term
	 * @param visitor receives accepted terms, posting and impact views, distances and bucket cardinalities
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the index never assigned the field id
	 */
	public void forEachTerm(int fieldId, @Nonnull FulltextDictionaryWalker walker, @Nonnull MatchVisitor visitor) {
		assertFieldKnown(fieldId);
		walker.walk(this.dictionary, fieldId, visitor);
	}

	/**
	 * Returns the heap this index occupies, in bytes: the index object and its dirty flag, the field registry - the
	 * name-to-id map, the field array and every field's length table - the term dictionary with its postings and
	 * impacts, and the surface dictionary with its counts and word-phase table.
	 *
	 * Not charged: the page bookkeeping, which no paged index charges - it is flush state carried by reference through
	 * every committed copy. Each field key is charged with its {@link Field}, the one holder a retired field's key
	 * still has; {@link #fieldIds} maps the very same instance. A retired field and its successor registered with one
	 * key instance are charged it twice. A running transaction's layer belongs to the transaction.
	 *
	 * Walking the dictionary costs `O(terms / block size)`, so this is an index-detail figure, never one a query path
	 * may ask for.
	 *
	 * @return the heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// The id and seven reference slots: fieldIds, fields, dictionary, surfaceForms, wordPhaseIds, dirty,
		// pageStreamRegistry.
		long size = layout.sizeOfObject(Long.BYTES + 7L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			// the key is charged with its field, the boxed field id to this map, its only holder
			+ MapHeapSize.sizeOf(this.fieldIds, fieldKey -> 0L, fieldId -> layout.sizeOfObject(Integer.BYTES))
			+ this.dictionary.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.surfaceForms.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.wordPhaseIds.getHeapSizeInBytes(
				fingerprint -> layout.sizeOfObject(Long.BYTES),
				phaseId -> layout.sizeOfObject(Integer.BYTES));
		if (this.fields.length > 0) {
			// the shared empty registry belongs to no index
			size += layout.sizeOfArray(this.fields.length, layout.referenceSize());
			for (final Field field : this.fields) {
				// The pivot, two identities and retired flag, then key / analyzerName / expression / lengths.
				final String textExpression = field.textExpression();
				size += layout.sizeOfObject(Double.BYTES + 2L * Long.BYTES + 1L + 4L * layout.referenceSize())
					+ getHeapSizeInBytes(field.key(), layout)
					+ MemoryMeasuringConstants.computeStringSize(field.analyzerName())
					+ (textExpression == null ? 0L : MemoryMeasuringConstants.computeStringSize(textExpression))
					+ field.lengths().getHeapSizeInBytes();
			}
		}
		return size;
	}

	/**
	 * Returns the heap a field key occupies: the record with its kind / referenceName / name slots and its two names.
	 * The kind is an enum constant, owned by the JVM.
	 *
	 * @param fieldKey the key
	 * @param layout   the layout of the running VM
	 * @return the heap footprint in bytes
	 */
	private static long getHeapSizeInBytes(@Nonnull FulltextFieldKey fieldKey, @Nonnull VMLayout layout) {
		final String referenceName = fieldKey.referenceName();
		return layout.sizeOfObject(3L * layout.referenceSize())
			+ MemoryMeasuringConstants.computeStringSize(fieldKey.name())
			+ (referenceName == null ? 0L : MemoryMeasuringConstants.computeStringSize(referenceName));
	}

	@Nonnull
	@Override
	public FulltextIndexChanges createLayer() {
		return new FulltextIndexChanges();
	}

	/**
	 * The delegate branch journals the two things it writes itself, a field registration and a field retirement; every
	 * part journals its own writes.
	 *
	 * @return always true
	 */
	@Override
	public boolean supportsWarmUpRollback() {
		return true;
	}

	@Nonnull
	@Override
	public FulltextIndex createCopyWithMergedTransactionalMemory(
		@Nullable FulltextIndexChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (layer == null) {
			// every write creates the layer, so no part of this index was written - carried forward as is
			return this;
		}
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		// the flush of this transaction has already written the pages it staged, so they become the baseline the next
		// flush diffs against; a staged set that never reaches a merge (warm-up has none) is published by the next
		// flush instead - see publishPreviousFlush
		this.pageStreamRegistry.publishStaged();
		final List<Field> added = layer.getAddedFields();
		final int committedCount = this.fields.length;
		final int fieldCount = committedCount + added.size();
		final Map<FulltextFieldKey, Integer> mergedFieldIds = CollectionUtils.createHashMap(fieldCount);
		final Field[] mergedFields = fieldCount == 0 ? NO_FIELDS : new Field[fieldCount];
		for (int fieldId = 0; fieldId < fieldCount; fieldId++) {
			final Field field = fieldId < committedCount
				? this.fields[fieldId]
				: added.get(fieldId - committedCount);
			final boolean retired = field.retired() || layer.isRetired(fieldId);
			if (!retired) {
				mergedFieldIds.put(field.key(), fieldId);
			}
			mergedFields[fieldId] = new Field(
				field.key(), field.analyzerName(), field.indexFingerprint(), field.wordPhaseId(),
				field.lengthPivot(),
				field.textExpression(),
				retired,
				transactionalLayer.getStateCopyWithCommittedChanges(field.lengths())
			);
		}
		return new FulltextIndex(
			mergedFieldIds,
			mergedFields,
			transactionalLayer.getStateCopyWithCommittedChanges(this.dictionary),
			transactionalLayer.getStateCopyWithCommittedChanges(this.surfaceForms),
			transactionalLayer.getStateCopyWithCommittedChanges(this.wordPhaseIds),
			this.pageStreamRegistry
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final FulltextIndexChanges layer = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		for (final Field field : this.fields) {
			field.lengths().removeLayer(transactionalLayer);
		}
		if (layer != null) {
			for (final Field field : layer.getAddedFields()) {
				field.lengths().removeLayer(transactionalLayer);
			}
		}
		this.dictionary.removeLayer(transactionalLayer);
		this.surfaceForms.removeLayer(transactionalLayer);
		this.wordPhaseIds.removeLayer(transactionalLayer);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.dirty);
	}

	/**
	 * Returns whether anything was written to the index since its last flush collected it. A clean index must not be
	 * collected.
	 *
	 * @return true when the index has changes to persist
	 */
	public boolean isDirty() {
		return this.dirty.isTrue();
	}

	/**
	 * Clears the dirty flag, once the flush has collected the changes.
	 *
	 * Inside a transaction, call it only on an index the same transaction wrote: the write created the index's
	 * {@link FulltextIndexChanges} layer, and without it the commit merge carries the index forward unmerged and never
	 * sweeps the flag's layer this call creates.
	 */
	public void resetDirty() {
		this.dirty.setToFalse();
	}

	/**
	 * Walks the dictionary leaf by leaf and returns what this flush must write: the leaf pages that changed since the
	 * last flush, the ordered list of every live page, the high-water of the page sequences, and the pages that left
	 * the dictionary and must be removed.
	 *
	 * A leaf without a page - a fresh one or either new half of a split - is assigned a newly
	 * allocated page; a leaf is collected when it is new or its transaction-aware dirty flag is set, and the flag is
	 * cleared on the way. The next set of live pages is staged, and becomes the baseline at the next publication: the
	 * commit merge, or the next collect of either dictionary - which within one flush is
	 * {@link #collectChangedSurfaceFormPages()}.
	 *
	 * Before anything is staged, every set still staged is published: see {@link #publishPreviousFlush()} for why that
	 * is necessary. The dictionary is always written in pages, even when it fits one leaf: an index
	 * holding a whole corpus's terms is never small, so an inline shape would only add a collapse path to maintain.
	 *
	 * The caller gates on {@link #isDirty()}; a clean index must not be collected. Inside a transaction, the index must
	 * also have been written by the same transaction, for the reason {@link #resetDirty()} gives: the leaf flags this
	 * walk clears live in layers only that transaction's merge of the index sweeps.
	 *
	 * @return the changed pages, the ordered live page sequences, the high-water and the freed page sequences
	 */
	@Nonnull
	public PageEmission<DictionaryPage> collectChangedPages() {
		publishPreviousFlush();
		final List<LeafPageHandle<String>> handles = this.dictionary.leafPageHandles();
		return this.pageStreamRegistry.collectChangedPages(
			DICTIONARY_PAGE_STREAM, handles,
			(pageSequence, handle) -> {
				final BucketCursor<String> cursor = handle.cursor();
				final List<ValueToRecord> buckets = new ArrayList<>(VALUE_BLOCK_SIZE);
				final List<byte[]> impacts = new ArrayList<>(VALUE_BLOCK_SIZE);
				while (cursor.next()) {
					final String key = cursor.value();
					// a multi-record bucket is written as a bitmap whichever tier holds it in memory, as the inverted
					// index writes its own; the bitmap tier is wrapped, the array tier copied once per dirty leaf
					if (cursor.isSingle()) {
						buckets.add(new ValueToRecordPrimitive(key, cursor.singleRecordId()));
					} else {
						final Bitmap records = cursor.records();
						buckets.add(
							records instanceof final TransactionalBitmap live
								? new ValueToRecordBitmap(key, live)
								: new ValueToRecordBitmap(key, records)
						);
					}
					impacts.add(cursor.impacts().toArray());
				}
				return new DictionaryPage(
					pageSequence, buckets.toArray(ValueToRecord[]::new), impacts.toArray(byte[][]::new)
				);
			}
		);
	}

	/**
	 * Returns what this flush must write of the field length tables, one emission per field in field-id order: the
	 * blocks that changed since the last flush, the blocks that left, and every block the field's table holds. A
	 * table pages by its 65,536-key blocks - see {@link FieldLengthTable#collectChangedBlocks()} - so an entity
	 * written in a large table rewrites one block, not the table.
	 *
	 * The caller gates on {@link #isDirty()}, as for {@link #collectChangedPages()}.
	 *
	 * @return the emission of each field, the i-th belonging to field id `i`
	 */
	@Nonnull
	public LengthBlockEmission[] collectChangedLengthBlocks() {
		final int fieldCount = getFieldCount();
		final LengthBlockEmission[] emissions = new LengthBlockEmission[fieldCount];
		for (int fieldId = 0; fieldId < fieldCount; fieldId++) {
			emissions[fieldId] = fieldAt(fieldId).lengths().collectChangedBlocks();
		}
		return emissions;
	}

	/**
	 * Promotes every set of pages still staged to the baseline this collect diffs against. The registry publishes all
	 * its streams at once, so besides what the previous flush staged this takes the set the other dictionary's collect
	 * staged earlier in the same flush.
	 *
	 * The commit merge publishes the staged set, but a warm-up (bulk) flush never reaches a merge, so without this the
	 * baseline would stay empty for the whole warm-up while the disk moved on, and the freed-page diff of every warm-up
	 * flush would come out empty. A leaf merge is the one structural change that drops a page without allocating one:
	 * the surviving leaf absorbs its sibling in place and keeps its own page. With an empty baseline the dropped page
	 * would be neither removed nor taken off the page list, and the next cold load would assemble the survivor
	 * followed by its stale sibling, whose keys overlap the survivor's.
	 *
	 * Publishing at collect time is safe on every path, for the reason
	 * {@link io.evitadb.index.invertedIndex.InvertedIndex} records at its own copy of this method: a failed flush is
	 * never followed by another flush of the same data, so no later flush can diff against a set that did not land.
	 * The same holds for a set staged earlier in the flush in progress. On the transactional path the merge has
	 * already published what the previous flush staged, so the first collect of a flush finds nothing to publish; the
	 * second one publishes the set the first one staged.
	 */
	private void publishPreviousFlush() {
		this.pageStreamRegistry.publishStaged();
	}

	/**
	 * Returns the state of a field, as the caller's transaction sees the registry.
	 *
	 * @param fieldId id of a field this index assigned
	 * @return the field
	 */
	@Nonnull
	private Field fieldAt(int fieldId) {
		final int committedCount = this.fields.length;
		if (fieldId < committedCount) {
			return this.fields[fieldId];
		}
		final FulltextIndexChanges layer = Transaction.getTransactionalMemoryLayerIfExists(this);
		Assert.isPremiseValid(layer != null, () -> "Fulltext field id " + fieldId + " is not registered!");
		return layer.getAddedFields().get(fieldId - committedCount);
	}

	/**
	 * Marks the index as written: in the current transaction, so the commit merges it, and dirty, so the next flush
	 * collects it. Outside a transaction only the dirty flag is written.
	 */
	private void markWritten() {
		Transaction.getOrCreateTransactionalMemoryLayer(this);
		this.dirty.setToTrue();
	}

	/**
	 * Unregisters every field from the passed id on - the inverse a warm-up savepoint replays for a registration.
	 *
	 * @param fieldId the first id to unregister
	 */
	private void unregisterFieldsFrom(int fieldId) {
		if (this.fields.length > fieldId) {
			for (int i = fieldId; i < this.fields.length; i++) {
				final Field field = this.fields[i];
				// a retired field's key no longer maps to it - when it maps anywhere, it is to another field
				if (!field.retired()) {
					this.fieldIds.remove(field.key());
				}
			}
			this.fields = fieldId == 0 ? NO_FIELDS : Arrays.copyOf(this.fields, fieldId);
		}
	}

	/**
	 * Puts a retired field back as it was before its retirement - the inverse a warm-up savepoint replays for a
	 * retirement. Strict reverse replay has already undone every later registration, so the key maps to no field.
	 *
	 * @param fieldId the id of the retired field
	 * @param field   the field as it was before the retirement
	 */
	private void unretireField(int fieldId, @Nonnull Field field) {
		replaceField(fieldId, field);
		this.fieldIds.put(field.key(), fieldId);
	}

	/**
	 * Replaces the committed state of a field - copy-on-write, as every change of the registry array is, because a
	 * reader may still hold the array it replaces.
	 *
	 * @param fieldId id of a committed field
	 * @param field   its new state
	 */
	private void replaceField(int fieldId, @Nonnull Field field) {
		final Field[] registered = Arrays.copyOf(this.fields, this.fields.length);
		registered[fieldId] = field;
		this.fields = registered;
	}

	/**
	 * Analyzes the elements of a value through the field's compiled index chain into its distinct terms with their
	 * frequencies and its length, summed over the elements - one element for a single value.
	 *
	 * The length counts token **positions**: a term arriving at position increment `0` is a variant of the previous
	 * position, not a further token of the text, and a stop word removed by the chain leaves a gap but is not a token
	 * either. Each element is analyzed on its own, so no token spans two of them.
	 *
	 * The term frequencies count every term the chain emits, stacked variants at increment `0` included, so a term's
	 * frequency may exceed the length. Surface pairs are distinct over the entire value. A passive capture in the
	 * full index chain pairs each normalized pre-stem input with its outputs, including shared positions. Only index
	 * operations contribute; query-only synonyms and normalization never determine persisted keys. Display is the
	 * original NFC offset slice lowercased with `Locale.ROOT`, with NUL dropped and all other characters retained.
	 *
	 * @param analyzer the compiled index chain of the field
	 * @param values the elements to analyze
	 * @return the analysis
	 */
	@Nonnull
	private AnalyzedValue analyze(@Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull String... values) {
		final Map<String, int[]> termFrequencies = CollectionUtils.createHashMap(16);
		final Map<SurfacePair, String> distinctPairs = CollectionUtils.createLinkedHashMap(16);
		final SurfacePair lookup = new SurfacePair("", "");
		final int[] length = new int[1];
		for (final String value : values) {
			Assert.isPremiseValid(value != null, "A fulltext value must not contain a null element!");
			analyzer.indexSurface().analyzeIndexed(value, (term, folded, surface, start, end, increment) -> {
				if (increment > 0) {
					length[0]++;
				}
				termFrequencies.computeIfAbsent(term, t -> new int[1])[0]++;
				if (folded != null) {
					// Borrowed capture buffers are compared without strings; stored keys own immutable copies.
					lookup.reset(folded, term);
					if (!distinctPairs.containsKey(lookup)) {
						// Display alone uses source offsets. Drop NUL and retain every other original character.
						final String accented = surface.get().toLowerCase(Locale.ROOT).replace("\0", "");
						distinctPairs.put(lookup.freeze(), accented);
					}
				}
			});
		}
		final Map<String, String> surfacePairs = CollectionUtils.createLinkedHashMap(distinctPairs.size());
		for (final Map.Entry<SurfacePair, String> entry : distinctPairs.entrySet()) {
			final SurfacePair pair = entry.getKey();
			final String folded = pair.folded.toString();
			final String stem = pair.stem.equals(folded) ? "" : encodeDistinctSurfaceComponent(pair.stem);
			surfacePairs.put(encodeSurfaceComponent(folded) + '\0' + stem + '\0', entry.getValue());
		}
		return new AnalyzedValue(termFrequencies, surfacePairs, length[0]);
	}

	/**
	 * Verifies a length pivot is usable.
	 *
	 * @param lengthPivot the pivot to verify
	 * @throws io.evitadb.exception.GenericEvitaInternalError when it is not positive and finite
	 */
	private static void assertPivotValid(double lengthPivot) {
		Assert.isPremiseValid(
			lengthPivot > 0.0 && Double.isFinite(lengthPivot),
			() -> "A length pivot must be positive and finite, " + lengthPivot + " was passed!"
		);
	}

	/**
	 * Verifies the field id was assigned by this index, so a key is never written under a prefix no field owns.
	 *
	 * @param fieldId the id to verify
	 * @throws io.evitadb.exception.GenericEvitaInternalError when it was not
	 */
	private void assertFieldKnown(int fieldId) {
		Assert.isPremiseValid(
			fieldId >= 0 && fieldId < getFieldCount(),
			() -> "Fulltext field id " + fieldId + " was never assigned by this index!"
		);
	}

	/**
	 * Creates the surface dictionary using the tree's unique primitive long payload column.
	 *
	 * @return an empty tree with no posting sets or impacts
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static TransactionalBucketBPlusTree<String> createSurfaceForms() {
		return TransactionalBucketBPlusTree.withLongPayload(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			String.class, null, (ValueColumnFactory<String>) ValueColumnFactory.forKey(String.class, null));
	}

	/**
	 * Returns the short identities written in the root record. The returned table is detached from live state.
	 *
	 * @return fingerprint to short prefix id, including retired phases still owning lane keys
	 */
	@Nonnull
	public Map<Long, Integer> getWordPhaseIds() {
		final Map<Long, Integer> result = CollectionUtils.createHashMap(this.wordPhaseIds.size());
		this.wordPhaseIds.forEach((fingerprint, phaseId) -> result.put(fingerprint, phaseId.value()));
		return result;
	}

	/**
	 * An owned id cell prevents JVM integer caching from making the table's heap depend on other index counters.
	 *
	 * @param value short fixed-width prefix id
	 */
	private record WordPhaseId(int value) {
	}

	/**
	 * Validates persisted ids and restores owned cells for the transactional table.
	 *
	 * @param ids persisted fingerprint to short prefix table
	 * @return owned cells, with every prefix unique and encodable
	 */
	@Nonnull
	private static Map<Long, WordPhaseId> restoreWordPhaseIds(@Nonnull Map<Long, Integer> ids) {
		final Map<Long, WordPhaseId> result = CollectionUtils.createHashMap(Math.max(8, ids.size()));
		for (final Map.Entry<Long, Integer> entry : ids.entrySet()) {
			final int id = entry.getValue();
			FulltextTermKeys.fieldPrefix(id);
			final WordPhaseId cell = new WordPhaseId(id);
			Assert.isPremiseValid(!result.containsValue(cell), "Persisted word phases must have distinct ids!");
			result.put(entry.getKey(), cell);
		}
		return result;
	}

	/**
	 * Assigns the lowest available fixed-width prefix to a new word phase.
	 *
	 * @param fingerprint the persisted word-phase fingerprint
	 */
	private void assignWordPhaseId(long fingerprint) {
		if (this.wordPhaseIds.get(fingerprint) != null) {
			return;
		}
		int candidate = 0;
		while (this.wordPhaseIds.containsValue(new WordPhaseId(candidate))) {
			candidate++;
		}
		Assert.isPremiseValid(candidate <= FulltextTermKeys.MAX_FIELD_ID, "Surface word-phase ids exhausted!");
		this.wordPhaseIds.put(fingerprint, new WordPhaseId(candidate));
	}

	/**
	 * Drops an identity only when neither a current field nor a lane key can still use it.
	 *
	 * @param fingerprint the word phase that may have become unused
	 */
	private void releaseWordPhaseIdIfUnused(long fingerprint) {
		final WordPhaseId phaseId = this.wordPhaseIds.get(fingerprint);
		if (phaseId == null) {
			return;
		}
		for (int fieldId = 0; fieldId < getFieldCount(); fieldId++) {
			if (!isFieldRetired(fieldId) && fieldAt(fieldId).wordPhaseId() == fingerprint) {
				return;
			}
		}
		final String prefix = surfacePhasePrefix(phaseId);
		final BucketCursor<String> cursor = this.surfaceForms.cursor(prefix);
		if (!cursor.next() || !cursor.value().startsWith(prefix)) {
			this.wordPhaseIds.remove(fingerprint);
		}
	}

	/**
	 * Updates each pair once per entity field value. The first accented spelling is retained until the pair vanishes;
	 * tracking every spelling's frequency would require another persisted dictionary just for suggestion display.
	 *
	 * @param fingerprint the field's word-phase identity
	 * @param pairs distinct folded/stem pairs with candidate display spellings
	 * @param delta one for addition, minus one for removal
	 */
	private void updateSurfaceForms(long fingerprint, @Nonnull Map<String, String> pairs, int delta) {
		final WordPhaseId phaseId = this.wordPhaseIds.get(fingerprint);
		Assert.isPremiseValid(phaseId != null, "A written field must own a word-phase id!");
		final String phasePrefix = surfacePhasePrefix(phaseId);
		for (final Map.Entry<String, String> pair : pairs.entrySet()) {
			final String prefix = phasePrefix + pair.getKey();
			final BucketCursor<String> cursor = this.surfaceForms.cursor(prefix);
			final boolean present = cursor.next() && cursor.value().startsWith(prefix);
			Assert.isPremiseValid(present || delta > 0, "A removed surface pair must exist!");
			final String key;
			final long count;
			if (present) {
				key = cursor.value();
				count = Math.addExact(cursor.longRecordId(), delta);
				this.surfaceForms.removeLongRecord(key);
			} else {
				final String folded = decodeSurfaceComponent(pair.getKey().substring(0, pair.getKey().indexOf('\0')));
				key = prefix + (folded.equals(pair.getValue()) ? "" : encodeDistinctSurfaceComponent(pair.getValue()));
				count = 1;
			}
			if (count > 0) {
				this.surfaceForms.addLongRecord(key, count);
			}
		}
	}

	/**
	 * Escapes framing NUL and the escape character SOH, retaining ordinary tokens verbatim. The escape codes
	 * preserve character order and prefix bounds: NUL becomes SOH/SOH and SOH becomes SOH/STX.
	 *
	 * @param component actual index token or display spelling
	 * @return delimiter-free component
	 */
	@Nonnull
	private static String encodeSurfaceComponent(@Nonnull String component) {
		if (component.indexOf('\0') < 0 && component.indexOf('\1') < 0) {
			return component;
		}
		final StringBuilder encoded = new StringBuilder(component.length() + 2);
		for (int i = 0; i < component.length(); i++) {
			final char character = component.charAt(i);
			if (character <= '\1') {
				encoded.append('\1').append((char) (character + 1));
			} else {
				encoded.append(character);
			}
		}
		return encoded.toString();
	}

	/**
	 * Encodes a component different from folded; SOH/ETX reserves an explicit empty value, while an empty encoded
	 * stem or display means identity with folded. Literal SOH is escaped, so the explicit-empty marker is unambiguous.
	 *
	 * @param component nonidentity token or display
	 * @return nonempty encoded component
	 */
	@Nonnull
	private static String encodeDistinctSurfaceComponent(@Nonnull String component) {
		return component.isEmpty() ? "\1\3" : encodeSurfaceComponent(component);
	}

	/**
	 * Decodes an encoded component and rejects malformed escapes in persisted pages.
	 *
	 * @param component encoded component
	 * @return original token or spelling
	 */
	@Nonnull
	private static String decodeSurfaceComponent(@Nonnull String component) {
		if (component.equals("\1\3")) {
			return "";
		}
		if (component.indexOf('\1') < 0) {
			Assert.isPremiseValid(component.indexOf('\0') < 0, "An encoded component must not contain NUL!");
			return component;
		}
		final StringBuilder decoded = new StringBuilder(component.length());
		for (int i = 0; i < component.length(); i++) {
			final char character = component.charAt(i);
			if (character == '\1') {
				Assert.isPremiseValid(++i < component.length(), "A surface escape must have a code!");
				final char code = component.charAt(i);
				Assert.isPremiseValid(code == '\1' || code == '\2', "Invalid surface escape code!");
				decoded.append((char) (code - 1));
			} else {
				Assert.isPremiseValid(character != '\0', "An encoded component must not contain NUL!");
				decoded.append(character);
			}
		}
		return decoded.toString();
	}

	/**
	 * Validates canonical component encoding, including omitted identity stems and display spellings.
	 *
	 * @param key framed persisted key
	 * @param foldedEnd end of the folded component
	 */
	private static void validateSurfaceKey(@Nonnull String key, int foldedEnd) {
		final int stemEnd = surfaceStemEnd(key, foldedEnd);
		final String encodedFolded = key.substring(FulltextTermKeys.FIELD_PREFIX_WIDTH + 1, foldedEnd);
		final String folded = decodeSurfaceComponent(encodedFolded);
		Assert.isPremiseValid(encodeSurfaceComponent(folded).equals(encodedFolded), "Noncanonical folded component!");
		final String encodedStem = key.substring(foldedEnd + 1, stemEnd);
		if (!encodedStem.isEmpty()) {
			final String stem = decodeSurfaceComponent(encodedStem);
			Assert.isPremiseValid(!stem.equals(folded) && encodeDistinctSurfaceComponent(stem).equals(encodedStem),
				"An identity stem must be omitted!");
		}
		final String encodedDisplay = key.substring(stemEnd + 1);
		if (!encodedDisplay.isEmpty()) {
			final String display = decodeSurfaceComponent(encodedDisplay);
			Assert.isPremiseValid(display.indexOf('\0') < 0 && !display.equals(folded)
				&& encodeDistinctSurfaceComponent(display).equals(encodedDisplay), "Noncanonical surface display!");
		}
	}

	/**
	 * Opens a cursor bounded to one word phase and a literal folded prefix. An empty prefix visits the entire phase.
	 * NUL is ordinary token text here, including at the end. A cursor must not outlive a write to this index.
	 *
	 * @param wordPhase persistent fingerprint of the queried profile
	 * @param foldedPrefix literal query-prepared prefix
	 * @return bounded prefix cursor yielding normalized lookup keys, stems and display spellings
	 */
	@Nonnull
	public SurfaceFormCursor surfaceFormCursor(long wordPhase, @Nonnull String foldedPrefix) {
		return createSurfaceFormCursor(wordPhase, foldedPrefix, false);
	}

	/**
	 * Opens a cursor over exactly one literal folded word in one word phase, including words ending in NUL.
	 * A cursor must not outlive a write to this index.
	 *
	 * @param wordPhase persistent fingerprint of the queried profile
	 * @param foldedWord complete query-prepared lookup word, without any framing delimiter
	 * @return exact-word cursor yielding every independently counted stem of that word
	 */
	@Nonnull
	public SurfaceFormCursor exactSurfaceFormCursor(long wordPhase, @Nonnull String foldedWord) {
		return createSurfaceFormCursor(wordPhase, foldedWord, true);
	}

	/**
	 * Resolves the short phase prefix for either explicit cursor mode.
	 * @param wordPhase persistent phase fingerprint
	 * @param folded literal folded word or prefix
	 * @param exact whether the complete folded component must match
	 * @return bounded cursor, empty when this phase has no id
	 */
	@Nonnull
	private SurfaceFormCursor createSurfaceFormCursor(long wordPhase, @Nonnull String folded, boolean exact) {
		final WordPhaseId phaseId = this.wordPhaseIds.get(wordPhase);
		return new SurfaceFormCursor(phaseId == null ? null : surfacePhasePrefix(phaseId), folded, exact);
	}

	/**
	 * Returns the key prefix of a word phase in the surface dictionary: its short id followed by the frame NUL.
	 *
	 * @param phaseId the short id of the word phase
	 * @return the prefix every surface key of the phase starts with
	 */
	@Nonnull
	private static String surfacePhasePrefix(@Nonnull WordPhaseId phaseId) {
		return FulltextTermKeys.fieldPrefix(phaseId.value()) + '\0';
	}

	/**
	 * Returns where the folded component of a framed surface key ends.
	 *
	 * @param key the framed surface key
	 * @return the index of the NUL closing the folded component, or -1 when the key has none
	 */
	private static int surfaceFoldedEnd(@Nonnull String key) {
		return key.indexOf('\0', FulltextTermKeys.FIELD_PREFIX_WIDTH + 1);
	}

	/**
	 * Returns where the stem component of a framed surface key ends.
	 *
	 * @param key       the framed surface key
	 * @param foldedEnd the end of its folded component, see {@link #surfaceFoldedEnd(String)}
	 * @return the index of the NUL closing the stem component, or -1 when the key has none
	 */
	private static int surfaceStemEnd(@Nonnull String key, int foldedEnd) {
		return key.indexOf('\0', foldedEnd + 1);
	}

	/** A forward cursor over one word phase's surface keys, bounded by the requested folded prefix. */
	public final class SurfaceFormCursor {
		/** Encoded word-phase prefix, or null for a phase absent from the index. */
		@Nullable private final String phasePrefix;
		/** Encoded lower bound and range prefix. */
		@Nonnull private final String keyPrefix;
		/** Current underlying leaf cursor. */
		@Nullable private BucketCursor<String> delegate;
		/** Current encoded key, null before advancing or after exhaustion. */
		@Nullable private String key;

		/**
		 * Opens a range without exposing the mutable tree.
		 *
		 * @param phasePrefix encoded word-phase prefix, null for an absent phase
		 * @param foldedPrefix literal surface prefix or complete word
		 * @param exact whether to frame the complete word for exact matching
		 */
		private SurfaceFormCursor(@Nullable String phasePrefix, @Nonnull String foldedPrefix, boolean exact) {
			this.phasePrefix = phasePrefix;
			this.keyPrefix = phasePrefix == null ? ""
				: phasePrefix + encodeSurfaceComponent(foldedPrefix) + (exact ? "\0" : "");
			this.delegate = phasePrefix == null ? null : FulltextIndex.this.surfaceForms.cursor(this.keyPrefix);
		}

		/**
		 * Advances within the bounded range.
		 *
		 * @return true when another surface pair is available
		 */
		public boolean next() {
			if (this.delegate != null && this.delegate.next() && this.delegate.value().startsWith(this.keyPrefix)) {
				this.key = this.delegate.value();
				return true;
			}
			this.delegate = null;
			this.key = null;
			return false;
		}

		/**
		 * Seeks forward to a literal folded lower bound within this cursor's range, with NUL always treated as text.
		 * The next next() visits the ceiling key. Before the first result, bounds below the range start are clamped
		 * to that start, including the framing of an exact-word cursor.
		 * Uses the tree's existing cursor factory; a later tree cursor improvement can make this seek leaf-local.
		 *
		 * @param foldedLowerBound the normalized surface lower bound
		 * @throws io.evitadb.exception.GenericEvitaInternalError when the bound is not past the current key
		 */
		public void seek(@Nonnull String foldedLowerBound) {
			if (this.phasePrefix == null || this.delegate == null) {
				return;
			}
			final String lowerBound = this.phasePrefix + encodeSurfaceComponent(foldedLowerBound);
			final String target = lowerBound.compareTo(this.keyPrefix) < 0 ? this.keyPrefix : lowerBound;
			Assert.isPremiseValid(this.key == null || target.compareTo(this.key) > 0,
				"A surface cursor only seeks forward!");
			this.delegate = FulltextIndex.this.surfaceForms.cursor(target);
			this.key = null;
		}

		/**
		 * Returns the captured index token after the profile's embedded lookup normalization.
		 *
		 * @return folded surface without the word-phase prefix
		 */
		@Nonnull
		public String folded() {
			final String current = currentKey();
			return decodeSurfaceComponent(
				current.substring(FulltextTermKeys.FIELD_PREFIX_WIDTH + 1, surfaceFoldedEnd(current)));
		}

		/**
		 * Returns the index stem of the current pair.
		 *
		 * @return the term used for postings lookup in the queried profile's fields
		 */
		@Nonnull
		public String stem() {
			final String current = currentKey();
			final int foldedEnd = surfaceFoldedEnd(current);
			final String encoded = current.substring(foldedEnd + 1, surfaceStemEnd(current, foldedEnd));
			return encoded.isEmpty() ? folded() : decodeSurfaceComponent(encoded);
		}

		/**
		 * Returns the retained display spelling, falling back to the folded form when the key omits its display
		 * component because the display equals the folded form.
		 *
		 * @return the lower-cased accented word
		 */
		@Nonnull
		public String accented() {
			final String current = currentKey();
			final int start = surfaceStemEnd(current, surfaceFoldedEnd(current)) + 1;
			return start == current.length() ? folded() : decodeSurfaceComponent(current.substring(start));
		}

		/**
		 * Checks that the cursor currently stands on a key.
		 *
		 * @return the current encoded key
		 */
		@Nonnull
		private String currentKey() {
			Assert.isPremiseValid(this.key != null, "Advance the surface cursor before reading it!");
			return this.key;
		}
	}

	/**
	 * Emits changed surface leaves and stages their live sequences, just like {@link #collectChangedPages()} does for
	 * the term dictionary - including its first step, {@link #publishPreviousFlush()}, which within one flush also
	 * publishes the set that collect has just staged.
	 *
	 * @return dirty pages and the ordered live-page metadata
	 */
	@Nonnull
	public PageEmission<SurfaceFormPage> collectChangedSurfaceFormPages() {
		publishPreviousFlush();
		return this.pageStreamRegistry.collectChangedPages(
			SURFACE_FORM_PAGE_STREAM, this.surfaceForms.leafPageHandles(),
			(sequence, handle) -> {
				final String[] keys = new String[VALUE_BLOCK_SIZE];
				final long[] counts = new long[VALUE_BLOCK_SIZE];
				int count = 0;
				final BucketCursor<String> cursor = handle.cursor();
				while (cursor.next()) {
					keys[count] = cursor.value();
					counts[count++] = cursor.longRecordId();
				}
				return new SurfaceFormPage(sequence, Arrays.copyOf(keys, count), Arrays.copyOf(counts, count));
			});
	}

	/**
	 * Returns the surface pages a dropped index must reclaim.
	 *
	 * @return sequences of surface pages on disk
	 */
	@Nonnull
	public int[] getPersistedSurfaceFormPages() {
		return this.pageStreamRegistry.pendingLivePageSequences(SURFACE_FORM_PAGE_STREAM);
	}

	/**
	 * Restores the surface tree with its original leaf boundaries and clean page identities.
	 *
	 * @param sequences listed surface page sequences
	 * @param pages corresponding page contents
	 * @param highWater highest allocated surface page sequence
	 * @param registry dictionary and surface page bookkeeping
	 * @return the reassembled clean surface tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<String> restoreSurfaceForms(
		@Nonnull int[] sequences, @Nonnull SurfaceFormPage[] pages, int highWater,
		@Nonnull PageStreamRegistry registry
	) {
		Assert.isPremiseValid(sequences.length > 0 && sequences.length == pages.length,
			"Surface pages must match their nonempty sequence list!");
		final TransactionalBucketBPlusTree<String> result;
		if (pages.length == 1 && pages[0].keys().length == 0) {
			Assert.isPremiseValid(pages[0].pageSequence() == sequences[0], "Surface page sequence mismatch!");
			result = createSurfaceForms();
			result.leafPageHandles().get(0).setPageSequence(sequences[0]);
		} else {
			final List<TransactionalBucketBPlusTree<String>> trees = new ArrayList<>(pages.length);
			for (int i = 0; i < pages.length; i++) {
				final SurfaceFormPage page = pages[i];
				Assert.isPremiseValid(page.pageSequence() == sequences[i], "Surface page sequence mismatch!");
				Assert.isPremiseValid(page.keys().length > 0, "Only the sole surface root page may be empty!");
				final TransactionalBucketBPlusTree<String> tree = createSurfaceForms();
				tree.bulkLoadSingleRecordPage(page.keys(), page.referenceCounts(), page.keys().length);
				trees.add(tree);
			}
			result = createSurfaceForms().assembleFromSingleLeafTrees(trees, sequences, "fulltext surface forms");
		}
		registry.restore(SURFACE_FORM_PAGE_STREAM, highWater, sequences);
		return result;
	}

	/**
	 * One persisted surface leaf, holding primitive reference counts and no postings.
	 *
	 * @param pageSequence stable leaf page sequence
	 * @param keys encoded keys in ascending order
	 * @param referenceCounts one positive count per key
	 */
	public record SurfaceFormPage(int pageSequence, @Nonnull String[] keys, @Nonnull long[] referenceCounts) {
		/**
		 * Validates the count column before accepting a leaf page.
		 *
		 * @param pageSequence stable leaf sequence
		 * @param keys encoded surface keys
		 * @param referenceCounts positive references aligned with the keys
		 */
		public SurfaceFormPage {
			Assert.isPremiseValid(keys.length == referenceCounts.length, "Surface counts must align with keys!");
			for (final long count : referenceCounts) {
				Assert.isPremiseValid(count > 0, "A stored surface pair must have positive references!");
			}
		}
	}

	/**
	 * One leaf page of the dictionary, as a flush writes it.
	 *
	 * @param pageSequence the page's stable sequence
	 * @param buckets      the leaf's buckets in ascending key order: the encoded key and its posting list
	 * @param impacts      the impacts of each bucket, `impacts[i][j]` belonging to the j-th posting of `buckets[i]`
	 */
	public record DictionaryPage(int pageSequence, @Nonnull ValueToRecord[] buckets, @Nonnull byte[][] impacts) {
	}

	/**
	 * A pair lookup compares borrowed characters without allocating a capture string. Only the scratch instance is
	 * reset; keys inserted into the value's map are frozen copies owning strings. No mutable capture escapes analysis.
	 */
	private static final class SurfacePair {
		/** Borrowed characters in the scratch lookup, immutable string in a stored key. */
		@Nonnull private CharSequence folded;
		/** Final posting term already materialized by the index analyzer. */
		@Nonnull private String stem;
		/** Content hash stable for stored keys, recomputed when the scratch lookup is reset. */
		private int hash;

		/**
		 * Creates a scratch or frozen pair over the supplied characters.
		 * @param folded borrowed or immutable normalized lookup text
		 * @param stem final index term
		 */
		private SurfacePair(@Nonnull CharSequence folded, @Nonnull String stem) {
			reset(folded, stem);
		}

		/**
		 * Updates the scratch lookup without retaining a buffer in a map.
		 * @param folded borrowed normalized lookup text valid during this callback
		 * @param stem final index term
		 */
		private void reset(@Nonnull CharSequence folded, @Nonnull String stem) {
			this.folded = folded;
			this.stem = stem;
			int foldedHash = 0;
			for (int i = 0; i < folded.length(); i++) {
				foldedHash = 31 * foldedHash + folded.charAt(i);
			}
			this.hash = 31 * foldedHash + stem.hashCode();
		}

		/**
		 * Materializes capture characters only for a previously unseen pair.
		 * @return stable map key owning immutable text
		 */
		@Nonnull
		private SurfacePair freeze() {
			return new SurfacePair(this.folded.toString(), this.stem);
		}

		@Override
		public int hashCode() {
			return this.hash;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return this == other || other instanceof SurfacePair pair
				&& this.stem.equals(pair.stem) && CharSequence.compare(this.folded, pair.folded) == 0;
		}
	}

	/**
	 * One value after analysis.
	 *
	 * @param termFrequencies each distinct term with its frequency in a one-element array, so counting allocates
	 *                        once per distinct term rather than once per occurrence
	 * @param surfacePairs distinct folded/stem pair prefixes with their first accented spelling
	 * @param length          the value's length in token positions, summed over its elements
	 */
	private record AnalyzedValue(
		@Nonnull Map<String, int[]> termFrequencies, @Nonnull Map<String, String> surfacePairs, int length
	) {
	}

}
