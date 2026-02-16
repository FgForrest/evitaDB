/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.dataType.DataChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InitialReferencesBuilder} verifying construction,
 * querying, mutation, and cardinality management of initial references.
 *
 * @author evitaDB
 */
@DisplayName("InitialReferencesBuilder functionality")
class InitialReferencesBuilderTest extends AbstractBuilderTest {

	private static final String STORE = "store";
	private static final String BRAND = "brand";
	private static final String GROUP = "group";
	private static final String CATEGORY = "category";
	private static final String COUNTRY = "country";

	/**
	 * Builds an entity schema with store (ZERO_OR_MORE) and brand
	 * (ZERO_OR_MORE_WITH_DUPLICATES, with "country" attribute)
	 * reference definitions.
	 */
	@Nonnull
	private static EntitySchemaContract createSchemaWithReferences() {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				STORE, STORE, Cardinality.ZERO_OR_MORE,
				r -> {}
			)
			.withReferenceToEntity(
				BRAND, BRAND,
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				r -> r.withAttribute(
					COUNTRY, String.class,
					AttributeSchemaEditor::representative
				)
			)
			.toInstance();
	}

	/**
	 * Builds an entity schema with store reference having
	 * ZERO_OR_ONE cardinality and all evolution modes enabled.
	 */
	@Nonnull
	private static EntitySchemaContract createSchemaWithSingleRef() {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				STORE, STORE, Cardinality.ZERO_OR_ONE,
				r -> {}
			)
			.toInstance();
	}

	/**
	 * Builds an entity schema with ZERO_OR_ONE cardinality on
	 * store reference and with UPDATING_REFERENCE_CARDINALITY
	 * evolution mode disabled.
	 */
	@Nonnull
	private static EntitySchemaContract
	createSchemaWithStrictCardinality() {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				STORE, STORE, Cardinality.ZERO_OR_ONE,
				r -> {}
			)
			.verifySchemaStrictly()
			.toInstance();
	}

	/**
	 * Builds an entity schema with a brand reference that has
	 * group type configured to "group".
	 */
	@Nonnull
	private static EntitySchemaContract
	createSchemaWithGroupReference() {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_MORE,
				r -> r.withGroupType(GROUP)
			)
			.toInstance();
	}

	/**
	 * Creates a seeded list of reference contracts using a
	 * temporary builder. This is used to simulate existing
	 * references passed to the seeded constructor.
	 */
	@Nonnull
	private static List<ReferenceContract> createSeedReferences(
		@Nonnull EntitySchemaContract schema
	) {
		final InitialReferencesBuilder tempBuilder =
			new InitialReferencesBuilder(schema);
		tempBuilder.setReference(STORE, 1);
		tempBuilder.setReference(STORE, 2);
		final References refs = tempBuilder.build();
		return new ArrayList<>(refs.getReferences());
	}

	/**
	 * Creates a seeded list of reference contracts containing
	 * duplicates for the brand reference.
	 */
	@Nonnull
	private static List<ReferenceContract>
	createSeedReferencesWithDuplicates(
		@Nonnull EntitySchemaContract schema
	) {
		final InitialReferencesBuilder tempBuilder =
			new InitialReferencesBuilder(schema);
		tempBuilder.setOrUpdateReference(
			BRAND, 1, ref -> false,
			ref -> ref.setAttribute(COUNTRY, "CZ")
		);
		tempBuilder.setOrUpdateReference(
			BRAND, 1, ref -> false,
			ref -> ref.setAttribute(COUNTRY, "DE")
		);
		final References refs = tempBuilder.build();
		return new ArrayList<>(refs.getReferences());
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should create empty builder with no references"
		)
		void shouldCreateEmptyBuilder() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			assertTrue(builder.getReferences().isEmpty());
			assertTrue(builder.getReferenceNames().isEmpty());
		}

		@Test
		@DisplayName(
			"should create builder from existing references"
		)
		void shouldCreateBuilderFromExistingReferences() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final List<ReferenceContract> seeds =
				createSeedReferences(schema);

			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema, seeds);

			assertEquals(2, builder.getReferences().size());
			assertTrue(
				builder.getReferenceNames().contains(STORE)
			);
		}

		@Test
		@DisplayName(
			"should create builder from references with duplicates"
		)
		void
		shouldCreateBuilderFromExistingReferencesWithDuplicates() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final List<ReferenceContract> seeds =
				createSeedReferencesWithDuplicates(schema);

			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema, seeds);

			assertEquals(2, builder.getReferences().size());
			final List<ReferenceContract> brandRefs =
				new ArrayList<>(builder.getReferences(BRAND));
			assertEquals(2, brandRefs.size());
		}
	}

	@Nested
	@DisplayName("Querying references")
	class QueryingReferencesTest {

		@Test
		@DisplayName("should return empty when none added")
		void shouldReturnEmptyReferencesWhenNoneAdded() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			assertTrue(builder.getReferences().isEmpty());
			assertTrue(
				builder.getReferences(STORE).isEmpty()
			);
		}

		@Test
		@DisplayName("should return reference names after adding")
		void shouldReturnReferenceNames() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "CZ")
			);

			final Set<String> names = builder.getReferenceNames();
			assertTrue(names.contains(STORE));
			assertTrue(names.contains(BRAND));
		}

		@Test
		@DisplayName("should return references by name")
		void shouldReturnReferencesByName() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setReference(STORE, 2);

			final Collection<ReferenceContract> storeRefs =
				builder.getReferences(STORE);
			assertEquals(2, storeRefs.size());
		}

		@Test
		@DisplayName("should return reference by name and id")
		void shouldReturnReferenceByNameAndId() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 42);

			final Optional<ReferenceContract> ref =
				builder.getReference(STORE, 42);
			assertTrue(ref.isPresent());
			assertEquals(42, ref.get().getReferencedPrimaryKey());
		}

		@Test
		@DisplayName(
			"should throw on duplicate reference access "
				+ "via getReference(name, id)"
		)
		void shouldThrowOnDuplicateReferenceAccess() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "CZ")
			);
			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "DE")
			);

			assertThrows(
				ReferenceAllowsDuplicatesException.class,
				() -> builder.getReference(BRAND, 1)
			);
		}

		@Test
		@DisplayName("should return reference by ReferenceKey")
		void shouldReturnReferenceByKey() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 5);

			final ReferenceKey key = new ReferenceKey(STORE, 5);
			final Optional<ReferenceContract> ref =
				builder.getReference(key);
			assertTrue(ref.isPresent());
			assertEquals(5, ref.get().getReferencedPrimaryKey());
		}

		@Test
		@DisplayName(
			"should return all duplicates via getReferences(key)"
		)
		void shouldReturnAllDuplicateReferences() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "CZ")
			);
			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "DE")
			);

			final ReferenceKey key = new ReferenceKey(BRAND, 1);
			final List<ReferenceContract> refs =
				builder.getReferences(key);
			assertEquals(2, refs.size());
		}

		@Test
		@DisplayName("should return reference chunk")
		void shouldReturnReferenceChunk() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setReference(STORE, 2);

			final DataChunk<ReferenceContract> chunk =
				builder.getReferenceChunk(STORE);
			assertNotNull(chunk);
			assertEquals(2, chunk.getTotalRecordCount());
		}

		@Test
		@DisplayName(
			"should report references available for both overloads"
		)
		void shouldReportReferencesAvailable() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			assertTrue(builder.referencesAvailable());
			assertTrue(builder.referencesAvailable(STORE));
			assertTrue(
				builder.referencesAvailable("nonexistent")
			);
		}
	}

	@Nested
	@DisplayName("Setting references")
	class SettingReferencesTest {

		@Test
		@DisplayName("should set simple reference by name and pk")
		void shouldSetSimpleReference() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 10);

			final Optional<ReferenceContract> ref =
				builder.getReference(STORE, 10);
			assertTrue(ref.isPresent());
			assertEquals(
				STORE, ref.get().getReferenceName()
			);
		}

		@Test
		@DisplayName(
			"should set reference with consumer for attributes"
		)
		void shouldSetReferenceWithConsumer() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(
				STORE, 10,
				ref -> ref.setAttribute("priority", 5)
			);

			final Optional<ReferenceContract> ref =
				builder.getReference(STORE, 10);
			assertTrue(ref.isPresent());
		}

		@Test
		@DisplayName(
			"should set reference with entity type and cardinality"
		)
		void
		shouldSetReferenceWithEntityTypeAndCardinality() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			// Use the implicit schema creation path
			builder.setReference(
				CATEGORY, CATEGORY,
				Cardinality.ZERO_OR_MORE, 1
			);

			final Collection<ReferenceContract> refs =
				builder.getReferences(CATEGORY);
			assertEquals(1, refs.size());
		}

		@Test
		@DisplayName(
			"should set reference with entity type, "
				+ "cardinality and consumer"
		)
		void
		shouldSetReferenceWithEntityTypeCardinalityAndConsumer() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(
				CATEGORY, CATEGORY,
				Cardinality.ZERO_OR_MORE, 1,
				ref -> ref.setAttribute("order", 1)
			);

			final Collection<ReferenceContract> refs =
				builder.getReferences(CATEGORY);
			assertEquals(1, refs.size());
		}

		@Test
		@DisplayName(
			"should throw when setting reference to "
				+ "unknown name without entity type"
		)
		void shouldThrowWhenSettingUnknownReference() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			assertThrows(
				ReferenceNotKnownException.class,
				() -> builder.setReference("unknown", 1)
			);
		}
	}

	@Nested
	@DisplayName("Updating references")
	class UpdatingReferencesTest {

		@Test
		@DisplayName(
			"should update existing reference via updateReference"
		)
		void shouldUpdateExistingReference() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 10);
			builder.updateReference(
				STORE, 10,
				ref -> ref.setAttribute("priority", 99)
			);

			final Optional<ReferenceContract> ref =
				builder.getReference(STORE, 10);
			assertTrue(ref.isPresent());
		}

		@Test
		@DisplayName(
			"should update references matching a predicate"
		)
		void shouldUpdateReferencesByPredicate() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setReference(STORE, 2);

			builder.updateReferences(
				ref -> ref.getReferenceName().equals(STORE)
					&& ref.getReferencedPrimaryKey() == 1,
				ref -> ref.setAttribute("priority", 5)
			);

			// verify the collection still has 2 references
			assertEquals(
				2, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should do nothing when updating non-existent ref"
		)
		void shouldDoNothingWhenUpdatingNonExistentReference() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			// update on non-existent reference is a no-op
			builder.updateReference(
				STORE, 999,
				ref -> ref.setAttribute("priority", 5)
			);

			assertTrue(builder.getReferences().isEmpty());
		}

		@Test
		@DisplayName(
			"should create new reference via setOrUpdateReference"
		)
		void shouldSetOrUpdateReferenceCreatingNew() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setOrUpdateReference(
				STORE, 1, ref -> false,
				ref -> ref.setAttribute("priority", 10)
			);

			assertEquals(
				1, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should update existing via setOrUpdateReference "
				+ "when filter matches"
		)
		void shouldSetOrUpdateReferenceUpdatingExisting() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);

			// filter matches - update the existing one
			builder.setOrUpdateReference(
				STORE, 1, ref -> true,
				ref -> ref.setAttribute("priority", 20)
			);

			// still just one reference, not two
			assertEquals(
				1, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should create duplicate via setOrUpdateReference "
				+ "when filter doesn't match"
		)
		void shouldSetOrUpdateReferenceWithDuplicates() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "CZ")
			);
			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "DE")
			);

			final List<ReferenceContract> refs =
				builder.getReferences(
					new ReferenceKey(BRAND, 1)
				);
			assertEquals(2, refs.size());
		}
	}

	@Nested
	@DisplayName("Removing references")
	class RemovingReferencesTest {

		@Test
		@DisplayName(
			"should remove reference by name and id"
		)
		void shouldRemoveReferenceByNameAndId() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setReference(STORE, 2);

			builder.removeReference(STORE, 1);

			assertEquals(
				1, builder.getReferences(STORE).size()
			);
			assertTrue(
				builder.getReference(STORE, 1).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should throw when removing duplicate "
				+ "by name and id"
		)
		void shouldThrowOnRemovingDuplicateByNameAndId() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "CZ")
			);
			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "DE")
			);

			assertThrows(
				ReferenceAllowsDuplicatesException.class,
				() -> builder.removeReference(BRAND, 1)
			);
		}

		@Test
		@DisplayName("should remove reference by ReferenceKey")
		void shouldRemoveReferenceByKey() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 5);

			final ReferenceContract stored =
				builder.getReference(STORE, 5).orElseThrow();
			final ReferenceKey key =
				stored.getReferenceKey();

			builder.removeReference(key);

			assertTrue(
				builder.getReferences(STORE).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should remove all refs with same name and id"
		)
		void shouldRemoveAllReferencesByNameAndId() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "CZ")
			);
			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "DE")
			);

			builder.removeReferences(BRAND, 1);

			assertTrue(
				builder.getReferences(BRAND).isEmpty()
			);
		}

		@Test
		@DisplayName("should remove all refs by name")
		void shouldRemoveAllReferencesByName() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setReference(STORE, 2);
			builder.setReference(STORE, 3);

			builder.removeReferences(STORE);

			assertTrue(
				builder.getReferences(STORE).isEmpty()
			);
		}

		@Test
		@DisplayName("should remove references by predicate")
		void shouldRemoveReferencesByPredicate() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setReference(STORE, 2);
			builder.setReference(STORE, 3);

			builder.removeReferences(
				ref -> ref.getReferencedPrimaryKey() == 2
			);

			assertEquals(
				2, builder.getReferences(STORE).size()
			);
			assertTrue(
				builder.getReference(STORE, 2).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should remove references by name and predicate"
		)
		void shouldRemoveReferencesByNameAndPredicate() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setReference(STORE, 2);

			builder.removeReferences(
				STORE,
				ref -> ref.getReferencedPrimaryKey() == 1
			);

			assertEquals(
				1, builder.getReferences(STORE).size()
			);
			assertTrue(
				builder.getReference(STORE, 1).isEmpty()
			);
		}
	}

	@Nested
	@DisplayName("Mutations")
	class MutationsTest {

		@Test
		@DisplayName(
			"should apply InsertReferenceMutation"
		)
		void shouldApplyInsertReferenceMutation() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			final ReferenceKey key =
				new ReferenceKey(STORE, 1, -1);
			final InsertReferenceMutation mutation =
				new InsertReferenceMutation(
					key, Cardinality.ZERO_OR_MORE, STORE
				);

			builder.mutateReference(mutation);

			assertFalse(
				builder.getReferences(STORE).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should apply SetReferenceGroupMutation"
		)
		void shouldApplySetReferenceGroupMutation() {
			final EntitySchemaContract schema =
				createSchemaWithGroupReference();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(BRAND, 1);

			final ReferenceContract ref =
				builder.getReference(BRAND, 1).orElseThrow();
			final SetReferenceGroupMutation mutation =
				new SetReferenceGroupMutation(
					ref.getReferenceKey(), GROUP, 100
				);

			builder.mutateReference(mutation);

			final ReferenceContract updated =
				builder.getReference(BRAND, 1).orElseThrow();
			assertTrue(updated.getGroup().isPresent());
			final GroupEntityReference group =
				updated.getGroup().get();
			assertEquals(GROUP, group.getType());
			assertEquals(100, group.getPrimaryKey());
		}

		@Test
		@DisplayName(
			"should apply RemoveReferenceGroupMutation"
		)
		void shouldApplyRemoveReferenceGroupMutation() {
			final EntitySchemaContract schema =
				createSchemaWithGroupReference();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(BRAND, 1);

			// set a group first
			final ReferenceContract ref =
				builder.getReference(BRAND, 1).orElseThrow();
			builder.mutateReference(
				new SetReferenceGroupMutation(
					ref.getReferenceKey(), GROUP, 100
				)
			);

			// now remove it
			final ReferenceContract refWithGroup =
				builder.getReference(BRAND, 1).orElseThrow();
			builder.mutateReference(
				new RemoveReferenceGroupMutation(
					refWithGroup.getReferenceKey()
				)
			);

			final ReferenceContract updated =
				builder.getReference(BRAND, 1).orElseThrow();
			assertTrue(updated.getGroup().isEmpty());
		}

		@Test
		@DisplayName(
			"should apply RemoveReferenceMutation"
		)
		void shouldApplyRemoveReferenceMutation() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);

			final ReferenceContract ref =
				builder.getReference(STORE, 1).orElseThrow();
			builder.mutateReference(
				new RemoveReferenceMutation(
					ref.getReferenceKey()
				)
			);

			assertTrue(
				builder.getReferences(STORE).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should apply ReferenceAttributeMutation"
		)
		void shouldApplyReferenceAttributeMutation() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setOrUpdateReference(
				BRAND, 1, ref -> false,
				ref -> ref.setAttribute(COUNTRY, "CZ")
			);

			// Get the first (and possibly only non-duplicate)
			// brand reference via the collection
			final ReferenceContract brandRef =
				builder.getReferences(BRAND).iterator().next();
			final ReferenceKey refKey =
				brandRef.getReferenceKey();

			final ReferenceAttributeMutation mutation =
				new ReferenceAttributeMutation(
					refKey,
					new UpsertAttributeMutation(COUNTRY, "SK")
				);

			builder.mutateReference(mutation);

			final ReferenceContract updated =
				builder.getReference(refKey).orElseThrow();
			assertEquals(
				"SK",
				updated.getAttribute(COUNTRY)
			);
		}
	}

	@Nested
	@DisplayName("Change set and build")
	class ChangeSetAndBuildTest {

		@Test
		@DisplayName(
			"should build change set for simple references"
		)
		void shouldBuildChangeSetForSimpleReferences() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setReference(STORE, 2);

			final List<? extends ReferenceMutation<?>> mutations =
				builder.buildChangeSet()
					.collect(Collectors.toList());

			// Each simple reference produces an
			// InsertReferenceMutation
			final long insertCount = mutations.stream()
				.filter(
					m -> m instanceof InsertReferenceMutation
				)
				.count();
			assertEquals(2, insertCount);
		}

		@Test
		@DisplayName(
			"should build change set with groups and attributes"
		)
		void
		shouldBuildChangeSetForReferencesWithGroupsAndAttributes() {
			final EntitySchemaContract schema =
				createSchemaWithGroupReference();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(
				BRAND, 1,
				ref -> {
					ref.setGroup(GROUP, 100);
					ref.setAttribute("note", "test");
				}
			);

			final List<? extends ReferenceMutation<?>> mutations =
				builder.buildChangeSet()
					.collect(Collectors.toList());

			// InsertReferenceMutation + SetReferenceGroupMutation
			// + ReferenceAttributeMutation
			assertTrue(mutations.size() >= 3);
			assertTrue(
				mutations.stream().anyMatch(
					m -> m instanceof InsertReferenceMutation
				)
			);
			assertTrue(
				mutations.stream().anyMatch(
					m -> m instanceof SetReferenceGroupMutation
				)
			);
			assertTrue(
				mutations.stream().anyMatch(
					m -> m instanceof ReferenceAttributeMutation
				)
			);
		}

		@Test
		@DisplayName(
			"should build empty change set from empty builder"
		)
		void shouldBuildEmptyChangeSet() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			final List<? extends ReferenceMutation<?>> mutations =
				builder.buildChangeSet()
					.collect(Collectors.toList());

			assertTrue(mutations.isEmpty());
		}

		@Test
		@DisplayName(
			"should build valid References instance"
		)
		void shouldBuildReferencesInstance() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);
			builder.setReference(STORE, 2);

			final References references = builder.build();
			assertNotNull(references);
			assertEquals(2, references.getReferences().size());
		}

		@Test
		@DisplayName(
			"should build empty References from empty builder"
		)
		void shouldBuildEmptyReferencesInstance() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			final References references = builder.build();
			assertNotNull(references);
			assertTrue(references.getReferences().isEmpty());
		}
	}

	@Nested
	@DisplayName("Cardinality management")
	class CardinalityManagementTest {

		@Test
		@DisplayName(
			"should promote cardinality when evolution allowed"
		)
		void shouldPromoteCardinalityWhenEvolutionAllowed() {
			// PRODUCT_SCHEMA has all EvolutionModes enabled
			final EntitySchemaContract schema =
				createSchemaWithSingleRef();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			// First reference is fine (ZERO_OR_ONE)
			builder.setReference(STORE, 1);
			// Second reference triggers cardinality promotion
			builder.setReference(STORE, 2);

			// Both references should exist after promotion
			assertEquals(
				2, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should throw when cardinality violated "
				+ "and evolution not allowed"
		)
		void shouldThrowWhenCardinalityViolated() {
			final EntitySchemaContract schema =
				createSchemaWithStrictCardinality();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			builder.setReference(STORE, 1);

			assertThrows(
				ReferenceCardinalityViolatedException.class,
				() -> builder.setReference(STORE, 2)
			);
		}

		@Test
		@DisplayName(
			"should generate decreasing negative internal ids"
		)
		void shouldGenerateDecrementingInternalIds() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);

			final int first = builder.getNextReferenceInternalId();
			final int second =
				builder.getNextReferenceInternalId();
			final int third = builder.getNextReferenceInternalId();

			assertTrue(first < 0);
			assertTrue(second < first);
			assertTrue(third < second);
		}
	}
}
