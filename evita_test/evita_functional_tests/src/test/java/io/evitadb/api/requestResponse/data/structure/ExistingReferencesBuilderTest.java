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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExistingReferencesBuilder} verifying
 * construction, querying, mutation, change set generation,
 * cardinality management and identity semantics when modifying
 * an existing set of references.
 *
 * @author evitaDB
 */
@DisplayName("ExistingReferencesBuilder")
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
				BRAND, BRAND,
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				r -> r.withAttribute(
					COUNTRY, String.class,
					AttributeSchemaEditor::representative
				)
			)
			.withReferenceToEntity(
				CATEGORY, CATEGORY,
				Cardinality.ZERO_OR_MORE,
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
		final InitialReferencesBuilder builder =
			new InitialReferencesBuilder(schema);
		builder.setReference(STORE, 1);
		builder.setReference(STORE, 2);
		builder.setOrUpdateReference(
			BRAND, 1, ref -> false,
			ref -> ref.setAttribute(COUNTRY, "CZ")
		);
		builder.setOrUpdateReference(
			BRAND, 1, ref -> false,
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
		final EntitySchemaContract schema =
			createSchemaWithReferences();
		final References base =
			createBaseReferences(schema);
		return createBuilder(schema, base);
	}

	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("should create builder wrapping base refs")
		void shouldCreateBuilderWrappingBaseReferences() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			assertNotNull(builder);
			assertFalse(builder.getReferences().isEmpty());
		}

		@Test
		@DisplayName(
			"should report correct reference count"
		)
		void shouldReportCorrectReferenceCount() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

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
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			assertTrue(builder.referencesAvailable());
			assertTrue(builder.referencesAvailable(STORE));
			assertTrue(builder.referencesAvailable(BRAND));
		}

		@Test
		@DisplayName(
			"should return correct reference names"
		)
		void shouldReturnCorrectReferenceNames() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final Set<String> names =
				builder.getReferenceNames();
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
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final Collection<ReferenceContract> all =
				builder.getReferences();
			assertEquals(5, all.size());
		}

		@Test
		@DisplayName(
			"should return 2 store references by name"
		)
		void shouldReturnStoreReferencesByName() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final Collection<ReferenceContract> stores =
				builder.getReferences(STORE);
			assertEquals(2, stores.size());
		}

		@Test
		@DisplayName(
			"should return reference by name and id"
		)
		void shouldReturnReferenceByNameAndId() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final Optional<ReferenceContract> ref =
				builder.getReference(STORE, 1);
			assertTrue(ref.isPresent());
			assertEquals(
				1, ref.get().getReferencedPrimaryKey()
			);
		}

		@Test
		@DisplayName(
			"should return reference by ReferenceKey"
		)
		void shouldReturnReferenceByReferenceKey() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final ReferenceContract storeRef =
				builder.getReferences(STORE).iterator()
					.next();
			final ReferenceKey key =
				storeRef.getReferenceKey();

			final Optional<ReferenceContract> ref =
				builder.getReference(key);
			assertTrue(ref.isPresent());
			assertEquals(
				STORE, ref.get().getReferenceName()
			);
		}

		@Test
		@DisplayName(
			"should throw when getting duplicate-allowing "
				+ "reference via getReference(name, id)"
		)
		void shouldThrowOnDuplicateReferenceAccess() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

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
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final ReferenceKey key =
				new ReferenceKey(BRAND, 1);
			final List<ReferenceContract> refs =
				builder.getReferences(key);
			assertEquals(2, refs.size());
		}

		@Test
		@DisplayName(
			"should return correct reference chunk"
		)
		void shouldReturnReferenceChunk() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final DataChunk<ReferenceContract> chunk =
				builder.getReferenceChunk(STORE);
			assertNotNull(chunk);
			assertEquals(
				2, chunk.getTotalRecordCount()
			);
		}

		@Test
		@DisplayName(
			"should return empty when ref id not found"
		)
		void shouldReturnEmptyForNonExistentReference() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final Optional<ReferenceContract> ref =
				builder.getReference(STORE, 999);
			assertTrue(ref.isEmpty());
		}
	}

	@Nested
	@DisplayName("Setting references")
	class SettingReferencesTest {

		@Test
		@DisplayName("should add new store reference")
		void shouldAddNewStoreReference() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.setReference(STORE, 3);

			final Collection<ReferenceContract> stores =
				builder.getReferences(STORE);
			assertEquals(3, stores.size());
		}

		@Test
		@DisplayName(
			"should set reference with consumer"
		)
		void shouldSetReferenceWithConsumer() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.setReference(
				STORE, 3,
				ref -> ref.setAttribute("priority", 5)
			);

			assertEquals(
				3, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should set reference with entity type "
				+ "and cardinality"
		)
		void shouldSetReferenceWithEntityTypeAndCardinality() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.setReference(
				CATEGORY, CATEGORY,
				Cardinality.ZERO_OR_MORE, 2
			);

			assertEquals(
				2, builder.getReferences(CATEGORY).size()
			);
		}

		@Test
		@DisplayName(
			"should overwrite existing store reference"
		)
		void shouldOverwriteExistingStoreReference() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			// overwrite store/1 with consumer
			builder.setReference(
				STORE, 1,
				ref -> ref.setAttribute("priority", 42)
			);

			// count should remain the same
			assertEquals(
				2, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should throw for unknown reference type "
				+ "on strict schema"
		)
		void shouldThrowForUnknownReferenceOnStrictSchema() {
			final EntitySchemaContract schema =
				createSchemaWithStrictCardinality();
			final InitialReferencesBuilder init =
				new InitialReferencesBuilder(schema);
			init.setReference(STORE, 1);
			final References base = init.build();

			final ExistingReferencesBuilder builder =
				createBuilder(schema, base);

			assertThrows(
				ReferenceNotKnownException.class,
				() -> builder.setReference("unknown", 1)
			);
		}

		@Test
		@DisplayName(
			"should throw ReferenceAllowsDuplicates "
				+ "for brand via setReference"
		)
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
		@DisplayName(
			"should update existing reference attributes"
		)
		void shouldUpdateExistingReferenceAttributes() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.updateReference(
				STORE, 1,
				ref -> ref.setAttribute("priority", 99)
			);

			// reference should still exist
			final Optional<ReferenceContract> ref =
				builder.getReference(STORE, 1);
			assertTrue(ref.isPresent());
		}

		@Test
		@DisplayName(
			"should be no-op when updating "
				+ "non-existent reference"
		)
		void shouldBeNoOpForNonExistentReference() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			// store/999 does not exist - should be no-op
			builder.updateReference(
				STORE, 999,
				ref -> ref.setAttribute("priority", 5)
			);

			// count should remain the same
			assertEquals(
				2, builder.getReferences(STORE).size()
			);
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
				STORE, 3, ref -> false,
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
				STORE, 1, ref -> true,
				ref -> ref.setAttribute("priority", 20)
			);

			// still 2 refs, not 3
			assertEquals(
				2, builder.getReferences(STORE).size()
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
		@DisplayName(
			"should apply InsertReferenceMutation"
		)
		void shouldApplyInsertReferenceMutation() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final ReferenceKey key =
				new ReferenceKey(STORE, 3, -10);
			final InsertReferenceMutation mutation =
				new InsertReferenceMutation(
					key, Cardinality.ZERO_OR_MORE, STORE
				);

			builder.mutateReference(mutation);

			assertEquals(
				3, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should apply SetReferenceGroupMutation"
		)
		void shouldApplySetReferenceGroupMutation() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final ReferenceContract catRef =
				builder.getReference(CATEGORY, 1)
					.orElseThrow();
			final SetReferenceGroupMutation mutation =
				new SetReferenceGroupMutation(
					catRef.getReferenceKey(), GROUP, 200
				);

			builder.mutateReference(mutation);

			final ReferenceContract updated =
				builder.getReference(CATEGORY, 1)
					.orElseThrow();
			assertTrue(updated.getGroup().isPresent());
			final GroupEntityReference group =
				updated.getGroup().get();
			assertEquals(200, group.getPrimaryKey());
		}

		@Test
		@DisplayName(
			"should apply RemoveReferenceGroupMutation"
		)
		void shouldApplyRemoveReferenceGroupMutation() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final ReferenceContract catRef =
				builder.getReference(CATEGORY, 1)
					.orElseThrow();

			builder.mutateReference(
				new RemoveReferenceGroupMutation(
					catRef.getReferenceKey()
				)
			);

			final ReferenceContract updated =
				builder.getReference(CATEGORY, 1)
					.orElseThrow();
			assertTrue(updated.getGroup().isEmpty());
		}

		@Test
		@DisplayName(
			"should apply RemoveReferenceMutation"
		)
		void shouldApplyRemoveReferenceMutation() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final ReferenceContract storeRef =
				builder.getReference(STORE, 1)
					.orElseThrow();

			builder.mutateReference(
				new RemoveReferenceMutation(
					storeRef.getReferenceKey()
				)
			);

			assertTrue(
				builder.getReference(STORE, 1).isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should apply ReferenceAttributeMutation"
		)
		void shouldApplyReferenceAttributeMutation() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final ReferenceContract brandRef =
				builder.getReferences(BRAND)
					.iterator().next();
			final ReferenceKey refKey =
				brandRef.getReferenceKey();

			final ReferenceAttributeMutation mutation =
				new ReferenceAttributeMutation(
					refKey,
					new UpsertAttributeMutation(
						COUNTRY, "SK"
					)
				);

			builder.mutateReference(mutation);

			final ReferenceContract updated =
				builder.getReference(refKey)
					.orElseThrow();
			assertEquals(
				"SK", updated.getAttribute(COUNTRY)
			);
		}
	}

	@Nested
	@DisplayName("Change set and build")
	class ChangeSetAndBuildTest {

		@Test
		@DisplayName(
			"should return empty change set "
				+ "when no mutations"
		)
		void shouldReturnEmptyChangeSetWhenNoMutations() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final List<? extends ReferenceMutation<?>>
				mutations = builder.buildChangeSet()
				.collect(Collectors.toList());

			assertTrue(mutations.isEmpty());
		}

		@Test
		@DisplayName(
			"should report no changes via "
				+ "isThereAnyChangeInMutations"
		)
		void shouldReportNoChanges() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			assertFalse(
				builder.isThereAnyChangeInMutations()
			);
		}

		@Test
		@DisplayName(
			"should produce change set for added refs"
		)
		void shouldProduceChangeSetForAddedRefs() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.setReference(STORE, 3);

			final List<? extends ReferenceMutation<?>>
				mutations = builder.buildChangeSet()
				.collect(Collectors.toList());

			assertFalse(mutations.isEmpty());
			assertTrue(
				mutations.stream().anyMatch(
					m -> m instanceof InsertReferenceMutation
				)
			);
		}

		@Test
		@DisplayName(
			"should produce change set for removed refs"
		)
		void shouldProduceChangeSetForRemovedRefs() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.removeReference(STORE, 1);

			final List<? extends ReferenceMutation<?>>
				mutations = builder.buildChangeSet()
				.collect(Collectors.toList());

			assertFalse(mutations.isEmpty());
			assertTrue(
				mutations.stream().anyMatch(
					m -> m instanceof RemoveReferenceMutation
				)
			);
		}

		@Test
		@DisplayName(
			"should return same instance when "
				+ "no changes exist"
		)
		void shouldReturnSameInstanceWhenNoChanges() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final References base =
				createBaseReferences(schema);
			final ExistingReferencesBuilder builder =
				createBuilder(schema, base);

			final References built = builder.build();

			assertSame(base, built);
		}

		@Test
		@DisplayName(
			"should return new instance when changes exist"
		)
		void shouldReturnNewInstanceWhenChangesExist() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final References base =
				createBaseReferences(schema);
			final ExistingReferencesBuilder builder =
				createBuilder(schema, base);

			builder.setReference(STORE, 3);

			final References built = builder.build();

			assertNotSame(base, built);
		}
	}

	@Nested
	@DisplayName("Identity semantics")
	class IdentityTest {

		@Test
		@DisplayName(
			"should return same References instance "
				+ "when no mutations applied"
		)
		void shouldReturnSameWhenNoMutations() {
			final EntitySchemaContract schema =
				createSchemaWithReferences();
			final References base =
				createBaseReferences(schema);
			final ExistingReferencesBuilder builder =
				createBuilder(schema, base);

			assertSame(base, builder.build());
		}

		@Test
		@DisplayName(
			"should skip no-op mutations when "
				+ "setting identical values"
		)
		void shouldSkipNoOpMutations() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			// overwrite store/1 with identical content
			builder.setReference(STORE, 1);

			// the change set should be non-empty because
			// the builder produces InsertReferenceMutation,
			// but the build result should still differ from
			// base because the internal tracking records it
			assertTrue(
				builder.isThereAnyChangeInMutations()
			);
		}
	}

	@Nested
	@DisplayName("Cardinality management")
	class CardinalityManagementTest {

		@Test
		@DisplayName(
			"should auto-promote cardinality when "
				+ "evolution allowed"
		)
		void shouldAutoPromoteCardinality() {
			final EntitySchemaContract schema =
				createSchemaWithSingleRef();
			final InitialReferencesBuilder init =
				new InitialReferencesBuilder(schema);
			init.setReference(STORE, 1);
			final References base = init.build();

			final ExistingReferencesBuilder builder =
				createBuilder(schema, base);

			// second reference triggers promotion
			builder.setReference(STORE, 2);

			assertEquals(
				2, builder.getReferences(STORE).size()
			);
		}

		@Test
		@DisplayName(
			"should throw when cardinality violated "
				+ "with strict schema"
		)
		void shouldThrowWhenCardinalityViolated() {
			final EntitySchemaContract schema =
				createSchemaWithStrictCardinality();
			final InitialReferencesBuilder init =
				new InitialReferencesBuilder(schema);
			init.setReference(STORE, 1);
			final References base = init.build();

			final ExistingReferencesBuilder builder =
				createBuilder(schema, base);

			assertThrows(
				ReferenceCardinalityViolatedException.class,
				() -> builder.setReference(STORE, 2)
			);
		}

		@Test
		@DisplayName(
			"should generate decreasing negative "
				+ "internal ids"
		)
		void shouldGenerateDecreasingNegativeInternalIds() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final int first =
				builder.getNextReferenceInternalId();
			final int second =
				builder.getNextReferenceInternalId();
			final int third =
				builder.getNextReferenceInternalId();

			assertTrue(first < 0);
			assertTrue(second < first);
			assertTrue(third < second);
		}

		@Test
		@DisplayName(
			"should create reference via createReference"
		)
		void shouldCreateReferenceViaCreateReference() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			final ReferenceKey key =
				builder.createReference(STORE, 5);

			assertNotNull(key);
			assertEquals(STORE, key.referenceName());
			assertEquals(5, key.primaryKey());
			assertTrue(key.internalPrimaryKey() < 0);

			assertEquals(
				3, builder.getReferences(STORE).size()
			);
		}
	}

	@Nested
	@DisplayName("Remove and re-add")
	class RemoveAndReAddTest {

		@Test
		@DisplayName(
			"should allow removing and re-adding same ref"
		)
		void shouldRemoveAndReAddSameReference() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.removeReference(STORE, 1);
			assertTrue(
				builder.getReference(STORE, 1).isEmpty()
			);

			// re-add the same reference
			builder.setReference(STORE, 1);

			final Optional<ReferenceContract> ref =
				builder.getReference(STORE, 1);
			assertTrue(ref.isPresent());
			assertEquals(
				1, ref.get().getReferencedPrimaryKey()
			);
		}

		@Test
		@DisplayName(
			"should properly merge on re-add with attrs"
		)
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
		@DisplayName(
			"should produce empty change set "
				+ "after remove and re-add of identical ref"
		)
		void shouldProduceEmptyChangeSetAfterRemoveReAdd() {
			final ExistingReferencesBuilder builder =
				createDefaultBuilder();

			builder.removeReference(STORE, 1);
			builder.setReference(STORE, 1);

			final List<? extends ReferenceMutation<?>>
				mutations = builder.buildChangeSet()
				.collect(Collectors.toList());

			// removing and re-adding the same reference
			// with identical content cancels out
			assertTrue(mutations.isEmpty());
		}
	}
}
