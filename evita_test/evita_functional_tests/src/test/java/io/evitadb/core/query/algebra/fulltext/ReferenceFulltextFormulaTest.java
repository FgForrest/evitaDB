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

import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.index.fulltext.FulltextTermExactness;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.MembershipForm;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.RowValues;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ValueMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static io.evitadb.index.fulltext.FulltextIndexTestSupport.exactAnalyzer;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.registerExactField;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Symbolic combination over real reference dictionaries answers exactly what the raw rows hold. */
@DisplayName("Reference fulltext symbolic combination")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
class ReferenceFulltextFormulaTest {
	private static final FulltextFieldKey FIRST = FulltextFieldKey.referenceAttribute("ref", "first");
	private static final FulltextFieldKey SECOND = FulltextFieldKey.referenceAttribute("ref", "second");

	@Nested
	@DisplayName("Matching within one row")
	class Matching {

		/** Generated rows over two fields and two signed targets, checked against a model of the raw values. */
		@Test
		@DisplayName("Matches exactly the owners whose row holds every word in some field")
		void shouldMatchOwnersWhoseRowHoldsEveryWord() {
			final int[] targets = {0, -10};
			final String[] pool = {"red", "blue", "green", "red blue", "blue green", "gray", "red gray"};
			final Random random = new Random(7L);
			final FulltextIndex index = new FulltextIndex();
			final int[] fields = {registerExactField(index, FIRST, 25.0), registerExactField(index, SECOND, 25.0)};
			// owner -> target -> raw values per field; an absent target means the owner has no row there
			final Map<Integer, Map<Integer, List<Set<String>>>> rows = new TreeMap<>();
			for (int owner = 1; owner <= 30; owner++) {
				final Map<Integer, List<Set<String>>> ownerRows = new TreeMap<>();
				for (final int target : targets) {
					// the first owner holds a row everywhere, so its values start as ALL and later rows demote them
					if (owner == 1 || random.nextInt(4) > 0) {
						final List<Set<String>> fieldValues = new ArrayList<>(2);
						for (int field = 0; field < fields.length; field++) {
							final Set<String> values = new HashSet<>();
							for (final String value : pool) {
								if (random.nextInt(4) == 0) {
									values.add(value);
								}
							}
							if (target == 0 && field == 0) {
								// every row of the target carries this value, which keeps it in the ALL form
								values.add("red gray");
							}
							fieldValues.add(values);
						}
						ownerRows.put(target, fieldValues);
					}
				}
				rows.put(owner, ownerRows);
				for (int field = 0; field < fields.length; field++) {
					final List<RowValues> after = new ArrayList<>(ownerRows.size());
					for (final Map.Entry<Integer, List<Set<String>>> row : ownerRows.entrySet()) {
						after.add(new RowValues(row.getKey(), row.getValue().get(field)));
					}
					write(index, fields[field], owner, List.of(), after);
				}
			}
			final Set<MembershipForm> forms = EnumSet.noneOf(MembershipForm.class);
			for (final int field : fields) {
				for (final ValueMatch match : dictionary(index, field).lookup(Set.of("red", "blue", "green"))) {
					forms.add(match.form());
				}
			}
			assertEquals(EnumSet.allOf(MembershipForm.class), forms, "The rows must produce every membership form");

			final String[][] queries = {
				{"red"}, {"blue"}, {"green"}, {"gray"}, {"red", "blue"}, {"blue", "green"}, {"red", "gray"},
				{"red", "green", "blue"}
			};
			for (final String[] query : queries) {
				final ReferenceFulltextFormula formula = formula(index, words(fields, query));
				for (final int target : targets) {
					final BaseBitmap partition = new BaseBitmap();
					final BaseBitmap expected = new BaseBitmap();
					for (final Map.Entry<Integer, Map<Integer, List<Set<String>>>> entry : rows.entrySet()) {
						final int owner = entry.getKey();
						final List<Set<String>> row = entry.getValue().get(target);
						if (row != null) {
							partition.add(owner);
							final boolean match = holdsEveryWord(row, query);
							assertEquals(match, formula.matches(target, owner),
								() -> "owner " + owner + " at target " + target + " for " + Arrays.toString(query));
							if (match) {
								expected.add(owner);
							}
						}
					}
					assertArrayEquals(expected.getArray(), formula.resolve(target, partition).getArray(),
						() -> "target " + target + " for " + Arrays.toString(query));
				}
				assertFalse(formula.matches(99, 1), "A target no row references matches nothing");
				assertTrue(formula.resolve(99, new BaseBitmap(1)).isEmpty());
			}
		}

		/** One hundred matching values per field collapse into one combined entry without changing the owners. */
		@Test
		@DisplayName("Resolves many matching values of one field like a single one")
		void shouldResolveManyMatchingValuesLikeOne() {
			final FulltextIndex index = new FulltextIndex();
			final int first = registerExactField(index, FIRST, 25.0);
			final int second = registerExactField(index, SECOND, 25.0);
			final Set<String> values = new HashSet<>();
			for (int value = 1; value <= 100; value++) {
				values.add("word v" + value);
			}
			// owner 1 carries every value in the first field (EXCEPT for the owners written after it), owners 3 and 4
			// carry them in the second (HOLDERS); owner 2 carries none and owner 5 was never enrolled
			for (int owner = 1; owner <= 4; owner++) {
				write(index, first, owner, List.of(), List.of(new RowValues(-10, owner == 1 ? values : Set.of())));
				write(index, second, owner, List.of(), List.of(new RowValues(-10, owner >= 3 ? values : Set.of())));
			}
			assertEquals(MembershipForm.EXCEPT,
				dictionary(index, first).lookup(Set.of("word")).getFirst().form());
			assertEquals(MembershipForm.HOLDERS,
				dictionary(index, second).lookup(Set.of("word")).getFirst().form());
			final ReferenceFulltextFormula formula = formula(index, words(new int[]{first, second}, "word"));

			assertArrayEquals(new int[]{1, 3, 4}, formula.resolve(-10, new BaseBitmap(1, 2, 3, 4, 5)).getArray());
			for (int owner = 1; owner <= 5; owner++) {
				assertEquals(owner == 1 || owner == 3 || owner == 4, formula.matches(-10, owner), "owner " + owner);
			}
		}

		/** ALL dominates a field whatever position its value takes among the matching values. */
		@ParameterizedTest
		@ValueSource(ints = {0, 1, 2})
		@DisplayName("Lets ALL dominate wherever its value falls among the matching values")
		void shouldLetAllDominateInAnyValueOrder(int allPosition) {
			final FulltextIndex index = new FulltextIndex();
			final int field = registerExactField(index, FIRST, 25.0);
			final String[] values = {"word one", "word two", "word three"};
			// value ids follow first appearance and lookup returns them in id order: minting them on another target
			// first fixes the order in which the combination sees them
			final Set<String> minted = new HashSet<>();
			List<RowValues> mintingRow = List.of();
			for (final String value : values) {
				minted.add(value);
				final List<RowValues> grown = List.of(new RowValues(999, minted));
				write(index, field, 99, mintingRow, grown);
				mintingRow = grown;
			}
			final String all = values[allPosition];
			final String except = values[(allPosition + 1) % 3];
			final String holders = values[(allPosition + 2) % 3];
			write(index, field, 1, List.of(), List.of(new RowValues(-10, Set.of(all, except))));
			write(index, field, 2, List.of(), List.of(new RowValues(-10, Set.of(all))));
			write(index, field, 3, List.of(), List.of(new RowValues(-10, Set.of(all, holders))));
			final List<MembershipForm> forms = new ArrayList<>(3);
			for (final ValueMatch match : dictionary(index, field).lookup(Set.of("word"))) {
				if (match.targetPK() == -10) {
					forms.add(match.form());
				}
			}
			assertEquals(MembershipForm.ALL, forms.get(allPosition), "Fixture must place ALL at " + allPosition);
			assertTrue(forms.containsAll(EnumSet.allOf(MembershipForm.class)), forms::toString);
			final ReferenceFulltextFormula formula = formula(index, words(new int[]{field}, "word"));

			// owners 2 and 3 are EXCEPT exclusions of the second value, owner 4 is not enrolled
			assertArrayEquals(new int[]{1, 2, 3}, formula.resolve(-10, new BaseBitmap(1, 2, 3, 4)).getArray());
			for (int owner = 1; owner <= 4; owner++) {
				assertEquals(owner != 4, formula.matches(-10, owner), "owner " + owner);
			}
		}

		/** Words found at different targets of one owner never pool into a match. */
		@Test
		@DisplayName("Requires every word at the same target")
		void shouldRequireEveryWordAtTheSameTarget() {
			final FulltextIndex index = new FulltextIndex();
			final int field = registerExactField(index, FIRST, 25.0);
			write(index, field, 1, List.of(),
				List.of(new RowValues(0, Set.of("red")), new RowValues(-10, Set.of("blue"))));
			write(index, field, 2, List.of(), List.of(new RowValues(-10, Set.of("red blue"))));
			final ReferenceFulltextFormula formula = formula(index, words(new int[]{field}, "red", "blue"));

			assertTrue(formula.resolve(0, new BaseBitmap(1)).isEmpty());
			assertArrayEquals(new int[]{2}, formula.resolve(-10, new BaseBitmap(1, 2)).getArray());
			assertFalse(formula.matches(0, 1));
			assertFalse(formula.matches(-10, 1));
			assertTrue(formula.matches(-10, 2));
		}
	}

	@Nested
	@DisplayName("Lazy preparation")
	class Preparation {

		/**
		 * Mockito spies are the sole observer here: a read that does not happen changes no result, so only the
		 * recorded interactions show that construction reads nothing and a virtual check reads no enrollment bitmap.
		 */
		@Test
		@DisplayName("Reads no index at construction and no enrollment bitmap for a virtual row")
		void shouldNotReadIndexUntilFirstEvaluationAndNeverReadEnrollmentForVirtualChecks() {
			final FulltextIndex real = new FulltextIndex();
			final int field = registerExactField(real, FIRST, 25.0);
			write(real, field, 1, List.of(), List.of(new RowValues(-10, Set.of("word"))));
			final ReferenceFulltextValueIndex dictionary = spy(dictionary(real, field));
			final FulltextIndex index = spy(real);
			doReturn(dictionary).when(index).getReferenceValues(field);
			final ReferenceFulltextFormula formula = formula(index, words(new int[]{field}, "word"));
			verifyNoInteractions(index);

			assertTrue(formula.matches(-10, 1));
			verify(dictionary, never()).getEnrolledOwners(any());
			assertArrayEquals(new int[]{1}, formula.resolve(-10, new BaseBitmap(1, 2)).getArray());
			verify(dictionary).getEnrolledOwners(any());
		}

		/** A retained reference field must never silently become an empty match when its dictionary is missing. */
		@Test
		@DisplayName("Rejects a retained field without a reference dictionary lazily, naming the field")
		void shouldRejectMissingReferenceDictionary() {
			final FulltextIndex index = new FulltextIndex();
			final int field = registerExactField(index, FulltextFieldKey.attribute("unexpected"), 25.0);
			final ReferenceFulltextFormula formula = assertDoesNotThrow(
				() -> formula(index, words(new int[]{field}, "word")));

			final GenericEvitaInternalError error =
				assertThrows(GenericEvitaInternalError.class, () -> formula.matches(-10, 1));
			assertTrue(error.getMessage().contains("unexpected"), error.getMessage());
			assertTrue(error.getMessage().contains("id " + field), error.getMessage());
		}
	}

	@Nested
	@DisplayName("Partition discovery")
	class Discovery {

		/** Discovery asks for the partitions of the targets holding every word, and only those. */
		@Test
		@DisplayName("Asks discovery for the targets holding every word, signed targets included")
		void shouldDiscoverPartitionsOfQualifyingTargetsOnly() {
			final FulltextIndex index = new FulltextIndex();
			final int field = registerExactField(index, FIRST, 25.0);
			write(index, field, 1, List.of(), List.of(new RowValues(5, Set.of("red blue")),
				new RowValues(0, Set.of("red blue")), new RowValues(-10, Set.of("red blue")),
				new RowValues(7, Set.of("red"))));
			final List<int[]> requests = new ArrayList<>(1);
			final ReferenceFulltextFormula formula = new ReferenceFulltextFormula(index,
				union(words(new int[]{field}, "red", "blue")), targets -> {
					requests.add(targets.clone());
					return ReferencePartitions.split(EmptyBitmap.INSTANCE);
				}, pk -> { throw new AssertionError("No partition lookup expected"); });

			assertTrue(formula.getMatchingPartitions().isEmpty());
			assertEquals(1, requests.size());
			final int[] requested = requests.getFirst();
			Arrays.sort(requested);
			assertArrayEquals(new int[]{-10, 0, 5}, requested);
		}
	}

	/** Uses the production parent wrapper, so enrollment and forms follow the real write protocol. */
	private static void write(
		FulltextIndex index, int field, int owner, List<RowValues> before, List<RowValues> after
	) {
		index.reconcileReferenceValues(field, owner, before, after, true, exactAnalyzer());
	}

	/** Returns the field's real dictionary, failing when the fixture registered a non-reference field. */
	private static ReferenceFulltextValueIndex dictionary(FulltextIndex index, int field) {
		final ReferenceFulltextValueIndex dictionary = index.getReferenceValues(field);
		assertNotNull(dictionary);
		return dictionary;
	}

	/** Checks the raw row model: every word appears in some value of some field of this one row. */
	private static boolean holdsEveryWord(List<Set<String>> row, String[] words) {
		for (final String word : words) {
			boolean found = false;
			for (final Set<String> values : row) {
				for (final String value : values) {
					found |= Arrays.asList(value.split(" ")).contains(word);
				}
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}

	/** Builds one query word per text, each looked up in every given field. */
	private static ExpandedTerm[][] words(int[] fields, String... texts) {
		final ExpandedTerm[][] result = new ExpandedTerm[texts.length][];
		for (int word = 0; word < texts.length; word++) {
			result[word] = new ExpandedTerm[fields.length];
			for (int field = 0; field < fields.length; field++) {
				result[word][field] = term(fields[field], texts[word]);
			}
		}
		return result;
	}

	/** Builds the shared union preparation the reference formula reads its words from. */
	private static FulltextFormula union(ExpandedTerm[][] words) {
		return new FulltextFormula(words, null, new FulltextScoringPolicy(Map.of()));
	}

	/** Builds the memoized preparation without allowing an unexpected discovery split. */
	private static ReferenceFulltextFormula formula(FulltextIndex index, ExpandedTerm[][] words) {
		return new ReferenceFulltextFormula(index, union(words), targets -> {
			throw new AssertionError("Partition discovery must stay lazy");
		}, pk -> { throw new AssertionError("No partition lookup expected"); });
	}

	/** Constructs a retained field/term input; this suite exercises membership rather than impacts. */
	private static ExpandedTerm term(int field, String text) {
		final ImpactView impacts = ImpactView.of(new byte[]{1});
		return new ExpandedTerm(field, text, new BaseBitmap(1), impacts, 0,
			FulltextTermExactness.EXACT, 0, 1f);
	}
}
