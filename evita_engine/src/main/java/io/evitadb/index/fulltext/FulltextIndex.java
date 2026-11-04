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
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.typo.FulltextDictionaryWalker;
import io.evitadb.index.fulltext.typo.FulltextDictionaryWalker.MatchVisitor;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.index.map.MapHeapSize;
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
 * Each part versions itself: the dictionary through the bucket tree's node layers (the impacts ride along in the
 * leaves), every length table through its own layer, and the field registry through this index's
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
 * The dictionary is written in pages, one per leaf, the way the inverted index writes its buckets: a separate dirty
 * flag - "written since the last flush", on both paths - gates the flush, {@link #collectChangedPages()} emits the
 * leaves that changed and the pages that left, and a {@link PageStreamRegistry} keeps the page sequences and the
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
	 * Local key of the dictionary's page stream in {@link #pageStreamRegistry} - the index's only paged structure so
	 * far. It is not the stream id the storage resolves for the pages; that one is assigned by the catalog's key
	 * compressor when the pages are written.
	 */
	private static final int DICTIONARY_PAGE_STREAM = 0;

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

	/**
	 * Whether anything was written to the index since its last flush collected it - the gate of the flush. Set by
	 * every write, inside a transaction and outside one alike, and cleared by {@link #resetDirty()} once the flush has
	 * collected the changes.
	 */
	@Nonnull private final TransactionalBoolean dirty;

	/**
	 * The page bookkeeping of the dictionary: the page sequence allocator, its high-water and the set of pages on disk
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
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	/**
	 * Adopts already-merged parts - the constructor the commit merge builds the next version with.
	 *
	 * @param fieldIds           key to id of every field not retired
	 * @param fields             per-field state by id
	 * @param dictionary         the term dictionary
	 * @param pageStreamRegistry the page bookkeeping, carried by reference from the version being merged
	 */
	private FulltextIndex(
		@Nonnull Map<FulltextFieldKey, Integer> fieldIds,
		@Nonnull Field[] fields,
		@Nonnull TransactionalBucketBPlusTree<String> dictionary,
		@Nonnull PageStreamRegistry pageStreamRegistry
	) {
		this.fieldIds = fieldIds;
		this.fields = fields;
		this.dictionary = dictionary;
		// the merge runs after the flush collected this version's changes, so the committed copy starts clean
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = pageStreamRegistry;
	}

	/**
	 * Restores an index from its persisted parts: the field registry with every field's lengths, and the dictionary's
	 * leaf pages in key order. Each page becomes one leaf, so the restored dictionary has the leaf boundaries it was
	 * written with, and the page bookkeeping is restored with it - every leaf keeps its page sequence and none is
	 * dirty, so the first flush after the load writes nothing for an unchanged dictionary.
	 *
	 * An empty dictionary is persisted as the single empty page of its root leaf, and restored as such.
	 *
	 * @param fields                the registered fields in id order, retired ones included, each with its length
	 *                              table
	 * @param orderedPageSequences  the dictionary's page sequences in key order, as the last flush listed them
	 * @param pages                 the pages, positionally aligned with `orderedPageSequences`
	 * @param highWaterPageSequence the highest page sequence the dictionary ever allocated
	 * @return the restored index, clean
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the pages do not match their list, overlap, or an
	 *                                                        empty page is not the dictionary's only one; when a field
	 *                                                        has a non-positive or non-finite pivot; or when two
	 *                                                        fields of one key are persisted as not retired
	 */
	@Nonnull
	public static FulltextIndex fromPersistedPages(
		@Nonnull List<Field> fields,
		@Nonnull int[] orderedPageSequences,
		@Nonnull DictionaryPage[] pages,
		int highWaterPageSequence
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
		final Map<FulltextFieldKey, Integer> fieldIds = CollectionUtils.createHashMap(fields.size());
		for (int fieldId = 0; fieldId < fields.size(); fieldId++) {
			final Field field = fields.get(fieldId);
			assertPivotValid(field.lengthPivot());
			if (!field.retired()) {
				final Integer previous = fieldIds.put(field.key(), fieldId);
				Assert.isPremiseValid(
					previous == null, () -> "Fulltext field " + field.key() + " is persisted twice as not retired!"
				);
			}
		}
		return new FulltextIndex(
			fieldIds, fields.toArray(NO_FIELDS), dictionary, pageStreamRegistry
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
		indexValues(fieldId, primaryKey, analyzer.index(), values);
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
		unindexValues(fieldId, primaryKey, analyzer.index(), values);
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
		int fieldId, int primaryKey, @Nonnull FulltextAnalyzer analyzer, @Nonnull String... values
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
		int fieldId, int primaryKey, @Nonnull FulltextAnalyzer analyzer, @Nonnull String... values
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
	 * name-to-id map, the field array and every field's length table - and the term dictionary with its postings and
	 * impacts, priced by the dictionary itself.
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
		// The id and the fieldIds / fields / dictionary / dirty / pageStreamRegistry slots.
		long size = layout.sizeOfObject(Long.BYTES + 5L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			// the key is charged with its field, the boxed field id to this map, its only holder
			+ MapHeapSize.sizeOf(this.fieldIds, fieldKey -> 0L, fieldId -> layout.sizeOfObject(Integer.BYTES))
			+ this.dictionary.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER);
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
	 * A leaf without a page - a fresh one, or either half of a split, both of which are new leaves - is assigned a newly
	 * allocated page; a leaf is collected when it is new or its transaction-aware dirty flag is set, and the flag is
	 * cleared on the way. The next set of live pages is staged, and becomes the baseline at the commit merge.
	 *
	 * Before anything is staged, the set staged by the PREVIOUS flush is published: see {@link #publishPreviousFlush()}
	 * for why that is necessary. The dictionary is always written in pages, even when it fits one leaf: an index
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
	 * Promotes the set of pages staged by the previous flush to the baseline this flush diffs against.
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
	 * On the transactional path the merge has already published, and this is a no-op.
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
	 * frequency may exceed the length.
	 *
	 * @param analyzer the compiled index chain of the field
	 * @param values the elements to analyze
	 * @return the analysis
	 */
	@Nonnull
	private AnalyzedValue analyze(@Nonnull FulltextAnalyzer analyzer, @Nonnull String... values) {
		final Map<String, int[]> termFrequencies = CollectionUtils.createHashMap(16);
		final int[] length = new int[1];
		for (final String value : values) {
			Assert.isPremiseValid(value != null, "A fulltext value must not contain a null element!");
			analyzer.analyze(value, (term, surfaceForm, startOffset, endOffset, positionIncrement) -> {
				if (positionIncrement > 0) {
					length[0]++;
				}
				termFrequencies.computeIfAbsent(term, t -> new int[1])[0]++;
			});
		}
		return new AnalyzedValue(termFrequencies, length[0]);
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
	 * One leaf page of the dictionary, as a flush writes it.
	 *
	 * @param pageSequence the page's stable sequence
	 * @param buckets      the leaf's buckets in ascending key order: the encoded key and its posting list
	 * @param impacts      the impacts of each bucket, `impacts[i][j]` belonging to the j-th posting of `buckets[i]`
	 */
	public record DictionaryPage(int pageSequence, @Nonnull ValueToRecord[] buckets, @Nonnull byte[][] impacts) {
	}

	/**
	 * One value after analysis.
	 *
	 * @param termFrequencies each distinct term with its frequency in a one-element array, so counting allocates
	 *                        once per distinct term rather than once per occurrence
	 * @param length          the value's length in token positions, summed over its elements
	 */
	private record AnalyzedValue(@Nonnull Map<String, int[]> termFrequencies, int length) {
	}

}
