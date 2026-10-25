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


package io.evitadb.index.analysis;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.index.analysis.AttributeNormalizationPolicy.ElementBinding;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.asciiFolding;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Ownership moves and retained bindings of schema-time reconciliation, on plain policies without an engine. */
@Tag(INDEXING)
@Tag(SCHEMA)
@DisplayName("Built-with policy reconciliation branches")
class AttributeNormalizationReconcilerTest {
	private static final AttributeIndexKey CODE = new AttributeIndexKey(null, "code", null);
	private static final AttributeIndexKey TITLE = new AttributeIndexKey(null, "title", null);
	private static final AttributeIndexKey COMPOUND = new AttributeIndexKey(null, "compound", null);
	private static final ElementBinding CODE_ELEMENT = new ElementBinding("code", String.class, false, false);
	private static final ElementBinding OTHER_ELEMENT = new ElementBinding("other", Integer.class, false, false);

	@Nested
	@DisplayName("Global ownership")
	class GlobalOwnership {

		@Test
		@DisplayName("An empty attribute redeclared as global adopts the catalog row and drops its local one")
		void shouldAdoptGlobalIdentityWhenEmptyAndDropLocalRow() {
			final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
				Map.of(CODE, List.of(lowercase())), Map.of(), Set.of());

			final AttributeNormalizationPolicy result = AttributeNormalizationReconciler.reconcile("unit", previous,
				globalCodeSchema(), key -> false, key -> false, Map.of("code", List.of(asciiFolding())));

			assertFalse(result.rows().containsKey(CODE));
			assertEquals(Set.of("code"), result.globalAttributes());
		}

		@Test
		@DisplayName("A populated attribute redeclared as global keeps its local row and adopts nothing")
		void shouldKeepLocalRowAndNotAdoptGlobalWhenPopulated() {
			final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
				Map.of(CODE, List.of(lowercase())), Map.of(), Set.of());

			final AttributeNormalizationPolicy result = AttributeNormalizationReconciler.reconcile("unit", previous,
				globalCodeSchema(), key -> true, key -> false, Map.of("code", List.of(asciiFolding())));

			assertEquals(List.of(lowercase()), result.rows().get(CODE));
			assertTrue(result.globalAttributes().isEmpty());
		}

		@Test
		@DisplayName("An empty adopted global attribute redeclared locally releases the catalog row")
		void shouldReleaseAdoptedGlobalIdentityWhenLocalDeclarationReplacesItAndAttributeIsEmpty() {
			final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
				Map.of(), Map.of(), Set.of("code"));

			final AttributeNormalizationPolicy result = AttributeNormalizationReconciler.reconcile("unit", previous,
				localCodeSchema(), key -> false, key -> false, Map.of("code", List.of(asciiFolding())));

			assertTrue(result.globalAttributes().isEmpty());
			assertEquals(List.of(lowercase()), result.rows().get(CODE));
		}

		@Test
		@DisplayName("A populated adopted global attribute redeclared locally keeps the catalog row")
		void shouldKeepAdoptedGlobalIdentityWhenLocalDeclarationReplacesItAndAttributeIsPopulated() {
			final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
				Map.of(), Map.of(), Set.of("code"));

			final AttributeNormalizationPolicy result = AttributeNormalizationReconciler.reconcile("unit", previous,
				localCodeSchema(), key -> true, key -> false, Map.of("code", List.of(asciiFolding())));

			assertEquals(Set.of("code"), result.globalAttributes());
			assertFalse(result.rows().containsKey(CODE));
		}
	}

	@Nested
	@DisplayName("Compound bindings")
	class CompoundBindings {

		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("A changed compound declaration rebinds only while the compound holds no tuple")
		void shouldRebindCompoundWhenNotPopulatedAndKeepRetainedBindingWhenPopulated(boolean compoundPopulated) {
			final List<ElementBinding> retained = List.of(CODE_ELEMENT, OTHER_ELEMENT);
			final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
				Map.of(), Map.of(COMPOUND, retained), Set.of());
			final EntitySchemaContract schema = builder(catalogSchema(Map.of()))
				.withAttribute("code", String.class, a -> a.nullable())
				.withAttribute("other", Integer.class)
				.withSortableAttributeCompound("compound", element("other"), element("code"))
				.toInstance();

			final AttributeNormalizationPolicy result = AttributeNormalizationReconciler.reconcile("unit", previous,
				schema, key -> false, key -> compoundPopulated, Map.of());

			assertEquals(
				compoundPopulated ? retained : List.of(OTHER_ELEMENT, CODE_ELEMENT),
				result.compounds().get(COMPOUND)
			);
		}

		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("A removed compound declaration drops its binding only while the compound holds no tuple")
		void shouldDropRetainedCompoundBindingOfRemovedDeclarationOnlyWhenNotPopulated(boolean compoundPopulated) {
			final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
				Map.of(), Map.of(COMPOUND, List.of(CODE_ELEMENT, OTHER_ELEMENT)), Set.of());
			final EntitySchemaContract schema = builder(catalogSchema(Map.of()))
				.withAttribute("code", String.class, a -> a.nullable())
				.withAttribute("other", Integer.class)
				.toInstance();

			final AttributeNormalizationPolicy result = AttributeNormalizationReconciler.reconcile("unit", previous,
				schema, key -> false, key -> compoundPopulated, Map.of());

			assertEquals(compoundPopulated, result.compounds().containsKey(COMPOUND));
		}
	}

	@Test
	@DisplayName("A renamed attribute is a new identity while the populated old name keeps its row")
	void shouldTreatRenamedAttributeAsIndependentIdentity() {
		final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(lowercase())), Map.of(), Set.of());
		final EntitySchemaContract schema = builder(catalogSchema(Map.of()))
			.withAttribute("title", String.class, a -> a.normalized(asciiFolding()))
			.toInstance();

		final AttributeNormalizationPolicy result = AttributeNormalizationReconciler.reconcile("unit", previous,
			schema, CODE::equals, key -> false, Map.of());

		assertEquals(Map.of(CODE, List.of(lowercase()), TITLE, List.of(asciiFolding())), result.rows());
	}

	/** Declares `code` through a catalog attribute whose own chain differs from any local row used here. */
	@Nonnull
	private static EntitySchemaContract globalCodeSchema() {
		final GlobalAttributeSchemaContract code = GlobalAttributeSchema._internalBuild(
			"code", String.class, false, ConflictResolutionOverride.ENTITY, List.of(asciiFolding()));
		return builder(catalogSchema(Map.of("code", code))).withGlobalAttribute("code").toInstance();
	}

	/** Declares `code` as a collection attribute normalized with `lowercase`. */
	@Nonnull
	private static EntitySchemaContract localCodeSchema() {
		return builder(catalogSchema(Map.of()))
			.withAttribute("code", String.class, a -> a.normalized(lowercase()))
			.toInstance();
	}

	/** Creates an ordered compound element. */
	@Nonnull
	private static AttributeElement element(@Nonnull String name) {
		return new AttributeElement(name, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
	}

	/** Produces schema candidates without an engine. */
	@Nonnull
	private static InternalEntitySchemaBuilder builder(@Nonnull CatalogSchema catalogSchema) {
		return new InternalEntitySchemaBuilder(catalogSchema, EntitySchema._internalBuild("Product"));
	}

	/** Supplies a standalone catalog schema carrying the given global attributes. */
	@Nonnull
	private static CatalogSchema catalogSchema(@Nonnull Map<String, GlobalAttributeSchemaContract> attributes) {
		return CatalogSchema._internalBuild(1, "unit", NamingConvention.generate("unit"), null, null,
			EnumSet.allOf(CatalogEvolutionMode.class), attributes, EmptyEntitySchemaAccessor.INSTANCE);
	}
}
