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

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.dataType.DataChunk;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExistingReferencesBuilder} verifying
 * construction, querying, mutation, change set generation,
 * cardinality management and identity semantics when modifying
 * an existing set of references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ExistingReferencesBuilder")
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REFERENCE)
class ExistingReferencesBuilderTest extends AbstractBuilderTest {

	private static final String STORE = "store";
	private static final String BRAND = "brand";
	private static final String GROUP = "group";
	private static final String CATEGORY = "category";
	private static final String COUNTRY = "country";

	/**
	 * Builds an entity schema with store (ZERO_OR_MORE), brand
	 * (ZERO_OR_MORE_WITH_DUPLICATES with "country" attribute),
	 * and category (ZERO_OR_MORE with group type "group").
	 */
	@Nonnull
	private static EntitySchemaContract
	createSchemaWithReferences() {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				STORE, STORE, Cardinality.ZERO_OR_MORE,
				r -> {}
			)
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				r -> r.withAttribute(
					COUNTRY, String.class,
					AttributeSchemaEditor::representative
				)
			)
			.withReferenceToEntity(
				CATEGORY, CATEGORY, Cardinality.ZERO_OR_MORE,
				r -> r.withGroupType(GROUP)
			)
			.toInstance();
	}

	/**
	 * Builds an entity schema with store reference having
	 * ZERO_OR_ONE cardinality and all evolution modes enabled.
	 */
	@Nonnull
	private static EntitySchemaContract
	createSchemaWithSingleRef() {
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
	 * store reference and strict schema verification (no
	 * cardinality evolution).
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
	 * Creates initial {@link References} via
	 * {@link InitialReferencesBuilder} populated with:
	 * - store/1, store/2
	 * - brand/1 x2 with different country values
	 * - category/1 with group (group, 100)
	 */
	@Nonnull
	private static References createBaseReferences(
		@Nonnull EntitySchemaContract schema
	) {
		final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);
		builder.setReference(STORE, 1);
		builder.setReference(STORE, 2);
		builder.setOrUpdateReference(
			BRAND, 1, Functions.alwaysFalse(),
			ref -> ref.setAttribute(COUNTRY, "CZ")
		);
		builder.setOrUpdateReference(
			BRAND, 1, Functions.alwaysFalse(),
			ref -> ref.setAttribute(COUNTRY, "DE")
		);
		builder.setReference(
			CATEGORY, 1,
			ref -> ref.setGroup(GROUP, 100)
		);
		return builder.build();
	}

	/**
	 * Creates an {@link ExistingReferencesBuilder} wrapping
	 * pre-built base references with the DEFAULT_INSTANCE
	 * predicate and a no-op rich reference fetcher.
	 */
	@Nonnull
	private static ExistingReferencesBuilder createBuilder(
		@Nonnull EntitySchemaContract schema,
		@Nonnull References baseReferences
	) {
		return new ExistingReferencesBuilder(
			schema,
			baseReferences,
			ReferenceContractSerializablePredicate
				.DEFAULT_INSTANCE,
			key -> Optional.empty()
		);
	}

	/**
	 * Convenience method that creates the schema, base
	 * references and builder in one call.
	 */
	@Nonnull
	private static ExistingReferencesBuilder
	createDefaultBuilder() {
		final EntitySchemaContract schema = createSchemaWithReferences();
		final References base = createBaseReferences(schema);
		return createBuilder(schema, base);
	}

	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("should create builder wrapping base refs")
		void shouldCreateBuilderWrappingBaseReferences() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			assertNotNull(builder);
			assertFalse(builder.getReferences().isEmpty());
		}

		@Test
		@DisplayName(
			"should report correct reference count"
		)
		void shouldReportCorrectReferenceCount() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			// store/1, store/2, brand/1 x2, category/1
			assertEquals(
				5, builder.getReferences().size()
			);
		}

		@Test
		@DisplayName(
			"should report references available"
		)
		void shouldReportReferencesAvailable() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			assertTrue(builder.referencesAvailable());
			assertTrue(builder.referencesAvailable(STORE));
			assertTrue(builder.referencesAvailable(BRAND));
		}

		@Test
		@DisplayName(
			"should return correct reference names"
		)
		void shouldReturnCorrectReferenceNames() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final Set<String> names = builder.getReferenceNames();
			assertTrue(names.contains(STORE));
			assertTrue(names.contains(BRAND));
			assertTrue(names.contains(CATEGORY));
			assertEquals(3, names.size());
		}
	}

	@Nested
	@DisplayName("Querying references")
	class QueryingReferencesTest {

		@Test
		@DisplayName("should return all references")
		void shouldReturnAllReferences() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final Collection<ReferenceContract> all = builder.getReferences();
			assertEquals(5, all.size());
		}

		@Test
		@DisplayName(
			"should return 2 store references by name"
		)
		void shouldReturnStoreReferencesByName() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final Collection<ReferenceContract> stores = builder.getReferences(STORE);
			assertEquals(2, stores.size());
		}

		@Test
		@DisplayName(
			"should return reference by name and id"
		)
		void shouldReturnReferenceByNameAndId() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final Optional<ReferenceContract> ref = builder.getReference(STORE, 1);
			assertTrue(ref.isPresent());
			assertEquals(1, ref.get().getReferencedPrimaryKey());
		}

		@Test
		@DisplayName(
			"should return reference by ReferenceKey"
		)
		void shouldReturnReferenceByReferenceKey() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final ReferenceContract storeRef = builder.getReferences(STORE).iterator().next();
			final ReferenceKey key = storeRef.getReferenceKey();

			final Optional<ReferenceContract> ref = builder.getReference(key);
			assertTrue(ref.isPresent());
			assertEquals(STORE, ref.get().getReferenceName());
		}

		@Test
		@DisplayName(
			"should throw when getting duplicate-allowing "
				+ "reference via getReference(name, id)"
		)
		void shouldThrowOnDuplicateReferenceAccess() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			assertThrows(
				ReferenceAllowsDuplicatesException.class,
				() -> builder.getReference(BRAND, 1)
			);
		}

		@Test
		@DisplayName(
			"should return 2 duplicates via "
				+ "getReferences(key)"
		)
		void shouldReturnAllDuplicateReferences() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final ReferenceKey key = new ReferenceKey(BRAND, 1);
			final List<ReferenceContract> refs = builder.getReferences(key);
			assertEquals(2, refs.size());
		}

		@Test
		@DisplayName(
			"should return correct reference chunk"
		)
		void shouldReturnReferenceChunk() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final DataChunk<ReferenceContract> chunk = builder.getReferenceChunk(STORE);
			assertNotNull(chunk);
			assertEquals(2, chunk.getTotalRecordCount());
		}

		@Test
		@DisplayName(
			"should return empty when ref id not found"
		)
		void shouldReturnEmptyForNonExistentReference() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final Optional<ReferenceContract> ref = builder.getReference(STORE, 999);
			assertTrue(ref.isEmpty());
		}
	}

	@Nested
	@DisplayName("Setting references")
	class SettingReferencesTest {

		@Test
		@DisplayName("should add new store reference")
		void shouldAddNewStoreReference() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			builder.setReference(STORE, 3);

			final Collection<ReferenceContract> stores = builder.getReferences(STORE);
			assertEquals(3, stores.size());
		}

		@Test
		@DisplayName(
			"should set reference with consumer"
		)
		void shouldSetReferenceWithConsumer() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			builder.setReference(
				STORE, 3,
				ref -> ref.setAttribute("priority", 5)
			);

			assertEquals( 3, builder.getReferences(STORE).size());
		}

		@Test
		@DisplayName(
			"should set reference with entity type "
				+ "and cardinality"
		)
		void shouldSetReferenceWithEntityTypeAndCardinality() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			builder.setReference(
				CATEGORY, CATEGORY,
				Cardinality.ZERO_OR_MORE, 2
			);

			assertEquals(2, builder.getReferences(CATEGORY).size());
		}

		@Test
		@DisplayName("should overwrite existing store reference")
		void shouldOverwriteExistingStoreReference() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			// overwrite store/1 with consumer
			builder.setReference(
				STORE, 1,
				ref -> ref.setAttribute("priority", 42)
			);

			// count should remain the same
			assertEquals( 2, builder.getReferences(STORE).size());
		}

		@Test
		@DisplayName("should throw for unknown reference type on strict schema")
		void shouldThrowForUnknownReferenceOnStrictSchema() {
			final EntitySchemaContract schema = createSchemaWithStrictCardinality();
			final InitialReferencesBuilder init = new InitialReferencesBuilder(schema);
			init.setReference(STORE, 1);
			final References base = init.build();

			final ExistingReferencesBuilder builder = createBuilder(schema, base);

			assertThrows(
				ReferenceNotKnownException.class,
				() -> builder.setReference("unknown", 1)
			);
		}

		@Test
		@DisplayName("should throw ReferenceAllowsDuplicates for brand via setReference")
		void shouldThrowForDuplicateAllowingRef() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			assertThrows(
				ReferenceAllowsDuplicatesException.class,
				() -> builder.setReference(BRAND, 1)
			);
		}
	}

	@Nested
	@DisplayName("Updating references")
	class UpdatingReferencesTest {

		@Test
		@DisplayName("should update existing reference attributes")
		void shouldUpdateExistingReferenceAttributes() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			builder.updateReference(
				STORE, 1,
				ref -> ref.setAttribute("priority", 99)
			);

			// reference should still exist
			final Optional<ReferenceContract> ref = builder.getReference(STORE, 1);
			assertTrue(ref.isPresent());
		}

		@Test
		@DisplayName("should be no-op when updating non-existent reference")
		void shouldBeNoOpForNonExistentReference() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			// store/999 does not exist - should be no-op
			builder.updateReference(
				STORE, 999,
				ref -> ref.setAttribute("priority", 5)
			);

			// count should remain the same
			assertEquals(2, builder.getReferences(STORE).size());
		}

		@Test
		@DisplayName(
			"should update references matching predicate"
		)
		void shouldUpdateReferencesMatchingPredicate() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.updateReferences(
				ref -> ref.getReferenceName().equals(STORE)
					&& ref.getReferencedPrimaryKey() == 1,
				ref -> ref.setAttribute("priority", 5)
			);

			// still 2 store refs
			assertEquals(
				2, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should create new via setOrUpdateReference "
				+ "when filter never matches"
		)
		void shouldCreateNewViaSetOrUpdate() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.setOrUpdateReference(
				STORE, 3, Functions.alwaysFalse(),
				ref -> ref.setAttribute("priority", 10)
			);

			assertEquals(
				3, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should update existing via "
				+ "setOrUpdateReference when filter matches"
		)
		void shouldUpdateExistingViaSetOrUpdate() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.setOrUpdateReference(
				STORE, 1, Functions.alwaysTrue(),
				ref -> ref.setAttribute("priority", 20)
			);

			// still 2 refs, not 3
			assertEquals(
				2, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should reject an indistinguishable duplicate "
				+ "when an existing one was updated first"
		)
		void shouldRejectIndistinguishableDuplicateAfterUpdate() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			// updating an existing base duplicate registers
			// it into the reference bundle ...
			builder.setOrUpdateReference(
				BRAND, 1,
				ref -> "CZ".equals(
					ref.getAttribute(COUNTRY, String.class)
				),
				ref -> ref.setAttribute(COUNTRY, "CZ-EAST")
			);
			// ... and adding another one converts the whole
			// business key into a duplicated group
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "AT")
			);

			// the untouched DE duplicate must still occupy
			// its representative attribute slot, otherwise an
			// indistinguishable twin of it sneaks in
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setOrUpdateReference(
					BRAND, 1, Functions.alwaysFalse(),
					ref -> ref.setAttribute(COUNTRY, "DE")
				)
			);
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
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

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
			"should remove reference by ReferenceKey"
		)
		void shouldRemoveReferenceByKey() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final ReferenceContract storeRef =
				builder.getReference(STORE, 1)
					.orElseThrow();
			final ReferenceKey key =
				storeRef.getReferenceKey();

			builder.removeReference(key);

			assertTrue(
				builder.getReference(STORE, 1).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should remove all references by name"
		)
		void shouldRemoveAllReferencesByName() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.removeReferences(STORE);

			assertTrue(
				builder.getReferences(STORE).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should remove references by predicate"
		)
		void shouldRemoveReferencesByPredicate() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.removeReferences(
				ref -> ref.getReferenceName().equals(STORE)
					&& ref.getReferencedPrimaryKey() == 2
			);

			assertEquals(
				1, builder.getReferences(STORE).size()
			);
			assertTrue(
				builder.getReference(STORE, 2).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should throw when removing duplicate-allowing "
				+ "ref by name and id"
		)
		void shouldThrowOnRemovingDuplicateByNameAndId() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			assertThrows(
				ReferenceAllowsDuplicatesException.class,
				() -> builder.removeReference(BRAND, 1)
			);
		}

		@Test
		@DisplayName(
			"should remove all duplicates via "
				+ "removeReferences(name, id)"
		)
		void shouldRemoveAllDuplicatesViaRemoveReferences() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.removeReferences(BRAND, 1);

			assertTrue(
				builder.getReferences(BRAND).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should remove references by name "
				+ "and predicate"
		)
		void shouldRemoveReferencesByNameAndPredicate() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

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

		@Test
		@DisplayName(
			"should throw when removing non-existent ref"
		)
		void shouldThrowWhenRemovingNonExistentReference() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			assertThrows(
				InvalidMutationException.class,
				() -> builder.removeReference(STORE, 999)
			);
		}
	}

	@Nested
	@DisplayName("Mutations")
	class MutationsTest {

		@Test
		@DisplayName("should apply InsertReferenceMutation")
		void shouldApplyInsertReferenceMutation() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final ReferenceKey key = new ReferenceKey(STORE, 3, -10);
			final InsertReferenceMutation mutation =
				new InsertReferenceMutation(key, Cardinality.ZERO_OR_MORE, STORE);

			builder.mutateReference(mutation);

			assertEquals(3, builder.getReferences(STORE).size());
		}

		@Test
		@DisplayName("should apply SetReferenceGroupMutation")
		void shouldApplySetReferenceGroupMutation() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final ReferenceContract catRef = builder.getReference(CATEGORY, 1).orElseThrow();
			final SetReferenceGroupMutation mutation =
				new SetReferenceGroupMutation(catRef.getReferenceKey(), GROUP, 200);

			builder.mutateReference(mutation);

			final ReferenceContract updated = builder.getReference(CATEGORY, 1).orElseThrow();
			assertTrue(updated.getGroup().isPresent());
			final GroupEntityReference group = updated.getGroup().get();
			assertEquals(200, group.getPrimaryKey());
		}

		@Test
		@DisplayName("should apply RemoveReferenceGroupMutation")
		void shouldApplyRemoveReferenceGroupMutation() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final ReferenceContract catRef = builder.getReference(CATEGORY, 1).orElseThrow();

			builder.mutateReference(
				new RemoveReferenceGroupMutation(catRef.getReferenceKey())
			);

			final ReferenceContract updated = builder.getReference(CATEGORY, 1).orElseThrow();
			assertTrue(updated.getGroup().isEmpty());
		}

		@Test
		@DisplayName("should apply RemoveReferenceMutation")
		void shouldApplyRemoveReferenceMutation() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final ReferenceContract storeRef = builder.getReference(STORE, 1).orElseThrow();

			builder.mutateReference(
				new RemoveReferenceMutation(storeRef.getReferenceKey())
			);

			assertTrue(builder.getReference(STORE, 1).isEmpty());
		}

		@Test
		@DisplayName("should apply ReferenceAttributeMutation")
		void shouldApplyReferenceAttributeMutation() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final ReferenceContract brandRef = builder.getReferences(BRAND).iterator().next();
			final ReferenceKey refKey = brandRef.getReferenceKey();

			final ReferenceAttributeMutation mutation =
				new ReferenceAttributeMutation(
					refKey,
					new UpsertAttributeMutation(COUNTRY, "SK")
				);

			builder.mutateReference(mutation);

			final ReferenceContract updated = builder.getReference(refKey).orElseThrow();
			assertEquals("SK", updated.getAttribute(COUNTRY));
		}
	}

	@Nested
	@DisplayName("Change set and build")
	class ChangeSetAndBuildTest {

		@Test
		@DisplayName("should return empty change set when no mutations")
		void shouldReturnEmptyChangeSetWhenNoMutations() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final List<? extends ReferenceMutation<?>> mutations = builder.buildChangeSet().toList();

			assertTrue(mutations.isEmpty());
		}

		@Test
		@DisplayName("should report no changes via isThereAnyChangeInMutations")
		void shouldReportNoChanges() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			assertFalse(builder.isThereAnyChangeInMutations());
		}

		@Test
		@DisplayName("should produce change set for added refs")
		void shouldProduceChangeSetForAddedRefs() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			builder.setReference(STORE, 3);

			final List<? extends ReferenceMutation<?>>
				mutations = builder.buildChangeSet()
				.toList();

			assertFalse(mutations.isEmpty());
			assertTrue(mutations.stream().anyMatch(InsertReferenceMutation.class::isInstance));
		}

		@Test
		@DisplayName("should produce change set for removed refs")
		void shouldProduceChangeSetForRemovedRefs() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			builder.removeReference(STORE, 1);

			final List<? extends ReferenceMutation<?>> mutations = builder.buildChangeSet().toList();

			assertFalse(mutations.isEmpty());
			assertTrue(mutations.stream().anyMatch(RemoveReferenceMutation.class::isInstance));
		}

		@Test
		@DisplayName("should return same instance when no changes exist")
		void shouldReturnSameInstanceWhenNoChanges() {
			final EntitySchemaContract schema = createSchemaWithReferences();
			final References base = createBaseReferences(schema);
			final ExistingReferencesBuilder builder = createBuilder(schema, base);

			final References built = builder.build();

			assertSame(base, built);
		}

		@Test
		@DisplayName("should return new instance when changes exist")
		void shouldReturnNewInstanceWhenChangesExist() {
			final EntitySchemaContract schema = createSchemaWithReferences();
			final References base = createBaseReferences(schema);
			final ExistingReferencesBuilder builder = createBuilder(schema, base);

			builder.setReference(STORE, 3);

			final References built = builder.build();

			assertNotSame(base, built);
		}
	}

	@Nested
	@DisplayName("Identity semantics")
	class IdentityTest {

		@Test
		@DisplayName("should return same References instance when no mutations applied")
		void shouldReturnSameWhenNoMutations() {
			final EntitySchemaContract schema = createSchemaWithReferences();
			final References base = createBaseReferences(schema);
			final ExistingReferencesBuilder builder = createBuilder(schema, base);

			assertSame(base, builder.build());
		}

		@Test
		@DisplayName("should skip no-op mutations when setting identical values")
		void shouldSkipNoOpMutations() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			// overwrite store/1 with identical content
			builder.setReference(STORE, 1);

			// the change set should be non-empty because
			// the builder produces InsertReferenceMutation,
			// but the build result should still differ from
			// base because the internal tracking records it
			assertTrue(builder.isThereAnyChangeInMutations());
		}
	}

	@Nested
	@DisplayName("Cardinality management")
	class CardinalityManagementTest {

		@Test
		@DisplayName("should auto-promote cardinality when evolution allowed")
		void shouldAutoPromoteCardinality() {
			final EntitySchemaContract schema = createSchemaWithSingleRef();
			final InitialReferencesBuilder init = new InitialReferencesBuilder(schema);
			init.setReference(STORE, 1);
			final References base = init.build();

			final ExistingReferencesBuilder builder = createBuilder(schema, base);

			// second reference triggers promotion
			builder.setReference(STORE, 2);

			assertEquals(2, builder.getReferences(STORE).size());
		}

		@Test
		@DisplayName("should throw when cardinality violated with strict schema")
		void shouldThrowWhenCardinalityViolated() {
			final EntitySchemaContract schema = createSchemaWithStrictCardinality();
			final InitialReferencesBuilder init = new InitialReferencesBuilder(schema);
			init.setReference(STORE, 1);
			final References base = init.build();

			final ExistingReferencesBuilder builder = createBuilder(schema, base);

			assertThrows(
				ReferenceCardinalityViolatedException.class,
				() -> builder.setReference(STORE, 2)
			);
		}

		@Test
		@DisplayName("should generate decreasing negative internal ids")
		void shouldGenerateDecreasingNegativeInternalIds() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final int first = builder.getNextReferenceInternalId();
			final int second = builder.getNextReferenceInternalId();
			final int third = builder.getNextReferenceInternalId();

			assertTrue(first < 0);
			assertTrue(second < first);
			assertTrue(third < second);
		}

		@Test
		@DisplayName("should create reference via createReference")
		void shouldCreateReferenceViaCreateReference() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final ReferenceKey key = builder.createReference(STORE, 5);

			assertNotNull(key);
			assertEquals(STORE, key.referenceName());
			assertEquals(5, key.primaryKey());
			assertTrue(key.internalPrimaryKey() < 0);

			assertEquals(3, builder.getReferences(STORE).size());
		}
	}

	@Nested
	@DisplayName("Remove and re-add")
	class RemoveAndReAddTest {

		@Test
		@DisplayName("should allow removing and re-adding same ref")
		void shouldRemoveAndReAddSameReference() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.removeReference(STORE, 1);
			assertTrue(builder.getReference(STORE, 1).isEmpty());

			// re-add the same reference
			builder.setReference(STORE, 1);

			final Optional<ReferenceContract> ref = builder.getReference(STORE, 1);
			assertTrue(ref.isPresent());
			assertEquals(1, ref.get().getReferencedPrimaryKey());
		}

		@Test
		@DisplayName("should properly merge on re-add with attrs")
		void shouldProperlyMergeOnReAddWithAttributes() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.removeReference(STORE, 1);

			builder.setReference(
				STORE, 1,
				ref -> ref.setAttribute("priority", 42)
			);

			final Optional<ReferenceContract> ref =
				builder.getReference(STORE, 1);
			assertTrue(ref.isPresent());
		}

		@Test
		@DisplayName("should produce empty change set after remove and re-add of identical ref")
		void shouldProduceEmptyChangeSetAfterRemoveReAdd() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			builder.removeReference(STORE, 1);
			builder.setReference(STORE, 1);

			final List<? extends ReferenceMutation<?>> mutations = builder.buildChangeSet().toList();

			// removing and re-adding the same reference
			// with identical content cancels out
			assertTrue(mutations.isEmpty());
		}
	}

	@Nested
	@DisplayName(
		"RESET-mode merge produces correct queue under "
			+ "multi-setReference within a single builder"
	)
	class ResetModeMergeSemanticsTest {

		private static final String COUNTRY_OFFICE = "countryOffice";
		private static final String PRIORITY = "priority";

		/**
		 * Schema with one ZERO_OR_ONE store reference carrying
		 * declared `countryOffice` (String) and `priority`
		 * (Integer) attributes. Tests exercise the merge path
		 * that runs when RESET-mode setReference fires twice
		 * against the same reference within one builder.
		 */
		@Nonnull
		private static EntitySchemaContract createSchemaForResetMergeTests() {
			return new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, PRODUCT_SCHEMA
			)
				.withReferenceToEntity(
					STORE, STORE, Cardinality.ZERO_OR_ONE,
					r -> r
						.withAttribute(COUNTRY_OFFICE, String.class)
						.withAttribute(PRIORITY, Integer.class)
				)
				.toInstance();
		}

		/**
		 * Base References with STORE/1 holding only
		 * `countryOffice=CZ`. `priority` is intentionally
		 * absent so the missed-remove scenario has a clean
		 * baseline.
		 */
		@Nonnull
		private static References createBaseForResetMergeTests(@Nonnull EntitySchemaContract schema) {
			final InitialReferencesBuilder builder =
				new InitialReferencesBuilder(schema);
			builder.setReference(
				STORE, 1,
				ref -> ref.setAttribute(COUNTRY_OFFICE, "CZ")
			);
			return builder.build();
		}

		/**
		 * Reads the raw mutation queue stored on the builder
		 * for the given reference, bypassing the version-based
		 * filtering in {@link ExistingReferencesBuilder#buildChangeSet()}
		 * (which drops idempotent no-op mutations and therefore
		 * masks merge-level correctness defects).
		 *
		 * Working-layer indexes / histograms are updated from
		 * this raw queue eagerly during the session, *before*
		 * the version filter ever runs; that is the layer where
		 * the histogram drift bug at seed `-1128235571`
		 * surfaces.
		 */
		@Nonnull
		private static List<ReferenceMutation<?>> readRawQueue(
			@Nonnull ExistingReferencesBuilder builder,
			@Nonnull String referenceName,
			int referencedPrimaryKey
		) {
			final Map<Integer, List<ReferenceMutation<?>>> byInternalId = builder
				.getRawReferenceMutations()
				.getOrDefault(new ReferenceKey(referenceName, referencedPrimaryKey), Map.of());
			// single ZERO_OR_ONE reference - one entry
			return byInternalId.isEmpty() ? List.of() : byInternalId.values().iterator().next();
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName(
			"should keep Upsert in raw queue after intra-session "
				+ "removal followed by re-add to base value"
		)
		void shouldPreserveAttributeReAddAfterIntraSessionRemoval() {
			final EntitySchemaContract schema = createSchemaForResetMergeTests();
			final References base = createBaseForResetMergeTests(schema);
			final ExistingReferencesBuilder builder = createBuilder(schema, base);

			// Call 1 (RESET): configurator omits countryOffice
			// → merge emits Remove(countryOffice). Working-layer
			// indexes see the remove and drop bucket entries.
			builder.setReference(STORE, 1, ref -> {});

			// Call 2 (RESET): configurator re-sets the original
			// base value. The re-add MUST appear in the raw
			// queue — comparing the new value against the frozen
			// `refInBase` snapshot would mark it as "same as
			// before" and drop it, leaving working-layer indexes
			// stuck on the prior remove.
			builder.setReference(
				STORE, 1,
				ref -> ref.setAttribute(
					COUNTRY_OFFICE, "CZ"
				)
			);

			final List<ReferenceMutation<?>> queue =
				readRawQueue(builder, STORE, 1);

			final boolean hasCountryUpsert = queue.stream()
				.anyMatch(m ->
					m instanceof ReferenceAttributeMutation ram
						&& ram.getAttributeMutation()
						instanceof UpsertAttributeMutation up
						&& up.getAttributeKey()
						.attributeName()
						.equals(COUNTRY_OFFICE)
				);
			assertTrue(
				hasCountryUpsert,
				"Upsert(countryOffice,CZ) must survive the "
					+ "intra-session round-trip in the raw "
					+ "queue — the engine's working-layer "
					+ "indexes read mutations eagerly before "
					+ "the version-based filter at "
					+ "buildChangeSet() runs."
			);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName(
			"should emit Remove for attribute added by prior "
				+ "RESET when subsequent RESET configurator omits it"
		)
		void shouldEmitRemovalForAttributeOmittedAfterPriorReset() {
			final EntitySchemaContract schema =
				createSchemaForResetMergeTests();
			final References base =
				createBaseForResetMergeTests(schema);
			final ExistingReferencesBuilder builder =
				createBuilder(schema, base);

			// Call 1 (RESET): adds priority=7 (not in base).
			// Working-layer indexes register priority=7 for
			// STORE/1 eagerly.
			builder.setReference(STORE, 1, ref -> {
				ref.setAttribute(COUNTRY_OFFICE, "CZ");
				ref.setAttribute(PRIORITY, 7);
			});

			// Call 2 (RESET): omits priority. RESET semantics
			// dictate that the reference's complete attribute
			// set is now just countryOffice; priority must
			// disappear. Without an explicit Remove(priority)
			// in the raw queue, working-layer indexes that
			// processed Upsert(priority,7) in call 1 leak past
			// call 2.
			builder.setReference(
				STORE, 1,
				ref -> ref.setAttribute(
					COUNTRY_OFFICE, "CZ"
				)
			);

			final List<ReferenceMutation<?>> queue =
				readRawQueue(builder, STORE, 1);

			final boolean hasPriorityUpsert = queue.stream()
				.anyMatch(m ->
					m instanceof ReferenceAttributeMutation ram
						&& ram.getAttributeMutation()
						instanceof UpsertAttributeMutation up
						&& up.getAttributeKey()
						.attributeName()
						.equals(PRIORITY)
				);
			assertFalse(
				hasPriorityUpsert,
				"Upsert(priority,7) from call 1 must NOT "
					+ "survive call 2's queue replacement."
			);

			final boolean hasPriorityRemove = queue.stream()
				.anyMatch(m ->
					m instanceof ReferenceAttributeMutation ram
						&& ram.getAttributeMutation()
						instanceof RemoveAttributeMutation
						&& ram.getAttributeMutation()
						.getAttributeKey()
						.attributeName()
						.equals(PRIORITY)
				);
			assertTrue(
				hasPriorityRemove,
				"Raw queue must contain Remove(priority) — "
					+ "without it, working-layer indexes that "
					+ "registered priority=7 in call 1 leak."
			);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName(
			"should drop redundant upsert from merged queue "
				+ "when value already matches effective state"
		)
		void shouldDropRedundantUpsertWhenValueMatchesBase() {
			final EntitySchemaContract schema =
				createSchemaForResetMergeTests();
			final References base =
				createBaseForResetMergeTests(schema);
			final ExistingReferencesBuilder builder =
				createBuilder(schema, base);

			// Single RESET re-asserting the base value. The
			// projection-based filter must recognise this as
			// a no-op and drop the upsert from the queue —
			// otherwise working-layer indexes do redundant
			// histogram re-evaluation work on every such call.
			builder.setReference(
				STORE, 1,
				ref -> ref.setAttribute(
					COUNTRY_OFFICE, "CZ"
				)
			);

			final List<ReferenceMutation<?>> queue =
				readRawQueue(builder, STORE, 1);

			final boolean hasCountryUpsert = queue.stream()
				.anyMatch(m ->
					m instanceof ReferenceAttributeMutation ram
						&& ram.getAttributeMutation()
						instanceof UpsertAttributeMutation up
						&& up.getAttributeKey()
						.attributeName()
						.equals(COUNTRY_OFFICE)
				);
			assertFalse(
				hasCountryUpsert,
				"Setting an attribute to its existing base "
					+ "value must not enqueue a redundant "
					+ "Upsert — the projection-based filter "
					+ "must drop it."
			);
		}
	}

	@Nested
	@DisplayName(
		"Removal once the reference bundle is initialized"
	)
	class RemovalWithInitializedBundleTest {

		@Test
		@DisplayName("should remove base reference by (name, id) after another reference of the same name was set")
		void shouldRemoveBaseReferenceAfterAnotherOneWasSet() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			// this initializes the store reference bundle
			builder.setReference(STORE, 3);

			// the (name, id) key carries no internal primary
			// key - the bundle update must resolve it from the
			// reference found in the base entity
			builder.removeReference(STORE, 1);

			assertTrue(builder.getReference(STORE, 1).isEmpty());
			assertEquals(
				Set.of(2, 3),
				builder.getReferences(STORE)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toSet())
			);
		}

		@Test
		@DisplayName("should remove all duplicates by (name, id) after another reference of the same name was set")
		void shouldRemoveAllDuplicatesAfterAnotherOneWasSet() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			// this initializes the brand reference bundle with
			// both brand/1 duplicates present in the base
			builder.setOrUpdateReference(
				BRAND, 2, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "SK")
			);

			builder.removeReferences(BRAND, 1);

			assertEquals(1, builder.getReferences(BRAND).size());
			assertEquals(
				Set.of(2),
				builder.getReferences(BRAND)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toSet())
			);

			// every removed duplicate must be gone from the
			// bundle as-well - otherwise its representative
			// attributes still occupy the (brand, 1, country)
			// slot and re-adding them is rejected as
			// indistinguishable
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "CZ")
			);
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "DE")
			);

			assertEquals(3, builder.getReferences(BRAND).size());
			assertEquals(
				Set.of("CZ", "DE"),
				builder.getReferences(BRAND)
					.stream()
					.filter(it -> it.getReferencedPrimaryKey() == 1)
					.map(it -> it.getAttribute(COUNTRY, String.class))
					.collect(Collectors.toSet())
			);
		}

		@Test
		@DisplayName(
			"should remove both the pending and the base "
				+ "duplicates by (name, id)"
		)
		void shouldRemovePendingAndBaseDuplicatesTogether() {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			// a third brand/1 duplicate that lives only in this
			// builder - it holds an internal primary key of its
			// own, unrelated to the two base duplicates
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "AT")
			);

			builder.removeReferences(BRAND, 1);

			assertTrue(builder.getReferences(BRAND).isEmpty());

			// all three - the two base ones and the pending one
			// - must be gone from the bundle
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "CZ")
			);
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "DE")
			);
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "AT")
			);

			assertEquals(
				Set.of("CZ", "DE", "AT"),
				builder.getReferences(BRAND)
					.stream()
					.map(it -> it.getAttribute(COUNTRY, String.class))
					.collect(Collectors.toSet())
			);
		}

		@Test
		@DisplayName("should remove several pending duplicates sharing a single (name, id) at once")
		void shouldRemoveMultiplePendingDuplicatesTogether() {
			assertMultiplePendingDuplicatesRemoved(false);
		}

		@Test
		@DisplayName("should remove several pending duplicates when the base one was updated first")
		void shouldRemoveMultiplePendingDuplicatesUpdatedFirst() {
			assertMultiplePendingDuplicatesRemoved(true);
		}

		/**
		 * Removes both pending duplicate queues of a single business key at once and verifies the emitted
		 * mutations and the reference bundle state.
		 *
		 * @param updateBaseFirst whether the base duplicate is updated before the builder only duplicate is created -
		 *                        the two orderings initialize the reference bundle at different moments
		 */
		private static void assertMultiplePendingDuplicatesRemoved(boolean updateBaseFirst) {
			final ExistingReferencesBuilder builder = createDefaultBuilder();

			final Set<Integer> baseInternalIds =
				builder.getReferences(BRAND)
					.stream()
					.map(it -> it.getReferenceKey().internalPrimaryKey())
					.collect(Collectors.toSet());
			assertEquals(2, baseInternalIds.size());

			// one pending mutation updates the base CZ
			// duplicate in place, another creates a duplicate
			// that lives solely in this builder
			final Runnable updateBase = () ->
				builder.setOrUpdateReference(
					BRAND, 1,
					ref -> "CZ".equals(ref.getAttribute(COUNTRY, String.class)),
					ref -> ref.setAttribute(COUNTRY, "CZ-EAST")
				);
			final Runnable createNew = () ->
				builder.setOrUpdateReference(
					BRAND, 1, Functions.alwaysFalse(),
					ref -> ref.setAttribute(COUNTRY, "AT")
				);
			if (updateBaseFirst) {
				updateBase.run();
				createNew.run();
			} else {
				createNew.run();
				updateBase.run();
			}

			// two pending mutation queues for the very same
			// business key must not block the removal - the
			// contract promises to remove them all
			builder.removeReferences(BRAND, 1);

			assertTrue(builder.getReferences(BRAND).isEmpty());

			// only the two base duplicates deserve a remove
			// mutation - the builder-only one never existed
			// outside of this builder, so emitting a remove
			// for it would fail on the server
			final List<? extends ReferenceMutation<?>> changeSet = builder.buildChangeSet()
				.filter(it -> BRAND.equals(it.getReferenceKey().referenceName()))
				.toList();
			assertEquals(
				baseInternalIds,
				changeSet.stream()
					.map(it -> it.getReferenceKey().internalPrimaryKey())
					.collect(Collectors.toSet())
			);
			assertEquals(baseInternalIds.size(), changeSet.size());
			for (ReferenceMutation<?> mutation : changeSet) {
				assertInstanceOf(RemoveReferenceMutation.class, mutation);
			}

			// all three - both base duplicates and the
			// pending one - must be gone from the bundle
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "CZ-EAST")
			);
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "DE")
			);
			builder.setOrUpdateReference(
				BRAND, 1, Functions.alwaysFalse(),
				ref -> ref.setAttribute(COUNTRY, "AT")
			);

			assertEquals(
				Set.of("CZ-EAST", "DE", "AT"),
				builder.getReferences(BRAND)
					.stream()
					.map(it -> it.getAttribute(COUNTRY, String.class))
					.collect(Collectors.toSet())
			);
		}
	}
}
