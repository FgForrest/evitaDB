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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import java.math.BigDecimal;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator.ConsistencyChecks;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaCardinalityMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaRelatedEntityGroupMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.RemoveReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaRelatedEntityMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaConflictResolutionOverrideMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaSearchableThroughMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.*;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.math.BigDecimal.valueOf;
import static io.evitadb.api.requestResponse.schema.ReferenceSearchMode.COMPOSITION;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Contracts of reference text composition editors and component-wise mutation combination. */
@DisplayName("Reference searchableThrough metadata")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(REFERENCE)
class ReferenceSearchableThroughTest {
	private static final String PRODUCT = "Product";
	private static final String BLOCK = "ContentBlock";
	private static final String BLOCKS = "blocks";
	private static final EntitySchema OWNER = EntitySchema._internalBuild(PRODUCT);
	private static final ScopedReferenceSearchMode[] LIVE = {
		new ScopedReferenceSearchMode(Scope.LIVE, COMPOSITION)
	};
	private static final ScopedReferenceSearchMode[] ARCHIVED = {
		new ScopedReferenceSearchMode(Scope.ARCHIVED, COMPOSITION)
	};
	private static final CatalogSchema CATALOG = CatalogSchema._internalBuild(
		"testCatalog", NamingConvention.generate("testCatalog"), null,
		EnumSet.allOf(CatalogEvolutionMode.class), new EntitySchemaProvider() {
			@Nonnull
			@Override
			public Collection<EntitySchemaContract> getEntitySchemas() {
				return List.of(OWNER);
			}

			@Nonnull
			@Override
			public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
				return PRODUCT.equals(entityType) ? Optional.of(OWNER) : Optional.empty();
			}
		}
	);

	/** Creates a managed reference builder and returns the immutable result. */
	private static ReferenceSchemaContract build(Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> editor) {
		return new InternalEntitySchemaBuilder(CATALOG, OWNER)
			.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
				reference -> {
					reference.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED);
					editor.accept(reference);
				})
			.toInstance().getReference(BLOCKS).orElseThrow();
	}

	/**
	 * Creates a canonical create mutation with both scopes indexed.
	 * @param modes composition modes to declare, or null to use the creation default
	 * @param weight decimal ranking contribution, or null to use the schema default
	 * @return normal reference creation mutation
	 */
	@Nonnull
	private static CreateReferenceSchemaMutation create(
		@Nullable ScopedReferenceSearchMode[] modes, @Nullable BigDecimal weight
	) {
		return new CreateReferenceSchemaMutation(
			BLOCKS, null, null, Cardinality.ZERO_OR_MORE, BLOCK, true, null, false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
			}, null, null, null, null, null, ConflictResolutionOverride.INHERITED, modes, weight
		);
	}

	/**
	 * Creates an explicitly indexed reflected reference mutation.
	 * @param modes composition modes to declare, or null to use the creation default
	 * @param weight decimal ranking contribution, or null to use the schema default
	 * @return reflected reference creation mutation
	 */
	@Nonnull
	private static CreateReflectedReferenceSchemaMutation createReflected(
		@Nullable ScopedReferenceSearchMode[] modes, @Nullable BigDecimal weight
	) {
		return new CreateReflectedReferenceSchemaMutation(
			"products", null, null, Cardinality.ZERO_OR_MORE, PRODUCT, BLOCKS,
			ScopedReferenceIndexType.DEFAULT, null, null, null, null, null,
			ReflectedReferenceSchemaContract.AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
			null, modes, weight
		);
	}

	/**
	 * Creates either reference kind with one explicit index scope and an optional mode.
	 * @param reflected whether to construct a reflected reference
	 * @param scope scope in which the reference is configured
	 * @param searchable whether to declare reference composition in that scope
	 * @param weight decimal ranking contribution supplied by this case
	 * @return creation mutation carrying the requested scope and weight
	 */
	@Nonnull
	private static CombinableLocalEntitySchemaMutation scopedCreate(
		boolean reflected, @Nonnull Scope scope, boolean searchable, @Nonnull BigDecimal weight
	) {
		final ScopedReferenceIndexType[] indexed = {
			new ScopedReferenceIndexType(scope, ReferenceIndexType.FOR_FILTERING)
		};
		final ScopedReferenceSearchMode[] modes = searchable ?
			new ScopedReferenceSearchMode[]{new ScopedReferenceSearchMode(scope, COMPOSITION)} :
			ScopedReferenceSearchMode.EMPTY;
		return reflected ? new CreateReflectedReferenceSchemaMutation(
			"products", "Product links", "Explicit metadata", Cardinality.ZERO_OR_MORE, PRODUCT, BLOCKS,
			indexed, null, null, null, null, null,
			ReflectedReferenceSchemaContract.AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
			null, modes, weight
		) : new CreateReferenceSchemaMutation(
			BLOCKS, null, null, Cardinality.ZERO_OR_MORE, BLOCK, true, null, false,
			indexed, null, null, null, null, null, ConflictResolutionOverride.INHERITED, modes, weight
		);
	}

	/**
	 * Mutations that rebuild an existing reference schema, each applied to a composed reference that carries
	 * attributes and a sortable compound, so every rebuild path has something to copy.
	 */
	@Nonnull
	private static Stream<Named<UnaryOperator<ReferenceSchemaContract>>> rebuildingMutations() {
		return Stream.of(
			Named.of("rename", reference -> new ModifyReferenceSchemaNameMutation(BLOCKS, "contentBlocks")
				.mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("cardinality", reference -> new ModifyReferenceSchemaCardinalityMutation(
				BLOCKS, Cardinality.ONE_OR_MORE).mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("managed target", reference -> new ModifyReferenceSchemaRelatedEntityMutation(
				BLOCKS, "OtherBlock", true).mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("group type", reference -> new ModifyReferenceSchemaRelatedEntityGroupMutation(
				BLOCKS, "BlockGroup", true).mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("conflict resolution override",
				reference -> new SetReferenceSchemaConflictResolutionOverrideMutation(
					BLOCKS, ConflictResolutionOverride.GRANULAR).mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("bucketed", reference -> new SetReferenceSchemaBucketedMutation(
				BLOCKS, new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, "byPriority", null, null)
				}).mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("indexed", reference -> new SetReferenceSchemaIndexedMutation(
				BLOCKS, new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
					new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)
				}).mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("attribute modification", reference -> new ModifyReferenceAttributeSchemaMutation(
				BLOCKS, new ModifyAttributeSchemaDescriptionMutation("code", "Block code"))
				.mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("attribute removal", reference -> new RemoveAttributeSchemaMutation("note")
				.mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("compound creation", reference -> new CreateSortableAttributeCompoundSchemaMutation(
				"priorityCode", null, null, null,
				AttributeElement.attributeElement("priority"), AttributeElement.attributeElement("code"))
				.mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("compound modification", reference -> new ModifyReferenceSortableAttributeCompoundSchemaMutation(
				BLOCKS, new ModifySortableAttributeCompoundSchemaDescriptionMutation("codePriority", "Ordering"))
				.mutate(OWNER, reference, ConsistencyChecks.APPLY)),
			Named.of("compound removal", reference -> new RemoveSortableAttributeCompoundSchemaMutation("codePriority")
				.mutate(OWNER, reference, ConsistencyChecks.APPLY))
		);
	}

	/** Equal decimal scales must not create a schema revision or alter schema identity. */
	@Test
	@DisplayName("treats 1, 1.0 and 1.00 as the same weight in every schema place")
	void shouldIgnoreInsignificantWeightScale() {
		final EntitySchemaContract owner = new InternalEntitySchemaBuilder(CATALOG, OWNER)
			.withAttribute("text", String.class, a -> a.searchWeight(BigDecimal.ONE))
			.withAssociatedData("text", String.class, a -> a.searchWeight(BigDecimal.ONE))
			.toInstance();
		final AttributeSchemaContract attribute = owner.getAttribute("text").orElseThrow();
		final AssociatedDataSchemaContract data = owner.getAssociatedData("text").orElseThrow();
		final ReferenceSchemaContract reference = build(r -> r.searchableThrough(COMPOSITION, BigDecimal.ONE));
		final GlobalAttributeSchemaContract global = new InternalCatalogSchemaBuilder(CATALOG)
			.withAttribute("globalText", String.class, a -> a.searchWeight(BigDecimal.ONE))
			.toInstance().getAttribute("globalText").orElseThrow();
		final ReflectedReferenceSchema reflected = ReflectedReferenceSchema._internalBuild(
			"mirror", "Target", "original", null, BigDecimal.ONE);
		for (final String stated : new String[]{"1", "1.0", "1.00"}) {
			final BigDecimal weight = new BigDecimal(stated);
			assertSame(attribute, new SetAttributeSchemaSearchableMutation("text", null, null, null, weight)
				.mutate(CATALOG, attribute, AttributeSchemaContract.class));
			assertSame(global, new SetAttributeSchemaSearchableMutation("globalText", null, null, null, weight)
				.mutate(CATALOG, global, GlobalAttributeSchemaContract.class));
			assertSame(reflected, new SetReferenceSchemaSearchableThroughMutation("mirror", null, weight)
				.mutate(OWNER, reflected, ConsistencyChecks.APPLY));
			assertSame(data, new SetAssociatedDataSchemaSearchableMutation("text", null, null, null, null, weight)
				.mutate(data));
			assertSame(reference, new SetReferenceSchemaSearchableThroughMutation(BLOCKS, null, weight)
				.mutate(OWNER, reference, ConsistencyChecks.APPLY));
			final ReferenceSchemaContract scaled = build(r -> r.searchableThrough(COMPOSITION, weight));
			assertEquals(reference, scaled);
			assertEquals(reference.hashCode(), scaled.hashCode());
			assertEquals(reference.toString(), scaled.toString());
		}
	}

	/** Mutation ordering and validation at builder completion and explicit APPLY boundaries. */
	@Nested
	@DisplayName("Searchable-through validation regressions")
	class ValidationRegressions {
		/** The clear must survive optimization across the intervening index withdrawal. */
		@Test
		@DisplayName("should move composition after clearing its old index through the real builder")
		void shouldMoveCompositionAfterClearingItsOldIndex() {
			final EntitySchemaContract initial = create(LIVE, valueOf(0.8)).mutate(CATALOG, OWNER);
			final ReferenceSchemaContract result = new InternalEntitySchemaBuilder(CATALOG, initial)
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE, reference -> {
					reference.nonSearchableThroughInScope(Scope.LIVE).nonIndexed(Scope.LIVE)
						.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED);
					assertEquals(Map.of(Scope.ARCHIVED, COMPOSITION), reference.getSearchableThroughInScopes());
					assertFalse(reference.isIndexedInScope(Scope.LIVE));
				}).toInstance().getReferenceOrThrowException(BLOCKS);
			assertEquals(Map.of(Scope.ARCHIVED, COMPOSITION), result.getSearchableThroughInScopes());
			assertFalse(result.isIndexedInScope(Scope.LIVE));
		}

		/** A create absorbing the set still validates its independent weight. */
		@Test
		@DisplayName("should reject an invalid weight folded into a new reflected builder")
		void shouldRejectInvalidWeightInNewReflectedBuilder() {
			assertThrows(InvalidSchemaMutationException.class,
				() -> new InternalEntitySchemaBuilder(CATALOG, EntitySchema._internalBuild(BLOCK))
					.withReflectedReferenceToEntity("products", PRODUCT, BLOCKS,
						reference -> reference.searchableThrough(COMPOSITION, new BigDecimal("1E+39"))).toInstance());
		}

		/** An explicit index scope is known even before the original reference exists. */
		@Test
		@DisplayName("should reject a known unindexed scope folded into a new reflected builder")
		void shouldRejectKnownUnindexedScopeInNewReflectedBuilder() {
			assertThrows(InvalidSchemaMutationException.class,
				() -> new InternalEntitySchemaBuilder(CATALOG, EntitySchema._internalBuild(BLOCK))
					.withReflectedReferenceToEntity("products", PRODUCT, BLOCKS,
						reference -> reference.indexedForFilteringInScope(Scope.LIVE)
							.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED)).toInstance());
		}

		/** Entity-level creation cannot bypass validation by calling its reference mutation with SKIP. */
		@Test
		@DisplayName("should reject invalid folded settings in entity-level reflected creation")
		void shouldRejectInvalidSettingsInEntityLevelReflectedCreate() {
			final EntitySchema owner = EntitySchema._internalBuild(BLOCK);
			assertThrows(InvalidSchemaMutationException.class,
				() -> createReflected(LIVE, new BigDecimal("1E+39")).mutate(CATALOG, owner));
			assertThrows(InvalidSchemaMutationException.class,
				() -> createReflected(ARCHIVED, valueOf(0.8)).mutate(CATALOG, owner));
		}

		/** Bound reflected references must retain both their index and referenced-entity component at APPLY. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("should reject withdrawing a bound reflected index or its referenced entity component at APPLY")
		void shouldRejectBoundReflectedIndexWithdrawalAtApply(boolean withdrawComponent) {
			final ReferenceSchemaContract original = create(LIVE, valueOf(0.8))
				.mutate(OWNER, null, ConsistencyChecks.APPLY);
			final ReflectedReferenceSchema reflected = ReflectedReferenceSchema._internalBuild(
				"products", PRODUCT, BLOCKS, Map.of(Scope.LIVE, COMPOSITION), valueOf(0.8)
			).withReferencedSchema(original);
			final SetReferenceSchemaIndexedMutation mutation = new SetReferenceSchemaIndexedMutation(
				"products", new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, withdrawComponent
						? ReferenceIndexType.FOR_FILTERING : ReferenceIndexType.NONE)
				}, withdrawComponent ? new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE,
						new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY})
				} : null
			);
			final EntitySchema owner = EntitySchema._internalBuild(BLOCK);
			assertThrows(InvalidSchemaMutationException.class,
				() -> mutation.mutate(owner, reflected, ConsistencyChecks.APPLY));
			assertDoesNotThrow(() -> mutation.mutate(owner, reflected, ConsistencyChecks.SKIP));
		}

		/** An unmanaged target cannot keep composition, even on direct mutation application. */
		@Test
		@DisplayName("should refuse retargeting a composed reference to an unmanaged type at APPLY")
		void shouldRejectUnmanagedRetargetAtApply() {
			final ReferenceSchemaContract original = create(LIVE, valueOf(0.8))
				.mutate(OWNER, null, ConsistencyChecks.APPLY);
			final ModifyReferenceSchemaRelatedEntityMutation mutation =
				new ModifyReferenceSchemaRelatedEntityMutation(BLOCKS, "External", false);
			assertThrows(InvalidSchemaMutationException.class,
				() -> mutation.mutate(OWNER, original, ConsistencyChecks.APPLY));
			assertDoesNotThrow(() -> mutation.mutate(OWNER, original, ConsistencyChecks.SKIP));
		}

		/** Clearing composition first preserves a valid builder path to an unmanaged target. */
		@Test
		@DisplayName("should allow retargeting after the builder withdraws composition")
		void shouldRetargetAfterWithdrawingComposition() {
			final EntitySchemaContract initial = create(LIVE, valueOf(0.8)).mutate(CATALOG, OWNER);
			final ReferenceSchemaContract result = new InternalEntitySchemaBuilder(CATALOG, initial)
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					ReferenceSchemaEditor::nonSearchableThrough)
				.withReferenceTo(BLOCKS, "External", Cardinality.ZERO_OR_MORE)
				.toInstance().getReferenceOrThrowException(BLOCKS);
			assertTrue(result.getSearchableThroughInScopes().isEmpty());
			assertEquals("External", result.getReferencedEntityType());
			assertFalse(result.isReferencedEntityTypeManaged());
		}

		/**
		 * A later index edit merged with an earlier one must not move the earlier one past the composition that
		 * relies on it, and the merge must still keep the scopes the later edit does not name.
		 */
		@Test
		@DisplayName("should keep the index a composition relies on when a later index edit merges with it")
		void shouldKeepIndexAddedBeforeCompositionWhenLaterIndexEditMerges() {
			final EntitySchemaContract initial = new InternalEntitySchemaBuilder(CATALOG, OWNER)
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFilteringInScope(Scope.LIVE))
				.toInstance();
			final ReferenceSchemaContract result = new InternalEntitySchemaBuilder(CATALOG, initial)
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE, reference -> reference
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED)
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE))
				.toInstance().getReferenceOrThrowException(BLOCKS);
			assertEquals(Map.of(Scope.ARCHIVED, COMPOSITION), result.getSearchableThroughInScopes());
			assertEquals(
				Map.of(
					Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
					Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
				),
				result.getReferenceIndexTypeInScopes()
			);
		}

		/**
		 * The same ordering holds for a reflected reference already in the schema: its explicit index is known
		 * without the original reference, so the composition replays against it.
		 */
		@Test
		@DisplayName("should keep the index a reflected composition relies on when a later index edit merges with it")
		void shouldKeepReflectedIndexAddedBeforeCompositionWhenLaterIndexEditMerges() {
			final EntitySchemaContract initial = new InternalEntitySchemaBuilder(
				CATALOG, EntitySchema._internalBuild(BLOCK)
			)
				.withReflectedReferenceToEntity("products", PRODUCT, BLOCKS,
					reference -> reference.indexedForFilteringInScope(Scope.LIVE))
				.toInstance();
			final ReferenceSchemaContract result = new InternalEntitySchemaBuilder(CATALOG, initial)
				.withReflectedReferenceToEntity("products", PRODUCT, BLOCKS, reference -> reference
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED)
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE))
				.toInstance().getReferenceOrThrowException("products");
			assertEquals(Map.of(Scope.ARCHIVED, COMPOSITION), result.getSearchableThroughInScopes());
			assertEquals(
				Map.of(
					Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
					Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
				),
				result.getReferenceIndexTypeInScopes()
			);
		}

		/** A second retarget merged with the first must not replay the composition against the old target. */
		@Test
		@DisplayName("should keep composition when a reference is retargeted twice in one session")
		void shouldKeepCompositionWhenRetargetedTwiceInOneSession() {
			final EntitySchemaContract initial = new InternalEntitySchemaBuilder(CATALOG, OWNER)
				.withReferenceTo(BLOCKS, "External", Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFilteringInScope(Scope.LIVE))
				.toInstance();
			final ReferenceSchemaContract result = new InternalEntitySchemaBuilder(CATALOG, initial)
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					reference -> reference.searchableThrough(COMPOSITION))
				.withReferenceToEntity(BLOCKS, "OtherBlock", Cardinality.ZERO_OR_MORE)
				.toInstance().getReferenceOrThrowException(BLOCKS);
			assertEquals("OtherBlock", result.getReferencedEntityType());
			assertTrue(result.isReferencedEntityTypeManaged());
			assertEquals(Map.of(Scope.LIVE, COMPOSITION), result.getSearchableThroughInScopes());
		}

		/** A composition must not fold into a reflected create ahead of the separate index edit it relies on. */
		@Test
		@DisplayName("should compose after extending the explicit index of a new reflected reference")
		void shouldComposeAfterExtendingIndexOfNewReflectedReference() {
			final InternalEntitySchemaBuilder builder = new InternalEntitySchemaBuilder(
				CATALOG, EntitySchema._internalBuild(BLOCK)
			);
			builder.withReflectedReferenceToEntity("products", PRODUCT, BLOCKS,
				reference -> reference.indexedForFilteringInScope(Scope.LIVE));
			builder.withReflectedReferenceToEntity("products", PRODUCT, BLOCKS, reference -> reference
				.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
				.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED));
			final ReferenceSchemaContract result = builder.toInstance().getReferenceOrThrowException("products");
			assertEquals(Map.of(Scope.ARCHIVED, COMPOSITION), result.getSearchableThroughInScopes());
			assertTrue(result.isIndexedInScope(Scope.LIVE));
			assertTrue(result.isIndexedInScope(Scope.ARCHIVED));
		}
	}

	@Nested
	@DisplayName("Editors")
	class Editors {
		/** No mode is declared by default, while its weight is already defined. */
		@Test
		@DisplayName("should default to no modes and weight one half")
		void shouldDefaultToNoModes() {
			final ReferenceSchemaContract reference = build(editor -> {});
			assertNull(reference.getSearchableThrough());
			assertNull(reference.getSearchableThrough(Scope.ARCHIVED));
			assertTrue(reference.getSearchableThroughInScopes().isEmpty());
			assertEquals(valueOf(0.5), reference.getSearchableThroughWeight());
		}

		/** Every declaration overload edits the complete scope map and one shared weight. */
		@Test
		@DisplayName("should support every declaration overload and a shared weight")
		void shouldSupportAllDeclarationOverloads() {
			final ReferenceSchemaContract reference = build(editor -> {
				editor.searchableThrough(COMPOSITION);
				assertEquals(COMPOSITION, editor.getSearchableThrough());
				assertEquals(valueOf(0.5), editor.getSearchableThroughWeight());
				editor.searchableThroughInScope(COMPOSITION, valueOf(0.25), Scope.ARCHIVED);
				assertEquals(valueOf(0.25), editor.getSearchableThroughWeight());
				editor.searchableThrough(COMPOSITION, valueOf(2));
				editor.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED);
			});
			assertEquals(Map.of(Scope.LIVE, COMPOSITION, Scope.ARCHIVED, COMPOSITION),
				reference.getSearchableThroughInScopes());
			assertEquals(valueOf(2), reference.getSearchableThroughWeight());
			assertThrows(UnsupportedOperationException.class,
				() -> reference.getSearchableThroughInScopes().put(Scope.LIVE, COMPOSITION));
		}

		/** Removing one or all scopes leaves the separately stored shared weight intact. */
		@Test
		@DisplayName("should retain the weight when removing one or every mode")
		void shouldRetainWeightWhenRemovingModes() {
			final ReferenceSchemaContract reference = build(editor -> {
				editor.searchableThroughInScope(COMPOSITION, valueOf(3), Scope.LIVE, Scope.ARCHIVED);
				editor.nonSearchableThroughInScope(Scope.LIVE);
				assertNull(editor.getSearchableThrough());
				assertEquals(COMPOSITION, editor.getSearchableThrough(Scope.ARCHIVED));
				editor.nonSearchableThrough();
			});
			assertTrue(reference.getSearchableThroughInScopes().isEmpty());
			assertEquals(valueOf(3), reference.getSearchableThroughWeight());
		}

		/** Attribute/schema edits must preserve reference metadata while reconstructing DTOs. */
		@Test
		@DisplayName("should preserve modes and weight through unrelated reference edits")
		void shouldPreserveMetadataThroughOtherEdits() {
			final ReferenceSchemaContract reference = build(
				editor -> editor.searchableThrough(COMPOSITION, valueOf(0.75))
				.withDescription("Page blocks").deprecated("Use content")
				.withAttribute("priority", Integer.class, attribute -> attribute.sortable())
				.faceted().withConflictResolutionOverride(ConflictResolutionOverride.GRANULAR));
			assertEquals(COMPOSITION, reference.getSearchableThrough());
			assertEquals(valueOf(0.75), reference.getSearchableThroughWeight());
			assertTrue(reference.getAttribute("priority").isPresent());
			assertTrue(reference.isFaceted());
			assertEquals(ConflictResolutionOverride.GRANULAR, reference.getConflictResolutionOverride());
		}

		/** Negative zero is a valid zero-strength match and has one canonical bit representation. */
		@Test
		@DisplayName("should fold negative zero to positive zero")
		void shouldFoldNegativeZero() {
			final ReferenceSchemaContract reference = build(
				editor -> editor.searchableThrough(COMPOSITION, valueOf(0)));
			assertEquals(valueOf(0), reference.getSearchableThroughWeight());
		}
	}

	@Nested
	@DisplayName("Mutations")
	class Mutations {
		/** Missing create components default independently to empty scopes and weight one half. */
		@Test
		@DisplayName("should apply create defaults and explicit values")
		void shouldApplyCreateDefaultsAndValues() {
			final ReferenceSchemaContract defaults = create(null, null).mutate(OWNER, null, ConsistencyChecks.APPLY);
			assertTrue(defaults.getSearchableThroughInScopes().isEmpty());
			assertEquals(valueOf(0.5), defaults.getSearchableThroughWeight());
			final ReferenceSchemaContract explicit = create(ARCHIVED, valueOf(0))
				.mutate(OWNER, null, ConsistencyChecks.APPLY);
			assertEquals(Map.of(Scope.ARCHIVED, COMPOSITION), explicit.getSearchableThroughInScopes());
			assertEquals(valueOf(0), explicit.getSearchableThroughWeight());
		}

		/** Convenience constructors carry the same independent modes and weight as the canonical constructor. */
		@Test
		@DisplayName("should carry modes and weight through every create constructor chain")
		void shouldCarrySettingsThroughConvenienceConstructors() {
			final CreateReferenceSchemaMutation simple = new CreateReferenceSchemaMutation(
				BLOCKS, null, null, Cardinality.ZERO_OR_MORE, BLOCK, true, null, false,
				true, false, LIVE, valueOf(0.125)
			);
			final CreateReferenceSchemaMutation scoped = new CreateReferenceSchemaMutation(
				BLOCKS, null, null, Cardinality.ZERO_OR_MORE, BLOCK, true, null, false,
				ScopedReferenceIndexType.DEFAULT, null, null, null, null, null, LIVE, valueOf(0.125)
			);
			assertEquals(simple, scoped);
			assertEquals(Map.of(Scope.LIVE, COMPOSITION),
				simple.mutate(OWNER, null, ConsistencyChecks.APPLY).getSearchableThroughInScopes());
			assertEquals(valueOf(0.125), simple.getSearchableThroughWeight());
			final CreateReflectedReferenceSchemaMutation reflectedSimple =
				new CreateReflectedReferenceSchemaMutation(
					"products", null, null, Cardinality.ZERO_OR_MORE, PRODUCT, BLOCKS, false,
					ReflectedReferenceSchemaContract.AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null, LIVE, valueOf(0.25)
				);
			assertEquals(valueOf(0.25), reflectedSimple.getSearchableThroughWeight());
			assertArrayEquals(LIVE, reflectedSimple.getSearchableThroughInScopes());
		}

		/** Set mutations update only non-null components. An empty array clears modes. */
		@Test
		@DisplayName("should keep null set components and preserve weight when clearing modes")
		void shouldKeepNullSetComponents() {
			final ReferenceSchemaContract initial = create(LIVE, valueOf(0.75))
				.mutate(OWNER, null, ConsistencyChecks.APPLY);
			final ReferenceSchemaContract scopesOnly = new SetReferenceSchemaSearchableThroughMutation(
				BLOCKS, ARCHIVED, null).mutate(OWNER, initial, ConsistencyChecks.APPLY);
			assertEquals(Map.of(Scope.ARCHIVED, COMPOSITION), scopesOnly.getSearchableThroughInScopes());
			assertEquals(valueOf(0.75), scopesOnly.getSearchableThroughWeight());
			final ReferenceSchemaContract weightOnly = new SetReferenceSchemaSearchableThroughMutation(
				BLOCKS, null, valueOf(4)).mutate(OWNER, scopesOnly, ConsistencyChecks.APPLY);
			assertEquals(scopesOnly.getSearchableThroughInScopes(), weightOnly.getSearchableThroughInScopes());
			assertEquals(valueOf(4), weightOnly.getSearchableThroughWeight());
			assertSame(weightOnly, new SetReferenceSchemaSearchableThroughMutation(BLOCKS, null, null)
				.mutate(OWNER, weightOnly, ConsistencyChecks.APPLY));
			final ReferenceSchemaContract cleared = new SetReferenceSchemaSearchableThroughMutation(
				BLOCKS, ScopedReferenceSearchMode.EMPTY, null).mutate(OWNER, weightOnly, ConsistencyChecks.APPLY);
			assertTrue(cleared.getSearchableThroughInScopes().isEmpty());
			assertEquals(valueOf(4), cleared.getSearchableThroughWeight());
		}

		/** Separate sets must keep their position around changes to composition prerequisites. */
		@Test
		@DisplayName("should keep separate set mutations in their original order")
		void shouldNotCombineSets() {
			final SetReferenceSchemaSearchableThroughMutation previous =
				new SetReferenceSchemaSearchableThroughMutation(BLOCKS, LIVE, valueOf(2));
			assertNull(new SetReferenceSchemaSearchableThroughMutation(BLOCKS, ARCHIVED, null)
				.combineWith(CATALOG, OWNER, previous));
			assertNull(new SetReferenceSchemaSearchableThroughMutation(BLOCKS, null, valueOf(0))
				.combineWith(CATALOG, OWNER, previous));
			assertNull(new SetReferenceSchemaSearchableThroughMutation("other", null, valueOf(2))
				.combineWith(CATALOG, OWNER, previous));
		}

		/** A set folds only into the creation of its own reference and never absorbs an index change. */
		@Test
		@DisplayName("should not combine a set with another reference's creation or with an index change")
		void shouldNotCombineSetWithOtherMutations() {
			final SetReferenceSchemaSearchableThroughMutation otherReference =
				new SetReferenceSchemaSearchableThroughMutation("other", LIVE, valueOf(2));
			final SetReferenceSchemaIndexedMutation indexChange = new SetReferenceSchemaIndexedMutation(
				BLOCKS, new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				}
			);

			assertNull(otherReference.combineWith(CATALOG, OWNER, create(LIVE, valueOf(1))));
			assertNull(otherReference.combineWith(CATALOG, OWNER, createReflected(LIVE, valueOf(1))));
			assertNull(new SetReferenceSchemaSearchableThroughMutation(BLOCKS, LIVE, valueOf(2))
				.combineWith(CATALOG, OWNER, indexChange));
		}

		/** Replays of older mutations skip the requirement check, which then runs when the schema is validated. */
		@Test
		@DisplayName("should check the requirements of a set only when the mutation applies")
		void shouldSkipRequirementChecksForSetMutationWhenNotApplying() {
			final ReferenceSchemaContract liveOnly = ((CreateReferenceSchemaMutation) scopedCreate(
				false, Scope.LIVE, false, valueOf(0.5)
			)).mutate(OWNER, null, ConsistencyChecks.APPLY);
			final SetReferenceSchemaSearchableThroughMutation archived =
				new SetReferenceSchemaSearchableThroughMutation(BLOCKS, ARCHIVED, null);

			assertEquals(Map.of(Scope.ARCHIVED, COMPOSITION),
				archived.mutate(OWNER, liveOnly, ConsistencyChecks.SKIP).getSearchableThroughInScopes());
			assertThrows(InvalidSchemaMutationException.class,
				() -> archived.mutate(OWNER, liveOnly, ConsistencyChecks.APPLY));
		}

		/** Every mutation that rebuilds the reference schema must copy its composition modes and shared weight. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.requestResponse.schema.ReferenceSearchableThroughTest#rebuildingMutations")
		@DisplayName("should keep modes and weight when a mutation rebuilds the reference")
		void shouldKeepSettingsWhenMutationRebuildsReference(UnaryOperator<ReferenceSchemaContract> mutation) {
			final ReferenceSchemaContract composed = build(
				editor -> editor.searchableThrough(COMPOSITION, valueOf(0.75))
				.withAttribute("code", String.class, attribute -> attribute.sortable())
				.withAttribute("priority", Integer.class, attribute -> attribute.sortable())
				.withAttribute("note", String.class)
				.withSortableAttributeCompound("codePriority",
					AttributeElement.attributeElement("code"), AttributeElement.attributeElement("priority")));

			final ReferenceSchemaContract rebuilt = mutation.apply(composed);

			assertNotSame(composed, rebuilt);
			assertEquals(Map.of(Scope.LIVE, COMPOSITION), rebuilt.getSearchableThroughInScopes());
			assertEquals(valueOf(0.75), rebuilt.getSearchableThroughWeight());
		}

		/** Set changes are absorbed into both normal and reflected create mutations. */
		@Test
		@DisplayName("should fold set scopes and weight into normal and reflected creates")
		void shouldFoldSetIntoCreates() {
			final SetReferenceSchemaSearchableThroughMutation set =
				new SetReferenceSchemaSearchableThroughMutation(BLOCKS, ARCHIVED, null);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				set.combineWith(CATALOG, OWNER, create(LIVE, valueOf(2)));
			final CreateReferenceSchemaMutation combined = assertInstanceOf(CreateReferenceSchemaMutation.class,
				result.origin());
			assertEquals(create(ARCHIVED, valueOf(2)), combined);
			assertNull(result.current());
			final SetReferenceSchemaSearchableThroughMutation reflectedSet =
				new SetReferenceSchemaSearchableThroughMutation("products", null, valueOf(3));
			final CreateReflectedReferenceSchemaMutation reflectedCombined =
				assertInstanceOf(CreateReflectedReferenceSchemaMutation.class,
					reflectedSet.combineWith(CATALOG, OWNER, createReflected(LIVE, valueOf(0.25))).origin());
			assertEquals(createReflected(LIVE, valueOf(3)), reflectedCombined);
			assertEquals(valueOf(3), reflectedCombined.mutate(OWNER, null, ConsistencyChecks.APPLY)
				.getSearchableThroughWeight());
		}

		/** Recreated references may add, move or remove composition while their required index scopes change. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("should reconcile composition after required index scopes when recreating a reference")
		void shouldReconcileRecreatedReference(boolean reflected) {
			final String name = reflected ? "products" : BLOCKS;
			for (final boolean initiallySearchable : new boolean[]{false, true}) {
				for (final boolean finallySearchable : new boolean[]{false, true}) {
					final LocalEntitySchemaMutation previous = scopedCreate(
						reflected, Scope.LIVE, initiallySearchable, valueOf(2)
					);
					final EntitySchemaContract initial = previous.mutate(CATALOG, OWNER);
					final CombinableLocalEntitySchemaMutation replacement = scopedCreate(
						reflected, Scope.ARCHIVED, finallySearchable, valueOf(0.25)
					);
					final MutationCombinationResult<LocalEntitySchemaMutation> combined = replacement.combineWith(
						CATALOG, initial, new RemoveReferenceSchemaMutation(name)
					);
					assertNotNull(combined);
					EntitySchemaContract updated = initial;
					for (final LocalEntitySchemaMutation mutation : combined.current()) {
						updated = mutation.mutate(CATALOG, updated);
					}
					final ReferenceSchemaContract reference = updated.getReference(name).orElseThrow();
					assertFalse(reference.isIndexedInScope(Scope.LIVE));
					assertTrue(reference.isIndexedInScope(Scope.ARCHIVED));
					assertEquals(finallySearchable ? Map.of(Scope.ARCHIVED, COMPOSITION) : Map.of(),
						reference.getSearchableThroughInScopes());
					assertEquals(valueOf(0.25), reference.getSearchableThroughWeight());
				}
			}
		}

		/** Omitted create values resolve to defaults even when replacing a configured reference. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("should restore default composition when a replacement create omits it")
		void shouldRestoreDefaultsWhenRecreatingReference(boolean reflected) {
			final String name = reflected ? "products" : BLOCKS;
			final EntitySchemaContract initial = scopedCreate(reflected, Scope.LIVE, true, valueOf(2))
				.mutate(CATALOG, OWNER);
			final CombinableLocalEntitySchemaMutation replacement = reflected
				? new CreateReflectedReferenceSchemaMutation(
					"products", "Product links", "Explicit metadata", Cardinality.ZERO_OR_MORE, PRODUCT, BLOCKS,
					ScopedReferenceIndexType.DEFAULT, null, null, null, null, null,
					ReflectedReferenceSchemaContract.AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null, null, null
				) : create(null, null);
			final MutationCombinationResult<LocalEntitySchemaMutation> combined = replacement.combineWith(
				CATALOG, initial, new RemoveReferenceSchemaMutation(name)
			);
			assertNotNull(combined);
			EntitySchemaContract updated = initial;
			for (final LocalEntitySchemaMutation mutation : combined.current()) {
				updated = mutation.mutate(CATALOG, updated);
			}
			final ReferenceSchemaContract reference = updated.getReference(name).orElseThrow();
			assertTrue(reference.getSearchableThroughInScopes().isEmpty());
			assertEquals(valueOf(0.5), reference.getSearchableThroughWeight());
		}

		/** Array contents and normalized weights are part of mutation and DTO equality. */
		@Test
		@DisplayName("should compare modes and normalized weights by value")
		void shouldCompareByValue() {
			final SetReferenceSchemaSearchableThroughMutation negativeZero =
				new SetReferenceSchemaSearchableThroughMutation(BLOCKS, LIVE.clone(), valueOf(0));
			final SetReferenceSchemaSearchableThroughMutation zero =
				new SetReferenceSchemaSearchableThroughMutation(BLOCKS, LIVE.clone(), valueOf(0));
			assertEquals(zero, negativeZero);
			assertEquals(zero.hashCode(), negativeZero.hashCode());
			assertNotEquals(zero, new SetReferenceSchemaSearchableThroughMutation(BLOCKS, ARCHIVED, valueOf(0)));
			assertEquals(create(LIVE, valueOf(0)), create(LIVE.clone(), valueOf(0)));
			assertNotEquals(create(LIVE, valueOf(1)), create(ARCHIVED, valueOf(1)));
			assertNotEquals(create(LIVE, valueOf(1)), create(LIVE, valueOf(2)));
			assertNotEquals(create(LIVE, valueOf(1)).mutate(OWNER, null, ConsistencyChecks.APPLY),
				create(ARCHIVED, valueOf(1)).mutate(OWNER, null, ConsistencyChecks.APPLY));
			assertTrue(zero.toString().contains("COMPOSITION"));
			assertTrue(create(LIVE, valueOf(1)).toString().contains("searchableThrough"));
		}

		/** The scoped record validates both components at construction time. */
		@Test
		@DisplayName("should reject null scoped record components")
		void shouldRejectNullScopedComponents() {
			assertThrows(RuntimeException.class, () -> new ScopedReferenceSearchMode(null, COMPOSITION));
			assertThrows(RuntimeException.class, () -> new ScopedReferenceSearchMode(Scope.LIVE, null));
		}
	}
}
