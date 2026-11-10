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

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ChildKind;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.MembershipForm;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.RowValues;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ValueMatch;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ValuePage;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.IndexedTermConsumer;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.utils.NumberUtils;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static io.evitadb.index.fulltext.FulltextIndexTestSupport.exactAnalyzer;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.registerExactField;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.*;

/** Real field-owned dictionary tests, including symbolic forms and a bounded row-model oracle. */
@Tag(INDEXING)
@Tag(FULLTEXT)
@DisplayName("Reference fulltext value dictionary")
class ReferenceFulltextValueIndexTest {
	private static final FulltextFieldKey KEY = FulltextFieldKey.referenceAttribute("category", "name");

	@Nested
	@DisplayName("Membership forms and resolution")
	class MembershipForms {

		/** Invalid internal tags fail explicitly even when no persistence loader has seen the state. */
		@Test
		@DisplayName("should refuse a stored form tag outside ALL, EXCEPT and HOLDERS")
		void shouldRefuseUnknownMembershipTag() {
			final FulltextIndex index = createIndex();
			change(index, 1, List.of(), List.of(row(10, "red")), true);
			final ReferenceFulltextValueIndex dictionary = index.getReferenceValues(0);
			// stored tags are 0 (ALL, never stored), 1 (EXCEPT) and 2 (HOLDERS): 3 is the first impossible one
			dictionary.subsetForms.addLongRecord(NumberUtils.pack(match(dictionary, "red").valueId(), 10), 3L);
			final GenericEvitaInternalError error =
				assertThrows(GenericEvitaInternalError.class, () -> dictionary.lookup(Set.of("red")));
			assertTrue(error.getMessage().contains("Unknown reference membership form: 3"), error.getMessage());
		}

		/** Resolution intersects enrollment across distant blocks and returns independent owned results. */
		@Test
		@DisplayName("should resolve partitions spanning several enrollment blocks")
		void shouldResolveMultiBlockPartitions() {
			final FulltextIndex index = createIndex();
			change(index, 1, List.of(), List.of(row(10, "red")), true);
			change(index, 131073, List.of(), List.of(row(10, "red")), true);
			final ReferenceFulltextValueIndex dictionary = index.getReferenceValues(0);
			final BaseBitmap partition = new BaseBitmap(1, 2, 65537, 131073, 196609);
			assertArrayEquals(new int[]{1, 131073},
				resolve(dictionary, match(dictionary, "red"), partition).getArray());
			change(index, 65537, List.of(), List.of(row(10)), true);
			assertEquals(MembershipForm.EXCEPT, match(dictionary, "red").form());
			assertArrayEquals(new int[]{1, 131073},
				resolve(dictionary, match(dictionary, "red"), partition).getArray());
			change(index, 196609, List.of(), List.of(row(10, "blue")), true);
			assertEquals(MembershipForm.HOLDERS, match(dictionary, "blue").form());
			assertArrayEquals(new int[]{196609}, resolve(dictionary, match(dictionary, "blue"), partition).getArray());
			assertArrayEquals(new int[]{1, 2, 65537, 131073, 196609}, partition.getArray());
		}

		/** ALL demotion retains an empty EXCEPT for differing duplicate rows and adds only true nonholders. */
		@Test
		@DisplayName("should maintain ALL, empty EXCEPT and HOLDERS through duplicate row changes")
		void shouldMaintainFormsThroughDuplicateRowChanges() {
			final FulltextIndex index = createIndex();
			final ReferenceFulltextValueIndex dictionary = index.getReferenceValues(0);
			final List<RowValues> red = List.of(row(10, "red"));
			change(index, 1, List.of(), red, true);
			assertEquals(MembershipForm.ALL, match(dictionary, "red").form());
			final List<RowValues> duplicate = List.of(row(10, "red"), row(10));
			change(index, 1, red, duplicate, true);
			final ValueMatch emptyExcept = match(dictionary, "red");
			assertEquals(MembershipForm.EXCEPT, emptyExcept.form());
			assertTrue(emptyExcept.owners().isEmpty());
			change(index, 2, List.of(), List.of(row(10)), true);
			assertArrayEquals(new int[]{2}, match(dictionary, "red").owners().getArray());
			assertArrayEquals(new int[]{1}, resolve(dictionary, match(dictionary, "red"),
				new BaseBitmap(1, 2, 999)).getArray());
			change(index, 3, List.of(), List.of(row(10, "blue")), true);
			assertEquals(MembershipForm.HOLDERS, match(dictionary, "blue").form());
			assertArrayEquals(new int[]{3}, resolve(dictionary, match(dictionary, "blue"),
				new BaseBitmap(1, 2, 3, 999)).getArray());
			assertTrue(resolve(dictionary, match(dictionary, "blue"), new BaseBitmap(1, 2)).isEmpty());
			change(index, 2, List.of(row(10)), List.of(), false);
			change(index, 3, List.of(row(10, "blue")), List.of(), false);
			change(index, 1, duplicate, red, true);
			assertEquals(MembershipForm.ALL, match(dictionary, "red").form());
			assertEquals(0, dictionary.ownerSubsets.size());
			assertEquals(0, dictionary.subsetForms.size());
			assertArrayEquals(new int[]{1}, resolve(dictionary, match(dictionary, "red"),
				new BaseBitmap(1, 2, 999)).getArray());
		}

		/** Batch hydration must consider all rows before assigning owner exception membership. */
		@Test
		@DisplayName("should hydrate all owner rows before deciding EXCEPT membership")
		void shouldHydrateAllOwnerRowsBeforeDecidingExceptMembership() {
			final FulltextIndex index = createIndex();
			change(index, 1, List.of(), List.of(row(10, "red")), true);
			change(index, 2, List.of(row(10, "unindexed")),
				List.of(row(10), row(10, "red"), row(10)), true);
			final ReferenceFulltextValueIndex dictionary = index.getReferenceValues(0);
			assertEquals(4L, dictionary.targetCounts.getLongRecordEqualTo(10).orElseThrow());
			assertEquals(MembershipForm.EXCEPT, match(dictionary, "red").form());
			assertTrue(match(dictionary, "red").owners().isEmpty());
			assertTrue(dictionary.lookup(Set.of("unindexed")).isEmpty());
			assertArrayEquals(new int[]{1, 2}, resolve(dictionary, match(dictionary, "red"),
				new BaseBitmap(1, 2, 3)).getArray());
		}

		/** Each field's ALL resolves inside its own enrollment, including partially hydrated generations. */
		@Test
		@DisplayName("should restrict cross-field ALL forms to each generation's enrolled partition owners")
		void shouldResolveDifferentFieldEnrollments() {
			final FulltextIndex index = createIndex();
			final int second =
				registerExactField(index, FulltextFieldKey.referenceAttribute("category", "color"), 25.0);
			change(index, 1, List.of(), List.of(row(10, "shoe")), true);
			index.reconcileReferenceValues(second, 2, List.of(), List.of(row(10, "red")), true, exactAnalyzer());
			final ReferenceFulltextValueIndex names = index.getReferenceValues(0);
			final ReferenceFulltextValueIndex colors = index.getReferenceValues(second);
			final BaseBitmap partition = new BaseBitmap(1, 2, 3);
			assertEquals(MembershipForm.ALL, match(names, "shoe").form());
			assertEquals(MembershipForm.ALL, match(colors, "red").form());
			assertArrayEquals(new int[]{1}, resolve(names, match(names, "shoe"), partition).getArray());
			assertArrayEquals(new int[]{2}, resolve(colors, match(colors, "red"), partition).getArray());
			index.reconcileReferenceValues(second, 1, List.of(), List.of(row(10, "red")), true, exactAnalyzer());
			assertArrayEquals(new int[]{1, 2}, resolve(colors, match(colors, "red"), partition).getArray());
		}

	}

	@Nested
	@DisplayName("Value identity and generations")
	class ValueIdentity {

		/** Analysis belongs to value birth/death; symbolic lookup and a partial rename never analyze raw text. */
		@Test
		@DisplayName("should analyze each raw value only on birth and final death during a many-owner rename")
		void shouldAnalyzeOnlyOnValueBirthAndDeath() {
			final CompiledFulltextAnalyzer base = exactAnalyzer();
			final Map<String, Integer> calls = new HashMap<>();
			try (final FulltextAnalyzer counting = new FulltextAnalyzer("counting", new KeywordAnalyzer()) {
				@Override
				public void analyzeIndexed(String text, IndexedTermConsumer consumer) {
					calls.merge(text, 1, Integer::sum);
					base.indexSurface().analyzeIndexed(text, consumer);
				}
			}) {
				final CompiledFulltextAnalyzer analyzer = withIndexSurface(base, counting);
				final FulltextIndex index = createIndex();
				final List<RowValues> red = List.of(row(10, "red"));
				final List<RowValues> blue = List.of(row(10, "blue"));
				for (int owner = 1; owner <= 20; owner++) {
					index.reconcileReferenceValues(0, owner, List.of(), red, true, analyzer);
				}
				assertEquals(Map.of("red", 1), calls);
				for (int owner = 1; owner <= 20; owner++) {
					index.reconcileReferenceValues(0, owner, red, blue, true, analyzer);
					if (owner == 10) {
						final ReferenceFulltextValueIndex values = index.getReferenceValues(0);
						assertArrayEquals(new int[]{11, 12}, resolve(values, match(values, "red"),
							new BaseBitmap(9, 10, 11, 12)).getArray());
						assertArrayEquals(new int[]{9, 10}, resolve(values, match(values, "blue"),
							new BaseBitmap(9, 10, 11, 12)).getArray());
						assertEquals(Map.of("red", 1, "blue", 1), calls);
					}
				}
				assertEquals(Map.of("red", 2, "blue", 1), calls);
				assertTrue(index.getReferenceValues(0).lookup(Set.of("red")).isEmpty());
			}
		}

		/** A provenance move preserves the raw value id and cannot combine words across targets. */
		@Test
		@DisplayName("should retain value ids on a target move and keep target word intersections separate")
		void shouldRetainValueIdsOnTargetMove() {
			final FulltextIndex index = createIndex();
			final List<RowValues> original = List.of(row(10, "Zimní čepice"), row(20, "Letní bundy"));
			change(index, 1, List.of(), original, true);
			final ReferenceFulltextValueIndex dictionary = index.getReferenceValues(0);
			final Set<Integer> winter = new TreeSet<>();
			assertEquals(1, dictionary.lookup(Set.of("zimní")).size());
			assertEquals(1, dictionary.lookup(Set.of("bundy")).size());
			for (final ValueMatch match : dictionary.lookup(Set.of("zimní"))) {
				winter.add(match.targetPK());
			}
			for (final ValueMatch match : dictionary.lookup(Set.of("bundy"))) {
				assertFalse(winter.contains(match.targetPK()));
			}
			final long id = dictionary.values.getLongRecordEqualTo("Zimní čepice").orElseThrow();
			change(index, 1, original, List.of(row(20, "Zimní čepice"), row(10, "Letní bundy")), true);
			assertEquals(id, dictionary.values.getLongRecordEqualTo("Zimní čepice").orElseThrow());
			assertEquals(3, dictionary.allocator.getNextValueId());
		}

		/** Index-chain, pivot and withdrawal changes retain frozen generations and hydrate only the written owner. */
		@Test
		@DisplayName("should keep retired dictionaries frozen across analyzer, pivot and withdrawal changes")
		void shouldRetainFrozenDictionaryGenerations() {
			final FulltextIndex index = createIndex();
			change(index, 1, List.of(), List.of(row(10, "red")), true);
			change(index, 2, List.of(), List.of(row(10, "blue")), true);
			final ReferenceFulltextValueIndex original = index.getReferenceValues(0);
			final CompiledFulltextAnalyzer analyzer = exactAnalyzer();
			final long newFingerprint = analyzer.fingerprint().index() ^ 1L;
			final int changedAnalyzer = index.getOrAssignFieldId(KEY, "text", newFingerprint,
				analyzer.fingerprint().wordPhase(), 25.0, null);
			assertTrue(index.isFieldRetired(0));
			index.reconcileReferenceValues(changedAnalyzer, 2, List.of(row(10, "blue")),
				List.of(row(10, "red")), true, analyzer);
			final ReferenceFulltextValueIndex fresh = index.getReferenceValues(changedAnalyzer);
			assertEquals(1L, fresh.targetCounts.getLongRecordEqualTo(10).orElseThrow());
			assertFalse(fresh.isEnrolled(1));
			assertTrue(fresh.lookup(Set.of("blue")).isEmpty());
			assertArrayEquals(new int[]{2}, resolve(fresh, match(fresh, "red"), new BaseBitmap(1, 2)).getArray());
			assertArrayEquals(new int[]{2},
				resolve(original, match(original, "blue"), new BaseBitmap(1, 2)).getArray());
			final int changedPivot = index.getOrAssignFieldId(KEY, "text", newFingerprint,
				analyzer.fingerprint().wordPhase(), 50.0, null);
			assertTrue(index.isFieldRetired(changedAnalyzer));
			assertEquals(0, index.getReferenceValues(changedPivot).values.size());
			index.retireField(KEY);
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, index.getFieldId(KEY));
			final int redeclared = index.getOrAssignFieldId(KEY, "text", newFingerprint,
				analyzer.fingerprint().wordPhase(), 50.0, null);
			// Deleting the old union on the first write must enroll an empty post-image, never resurrect old text.
			index.reconcileReferenceValues(redeclared, 1, List.of(row(10, "red")), List.of(), true, analyzer);
			assertTrue(index.getReferenceValues(redeclared).isEnrolled(1));
			assertTrue(index.getReferenceValues(redeclared).lookup(Set.of("red")).isEmpty());
			assertEquals(2L, original.targetCounts.getLongRecordEqualTo(10).orElseThrow());
			assertEquals(1L, fresh.targetCounts.getLongRecordEqualTo(10).orElseThrow());
		}

	}

	@Nested
	@DisplayName("Transactions, rollback and flush")
	class TransactionsAndFlush {

		/** A bare insert changes R and enrollment while all 600 HOLDERS payloads remain clean. */
		@Test
		@DisplayName("should keep unchanged holder pages clean when a bare row is inserted")
		void shouldKeepUnchangedHolderPagesClean() {
			final FulltextIndex index = createIndex();
			change(index, 1, List.of(), List.of(row(10)), true);
			final Set<String> values = new TreeSet<>();
			for (int i = 0; i < 600; i++) {
				values.add("value" + i);
			}
			change(index, 2, List.of(), List.of(new RowValues(10, values)), true);
			final ReferenceFulltextValueIndex dictionary = index.getReferenceValues(0);
			final PageStreamRegistry registry = new PageStreamRegistry();
			dictionary.collectChangedPages(0, registry);
			registry.publishStaged();
			change(index, 3, List.of(), List.of(row(10)), true);
			final List<PageEmission<ValuePage>> emissions = dictionary.collectChangedPages(0, registry);
			for (final ChildKind kind : ChildKind.values()) {
				assertEquals(kind == ChildKind.TARGET_ROW_COUNTS || kind == ChildKind.ENROLLED_OWNER_BLOCKS,
					!emissions.get(kind.ordinal()).changedPages().isEmpty(), kind.name());
				assertEquals(0, emissions.get(kind.ordinal()).freedPageSequences().length);
			}
		}

		/** A reconciliation that changes nothing must not make the next flush rewrite the index. */
		@Test
		@DisplayName("should leave the index clean when an unenrolled owner leaves or the row images are identical")
		void shouldLeaveIndexCleanWhenReconcileChangesNothing() {
			final FulltextIndex index = createIndex();
			change(index, 1, List.of(), List.of(row(10, "red")), true);
			index.resetDirty();
			change(index, 2, List.of(), List.of(), false);
			assertFalse(index.isDirty());
			change(index, 1, List.of(row(10, "red")), List.of(row(10, "red")), true);
			assertFalse(index.isDirty());
			change(index, 3, List.of(), List.of(row(10)), true);
			assertTrue(index.isDirty());
		}

		/** Each rollback mechanism restores the same field-owned children and allocation state. */
		@Test
		@DisplayName("should restore dictionary children after transaction, savepoint and warm-up rollback")
		void shouldRestoreDictionaryAfterRollback() {
			final FulltextIndex index = createIndex();
			change(index, 1, List.of(), List.of(row(10, "red")), true);
			assertStateAfterRollback(index,
				original -> change(original, 2, List.of(), List.of(row(10, "blue"), row(10)), true),
				(original, reverted) -> assertEquals(Set.of("red"), rawValues(original)));
			assertSavepointRollbackRestores(index,
				original -> change(original, 3, List.of(), List.of(row(10)), true),
				ReferenceFulltextValueIndexTest::dictionaryState,
				original -> change(original, 2, List.of(), List.of(row(10, "blue")), true));
			assertWarmUpSavepointRollbackRestores(index,
				original -> change(original, 4, List.of(), List.of(row(10)), true),
				ReferenceFulltextValueIndexTest::dictionaryState,
				original -> change(original, 2, List.of(), List.of(row(10, "blue")), true));
			assertFalse(index.getReferenceValues(0).isEnrolled(2));
			assertEquals(2, index.getReferenceValues(0).allocator.getNextValueId());
			assertStateAfterCommit(index,
				original -> change(original, 2, List.of(), List.of(row(10, "blue")), true),
				(original, committed) -> {
					assertEquals(Set.of("red"), rawValues(original));
					assertEquals(Set.of("red", "blue"), rawValues(committed));
					assertFalse(original.getReferenceValues(0).isEnrolled(2));
					assertTrue(committed.getReferenceValues(0).isEnrolled(2));
				});
		}

		/** Failure within one complete-owner reconciliation must undo its already processed first target. */
		@Test
		@DisplayName("should rewind the first target when the second one fails in warm-up")
		void shouldRestoreWarmUpAfterFailureInsideSecondTarget() {
			final FulltextIndex index = createIndex();
			final List<RowValues> red = List.of(row(1, "red"));
			change(index, 1, List.of(), red, true);
			change(index, 2, List.of(), List.of(row(1)), true);
			final List<String> before = dictionaryState(index);
			final CompiledFulltextAnalyzer base = exactAnalyzer();
			final boolean[] reached = {false};
			try (final FulltextAnalyzer failing = new FulltextAnalyzer("failing", new KeywordAnalyzer()) {
				@Override
				public void analyzeIndexed(String text, IndexedTermConsumer consumer) {
					if (text.equals("crash")) {
						assertFalse(index.getReferenceValues(0).lookup(Set.of("blue")).isEmpty());
						reached[0] = true;
						throw new IllegalStateException("Injected second-target failure");
					}
					base.indexSurface().analyzeIndexed(text, consumer);
				}
			}) {
				final CompiledFulltextAnalyzer analyzer = withIndexSurface(base, failing);
				final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
				try {
					assertThrows(IllegalStateException.class, () -> index.reconcileReferenceValues(
						0, 1, red, List.of(row(1, "blue"), row(2, "crash")), true, analyzer));
				} finally {
					savepoint.rollback();
				}
			}
			assertTrue(reached[0]);
			assertEquals(before, dictionaryState(index));
		}

	}

	@Nested
	@DisplayName("Model oracle")
	class ModelOracle {

		/** A deterministic model checks every form, both adjacency directions, counts and owner zero crossings. */
		@Test
		@DisplayName("should match a bounded owner-row oracle through arbitrary batch transitions")
		void shouldMatchOwnerRowOracle() {
			final FulltextIndex index = createIndex();
			final ReferenceFulltextValueIndex dictionary = index.getReferenceValues(0);
			final Map<Integer, List<RowValues>> owners = new HashMap<>();
			final Random random = new Random(42);
			final Map<OracleMatch, MembershipForm> expectedForms = new HashMap<>();
			for (int mutation = 0; mutation < 500; mutation++) {
				final int owner = random.nextInt(8) + 1;
				final List<RowValues> after = new ArrayList<>();
				for (int i = 0, count = random.nextInt(4); i < count; i++) {
					final Set<String> values = switch (random.nextInt(5)) {
						case 0 -> Set.of();
						case 1 -> Set.of("red");
						case 2 -> Set.of("blue");
						case 3 -> Set.of("red", "blue");
						default -> Set.of("red blue");
					};
					after.add(new RowValues(10 + random.nextInt(3), values));
				}
				final boolean enroll = !after.isEmpty() || random.nextBoolean();
				change(index, owner, owners.getOrDefault(owner, List.of()), after, enroll);
				assertEquals(enroll, dictionary.isEnrolled(owner));
				owners.put(owner, after);
				final Map<OracleMatch, ValueMatch> actual = new HashMap<>();
				final Map<Integer, String> rawById = new HashMap<>();
				final BucketCursor<String> cursor = dictionary.values.cursor();
				while (cursor.next()) {
					rawById.put((int) cursor.longRecordId(), cursor.value());
				}
				final Set<OracleMatch> expectedKeys = new HashSet<>();
				for (final String word : List.of("red", "blue")) {
					for (final List<RowValues> rows : owners.values()) {
						for (final RowValues row : rows) {
							for (final String raw : row.values()) {
								if (List.of(raw.split(" ")).contains(word)) {
									expectedKeys.add(new OracleMatch(word, row.targetPK(), raw));
								}
							}
						}
					}
					for (final ValueMatch match : dictionary.lookup(Set.of(word))) {
						actual.put(new OracleMatch(word, match.targetPK(), rawById.get(match.valueId())), match);
					}
				}
				assertEquals(expectedKeys, actual.keySet());
				expectedForms.keySet().retainAll(expectedKeys);
				for (final Map.Entry<OracleMatch, ValueMatch> link : actual.entrySet()) {
					final ValueMatch match = link.getValue();
					final Set<Integer> expected = new TreeSet<>();
					long rows = 0L;
					long carrying = 0L;
					for (final Map.Entry<Integer, List<RowValues>> entry : owners.entrySet()) {
						boolean holder = false;
						for (final RowValues row : entry.getValue()) {
							if (row.targetPK() == match.targetPK()) {
								rows++;
								if (row.values().contains(link.getKey().raw())) {
									carrying++;
									holder = true;
								}
							}
						}
						if (holder) {
							expected.add(entry.getKey());
							if (match.form() == MembershipForm.EXCEPT) {
								assertFalse(match.owners().contains(entry.getKey()));
							}
						}
					}
					assertEquals(rows, dictionary.targetCounts.getLongRecordEqualTo(match.targetPK()).orElseThrow());
					assertEquals(carrying, dictionary.holderCounts.getLongRecordEqualTo(
						NumberUtils.pack(match.valueId(), match.targetPK())).orElseThrow());
					final MembershipForm previousForm = expectedForms.get(link.getKey());
					final MembershipForm expectedForm = carrying == rows ? MembershipForm.ALL :
						previousForm == null ? MembershipForm.HOLDERS :
						previousForm == MembershipForm.ALL ? MembershipForm.EXCEPT : previousForm;
					assertEquals(expectedForm, match.form());
					expectedForms.put(link.getKey(), expectedForm);
					final int[] partition = owners.entrySet().stream().filter(entry -> entry.getValue().stream()
						.anyMatch(row -> row.targetPK() == match.targetPK()))
						.mapToInt(Map.Entry::getKey).sorted().toArray();
					assertArrayEquals(expected.stream().mapToInt(Integer::intValue).toArray(),
						resolve(dictionary, match, new BaseBitmap(partition)).getArray());
					assertTrue(dictionary.targetValues.getRecordsEqualTo(match.targetPK()).contains(match.valueId()));
				}
			}
		}

	}

	/** Independent model key includes the exact raw value, even when several values share a word. */
	private record OracleMatch(String word, int target, String raw) { }

	/** Creates a real field-owned generation. */
	private static FulltextIndex createIndex() {
		final FulltextIndex result = new FulltextIndex();
		registerExactField(result, KEY, 25.0);
		return result;
	}

	/** Swaps only the chain that analyzes raw values on birth and death, keeping the base fingerprint. */
	private static CompiledFulltextAnalyzer withIndexSurface(CompiledFulltextAnalyzer base, FulltextAnalyzer surface) {
		return new CompiledFulltextAnalyzer(
			base.word(), base.splitting(), base.queryText(), base.textSplitting(), base.textSplitWord(), surface,
			base.surface(), base.textSplitSurface(), base.fragment(), base.textSplitFragment(), base.fingerprint(),
			base.foldedStopWords(), base.stopWordPreparations());
	}

	/** Constructs one row with distinct values. */
	private static RowValues row(int target, String... values) {
		return new RowValues(target, Set.of(values));
	}

	/** Uses the production parent wrapper, preserving its transaction/dirty protocol. */
	private static void change(
		FulltextIndex index, int owner, List<RowValues> before, List<RowValues> after, boolean enroll
	) {
		index.reconcileReferenceValues(0, owner, before, after, enroll, exactAnalyzer());
	}

	/** Interprets symbolic lookup results using the production enrollment views and single-owner checks. */
	private static BaseBitmap resolve(ReferenceFulltextValueIndex dictionary, ValueMatch match, BaseBitmap partition) {
		final BaseBitmap result = new BaseBitmap();
		final Bitmap enrolled = dictionary.getEnrolledOwners(partition);
		for (final int owner : partition.getArray()) {
			assertEquals(dictionary.isEnrolled(owner), enrolled.contains(owner));
			if (switch (match.form()) {
				case ALL -> enrolled.contains(owner);
				case EXCEPT -> enrolled.contains(owner) && !match.owners().contains(owner);
				case HOLDERS -> match.owners().contains(owner);
			}) {
				result.add(owner);
			}
		}
		return result;
	}

	/** Finds the sole value-target match in the single-target fixtures. */
	private static ValueMatch match(ReferenceFulltextValueIndex dictionary, String term) {
		final List<ValueMatch> matches = dictionary.lookup(Set.of(term));
		assertEquals(1, matches.size());
		return matches.getFirst();
	}

	/** Captures stable raw text identities through production child state. */
	private static Set<String> rawValues(FulltextIndex index) {
		final Set<String> result = new TreeSet<>();
		final BucketCursor<String> cursor = index.getReferenceValues(0).values.cursor();
		while (cursor.next()) {
			result.add(cursor.value());
		}
		return result;
	}

	/** Savepoint comparisons include every count, tag, subset, adjacency and allocator, even with unchanged raw ids. */
	static List<String> dictionaryState(FulltextIndex index) {
		final ReferenceFulltextValueIndex values = index.getReferenceValues(0);
		final List<String> result = new ArrayList<>();
		result.add("next=" + values.allocator.getNextValueId());
		int kind = 0;
		for (final TransactionalBucketBPlusTree<?> tree : List.of(
			values.values, values.terms, values.valueTargets, values.targetValues, values.targetCounts,
			values.holderCounts, values.subsetForms, values.ownerSubsets, values.enrolledOwners)) {
			final boolean scalar = ReferenceFulltextValueIndex.ChildKind.values()[kind].scalar();
			final BucketCursor<?> cursor = tree.cursor();
			while (cursor.next()) {
				result.add(kind + ":" + cursor.value() + "=" + (scalar ? cursor.longRecordId() :
					Arrays.toString(cursor.records().getArray())));
			}
			kind++;
		}
		return result;
	}
}
