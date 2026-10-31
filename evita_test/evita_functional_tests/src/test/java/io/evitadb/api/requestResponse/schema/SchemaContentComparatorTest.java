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

import io.evitadb.api.APITestConstants;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithPriceMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how the schema builders decide that their collected mutations change nothing, which they judge by comparing
 * the final schema with the base schema property by property (`SchemaContentComparator`).
 *
 * Two failure directions matter. A genuine change of any single property must still be emitted - a property the
 * comparison overlooks would silently drop such a change, which is far worse than a redundant mutation. And a value
 * that is set and later restored must emit nothing, because the server records every received mutation in the
 * transaction and derives conflict keys from it. The field inventory tests make the first direction future-proof: they
 * fail as soon as a schema DTO gains a field nobody has classified as compared or derived.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Schema builders recognising mutations that change nothing")
@Tag(CONTRACT)
@Tag(SCHEMA)
class SchemaContentComparatorTest {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_URL = "url";
	private static final String ASSOCIATED_DATA_LABELS = "labels";
	private static final String REFERENCE_BRAND = "brand";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String RENAMED_PRODUCT = "renamedProduct";
	private static final String HISTOGRAM_CONDITIONAL = "conditionalHistogram";
	private static final String HISTOGRAM_UNCONDITIONAL = "unconditionalHistogram";
	private static final String EXPRESSION_LIST_MODE = "$reference.groupEntity?.attributes['mode'] == 'LIST'";
	private static final String EXPRESSION_SLIDER_MODE = "$reference.groupEntity?.attributes['mode'] == 'SLIDER'";
	private static final String EXPRESSION_SIZE = "$reference.attributes['size'] ?? 0";
	private static final String EXPRESSION_WEIGHT = "$reference.attributes['weight'] ?? 0";
	private static final String EXPRESSION_RANGED = "$reference.attributes['ranged'] == true";
	private static final String EXPRESSION_NOT_RANGED = "$reference.attributes['ranged'] == false";
	private static final String COMPOUND_CODE_NAME = "codeName";
	private static final String ORIGINAL_DESCRIPTION = "original";
	private static final Currency EUR = Currency.getInstance("EUR");
	private static final Currency CZK = Currency.getInstance("CZK");

	/**
	 * `EntitySchema` fields `SchemaContentComparator.isEntitySchemaContentEqual` compares through the schema contract.
	 */
	private static final Set<String> COMPARED_ENTITY_SCHEMA_FIELDS = Set.of(
		"name", "nameVariants", "description", "deprecationNotice", "conflictResolution", "withGeneratedPrimaryKey",
		"withHierarchy", "hierarchyIndexedInScopes", "withPrice", "priceIndexedInScopes", "indexedPricePlaces",
		"locales", "currencies", "evolutionMode", "attributes", "sortableAttributeCompounds", "associatedData",
		"references"
	);
	/**
	 * `EntitySchema` fields deliberately left out: the version counter and data derived from the compared fields.
	 */
	private static final Set<String> NOT_COMPARED_ENTITY_SCHEMA_FIELDS = Set.of(
		"version", "sortableAttributeCompoundNameIndex", "attributeNameIndex", "associatedDataNameIndex",
		"referenceNameIndex", "reflectedReferences", "nonNullableOrDefaultValueAttributes", "nonNullableAssociatedData",
		"attributeToSortableAttributeCompoundIndex", "memoizedLocalized"
	);
	/**
	 * `CatalogSchema` fields `SchemaContentComparator.isCatalogSchemaContentEqual` compares through the schema contract.
	 */
	private static final Set<String> COMPARED_CATALOG_SCHEMA_FIELDS = Set.of(
		"name", "nameVariants", "description", "conflictResolution", "catalogEvolutionMode", "attributes"
	);
	/**
	 * `CatalogSchema` fields deliberately left out: the version counter, derived data and the entity schemas, which the
	 * catalog builder judges one by one.
	 */
	private static final Set<String> NOT_COMPARED_CATALOG_SCHEMA_FIELDS = Set.of(
		"version", "attributeNameIndex", "entitySchemaAccessor"
	);

	private final EntitySchema baseEntitySchema;
	private final EntitySchema baseBrandSchema;
	private final CatalogSchema baseCatalogSchema;

	/**
	 * Builds a base catalog with one global attribute, a base product schema touching every entity-level property, so
	 * that each scenario can change exactly one of them, and a brand schema with a description only, so that catalog
	 * scenarios can modify a second entity schema.
	 */
	SchemaContentComparatorTest() {
		final CatalogSchema blankCatalogSchema = createCatalogSchema();
		this.baseEntitySchema = (EntitySchema) new InternalEntitySchemaBuilder(
			blankCatalogSchema, EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withDescription(ORIGINAL_DESCRIPTION)
			.withHierarchy()
			.withPriceInCurrency(2, EUR)
			.withLocale(Locale.ENGLISH)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterable().sortable())
			.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::sortable)
			.withSortableAttributeCompound(
				COMPOUND_CODE_NAME, attributeElement(ATTRIBUTE_CODE), attributeElement(ATTRIBUTE_NAME)
			)
			.withAssociatedData(ASSOCIATED_DATA_LABELS, String.class)
			.withReferenceTo(REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
			.toInstance();
		this.baseBrandSchema = (EntitySchema) new InternalEntitySchemaBuilder(
			blankCatalogSchema, EntitySchema._internalBuild(Entities.BRAND)
		)
			.withDescription(ORIGINAL_DESCRIPTION)
			.toInstance();
		this.baseCatalogSchema = (CatalogSchema) new InternalCatalogSchemaBuilder(
			createCatalogSchema(this.baseEntitySchema, this.baseBrandSchema)
		)
			.withDescription(ORIGINAL_DESCRIPTION)
			.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGloballyWithinLocale())
			.toInstance();
	}

	/**
	 * Returns scenarios that each change exactly one entity-level property of the base product schema.
	 */
	@Nonnull
	static Stream<Arguments> genuineEntityChanges() {
		return Stream.of(
			entityScenario("description", builder -> builder.withDescription("changed")),
			entityScenario("deprecation notice", builder -> builder.deprecated("no longer used")),
			entityScenario(
				"conflict resolution", builder -> builder.withConflictResolution(new ConflictResolution(ConflictPolicy.COLLECTION))
			),
			entityScenario(
				"generated primary key",
				builder -> {
					if (builder.isWithGeneratedPrimaryKey()) {
						builder.withoutGeneratedPrimaryKey();
					} else {
						builder.withGeneratedPrimaryKey();
					}
				}
			),
			entityScenario("hierarchy", EntitySchemaEditor::withoutHierarchy),
			entityScenario("hierarchy indexed scopes", builder -> builder.withHierarchyIndexedInScope(Scope.LIVE, Scope.ARCHIVED)),
			entityScenario("price", EntitySchemaEditor::withoutPrice),
			entityScenario("indexed price places", builder -> builder.withPrice(3)),
			entityScenario("price indexed scopes", builder -> builder.withPriceIndexedInScope(2, Scope.LIVE, Scope.ARCHIVED)),
			entityScenario("locales", builder -> builder.withLocale(Locale.GERMAN)),
			entityScenario("currencies", builder -> builder.withPriceInCurrency(CZK)),
			entityScenario("evolution mode", EntitySchemaEditor::verifySchemaStrictly),
			entityScenario(
				"attribute", builder -> builder.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
			),
			entityScenario(
				"sortable attribute compound",
				builder -> builder.withSortableAttributeCompound(
					"nameCode", attributeElement(ATTRIBUTE_NAME), attributeElement(ATTRIBUTE_CODE)
				)
			),
			entityScenario("associated data", builder -> builder.withoutAssociatedData(ASSOCIATED_DATA_LABELS)),
			entityScenario(
				"reference", builder -> builder.withReferenceTo(REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE)
			)
		);
	}

	/**
	 * Returns scenarios that change a property of the base product schema and restore it afterwards.
	 */
	@Nonnull
	static Stream<Arguments> restoredEntityChanges() {
		return Stream.of(
			entityScenario("description", builder -> builder.withDescription("changed").withDescription(ORIGINAL_DESCRIPTION)),
			entityScenario("indexed price places", builder -> builder.withPrice(3).withPrice(2)),
			entityScenario("locales", builder -> builder.withLocale(Locale.GERMAN).withoutLocale(Locale.GERMAN)),
			entityScenario(
				"attribute",
				builder -> builder
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.withDescription("changed"))
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.withDescription(null))
			),
			entityScenario(
				"reference",
				builder -> builder.withReferenceTo(
					REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.withDescription("changed").withDescription(null)
				)
			)
		);
	}

	@DisplayName("emit a genuine change of a single entity-level property")
	@ParameterizedTest(name = "{0}")
	@MethodSource("genuineEntityChanges")
	void shouldEmitGenuineEntityChange(@Nonnull String property, @Nonnull Consumer<EntitySchemaBuilder> change) {
		final EntitySchemaBuilder builder = openEntityBuilder();
		change.accept(builder);

		assertTrue(
			builder.toMutation().isPresent(),
			"A genuine change of the " + property + " must be emitted."
		);
	}

	@DisplayName("emit nothing when an entity-level property is changed and restored")
	@ParameterizedTest(name = "{0}")
	@MethodSource("restoredEntityChanges")
	void shouldEmitNothingWhenEntityChangeIsRestored(@Nonnull String property, @Nonnull Consumer<EntitySchemaBuilder> change) {
		final EntitySchemaBuilder builder = openEntityBuilder();
		change.accept(builder);

		assertEquals(Optional.empty(), builder.toMutation(), "The restored " + property + " must not be emitted.");
	}

	/**
	 * Returns scenarios that each change exactly one catalog-level property of the base catalog schema.
	 */
	@Nonnull
	static Stream<Arguments> genuineCatalogChanges() {
		return Stream.of(
			catalogScenario("description", builder -> builder.withDescription("changed")),
			catalogScenario(
				"conflict resolution", builder -> builder.withConflictResolution(new ConflictResolution(ConflictPolicy.COLLECTION))
			),
			catalogScenario("evolution mode", CatalogSchemaEditor::verifyCatalogSchemaStrictly),
			catalogScenario(
				"global attribute",
				builder -> builder.withAttribute(ATTRIBUTE_URL, String.class, GlobalAttributeSchemaEditor::uniqueGlobally)
			)
		);
	}

	@DisplayName("emit a genuine change of a single catalog-level property")
	@ParameterizedTest(name = "{0}")
	@MethodSource("genuineCatalogChanges")
	void shouldEmitGenuineCatalogChange(@Nonnull String property, @Nonnull Consumer<CatalogSchemaBuilder> change) {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(this.baseCatalogSchema);
		change.accept(builder);

		assertTrue(
			builder.toMutation().isPresent(),
			"A genuine change of the " + property + " must be emitted."
		);
	}

	@DisplayName("emit nothing when the global uniqueness is changed and restored")
	@Test
	void shouldEmitNothingWhenGlobalUniquenessIsRestored() {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(this.baseCatalogSchema)
			.withAttribute(ATTRIBUTE_URL, String.class, GlobalAttributeSchemaEditor::uniqueGlobally)
			.withAttribute(ATTRIBUTE_URL, String.class, GlobalAttributeSchemaEditor::uniqueGloballyWithinLocale);

		assertEquals(Optional.empty(), builder.toMutation());
	}

	@DisplayName("keep the entity change and drop the restored catalog-level change")
	@Test
	void shouldDropRestoredCatalogLevelChangeNextToGenuineEntityChange() {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(this.baseCatalogSchema)
			.withAttribute(ATTRIBUTE_URL, String.class, GlobalAttributeSchemaEditor::uniqueGlobally)
			.withAttribute(ATTRIBUTE_URL, String.class, GlobalAttributeSchemaEditor::uniqueGloballyWithinLocale)
			.withEntitySchema(Entities.PRODUCT, entity -> entity.withDescription("changed"));

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(1, mutations.length, () -> "Expected only the entity change, got " + Arrays.toString(mutations));
		assertInstanceOf(ModifyEntitySchemaMutation.class, mutations[0]);
	}

	@DisplayName("keep the catalog-level change and drop the entity change that changes nothing")
	@Test
	void shouldDropNoOpEntityChangeNextToGenuineCatalogLevelChange() {
		// the entity modification re-sends the price settings the base product schema already has, as a client
		// with an outdated view of the schema would
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(
			this.baseCatalogSchema,
			List.of(
				new ModifyCatalogSchemaDescriptionMutation("changed"),
				new ModifyEntitySchemaMutation(
					Entities.PRODUCT,
					new SetEntitySchemaWithPriceMutation(true, new Scope[]{Scope.LIVE}, 2)
				)
			)
		);

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(1, mutations.length, () -> "Expected only the catalog change, got " + Arrays.toString(mutations));
		assertTrue(
			Arrays.stream(mutations).noneMatch(ModifyEntitySchemaMutation.class::isInstance),
			() -> "The unchanged entity change must not be emitted: " + Arrays.toString(mutations)
		);
	}

	@DisplayName("keep a modification of an entity schema the catalog does not have yet")
	@Test
	void shouldKeepModificationOfEntitySchemaCreatedImplicitly() {
		// the server creates a missing entity schema a modification targets when the catalog evolution mode allows
		// adding entity types, while the builder's own replay has no entity schema to apply the modification to
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(
			this.baseCatalogSchema,
			List.of(
				new ModifyEntitySchemaMutation(
					Entities.CATEGORY, new ModifyEntitySchemaDescriptionMutation("created by its modification")
				)
			)
		);

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(1, mutations.length, () -> "Expected the modification, got " + Arrays.toString(mutations));
		assertInstanceOf(ModifyEntitySchemaMutation.class, mutations[0]);
		assertEquals(Entities.CATEGORY, ((ModifyEntitySchemaMutation) mutations[0]).getName());
	}

	@DisplayName("keep every mutation when an entity change depends on a global attribute removed later")
	@Test
	void shouldKeepAllMutationsWhenEntityChangeDependsOnRemovedGlobalAttribute() {
		// the product adopts the global attribute while it still exists, the catalog removes it afterwards - judging
		// the entity change against the final catalog schema alone would fail on the attribute it no longer has
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(this.baseCatalogSchema)
			.withEntitySchema(
				Entities.PRODUCT,
				entity -> entity.withGlobalAttribute(ATTRIBUTE_URL).withDescription("changed")
			)
			.withoutAttribute(ATTRIBUTE_URL);

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(2, mutations.length, () -> "Expected both levels, got " + Arrays.toString(mutations));
		assertInstanceOf(ModifyEntitySchemaMutation.class, mutations[0]);
		assertInstanceOf(RemoveAttributeSchemaMutation.class, mutations[1]);
		// the emitted mutations must apply to the base schema in the order they are sent
		final CatalogSchemaContract replayedSchema = new InternalCatalogSchemaBuilder(
			this.baseCatalogSchema, List.of(mutations)
		).toInstance();
		assertTrue(replayedSchema.getAttribute(ATTRIBUTE_URL).isEmpty());
	}

	@DisplayName("emit the merged modification when an existing entity is modified twice")
	@Test
	void shouldEmitMergedModificationWhenExistingEntityIsModifiedTwice() {
		// the second modification materializes the builder before it is merged with the first one, so the builder
		// has to replay the merged modification from the base catalog schema to judge it
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(this.baseCatalogSchema)
			.withEntitySchema(
				Entities.PRODUCT,
				entity -> entity.withReferenceTo(
					REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.withAttribute(ATTRIBUTE_ORDER, Integer.class)
				)
			)
			.withEntitySchema(Entities.PRODUCT, entity -> entity.withDescription("changed"));

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(1, mutations.length, () -> "Expected one merged modification, got " + Arrays.toString(mutations));
		final ModifyEntitySchemaMutation modification =
			assertInstanceOf(ModifyEntitySchemaMutation.class, mutations[0]);
		// the emitted modification must carry both changes when applied to the base product schema
		final EntitySchemaContract modifiedProduct = modification.mutate(this.baseCatalogSchema, this.baseEntitySchema);
		assertNotNull(modifiedProduct);
		assertEquals("changed", modifiedProduct.getDescription());
		assertTrue(
			modifiedProduct.getReference(REFERENCE_CATEGORIES)
				.flatMap(reference -> reference.getAttribute(ATTRIBUTE_ORDER))
				.isPresent(),
			"The reference and its attribute must be emitted."
		);
	}

	@DisplayName("keep the creation of an entity schema and drop the restored catalog-level change")
	@Test
	void shouldKeepEntitySchemaCreationNextToRestoredCatalogLevelChange() {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(this.baseCatalogSchema)
			.withAttribute(ATTRIBUTE_URL, String.class, GlobalAttributeSchemaEditor::uniqueGlobally)
			.withAttribute(ATTRIBUTE_URL, String.class, GlobalAttributeSchemaEditor::uniqueGloballyWithinLocale)
			.withEntitySchema(Entities.CATEGORY, entity -> entity.withDescription("created"));

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(
			2, mutations.length, () -> "Expected the creation and its modification, got " + Arrays.toString(mutations)
		);
		assertInstanceOf(CreateEntitySchemaMutation.class, mutations[0]);
		assertInstanceOf(ModifyEntitySchemaMutation.class, mutations[1]);
	}

	@DisplayName("keep the removal of an entity schema")
	@Test
	void shouldKeepEntitySchemaRemoval() {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(
			this.baseCatalogSchema, List.of(new RemoveEntitySchemaMutation(Entities.PRODUCT))
		);

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(1, mutations.length, () -> "Expected only the removal, got " + Arrays.toString(mutations));
		assertInstanceOf(RemoveEntitySchemaMutation.class, mutations[0]);
	}

	@DisplayName("keep the renaming of an entity schema together with the modifications of that entity schema")
	@Test
	void shouldKeepEntitySchemaRenamingWithItsModifications() {
		// the modification re-sends the price settings the base product schema already has - on its own it is
		// dropped, but the same entity schema is renamed afterwards
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(
			this.baseCatalogSchema,
			List.of(
				new ModifyEntitySchemaMutation(
					Entities.PRODUCT,
					new SetEntitySchemaWithPriceMutation(true, new Scope[]{Scope.LIVE}, 2)
				),
				new ModifyEntitySchemaNameMutation(Entities.PRODUCT, RENAMED_PRODUCT, false)
			)
		);

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(
			2, mutations.length, () -> "Expected the modification and the renaming, got " + Arrays.toString(mutations)
		);
		assertInstanceOf(ModifyEntitySchemaMutation.class, mutations[0]);
		assertInstanceOf(ModifyEntitySchemaNameMutation.class, mutations[1]);
	}

	@DisplayName("judge each modified entity schema on its own")
	@Test
	void shouldJudgeEachModifiedEntitySchemaOnItsOwn() {
		// the brand modification re-sends the description the base brand schema already has
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(
			this.baseCatalogSchema,
			List.of(
				new ModifyEntitySchemaMutation(Entities.PRODUCT, new ModifyEntitySchemaDescriptionMutation("changed")),
				new ModifyEntitySchemaMutation(
					Entities.BRAND, new ModifyEntitySchemaDescriptionMutation(ORIGINAL_DESCRIPTION)
				)
			)
		);

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(1, mutations.length, () -> "Expected only the product change, got " + Arrays.toString(mutations));
		final ModifyEntitySchemaMutation modification =
			assertInstanceOf(ModifyEntitySchemaMutation.class, mutations[0]);
		assertEquals(Entities.PRODUCT, modification.getName());
	}

	@DisplayName("keep a no-op modification next to a genuine change of the same entity schema")
	@Test
	void shouldKeepNoOpModificationNextToGenuineChangeOfSameEntitySchema() {
		final CatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(
			this.baseCatalogSchema,
			List.of(
				new ModifyEntitySchemaMutation(
					Entities.PRODUCT,
					new SetEntitySchemaWithPriceMutation(true, new Scope[]{Scope.LIVE}, 2),
					new ModifyEntitySchemaDescriptionMutation("changed")
				)
			)
		);

		final LocalCatalogSchemaMutation[] mutations = emittedMutations(builder);
		assertEquals(1, mutations.length, () -> "Expected the product change, got " + Arrays.toString(mutations));
		final ModifyEntitySchemaMutation modification =
			assertInstanceOf(ModifyEntitySchemaMutation.class, mutations[0]);
		assertEquals(
			2, modification.getSchemaMutations().length,
			() -> "The entity schema is judged as a whole, so both modifications must be kept: " +
				Arrays.toString(modification.getSchemaMutations())
		);
	}

	@DisplayName("classify every entity schema field as compared or derived")
	@Test
	void shouldAccountForEveryEntitySchemaField() {
		assertFieldsAccountedFor(EntitySchema.class, COMPARED_ENTITY_SCHEMA_FIELDS, NOT_COMPARED_ENTITY_SCHEMA_FIELDS);
	}

	@DisplayName("classify every catalog schema field as compared or derived")
	@Test
	void shouldAccountForEveryCatalogSchemaField() {
		assertFieldsAccountedFor(CatalogSchema.class, COMPARED_CATALOG_SCHEMA_FIELDS, NOT_COMPARED_CATALOG_SCHEMA_FIELDS);
	}

	/**
	 * Asserts that the instance fields of the schema DTO are exactly the compared and the deliberately skipped ones.
	 *
	 * @param schemaClass schema DTO class
	 * @param compared    fields the comparison covers
	 * @param notCompared fields the comparison deliberately skips
	 */
	private static void assertFieldsAccountedFor(
		@Nonnull Class<?> schemaClass,
		@Nonnull Set<String> compared,
		@Nonnull Set<String> notCompared
	) {
		final Set<String> actualFields = Arrays.stream(schemaClass.getDeclaredFields())
			.filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
			.map(Field::getName)
			.collect(Collectors.toCollection(TreeSet::new));
		final Set<String> classifiedFields = new TreeSet<>(compared);
		classifiedFields.addAll(notCompared);
		assertEquals(
			classifiedFields, actualFields,
			schemaClass.getSimpleName() + " fields changed. Compare every new content field in SchemaContentComparator " +
				"(otherwise the schema builders drop its genuine changes) and list it in this test."
		);
	}

	/**
	 * Opens an entity schema builder over the base product schema.
	 */
	@Nonnull
	private EntitySchemaBuilder openEntityBuilder() {
		return new InternalEntitySchemaBuilder(this.baseCatalogSchema, this.baseEntitySchema);
	}

	/**
	 * Returns the mutations the catalog builder emits, or an empty array when it emits none.
	 */
	@Nonnull
	private static LocalCatalogSchemaMutation[] emittedMutations(@Nonnull CatalogSchemaBuilder builder) {
		return builder.toMutation()
			.map(ModifyCatalogSchemaMutation::getSchemaMutations)
			.orElse(new LocalCatalogSchemaMutation[0]);
	}

	/**
	 * Creates a catalog schema that provides the given entity schemas.
	 */
	@Nonnull
	private static CatalogSchema createCatalogSchema(@Nonnull EntitySchemaContract... entitySchemas) {
		final List<EntitySchemaContract> providedSchemas = List.of(entitySchemas);
		return CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			NamingConvention.generate(APITestConstants.TEST_CATALOG),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return providedSchemas;
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
					return providedSchemas.stream()
						.filter(entitySchema -> entitySchema.getName().equals(entityType))
						.findFirst();
				}
			}
		);
	}

	/**
	 * Creates a named entity-level scenario.
	 */
	@Nonnull
	private static Arguments entityScenario(@Nonnull String property, @Nonnull Consumer<EntitySchemaBuilder> change) {
		return Arguments.of(property, change);
	}

	/**
	 * Creates a named catalog-level scenario.
	 */
	@Nonnull
	private static Arguments catalogScenario(@Nonnull String property, @Nonnull Consumer<CatalogSchemaBuilder> change) {
		return Arguments.of(property, change);
	}

	/**
	 * The reference setters for partial faceting, histograms and partial bucketing collect no mutation when the
	 * reference already has the requested setting, and they compare expressions by their canonical text. Every
	 * scenario therefore passes a freshly parsed expression and inspects what the reference builder itself collected:
	 * the entity builder would drop a redundant mutation anyway when it compares the final schema.
	 */
	@Nested
	@DisplayName("reference setters asked to apply a setting")
	class ReferenceSetters {
		private final EntitySchemaContract productSchema;

		/**
		 * Builds a product schema whose brand reference has partial faceting, one histogram with and one without
		 * a condition, and partial bucketing, all in the live scope.
		 */
		ReferenceSetters() {
			this.productSchema = new InternalEntitySchemaBuilder(
				SchemaContentComparatorTest.this.baseCatalogSchema, SchemaContentComparatorTest.this.baseEntitySchema
			)
				.withReferenceTo(
					REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs
						.indexedInScope(Scope.LIVE)
						.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(EXPRESSION_LIST_MODE))
						.bucketedInScope(
							Scope.LIVE, HISTOGRAM_CONDITIONAL,
							ExpressionFactory.parse(EXPRESSION_SIZE), ExpressionFactory.parse(EXPRESSION_RANGED)
						)
						.bucketedInScope(
							Scope.LIVE, HISTOGRAM_UNCONDITIONAL, ExpressionFactory.parse(EXPRESSION_SIZE), null
						)
						.bucketedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(EXPRESSION_SLIDER_MODE))
				)
				.toInstance();
		}

		/**
		 * Returns scenarios that apply a setting the brand reference already has.
		 */
		@Nonnull
		static Stream<Arguments> unchangedReferenceSettings() {
			return Stream.of(
				referenceScenario(
					"partial faceting",
					reference -> reference.facetedPartiallyInScope(
						Scope.LIVE, ExpressionFactory.parse(EXPRESSION_LIST_MODE)
					)
				),
				referenceScenario(
					"histogram with a condition",
					reference -> reference.bucketedInScope(
						Scope.LIVE, HISTOGRAM_CONDITIONAL,
						ExpressionFactory.parse(EXPRESSION_SIZE), ExpressionFactory.parse(EXPRESSION_RANGED)
					)
				),
				referenceScenario(
					"histogram without a condition",
					reference -> reference.bucketedInScope(
						Scope.LIVE, HISTOGRAM_UNCONDITIONAL, ExpressionFactory.parse(EXPRESSION_SIZE), null
					)
				),
				referenceScenario(
					"partial bucketing",
					reference -> reference.bucketedPartiallyInScope(
						Scope.LIVE, ExpressionFactory.parse(EXPRESSION_SLIDER_MODE)
					)
				)
			);
		}

		/**
		 * Returns scenarios that each change one setting of the brand reference.
		 */
		@Nonnull
		static Stream<Arguments> changedReferenceSettings() {
			return Stream.of(
				referenceScenario(
					"partial faceting expression",
					reference -> reference.facetedPartiallyInScope(
						Scope.LIVE, ExpressionFactory.parse(EXPRESSION_SLIDER_MODE)
					)
				),
				referenceScenario(
					"histogram value",
					reference -> reference.bucketedInScope(
						Scope.LIVE, HISTOGRAM_CONDITIONAL,
						ExpressionFactory.parse(EXPRESSION_WEIGHT), ExpressionFactory.parse(EXPRESSION_RANGED)
					)
				),
				referenceScenario(
					"histogram condition",
					reference -> reference.bucketedInScope(
						Scope.LIVE, HISTOGRAM_CONDITIONAL,
						ExpressionFactory.parse(EXPRESSION_SIZE), ExpressionFactory.parse(EXPRESSION_NOT_RANGED)
					)
				),
				referenceScenario(
					"histogram condition removed",
					reference -> reference.bucketedInScope(
						Scope.LIVE, HISTOGRAM_CONDITIONAL, ExpressionFactory.parse(EXPRESSION_SIZE), null
					)
				),
				referenceScenario(
					"histogram condition added",
					reference -> reference.bucketedInScope(
						Scope.LIVE, HISTOGRAM_UNCONDITIONAL,
						ExpressionFactory.parse(EXPRESSION_SIZE), ExpressionFactory.parse(EXPRESSION_RANGED)
					)
				),
				referenceScenario(
					"partial bucketing expression",
					reference -> reference.bucketedPartiallyInScope(
						Scope.LIVE, ExpressionFactory.parse(EXPRESSION_LIST_MODE)
					)
				)
			);
		}

		@DisplayName("collect nothing for a setting the reference already has")
		@ParameterizedTest(name = "{0}")
		@MethodSource("unchangedReferenceSettings")
		void shouldCollectNothingWhenSettingIsUnchanged(
			@Nonnull String setting,
			@Nonnull Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> change
		) {
			final EntitySchemaBuilder entityBuilder = openBuilderOverReference();

			final Collection<LocalEntitySchemaMutation> collected = changeReference(entityBuilder, change);

			assertEquals(List.of(), List.copyOf(collected), "The unchanged " + setting + " must collect no mutation.");
		}

		@DisplayName("collect and emit a changed setting")
		@ParameterizedTest(name = "{0}")
		@MethodSource("changedReferenceSettings")
		void shouldCollectAndEmitChangedSetting(
			@Nonnull String setting,
			@Nonnull Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> change
		) {
			final EntitySchemaBuilder entityBuilder = openBuilderOverReference();

			final Collection<LocalEntitySchemaMutation> collected = changeReference(entityBuilder, change);

			assertFalse(collected.isEmpty(), "A change of the " + setting + " must be collected.");
			assertTrue(entityBuilder.toMutation().isPresent(), "A change of the " + setting + " must be emitted.");
		}

		@DisplayName("collect a composed expression that reads like the current one but groups its operands differently")
		@Test
		void shouldCollectComposedExpressionWithSameTextAndDifferentGrouping() {
			// `ranged || (list && slider)` as parsed, against `(ranged || list) && slider` as composed - the composed
			// tree has no node for its implied parentheses, so both print the same text while they mean different things
			final Expression parsed = ExpressionFactory.parse(
				EXPRESSION_RANGED + " || " + EXPRESSION_LIST_MODE + " && " + EXPRESSION_SLIDER_MODE
			);
			final Expression composed = ExpressionFactory.and(
				ExpressionFactory.parse(EXPRESSION_RANGED + " || " + EXPRESSION_LIST_MODE),
				ExpressionFactory.parse(EXPRESSION_SLIDER_MODE)
			);
			assertEquals(parsed.toExpressionString(), composed.toExpressionString());
			assertNotEquals(parsed, composed);
			final EntitySchemaContract productWithParsedExpression = new InternalEntitySchemaBuilder(
				SchemaContentComparatorTest.this.baseCatalogSchema, this.productSchema
			)
				.withReferenceTo(
					REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.facetedPartiallyInScope(Scope.LIVE, parsed)
				)
				.toInstance();
			final EntitySchemaBuilder entityBuilder = new InternalEntitySchemaBuilder(
				SchemaContentComparatorTest.this.baseCatalogSchema, productWithParsedExpression
			);

			final Collection<LocalEntitySchemaMutation> collected = changeReference(
				entityBuilder, reference -> reference.facetedPartiallyInScope(Scope.LIVE, composed)
			);

			assertFalse(collected.isEmpty(), "The differently grouped expression must be collected.");
			assertTrue(entityBuilder.toMutation().isPresent(), "The differently grouped expression must be emitted.");
			assertEquals(
				composed,
				entityBuilder.toInstance().getReferenceOrThrowException(REFERENCE_BRAND).getFacetedPartiallyInScope(Scope.LIVE)
			);
		}

		/**
		 * Opens an entity schema builder over the product schema with the configured brand reference.
		 */
		@Nonnull
		private EntitySchemaBuilder openBuilderOverReference() {
			return new InternalEntitySchemaBuilder(
				SchemaContentComparatorTest.this.baseCatalogSchema, this.productSchema
			);
		}

		/**
		 * Applies the change to the brand reference and returns the mutations the reference builder collected.
		 */
		@Nonnull
		private static Collection<LocalEntitySchemaMutation> changeReference(
			@Nonnull EntitySchemaBuilder entityBuilder,
			@Nonnull Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> change
		) {
			final AtomicReference<ReferenceSchemaEditor.ReferenceSchemaBuilder> referenceBuilder =
				new AtomicReference<>();
			entityBuilder.withReferenceTo(
				REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> {
					change.accept(whichIs);
					referenceBuilder.set(whichIs);
				}
			);
			return referenceBuilder.get().toMutation();
		}

		/**
		 * Creates a named reference-level scenario.
		 */
		@Nonnull
		private static Arguments referenceScenario(
			@Nonnull String setting,
			@Nonnull Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> change
		) {
			return Arguments.of(setting, change);
		}

	}

}
