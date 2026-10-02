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
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bPlusTree.OverflowRecords;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlockEmission;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.index.map.MapHeapSize;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
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
 * - **Field ids**, assigned on first use, which become the key prefix. The id of a field never changes for the life
 *   of the index, so a key once written always means the same field.
 *
 * ## The impact byte
 *
 * The impact of a posting is BM25's term-frequency saturation with the field length normalized against a **pivot**,
 * quantized to `1..255` (see {@link #computeImpact(int, int, double)}). The pivot is a fixed parameter of the field,
 * not the corpus' average length: an average moves with every write, which would make every stored impact stale the
 * moment the next document arrives and make two replicas of one catalog disagree about a score. With a fixed pivot
 * the impact is a function of the query term and the document alone. Until the schema can declare a pivot, it is
 * passed to the constructor (and per field through {@link #getOrAssignFieldId(String, double)}).
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
 * {@link FulltextIndexChanges}, which holds the fields a transaction registered. Every write also creates that
 * layer, so it doubles as the "written in this transaction" mark: an index no transaction wrote to is carried forward
 * as the same instance - keeping its identity, which is what a consumer keys a cache on - and none of its parts is
 * merged.
 *
 * Outside a transaction (the warm-up bulk path) everything is written in place, and each part journals its writes
 * into an open warm-up savepoint; the registry journals a field's registration, so a rolled-back entity mutation
 * that introduced a field leaves no field behind. Inside a transaction a per-entity savepoint rewinds every part
 * through its layer's memento.
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
	 * Returned by {@link #getFieldId(String)} for a field the index has never seen.
	 */
	public static final int UNKNOWN_FIELD_ID = -1;

	/**
	 * The length pivot used when the caller supplies none: a field of this many tokens is "of normal length", and
	 * its term frequencies are neither boosted nor damped by length.
	 */
	public static final double DEFAULT_LENGTH_PIVOT = 25.0;

	/**
	 * BM25's term-frequency saturation, `k1`. The customary value; nothing in P1 tuned it.
	 */
	public static final double BM25_K1 = 1.2;

	/**
	 * BM25's length normalization strength, `b`. The customary value; nothing in P1 tuned it.
	 */
	public static final double BM25_B = 0.75;

	/**
	 * Largest impact a posting can carry.
	 */
	public static final int MAX_IMPACT = 255;

	/**
	 * Maximum number of buckets (terms) in a leaf of the dictionary. The value the P1 measurements were taken with,
	 * and the one the inverted index uses for its own value trees.
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
	 * Analyzer of the index slot of this partition's locale; every indexed value goes through it.
	 */
	@Nonnull private final FulltextAnalyzer indexAnalyzer;

	/**
	 * The pivot a field gets when it is registered without one.
	 */
	private final double defaultLengthPivot;

	/**
	 * Field name to its id, for the fields committed with this instance (and, outside a transaction, registered since).
	 */
	@Nonnull private final Map<String, Integer> fieldIds;

	/**
	 * Per-field state indexed by the field's id, for the same fields as {@link #fieldIds}. A transaction's own
	 * registrations follow them, in its {@link FulltextIndexChanges}. Copy-on-write: a field is registered a handful of
	 * times in the life of an index, so the array is replaced rather than grown, and its length is the field count.
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
	 * The state of one field. Its name and pivot never change; its length table is replaced by its committed copy at
	 * every commit that merges the index.
	 *
	 * @param name        the field's name
	 * @param lengthPivot the field's length pivot
	 * @param lengths     the lengths of the field's indexed values
	 */
	public record Field(@Nonnull String name, double lengthPivot, @Nonnull FieldLengthTable lengths) {
	}

	/**
	 * Creates an empty index with {@link #DEFAULT_LENGTH_PIVOT} as the fields' default pivot.
	 *
	 * @param indexAnalyzer analyzer of the index slot of the partition's locale
	 */
	public FulltextIndex(@Nonnull FulltextAnalyzer indexAnalyzer) {
		this(indexAnalyzer, DEFAULT_LENGTH_PIVOT);
	}

	/**
	 * Creates an empty index.
	 *
	 * @param indexAnalyzer      analyzer of the index slot of the partition's locale
	 * @param defaultLengthPivot length pivot a field gets when registered without one; must be positive and finite
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the pivot is not positive and finite
	 */
	public FulltextIndex(@Nonnull FulltextAnalyzer indexAnalyzer, double defaultLengthPivot) {
		assertPivotValid(defaultLengthPivot);
		this.indexAnalyzer = indexAnalyzer;
		this.defaultLengthPivot = defaultLengthPivot;
		this.fieldIds = CollectionUtils.createHashMap(8);
		this.fields = NO_FIELDS;
		this.dictionary = createDictionary();
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	/**
	 * Adopts already-merged parts - the constructor the commit merge builds the next version with.
	 *
	 * @param indexAnalyzer      analyzer of the index slot of the partition's locale
	 * @param defaultLengthPivot length pivot a field gets when registered without one
	 * @param fieldIds           field name to id
	 * @param fields             per-field state by id
	 * @param dictionary         the term dictionary
	 * @param pageStreamRegistry the page bookkeeping, carried by reference from the version being merged
	 */
	private FulltextIndex(
		@Nonnull FulltextAnalyzer indexAnalyzer,
		double defaultLengthPivot,
		@Nonnull Map<String, Integer> fieldIds,
		@Nonnull Field[] fields,
		@Nonnull TransactionalBucketBPlusTree<String> dictionary,
		@Nonnull PageStreamRegistry pageStreamRegistry
	) {
		this.indexAnalyzer = indexAnalyzer;
		this.defaultLengthPivot = defaultLengthPivot;
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
	 * @param indexAnalyzer         analyzer of the index slot of the partition's locale
	 * @param defaultLengthPivot    length pivot a field registered later without one gets
	 * @param fields                the registered fields in id order, each with its length table
	 * @param orderedPageSequences  the dictionary's page sequences in key order, as the last flush listed them
	 * @param pages                 the pages, positionally aligned with `orderedPageSequences`
	 * @param highWaterPageSequence the highest page sequence the dictionary ever allocated
	 * @return the restored index, clean
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the pages do not match their list, overlap, or an
	 *                                                        empty page is not the dictionary's only one
	 */
	@Nonnull
	public static FulltextIndex fromPersistedPages(
		@Nonnull FulltextAnalyzer indexAnalyzer,
		double defaultLengthPivot,
		@Nonnull List<Field> fields,
		@Nonnull int[] orderedPageSequences,
		@Nonnull DictionaryPage[] pages,
		int highWaterPageSequence
	) {
		assertPivotValid(defaultLengthPivot);
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
		final Map<String, Integer> fieldIds = CollectionUtils.createHashMap(fields.size());
		for (int fieldId = 0; fieldId < fields.size(); fieldId++) {
			final Field field = fields.get(fieldId);
			assertPivotValid(field.lengthPivot());
			final Integer previous = fieldIds.put(field.name(), fieldId);
			Assert.isPremiseValid(previous == null, () -> "Fulltext field `" + field.name() + "` is persisted twice!");
		}
		return new FulltextIndex(
			indexAnalyzer, defaultLengthPivot, fieldIds, fields.toArray(NO_FIELDS), dictionary, pageStreamRegistry
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
	 * Returns the id of a field, registering it with the default pivot when it is new. An existing field is returned
	 * whatever pivot it was registered with.
	 *
	 * @param fieldName name of the searchable field
	 * @return the field's id
	 */
	public int getOrAssignFieldId(@Nonnull String fieldName) {
		final int existing = getFieldId(fieldName);
		// the default pivot applies to a registration only - a field registered with its own keeps it
		return existing == UNKNOWN_FIELD_ID ? getOrAssignFieldId(fieldName, this.defaultLengthPivot) : existing;
	}

	/**
	 * Returns the id of a field, registering it with the passed pivot when it is new. A field's pivot cannot change
	 * once impacts were computed against it — that would take a reindex — so asking for an existing field with a
	 * different pivot is refused.
	 *
	 * @param fieldName   name of the searchable field
	 * @param lengthPivot the field's length pivot; must be positive
	 * @return the field's id
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the pivot differs from the registered one, or the
	 *                                                        index already holds as many fields as the key prefix can
	 *                                                        address
	 */
	public int getOrAssignFieldId(@Nonnull String fieldName, double lengthPivot) {
		assertPivotValid(lengthPivot);
		final int existing = getFieldId(fieldName);
		if (existing != UNKNOWN_FIELD_ID) {
			final double registered = fieldAt(existing).lengthPivot();
			Assert.isPremiseValid(
				Double.compare(registered, lengthPivot) == 0,
				() -> "Fulltext field `" + fieldName + "` is registered with length pivot " + registered +
					", it cannot change to " + lengthPivot + " without a reindex!"
			);
			return existing;
		}
		final FulltextIndexChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		// a new field changes what the index persists even before it holds a value
		this.dirty.setToTrue();
		final int fieldId = getFieldCount();
		Assert.isPremiseValid(
			fieldId <= FulltextTermKeys.MAX_FIELD_ID,
			() -> "The fulltext index cannot address more than " + (FulltextTermKeys.MAX_FIELD_ID + 1) + " fields!"
		);
		final Field field = new Field(fieldName, lengthPivot, new FieldLengthTable());
		if (layer != null) {
			layer.addField(field);
		} else {
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				// an absolute restore: no field from this id on, whatever was registered after it
				savepoint.push(() -> unregisterFieldsFrom(fieldId));
			}
			this.fieldIds.put(fieldName, fieldId);
			final Field[] registered = Arrays.copyOf(this.fields, fieldId + 1);
			registered[fieldId] = field;
			this.fields = registered;
		}
		return fieldId;
	}

	/**
	 * Returns the id of a field without assigning one.
	 *
	 * @param fieldName name of the searchable field
	 * @return the field's id, or {@link #UNKNOWN_FIELD_ID} when the index has never seen the field
	 */
	public int getFieldId(@Nonnull String fieldName) {
		final Integer existing = this.fieldIds.get(fieldName);
		if (existing != null) {
			return existing;
		}
		final FulltextIndexChanges layer = Transaction.getTransactionalMemoryLayerIfExists(this);
		if (layer != null) {
			final List<Field> added = layer.getAddedFields();
			for (int i = 0; i < added.size(); i++) {
				if (added.get(i).name().equals(fieldName)) {
					return this.fields.length + i;
				}
			}
		}
		return UNKNOWN_FIELD_ID;
	}

	/**
	 * Returns the name of a field.
	 *
	 * @param fieldId id of the field
	 * @return the field's name, or null when no field carries the id
	 */
	@Nullable
	public String getFieldName(int fieldId) {
		return fieldId >= 0 && fieldId < getFieldCount() ? fieldAt(fieldId).name() : null;
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
	 * Returns the number of fields the index has assigned an id to.
	 *
	 * @return the field count
	 */
	public int getFieldCount() {
		final FulltextIndexChanges layer = Transaction.getTransactionalMemoryLayerIfExists(this);
		return layer == null ? this.fields.length : this.fields.length + layer.getAddedFields().size();
	}

	/**
	 * Returns the name of the analyzer of the index slot every value of this index went through. The persisted index
	 * records it, so that it is read back with the analyzer it was built with.
	 *
	 * @return the analyzer name
	 */
	@Nonnull
	public String getAnalyzerName() {
		return this.indexAnalyzer.getAnalyzerName();
	}

	/**
	 * Returns the pivot a field gets when it is registered without one.
	 *
	 * @return the default length pivot
	 */
	public double getDefaultLengthPivot() {
		return this.defaultLengthPivot;
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
	 * Indexes the value of an entity's field: analyzes it through the index slot, and records every distinct term
	 * with its impact and the value's length. The entity must not already have a value indexed for the field — an
	 * update is a {@link #removeValue(String, int, String)} of the old value followed by this.
	 *
	 * A value producing no token is indexed as nothing: no posting and no length.
	 *
	 * @param fieldName  name of the searchable field; registered with the default pivot when new
	 * @param primaryKey primary key of the entity
	 * @param value      the stored value
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the entity already has a value for the field
	 */
	public void addValue(@Nonnull String fieldName, int primaryKey, @Nonnull String value) {
		indexValues(fieldName, primaryKey, value);
	}

	/**
	 * Indexes the array value of an entity's field as one text: every element is analyzed through the index slot,
	 * and the term frequencies and lengths of all of them are summed (see the class documentation). The entity must
	 * not already have a value indexed for the field — an update is a {@link #removeValue(String, int, String[])} of
	 * the whole old array followed by this.
	 *
	 * An array whose elements produce no token is indexed as nothing: no posting and no length.
	 *
	 * @param fieldName  name of the searchable field; registered with the default pivot when new
	 * @param primaryKey primary key of the entity
	 * @param values     the stored array
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the entity already has a value for the field
	 */
	public void addValue(@Nonnull String fieldName, int primaryKey, @Nonnull String[] values) {
		indexValues(fieldName, primaryKey, values);
	}

	/**
	 * Removes the value of an entity's field from the index: analyzes the value again — the index does not keep the
	 * original — and removes the entity from the posting list of every term it produced, and its length. The value
	 * must be the one that was indexed, or terms it no longer produces would keep their postings.
	 *
	 * Removing from a field the index has never seen, or a value producing no token, changes nothing.
	 *
	 * @param fieldName  name of the searchable field
	 * @param primaryKey primary key of the entity
	 * @param value      the value that was indexed
	 */
	public void removeValue(@Nonnull String fieldName, int primaryKey, @Nonnull String value) {
		unindexValues(fieldName, primaryKey, value);
	}

	/**
	 * Removes the array value of an entity's field from the index — the mirror of
	 * {@link #addValue(String, int, String[])}. The array must hold the elements that were indexed, in any order;
	 * removing only some of them is not possible, because the entity has one entry for the whole array.
	 *
	 * Removing from a field the index has never seen, or an array producing no token, changes nothing.
	 *
	 * @param fieldName  name of the searchable field
	 * @param primaryKey primary key of the entity
	 * @param values     the array that was indexed
	 */
	public void removeValue(@Nonnull String fieldName, int primaryKey, @Nonnull String[] values) {
		unindexValues(fieldName, primaryKey, values);
	}

	/**
	 * The body of both `addValue` overloads: one value is an array of one element.
	 *
	 * @param fieldName  name of the searchable field; registered with the default pivot when new
	 * @param primaryKey primary key of the entity
	 * @param values     the elements of the value
	 */
	private void indexValues(@Nonnull String fieldName, int primaryKey, @Nonnull String... values) {
		final int fieldId = getOrAssignFieldId(fieldName);
		final Field field = fieldAt(fieldId);
		Assert.isPremiseValid(
			field.lengths().getEncoded(primaryKey) == 0,
			() -> "Entity " + primaryKey + " already has a value of fulltext field `" + fieldName + "` indexed; " +
				"remove it before indexing another."
		);
		final AnalyzedValue analyzed = analyze(values);
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
	 * The body of both `removeValue` overloads: one value is an array of one element.
	 *
	 * @param fieldName  name of the searchable field
	 * @param primaryKey primary key of the entity
	 * @param values     the elements of the value that was indexed
	 */
	private void unindexValues(@Nonnull String fieldName, int primaryKey, @Nonnull String... values) {
		final int fieldId = getFieldId(fieldName);
		if (fieldId == UNKNOWN_FIELD_ID) {
			return;
		}
		final AnalyzedValue analyzed = analyze(values);
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
	 * Records a single posting directly, bypassing analysis — the low-level counterpart of
	 * {@link #addValue(String, int, String)} for callers that analyze themselves. Adding a posting that is already
	 * present replaces its impact.
	 *
	 * Only the dictionary is touched: the field's {@link FieldLengthTable} is not, so a caller mixing this with
	 * {@link #addValue(String, int, String)} for the same entity leaves its length and its postings disagreeing.
	 *
	 * @param fieldId    id of the field, as returned by {@link #getOrAssignFieldId(String)}
	 * @param term       the analyzed term
	 * @param primaryKey primary key of the entity
	 * @param impact     the posting's impact, `1..`{@link #MAX_IMPACT}
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the index never assigned the field id, or the
	 *                                                        impact is out of range
	 */
	public void addPosting(int fieldId, @Nonnull String term, int primaryKey, int impact) {
		assertFieldKnown(fieldId);
		Assert.isPremiseValid(
			impact >= 1 && impact <= MAX_IMPACT,
			() -> "An impact must be within 1.." + MAX_IMPACT + ", " + impact + " was passed!"
		);
		markWritten();
		this.dictionary.addRecord(FulltextTermKeys.encode(fieldId, term), primaryKey, (byte) impact);
	}

	/**
	 * Removes a single posting directly, bypassing analysis. The term leaves the dictionary with its last posting;
	 * removing a posting that is not present changes nothing. Like {@link #addPosting}, it leaves the field's
	 * {@link FieldLengthTable} untouched.
	 *
	 * @param fieldId    id of the field, as returned by {@link #getOrAssignFieldId(String)}
	 * @param term       the analyzed term
	 * @param primaryKey primary key of the entity
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the index never assigned the field id
	 */
	public void removePosting(int fieldId, @Nonnull String term, int primaryKey) {
		assertFieldKnown(fieldId);
		markWritten();
		this.dictionary.removeRecord(FulltextTermKeys.encode(fieldId, term), primaryKey);
	}

	/**
	 * Returns the posting list of a term in a field.
	 *
	 * @param fieldId id of the field
	 * @param term    the analyzed term
	 * @return the primary keys of the entities whose field contains the term — a read-only view of the dictionary's
	 * own storage, empty when the term is absent
	 */
	@Nonnull
	public Bitmap getPostings(int fieldId, @Nonnull String term) {
		assertFieldKnown(fieldId);
		return this.dictionary.getRecordsEqualTo(FulltextTermKeys.encode(fieldId, term));
	}

	/**
	 * Returns the impacts of a term in a field, aligned with its posting list. The answer is a copy, paid for with a
	 * descent of the dictionary; a walk over many terms reads them through {@link #forEachTerm} and its
	 * {@link TermVisitor} instead, which costs neither.
	 *
	 * @param fieldId id of the field
	 * @param term    the analyzed term
	 * @return one unsigned byte per posting, `impacts[i]` belonging to the i-th posting of
	 * {@link #getPostings(int, String)}; empty when the term is absent
	 */
	@Nonnull
	public byte[] getImpacts(int fieldId, @Nonnull String term) {
		assertFieldKnown(fieldId);
		return this.dictionary.impactsOf(FulltextTermKeys.encode(fieldId, term));
	}

	/**
	 * Returns the length table of a field.
	 *
	 * @param fieldId id of the field
	 * @return the lengths of the field's indexed values
	 */
	@Nonnull
	public FieldLengthTable getFieldLengths(int fieldId) {
		assertFieldKnown(fieldId);
		return fieldAt(fieldId).lengths();
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
	 * Returns the number of distinct (field, term) keys in the dictionary.
	 *
	 * @return the dictionary size
	 */
	public int getTermCount() {
		return this.dictionary.size();
	}

	/**
	 * Returns the heap this index occupies, in bytes: the index object and its dirty flag, the field registry - the
	 * name-to-id map, the field array and every field's length table - and the term dictionary with its postings and
	 * impacts, priced by the dictionary itself.
	 *
	 * Not charged: the analyzer, which the registry shares among every index using it, and the page bookkeeping, which
	 * no paged index charges - it is flush state carried by reference through every committed copy. Each field name is
	 * charged once, as the key of {@link #fieldIds}; its {@link Field} holds the very same instance. A running
	 * transaction's layer belongs to the transaction.
	 *
	 * Walking the dictionary costs `O(terms / block size)`, so this is an index-detail figure, never one a query path
	 * may ask for.
	 *
	 * @return the heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// id and defaultLengthPivot, then the indexAnalyzer / fieldIds / fields / dictionary / dirty /
		// pageStreamRegistry slots
		long size = layout.sizeOfObject(Long.BYTES + Double.BYTES + 6L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			// the boxed field id is charged to this map, its only holder
			+ MapHeapSize.sizeOf(
				this.fieldIds, MemoryMeasuringConstants::computeStringSize, fieldId -> layout.sizeOfObject(Integer.BYTES)
			)
			+ this.dictionary.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER);
		if (this.fields.length > 0) {
			// the shared empty registry belongs to no index
			size += layout.sizeOfArray(this.fields.length, layout.referenceSize());
			for (final Field field : this.fields) {
				// lengthPivot, then the name / lengths slots
				size += layout.sizeOfObject(Double.BYTES + 2L * layout.referenceSize())
					+ field.lengths().getHeapSizeInBytes();
			}
		}
		return size;
	}

	@Nonnull
	@Override
	public FulltextIndexChanges createLayer() {
		return new FulltextIndexChanges();
	}

	/**
	 * The delegate branch journals the one thing it writes itself, a field registration; every part journals its own
	 * writes.
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
		final Map<String, Integer> mergedFieldIds = CollectionUtils.createHashMap(fieldCount);
		final Field[] mergedFields = fieldCount == 0 ? NO_FIELDS : new Field[fieldCount];
		for (int fieldId = 0; fieldId < fieldCount; fieldId++) {
			final Field field = fieldId < committedCount
				? this.fields[fieldId]
				: added.get(fieldId - committedCount);
			mergedFieldIds.put(field.name(), fieldId);
			mergedFields[fieldId] = new Field(
				field.name(),
				field.lengthPivot(),
				transactionalLayer.getStateCopyWithCommittedChanges(field.lengths())
			);
		}
		return new FulltextIndex(
			this.indexAnalyzer,
			this.defaultLengthPivot,
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
	 * The caller gates on {@link #isDirty()}; a clean index must not be collected.
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
				this.fieldIds.remove(this.fields[i].name());
			}
			this.fields = fieldId == 0 ? NO_FIELDS : Arrays.copyOf(this.fields, fieldId);
		}
	}

	/**
	 * Analyzes the elements of a value through the index slot into its distinct terms with their frequencies and its
	 * length, summed over the elements - one element for a single value.
	 *
	 * The length counts token **positions**: a term arriving at position increment `0` is a variant of the previous
	 * position, not a further token of the text, and a stop word removed by the chain leaves a gap but is not a token
	 * either. Each element is analyzed on its own, so no token spans two of them.
	 *
	 * The term frequencies count every term the chain emits, stacked variants at increment `0` included, so a term's
	 * frequency may exceed the length.
	 *
	 * @param values the elements to analyze
	 * @return the analysis
	 */
	@Nonnull
	private AnalyzedValue analyze(@Nonnull String... values) {
		final Map<String, int[]> termFrequencies = CollectionUtils.createHashMap(16);
		final int[] length = new int[1];
		for (final String value : values) {
			Assert.isPremiseValid(value != null, "A fulltext value must not contain a null element!");
			this.indexAnalyzer.analyze(value, (term, surfaceForm, startOffset, endOffset, positionIncrement) -> {
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
