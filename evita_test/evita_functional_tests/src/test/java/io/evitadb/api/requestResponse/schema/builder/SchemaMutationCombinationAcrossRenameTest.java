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

package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.RemoveReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.trim;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A schema builder combines the mutations it collects, so that a later statement about an element replaces an earlier
 * one. The combined pipeline must still mean what applying every collected mutation in order means. A rename makes
 * one name stand for two different elements in one pipeline - the element renamed away and the one renamed into its
 * place - so a statement before the rename must never be combined with a statement after it.
 */
@Tag(CONTRACT)
@Tag(SCHEMA)
@DisplayName("Schema mutation combination across a rename")
class SchemaMutationCombinationAcrossRenameTest {
	private static final String PRODUCT = "Product";

	/**
	 * Supplies, per element kind, a statement about `X`, the renames `X -> legacyX` and `replacementX -> X`, and a second
	 * statement about the new `X`, together with the projection of the two statements' outcome.
	 */
	@Nonnull
	static Stream<Arguments> entityPipelines() {
		return Stream.of(
			Arguments.of(
				"attribute description",
				List.of(
					new ModifyAttributeSchemaDescriptionMutation("code", "first"),
					new ModifyAttributeSchemaNameMutation("code", "legacyCode"),
					new ModifyAttributeSchemaNameMutation("replacementCode", "code"),
					new ModifyAttributeSchemaDescriptionMutation("code", "second")
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					String.valueOf(schema.getAttribute("legacyCode").orElseThrow().getDescription()),
					String.valueOf(schema.getAttribute("code").orElseThrow().getDescription())
				),
				List.of("first", "second")
			),
			Arguments.of(
				"attribute normalization",
				List.of(
					new SetAttributeSchemaNormalizedMutation("code", lowercase()),
					new ModifyAttributeSchemaNameMutation("code", "legacyCode"),
					new ModifyAttributeSchemaNameMutation("replacementCode", "code"),
					new SetAttributeSchemaNormalizedMutation("code", trim())
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					schema.getAttribute("legacyCode").orElseThrow().getNormalization(),
					schema.getAttribute("code").orElseThrow().getNormalization()
				),
				List.of(List.of(lowercase()), List.of(trim()))
			),
			Arguments.of(
				"attribute filterability",
				List.of(
					new SetAttributeSchemaFilterableMutation("code", new Scope[]{Scope.LIVE}),
					new ModifyAttributeSchemaNameMutation("code", "legacyCode"),
					new ModifyAttributeSchemaNameMutation("replacementCode", "code"),
					new SetAttributeSchemaFilterableMutation("code", new Scope[]{Scope.ARCHIVED})
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					schema.getAttribute("legacyCode").orElseThrow().getFilterableInScopes(),
					schema.getAttribute("code").orElseThrow().getFilterableInScopes()
				),
				List.of(EnumSet.of(Scope.LIVE), EnumSet.of(Scope.ARCHIVED))
			),
			Arguments.of(
				"reference description",
				List.of(
					new ModifyReferenceSchemaDescriptionMutation("brand", "first"),
					new ModifyReferenceSchemaNameMutation("brand", "legacyBrand"),
					new ModifyReferenceSchemaNameMutation("maker", "brand"),
					new ModifyReferenceSchemaDescriptionMutation("brand", "second")
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					String.valueOf(schema.getReference("legacyBrand").orElseThrow().getDescription()),
					String.valueOf(schema.getReference("brand").orElseThrow().getDescription())
				),
				List.of("first", "second")
			),
			Arguments.of(
				"reference attribute description",
				List.of(
					new ModifyReferenceAttributeSchemaMutation(
						"brand", new ModifyAttributeSchemaDescriptionMutation("code", "first")),
					new ModifyReferenceAttributeSchemaMutation(
						"brand", new ModifyAttributeSchemaNameMutation("code", "legacyCode")),
					new ModifyReferenceAttributeSchemaMutation(
						"brand", new ModifyAttributeSchemaNameMutation("replacementCode", "code")),
					new ModifyReferenceAttributeSchemaMutation(
						"brand", new ModifyAttributeSchemaDescriptionMutation("code", "second"))
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					String.valueOf(schema.getReference("brand").orElseThrow()
						.getAttribute("legacyCode").orElseThrow().getDescription()),
					String.valueOf(schema.getReference("brand").orElseThrow()
						.getAttribute("code").orElseThrow().getDescription())
				),
				List.of("first", "second")
			),
			Arguments.of(
				"reference attribute description across a reference rename",
				List.of(
					new ModifyReferenceAttributeSchemaMutation(
						"brand", new ModifyAttributeSchemaDescriptionMutation("code", "first")),
					new ModifyReferenceSchemaNameMutation("brand", "legacyBrand"),
					new ModifyReferenceSchemaNameMutation("maker", "brand"),
					new ModifyReferenceAttributeSchemaMutation(
						"brand", new ModifyAttributeSchemaDescriptionMutation("code", "second"))
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					String.valueOf(schema.getReference("legacyBrand").orElseThrow()
						.getAttribute("code").orElseThrow().getDescription()),
					String.valueOf(schema.getReference("brand").orElseThrow()
						.getAttribute("code").orElseThrow().getDescription())
				),
				List.of("first", "second")
			),
			Arguments.of(
				"reference removed by its old name after a rename",
				List.of(
					new ModifyReferenceSchemaNameMutation("brand", "legacyBrand"),
					new RemoveReferenceSchemaMutation("brand")
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					schema.getReference("legacyBrand").isPresent(),
					schema.getReference("brand").isPresent()
				),
				List.of(true, false)
			),
			Arguments.of(
				"reference re-created between two renames of its name",
				List.of(
					new ModifyReferenceSchemaNameMutation("brand", "legacyBrand"),
					createReference("brand", "Other"),
					new ModifyReferenceSchemaNameMutation("brand", "currentBrand")
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					schema.getReference("legacyBrand").orElseThrow().getReferencedEntityType(),
					schema.getReference("currentBrand").orElseThrow().getReferencedEntityType()
				),
				List.of("Brand", "Other")
			),
			Arguments.of(
				"reference removed and re-created after its name was given to another one",
				List.of(
					new ModifyReferenceSchemaNameMutation("brand", "legacyBrand"),
					new ModifyReferenceSchemaNameMutation("maker", "brand"),
					new RemoveReferenceSchemaMutation("brand"),
					createReference("brand", "Brand")
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					schema.getReference("legacyBrand").orElseThrow().getReferencedEntityType(),
					schema.getReference("brand").orElseThrow().getReferencedEntityType()
				),
				List.of("Brand", "Brand")
			),
			Arguments.of(
				"associated data description",
				List.of(
					new ModifyAssociatedDataSchemaDescriptionMutation("notes", "first"),
					new ModifyAssociatedDataSchemaNameMutation("notes", "legacyNotes"),
					new ModifyAssociatedDataSchemaNameMutation("replacementNotes", "notes"),
					new ModifyAssociatedDataSchemaDescriptionMutation("notes", "second")
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					String.valueOf(schema.getAssociatedData("legacyNotes").orElseThrow().getDescription()),
					String.valueOf(schema.getAssociatedData("notes").orElseThrow().getDescription())
				),
				List.of("first", "second")
			),
			Arguments.of(
				"sortable attribute compound description",
				List.of(
					new ModifySortableAttributeCompoundSchemaDescriptionMutation("compound", "first"),
					new ModifySortableAttributeCompoundSchemaNameMutation("compound", "legacyCompound"),
					new ModifySortableAttributeCompoundSchemaNameMutation("replacementCompound", "compound"),
					new ModifySortableAttributeCompoundSchemaDescriptionMutation("compound", "second")
				),
				(Function<EntitySchemaContract, List<Object>>) schema -> List.of(
					String.valueOf(schema.getSortableAttributeCompound("legacyCompound").orElseThrow().getDescription()),
					String.valueOf(schema.getSortableAttributeCompound("compound").orElseThrow().getDescription())
				),
				List.of("first", "second")
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("entityPipelines")
	@DisplayName("An entity builder keeps statements about a renamed element apart from those about its replacement")
	void shouldNotCombineEntityStatementsAcrossRename(
		@Nonnull String kind,
		@Nonnull List<LocalEntitySchemaMutation> mutations,
		@Nonnull Function<EntitySchemaContract, List<Object>> projection,
		@Nonnull List<Object> expected
	) {
		final CatalogSchemaContract catalogSchema = catalogSchema(Map.of(), List.of());
		final EntitySchemaContract base = baseEntitySchema(catalogSchema);

		final EntitySchemaContract sequential = applyAll(catalogSchema, base, mutations);
		final LocalEntitySchemaMutation[] combined = new InternalEntitySchemaBuilder(catalogSchema, base, mutations)
			.toMutation()
			.orElseThrow()
			.getSchemaMutations();

		assertEquals(expected, projection.apply(sequential));
		assertEquals(expected, projection.apply(applyAll(catalogSchema, base, Arrays.asList(combined))));
	}

	@Test
	@DisplayName("An entity builder still combines two statements about one element when nothing renames it")
	void shouldStillCombineEntityStatementsWithoutRename() {
		final CatalogSchemaContract catalogSchema = catalogSchema(Map.of(), List.of());
		final EntitySchemaContract base = baseEntitySchema(catalogSchema);

		final LocalEntitySchemaMutation[] combined = new InternalEntitySchemaBuilder(catalogSchema, base, List.of(
			new ModifyAttributeSchemaDescriptionMutation("code", "first"),
			new ModifyAttributeSchemaNameMutation("replacementCode", "otherCode"),
			new ModifyAttributeSchemaDescriptionMutation("code", "second")
		)).toMutation().orElseThrow().getSchemaMutations();

		assertEquals(
			List.of(
				new ModifyAttributeSchemaNameMutation("replacementCode", "otherCode"),
				new ModifyAttributeSchemaDescriptionMutation("code", "second")
			),
			Arrays.asList(combined)
		);
	}

	@Test
	@DisplayName("Builder declarations after a rename to the same name still accumulate")
	void shouldAccumulateBuilderDeclarationsAfterRenameToSameName() {
		final CatalogSchemaContract catalogSchema = catalogSchema(Map.of(), List.of());
		final EntitySchemaContract base = baseEntitySchema(catalogSchema);

		final ReferenceSchemaContract reference = new InternalEntitySchemaBuilder(
			catalogSchema, base, List.of(new ModifyReferenceSchemaNameMutation("brand", "brand"))
		)
			.withReferenceTo(
				"brand", "Brand", Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedInScope(Scope.LIVE).indexedInScope(Scope.ARCHIVED)
			)
			.toInstance()
			.getReference("brand")
			.orElseThrow();

		assertEquals(
			List.of(true, true),
			List.of(reference.isIndexedInScope(Scope.LIVE), reference.isIndexedInScope(Scope.ARCHIVED))
		);
	}

	@Test
	@DisplayName("Consecutive catalog builder edits of a renamed entity type still accumulate their declarations")
	void shouldAccumulateCatalogBuilderEditsAfterEntityRename() {
		final CatalogSchemaContract withoutEntities = catalogSchema(Map.of(), List.of());
		final CatalogSchemaContract base = catalogSchema(Map.of(), List.of(
			entityWithUnindexedBrand(withoutEntities, PRODUCT), entityWithUnindexedBrand(withoutEntities, "Category")
		));

		final LocalCatalogSchemaMutation[] combined = new InternalCatalogSchemaBuilder(base, List.of(
			new ModifyEntitySchemaNameMutation(PRODUCT, "LegacyProduct", false),
			new ModifyEntitySchemaNameMutation("Category", PRODUCT, false)
		))
			.withEntitySchema(PRODUCT, whichIs -> whichIs.withReferenceTo(
				"brand", "Brand", Cardinality.ZERO_OR_MORE, reference -> reference.indexedInScope(Scope.LIVE)))
			.withEntitySchema(PRODUCT, whichIs -> whichIs.withReferenceTo(
				"brand", "Brand", Cardinality.ZERO_OR_MORE, reference -> reference.indexedInScope(Scope.ARCHIVED)))
			.toMutation()
			.orElseThrow()
			.getSchemaMutations();
		final ReferenceSchemaContract brand = applyAll(base, Arrays.asList(combined)).entities()
			.getEntitySchema(PRODUCT).orElseThrow()
			.getReference("brand").orElseThrow();

		assertEquals(
			List.of(true, true),
			List.of(brand.isIndexedInScope(Scope.LIVE), brand.isIndexedInScope(Scope.ARCHIVED))
		);
	}

	@Test
	@DisplayName("Two renames of one name stay two renames, as applying them one by one requires")
	void shouldNotMergeTwoRenamesOfOneName() {
		final CatalogSchemaContract catalogSchema = catalogSchema(Map.of(), List.of());
		final EntitySchemaContract base = baseEntitySchema(catalogSchema);
		final List<LocalEntitySchemaMutation> mutations = List.of(
			new ModifyAttributeSchemaNameMutation("code", "legacyCode"),
			new ModifyAttributeSchemaNameMutation("code", "otherCode")
		);

		final LocalEntitySchemaMutation[] combined = new InternalEntitySchemaBuilder(catalogSchema, base, mutations)
			.toMutation()
			.orElseThrow()
			.getSchemaMutations();

		assertEquals(mutations, Arrays.asList(combined));
	}

	@Test
	@DisplayName("A catalog builder keeps statements about a renamed global attribute apart from its replacement")
	void shouldNotCombineGlobalAttributeStatementsAcrossRename() {
		final CatalogSchemaContract base = catalogSchema(
			Map.of(
				"code", globalAttribute("code"),
				"replacementCode", globalAttribute("replacementCode")
			),
			List.of()
		);
		final List<LocalCatalogSchemaMutation> mutations = List.of(
			new ModifyAttributeSchemaDescriptionMutation("code", "first"),
			new ModifyAttributeSchemaNameMutation("code", "legacyCode"),
			new ModifyAttributeSchemaNameMutation("replacementCode", "code"),
			new ModifyAttributeSchemaDescriptionMutation("code", "second")
		);
		final Function<CatalogSchemaContract, List<Object>> projection = schema -> List.of(
			String.valueOf(schema.getAttribute("legacyCode").orElseThrow().getDescription()),
			String.valueOf(schema.getAttribute("code").orElseThrow().getDescription())
		);

		final LocalCatalogSchemaMutation[] combined = new InternalCatalogSchemaBuilder(base, mutations)
			.toMutation()
			.orElseThrow()
			.getSchemaMutations();

		assertEquals(List.of("first", "second"), projection.apply(applyAll(base, mutations).catalogSchema()));
		assertEquals(
			List.of("first", "second"), projection.apply(applyAll(base, Arrays.asList(combined)).catalogSchema()));
	}

	@Test
	@DisplayName("A catalog builder keeps statements about a renamed entity type apart from its replacement")
	void shouldNotCombineEntityTypeStatementsAcrossRename() {
		final CatalogSchemaContract base = catalogSchema(Map.of(), List.of(
			EntitySchema._internalBuild(PRODUCT), EntitySchema._internalBuild("Category")
		));
		final List<LocalCatalogSchemaMutation> mutations = List.of(
			new ModifyEntitySchemaMutation(PRODUCT, new ModifyEntitySchemaDescriptionMutation("first")),
			new ModifyEntitySchemaNameMutation(PRODUCT, "LegacyProduct", false),
			new ModifyEntitySchemaNameMutation("Category", PRODUCT, false),
			new ModifyEntitySchemaMutation(PRODUCT, new ModifyEntitySchemaDescriptionMutation("second"))
		);
		final Function<MutationEntitySchemaAccessor, List<Object>> projection = accessor -> List.of(
			String.valueOf(accessor.getEntitySchema("LegacyProduct").orElseThrow().getDescription()),
			String.valueOf(accessor.getEntitySchema(PRODUCT).orElseThrow().getDescription())
		);

		final LocalCatalogSchemaMutation[] combined = new InternalCatalogSchemaBuilder(base, mutations)
			.toMutation()
			.orElseThrow()
			.getSchemaMutations();

		assertEquals(List.of("first", "second"), projection.apply(applyAll(base, mutations).entities()));
		assertEquals(
			List.of("first", "second"), projection.apply(applyAll(base, Arrays.asList(combined)).entities()));
	}

	@Test
	@DisplayName("Two edits of one entity type through a catalog builder keep the later statement")
	void shouldKeepLaterStatementWhenCatalogBuilderMergesEntityEdits() {
		final CatalogSchemaContract withoutEntities = catalogSchema(Map.of(), List.of());
		final CatalogSchemaContract base = catalogSchema(Map.of(), List.of(
			new InternalEntitySchemaBuilder(withoutEntities, EntitySchema._internalBuild(PRODUCT))
				.withAttribute("code", String.class)
				.toInstance()
		));

		final LocalCatalogSchemaMutation[] combined = new InternalCatalogSchemaBuilder(base)
			.withEntitySchema(PRODUCT, whichIs -> whichIs.withAttribute(
				"code", String.class, attribute -> attribute.withDescription("first")))
			.withEntitySchema(PRODUCT, whichIs -> whichIs.withAttribute(
				"code", String.class, attribute -> attribute.withDescription("second")))
			.toMutation()
			.orElseThrow()
			.getSchemaMutations();

		assertEquals(
			"second",
			applyAll(base, Arrays.asList(combined)).entities().getEntitySchema(PRODUCT).orElseThrow()
				.getAttribute("code").orElseThrow().getDescription()
		);
	}

	/** Applies the mutations one by one, the reference meaning of the pipeline. */
	@Nonnull
	private static EntitySchemaContract applyAll(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract base,
		@Nonnull List<LocalEntitySchemaMutation> mutations
	) {
		EntitySchemaContract schema = base;
		for (final LocalEntitySchemaMutation mutation : mutations) {
			schema = mutation.mutate(catalogSchema, schema);
		}
		return schema;
	}

	/** Applies catalog mutations one by one, tracking the entity schemas they rename or modify. */
	@Nonnull
	private static CatalogOutcome applyAll(
		@Nonnull CatalogSchemaContract base,
		@Nonnull List<LocalCatalogSchemaMutation> mutations
	) {
		final MutationEntitySchemaAccessor entities = new MutationEntitySchemaAccessor(base);
		CatalogSchemaContract schema = base;
		for (final LocalCatalogSchemaMutation mutation : mutations) {
			schema = mutation.mutate(schema, entities).updatedCatalogSchema();
		}
		return new CatalogOutcome(schema, entities);
	}

	/** Builds the entity every entity pipeline starts from: two of each renamable element kind. */
	@Nonnull
	private static EntitySchemaContract baseEntitySchema(@Nonnull CatalogSchemaContract catalogSchema) {
		return new InternalEntitySchemaBuilder(catalogSchema, EntitySchema._internalBuild(PRODUCT))
			.withAttribute("code", String.class)
			.withAttribute("replacementCode", String.class)
			.withAttribute("left", String.class)
			.withAttribute("right", String.class)
			.withAssociatedData("notes", String.class)
			.withAssociatedData("replacementNotes", String.class)
			.withReferenceTo("brand", "Brand", Cardinality.ZERO_OR_MORE, whichIs -> whichIs
				.withAttribute("code", String.class)
				.withAttribute("replacementCode", String.class))
			.withReferenceTo("maker", "Maker", Cardinality.ZERO_OR_MORE, whichIs -> whichIs
				.withAttribute("code", String.class))
			.withSortableAttributeCompound("compound", element("left"), element("right"))
			.withSortableAttributeCompound("replacementCompound", element("right"), element("left"))
			.toInstance();
	}

	/** Builds an entity type with one non-indexed reference `brand` to the non-managed type `Brand`. */
	@Nonnull
	private static EntitySchemaContract entityWithUnindexedBrand(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull String entityType
	) {
		return new InternalEntitySchemaBuilder(catalogSchema, EntitySchema._internalBuild(entityType))
			.withReferenceTo("brand", "Brand", Cardinality.ZERO_OR_MORE, whichIs -> whichIs.nonIndexed())
			.toInstance();
	}

	/** Creates the mutation that adds a reference to a non-managed entity type, as an entity builder produces it. */
	@Nonnull
	private static CreateReferenceSchemaMutation createReference(@Nonnull String name, @Nonnull String entityType) {
		final CatalogSchemaContract catalogSchema = catalogSchema(Map.of(), List.of());
		return Arrays.stream(
				new InternalEntitySchemaBuilder(catalogSchema, EntitySchema._internalBuild(PRODUCT))
					.withReferenceTo(name, entityType, Cardinality.ZERO_OR_MORE)
					.toMutation()
					.orElseThrow()
					.getSchemaMutations()
			)
			.filter(CreateReferenceSchemaMutation.class::isInstance)
			.map(CreateReferenceSchemaMutation.class::cast)
			.findFirst()
			.orElseThrow();
	}

	/** Creates an ordered compound element. */
	@Nonnull
	private static AttributeElement element(@Nonnull String attributeName) {
		return new AttributeElement(attributeName, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
	}

	/** Creates a non-localized String global attribute without normalization. */
	@Nonnull
	private static GlobalAttributeSchemaContract globalAttribute(@Nonnull String name) {
		return GlobalAttributeSchema._internalBuild(name, String.class, false, ConflictResolutionOverride.ENTITY, null);
	}

	/** Supplies a standalone catalog schema carrying the given global attributes and entity schemas. */
	@Nonnull
	private static CatalogSchemaContract catalogSchema(
		@Nonnull Map<String, GlobalAttributeSchemaContract> attributes,
		@Nonnull List<EntitySchemaContract> entitySchemas
	) {
		final Map<String, EntitySchemaContract> entitySchemaIndex = new LinkedHashMap<>(entitySchemas.size());
		for (final EntitySchemaContract entitySchema : entitySchemas) {
			entitySchemaIndex.put(entitySchema.getName(), entitySchema);
		}
		final EntitySchemaProvider provider = new EntitySchemaProvider() {
			@Nonnull
			@Override
			public Collection<EntitySchemaContract> getEntitySchemas() {
				return new ArrayList<>(entitySchemaIndex.values());
			}

			@Nonnull
			@Override
			public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
				return Optional.ofNullable(entitySchemaIndex.get(entityType));
			}
		};
		return CatalogSchema._internalBuild(1, "test", NamingConvention.generate("test"), null, null,
			EnumSet.allOf(CatalogEvolutionMode.class), attributes, provider);
	}

	/**
	 * The outcome of applying catalog mutations one by one.
	 *
	 * @param catalogSchema the resulting catalog schema
	 * @param entities      the entity schemas after the renames and modifications the mutations made
	 */
	private record CatalogOutcome(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull MutationEntitySchemaAccessor entities
	) {
	}
}
