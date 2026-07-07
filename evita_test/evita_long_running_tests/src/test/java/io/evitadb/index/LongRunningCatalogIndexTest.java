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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Generational randomized proof test for {@link CatalogIndex}.
 *
 * Runs randomized insert/remove unique-attribute operations over multiple generations and
 * compares the committed CatalogIndex against a JDK reference implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("CatalogIndex generational proof")
@Tag(INDEXING)
@Tag(MANAGEMENT)
class LongRunningCatalogIndexTest implements TimeBoundedTestSupport {

	static final String ENTITY_TYPE = "Product";
	private static final int ENTITY_TYPE_PK = 1;

	/**
	 * The fixed set of unique-attribute names the generational operations draw from; also the exact set the
	 * {@link #snapshot} oracle probes. Shared with the sibling {@code LongRunningSavepointCatalogIndexTest} so both agree
	 * on which attributes may exist.
	 */
	static final String[] ATTR_NAMES = {"code", "url", "sku", "ean"};

	/**
	 * Creates a mock {@link Catalog} that resolves the given entity type name to
	 * the given entity type primary key.
	 *
	 * @param entityType the entity type name to resolve
	 * @param entityTypePk the primary key to return for the entity type
	 * @return a configured Catalog mock
	 */
	@Nonnull
	private static Catalog createMockCatalog(
		@Nonnull String entityType,
		int entityTypePk
	) {
		final EntityCollection entityCollection = mock(EntityCollection.class);
		when(entityCollection.getEntityTypePrimaryKey()).thenReturn(entityTypePk);
		when(entityCollection.getEntityType()).thenReturn(entityType);

		final Catalog catalog = mock(Catalog.class);
		when(catalog.getCollectionForEntityOrThrowException(entityType))
			.thenReturn(entityCollection);
		when(catalog.getCollectionForEntityPrimaryKeyOrThrowException(entityTypePk))
			.thenReturn(entityCollection);
		return catalog;
	}

	/**
	 * Creates a non-localized {@link GlobalAttributeSchemaContract} mock with the specified
	 * attribute name and value type. The attribute is **not** unique globally within locale.
	 *
	 * @param attributeName the name of the attribute
	 * @param type the value type of the attribute
	 * @return a configured mock
	 */
	@Nonnull
	private static GlobalAttributeSchemaContract createNonLocalizedAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull Class<?> type
	) {
		final GlobalAttributeSchemaContract schema = mock(GlobalAttributeSchemaContract.class);
		when(schema.getName()).thenReturn(attributeName);
		doReturn(type).when(schema).getType();
		when(schema.isLocalized()).thenReturn(false);
		when(schema.isUniqueGloballyWithinLocaleInScope(Scope.LIVE)).thenReturn(false);
		return schema;
	}

	/**
	 * Creates a simple {@link EntitySchemaContract} mock that returns the given entity type name.
	 *
	 * @param entityType the entity type name
	 * @return a configured mock
	 */
	@Nonnull
	private static EntitySchemaContract createEntitySchema(@Nonnull String entityType) {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getName()).thenReturn(entityType);
		return schema;
	}

	/**
	 * Creates a new {@link CatalogIndex} for {@link Scope#LIVE} with a mock catalog attached.
	 *
	 * @return a ready-to-use CatalogIndex
	 */
	@Nonnull
	private static CatalogIndex createLiveCatalogIndex() {
		final CatalogIndex index = new CatalogIndex(Scope.LIVE);
		index.attachToCatalog(null, createMockCatalog(ENTITY_TYPE, ENTITY_TYPE_PK));
		return index;
	}

	/**
	 * Mutable state carried across generations. Tracks both the CatalogIndex
	 * under test and a reference HashMap that mirrors expected state.
	 *
	 * @param catalogIndex the CatalogIndex being tested
	 * @param reference the reference map: AttributeKey -> (value -> recordId)
	 * @param nextRecordId the next record ID to assign
	 */
	private record TestState(
		@Nonnull CatalogIndex catalogIndex,
		@Nonnull HashMap<AttributeKey, HashMap<Object, Integer>> reference,
		int nextRecordId
	) {}

	@Tag(SLOW)
	@DisplayName(
		"should match reference implementation across random generations"
	)
	@ParameterizedTest(name = "seed={0}")
	@ArgumentsSource(TimeArgumentProvider.class)
	void shouldMatchReferenceAcrossGenerations(
		@Nonnull GenerationalTestInput input
	) {
		runFor(
			input,
			1000,
			new TestState(
				createLiveCatalogIndex(),
				new HashMap<>(),
				1
			),
			LongRunningCatalogIndexTest::executeGeneration
		);
	}

	/**
	 * Executes a single generation: performs random insert/remove operations
	 * inside a transaction, commits, then verifies the committed CatalogIndex
	 * matches the reference map.
	 *
	 * @param random the random source for this generation
	 * @param state the current test state
	 * @return the updated test state for the next generation
	 */
	@Nonnull
	private static TestState executeGeneration(
		@Nonnull Random random,
		@Nonnull TestState state
	) {
		final CatalogIndex index = state.catalogIndex();
		final HashMap<AttributeKey, HashMap<Object, Integer>> reference =
			state.reference();
		final int[] nextId = {state.nextRecordId()};

		final CatalogIndex[] committedHolder = new CatalogIndex[1];

		assertStateAfterCommit(
			index,
			original -> applyRandomBatch(random, original, reference, nextId),
			(original, committed) -> {
				committedHolder[0] = committed;

				// verify: the committed index's emptiness matches
				// whether the reference is empty
				assertEquals(
					reference.isEmpty(),
					committed.isEmpty(),
					"Emptiness mismatch"
				);

				// verify: each attribute in reference has a
				// corresponding GlobalUniqueIndex
				for (Map.Entry<AttributeKey, HashMap<Object, Integer>> entry :
					reference.entrySet()) {
					final String name = entry.getKey().attributeName();
					final GlobalAttributeSchemaContract schema =
						createNonLocalizedAttributeSchema(
							name, String.class
						);
					final GlobalUniqueIndex gui =
						committed.getGlobalUniqueIndex(schema, null);
					assertNotNull(
						gui,
						"Missing GlobalUniqueIndex for " + name
					);
					assertFalse(
						gui.isEmpty(),
						"GlobalUniqueIndex for " + name
							+ " should not be empty"
					);
				}

				// verify: attributes NOT in reference should not
				// have a GlobalUniqueIndex
				for (String name : ATTR_NAMES) {
					if (!reference.containsKey(
						new AttributeKey(name))
					) {
						final GlobalAttributeSchemaContract schema =
							createNonLocalizedAttributeSchema(
								name, String.class
							);
						assertNull(
							committed.getGlobalUniqueIndex(
								schema, null
							),
							"Unexpected GlobalUniqueIndex for "
								+ name
						);
					}
				}
			}
		);

		// carry committed CatalogIndex forward; re-attach catalog
		final CatalogIndex committed = committedHolder[0];
		committed.attachToCatalog(
			null,
			createMockCatalog(ENTITY_TYPE, ENTITY_TYPE_PK)
		);

		return new TestState(committed, reference, nextId[0]);
	}

	/**
	 * Applies one random unique-attribute operation — insert a fresh value or remove a random existing one — to `index`,
	 * mirroring it into the `reference` model so the two stay in lockstep. Shared by the commit and rollback proofs so
	 * both drive the identical random-draw sequence.
	 *
	 * @param random    the random source for this operation
	 * @param index     the CatalogIndex to mutate (the in-transaction instance)
	 * @param reference the reference map kept in lockstep with the index
	 * @param nextId    single-element holder of the next record id to assign (advanced on insert)
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull CatalogIndex index,
		@Nonnull HashMap<AttributeKey, HashMap<Object, Integer>> reference,
		@Nonnull int[] nextId
	) {
		// pick a random attribute name
		final String attrName = ATTR_NAMES[random.nextInt(ATTR_NAMES.length)];
		final GlobalAttributeSchemaContract attrSchema =
			createNonLocalizedAttributeSchema(attrName, String.class);
		final EntitySchemaContract entitySchema = createEntitySchema(ENTITY_TYPE);
		final AttributeKey attrKey = new AttributeKey(attrName);

		// decide: insert or remove
		final HashMap<Object, Integer> existing = reference.getOrDefault(attrKey, new HashMap<>());
		final boolean shouldInsert = existing.isEmpty() || random.nextBoolean();

		if (shouldInsert) {
			// generate a unique value
			final String value = attrName + "-" + nextId[0];
			final int recordId = nextId[0]++;

			index.insertUniqueAttribute(
				entitySchema, attrSchema,
				Collections.emptySet(), null,
				value, recordId
			);

			// update reference
			reference.computeIfAbsent(attrKey, k -> new HashMap<>()).put(value, recordId);
		} else {
			// remove a random existing entry
			final Object[] keys = existing.keySet().toArray();
			final Object keyToRemove = keys[random.nextInt(keys.length)];
			final int recordId = existing.get(keyToRemove);

			index.removeUniqueAttribute(
				entitySchema, attrSchema,
				Collections.emptySet(), null,
				keyToRemove, recordId
			);

			// update reference
			existing.remove(keyToRemove);
			if (existing.isEmpty()) {
				reference.remove(attrKey);
			}
		}
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh committed base index
	 * from the (random-walking) reference model, captures a value oracle of that base, applies a random unique-attribute
	 * mutation inside a transaction that is then rolled back, and asserts the base index is unchanged and no committed
	 * value was published.
	 */
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@DisplayName(
		"rollback discards every in-transaction unique-attribute mutation and leaves the base intact"
	)
	@ParameterizedTest(name = "seed={0}")
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(@Nonnull GenerationalTestInput input) {
		runFor(
			input,
			1000,
			new TestState(createLiveCatalogIndex(), new HashMap<>(), 1),
			(random, state) -> {
				final HashMap<AttributeKey, HashMap<Object, Integer>> reference = state.reference();
				final int[] nextId = {state.nextRecordId()};
				// rebuild a fresh committed base index from the (random-walking) reference model
				final CatalogIndex index = buildCatalogIndex(reference);
				final CatalogSnapshot beforeRollback = snapshot(index);

				assertStateAfterRollback(
					index,
					original -> applyRandomBatch(random, original, reference, nextId),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!");
						assertEquals(beforeRollback, snapshot(original),
							"CatalogIndex changed after rollback — atomic rollback leaked!");
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation rebuilds a
				// different live base index — a random walk that keeps the proof exploring fresh base indexes
				return new TestState(index, reference, nextId[0]);
			}
		);
	}

	/**
	 * Rebuilds a fresh {@link CatalogIndex} (attached to a mock catalog) from the (random-walking) reference model by
	 * replaying every `(attribute, value, recordId)` tuple via {@link CatalogIndex#insertUniqueAttribute}. Values within
	 * an attribute are distinct by construction, so no unique violation can occur during the replay.
	 */
	@Nonnull
	private static CatalogIndex buildCatalogIndex(
		@Nonnull HashMap<AttributeKey, HashMap<Object, Integer>> reference
	) {
		final CatalogIndex index = createLiveCatalogIndex();
		final EntitySchemaContract entitySchema = createEntitySchema(ENTITY_TYPE);
		for (final Map.Entry<AttributeKey, HashMap<Object, Integer>> attributeEntry : reference.entrySet()) {
			final String attributeName = attributeEntry.getKey().attributeName();
			final GlobalAttributeSchemaContract attributeSchema =
				createNonLocalizedAttributeSchema(attributeName, String.class);
			for (final Map.Entry<Object, Integer> valueEntry : attributeEntry.getValue().entrySet()) {
				index.insertUniqueAttribute(
					entitySchema, attributeSchema,
					Collections.emptySet(), null,
					valueEntry.getKey(), valueEntry.getValue()
				);
			}
		}
		return index;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot: per unique-attribute name, the sorted
	 * record ids indexed for {@link #ENTITY_TYPE} and the number of unique keys held. Both reads are transaction-aware, so
	 * two snapshots taken before and after a rollback can be compared with `.equals` to prove exact restoration; index
	 * object identity is never compared.
	 */
	@Nonnull
	static CatalogSnapshot snapshot(@Nonnull CatalogIndex index) {
		final Map<String, List<Integer>> recordIdsByAttribute = new HashMap<>();
		final Map<String, Integer> sizeByAttribute = new HashMap<>();
		for (final String attributeName : ATTR_NAMES) {
			final GlobalAttributeSchemaContract attributeSchema =
				createNonLocalizedAttributeSchema(attributeName, String.class);
			final GlobalUniqueIndex globalUniqueIndex = index.getGlobalUniqueIndex(attributeSchema, null);
			if (globalUniqueIndex != null) {
				recordIdsByAttribute.put(attributeName, toList(globalUniqueIndex.getRecordIds(ENTITY_TYPE)));
				sizeByAttribute.put(attributeName, globalUniqueIndex.size());
			}
		}
		return new CatalogSnapshot(recordIdsByAttribute, sizeByAttribute);
	}

	/**
	 * Converts a bitmap into an ascending list of its record ids (a value type with deep `.equals`).
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
	 * Value-comparable snapshot of a {@link CatalogIndex}: per attribute name the sorted record ids for
	 * {@link #ENTITY_TYPE} (the per-type bitmap of each {@link GlobalUniqueIndex}) and the count of unique keys it holds
	 * (its value tree). Record equality gives deep structural comparison, so two snapshots match iff the indexes hold the
	 * exact same unique-attribute content.
	 *
	 * @param recordIdsByAttribute attribute name to sorted indexed record ids
	 * @param sizeByAttribute      attribute name to number of unique keys held
	 */
	record CatalogSnapshot(
		@Nonnull Map<String, List<Integer>> recordIdsByAttribute,
		@Nonnull Map<String, Integer> sizeByAttribute
	) {
	}
}
