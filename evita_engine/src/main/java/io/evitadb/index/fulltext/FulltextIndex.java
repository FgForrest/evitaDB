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
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
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
 * baseline the next flush diffs against, carried by reference through every merge.
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
	 * registrations follow them, in its {@link FulltextIndexChanges}.
	 */
	@Nonnull private final List<Field> fields;

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
	record Field(@Nonnull String name, double lengthPivot, @Nonnull FieldLengthTable lengths) {
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
	@SuppressWarnings("unchecked")
	public FulltextIndex(@Nonnull FulltextAnalyzer indexAnalyzer, double defaultLengthPivot) {
		assertPivotValid(defaultLengthPivot);
		this.indexAnalyzer = indexAnalyzer;
		this.defaultLengthPivot = defaultLengthPivot;
		this.fieldIds = CollectionUtils.createHashMap(8);
		this.fields = new ArrayList<>(8);
		// natural `String` order - UTF-16 code-unit order, not code-point order: the field prefix relies on it, see
		// FulltextTermKeys
		this.dictionary = new TransactionalBucketBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			String.class,
			null,
			(ValueColumnFactory<String>) ValueColumnFactory.forKey(String.class, null)
		);
		this.dictionary.enableImpacts();
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
		@Nonnull List<Field> fields,
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
			this.fields.add(field);
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
					return this.fields.size() + i;
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
		return layer == null ? this.fields.size() : this.fields.size() + layer.getAddedFields().size();
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
		final int committedCount = this.fields.size();
		final int fieldCount = committedCount + added.size();
		final Map<String, Integer> mergedFieldIds = CollectionUtils.createHashMap(fieldCount);
		final List<Field> mergedFields = new ArrayList<>(fieldCount);
		for (int fieldId = 0; fieldId < fieldCount; fieldId++) {
			final Field field = fieldId < committedCount
				? this.fields.get(fieldId)
				: added.get(fieldId - committedCount);
			mergedFieldIds.put(field.name(), fieldId);
			mergedFields.add(
				new Field(
					field.name(),
					field.lengthPivot(),
					transactionalLayer.getStateCopyWithCommittedChanges(field.lengths())
				)
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
		final int committedCount = this.fields.size();
		if (fieldId < committedCount) {
			return this.fields.get(fieldId);
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
		while (this.fields.size() > fieldId) {
			this.fieldIds.remove(this.fields.remove(this.fields.size() - 1).name());
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
