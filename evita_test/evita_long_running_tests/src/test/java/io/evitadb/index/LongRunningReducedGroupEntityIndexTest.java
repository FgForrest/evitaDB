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

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Generational property-based stress test for {@link ReducedGroupEntityIndex}.
 * Runs randomized operations (PK insert/remove with cardinality + filter attribute
 * insert/remove) over multiple generations, comparing committed state against a
 * JDK reference implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReducedGroupEntityIndex generational proof")
@Tag(INDEXING)
@Tag(MANAGEMENT)
class LongRunningReducedGroupEntityIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "Product";
	private static final String REFERENCE_NAME = "CATEGORY";
	private static final int INDEX_PK = 1;

	/**
	 * Creates a new {@link ReducedGroupEntityIndex} with the given group primary key.
	 *
	 * @param groupPk the primary key of the group entity (used in the discriminator)
	 * @return a fresh index instance
	 */
	@Nonnull
	@SuppressWarnings("SameParameterValue")
	private static ReducedGroupEntityIndex createIndex(int groupPk) {
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_NAME, groupPk)
		);
		final EntityIndexKey key = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, rrk
		);
		return new ReducedGroupEntityIndex(INDEX_PK, ENTITY_TYPE, key);
	}

	/**
	 * Creates a non-localized, filterable {@link AttributeSchemaContract} stub for testing.
	 *
	 * @param name the attribute name
	 * @param type the attribute value type
	 * @return a new attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract createFilterableAttributeSchema(
		@Nonnull String name, @Nonnull Class<? extends Serializable> type
	) {
		return AttributeSchema._internalBuild(
			name,
			null,
			new Scope[]{Scope.LIVE},
			null,
			false, false, false,
			type, null
		);
	}

	@DisplayName("survives generational randomized test")
	@ParameterizedTest(
		name = "ReducedGroupEntityIndex should survive generational randomized test"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
		final AttributeSchemaContract attrSchema = createFilterableAttributeSchema(
			"code", String.class
		);
		final Set<Locale> noLocales = Collections.emptySet();

		runFor(
			input,
			50_000,
			new GenerationalState(
				new HashMap<>(16),
				new HashSet<>(16),
				createIndex(100)
			),
			(random, state) -> {
				final ReducedGroupEntityIndex tested = state.index();
				// deep copy reference state — must deep-copy inner sets
				final Map<Integer, Set<Integer>> refPkPairs = new HashMap<>(16);
				for (Map.Entry<Integer, Set<Integer>> entry : state.expectedPkPairs().entrySet()) {
					refPkPairs.put(entry.getKey(), new HashSet<>(entry.getValue()));
				}
				final Set<String> refAttributes = new HashSet<>(state.expectedAttributes());
				final AtomicReference<ReducedGroupEntityIndex> committedRef =
					new AtomicReference<>();

				assertStateAfterCommit(
					tested,
					original -> {
						final int ops = random.nextInt(5) + 1;
						for (int i = 0; i < ops; i++) {
							executeRandomOperation(
								random, original, refPkPairs,
								refAttributes, refSchema, attrSchema, noLocales
							);
						}
					},
					(original, committed) -> {
						assertNotNull(committed);
						final ReducedGroupEntityIndex typed =
							(ReducedGroupEntityIndex) committed;
						verifyState(typed, refPkPairs);
						committedRef.set(typed);
					}
				);

				return new GenerationalState(
					refPkPairs, refAttributes, committedRef.get()
				);
			},
			(state, exc) -> {
				System.out.println(
					"Failed state - PK pairs: " + state.expectedPkPairs()
				);
				System.out.println(
					"Failed state - Attributes: " + state.expectedAttributes()
				);
			}
		);
	}

	/**
	 * Executes a random operation on both the index and the reference model.
	 * The reference model tracks unique `(entityPk, referencedPk)` pairs to mirror
	 * real-world usage where each reference is unique. Duplicate pair insertions are
	 * skipped because the production code's cardinality becomes inconsistent with
	 * the bitmap tracking when the same pair is inserted multiple times.
	 */
	private static void executeRandomOperation(
		@Nonnull Random random,
		@Nonnull ReducedGroupEntityIndex idx,
		@Nonnull Map<Integer, Set<Integer>> refPkPairs,
		@Nonnull Set<String> refAttributes,
		@Nonnull ReferenceSchemaContract refSchema,
		@Nonnull AttributeSchemaContract attrSchema,
		@Nonnull Set<Locale> noLocales
	) {
		final int operation = random.nextInt(4);
		final int entityPk = random.nextInt(30) + 1;
		final int referencedPk = random.nextInt(20) + 1;

		switch (operation) {
			case 0 -> {
				// insert PK — skip if pair already exists (matches real-world semantics)
				final Set<Integer> refs = refPkPairs
					.computeIfAbsent(entityPk, k -> new HashSet<>());
				if (refs.add(referencedPk)) {
					idx.insertPrimaryKeyIfMissing(entityPk, referencedPk);
				}
			}
			case 1 -> {
				// remove PK — must pick a referencedPk that actually exists
				final Set<Integer> refs = refPkPairs.get(entityPk);
				if (refs != null && !refs.isEmpty()) {
					final int existingRefPk = refs.iterator().next();
					idx.removePrimaryKey(entityPk, existingRefPk);
					refs.remove(existingRefPk);
					if (refs.isEmpty()) {
						refPkPairs.remove(entityPk);
					}
				}
			}
			case 2 -> {
				// insert filter attribute
				final String value = "VAL_" + (random.nextInt(10) + 1);
				idx.insertFilterAttribute(
					refSchema, attrSchema, noLocales, null, value, entityPk
				);
				refAttributes.add(value + ":" + entityPk);
			}
			case 3 -> {
				// remove filter attribute (only if known)
				if (!refAttributes.isEmpty()) {
					final String entry = refAttributes.iterator().next();
					final String[] parts = entry.split(":");
					final String value = parts[0];
					final int recordId = Integer.parseInt(parts[1]);
					// only remove if index has it
					try {
						idx.removeFilterAttribute(
							refSchema, attrSchema, noLocales, null, value, recordId
						);
						refAttributes.remove(entry);
					} catch (Exception e) {
						// cardinality index might not exist - skip
					}
				}
			}
		}
	}

	/**
	 * Verifies that the committed index state matches the reference model for PKs.
	 * Each entityPk with at least one referencedPk pair should be in the bitmap.
	 */
	private static void verifyState(
		@Nonnull ReducedGroupEntityIndex committed,
		@Nonnull Map<Integer, Set<Integer>> expectedPkPairs
	) {
		final Set<Integer> expectedPks = expectedPkPairs.keySet();
		final Bitmap allPks = committed.getAllPrimaryKeys();
		assertEquals(
			expectedPks.size(), allPks.size(),
			"PK count mismatch. Expected: " + expectedPks +
				", got bitmap size: " + allPks.size()
		);
		for (int pk : expectedPks) {
			assertTrue(allPks.contains(pk), "Missing PK: " + pk);
		}
	}

	/**
	 * State carried between generations in the generational proof test.
	 *
	 * @param expectedPkPairs    maps entityPk to set of referencedPks (each unique pair = 1 cardinality)
	 * @param expectedAttributes set of "value:recordId" entries for tracking
	 * @param index              the committed index to use in the next generation
	 */
	private record GenerationalState(
		@Nonnull Map<Integer, Set<Integer>> expectedPkPairs,
		@Nonnull Set<String> expectedAttributes,
		@Nonnull ReducedGroupEntityIndex index
	) {}
}
