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

import io.evitadb.index.fulltext.analysis.AnalyzedTerm;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference model of a {@link FulltextIndex} that the generational proofs drive the index with and check it
 * against: which fields the index registered, in which order and with which pivot, which of them were retired, and
 * which value every entity has indexed in every field.
 *
 * The model works as a pair with an index. {@link #applyRandomOperations} draws a random step, applies it to the
 * index and records it, so the two stay in lockstep. {@link #expectedContent()} then renders what the index must
 * hold, and {@link #contentOf(FulltextIndex)} renders what it does hold, in the same format so the two compare with
 * `equals`.
 *
 * ## What the oracle derives itself, and what it shares
 *
 * The expected postings, impacts and lengths are computed from the modelled VALUES, never read from an index:
 * every term with the entities whose value produced it, every impact from the term frequency, the value's length
 * and the field's pivot, and every length through the table's encoding. An asymmetry between adding and removing a
 * value, an impact left behind by a split, or a length that outlives its value shows up as a difference.
 *
 * The oracle shares three pure functions with the implementation: the analyzer, {@link FulltextIndex#computeImpact}
 * and {@link FieldLengthTable#encode}. A defect inside one of them is invisible here, because both sides would be
 * wrong together. Their own tests (`FulltextAnalyzerTest`, `FulltextIndexTest`, `NumberUtilsTest`) cover them.
 *
 * ## The steps
 *
 * - **Write a value**: the entity's current value of the field, if any, is removed and a new one is often indexed in
 *   its place. A value is one text, or an array of two or three elements indexed as one text. Some values consist of
 *   stop words only and index nothing, which is a path of its own.
 * - **Register a field** on its first write, with a pivot drawn from several, so impacts differ between fields.
 * - **Retire a field**: its key stops resolving and its postings stay where they are. The next write to the key
 *   registers a fresh field under a new id.
 * - **Remove through a retired key**: removing a retired field's value through its key must change nothing, because
 *   the key no longer resolves to the field.
 *
 * Not thread-safe: one model drives one index from one thread.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FulltextIndexModel {
	/**
	 * Words the generated values are built from: Czech declension variants the Czech analyzer folds together, stop
	 * words, and diacritics. The vocabulary is small on purpose, so the common terms collect long posting lists that
	 * switch the bucket representation as they grow and shrink.
	 */
	private static final String[] VOCABULARY = {
		"Praha", "Brno", "Ostrava", "kolo", "kola", "kolem", "horské", "horský", "elektrické", "město",
		"městem", "hrad", "hradu", "řeka", "řeky", "most", "mostu", "a", "na", "pro", "žlutý", "kůň"
	};
	/**
	 * The pivots a newly registered field draws from: shorter and longer than the default, so the same value gets
	 * different impacts in different fields.
	 */
	private static final double[] PIVOTS = {6.0, FulltextIndex.DEFAULT_LENGTH_PIVOT, 80.0};
	/**
	 * One step in this many retires a field, and one more removes a value through a retired key.
	 */
	private static final int RARE_STEP_ONE_IN = 40;

	/**
	 * The analyzer of the index the model drives; the expected postings are derived through it.
	 */
	@Nonnull private final FulltextAnalyzer analyzer;
	/**
	 * The field keys the steps draw from.
	 */
	@Nonnull private final FulltextFieldKey[] keys;
	/**
	 * The primary keys the steps draw from, ascending - also the keys whose lengths the content dumps read.
	 */
	@Nonnull private final int[] primaryKeys;
	/**
	 * The modelled fields, indexed by the id the index assigned them.
	 */
	@Nonnull private final List<ModelField> fields;

	/**
	 * Creates an empty model, the counterpart of an empty index.
	 *
	 * @param analyzer    the analyzer of the index the model drives
	 * @param keys        the field keys the steps draw from
	 * @param primaryKeys the primary keys the steps draw from
	 */
	public FulltextIndexModel(
		@Nonnull FulltextAnalyzer analyzer,
		@Nonnull FulltextFieldKey[] keys,
		@Nonnull int[] primaryKeys
	) {
		this.analyzer = analyzer;
		this.keys = keys.clone();
		this.primaryKeys = primaryKeys.clone();
		Arrays.sort(this.primaryKeys);
		this.fields = new ArrayList<>(16);
	}

	/**
	 * Creates a deep copy of a model: the fields and their value maps are copied, the immutable values are shared.
	 *
	 * @param source the model to copy
	 */
	private FulltextIndexModel(@Nonnull FulltextIndexModel source) {
		this.analyzer = source.analyzer;
		this.keys = source.keys;
		this.primaryKeys = source.primaryKeys;
		this.fields = new ArrayList<>(source.fields.size() + 4);
		for (final ModelField field : source.fields) {
			this.fields.add(field.copy());
		}
	}

	/**
	 * Returns an independent copy, so a step sequence applied to the copy leaves this model as it was.
	 *
	 * @return the copy
	 */
	@Nonnull
	public FulltextIndexModel copy() {
		return new FulltextIndexModel(this);
	}

	/**
	 * Returns the number of fields the model registered, retired ones included - the field count the index must
	 * report.
	 *
	 * @return the field count
	 */
	public int getFieldCount() {
		return this.fields.size();
	}

	/**
	 * Applies random steps to the index and records each of them in the model.
	 *
	 * @param index         the index the model is paired with
	 * @param random        the source of randomness
	 * @param count         how many steps to apply
	 * @param maxFieldCount no field is retired once the model holds this many, which bounds how many fields a long
	 *                      chain of generations accumulates
	 * @param log           receives a short description of every step, for the failure report
	 */
	public void applyRandomOperations(
		@Nonnull FulltextIndex index,
		@Nonnull Random random,
		int count,
		int maxFieldCount,
		@Nonnull StringBuilder log
	) {
		for (int i = 0; i < count; i++) {
			applyRandomOperation(index, random, maxFieldCount, log);
		}
	}

	/**
	 * Writes the modelled state into an empty index: every field registered in id order with its pivot, its values
	 * indexed, and retired when the model says so. The index then holds what {@link #expectedContent()} renders.
	 *
	 * @param index an empty index over the model's analyzer
	 */
	public void replayInto(@Nonnull FulltextIndex index) {
		for (int fieldId = 0; fieldId < this.fields.size(); fieldId++) {
			final ModelField field = this.fields.get(fieldId);
			assertEquals(fieldId, index.getOrAssignFieldId(field.key, field.pivot), "replayed field id");
			for (final Map.Entry<Integer, ModelValue> entry : field.values.entrySet()) {
				addValue(index, field.key, entry.getKey(), entry.getValue().elements());
			}
			if (field.retired) {
				assertTrue(index.retireField(field.key), "replayed retirement of " + field.key);
			}
		}
	}

	/**
	 * Renders what the paired index must hold, derived from the modelled values alone.
	 *
	 * @return the expected content, in the format of {@link #contentOf(FulltextIndex)}
	 */
	@Nonnull
	public String expectedContent() {
		final StringBuilder content = new StringBuilder(8_192);
		int termCount = 0;
		for (final ModelField field : this.fields) {
			content.append(field.key).append('@').append(field.pivot).append(field.retired ? " retired\n" : "\n");
			final TreeMap<String, TreeMap<Integer, Integer>> postings = new TreeMap<>();
			final TreeMap<Integer, Integer> lengths = new TreeMap<>();
			for (final Map.Entry<Integer, ModelValue> entry : field.values.entrySet()) {
				final ModelValue value = entry.getValue();
				if (value.length() == 0) {
					// a value without tokens is indexed as nothing - no posting and no length
					continue;
				}
				lengths.put(entry.getKey(), FieldLengthTable.encode(value.length()));
				for (final Map.Entry<String, Integer> term : value.termFrequencies().entrySet()) {
					postings.computeIfAbsent(term.getKey(), t -> new TreeMap<>()).put(
						entry.getKey(), FulltextIndex.computeImpact(term.getValue(), value.length(), field.pivot)
					);
				}
			}
			for (final Map.Entry<String, TreeMap<Integer, Integer>> term : postings.entrySet()) {
				content.append(term.getKey()).append(':');
				for (final Map.Entry<Integer, Integer> posting : term.getValue().entrySet()) {
					content.append(' ').append(posting.getKey()).append('/').append(posting.getValue());
				}
				content.append('\n');
			}
			content.append("lengths ").append(lengths.size()).append(':');
			for (final Map.Entry<Integer, Integer> length : lengths.entrySet()) {
				content.append(' ').append(length.getKey()).append('/').append(length.getValue());
			}
			content.append('\n');
			termCount += postings.size();
		}
		for (final FulltextFieldKey key : this.keys) {
			content.append(key).append(" -> ").append(activeFieldId(key)).append('\n');
		}
		return content.append("terms ").append(termCount).toString();
	}

	/**
	 * Renders what an index holds over the model's keys and primary keys - see
	 * {@link #contentOf(FulltextIndex, FulltextFieldKey[], int[])}.
	 *
	 * @param index the index to read
	 * @return the content, comparable with {@link #expectedContent()}
	 */
	@Nonnull
	public String contentOf(@Nonnull FulltextIndex index) {
		return contentOf(index, this.keys, this.primaryKeys);
	}

	/**
	 * Renders what an index holds, as the caller's transaction sees it: every field with its pivot and retirement,
	 * every term with its postings and their impacts in posting order, the length of every passed primary key, what
	 * every passed key resolves to, and the dictionary size.
	 *
	 * Each term is read twice, through the walk - which hands out the leaf's own posting list and impacts - and
	 * through a descent, and the two must agree. That is where an impact that drifted from its posting shows up even
	 * when both read paths agree with each other about the posting list.
	 *
	 * @param index       the index to read
	 * @param keys        the keys whose resolution to render
	 * @param primaryKeys the primary keys whose lengths to render, ascending
	 * @return the content, comparable with `equals`
	 */
	@Nonnull
	public static String contentOf(
		@Nonnull FulltextIndex index,
		@Nonnull FulltextFieldKey[] keys,
		@Nonnull int[] primaryKeys
	) {
		final StringBuilder content = new StringBuilder(8_192);
		for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
			content.append(index.getFieldKey(fieldId)).append('@').append(index.getLengthPivot(fieldId))
				.append(index.isFieldRetired(fieldId) ? " retired\n" : "\n");
			final int theFieldId = fieldId;
			index.forEachTerm(fieldId, "", (term, postings, impacts) -> {
				final int[] walked = postings.getArray();
				final byte[] walkedImpacts = impacts.toArray();
				assertArrayEquals(
					index.getPostings(theFieldId, term).getArray(), walked, "walked postings of `" + term + "`"
				);
				assertArrayEquals(
					index.getImpacts(theFieldId, term), walkedImpacts, "walked impacts of `" + term + "`"
				);
				assertEquals(walked.length, walkedImpacts.length, "impacts misaligned with the postings of " + term);
				content.append(term).append(':');
				for (int i = 0; i < walked.length; i++) {
					content.append(' ').append(walked[i]).append('/').append(Byte.toUnsignedInt(walkedImpacts[i]));
				}
				content.append('\n');
				return true;
			});
			final FieldLengthTable lengths = index.getFieldLengths(fieldId);
			content.append("lengths ").append(lengths.size()).append(':');
			for (final int primaryKey : primaryKeys) {
				final int encoded = lengths.getEncoded(primaryKey);
				if (encoded != 0) {
					content.append(' ').append(primaryKey).append('/').append(encoded);
				}
			}
			content.append('\n');
		}
		for (final FulltextFieldKey key : keys) {
			content.append(key).append(" -> ").append(index.getFieldId(key)).append('\n');
		}
		return content.append("terms ").append(index.getTermCount()).toString();
	}

	/**
	 * Draws one step, applies it to the index and records it - see the class documentation for the steps.
	 *
	 * @param index         the index the model is paired with
	 * @param random        the source of randomness
	 * @param maxFieldCount no field is retired once the model holds this many
	 * @param log           receives a short description of the step
	 */
	private void applyRandomOperation(
		@Nonnull FulltextIndex index,
		@Nonnull Random random,
		int maxFieldCount,
		@Nonnull StringBuilder log
	) {
		final int dice = random.nextInt(RARE_STEP_ONE_IN);
		if (dice == 0 && this.fields.size() < maxFieldCount && retireRandomField(index, random, log)) {
			return;
		}
		if (dice == 1 && removeThroughRetiredKey(index, random, log)) {
			return;
		}
		final FulltextFieldKey key = this.keys[random.nextInt(this.keys.length)];
		final int primaryKey = this.primaryKeys[random.nextInt(this.primaryKeys.length)];
		final int fieldId = activeFieldId(key);
		ModelField field = fieldId < 0 ? null : this.fields.get(fieldId);
		final ModelValue current = field == null ? null : field.values.get(primaryKey);
		if (current != null) {
			final String[] elements = current.elements().clone();
			// an array is removed whole, and in any element order
			Collections.shuffle(Arrays.asList(elements), random);
			log.append("-").append(key).append('#').append(primaryKey).append(' ');
			removeValue(index, key, primaryKey, elements);
			field.values.remove(primaryKey);
			if (random.nextBoolean()) {
				return;
			}
		}
		if (field == null) {
			final double pivot = PIVOTS[random.nextInt(PIVOTS.length)];
			assertEquals(this.fields.size(), index.getOrAssignFieldId(key, pivot), "the id assigned to " + key);
			field = new ModelField(key, pivot);
			this.fields.add(field);
			log.append("new ").append(key).append('@').append(pivot).append(' ');
		}
		final String[] elements = randomValue(random);
		log.append("+").append(key).append('#').append(primaryKey).append('=')
			.append(Arrays.toString(elements)).append(' ');
		addValue(index, key, primaryKey, elements);
		field.values.put(primaryKey, analyze(elements));
	}

	/**
	 * Retires a random field still in service.
	 *
	 * @param index  the index the model is paired with
	 * @param random the source of randomness
	 * @param log    receives a short description of the step
	 * @return false when no field is in service, so nothing was done
	 */
	private boolean retireRandomField(
		@Nonnull FulltextIndex index,
		@Nonnull Random random,
		@Nonnull StringBuilder log
	) {
		final List<ModelField> inService = new ArrayList<>(this.fields.size());
		for (final ModelField field : this.fields) {
			if (!field.retired) {
				inService.add(field);
			}
		}
		if (inService.isEmpty()) {
			return false;
		}
		final ModelField field = inService.get(random.nextInt(inService.size()));
		log.append("retire ").append(field.key).append(' ');
		assertTrue(index.retireField(field.key), "retirement of " + field.key);
		field.retired = true;
		return true;
	}

	/**
	 * Removes a retired field's value through the field's key while the key resolves to no field, which must change
	 * nothing: a retired field takes no writes, removals included.
	 *
	 * @param index  the index the model is paired with
	 * @param random the source of randomness
	 * @param log    receives a short description of the step
	 * @return false when no retired field qualifies, so nothing was done
	 */
	private boolean removeThroughRetiredKey(
		@Nonnull FulltextIndex index,
		@Nonnull Random random,
		@Nonnull StringBuilder log
	) {
		final List<ModelField> candidates = new ArrayList<>(this.fields.size());
		for (final ModelField field : this.fields) {
			if (field.retired && !field.values.isEmpty() && activeFieldId(field.key) < 0) {
				candidates.add(field);
			}
		}
		if (candidates.isEmpty()) {
			return false;
		}
		final ModelField field = candidates.get(random.nextInt(candidates.size()));
		final Map.Entry<Integer, ModelValue> victim = field.values.firstEntry();
		log.append("-retired ").append(field.key).append('#').append(victim.getKey()).append(' ');
		removeValue(index, field.key, victim.getKey(), victim.getValue().elements());
		return true;
	}

	/**
	 * Returns the id of the field a key resolves to.
	 *
	 * @param key the field key
	 * @return the id of the key's field in service, or {@link FulltextIndex#UNKNOWN_FIELD_ID}
	 */
	private int activeFieldId(@Nonnull FulltextFieldKey key) {
		for (int fieldId = 0; fieldId < this.fields.size(); fieldId++) {
			final ModelField field = this.fields.get(fieldId);
			if (!field.retired && field.key.equals(key)) {
				return fieldId;
			}
		}
		return FulltextIndex.UNKNOWN_FIELD_ID;
	}

	/**
	 * Analyzes a value the way the index does: the term frequencies and the positions of all elements are summed.
	 *
	 * @param elements the elements of the value
	 * @return the analyzed value
	 */
	@Nonnull
	private ModelValue analyze(@Nonnull String[] elements) {
		final Map<String, Integer> termFrequencies = CollectionUtils.createHashMap(16);
		int length = 0;
		for (final String element : elements) {
			for (final AnalyzedTerm term : this.analyzer.getTerms(element)) {
				termFrequencies.merge(term.term(), 1, Integer::sum);
				if (term.positionIncrement() > 0) {
					length++;
				}
			}
		}
		return new ModelValue(elements, termFrequencies, length);
	}

	/**
	 * Generates a value: one text in three cases out of four, otherwise an array of two or three elements.
	 *
	 * @param random the source of randomness
	 * @return the elements of the value
	 */
	@Nonnull
	private static String[] randomValue(@Nonnull Random random) {
		final int elementCount = random.nextInt(4) == 0 ? 2 + random.nextInt(2) : 1;
		final String[] elements = new String[elementCount];
		for (int e = 0; e < elementCount; e++) {
			final int words = 1 + random.nextInt(elementCount == 1 ? 12 : 5);
			final StringBuilder element = new StringBuilder(words * 8);
			for (int w = 0; w < words; w++) {
				if (w > 0) {
					element.append(' ');
				}
				element.append(VOCABULARY[random.nextInt(VOCABULARY.length)]);
			}
			elements[e] = element.toString();
		}
		return elements;
	}

	/**
	 * Indexes a value through the overload its shape calls for.
	 *
	 * @param index      the index
	 * @param key        the field key
	 * @param primaryKey the entity
	 * @param elements   the elements of the value
	 */
	private static void addValue(
		@Nonnull FulltextIndex index,
		@Nonnull FulltextFieldKey key,
		int primaryKey,
		@Nonnull String[] elements
	) {
		if (elements.length == 1) {
			index.addValue(key, primaryKey, elements[0]);
		} else {
			index.addValue(key, primaryKey, elements);
		}
	}

	/**
	 * Removes a value through the overload its shape calls for.
	 *
	 * @param index      the index
	 * @param key        the field key
	 * @param primaryKey the entity
	 * @param elements   the elements of the value that was indexed
	 */
	private static void removeValue(
		@Nonnull FulltextIndex index,
		@Nonnull FulltextFieldKey key,
		int primaryKey,
		@Nonnull String[] elements
	) {
		if (elements.length == 1) {
			index.removeValue(key, primaryKey, elements[0]);
		} else {
			index.removeValue(key, primaryKey, elements);
		}
	}

	/**
	 * A modelled field.
	 */
	private static final class ModelField {
		/**
		 * The field's key.
		 */
		@Nonnull private final FulltextFieldKey key;
		/**
		 * The pivot the field was registered with.
		 */
		private final double pivot;
		/**
		 * The values every entity has indexed in the field, by primary key; frozen once the field is retired.
		 */
		@Nonnull private final TreeMap<Integer, ModelValue> values;
		/**
		 * Whether the field was retired.
		 */
		private boolean retired;

		/**
		 * Creates a field in service with no value.
		 *
		 * @param key   the field's key
		 * @param pivot the field's pivot
		 */
		ModelField(@Nonnull FulltextFieldKey key, double pivot) {
			this(key, pivot, new TreeMap<>(), false);
		}

		/**
		 * Creates a field.
		 *
		 * @param key     the field's key
		 * @param pivot   the field's pivot
		 * @param values  the field's values
		 * @param retired whether the field was retired
		 */
		private ModelField(
			@Nonnull FulltextFieldKey key,
			double pivot,
			@Nonnull TreeMap<Integer, ModelValue> values,
			boolean retired
		) {
			this.key = key;
			this.pivot = pivot;
			this.values = values;
			this.retired = retired;
		}

		/**
		 * Returns a copy with its own value map.
		 *
		 * @return the copy
		 */
		@Nonnull
		ModelField copy() {
			return new ModelField(this.key, this.pivot, new TreeMap<>(this.values), this.retired);
		}
	}

	/**
	 * A modelled value with its analysis, computed once when the value is written.
	 *
	 * @param elements        the elements of the value
	 * @param termFrequencies every distinct term with its frequency over all elements
	 * @param length          the number of positions over all elements, the length the index records
	 */
	private record ModelValue(
		@Nonnull String[] elements,
		@Nonnull Map<String, Integer> termFrequencies,
		int length
	) {}

}
