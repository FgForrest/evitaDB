/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextIndexModel;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Generational property-based stress test for {@link GlobalEntityIndex}.
 * Runs randomized operations (PK insert/remove, locale upsert/remove) over multiple
 * generations, comparing committed state against a JDK reference implementation.
 *
 * The operations also reach the per-locale fulltext indexes the global index owns: a write creates the locale's
 * index on first use and indexes, replaces or removes values in it, registering and retiring fields as it goes, and
 * a drop removes the locale's index whole. Two locales with different analyzers take part. Each fulltext index is
 * checked against a {@link FulltextIndexModel} of its locale, so a fulltext index created, written and dropped inside
 * one transaction, nested in the global index's transactional map, has to commit, roll back and restore together
 * with everything else the global index holds.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("GlobalEntityIndex generational proof")
@Tag(INDEXING)
@Tag(MANAGEMENT)
class LongRunningGlobalEntityIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "Product";
	private static final int INDEX_PK = 1;

	private static final Locale[] TEST_LOCALES = {
		Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, new Locale("cs")
	};

	/**
	 * The locales the global index keeps fulltext indexes for; their analyzers differ.
	 */
	static final Locale[] FULLTEXT_LOCALES = {new Locale("cs"), Locale.ENGLISH};
	/**
	 * The field keys the fulltext writes draw from.
	 */
	static final FulltextFieldKey[] FULLTEXT_KEYS = {
		FulltextFieldKey.attribute("name"), FulltextFieldKey.referenceAttribute("brand", "label")
	};
	/**
	 * The primary keys the fulltext writes draw from.
	 */
	static final int[] FULLTEXT_PRIMARY_KEYS = fulltextPrimaryKeys();
	/**
	 * No fulltext field is retired once a locale's index holds this many fields.
	 */
	private static final int MAX_FULLTEXT_FIELD_COUNT = 6;

	/**
	 * Registry providing the analyzers of {@link #FULLTEXT_LOCALES}.
	 */
	private static FulltextAnalyzerRegistry registry;
	/**
	 * The index-slot analyzer of every locale of {@link #FULLTEXT_LOCALES}.
	 */
	private static Map<Locale, FulltextAnalyzer> analyzers;

	@BeforeAll
	static void setUpAnalyzers() {
		registry = new FulltextAnalyzerRegistry();
		analyzers = indexAnalyzersOf(registry);
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	@Nonnull
	private static GlobalEntityIndex createInstance() {
		return new GlobalEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
	}

	@Nonnull
	private static EntitySchemaContract createEvolvingSchema() {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getLocales()).thenReturn(Set.of());
		when(schema.getEvolutionMode()).thenReturn(EnumSet.of(EvolutionMode.ADDING_LOCALES));
		return schema;
	}

	@DisplayName("survives generational randomized test")
	@ParameterizedTest(name = "GlobalEntityIndex should survive generational randomized test")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final EntitySchemaContract schema = createEvolvingSchema();

		runFor(
			input,
			50_000,
			new GenerationalState(
				new HashSet<>(),
				new HashMap<>(),
				new HashMap<>(),
				createInstance()
			),
			(random, state) -> {
				final GlobalEntityIndex tested = state.index();
				final Set<Integer> referencePks = new HashSet<>(state.expectedPks());
				final Map<Locale, Set<Integer>> referenceLocales = deepCopyLocaleMap(state.expectedLocales());
				final Map<Locale, FulltextIndexModel> referenceFulltext = deepCopyFulltextMap(state.expectedFulltext());
				final GlobalSnapshot before = snapshot(tested);
				final AtomicReference<GlobalEntityIndex> committedRef = new AtomicReference<>();

				assertStateAfterCommit(
					tested,
					original -> applyRandomBatch(
						random, original, referencePks, referenceLocales, referenceFulltext, analyzers, schema
					),
					(original, committed) -> {
						assertNotNull(committed);
						verifyState(committed, referencePks, referenceLocales, referenceFulltext);
						// the previous version shares the untouched fulltext indexes with the published one
						assertEquals(before, snapshot(original), "The version that was written to changed!");
						committedRef.set(committed);
					}
				);

				return new GenerationalState(
					referencePks, referenceLocales, referenceFulltext, committedRef.get()
				);
			},
			(state, exc) -> {
				System.out.println("Failed state - PKs: " + state.expectedPks());
				System.out.println("Failed state - Locales: " + state.expectedLocales());
				System.out.println("Failed state - Fulltext locales: " + state.expectedFulltext().keySet());
			}
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index
	 * from the (random-walking) reference model, captures a value oracle of that base, applies a random batch of
	 * insert/remove mutations inside a transaction that is then rolled back, and asserts the base index is unchanged and
	 * no committed value was published.
	 */
	@DisplayName("rollback leaves the base index intact")
	@ParameterizedTest(name = "GlobalEntityIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		final EntitySchemaContract schema = createEvolvingSchema();

		runFor(
			input,
			50_000,
			new RollbackModel(new HashSet<>(), new HashMap<>(), new HashMap<>()),
			(random, model) -> {
				final Set<Integer> referencePks = new HashSet<>(model.expectedPks());
				final Map<Locale, Set<Integer>> referenceLocales =
					deepCopyLocaleMap(model.expectedLocales());
				final Map<Locale, FulltextIndexModel> referenceFulltext =
					deepCopyFulltextMap(model.expectedFulltext());
				// rebuild a fresh base index from the (random-walking) reference model each generation
				final GlobalEntityIndex tested = buildIndexFromModel(
					referencePks, referenceLocales, referenceFulltext, schema
				);
				verifyState(tested, referencePks, referenceLocales, referenceFulltext);
				// value oracle of the base state that the rollback must return to
				final GlobalSnapshot beforeRollback = snapshot(tested);

				assertStateAfterRollback(
					tested,
					original -> applyRandomBatch(
						random, original, referencePks, referenceLocales, referenceFulltext, analyzers, schema
					),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!"
						);
						assertEquals(beforeRollback, snapshot(original),
							"GlobalEntityIndex changed after rollback — atomic rollback leaked!"
						);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation rebuilds from a
				// different live state — a random walk that keeps the proof exploring fresh base indexes
				return new RollbackModel(referencePks, referenceLocales, referenceFulltext);
			},
			(model, exc) -> {
				System.out.println("Failed model - PKs: " + model.expectedPks());
				System.out.println("Failed model - Locales: " + model.expectedLocales());
			}
		);
	}

	/**
	 * Applies a random batch of 1–5 insert/remove operations to `index`, mirroring each into the reference model so the
	 * two stay in lockstep. Shared by the commit and rollback proofs so both drive the identical random-draw sequence.
	 *
	 * @param random            source of randomness
	 * @param index             the index being mutated
	 * @param referencePks      the reference model for primary keys
	 * @param referenceLocales  the reference model for locale-to-PK mapping
	 * @param referenceFulltext the reference model of every fulltext index, by locale
	 * @param analyzers         the index-slot analyzer of every fulltext locale
	 * @param schema            the entity schema allowing locale evolution
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull GlobalEntityIndex index,
		@Nonnull Set<Integer> referencePks,
		@Nonnull Map<Locale, Set<Integer>> referenceLocales,
		@Nonnull Map<Locale, FulltextIndexModel> referenceFulltext,
		@Nonnull Map<Locale, FulltextAnalyzer> analyzers,
		@Nonnull EntitySchemaContract schema
	) {
		final int ops = random.nextInt(5) + 1;
		for (int i = 0; i < ops; i++) {
			executeRandomOperation(
				random, index, referencePks, referenceLocales, referenceFulltext, analyzers, schema
			);
		}
	}

	/**
	 * Builds a fresh {@link GlobalEntityIndex} whose logical content exactly matches the reference model, so a snapshot
	 * taken right after the build equals the model. Used to seed each rollback generation from the walking model.
	 *
	 * @param referencePks      the reference primary keys to insert
	 * @param referenceLocales  the reference locale-to-PK mapping to replay
	 * @param referenceFulltext the reference model of every fulltext index, by locale, to replay
	 * @param schema            the entity schema allowing locale evolution
	 * @return a freshly built index materialising the reference model
	 */
	@Nonnull
	private static GlobalEntityIndex buildIndexFromModel(
		@Nonnull Set<Integer> referencePks,
		@Nonnull Map<Locale, Set<Integer>> referenceLocales,
		@Nonnull Map<Locale, FulltextIndexModel> referenceFulltext,
		@Nonnull EntitySchemaContract schema
	) {
		final GlobalEntityIndex index = createInstance();
		for (final int pk : referencePks) {
			index.insertPrimaryKeyIfMissing(pk);
		}
		for (final Map.Entry<Locale, Set<Integer>> entry : referenceLocales.entrySet()) {
			for (final int pk : entry.getValue()) {
				index.upsertLanguage(entry.getKey(), pk, schema);
			}
		}
		for (final Map.Entry<Locale, FulltextIndexModel> entry : referenceFulltext.entrySet()) {
			entry.getValue().replayInto(
				index.getOrCreateFulltextIndex(entry.getKey(), analyzers.get(entry.getKey()))
			);
		}
		return index;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot (primary keys, per-locale record
	 * ids and the content of every fulltext index), so two snapshots taken before and after a rollback can be compared
	 * with `.equals` to prove exact restoration.
	 *
	 * @param index the index to snapshot
	 * @return a deeply `.equals`-comparable value snapshot of the index content
	 */
	@Nonnull
	static GlobalSnapshot snapshot(@Nonnull GlobalEntityIndex index) {
		final List<Integer> pks = toList(index.getAllPrimaryKeys());
		final Map<Locale, List<Integer>> locales = new HashMap<>();
		for (final Locale locale : index.getLanguages()) {
			locales.put(locale, toList(index.getRecordsWithLanguageFormula(locale).compute()));
		}
		final Map<Locale, String> fulltext = new HashMap<>();
		for (final Locale locale : index.getFulltextIndexLocales()) {
			final FulltextIndex fulltextIndex = index.getFulltextIndex(locale);
			assertNotNull(fulltextIndex, "A listed fulltext locale resolves to no index: " + locale);
			fulltext.put(
				locale, FulltextIndexModel.contentOf(fulltextIndex, FULLTEXT_KEYS, FULLTEXT_PRIMARY_KEYS)
			);
		}
		return new GlobalSnapshot(pks, locales, fulltext);
	}

	/**
	 * Converts a bitmap into an ascending list of its record ids (a value type with deep `.equals`).
	 *
	 * @param bitmap the bitmap to convert
	 * @return an ascending list of the bitmap's record ids
	 */
	@Nonnull
	private static List<Integer> toList(@Nonnull Bitmap bitmap) {
		final int[] array = bitmap.getArray();
		final List<Integer> list = new ArrayList<>(array.length);
		for (final int value : array) {
			list.add(value);
		}
		return list;
	}

	/**
	 * Executes a random operation on both the index and the reference model.
	 *
	 * @param random            source of randomness
	 * @param index             the index being tested
	 * @param referencePks      the reference model for primary keys
	 * @param referenceLocales  the reference model for locale-to-PK mapping
	 * @param referenceFulltext the reference model of every fulltext index, by locale
	 * @param analyzers         the index-slot analyzer of every fulltext locale
	 * @param schema            the entity schema allowing locale evolution
	 */
	private static void executeRandomOperation(
		@Nonnull Random random,
		@Nonnull GlobalEntityIndex index,
		@Nonnull Set<Integer> referencePks,
		@Nonnull Map<Locale, Set<Integer>> referenceLocales,
		@Nonnull Map<Locale, FulltextIndexModel> referenceFulltext,
		@Nonnull Map<Locale, FulltextAnalyzer> analyzers,
		@Nonnull EntitySchemaContract schema
	) {
		final int operation = random.nextInt(8);
		final int pk = random.nextInt(50) + 1;

		switch (operation) {
			case 0 -> {
				// insert PK
				index.insertPrimaryKeyIfMissing(pk);
				referencePks.add(pk);
			}
			case 1 -> {
				// remove PK (only if exists in reference)
				if (!referencePks.isEmpty()) {
					final int targetPk = referencePks.iterator().next();
					index.removePrimaryKey(targetPk);
					referencePks.remove(targetPk);
				}
			}
			case 2 -> {
				// upsert language
				final Locale locale = TEST_LOCALES[random.nextInt(TEST_LOCALES.length)];
				index.upsertLanguage(locale, pk, schema);
				referenceLocales.computeIfAbsent(locale, l -> new HashSet<>()).add(pk);
			}
			case 3 -> {
				// remove language (only if exists)
				if (!referenceLocales.isEmpty()) {
					final Locale locale = referenceLocales.keySet().iterator().next();
					final Set<Integer> pksForLocale = referenceLocales.get(locale);
					if (!pksForLocale.isEmpty()) {
						final int targetPk = pksForLocale.iterator().next();
						index.removeLanguage(locale, targetPk);
						pksForLocale.remove(targetPk);
						if (pksForLocale.isEmpty()) {
							referenceLocales.remove(locale);
						}
					}
				}
			}
			case 4, 5, 6 -> writeFulltext(random, index, referenceFulltext, analyzers);
			case 7 -> dropFulltext(random, index, referenceFulltext);
			default -> throw new GenericEvitaInternalError("Unexpected random operation: " + operation);
		}
	}

	/**
	 * Writes a few random fulltext steps into the fulltext index of a random locale, creating the index when the
	 * locale has none, and mirrors them into the locale's model.
	 *
	 * @param random            source of randomness
	 * @param index             the index being tested
	 * @param referenceFulltext the reference model of every fulltext index, by locale
	 * @param analyzers         the index-slot analyzer of every fulltext locale
	 */
	static void writeFulltext(
		@Nonnull Random random,
		@Nonnull GlobalEntityIndex index,
		@Nonnull Map<Locale, FulltextIndexModel> referenceFulltext,
		@Nonnull Map<Locale, FulltextAnalyzer> analyzers
	) {
		final Locale locale = FULLTEXT_LOCALES[random.nextInt(FULLTEXT_LOCALES.length)];
		final FulltextAnalyzer analyzer = analyzers.get(locale);
		final FulltextIndex fulltextIndex = index.getOrCreateFulltextIndex(locale, analyzer);
		final FulltextIndexModel model = referenceFulltext.computeIfAbsent(
			locale, l -> new FulltextIndexModel(analyzer, FULLTEXT_KEYS, FULLTEXT_PRIMARY_KEYS)
		);
		model.applyRandomOperations(
			fulltextIndex, random, 1 + random.nextInt(4), MAX_FULLTEXT_FIELD_COUNT, new StringBuilder(256)
		);
	}

	/**
	 * Drops the fulltext index of a random locale that has one, and its model.
	 *
	 * @param random            source of randomness
	 * @param index             the index being tested
	 * @param referenceFulltext the reference model of every fulltext index, by locale
	 */
	static void dropFulltext(
		@Nonnull Random random,
		@Nonnull GlobalEntityIndex index,
		@Nonnull Map<Locale, FulltextIndexModel> referenceFulltext
	) {
		final Locale locale = FULLTEXT_LOCALES[random.nextInt(FULLTEXT_LOCALES.length)];
		if (referenceFulltext.remove(locale) != null) {
			index.removeFulltextIndex(locale);
		}
	}

	/**
	 * Resolves the index-slot analyzer of every fulltext locale.
	 *
	 * @param registry the registry to resolve them from
	 * @return the analyzer of every locale of {@link #FULLTEXT_LOCALES}
	 */
	@Nonnull
	static Map<Locale, FulltextAnalyzer> indexAnalyzersOf(@Nonnull FulltextAnalyzerRegistry registry) {
		final Map<Locale, FulltextAnalyzer> analyzers = CollectionUtils.createHashMap(FULLTEXT_LOCALES.length);
		for (final Locale locale : FULLTEXT_LOCALES) {
			analyzers.put(locale, registry.getIndexAnalyzer(ENTITY_TYPE, locale));
		}
		return analyzers;
	}

	/**
	 * Builds the primary keys the fulltext writes draw from.
	 *
	 * @return the primary keys `1..50`, the range the other operations use
	 */
	@Nonnull
	private static int[] fulltextPrimaryKeys() {
		final int[] primaryKeys = new int[50];
		for (int i = 0; i < primaryKeys.length; i++) {
			primaryKeys[i] = i + 1;
		}
		return primaryKeys;
	}

	/**
	 * Verifies that the committed index state matches the reference model.
	 *
	 * @param committed        the committed index after STM transaction
	 * @param expectedPks      the expected set of primary keys
	 * @param expectedLocales  the expected locale-to-PK mapping
	 * @param expectedFulltext the expected fulltext indexes, by locale
	 */
	private static void verifyState(
		@Nonnull GlobalEntityIndex committed,
		@Nonnull Set<Integer> expectedPks,
		@Nonnull Map<Locale, Set<Integer>> expectedLocales,
		@Nonnull Map<Locale, FulltextIndexModel> expectedFulltext
	) {
		// verify PKs
		final Bitmap allPks = committed.getAllPrimaryKeys();
		assertEquals(expectedPks.size(), allPks.size(),
			"PK count mismatch. Expected: " + expectedPks + ", got bitmap size: " + allPks.size()
		);
		for (int pk : expectedPks) {
			assertTrue(allPks.contains(pk), "Missing PK: " + pk);
		}

		// verify locales
		assertEquals(expectedLocales.size(), committed.getLanguages().size(),
			"Locale count mismatch"
		);
		for (Map.Entry<Locale, Set<Integer>> entry : expectedLocales.entrySet()) {
			final Locale locale = entry.getKey();
			final Set<Integer> expectedPksForLocale = entry.getValue();
			final Bitmap localeBitmap = committed.getRecordsWithLanguageFormula(locale).compute();
			assertEquals(expectedPksForLocale.size(), localeBitmap.size(),
				"PK count mismatch for locale " + locale
			);
			for (int pk : expectedPksForLocale) {
				assertTrue(localeBitmap.contains(pk),
					"Missing PK " + pk + " for locale " + locale
				);
			}
		}

		// verify fulltext indexes
		final Map<Locale, String> expectedContents = CollectionUtils.createHashMap(expectedFulltext.size());
		for (final Map.Entry<Locale, FulltextIndexModel> entry : expectedFulltext.entrySet()) {
			expectedContents.put(entry.getKey(), entry.getValue().expectedContent());
		}
		assertEquals(expectedContents, snapshot(committed).fulltext(), "Fulltext index content mismatch");
	}

	/**
	 * Creates a deep copy of the fulltext models, so a batch applied to the copy leaves the original describing the
	 * version the batch started from.
	 *
	 * @param original the models to copy
	 * @return a new map with an independent copy of every model
	 */
	@Nonnull
	static Map<Locale, FulltextIndexModel> deepCopyFulltextMap(@Nonnull Map<Locale, FulltextIndexModel> original) {
		final Map<Locale, FulltextIndexModel> copy = CollectionUtils.createHashMap(original.size());
		for (final Map.Entry<Locale, FulltextIndexModel> entry : original.entrySet()) {
			copy.put(entry.getKey(), entry.getValue().copy());
		}
		return copy;
	}

	/**
	 * Creates a deep copy of the locale map to avoid shared mutable state between generations.
	 *
	 * @param original the map to copy
	 * @return a new map with independent sets for each locale
	 */
	@Nonnull
	private static Map<Locale, Set<Integer>> deepCopyLocaleMap(
		@Nonnull Map<Locale, Set<Integer>> original
	) {
		final Map<Locale, Set<Integer>> copy = new HashMap<>(original.size());
		for (Map.Entry<Locale, Set<Integer>> entry : original.entrySet()) {
			copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
		}
		return copy;
	}

	/**
	 * State carried between generations in the generational proof test.
	 *
	 * @param expectedPks      the expected set of primary keys
	 * @param expectedLocales  the expected locale-to-PK mapping
	 * @param expectedFulltext the expected fulltext indexes, by locale
	 * @param index            the committed index to use in the next generation
	 */
	private record GenerationalState(
		@Nonnull Set<Integer> expectedPks,
		@Nonnull Map<Locale, Set<Integer>> expectedLocales,
		@Nonnull Map<Locale, FulltextIndexModel> expectedFulltext,
		@Nonnull GlobalEntityIndex index
	) {}

	/**
	 * Reference model carried between generations of the rollback proof: the expected primary keys and locale-to-PK
	 * mapping. The base index is rebuilt fresh from this model at the start of each generation.
	 *
	 * @param expectedPks      the expected set of primary keys
	 * @param expectedLocales  the expected locale-to-PK mapping
	 * @param expectedFulltext the expected fulltext indexes, by locale
	 */
	private record RollbackModel(
		@Nonnull Set<Integer> expectedPks,
		@Nonnull Map<Locale, Set<Integer>> expectedLocales,
		@Nonnull Map<Locale, FulltextIndexModel> expectedFulltext
	) {}

	/**
	 * Value-comparable snapshot of a {@link GlobalEntityIndex}: the sorted primary keys, per locale the sorted record
	 * ids, and per locale the content of the fulltext index. Record equality gives deep structural comparison.
	 *
	 * @param pks      sorted primary keys held by the index
	 * @param locales  per-locale sorted record ids
	 * @param fulltext per-locale content of the fulltext index, as {@link FulltextIndexModel#contentOf} renders it
	 */
	record GlobalSnapshot(
		@Nonnull List<Integer> pks,
		@Nonnull Map<Locale, List<Integer>> locales,
		@Nonnull Map<Locale, String> fulltext
	) {}
}
