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

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CatalogIndex} verifying construction, unique attribute management,
 * locale handling, emptiness checks, and STM (Software Transactional Memory) behavior.
 *
 * @author evitaDB
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("CatalogIndex functionality")
class CatalogIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "Product";
	private static final String ATTR_CODE = "code";
	private static final String ATTR_URL = "url";
	private static final int ENTITY_TYPE_PK = 1;

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
	 * Creates a localized {@link GlobalAttributeSchemaContract} mock that is unique
	 * globally within locale scope.
	 *
	 * @param attributeName the name of the attribute
	 * @param type the value type of the attribute
	 * @return a configured mock
	 */
	@Nonnull
	private static GlobalAttributeSchemaContract createLocalizedAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull Class<?> type
	) {
		final GlobalAttributeSchemaContract schema = mock(GlobalAttributeSchemaContract.class);
		when(schema.getName()).thenReturn(attributeName);
		doReturn(type).when(schema).getType();
		when(schema.isLocalized()).thenReturn(true);
		when(schema.isUniqueGloballyWithinLocaleInScope(Scope.LIVE)).thenReturn(true);
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

	@Nested
	@DisplayName("Construction and identity")
	class ConstructionAndIdentityTest {

		@Test
		@DisplayName("should create empty index with correct scope and version")
		void shouldCreateEmptyIndexWithCorrectScopeAndVersion() {
			final CatalogIndex index = new CatalogIndex(Scope.LIVE);

			assertEquals(1, index.getVersion());
			assertEquals(new CatalogIndexKey(Scope.LIVE), index.getIndexKey());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("should preserve version and key from persisted data")
		void shouldPreserveVersionAndKeyFromPersistedData() {
			final CatalogIndexKey key = new CatalogIndexKey(Scope.LIVE);
			final CatalogIndex index = new CatalogIndex(
				5, key, new HashMap<>()
			);

			assertEquals(5, index.getVersion());
			assertSame(key, index.getIndexKey());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("should assign unique ID to each instance")
		void shouldAssignUniqueIdToEachInstance() {
			final CatalogIndex first = new CatalogIndex(Scope.LIVE);
			final CatalogIndex second = new CatalogIndex(Scope.LIVE);

			assertNotEquals(first.getId(), second.getId());
		}
	}

	@Nested
	@DisplayName("Unique attribute operations")
	class UniqueAttributeOperationsTest {

		private CatalogIndex index;
		private EntitySchemaContract entitySchema;

		@BeforeEach
		void setUp() {
			this.index = createLiveCatalogIndex();
			this.entitySchema = createEntitySchema(ENTITY_TYPE);
		}

		@Test
		@DisplayName("should create GlobalUniqueIndex on first insert")
		void shouldCreateGlobalUniqueIndexOnFirstInsert() {
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);

			this.index.insertUniqueAttribute(
				this.entitySchema, attrSchema,
				Collections.emptySet(), null, "ABC", 1
			);

			final GlobalUniqueIndex globalIndex =
				this.index.getGlobalUniqueIndex(attrSchema, null);
			assertNotNull(globalIndex);
			assertFalse(globalIndex.isEmpty());
		}

		@Test
		@DisplayName("should remove GlobalUniqueIndex when last value removed")
		void shouldRemoveGlobalUniqueIndexWhenLastValueRemoved() {
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);

			this.index.insertUniqueAttribute(
				this.entitySchema, attrSchema,
				Collections.emptySet(), null, "ABC", 1
			);
			this.index.removeUniqueAttribute(
				this.entitySchema, attrSchema,
				Collections.emptySet(), null, "ABC", 1
			);

			assertNull(this.index.getGlobalUniqueIndex(attrSchema, null));
		}

		@Test
		@DisplayName("should return null for non-existent attribute")
		void shouldReturnNullForNonExistentAttribute() {
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);

			assertNull(this.index.getGlobalUniqueIndex(attrSchema, null));
		}

		@Test
		@DisplayName("should maintain separate indexes per attribute name")
		void shouldMaintainSeparateIndexesPerAttributeName() {
			final GlobalAttributeSchemaContract codeSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);
			final GlobalAttributeSchemaContract urlSchema =
				createNonLocalizedAttributeSchema(ATTR_URL, String.class);

			this.index.insertUniqueAttribute(
				this.entitySchema, codeSchema,
				Collections.emptySet(), null, "CODE-1", 1
			);
			this.index.insertUniqueAttribute(
				this.entitySchema, urlSchema,
				Collections.emptySet(), null, "/product/1", 2
			);

			final GlobalUniqueIndex codeIndex =
				this.index.getGlobalUniqueIndex(codeSchema, null);
			final GlobalUniqueIndex urlIndex =
				this.index.getGlobalUniqueIndex(urlSchema, null);

			assertNotNull(codeIndex);
			assertNotNull(urlIndex);
			assertNotSame(codeIndex, urlIndex);
		}
	}

	@Nested
	@DisplayName("Locale handling")
	class LocaleHandlingTest {

		private CatalogIndex index;
		private EntitySchemaContract entitySchema;

		@BeforeEach
		void setUp() {
			this.index = createLiveCatalogIndex();
			this.entitySchema = createEntitySchema(ENTITY_TYPE);
		}

		@Test
		@DisplayName("should create non-localized key for non-localized attribute")
		void shouldCreateNonLocalizedKey() {
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);

			this.index.insertUniqueAttribute(
				this.entitySchema, attrSchema,
				Collections.emptySet(), null, "ABC", 1
			);

			// retrieve without locale -- should work for non-localized
			assertNotNull(this.index.getGlobalUniqueIndex(attrSchema, null));
		}

		@Test
		@DisplayName("should create localized key for locale-scoped unique attribute")
		void shouldCreateLocalizedKey() {
			final GlobalAttributeSchemaContract attrSchema =
				createLocalizedAttributeSchema(ATTR_URL, String.class);
			final Set<Locale> allowedLocales = Set.of(Locale.ENGLISH);

			this.index.insertUniqueAttribute(
				this.entitySchema, attrSchema,
				allowedLocales, Locale.ENGLISH, "/product/1", 1
			);

			// retrieve with matching locale
			assertNotNull(
				this.index.getGlobalUniqueIndex(attrSchema, Locale.ENGLISH)
			);
		}

		@Test
		@DisplayName(
			"should throw EntityLocaleMissingException when locale is null"
			+ " for locale-scoped unique attribute"
		)
		void shouldThrowWhenLocaleNullForLocaleScopedAttribute() {
			final GlobalAttributeSchemaContract attrSchema =
				createLocalizedAttributeSchema(ATTR_URL, String.class);

			assertThrows(
				EntityLocaleMissingException.class,
				() -> this.index.getGlobalUniqueIndex(attrSchema, null)
			);
		}
	}

	@Nested
	@DisplayName("isEmpty behavior")
	class IsEmptyBehaviorTest {

		@Test
		@DisplayName("should be empty when newly created")
		void shouldBeEmptyWhenNewlyCreated() {
			final CatalogIndex index = new CatalogIndex(Scope.LIVE);

			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("should not be empty after inserting unique attribute")
		void shouldNotBeEmptyAfterInsert() {
			final CatalogIndex index = createLiveCatalogIndex();
			final EntitySchemaContract entitySchema = createEntitySchema(ENTITY_TYPE);
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);

			index.insertUniqueAttribute(
				entitySchema, attrSchema,
				Collections.emptySet(), null, "ABC", 1
			);

			assertFalse(index.isEmpty());
		}

		@Test
		@DisplayName("should be empty after all unique attributes removed")
		void shouldBeEmptyAfterAllRemoved() {
			final CatalogIndex index = createLiveCatalogIndex();
			final EntitySchemaContract entitySchema = createEntitySchema(ENTITY_TYPE);
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);

			index.insertUniqueAttribute(
				entitySchema, attrSchema,
				Collections.emptySet(), null, "ABC", 1
			);
			index.removeUniqueAttribute(
				entitySchema, attrSchema,
				Collections.emptySet(), null, "ABC", 1
			);

			assertTrue(index.isEmpty());
		}
	}

	@Nested
	@DisplayName("Unique value violation")
	class UniqueValueViolationTest {

		@Test
		@DisplayName("should throw UniqueValueViolationException on duplicate value")
		void shouldThrowOnDuplicateValue() {
			final CatalogIndex index = createLiveCatalogIndex();
			final EntitySchemaContract entitySchema = createEntitySchema(ENTITY_TYPE);
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);

			index.insertUniqueAttribute(
				entitySchema, attrSchema,
				Collections.emptySet(), null, "DUPLICATE", 1
			);

			final UniqueValueViolationException exception = assertThrows(
				UniqueValueViolationException.class,
				() -> index.insertUniqueAttribute(
					entitySchema, attrSchema,
					Collections.emptySet(), null, "DUPLICATE", 2
				)
			);
			assertEquals(ATTR_CODE, exception.getAttributeName());
			assertEquals("DUPLICATE", exception.getValue());
			assertEquals(1, exception.getExistingRecordId());
			assertEquals(2, exception.getNewRecordId());
		}
	}

	@Nested
	@DisplayName("STM commit")
	class StmCommitTest {

		@Test
		@DisplayName("should propagate inserted attribute to committed copy")
		void shouldPropagateInsertedAttributeToCommittedCopy() {
			final CatalogIndex index = createLiveCatalogIndex();
			final EntitySchemaContract entitySchema = createEntitySchema(ENTITY_TYPE);
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);

			assertStateAfterCommit(
				index,
				original -> {
					original.insertUniqueAttribute(
						entitySchema, attrSchema,
						Collections.emptySet(), null, "ABC", 1
					);
				},
				(original, committed) -> {
					// original is still empty outside the transaction
					assertTrue(original.isEmpty());

					// committed copy has the inserted attribute
					assertNotNull(committed);
					assertFalse(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("should increment version when dirty")
		void shouldIncrementVersionWhenDirty() {
			final CatalogIndex index = createLiveCatalogIndex();
			final EntitySchemaContract entitySchema = createEntitySchema(ENTITY_TYPE);
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);
			final int originalVersion = index.getVersion();

			assertStateAfterCommit(
				index,
				original -> {
					original.insertUniqueAttribute(
						entitySchema, attrSchema,
						Collections.emptySet(), null, "ABC", 1
					);
				},
				(original, committed) -> {
					assertEquals(originalVersion, original.getVersion());
					assertEquals(
						originalVersion + 1,
						committed.getVersion()
					);
				}
			);
		}

		@Test
		@DisplayName("should not increment version when clean")
		void shouldNotIncrementVersionWhenClean() {
			final CatalogIndex index = createLiveCatalogIndex();
			final int originalVersion = index.getVersion();

			assertStateAfterCommit(
				index,
				original -> {
					// no mutations -- index stays clean
				},
				(original, committed) -> {
					assertEquals(originalVersion, committed.getVersion());
				}
			);
		}
	}

	@Nested
	@DisplayName("STM rollback")
	class StmRollbackTest {

		@Test
		@DisplayName("should discard changes after rollback")
		void shouldDiscardChangesAfterRollback() {
			final CatalogIndex index = createLiveCatalogIndex();
			final EntitySchemaContract entitySchema = createEntitySchema(ENTITY_TYPE);
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(ATTR_CODE, String.class);

			assertStateAfterRollback(
				index,
				original -> {
					original.insertUniqueAttribute(
						entitySchema, attrSchema,
						Collections.emptySet(), null, "ABC", 1
					);
				},
				(original, committed) -> {
					// after rollback, committed is null
					assertNull(committed);
					// original remains unchanged
					assertTrue(original.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("should preserve original version after rollback")
		void shouldPreserveOriginalVersionAfterRollback() {
			final CatalogIndex index = createLiveCatalogIndex();
			final int originalVersion = index.getVersion();

			assertStateAfterRollback(
				index,
				original -> {
					final EntitySchemaContract entitySchema =
						createEntitySchema(ENTITY_TYPE);
					final GlobalAttributeSchemaContract attrSchema =
						createNonLocalizedAttributeSchema(ATTR_CODE, String.class);
					original.insertUniqueAttribute(
						entitySchema, attrSchema,
						Collections.emptySet(), null, "XYZ", 1
					);
				},
				(original, committed) -> {
					assertEquals(originalVersion, original.getVersion());
				}
			);
		}
	}

	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

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

		@Tag(LONG_RUNNING_TEST)
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
				GenerationalProofTest::executeGeneration
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

			// pick a random attribute name
			final String[] attrNames = {"code", "url", "sku", "ean"};
			final String attrName =
				attrNames[random.nextInt(attrNames.length)];
			final GlobalAttributeSchemaContract attrSchema =
				createNonLocalizedAttributeSchema(attrName, String.class);
			final EntitySchemaContract entitySchema =
				createEntitySchema(ENTITY_TYPE);
			final AttributeKey attrKey = new AttributeKey(attrName);

			final CatalogIndex[] committedHolder = new CatalogIndex[1];

			assertStateAfterCommit(
				index,
				original -> {
					// decide: insert or remove
					final HashMap<Object, Integer> existing =
						reference.getOrDefault(attrKey, new HashMap<>());
					final boolean shouldInsert =
						existing.isEmpty() || random.nextBoolean();

					if (shouldInsert) {
						// generate a unique value
						final String value =
							attrName + "-" + nextId[0];
						final int recordId = nextId[0]++;

						original.insertUniqueAttribute(
							entitySchema, attrSchema,
							Collections.emptySet(), null,
							value, recordId
						);

						// update reference
						reference.computeIfAbsent(
							attrKey, k -> new HashMap<>()
						).put(value, recordId);
					} else {
						// remove a random existing entry
						final Object[] keys =
							existing.keySet().toArray();
						final Object keyToRemove =
							keys[random.nextInt(keys.length)];
						final int recordId =
							existing.get(keyToRemove);

						original.removeUniqueAttribute(
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
				},
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
					for (String name : attrNames) {
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
	}
}
