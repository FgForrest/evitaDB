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

package io.evitadb.core.query.filter.translator.fulltext;

import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextTermExactness;
import io.evitadb.index.fulltext.typo.FulltextDictionaryWalker;
import io.evitadb.index.fulltext.typo.FulltextTypoParameters;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expands a word within a profile group. Exact terms are uncapped. Each other leg keeps at most fifty hits per
 * field, unions those survivors and selects fifty stems by distance, maximum field frequency and term. A global
 * winner must survive in a field attaining its maximum frequency: every term ahead of it in that field is also
 * ahead globally. Missing field views are fetched only for global winners.
 *
 * An instance belongs to one query, which reuses its walkers across fields, groups and scopes.
 */
@NotThreadSafe
final class FulltextTermExpansion {
	/** Interim per-word, per-group term limit; exact lookups never consume it. */
	//TODO JNO change it at the end of #258
	private static final int TERMS_PER_LEG = 50;
	/** Distance ascending, maximum field frequency descending, then term ascending. */
	private static final Comparator<Candidate> LEG_ORDER = (left, right) -> FulltextTermHeap.compare(
		left.distance, left.frequency, left.term, right.distance, right.frequency, right.term
	);
	/** Submitted-search policy fixed for this query. */
	@Nonnull private final FulltextTypoParameters parameters;
	/** Automata built once per variant, edit band and end mode. */
	@Nonnull private final Map<WalkKey, FulltextDictionaryWalker> walkers = CollectionUtils.createHashMap(8);

	/**
	 * Creates query-local expansion state.
	 * @param parameters submitted-search policy
	 */
	FulltextTermExpansion(@Nonnull FulltextTypoParameters parameters) {
		this.parameters = parameters;
	}

	/**
	 * Expands a word into its profile's current field generations.
	 * @param index the scope's locale dictionary
	 * @param fieldIds current fields belonging to this profile
	 * @param variants the word's analyzed alternatives
	 * @param fragments normalized raw fragments prepared around stop removal
	 * @param unfinished whether the actual final raw word remains unclosed
	 * @return field-specific expansions with their best distance and exactness
	 */
	@Nonnull
	List<ExpandedTerm> expand(
		@Nonnull FulltextIndex index, @Nonnull int[] fieldIds, @Nonnull String[] variants,
		@Nonnull String[] fragments, boolean unfinished
	) {
		final Map<String, Candidate> exact = CollectionUtils.createLinkedHashMap(variants.length);
		for (final String variant : variants) {
			for (final int fieldId : fieldIds) {
				final ExpandedTerm term = lookUpTerm(index, fieldId, variant);
				if (term != null) {
					offer(exact, fieldId, term.term(), term.postings(), term.impacts(), 0,
						FulltextTermExactness.EXACT, term.postings().size());
				}
			}
		}
		final boolean prefixAllowed = expandsUnfinishedWord(fragments, unfinished);
		final boolean openEnd = prefixAllowed && fragmentLength(fragments) >= this.parameters.openEndedStartLength();
		final Map<String, Candidate> prefix = prefixAllowed
			? collectPrefixes(index, fieldIds, variants, fragments, exact) : CollectionUtils.createHashMap(0);
		final Map<String, Candidate> typo = collectTypos(index, fieldIds, variants, exact, prefix, openEnd);
		final List<ExpandedTerm> result = new ArrayList<>(viewCount(exact) + viewCount(prefix) + viewCount(typo));
		append(result, exact);
		append(result, prefix);
		append(result, typo);
		return result;
	}

	/**
	 * Collects prefix hits with bounded per-field heaps before selecting global survivors.
	 * @param index locale dictionary
	 * @param fieldIds profile fields
	 * @param variants analyzed alternatives whose prefixes also participate
	 * @param fragments normalized raw fragments
	 * @param exact uncapped exact matches excluded from this leg
	 * @return at most fifty stems, including their views in every searched field holding them
	 */
	@Nonnull
	private Map<String, Candidate> collectPrefixes(
		@Nonnull FulltextIndex index, @Nonnull int[] fieldIds, @Nonnull String[] variants,
		@Nonnull String[] fragments, @Nonnull Map<String, Candidate> exact
	) {
		final Set<String> prefixes = CollectionUtils.createHashSet(fragments.length + variants.length);
		for (final String fragment : fragments) {
			if (!fragment.isEmpty()) {
				prefixes.add(fragment);
			}
		}
		for (final String variant : variants) {
			if (!variant.isEmpty()) {
				prefixes.add(variant);
			}
		}
		final FulltextTermHeap[] fields = newHeaps(fieldIds.length);
		for (int f = 0; f < fieldIds.length; f++) {
			final FulltextTermHeap heap = fields[f];
			for (final String fragment : prefixes) {
				index.forEachTerm(fieldIds[f], fragment, (term, postings, impacts) -> {
					if (!exact.containsKey(term)) {
						heap.offer(term, postings, impacts, 0, postings.size());
					}
					return true;
				});
			}
		}
		return select(index, fieldIds, fields, FulltextTermExactness.PREFIX);
	}

	/**
	 * Walks complete distance bands across all variants and fields. A term is skipped when the exact leg or the
	 * selected prefix leg already holds it, or when an earlier band collected it.
	 * @param index locale dictionary
	 * @param fieldIds profile fields
	 * @param variants analyzed alternatives, each with its own edit budget
	 * @param exact uncapped exact matches
	 * @param prefix selected prefix matches; discarded prefixes may compete through a positive-distance path
	 * @param openEnd whether a matching prefix accepts the remaining suffix
	 * @return at most fifty fuzzy stems with all their field views
	 */
	@Nonnull
	private Map<String, Candidate> collectTypos(
		@Nonnull FulltextIndex index, @Nonnull int[] fieldIds, @Nonnull String[] variants,
		@Nonnull Map<String, Candidate> exact, @Nonnull Map<String, Candidate> prefix, boolean openEnd
	) {
		boolean hasTypos = false;
		for (final String variant : variants) {
			if (this.parameters.maxEdits(variant) > 0) {
				hasTypos = true;
				break;
			}
		}
		if (!hasTypos) {
			return CollectionUtils.createHashMap(0);
		}
		final FulltextTermHeap[] fields = newHeaps(fieldIds.length);
		Set<String> earlierTerms = CollectionUtils.createHashSet(0);
		for (int edits = 1; edits <= 2; edits++) {
			final int band = edits;
			final Set<String> previousBands = earlierTerms;
			for (final String variant : variants) {
				if (this.parameters.maxEdits(variant) < edits) {
					continue;
				}
				final FulltextDictionaryWalker walker = walker(variant, edits, openEnd);
				for (int f = 0; f < fieldIds.length; f++) {
					final FulltextTermHeap heap = fields[f];
					index.forEachTerm(fieldIds[f], walker, (term, postings, impacts, distance, frequency) -> {
						if (distance == band && !previousBands.contains(term) &&
							!exact.containsKey(term) && !prefix.containsKey(term)) {
							heap.offer(term, postings, impacts, distance, frequency);
						}
						return true;
					});
				}
			}
			earlierTerms = collectedTerms(fields);
			if (earlierTerms.size() == TERMS_PER_LEG) {
				break;
			}
		}
		return select(index, fieldIds, fields, FulltextTermExactness.FUZZY);
	}

	/**
	 * Retrieves or builds the variant's automaton once for this query.
	 * @param variant analyzed alternative
	 * @param edits current edit band
	 * @param openEnd acceptance of arbitrary suffixes
	 * @return reusable, non-reentrant walker
	 */
	@Nonnull
	private FulltextDictionaryWalker walker(@Nonnull String variant, int edits, boolean openEnd) {
		final WalkKey key = new WalkKey(variant, edits, openEnd);
		final FulltextDictionaryWalker existing = this.walkers.get(key);
		if (existing != null) {
			return existing;
		}
		final FulltextDictionaryWalker walker = new FulltextDictionaryWalker(variant, edits,
			this.parameters.nonFuzzyPrefix(), this.parameters.transpositions(), openEnd);
		this.walkers.put(key, walker);
		return walker;
	}

	/**
	 * Starts one leg's bounded field collectors.
	 * @param count number of searched fields
	 * @return one empty heap per field
	 */
	@Nonnull
	private static FulltextTermHeap[] newHeaps(int count) {
		final FulltextTermHeap[] fields = new FulltextTermHeap[count];
		for (int i = 0; i < count; i++) {
			fields[i] = new FulltextTermHeap(TERMS_PER_LEG);
		}
		return fields;
	}

	/**
	 * Collects earlier-band terms to skip them in wider walks; reaching the cap ends the cascade.
	 * @param fields bounded field collectors
	 * @return distinct earlier-band stems, stopping once the global cap is reached
	 */
	@Nonnull
	private static Set<String> collectedTerms(@Nonnull FulltextTermHeap[] fields) {
		final Set<String> terms = CollectionUtils.createHashSet(TERMS_PER_LEG);
		for (final FulltextTermHeap field : fields) {
			for (int i = 0; i < field.size(); i++) {
				terms.add(field.get(i).term());
				if (terms.size() == TERMS_PER_LEG) {
					return terms;
				}
			}
		}
		return terms;
	}

	/**
	 * Unions at most fields-times-cap hits, selects the global top terms and reacquires only missing winner views.
	 * @param index locale dictionary
	 * @param fieldIds searched field generations
	 * @param fields bounded field survivors
	 * @param exactness this leg's matching class
	 * @return selected terms in deterministic leg order, with every available field view
	 */
	@Nonnull
	private static Map<String, Candidate> select(
		@Nonnull FulltextIndex index, @Nonnull int[] fieldIds, @Nonnull FulltextTermHeap[] fields,
		@Nonnull FulltextTermExactness exactness
	) {
		final Map<String, Candidate> union = CollectionUtils.createHashMap(fieldIds.length * TERMS_PER_LEG);
		for (int f = 0; f < fields.length; f++) {
			final FulltextTermHeap heap = fields[f];
			for (int i = 0; i < heap.size(); i++) {
				final FulltextTermHeap.Entry hit = heap.get(i);
				offer(union, fieldIds[f], hit.term(), hit.postings(), hit.impacts(), hit.distance(), exactness,
					hit.frequency());
			}
		}
		final Candidate[] ordered = union.values().toArray(Candidate[]::new);
		Arrays.sort(ordered, LEG_ORDER);
		final int count = Math.min(TERMS_PER_LEG, ordered.length);
		final Map<String, Candidate> selected = CollectionUtils.createLinkedHashMap(count);
		for (int i = 0; i < count; i++) {
			final Candidate candidate = ordered[i];
			for (final int fieldId : fieldIds) {
				if (!candidate.hasField(fieldId)) {
					final ExpandedTerm found = lookUpTerm(index, fieldId, candidate.term);
					if (found != null) {
						offer(union, fieldId, candidate.term, found.postings(), found.impacts(), candidate.distance,
							candidate.exactness, found.postings().size());
					}
				}
			}
			selected.put(candidate.term, candidate);
		}
		return selected;
	}

	/**
	 * Tests the submitted input-mode guards before expanding the final word.
	 * @param fragments normalized raw fragments
	 * @param unfinished whether the actual final word is unclosed
	 * @return whether unfinished-word expansion is enabled at this length
	 */
	boolean expandsUnfinishedWord(@Nonnull String[] fragments, boolean unfinished) {
		return unfinished && this.parameters.prefixLeg() &&
			fragmentLength(fragments) >= this.parameters.minFragmentLength();
	}

	/**
	 * Counts normalized fragment code points before stemming.
	 * @param fragments raw fragments emitted by normalization
	 * @return greatest fragment length, or zero
	 */
	private static int fragmentLength(@Nonnull String[] fragments) {
		int length = 0;
		for (final String fragment : fragments) {
			length = Math.max(length, fragment.codePointCount(0, fragment.length()));
		}
		return length;
	}

	/**
	 * Reads exact field views without copying postings or impacts.
	 * @param index locale dictionary
	 * @param fieldId current field generation
	 * @param term exact analyzed term
	 * @return borrowed dictionary views, or null when the term is absent or has no postings
	 */
	@Nullable
	static ExpandedTerm lookUpTerm(@Nonnull FulltextIndex index, int fieldId, @Nonnull String term) {
		final ExpandedTerm[] found = new ExpandedTerm[1];
		index.forEachTerm(fieldId, term, (visited, postings, impacts) -> {
			if (visited.equals(term) && !postings.isEmpty()) {
				found[0] = new ExpandedTerm(fieldId, visited, postings, impacts, 0);
			}
			return false;
		});
		return found[0];
	}

	/**
	 * Merges a retained field view into a bounded union or the uncapped exact leg.
	 * @param candidates terms already collected
	 * @param fieldId hit field
	 * @param term bare stem
	 * @param postings borrowed posting view
	 * @param impacts impacts aligned with postings
	 * @param distance hit distance
	 * @param exactness matching class of the collecting leg
	 * @param frequency document frequency in this field
	 * @throws GenericEvitaInternalError when the term was already collected under another matching class
	 */
	private static void offer(
		@Nonnull Map<String, Candidate> candidates, int fieldId, @Nonnull String term,
		@Nonnull Bitmap postings, @Nonnull ImpactView impacts, int distance,
		@Nonnull FulltextTermExactness exactness, int frequency
	) {
		final Candidate candidate;
		final Candidate existing = candidates.get(term);
		if (existing == null) {
			candidate = new Candidate(term, distance, exactness);
			candidates.put(term, candidate);
		} else {
			candidate = existing;
		}
		if (exactness != candidate.exactness) {
			throw new GenericEvitaInternalError("Fulltext term `" + term + "` was offered as " +
				candidate.exactness + " and " + exactness + " in one leg.");
		}
		candidate.distance = Math.min(candidate.distance, distance);
		candidate.frequency = Math.max(candidate.frequency, frequency);
		if (!candidate.hasField(fieldId)) {
			candidate.fields.add(new FieldTerm(fieldId, postings, impacts));
		}
	}

	/**
	 * Counts selected field views before allocating the result array list.
	 * @param candidates selected terms
	 * @return exact number of result entries
	 */
	private static int viewCount(@Nonnull Map<String, Candidate> candidates) {
		int count = 0;
		for (final Candidate candidate : candidates.values()) {
			count += candidate.fields.size();
		}
		return count;
	}

	/**
	 * Emits selected borrowed field views with the term's merged match metadata.
	 * @param result destination in leg order
	 * @param candidates selected stems
	 */
	private static void append(@Nonnull List<ExpandedTerm> result, @Nonnull Map<String, Candidate> candidates) {
		for (final Candidate candidate : candidates.values()) {
			for (final FieldTerm field : candidate.fields) {
				result.add(new ExpandedTerm(field.fieldId(), candidate.term, field.postings(), field.impacts(),
					candidate.distance, candidate.exactness));
			}
		}
	}

	/**
	 * Query-local automaton identity; prefix and transposition policy are fixed on the enclosing instance.
	 * @param term analyzed alternative
	 * @param edits accepted distance band
	 * @param openEnd whether a matching prefix accepts its remaining suffix
	 */
	private record WalkKey(@Nonnull String term, int edits, boolean openEnd) { }

	/**
	 * Borrowed views in one selected field.
	 * @param fieldId current field generation
	 * @param postings primary keys containing the term
	 * @param impacts aligned impact bytes
	 */
	private record FieldTerm(int fieldId, @Nonnull Bitmap postings, @Nonnull ImpactView impacts) { }

	/** A union candidate, bounded by the total number of retained field hits. */
	private static final class Candidate {
		/** Bare dictionary stem. */
		@Nonnull private final String term;
		/** Borrowed field views; total views across a union are bounded by field-count times cap. */
		@Nonnull private final List<FieldTerm> fields = new ArrayList<>(2);
		/** Smallest distance across variants. */
		private int distance;
		/** Matching class of the leg that collected it. */
		@Nonnull private final FulltextTermExactness exactness;
		/** Maximum document frequency over fields. */
		private int frequency;

		/**
		 * Starts a union candidate.
		 * @param term bare dictionary stem
		 * @param distance first hit distance
		 * @param exactness first matching class
		 */
		private Candidate(@Nonnull String term, int distance, @Nonnull FulltextTermExactness exactness) {
			this.term = term;
			this.distance = distance;
			this.exactness = exactness;
		}

		/**
		 * Checks whether a retained view already covers a field.
		 * @param fieldId searched field generation
		 * @return true when this candidate holds that field's dictionary view
		 */
		private boolean hasField(int fieldId) {
			for (final FieldTerm field : this.fields) {
				if (field.fieldId() == fieldId) {
					return true;
				}
			}
			return false;
		}
	}
}
