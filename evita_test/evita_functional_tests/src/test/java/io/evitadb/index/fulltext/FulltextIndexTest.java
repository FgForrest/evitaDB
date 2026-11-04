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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlock;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlockEmission;
import io.evitadb.index.fulltext.FulltextFieldKey.FieldKind;
import io.evitadb.index.fulltext.FulltextIndex.DictionaryPage;
import io.evitadb.index.fulltext.FulltextIndex.Field;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Expansion;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.MergeStrategy;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockPart;
import io.evitadb.test.fulltext.AnalyzedTerm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.index.fulltext.FulltextFieldKey.associatedData;
import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.index.fulltext.FulltextFieldKey.referenceAttribute;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.*;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link FulltextIndex}: field registration, the posting and impact maintenance of the dictionary, the
 * isolation of fields sharing one dictionary, the analyzing write path, and the transactional contract — the last two
 * against an index rebuilt from scratch, the strongest oracle available.
 *
 * The randomized tests here are bounded and seeded. The time-bounded generational proofs - chained commits, warm-up
 * churn, and savepoint rollback and commit in both phases - are `LongRunningFulltextIndexTest` and
 * `LongRunningSavepointFulltextIndexTest` in the long-running test module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(FULLTEXT)
@DisplayName("Fulltext index")
class FulltextIndexTest {

	/**
	 * Words the randomized tests build values from: Czech declension variants the analyzer folds together, stop words,
	 * and diacritics.
	 */
	private static final String[] VOCABULARY = {
		"Praha", "Brno", "Ostrava", "kolo", "kola", "kolem", "horské", "horský", "elektrické", "město",
		"městem", "hrad", "hradu", "řeka", "řeky", "most", "mostu", "a", "na", "pro", "žlutý", "kůň"
	};

	/**
	 * Exclusive upper bound of the primary keys the randomized tests use.
	 */
	private static final int PK_BOUND = 300;

	/**
	 * Separates the elements of an array value in the value model of the randomized tests - a character the analyzer
	 * drops, so a model value with the separators replaced by spaces is the same text indexed as one value.
	 */
	private static final char ELEMENT_SEPARATOR = '|';

	/**
	 * Registry providing the real Czech index-slot analyzer.
	 */
	private static FulltextAnalyzerRegistry registry;

	/**
	 * The Czech index-slot analyzer every index in this test uses.
	 */
	private static FulltextAnalyzer analyzer;

	@BeforeAll
	static void setUpAnalyzer() {
		registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry());
		analyzer = testAnalyzer().index();
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	/**
	 * Creates an empty index over the Czech analyzer.
	 *
	 * @return the index
	 */
	@Nonnull
	private static FulltextIndex newIndex() {
		return new FulltextIndex();
	}

	/** An empty direct addition does not register a field generation. */
	@Test
	void shouldIgnoreDirectEmptyValueSets() {
		final FulltextIndex index = newIndex();
		index.addValue(attribute("name"), 1, testAnalyzer(), 25.0, null, new String[0]);
		assertEquals(0, index.getFieldCount(), "Empty direct addition must not register a field");
	}

	/** Empty removals and additions leave an existing stale field and its postings intact. */
	@Test
	void shouldIgnoreDirectEmptyRemovalFromStaleField() {
		final FulltextIndex index = newIndex();
		index.addValue(attribute("name"), 1, testAnalyzer(), 25.0, null, "Praha");
		final int original = index.getFieldId(attribute("name"));
		index.removeValue(attribute("name"), 2, testAnalyzer(), 50.0, new String[0]);
		assertFalse(index.isFieldRetired(original), "Empty direct removal must not retire a stale field");
		index.addValue(attribute("name"), 2, testAnalyzer(), 50.0, null, new String[0]);
		assertEquals(original, index.getFieldId(attribute("name")), "Empty add must preserve the stale generation");
	}

	/** Per-field analysis generations preserve posting ownership across changes. */
	@Nested
	@DisplayName("Analysis generations")
	class AnalysisGenerations {
		/** A changed chain retires on removal, and the add starts a new generation containing only the new write. */
		@Test
		@DisplayName("should retire on a fingerprint change and keep identical resends")
		void shouldRetireOnFingerprintChange() {
			final FulltextIndex index = newIndex();
			final CompiledFulltextAnalyzer first = testAnalyzer();
			final CompiledFulltextAnalyzer second =
				registry.getCompiledAnalyzer(SCHEMA, "html-text", Locale.forLanguageTag("cs"));
			index.addValue(attribute("name"), 1, first, 25.0, null, "Praha");
			assertEquals(0, index.getFieldId(attribute("name")));
			index.removeValue(attribute("name"), 1, second, 25.0, "Praha");
			assertTrue(index.isFieldRetired(0));
			index.addValue(attribute("name"), 2, second, 25.0, null, "Brno");
			assertEquals(1, index.getFieldId(attribute("name")));
			index.removeValue(attribute("name"), 2, second, 25.0, "Brno");
			index.addValue(attribute("name"), 2, second, 25.0, null, "Brno");
			assertEquals(2, index.getFieldCount());
			assertEquals(first.fingerprint().index(), index.getIndexFingerprint(0));
			assertEquals(second.fingerprint().wordPhase(), index.getWordPhaseId(1));
		}

		/** An old entity was never indexed in the new generation; removing it must not touch another entity. */
		@Test
		@DisplayName("should ignore removal of an entity absent from the fresh field")
		void shouldIgnoreRemovalFromFreshField() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), 25.0, null, "Praha");
			index.addValue(attribute("name"), 2, testAnalyzer(), 50.0, null, "Praha");
			index.removeValue(attribute("name"), 1, testAnalyzer(), 50.0, "Praha");
			assertArrayEquals(new int[]{2}, postingsOf(index, 1, "prah").getArray());
			assertFalse(index.hasIndexedValue(1, 1));
		}

		/** Automatic generation retirement is also journaled during warm-up bulk loading. */
		@Test
		@DisplayName("should roll back automatic retirement in a warm-up savepoint")
		void shouldRollBackAutomaticRetirementInWarmUp() {
			final FulltextIndex index = newIndex();
			assertWarmUpSavepointRollbackRestores(index,
				original -> original.addValue(attribute("name"), 1, testAnalyzer(), 25.0, null, "Praha"),
				FulltextIndexTest::registryOf,
				original -> original.addValue(attribute("name"), 2, testAnalyzer(), 50.0, null, "Brno"));
			assertEquals(0, index.getFieldId(attribute("name")));
			assertEquals(1, index.getFieldCount());
			assertFalse(index.isFieldRetired(0));
		}

		/** Automatic generation retirement participates in the transaction rollback. */
		@Test
		@Tag(TRANSACTION)
		@DisplayName("should roll back automatic retirement in a transaction")
		void shouldRollBackAutomaticRetirement() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), 25.0, null, "Praha");
			assertStateAfterRollback(index,
				original -> original.addValue(attribute("name"), 2, testAnalyzer(), 50.0, null, "Brno"),
				(original, rolledBack) -> {
					assertFalse(original.isFieldRetired(0));
					assertEquals(1, original.getFieldCount());
				});
		}
	}

	@Nested
	@DisplayName("Fields")
	class Fields {

		@Test
		@DisplayName("Field ids are assigned in first-use order and never change")
		void shouldAssignStableFieldIds() {
			final FulltextIndex index = newIndex();
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, index.getFieldId(attribute("name")));
			assertEquals(0, registerField(index, attribute("name")));
			assertEquals(1, registerField(index, attribute("description"), 120.0));
			assertEquals(0, registerField(index, attribute("name")));
			assertEquals(1, index.getFieldId(attribute("description")));
			assertEquals(attribute("description"), index.getFieldKey(1));
			assertNull(index.getFieldKey(2));
			assertNull(index.getFieldKey(-1));
			assertEquals(2, index.getFieldCount());
			assertEquals(25.0, index.getLengthPivot(0));
			assertEquals(120.0, index.getLengthPivot(1));
		}

		@Test
		@DisplayName("A pivot change retires its field, and a pivot must be positive")
		void shouldRetireOnPivotChangeAndRefuseInvalidPivot() {
			final FulltextIndex index = newIndex();
			registerField(index, attribute("body"), 200.0);
			assertEquals(0, registerField(index, attribute("body"), 200.0));
			assertEquals(1, registerField(index, attribute("body"), 100.0));
			assertTrue(index.isFieldRetired(0));
			assertThrows(GenericEvitaInternalError.class, () -> registerField(index, attribute("title"), 0.0));
			assertThrows(
				GenericEvitaInternalError.class, () -> registerField(index, attribute("title"), Double.NaN)
			);
			assertThrows(GenericEvitaInternalError.class, () -> registerField(index, attribute("invalid"), -1.0));
		}

		@Test
		@DisplayName("A field keeps the text expression it was registered with, retired or not")
		void shouldKeepTheTextExpressionOfAField() {
			final FulltextIndex index = newIndex();
			final int bodyId = registerField(index,
				associatedData("body"), 25.0, "$value.blocks.*[$.text]"
			);
			assertEquals("$value.blocks.*[$.text]", index.getTextExpression(bodyId));
			// an existing field is returned with the expression it was built with - extraction stays symmetric
			assertEquals(
				bodyId,
				registerField(index, associatedData("body"), 25.0, "$value.title")
			);
			assertEquals("$value.blocks.*[$.text]", index.getTextExpression(bodyId));
			// a field registered without one - an attribute, a String value - has none
			assertNull(index.getTextExpression(registerField(index, attribute("name"))));
			// retirement keeps it, a re-declaration registers the new one
			assertTrue(index.retireField(associatedData("body")));
			assertEquals("$value.blocks.*[$.text]", index.getTextExpression(bodyId));
			final int reDeclaredId = registerField(index,
				associatedData("body"), 25.0, "$value.title"
			);
			assertNotEquals(bodyId, reDeclaredId);
			assertEquals("$value.title", index.getTextExpression(reDeclaredId));
			assertThrows(GenericEvitaInternalError.class, () -> index.getTextExpression(10));
		}

		@Test
		@DisplayName("A field id the index never assigned is refused")
		void shouldRefuseUnassignedFieldId() {
			final FulltextIndex index = newIndex();
			registerField(index, attribute("name"));
			assertThrows(GenericEvitaInternalError.class, () -> index.hasIndexedValue(1, 1));
			assertThrows(GenericEvitaInternalError.class, () -> index.hasIndexedValue(-1, 1));
			assertThrows(GenericEvitaInternalError.class, () -> index.getPersistedLengthBlocks(1));
			assertThrows(GenericEvitaInternalError.class, () -> index.getLengthPivot(1));
			assertThrows(
				GenericEvitaInternalError.class, () -> index.forEachTerm(1, "", (term, postings, impacts) -> true)
			);
		}

		@Test
		@DisplayName("The last field id the index assigns is one a persisted length block can name")
		void shouldStopAssigningFieldIdsWhereLengthBlocksStop() {
			// the term key prefix addresses more fields than a length block page can name - the lower limit wins
			assertTrue(FulltextIndex.MAX_FIELD_ID < FulltextTermKeys.MAX_FIELD_ID);
			assertEquals(FulltextFieldLengthBlockPart.MAX_FIELD_ID, FulltextIndex.MAX_FIELD_ID);

			final FulltextIndex index = newIndex();
			for (int i = 0; i <= FulltextIndex.MAX_FIELD_ID; i++) {
				registerField(index, attribute("field" + i));
			}
			assertEquals(FulltextIndex.MAX_FIELD_ID + 1, index.getFieldCount());
			assertDoesNotThrow(() -> FulltextFieldLengthBlockPart.pageSequenceOf(FulltextIndex.MAX_FIELD_ID, 0xFFFF));
			assertThrows(GenericEvitaInternalError.class, () -> registerField(index, attribute("oneTooMany")));
			assertEquals(FulltextIndex.MAX_FIELD_ID + 1, index.getFieldCount());
		}

		@Test
		@DisplayName("A retired field keeps its id against the limit, and a transaction enforces the limit too")
		@Tag(TRANSACTION)
		void shouldCountRetiredFieldsAgainstTheIdLimitInsideATransaction() {
			final FulltextIndex index = newIndex();
			// every id but the last one, outside a transaction
			for (int i = 0; i < FulltextIndex.MAX_FIELD_ID; i++) {
				registerField(index, attribute("field" + i));
			}
			assertStateAfterCommit(
				index,
				original -> {
					assertTrue(original.retireField(attribute("field0")));
					// the retired field keeps id 0, so its successor takes the last id there is
					assertEquals(FulltextIndex.MAX_FIELD_ID, registerField(original, attribute("field0")));
					assertThrows(
						GenericEvitaInternalError.class, () -> registerField(original, attribute("oneTooMany"))
					);
					assertEquals(FulltextIndex.MAX_FIELD_ID + 1, original.getFieldCount());
				},
				(original, committed) -> {
					assertEquals(FulltextIndex.MAX_FIELD_ID + 1, committed.getFieldCount());
					assertTrue(committed.isFieldRetired(0));
					assertEquals(FulltextIndex.MAX_FIELD_ID, committed.getFieldId(attribute("field0")));
					assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, committed.getFieldId(attribute("oneTooMany")));
				}
			);
		}

	}

	@Nested
	@DisplayName("Field identity")
	class FieldIdentity {

		@Test
		@DisplayName("One name in different kinds, or under different references, is as many independent fields")
		void shouldKeepKindsAndReferencesApart() {
			final FulltextIndex index = newIndex();
			final FulltextFieldKey[] keys = {
				attribute("name"), associatedData("name"), referenceAttribute("brand", "name"),
				referenceAttribute("category", "name")
			};
			final String[] values = {"horské kolo", "elektrické kolo", "kolo Praha", "kolem hradu"};
			for (int i = 0; i < keys.length; i++) {
				index.addValue(keys[i], i + 1, testAnalyzer(), fieldPivot(index, keys[i]), null, values[i]);
			}
			assertEquals(keys.length, index.getFieldCount());
			final String bike = termFrequencies("kolo").keySet().iterator().next();
			for (int fieldId = 0; fieldId < keys.length; fieldId++) {
				assertEquals(fieldId, index.getFieldId(keys[fieldId]));
				assertEquals(keys[fieldId], index.getFieldKey(fieldId));
				assertArrayEquals(
					termFrequencies(values[fieldId]).containsKey(bike) ? new int[]{fieldId + 1} : new int[0],
					postingsOf(index, fieldId, bike).getArray(),
					keys[fieldId] + " holds only its own value"
				);
			}
		}

		@Test
		@DisplayName("A reference name is required for a reference attribute and refused for every other kind")
		void shouldRefuseMisplacedReferenceName() {
			assertThrows(
				GenericEvitaInternalError.class, () -> new FulltextFieldKey(FieldKind.REFERENCE_ATTRIBUTE, null, "name")
			);
			assertThrows(
				GenericEvitaInternalError.class, () -> new FulltextFieldKey(FieldKind.ATTRIBUTE, "brand", "name")
			);
			assertThrows(
				GenericEvitaInternalError.class, () -> new FulltextFieldKey(FieldKind.ASSOCIATED_DATA, "brand", "name")
			);
			assertEquals("brand", referenceAttribute("brand", "name").referenceName());
			assertNull(associatedData("name").referenceName());
		}

	}

	@Nested
	@DisplayName("Retirement")
	class Retirement {

		@Test
		@DisplayName("A retired field keeps its postings out of reach, takes no writes, and its key starts a new field")
		void shouldRetireFieldAndStartTheReAddedOneEmpty() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"horské kolo");
			index.addValue(attribute("description"), 1, testAnalyzer(), fieldPivot(index,
				attribute("description")), null, "Praha");
			final String bike = termFrequencies("kolo").keySet().iterator().next();

			assertTrue(index.retireField(attribute("name")));
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, index.getFieldId(attribute("name")));
			assertTrue(index.isFieldRetired(0));
			assertFalse(index.isFieldRetired(1));
			assertEquals(attribute("name"), index.getFieldKey(0));
			// the postings and the lengths stay where they are until a reindex drops them
			assertArrayEquals(new int[]{1}, postingsOf(index, 0, bike).getArray());
			assertTrue(index.hasIndexedValue(0, 1));
			assertTrue(index.isDirty(), "The field registry changed, so the index must be flushed.");

			assertFalse(index.retireField(attribute("name")), "A key resolving to no field retires nothing.");
			assertFalse(index.retireField(attribute("unknown")));
			final String retired = contentOf(index);
			index.removeValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")),
				"horské kolo");
			assertEquals(retired, contentOf(index), "Removing through the retired key reaches nothing.");

			index.addValue(attribute("name"), 2, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"elektrické kolo");
			assertEquals(2, index.getFieldId(attribute("name")));
			assertEquals(1, index.getFieldId(attribute("description")));
			assertEquals(3, index.getFieldCount());
			assertArrayEquals(new int[]{2}, postingsOf(index, 2, bike).getArray());
			assertArrayEquals(new int[]{1}, postingsOf(index, 0, bike).getArray());
		}

		@Test
		@DisplayName("A key retired again after its re-addition leaves two retired fields and resolves to the third")
		void shouldRetireTheReAddedFieldAgain() {
			final FulltextIndex index = newIndex();
			registerField(index, attribute("name"));
			assertTrue(index.retireField(attribute("name")));
			assertEquals(1, registerField(index, attribute("name"), 120.0));
			assertTrue(index.retireField(attribute("name")));
			assertEquals(2, registerField(index, attribute("name")));
			assertTrue(index.isFieldRetired(0));
			assertTrue(index.isFieldRetired(1));
			assertFalse(index.isFieldRetired(2));
			assertEquals(120.0, index.getLengthPivot(1));
			// the pivot belongs to the field, not to the key: the field registered now takes the default again
			assertEquals(25.0, index.getLengthPivot(2));
		}

		@Test
		@DisplayName("A restored registry resolves a key to its field not retired, and refuses two such fields")
		void shouldRestoreRetiredFields() {
			final DictionaryPage[] emptyDictionary = {new DictionaryPage(0, new ValueToRecord[0], new byte[0][])};
			final FulltextIndex restored = FulltextIndex.fromPersistedPages(List.of(
					new Field(attribute("name"), "text", testAnalyzer().fingerprint().index(),
						testAnalyzer().fingerprint().wordPhase(), 25.0, null, true, new FieldLengthTable()),
					new Field(attribute("description"), "text", testAnalyzer().fingerprint().index(),
						testAnalyzer().fingerprint().wordPhase(), 25.0, null, true, new FieldLengthTable()),
					new Field(attribute("name"), "text", testAnalyzer().fingerprint().index(),
						testAnalyzer().fingerprint().wordPhase(), 50.0, null, false, new FieldLengthTable())
				), new int[]{0}, emptyDictionary, 0);
			assertEquals(2, restored.getFieldId(attribute("name")));
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, restored.getFieldId(attribute("description")));
			assertTrue(restored.isFieldRetired(0));
			assertTrue(restored.isFieldRetired(1));
			assertEquals(3, registerField(restored, attribute("description")));

			assertThrows(
				GenericEvitaInternalError.class,
				() -> FulltextIndex.fromPersistedPages(List.of(
						new Field(attribute("name"), "text", testAnalyzer().fingerprint().index(),
							testAnalyzer().fingerprint().wordPhase(), 25.0, null, false, new FieldLengthTable()),
						new Field(attribute("name"), "text", testAnalyzer().fingerprint().index(),
							testAnalyzer().fingerprint().wordPhase(), 25.0, null, false, new FieldLengthTable())
					), new int[]{0}, emptyDictionary, 0)
			);
		}

		@Test
		@DisplayName("Asking whether an unassigned field id is retired is refused")
		void shouldRefuseRetiredQueryOfUnassignedId() {
			final FulltextIndex index = newIndex();
			assertThrows(GenericEvitaInternalError.class, () -> index.isFieldRetired(0));
		}

	}

	@Nested
	@DisplayName("Postings")
	class Postings {

		@Test
		@DisplayName("Postings and their impacts follow the values written; a term leaves with its last posting")
		void shouldMaintainPostingsAndImpacts() {
			final FulltextIndex index = newIndex();
			final FulltextFieldKey nameKey = attribute("name");
			final int name = registerExactField(index, nameKey, 25.0);
			addWords(index, nameKey, 7, "praha");
			addWords(index, nameKey, 3, "praha", "praha", "brno");
			assertArrayEquals(new int[]{3, 7}, postingsOf(index, name, "praha").getArray());
			assertArrayEquals(new byte[]{impact(2, 3), impact(1, 1)}, impactsOf(index, name, "praha"));
			// a value written in place of the old one replaces its impact
			removeWords(index, nameKey, 7, "praha");
			addWords(index, nameKey, 7, "praha", "praha");
			assertArrayEquals(new byte[]{impact(2, 3), impact(2, 2)}, impactsOf(index, name, "praha"));
			assertEquals(2, termCount(index));

			removeWords(index, nameKey, 3, "praha", "brno");
			assertArrayEquals(new int[]{7}, postingsOf(index, name, "praha").getArray());
			assertArrayEquals(new byte[]{impact(2, 2)}, impactsOf(index, name, "praha"));
			assertTrue(postingsOf(index, name, "brno").isEmpty(), "A term leaves with its last posting.");
			// removing a value the entity does not hold changes nothing
			removeWords(index, nameKey, 99, "praha");
			assertArrayEquals(new int[]{7}, postingsOf(index, name, "praha").getArray());

			removeWords(index, nameKey, 7, "praha");
			assertTrue(postingsOf(index, name, "praha").isEmpty());
			assertEquals(0, impactsOf(index, name, "praha").length);
			assertEquals(0, termCount(index));
		}

		@Test
		@DisplayName("The same term in two fields is two independent posting lists")
		void shouldIsolateFieldsSharingATerm() {
			final FulltextIndex index = newIndex();
			final int name = registerExactField(index, attribute("name"), 25.0);
			final int description = registerExactField(index, attribute("description"), 25.0);
			addWords(index, attribute("name"), 1, "kolo");
			addWords(index, attribute("description"), 2, "kolo");
			addWords(index, attribute("description"), 3, "kolo", "kolo");
			assertArrayEquals(new int[]{1}, postingsOf(index, name, "kolo").getArray());
			assertArrayEquals(new byte[]{impact(1, 1), impact(2, 2)}, impactsOf(index, description, "kolo"));
			assertEquals(2, termCount(index));

			removeWords(index, attribute("name"), 1, "kolo");
			assertTrue(postingsOf(index, name, "kolo").isEmpty());
			assertArrayEquals(new int[]{2, 3}, postingsOf(index, description, "kolo").getArray());
		}

		@Test
		@DisplayName("A term walk visits exactly the field's terms with the prefix, in order, across leaves")
		void shouldWalkOnlyTheFieldsTermsWithThePrefix() {
			final FulltextIndex index = newIndex();
			// eleven fields, so ids 9 and 10 straddle the digit/letter boundary of the hexadecimal prefix
			final int fieldCount = 11;
			final List<Map<String, TreeSet<Integer>>> expected = new ArrayList<>(fieldCount);
			final List<Map<Integer, TreeSet<String>>> values = new ArrayList<>(fieldCount);
			final Random random = new Random(7);
			for (int i = 0; i < fieldCount; i++) {
				assertEquals(i, registerExactField(index, attribute("field" + i), 25.0));
				expected.add(new TreeMap<>());
				values.add(new TreeMap<>());
			}
			// enough terms that one field spans several 256-bucket leaves
			for (int i = 0; i < 6_000; i++) {
				final int fieldId = random.nextInt(fieldCount);
				final String term = randomTerm(random);
				final int primaryKey = random.nextInt(50);
				expected.get(fieldId).computeIfAbsent(term, t -> new TreeSet<>()).add(primaryKey);
				values.get(fieldId).computeIfAbsent(primaryKey, pk -> new TreeSet<>()).add(term);
			}
			// an entity has one value per field, so its terms are written together
			for (int fieldId = 0; fieldId < fieldCount; fieldId++) {
				for (Map.Entry<Integer, TreeSet<String>> value : values.get(fieldId).entrySet()) {
					addWords(
						index, attribute("field" + fieldId), value.getKey(), value.getValue().toArray(String[]::new)
					);
				}
			}

			for (int fieldId = 0; fieldId < fieldCount; fieldId++) {
				assertWalkEquals(expected.get(fieldId), index, fieldId, "");
				for (String prefix : new String[]{"a", "ab", "č", "zz", "nonexistent"}) {
					final Map<String, TreeSet<Integer>> filtered = new TreeMap<>();
					for (Map.Entry<String, TreeSet<Integer>> entry : expected.get(fieldId).entrySet()) {
						if (entry.getKey().startsWith(prefix)) {
							filtered.put(entry.getKey(), entry.getValue());
						}
					}
					assertWalkEquals(filtered, index, fieldId, prefix);
				}
			}
		}

		@Test
		@DisplayName("A term walk stops when the visitor asks it to")
		void shouldStopWalkWhenVisitorDeclines() {
			final FulltextIndex index = newIndex();
			final int name = registerExactField(index, attribute("name"), 25.0);
			addWords(index, attribute("name"), 1, "a", "b", "c", "d");
			final List<String> visited = new ArrayList<>(4);
			index.forEachTerm(name, "", (term, postings, impacts) -> {
				visited.add(term);
				return visited.size() < 2;
			});
			assertEquals(List.of("a", "b"), visited);
		}

		@Test
		@DisplayName("Postings and impacts stay aligned through every bucket representation")
		void shouldKeepPostingsAndImpactsThroughBucketPromotion() {
			final FulltextIndex index = newIndex();
			final int body = registerExactField(index, attribute("body"), 25.0);
			final int title = registerExactField(index, attribute("title"), 25.0);
			// past the sorted-array tier, into a bitmap spanning three roaring containers; each value repeats the term
			// a different number of times, so neighbouring postings carry different impacts
			final TreeMap<Integer, Integer> expected = new TreeMap<>();
			for (int primaryKey = 140_000; primaryKey >= 0; primaryKey -= 97) {
				final int frequency = 1 + primaryKey % 7;
				addWords(index, attribute("body"), primaryKey, "the ".repeat(frequency));
				expected.put(primaryKey, FulltextIndex.computeImpact(frequency, frequency, 25.0));
			}
			addWords(index, attribute("title"), 5, "the");
			assertPostingsEqual(expected, index, body, "the");
			assertArrayEquals(new byte[]{impact(1, 1)}, impactsOf(index, title, "the"));

			// every other posting leaves, so the bitmap shrinks in every container
			for (int primaryKey = 140_000; primaryKey >= 0; primaryKey -= 2 * 97) {
				removeWords(index, attribute("body"), primaryKey, "the");
				expected.remove(primaryKey);
			}
			assertPostingsEqual(expected, index, body, "the");
			assertArrayEquals(new int[]{5}, postingsOf(index, title, "the").getArray());
		}

		@Test
		@DisplayName("The scorer ranks on the impacts a walk hands out exactly as on a flat copy of them")
		void shouldScoreOnWalkImpactsAsOnCopies() {
			final FulltextIndex index = newIndex();
			final int body = registerExactField(index, attribute("body"), 25.0);
			final Random random = new Random(99);
			// buckets in every tier: chunked bitmaps over many roaring containers, sorted arrays, singles - each value
			// repeating its terms a random number of times, so the impacts vary
			final Map<Integer, StringBuilder> values = new TreeMap<>();
			for (int primaryKey = 0; primaryKey < 400_000; primaryKey += 1 + random.nextInt(200)) {
				final StringBuilder value = values.computeIfAbsent(primaryKey, pk -> new StringBuilder(64));
				value.append("the ".repeat(1 + random.nextInt(8)));
				if (random.nextInt(50) == 0) {
					value.append("thermal ".repeat(1 + random.nextInt(8)));
				}
			}
			// a single-record bucket, on a primary key the stride-3 and stride-997 candidate sets below contain
			values.computeIfAbsent(2_991, pk -> new StringBuilder(8)).append("theory");
			for (Map.Entry<Integer, StringBuilder> value : values.entrySet()) {
				addWords(index, attribute("body"), value.getKey(), value.getValue().toString());
			}
			final List<Expansion> fromWalk = new ArrayList<>(3);
			final List<Expansion> fromCopies = new ArrayList<>(3);
			index.forEachTerm(body, "the", (term, postings, impacts) -> {
				fromWalk.add(new Expansion(postings, impacts, 0));
				fromCopies.add(new Expansion(postings, ImpactView.of(impacts.toArray()), 0));
				return true;
			});
			assertEquals(3, fromWalk.size());
			// candidates dense, comparable and sparse against the postings, and every merge forced on each, so the
			// counted impact offsets of the posting-driven merges and the ranks of the skipping one all meet the
			// chunk boundaries of the walked view
			for (final int stride : new int[]{3, 997, 9_973}) {
				final int[] candidates = new int[400_000 / stride];
				for (int i = 0; i < candidates.length; i++) {
					candidates[i] = i * stride;
				}
				final Result copied = FulltextPhaseOneScorer.score(
					candidates, new Expansion[][]{fromCopies.toArray(Expansion[]::new)}, 50, MergeStrategy.LINEAR
				);
				assertTrue(copied.matchedDocuments() > 0);
				for (final MergeStrategy strategy : MergeStrategy.values()) {
					final Result walked = FulltextPhaseOneScorer.score(
						candidates, new Expansion[][]{fromWalk.toArray(Expansion[]::new)}, 50, strategy
					);
					final String message = "stride " + stride + " " + strategy;
					assertEquals(copied.matchedDocuments(), walked.matchedDocuments(), message);
					assertArrayEquals(copied.primaryKeys(), walked.primaryKeys(), message);
					assertArrayEquals(copied.composites(), walked.composites(), message);
				}
			}
		}

	}

	@Nested
	@DisplayName("Write path")
	class WritePath {

		@Test
		@DisplayName("A value is analyzed into postings carrying the computed impacts, and its length is recorded")
		void shouldIndexValueWithImpactsAndLength() {
			final FulltextIndex index = newIndex();
			final String value = "Praha a Praha a Brno";
			index.addValue(attribute("name"), 10, testAnalyzer(), fieldPivot(index, attribute("name")), null, value);
			final int name = index.getFieldId(attribute("name"));

			final Map<String, Integer> frequencies = termFrequencies(value);
			final int length = positions(value);
			assertTrue(length > 0);
			assertTrue(index.hasIndexedValue(name, 10));
			// the length is recorded where it takes effect: every impact is normalized with it
			for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
				assertArrayEquals(new int[]{10}, postingsOf(index, name, entry.getKey()).getArray(), entry.getKey());
				final int expectedImpact = FulltextIndex.computeImpact(
					entry.getValue(), length, 25.0
				);
				assertEquals(expectedImpact, Byte.toUnsignedInt(impactsOf(index, name, entry.getKey())[0]));
			}
			assertEquals(frequencies.size(), termCount(index));
		}

		@Test
		@DisplayName("A value is indexed into a field registered with its own pivot, and its impacts use that pivot")
		void shouldAddValueToFieldRegisteredWithCustomPivot() {
			final FulltextIndex index = newIndex();
			final double pivot = 7.0;
			final int body = registerField(index, attribute("body"), pivot);
			index.addValue(attribute("body"), 1, testAnalyzer(), fieldPivot(index, attribute("body")), null,
				"Praha a Praha a Brno");
			index.addValue(attribute("body"), 2, testAnalyzer(), fieldPivot(index, attribute("body")), null,
				new String[]{"Praha", "Brno"});
			assertEquals(
				body, registerField(index, attribute("body")), "a known field is found, whatever its pivot"
			);
			assertEquals(pivot, index.getLengthPivot(body));
			final String praha = termFrequencies("Praha").keySet().iterator().next();
			assertArrayEquals(new int[]{1, 2}, postingsOf(index, body, praha).getArray());
			final int expectedImpact = FulltextIndex.computeImpact(
				termFrequencies("Praha a Praha a Brno").get(praha), positions("Praha a Praha a Brno"), pivot
			);
			assertEquals(expectedImpact, Byte.toUnsignedInt(impactsOf(index, body, praha)[0]));

			index.removeValue(attribute("body"), 1, testAnalyzer(), fieldPivot(index, attribute("body")),
				"Praha a Praha a Brno");
			index.removeValue(attribute("body"), 2, testAnalyzer(), fieldPivot(index, attribute("body")),
				new String[]{"Praha", "Brno"});
			assertEquals(0, termCount(index));
			assertFalse(index.hasIndexedValue(body, 1));
			assertFalse(index.hasIndexedValue(body, 2));
			// a removal never registers a field
			index.removeValue(attribute("unknown"), 1, testAnalyzer(), fieldPivot(index, attribute("unknown")),
				"Praha");
			index.removeValue(attribute("unknown"), 1, testAnalyzer(), fieldPivot(index, attribute("unknown")),
				new String[]{"Praha"});
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, index.getFieldId(attribute("unknown")));
			assertEquals(1, index.getFieldCount());
		}

		@Test
		@DisplayName("Impacts grow with term frequency and shrink with field length, and never reach zero")
		void shouldComputeImpactsMonotonically() {
			final double pivot = 20.0;
			for (int length = 1; length < 2_000; length += 7) {
				int previous = 0;
				for (int frequency = 1; frequency <= Math.min(length, 50); frequency++) {
					final int impact = FulltextIndex.computeImpact(frequency, length, pivot);
					assertTrue(impact >= previous && impact >= 1 && impact <= 255);
					previous = impact;
				}
			}
			for (int frequency = 1; frequency < 10; frequency++) {
				int previous = 256;
				for (int length = frequency; length < 5_000; length += 13) {
					final int impact = FulltextIndex.computeImpact(frequency, length, pivot);
					assertTrue(impact <= previous, "impact rose with length at " + length);
					previous = impact;
				}
			}
			assertEquals(1, FulltextIndex.computeImpact(1, 10_000_000, pivot));
		}

		@Test
		@DisplayName("Removing a value removes its postings and length and leaves other entities untouched")
		void shouldRemoveValue() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"Praha hlavní město");
			index.addValue(attribute("name"), 2, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"Praha a Brno");
			final int name = index.getFieldId(attribute("name"));
			final String praha = termFrequencies("Praha").keySet().iterator().next();
			assertArrayEquals(new int[]{1, 2}, postingsOf(index, name, praha).getArray());

			index.removeValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")),
				"Praha hlavní město");
			assertArrayEquals(new int[]{2}, postingsOf(index, name, praha).getArray());
			assertFalse(index.hasIndexedValue(name, 1));
			assertTrue(index.hasIndexedValue(name, 2));
			for (String term : termFrequencies("hlavní město").keySet()) {
				assertTrue(postingsOf(index, name, term).isEmpty(), term);
			}
			// removing from an unknown field, or a value without tokens, changes nothing
			index.removeValue(attribute("unknown"), 2, testAnalyzer(), fieldPivot(index, attribute("unknown")),
				"Praha");
			index.removeValue(attribute("name"), 2, testAnalyzer(), fieldPivot(index, attribute("name")), "!!!");
			assertArrayEquals(new int[]{2}, postingsOf(index, name, praha).getArray());
			assertTrue(index.hasIndexedValue(name, 2), "A removal without tokens must not erase the length.");
		}

		@Test
		@DisplayName("A second value for the same entity and field is refused until the first is removed")
		void shouldRefuseSecondValueUntilRemoved() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null, "Praha");
			assertThrows(GenericEvitaInternalError.class, () -> index.addValue(attribute("name"), 1,
				testAnalyzer(), fieldPivot(index, attribute("name")), null, "Brno"));
			index.removeValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), "Praha");
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null, "Brno");
			assertEquals(termFrequencies("Brno").size(), termCount(index));
		}

		@Test
		@DisplayName("A value without tokens indexes nothing")
		void shouldIndexNothingForValueWithoutTokens() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null, "!!! ...");
			assertEquals(0, termCount(index));
			assertFalse(index.hasIndexedValue(index.getFieldId(attribute("name")), 1));
		}

		@Test
		@DisplayName("An array value is indexed as the text its elements make one after another")
		void shouldIndexArrayAsOneText() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("keywords"), 1, testAnalyzer(), fieldPivot(index, attribute("keywords")),
				null, new String[]{"horské kolo", "kolo Praha"});
			index.addValue(attribute("keywords"), 2, testAnalyzer(), fieldPivot(index, attribute("keywords")),
				null, "Brno");
			final FulltextIndex expected = newIndex();
			expected.addValue(attribute("keywords"), 1, testAnalyzer(), fieldPivot(expected,
				attribute("keywords")), null, "horské kolo kolo Praha");
			expected.addValue(attribute("keywords"), 2, testAnalyzer(), fieldPivot(expected,
				attribute("keywords")), null, "Brno");
			// summed frequencies (`kolo` twice), summed length, and therefore the very same impacts
			assertEquals(contentOf(expected), contentOf(index));
			final int keywords = index.getFieldId(attribute("keywords"));
			final String bike = termFrequencies("kolo").keySet().iterator().next();
			// the impacts are normalized with the summed length
			assertArrayEquals(
				new byte[]{(byte) FulltextIndex.computeImpact(
					termFrequencies("horské kolo kolo Praha").get(bike),
					positions("horské kolo") + positions("kolo Praha"),
					fieldPivot(index, attribute("keywords"))
				)},
				impactsOf(index, keywords, bike)
			);

			// one entry per entity: a second value is refused in either form
			assertThrows(GenericEvitaInternalError.class, () -> index.addValue(attribute("keywords"), 1,
				testAnalyzer(), fieldPivot(index, attribute("keywords")), null, "Ostrava"));
			assertThrows(
				GenericEvitaInternalError.class, () -> index.addValue(attribute("keywords"), 1, testAnalyzer(),
					fieldPivot(index, attribute("keywords")), null, new String[]{"Ostrava"})
			);

			// the whole array goes, in any element order
			index.removeValue(attribute("keywords"), 1, testAnalyzer(), fieldPivot(index, attribute("keywords")),
				new String[]{"kolo Praha", "horské kolo"});
			final FulltextIndex onlyBrno = newIndex();
			onlyBrno.addValue(attribute("keywords"), 2, testAnalyzer(), fieldPivot(onlyBrno,
				attribute("keywords")), null, "Brno");
			assertEquals(contentOf(onlyBrno), contentOf(index));
		}

		@Test
		@DisplayName("An array without tokens indexes nothing, and an array with a null element is refused")
		void shouldIndexNothingForArrayWithoutTokens() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("keywords"), 1, testAnalyzer(), fieldPivot(index, attribute("keywords")),
				null, new String[0]);
			index.addValue(attribute("keywords"), 2, testAnalyzer(), fieldPivot(index, attribute("keywords")),
				null, new String[]{"!!!", "..."});
			assertEquals(0, termCount(index));
			final int keywords = index.getFieldId(attribute("keywords"));
			assertFalse(index.hasIndexedValue(keywords, 1));
			assertFalse(index.hasIndexedValue(keywords, 2));
			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.addValue(attribute("keywords"), 3, testAnalyzer(), fieldPivot(index,
					attribute("keywords")), null, new String[]{"Praha", null})
			);
			assertEquals(0, termCount(index));

			// an array whose elements but one produce no token is that one element
			index.addValue(attribute("keywords"), 4, testAnalyzer(), fieldPivot(index, attribute("keywords")),
				null, new String[]{"!!!", "Praha", ""});
			final FulltextIndex expected = newIndex();
			expected.addValue(attribute("keywords"), 4, testAnalyzer(), fieldPivot(expected,
				attribute("keywords")), null, "Praha");
			assertEquals(contentOf(expected), contentOf(index));
		}

		@Test
		@DisplayName("Random adds and removes leave the index identical to one rebuilt from the surviving values")
		void shouldMatchRebuildFromScratch() {
			final Random random = new Random(1234);
			final FulltextIndex index = newIndex();
			// field -> primary key -> value currently indexed
			final Map<String, Map<Integer, String>> live = new TreeMap<>();
			final String[] fields = {"name", "description", "keywords"};
			for (String field : fields) {
				// registered up front, so the ids do not depend on which field the steps happen to touch first
				registerField(index, attribute(field));
			}
			applyRandomValueOperations(random, index, live, fields, 4_000);

			final FulltextIndex rebuilt = rebuild(index, live);
			assertEquals(termCount(rebuilt), termCount(index));
			for (String field : fields) {
				final int fieldId = index.getFieldId(attribute(field));
				assertEquals(rebuilt.getFieldId(attribute(field)), fieldId);
				final List<String> rebuiltTerms = new ArrayList<>(64);
				rebuilt.forEachTerm(fieldId, "", (term, postings, impacts) -> rebuiltTerms.add(term));
				final List<String> terms = new ArrayList<>(64);
				index.forEachTerm(fieldId, "", (term, postings, impacts) -> terms.add(term));
				assertEquals(rebuiltTerms, terms, field);
				for (String term : terms) {
					assertArrayEquals(
						postingsOf(rebuilt, fieldId, term).getArray(), postingsOf(index, fieldId, term).getArray(),
						field + "/" + term
					);
					assertArrayEquals(
						impactsOf(rebuilt, fieldId, term), impactsOf(index, fieldId, term), field + "/" + term
					);
				}
			}
			// the lengths, exactly as a flush would persist them
			assertEquals(lengthBlocksOf(rebuilt), lengthBlocksOf(index));
			assertEquals(contentOf(rebuilt), contentOf(index));
		}

	}

	@Nested
	@DisplayName("Transactions")
	@Tag(TRANSACTION)
	class Transactions {

		@Test
		@DisplayName("Writes, a field registration included, are visible in the transaction and published at commit")
		void shouldPublishWritesAtCommit() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"horské kolo");
			final String before = contentOf(index);
			final String bike = termFrequencies("kolo").keySet().iterator().next();
			assertStateAfterCommit(
				index,
				t -> {
					t.addValue(attribute("name"), 2, testAnalyzer(), fieldPivot(t, attribute("name")), null,
						"elektrické kolo");
					t.addValue(attribute("brand"), 1, testAnalyzer(), fieldPivot(t, attribute("brand")), null, "Praha");
					t.removeValue(attribute("name"), 1, testAnalyzer(), fieldPivot(t, attribute("name")),
						"horské kolo");
					assertEquals(1, t.getFieldId(attribute("brand")));
					assertEquals(attribute("brand"), t.getFieldKey(1));
					assertEquals(2, t.getFieldCount());
					assertArrayEquals(new int[]{2}, postingsOf(t, 0, bike).getArray());
					assertTrue(t.hasIndexedValue(1, 1));
				},
				(original, committed) -> {
					assertEquals(before, contentOf(original));
					assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, original.getFieldId(attribute("brand")));
					assertEquals(1, original.getFieldCount());
					final FulltextIndex expected = newIndex();
					expected.addValue(attribute("name"), 2, testAnalyzer(), fieldPivot(expected,
						attribute("name")), null, "elektrické kolo");
					expected.addValue(attribute("brand"), 1, testAnalyzer(), fieldPivot(expected,
						attribute("brand")), null, "Praha");
					assertEquals(contentOf(expected), contentOf(committed));
				}
			);
		}

		@Test
		@DisplayName("A rolled-back transaction leaves the index and its field registry untouched")
		void shouldDiscardWritesOnRollback() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"horské kolo");
			final String before = contentOf(index);
			assertStateAfterRollback(
				index,
				t -> {
					t.addValue(attribute("brand"), 1, testAnalyzer(), fieldPivot(t, attribute("brand")), null, "Praha");
					t.removeValue(attribute("name"), 1, testAnalyzer(), fieldPivot(t, attribute("name")),
						"horské kolo");
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(before, contentOf(original));
					assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, original.getFieldId(attribute("brand")));
				}
			);
		}

		@Test
		@DisplayName("An index the transaction only read is carried forward as the same instance")
		void shouldCarryUntouchedIndexForward() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"horské kolo");
			assertStateAfterCommit(
				index,
				t -> {
					assertEquals(0, registerField(t, attribute("name")));
					t.forEachTerm(0, "", (term, postings, impacts) -> true);
				},
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("Random adds and removes across commits match a rebuild after every commit")
		void shouldMatchRebuildAcrossCommits() {
			final Random random = new Random(4321);
			final Map<String, Map<Integer, String>> live = new TreeMap<>();
			// the later fields are first touched inside a transaction, so their registration is transactional
			final String[] fields = {"name", "description", "keywords", "brand"};
			FulltextIndex index = newIndex();
			applyRandomValueOperations(random, index, live, new String[]{"name"}, 600);
			for (int round = 0; round < 8; round++) {
				final String before = contentOf(index);
				final FulltextIndex[] next = new FulltextIndex[1];
				final String[] seenInTransaction = new String[1];
				assertStateAfterCommit(
					index,
					t -> {
						applyRandomValueOperations(random, t, live, fields, 300);
						// no rebuild in here: a fresh index written inside the transaction would leave layers the
						// commit never sweeps - what the transaction reads is compared with the committed state instead
						seenInTransaction[0] = contentOf(t);
					},
					(original, committed) -> {
						assertEquals(before, contentOf(original));
						final String committedContent = contentOf(committed);
						assertEquals(contentOf(rebuild(committed, live)), committedContent);
						assertEquals(committedContent, seenInTransaction[0], "the transaction read its own writes");
						next[0] = committed;
					}
				);
				index = next[0];
			}
			assertEquals(fields.length, index.getFieldCount());
		}

		@Test
		@DisplayName("A savepoint rollback restores the index, field registry included; a savepoint commit keeps it")
		void shouldRestoreOnSavepointRollback() {
			final Random random = new Random(77);
			final FulltextIndex index = newIndex();
			final Map<String, Map<Integer, String>> live = new TreeMap<>();
			applyRandomValueOperations(random, index, live, new String[]{"name", "description"}, 400);
			// each helper runs its own transaction against the untouched original, so each starts from its own copy
			final Map<String, Map<Integer, String>> rolledBack = copyOf(live);
			assertSavepointRollbackRestores(
				index,
				t -> applyRandomValueOperations(random, t, rolledBack, new String[]{"name", "keywords"}, 100),
				FulltextIndexTest::contentOf,
				// a field first used inside the savepoint must be unregistered by its rollback
				t -> applyRandomValueOperations(random, t, copyOf(rolledBack), new String[]{"name", "brand"}, 100)
			);
			final Map<String, Map<Integer, String>> scratch = copyOf(live);
			assertSavepointCommitKeeps(
				index,
				t -> applyRandomValueOperations(random, t, scratch, new String[]{"name"}, 100),
				FulltextIndexTest::contentOf,
				t -> applyRandomValueOperations(random, t, scratch, new String[]{"description", "brand"}, 100)
			);
		}

		@Test
		@DisplayName("A retirement is visible in the transaction and published at commit, the original untouched")
		void shouldPublishRetirementAtCommit() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"horské kolo");
			index.addValue(attribute("description"), 1, testAnalyzer(), fieldPivot(index,
				attribute("description")), null, "Praha");
			final String before = contentOf(index);
			final String bike = termFrequencies("kolo").keySet().iterator().next();
			assertStateAfterCommit(
				index,
				t -> {
					assertTrue(t.retireField(attribute("name")));
					assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, t.getFieldId(attribute("name")));
					assertTrue(t.isFieldRetired(0));
					assertFalse(t.retireField(attribute("name")));
					t.addValue(attribute("name"), 2, testAnalyzer(), fieldPivot(t, attribute("name")), null,
						"elektrické kolo");
					assertEquals(2, t.getFieldId(attribute("name")));
					// a field the transaction registered is retired, and re-added, as well
					t.addValue(attribute("brand"), 3, testAnalyzer(), fieldPivot(t, attribute("brand")), null, "Brno");
					assertTrue(t.retireField(attribute("brand")));
					assertTrue(t.isFieldRetired(3));
					assertEquals(4, registerField(t, attribute("brand")));
				},
				(original, committed) -> {
					assertEquals(before, contentOf(original));
					assertEquals(0, original.getFieldId(attribute("name")));
					assertFalse(original.isFieldRetired(0));

					assertEquals(5, committed.getFieldCount());
					assertTrue(committed.isFieldRetired(0));
					assertFalse(committed.isFieldRetired(1));
					assertFalse(committed.isFieldRetired(2));
					assertTrue(committed.isFieldRetired(3));
					assertFalse(committed.isFieldRetired(4));
					assertEquals(2, committed.getFieldId(attribute("name")));
					assertEquals(4, committed.getFieldId(attribute("brand")));
					assertArrayEquals(new int[]{1}, postingsOf(committed, 0, bike).getArray());
					assertArrayEquals(new int[]{2}, postingsOf(committed, 2, bike).getArray());
				}
			);
		}

		@Test
		@DisplayName("A rolled-back retirement leaves the field in service")
		void shouldDiscardRetirementOnRollback() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"horské kolo");
			final String before = contentOf(index);
			assertStateAfterRollback(
				index,
				t -> {
					assertTrue(t.retireField(attribute("name")));
					t.addValue(attribute("name"), 2, testAnalyzer(), fieldPivot(t, attribute("name")), null,
						"elektrické kolo");
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(before, contentOf(original));
					assertEquals(0, original.getFieldId(attribute("name")));
					assertFalse(original.isFieldRetired(0));
				}
			);
		}

		@Test
		@DisplayName("A savepoint rollback restores the retirements made in it; a savepoint commit keeps them")
		void shouldRestoreRetirementOnSavepointRollback() {
			final FulltextIndex index = newIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(index, attribute("name")), null,
				"horské kolo");
			index.addValue(attribute("description"), 1, testAnalyzer(), fieldPivot(index,
				attribute("description")), null, "Praha");
			assertSavepointRollbackRestores(
				index,
				t -> {
					t.addValue(attribute("keywords"), 2, testAnalyzer(), fieldPivot(t, attribute("keywords")),
						null, "hrad");
					assertTrue(t.retireField(attribute("description")));
				},
				FulltextIndexTest::registryOf,
				t -> {
					assertTrue(t.retireField(attribute("name")));
					t.addValue(attribute("name"), 3, testAnalyzer(), fieldPivot(t, attribute("name")), null, "most");
					assertTrue(t.retireField(attribute("keywords")));
					t.addValue(attribute("brand"), 4, testAnalyzer(), fieldPivot(t, attribute("brand")), null, "Brno");
					assertTrue(t.retireField(attribute("brand")));
				}
			);
			assertSavepointCommitKeeps(
				index,
				t -> assertTrue(t.retireField(attribute("description"))),
				FulltextIndexTest::registryOf,
				t -> {
					assertTrue(t.retireField(attribute("name")));
					t.addValue(attribute("name"), 3, testAnalyzer(), fieldPivot(t, attribute("name")), null, "most");
				}
			);
		}

		@Test
		@DisplayName("A warm-up savepoint rollback restores the retirements made in it")
		void shouldRestoreRetirementOnWarmUpSavepointRollback() {
			final FulltextIndex index = newIndex();
			assertWarmUpSavepointRollbackRestores(
				index,
				t -> {
					t.addValue(attribute("name"), 1, testAnalyzer(), fieldPivot(t, attribute("name")), null,
						"horské kolo");
					t.addValue(attribute("description"), 1, testAnalyzer(), fieldPivot(t,
						attribute("description")), null, "Praha");
					assertTrue(t.retireField(attribute("description")));
				},
				FulltextIndexTest::registryOf,
				t -> {
					assertTrue(t.retireField(attribute("name")));
					t.addValue(attribute("name"), 3, testAnalyzer(), fieldPivot(t, attribute("name")), null, "most");
					t.addValue(attribute("brand"), 4, testAnalyzer(), fieldPivot(t, attribute("brand")), null, "Brno");
					assertTrue(t.retireField(attribute("brand")));
					t.addValue(attribute("description"), 5, testAnalyzer(), fieldPivot(t,
						attribute("description")), null, "řeka");
				}
			);
			assertEquals(0, index.getFieldId(attribute("name")));
			assertFalse(index.isFieldRetired(0));
			assertTrue(index.isFieldRetired(1));
			assertEquals(2, index.getFieldCount());
			// the restored registry keeps working
			assertTrue(index.retireField(attribute("name")));
			assertEquals(2, registerField(index, attribute("name")));
		}

		@Test
		@DisplayName("A warm-up savepoint rollback restores the index, field registry included")
		void shouldRestoreOnWarmUpSavepointRollback() {
			final Random random = new Random(78);
			final FulltextIndex index = newIndex();
			final Map<String, Map<Integer, String>> live = new TreeMap<>();
			assertWarmUpSavepointRollbackRestores(
				index,
				t -> applyRandomValueOperations(random, t, live, new String[]{"name", "description"}, 400),
				FulltextIndexTest::contentOf,
				t -> applyRandomValueOperations(random, t, copyOf(live), new String[]{"name", "brand"}, 200)
			);
			assertEquals(FulltextIndex.UNKNOWN_FIELD_ID, index.getFieldId(attribute("brand")));
			// the restored index keeps working: the next registration takes the id the rolled-back one had
			assertEquals(2, registerField(index, attribute("brand")));
		}

	}

	/**
	 * Applies random value replacements, additions and removals to an index and to the model of what it holds.
	 *
	 * @param random the source of randomness
	 * @param index  the index under test
	 * @param live   field to primary key to the value currently indexed; updated alongside the index
	 * @param fields the fields to draw from
	 * @param steps  how many steps to take
	 */
	private static void applyRandomValueOperations(
		@Nonnull Random random,
		@Nonnull FulltextIndex index,
		@Nonnull Map<String, Map<Integer, String>> live,
		@Nonnull String[] fields,
		int steps
	) {
		for (int step = 0; step < steps; step++) {
			final String field = fields[random.nextInt(fields.length)];
			final int primaryKey = random.nextInt(PK_BOUND);
			final Map<Integer, String> values = live.computeIfAbsent(field, f -> new TreeMap<>());
			final String current = values.get(primaryKey);
			if (current != null) {
				if (current.indexOf(ELEMENT_SEPARATOR) >= 0) {
					// an array is removed whole, and in any element order
					final List<String> elements = new ArrayList<>(List.of(splitElements(current)));
					Collections.shuffle(elements, random);
					index.removeValue(attribute(field), primaryKey, testAnalyzer(), fieldPivot(index,
						attribute(field)), elements.toArray(String[]::new));
				} else {
					index.removeValue(attribute(field), primaryKey, testAnalyzer(), fieldPivot(index,
						attribute(field)), current);
				}
				values.remove(primaryKey);
			}
			if (current == null || random.nextBoolean()) {
				// one value in four is an array of two or three elements
				final int elements = random.nextInt(4) == 0 ? 2 + random.nextInt(2) : 1;
				final StringBuilder value = new StringBuilder(64);
				for (int e = 0; e < elements; e++) {
					if (e > 0) {
						value.append(ELEMENT_SEPARATOR);
					}
					final int words = 1 + random.nextInt(elements == 1 ? 12 : 5);
					for (int w = 0; w < words; w++) {
						value.append(VOCABULARY[random.nextInt(VOCABULARY.length)]).append(' ');
					}
				}
				final String modelled = value.toString();
				if (elements == 1) {
					index.addValue(attribute(field), primaryKey, testAnalyzer(), fieldPivot(index,
						attribute(field)), null, modelled);
				} else {
					index.addValue(attribute(field), primaryKey, testAnalyzer(), fieldPivot(index,
						attribute(field)), null, splitElements(modelled));
				}
				values.put(primaryKey, modelled);
			}
		}
	}

	/**
	 * Builds a fresh index holding exactly the modelled values, with the fields registered in the order - and with the
	 * pivots - the reference index registered them, so the two assign the same ids. An array value is indexed as the
	 * single text its elements make when written one after another, which is what the array semantics promise - so
	 * the rebuild is an independent oracle of them, not a replay of the array API.
	 *
	 * @param reference the index whose field registry to copy
	 * @param live      field to primary key to value
	 * @return the rebuilt index
	 */
	@Nonnull
	private static FulltextIndex rebuild(
		@Nonnull FulltextIndex reference,
		@Nonnull Map<String, Map<Integer, String>> live
	) {
		final FulltextIndex rebuilt = newIndex();
		for (int fieldId = 0; fieldId < reference.getFieldCount(); fieldId++) {
			registerField(rebuilt, reference.getFieldKey(fieldId), reference.getLengthPivot(fieldId));
		}
		for (Map.Entry<String, Map<Integer, String>> entry : live.entrySet()) {
			for (Map.Entry<Integer, String> value : entry.getValue().entrySet()) {
				rebuilt.addValue(attribute(entry.getKey()), value.getKey(), testAnalyzer(), fieldPivot(rebuilt,
					attribute(entry.getKey())), null, value.getValue().replace(ELEMENT_SEPARATOR, ' '));
			}
		}
		return rebuilt;
	}

	/**
	 * Splits a modelled array value into its elements.
	 *
	 * @param modelled the value as the model holds it, elements separated by {@link #ELEMENT_SEPARATOR}
	 * @return the elements
	 */
	@Nonnull
	private static String[] splitElements(@Nonnull String modelled) {
		return modelled.split("\\" + ELEMENT_SEPARATOR);
	}

	/**
	 * Deep-copies the value model, so a step sequence that is going to be rolled back cannot corrupt the original.
	 *
	 * @param live field to primary key to value
	 * @return an independent copy
	 */
	@Nonnull
	private static Map<String, Map<Integer, String>> copyOf(@Nonnull Map<String, Map<Integer, String>> live) {
		final Map<String, Map<Integer, String>> copy = new TreeMap<>();
		for (Map.Entry<String, Map<Integer, String>> entry : live.entrySet()) {
			copy.put(entry.getKey(), new TreeMap<>(entry.getValue()));
		}
		return copy;
	}

	/**
	 * Dumps everything an index holds, as the caller's transaction sees it: every field with its pivot, every term with
	 * its postings and their impacts in posting order, every entity with a value indexed, and the dictionary size. The
	 * lengths themselves show in the impacts normalized with them; {@link #lengthBlocksOf} compares them as persisted.
	 *
	 * @param index the index to dump
	 * @return the dump, comparable with `equals`
	 */
	@Nonnull
	private static String contentOf(@Nonnull FulltextIndex index) {
		final StringBuilder content = new StringBuilder(8_192);
		for (int fieldId = 0; fieldId < index.getFieldCount(); fieldId++) {
			content.append(index.getFieldKey(fieldId)).append('@').append(index.getLengthPivot(fieldId))
				.append(index.isFieldRetired(fieldId) ? " retired\n" : "\n");
			final int theFieldId = fieldId;
			index.forEachTerm(fieldId, "", (term, postings, view) -> {
				content.append(term).append(':');
				final byte[] impacts = view.toArray();
				// the walk of the field reads the impacts off the leaf it stands on; they must be the ones a lookup of
				// the term alone finds
				assertArrayEquals(impactsOf(index, theFieldId, term), impacts, "walk impacts of " + term);
				final OfInt it = postings.iterator();
				int ordinal = 0;
				while (it.hasNext()) {
					content.append(' ').append(it.nextInt()).append('/').append(impacts[ordinal++] & 0xFF);
				}
				assertEquals(ordinal, impacts.length, "impacts misaligned with the postings of " + term);
				content.append('\n');
				return true;
			});
			content.append("indexed:");
			for (int primaryKey = 0; primaryKey < PK_BOUND; primaryKey++) {
				if (index.hasIndexedValue(fieldId, primaryKey)) {
					content.append(' ').append(primaryKey);
				}
			}
			content.append('\n');
		}
		return content.append("terms ").append(termCount(index)).toString();
	}

	/**
	 * Renders the field lengths of an index exactly as its next flush persists them: the block list of every field and
	 * every block written, with the encoded length of each entity it holds. Collecting them is what a flush does, so
	 * an index is rendered once per flush; an index never flushed writes every block it holds.
	 *
	 * @param index the index to render
	 * @return the rendering, comparable with `equals`
	 */
	@Nonnull
	private static String lengthBlocksOf(@Nonnull FulltextIndex index) {
		final StringBuilder blocks = new StringBuilder(4_096);
		final LengthBlockEmission[] emissions = index.collectChangedLengthBlocks();
		for (int fieldId = 0; fieldId < emissions.length; fieldId++) {
			blocks.append("field ").append(fieldId).append(' ')
				.append(Arrays.toString(emissions[fieldId].blockKeys())).append('\n');
			for (final LengthBlock block : emissions[fieldId].changedBlocks()) {
				blocks.append(block.blockKey()).append(':');
				for (int i = 0; i < block.lows().length; i++) {
					blocks.append(' ').append((int) block.lows()[i]).append('/')
						.append(Byte.toUnsignedInt(block.lengths()[i]));
				}
				blocks.append('\n');
			}
		}
		return blocks.toString();
	}

	/**
	 * Computes the impact of a posting in a field of the default pivot, as the index stores it.
	 *
	 * @param termFrequency how many times the term occurs in the value
	 * @param length        the value's length in tokens
	 * @return the impact byte
	 */
	private static byte impact(int termFrequency, int length) {
		return (byte) FulltextIndex.computeImpact(termFrequency, length, 25.0);
	}

	/**
	 * Dumps an index together with what its keys resolve to - the part of the field registry the content dump cannot
	 * show, because a retired field and its successor carry one key.
	 *
	 * @param index the index to dump
	 * @return the dump, comparable with `equals`
	 */
	@Nonnull
	private static String registryOf(@Nonnull FulltextIndex index) {
		final StringBuilder registry = new StringBuilder(8_192).append(contentOf(index));
		for (final String name : new String[]{"name", "description", "keywords", "brand"}) {
			registry.append('\n').append(name).append(" -> ").append(index.getFieldId(attribute(name)));
		}
		return registry.toString();
	}

	/**
	 * Analyzes a value with the test's analyzer and counts its distinct terms.
	 *
	 * @param value the value
	 * @return each distinct term with its frequency
	 */
	@Nonnull
	private static Map<String, Integer> termFrequencies(@Nonnull String value) {
		final Map<String, Integer> frequencies = new HashMap<>();
		for (AnalyzedTerm term : collectTerms(analyzer, value)) {
			frequencies.merge(term.term(), 1, Integer::sum);
		}
		return frequencies;
	}

	/**
	 * Counts the token positions of a value with the test's analyzer.
	 *
	 * @param value the value
	 * @return the number of terms arriving at a positive position increment
	 */
	private static int positions(@Nonnull String value) {
		int positions = 0;
		for (AnalyzedTerm term : collectTerms(analyzer, value)) {
			if (term.positionIncrement() > 0) {
				positions++;
			}
		}
		return positions;
	}

	/**
	 * Asserts a term's postings and impacts equal the expected map.
	 *
	 * @param expected expected impact by primary key
	 * @param index    the index
	 * @param fieldId  the field
	 * @param term     the term
	 */
	private static void assertPostingsEqual(
		@Nonnull TreeMap<Integer, Integer> expected,
		@Nonnull FulltextIndex index,
		int fieldId,
		@Nonnull String term
	) {
		final int[] postings = postingsOf(index, fieldId, term).getArray();
		final byte[] impacts = impactsOf(index, fieldId, term);
		assertEquals(expected.size(), postings.length);
		assertEquals(expected.size(), impacts.length);
		int i = 0;
		for (Map.Entry<Integer, Integer> entry : expected.entrySet()) {
			assertEquals(entry.getKey(), postings[i]);
			assertEquals(entry.getValue(), Byte.toUnsignedInt(impacts[i]), "impact of " + entry.getKey());
			i++;
		}
	}

	/**
	 * Asserts a walk over the field with the prefix yields exactly the expected terms and postings, in order.
	 *
	 * @param expected expected terms with their postings, in term order
	 * @param index    the index to walk
	 * @param fieldId  the field to walk
	 * @param prefix   the term prefix
	 */
	private static void assertWalkEquals(
		@Nonnull Map<String, TreeSet<Integer>> expected,
		@Nonnull FulltextIndex index,
		int fieldId,
		@Nonnull String prefix
	) {
		final List<String> terms = new ArrayList<>(expected.size());
		final List<int[]> postings = new ArrayList<>(expected.size());
		index.forEachTerm(fieldId, prefix, (term, termPostings, termImpacts) -> {
			terms.add(term);
			postings.add(termPostings.getArray());
			return true;
		});
		assertEquals(new ArrayList<>(expected.keySet()), terms, "field " + fieldId + ", prefix `" + prefix + "`");
		int i = 0;
		for (TreeSet<Integer> expectedPostings : expected.values()) {
			final int[] expectedArray = new int[expectedPostings.size()];
			int j = 0;
			for (Integer primaryKey : expectedPostings) {
				expectedArray[j++] = primaryKey;
			}
			assertArrayEquals(expectedArray, postings.get(i), terms.get(i));
			i++;
		}
	}

	/**
	 * Generates a short term over an alphabet that includes a diacritic, so the walk crosses non-ASCII keys.
	 *
	 * @param random the source of randomness
	 * @return a term of one to four characters
	 */
	@Nonnull
	private static String randomTerm(@Nonnull Random random) {
		final String alphabet = "abcčz";
		final int length = 1 + random.nextInt(4);
		final StringBuilder term = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			term.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return term.toString();
	}

}
