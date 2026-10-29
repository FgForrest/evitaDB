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

package io.evitadb.index.virtual;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.ReferencePartitionReindexRequiredException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.indexSelection.TargetIndexes.EligibilityObstacle;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ExceptionUtils;
import io.evitadb.utils.Functions;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.facetSummary;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.fromRoot;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinRoot;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.pickFirstByEntityProperty;
import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static io.evitadb.api.query.QueryConstraints.priceNatural;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.statistics;
import static io.evitadb.api.query.QueryConstraints.traverseByEntityProperty;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.CATALOG;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.utils.CollectionUtils.createHashSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Changes the indexing of a reference between `FOR_FILTERING` and `FOR_FILTERING_AND_PARTITIONING` in a catalog that
 * writes its single-owner partitions as registry rows, and writes through the partitions the reference had before the
 * change. Nothing converts them: the rows of a reference that became indexed for partitioning stay rows, and the index
 * objects of single-owner partitions of a reference that became indexed for filtering only stay objects.
 *
 * - A write that would put content into a row of a reference indexed for partitioning now - an owner entering it, a
 *   reference or entity attribute, a locale, a price, a facet or a facet group - is refused with
 *   {@link ReferencePartitionReindexRequiredException}, naming the reference and the scope, and leaves the catalog
 *   exactly as it was.
 * - A write that only takes content out of such a row - a removed attribute, locale, price, reference or entity, an
 *   owner moving to the other scope - proceeds on the row alone. A facet re-evaluation it causes is refused only when
 *   it would put a facet into the row.
 * - The index objects a reference indexed for partitioning left keep being read and written, gain and lose owners and
 *   are demoted to rows once a single owner is left.
 *
 * Every history runs in a catalog warming up and in a live one, the change in either scope, and a reflected reference
 * inheriting its indexing from its source reference follows the source's change.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Single-owner partitions after a change of their reference's index type")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(SCHEMA)
class VirtualPartitionIndexTypeChangeTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	/**
	 * The reference whose index type changes - in both scopes, faceted, with a group type, a sortable attribute and a
	 * localized sortable attribute.
	 */
	private static final String REFERENCE_CATEGORIES = "categories";
	/**
	 * The reflection of {@link #REFERENCE_CATEGORIES} on the category, inheriting its indexing.
	 */
	private static final String REFERENCE_PRODUCTS = "products";
	/**
	 * A reference indexed for filtering in both scopes, faceted only while {@link #FACETED_WHEN} holds.
	 */
	private static final String REFERENCE_TAGS = "tags";
	private static final String ATTRIBUTE_VISIBLE = "visible";
	private static final String ATTRIBUTE_PINNED = "pinned";
	/**
	 * The expression {@link #REFERENCE_TAGS} is faceted by - a product visible or pinned.
	 */
	private static final String FACETED_WHEN =
		"($entity.attributes['visible'] ?? false) == true || ($entity.attributes['pinned'] ?? false) == true";
	/**
	 * A reference to a category indexed for filtering in both scopes, faceted only while {@link #SHOWN_WHEN} holds -
	 * added by the test that needs a facet re-evaluated by a change of another entity, so the fixture of the other
	 * tests stays as it is.
	 */
	private static final String REFERENCE_LABELS = "labels";
	/**
	 * The expression {@link #REFERENCE_LABELS} is faceted by - a referenced category titled `SHOWN`.
	 */
	private static final String SHOWN_WHEN = "($reference.referencedEntity.attributes['title'] ?? '') == 'SHOWN'";
	/**
	 * A reference to a category allowing duplicates told apart by {@link #ATTRIBUTE_SLOT}, indexed for filtering in
	 * both scopes - added by the test that needs one, so the fixture of the other tests stays as it is.
	 */
	private static final String REFERENCE_PLACEMENTS = "placements";
	/**
	 * The representative attribute of {@link #REFERENCE_PLACEMENTS}.
	 */
	private static final String ATTRIBUTE_SLOT = "slot";
	/**
	 * A filterable entity attribute of the product that is not localized, each product ranked by its primary key -
	 * added by the tests that filter it, so the fixture of the other tests stays as it is. A hierarchy query filters it
	 * rather than a localized attribute: the locale of the query also restricts the parent filter of its hierarchy
	 * constraint, and the categories of the fixture hold no localized data, so a query with a locale selects no node.
	 */
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_ORDER = "orderInCategory";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ASSOCIATED_DATA_DESCRIPTION = "description";
	private static final String GROUP_TYPE = "categoryGroup";
	private static final String PRICE_LIST = "basic";
	private static final String SECOND_PRICE_LIST = "vip";
	private static final Currency CZK = Currency.getInstance("CZK");
	/**
	 * How many categories exist - enough for the references added later.
	 */
	private static final int CATEGORY_COUNT = 20;
	/**
	 * Products `1`, `2` and `5` live, `3`, `4` and `6` are archived. Product `n` references category `n` alone, so
	 * every partition has a single owner; the even products have German values too.
	 */
	private static final int PRODUCT_COUNT = 6;
	/**
	 * A category no product of the fixture references.
	 */
	private static final int FREE_CATEGORY = 11;
	/**
	 * Another category no product of the fixture references.
	 */
	private static final int SECOND_FREE_CATEGORY = 12;

	/**
	 * The prefix of an answer of a read refused for rows of a reference now indexed for partitioning.
	 */
	private static final String READ_REFUSED = "refused for ";
	/**
	 * The prefix of an answer of a read that failed otherwise.
	 */
	private static final String READ_FAILED = "failed with ";
	/**
	 * The answer of a read that found nothing.
	 */
	private static final String EMPTY_ANSWER = "[]";

	@Nullable private Evita evita;
	@Nullable private TestPaths paths;

	/**
	 * Supplies every combination of the catalog state and the scope whose indexing changes.
	 *
	 * @return the arguments
	 */
	@Nonnull
	static Stream<Arguments> statesAndScopes() {
		return Stream.of(CatalogState.WARMING_UP, CatalogState.ALIVE)
			.flatMap(state -> Arrays.stream(Scope.values()).map(scope -> Arguments.of(state, scope)));
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null) {
			this.evita.close();
			this.evita = null;
		}
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
			this.paths = null;
		}
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("statesAndScopes")
	@DisplayName("every write adding to a row of a reference now indexed for partitioning fails and changes nothing")
	void shouldRefuseEveryAddingWriteIntoARowOfTheFormerIndexing(CatalogState state, Scope changedScope) {
		final Evita theEvita = start("refusedAdding", state, false);
		final int owner = ownerIn(changedScope);
		final int other = otherIn(changedScope);
		final int partition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner);
		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, true);
		assertEquals(
			ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
			referenceSchema(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES).getReferenceIndexType(changedScope)
		);
		assertEquals(
			ReferenceIndexType.FOR_FILTERING,
			referenceSchema(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES).getReferenceIndexType(
				otherScope(changedScope)
			),
			"the indexing of the other scope must not change"
		);
		assertEquals(partition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner));

		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, "a second owner enters the row",
			updateProduct(other, it -> it.setReference(REFERENCE_CATEGORIES, owner))
		);
		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope,
			"the owner's new reference adds its facet to the row",
			updateProduct(owner, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY))
		);
		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, "a reference attribute is upserted",
			updateProduct(
				owner, it -> it.setReference(REFERENCE_CATEGORIES, owner, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 100))
			)
		);
		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, "an entity attribute is upserted",
			updateProduct(owner, it -> it.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Renamed"))
		);
		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, "the owner gains a locale",
			updateProduct(owner, it -> it.setAssociatedData(ASSOCIATED_DATA_DESCRIPTION, Locale.GERMAN, "Neu"))
		);
		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, "a price is upserted",
			updateProduct(
				owner,
				it -> it.setPrice(
					2, SECOND_PRICE_LIST, CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true
				)
			)
		);
		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, "a facet group is set",
			updateProduct(owner, it -> it.setReference(REFERENCE_CATEGORIES, owner, ref -> ref.setGroup(1)))
		);

		assertEquals(partition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner));
		assertNoPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(owner));
		assertNoPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(FREE_CATEGORY));
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("statesAndScopes")
	@DisplayName("a facet re-evaluation passes a row of the former indexing over unless a facet would enter it")
	void shouldRefuseFacetReEvaluationOnlyWhenAFacetWouldEnterARowOfTheFormerIndexing(
		CatalogState state,
		Scope changedScope
	) {
		final Evita theEvita = start("facetReEvaluation", state, false);
		final int owner = ownerIn(changedScope);
		final int other = otherIn(changedScope);
		update(
			theEvita,
			updateProduct(
				owner, it -> it.setAttribute(ATTRIBUTE_VISIBLE, true).setReference(REFERENCE_TAGS, FREE_CATEGORY)
			)
		);
		update(
			theEvita,
			updateProduct(
				other,
				it -> it.setAttribute(ATTRIBUTE_VISIBLE, true)
					.setAttribute(ATTRIBUTE_PINNED, true)
					.setReference(REFERENCE_TAGS, FREE_CATEGORY)
			)
		);
		final int ownerPartition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner);
		final int otherPartition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(other), other);
		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, true);

		// the owner is neither visible nor pinned any more - the facet of its tag leaves its partitions, and the row
		// holds none to leave
		update(theEvita, updateProduct(owner, it -> it.removeAttribute(ATTRIBUTE_VISIBLE)));
		assertEquals(ownerPartition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner));

		// the other product stays visible - the facet of its tag belongs into every partition of the product, the
		// row included, which has no place for it
		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope,
			"a removal keeping the tag faceted re-evaluates its facet into the row",
			updateProduct(other, it -> it.removeAttribute(ATTRIBUTE_PINNED))
		);
		assertEquals(otherPartition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(other), other));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a facet re-evaluation caused by another entity passes a row of the former indexing over unless a facet would enter it")
	void shouldRefuseCrossEntityFacetReEvaluationOnlyWhenAFacetWouldEnterARowOfTheFormerIndexing(CatalogState state) {
		final Evita theEvita = start(
			"crossEntityFacetReEvaluation", state, false,
			session -> session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					REFERENCE_LABELS, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
						.facetedInScope(Scope.LIVE, Scope.ARCHIVED)
						.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(SHOWN_WHEN))
						.facetedPartiallyInScope(Scope.ARCHIVED, ExpressionFactory.parse(SHOWN_WHEN))
				)
				.updateVia(session)
		);
		final int owner = ownerIn(Scope.LIVE);
		update(theEvita, updateProduct(owner, it -> it.setReference(REFERENCE_LABELS, FREE_CATEGORY)));
		final int categoryPartition = assertRow(theEvita, ENTITY_PRODUCT, Scope.LIVE, category(owner), owner);
		final int labelPartition = assertRow(theEvita, ENTITY_PRODUCT, Scope.LIVE, label(FREE_CATEGORY), owner);

		// the category is a partition of another reference of the owner the label's facet belongs into
		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, true);
		update(theEvita, retitleCategory(FREE_CATEGORY, "HIDDEN"));
		assertEquals(categoryPartition, assertRow(theEvita, ENTITY_PRODUCT, Scope.LIVE, category(owner), owner));
		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE,
			"the retitled category puts the facet of the label into the row of the category partition",
			retitleCategory(FREE_CATEGORY, "SHOWN")
		);
		assertEquals(categoryPartition, assertRow(theEvita, ENTITY_PRODUCT, Scope.LIVE, category(owner), owner));

		// the label partition itself is the row the facet of the label belongs into
		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_LABELS, Scope.LIVE, true);
		update(theEvita, retitleCategory(FREE_CATEGORY, "STILL HIDDEN"));
		assertEquals(labelPartition, assertRow(theEvita, ENTITY_PRODUCT, Scope.LIVE, label(FREE_CATEGORY), owner));
		assertRefused(
			theEvita, ENTITY_PRODUCT, REFERENCE_LABELS, Scope.LIVE,
			"the retitled category puts the facet of the label into the row of the label partition",
			retitleCategory(FREE_CATEGORY, "SHOWN")
		);
		assertEquals(labelPartition, assertRow(theEvita, ENTITY_PRODUCT, Scope.LIVE, label(FREE_CATEGORY), owner));
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("statesAndScopes")
	@DisplayName("every write removing from a row of a reference now indexed for partitioning proceeds on the row")
	void shouldTakeContentOutOfARowOfTheFormerIndexing(CatalogState state, Scope changedScope) {
		final Evita theEvita = start("removing", state, false);
		final int owner = ownerIn(changedScope);
		final int other = otherIn(changedScope);
		final int mover = moverIn(changedScope);
		final int ownerPartition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner);
		final int otherPartition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(other), other);
		assertTrue(
			hasSortValue(theEvita, changedScope, ownerPartition, "attributeName=" + ATTRIBUTE_ORDER),
			"the row must hold the sort value the removal takes out"
		);
		assertTrue(
			hasSortValue(theEvita, changedScope, otherPartition, "locale=de"), "the row must hold a German value"
		);
		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, true);

		update(
			theEvita, updateProduct(
				owner,
				it -> it.setReference(REFERENCE_CATEGORIES, owner, ref -> ref.removeAttribute(ATTRIBUTE_ORDER))
			)
		);
		assertEquals(ownerPartition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner));
		assertFalse(
			hasSortValue(theEvita, changedScope, ownerPartition, "attributeName=" + ATTRIBUTE_ORDER),
			"the removed reference attribute must leave the row"
		);

		update(
			theEvita, updateProduct(
				other,
				it -> it.removeAttribute(ATTRIBUTE_NAME, Locale.GERMAN)
					.setReference(
						REFERENCE_CATEGORIES, other, ref -> ref.removeAttribute(ATTRIBUTE_LABEL, Locale.GERMAN)
					)
			)
		);
		assertEquals(otherPartition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(other), other));
		assertFalse(
			hasSortValue(theEvita, changedScope, otherPartition, "locale=de"),
			"the removed locale must leave the row"
		);

		update(theEvita, updateProduct(owner, it -> it.removePrice(1, PRICE_LIST, CZK)));
		assertEquals(ownerPartition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner));

		update(theEvita, updateProduct(owner, it -> it.removeReference(REFERENCE_CATEGORIES, owner)));
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner));
		assertNoPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(owner));
		assertNotAdvertised(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, ownerPartition);

		update(theEvita, session -> session.deleteEntity(ENTITY_PRODUCT, other));
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(other));
		assertNotAdvertised(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, otherPartition);

		// the mover leaves the changed scope through its row and enters it again as an index object holding everything
		// the indexing for partitioning requires
		final int moverPartition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(mover), mover);
		update(theEvita, moveTo(otherScope(changedScope), mover));
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(mover));
		assertNotAdvertised(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, moverPartition);
		assertRow(theEvita, ENTITY_PRODUCT, otherScope(changedScope), category(mover), mover);
		update(theEvita, moveTo(changedScope, mover));
		assertNoRow(theEvita, ENTITY_PRODUCT, otherScope(changedScope), category(mover));
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(mover));
		assertPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(mover), mover);

		assertRowsAgreeWithBodies(theEvita, changedScope);
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("statesAndScopes")
	@DisplayName("the index objects a reference indexed for partitioning left are reloaded, read, written and demoted")
	void shouldKeepTheIndexObjectsOfTheFormerIndexingWorking(CatalogState state, Scope changedScope) {
		Evita theEvita = start("formerObjects", state, true);
		final int owner = ownerIn(changedScope);
		final int other = otherIn(changedScope);
		final int mover = moverIn(changedScope);
		final int partition = assertPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner);
		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, false);
		assertEquals(
			ReferenceIndexType.FOR_FILTERING,
			referenceSchema(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES).getReferenceIndexType(changedScope)
		);
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner));

		// the index objects the former indexing left are what the catalog persisted - a reload brings them back as
		// index objects, and no row is made of them
		theEvita = restart(theEvita);
		assertEquals(partition, assertPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner));
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner));
		assertPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(other), other);
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(other));

		// read
		assertEquals(
			List.of(owner),
			theEvita.queryCatalog(
				CATALOG,
				(Function<EvitaSessionContract, List<Integer>>) session -> session.queryList(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(
							referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(owner)), scope(changedScope)
						)
					),
					EntityReference.class
				).stream().map(EntityReference::getPrimaryKey).toList()
			)
		);

		// write
		update(
			theEvita, updateProduct(
				owner, it -> it.setReference(REFERENCE_CATEGORIES, owner, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 100))
			)
		);
		assertEquals(partition, assertPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner));
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner));

		// 1 -> 2
		update(theEvita, updateProduct(other, it -> it.setReference(REFERENCE_CATEGORIES, owner)));
		assertEquals(
			partition, assertPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner, other)
		);
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner));

		// 2 -> 1
		update(theEvita, updateProduct(owner, it -> it.removeReference(REFERENCE_CATEGORIES, owner)));
		assertEquals(partition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), other));
		assertNoPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(owner));
		// the demotion is what the catalog persisted - a reload finds the row and no index object
		theEvita = restart(theEvita);
		assertEquals(partition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), other));

		// 1 -> 0 of an index object left with its only owner - the object is retired with its owner, and the row it
		// never had is not registered
		final int moverPartition = assertPartitionObject(
			theEvita, ENTITY_PRODUCT, changedScope, category(mover), mover
		);
		update(theEvita, updateProduct(mover, it -> it.removeReference(REFERENCE_CATEGORIES, mover)));
		assertNoPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(mover));
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(mover));
		assertEquals(
			partition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), other),
			"vacating a partition without a row must leave the other rows alone"
		);
		assertNotAdvertised(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, moverPartition);

		// a new single-owner partition is a row again
		update(theEvita, updateProduct(mover, it -> it.setReference(REFERENCE_CATEGORIES, SECOND_FREE_CATEGORY)));
		assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(SECOND_FREE_CATEGORY), mover);
		assertNoPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(SECOND_FREE_CATEGORY));

		assertRowsAgreeWithBodies(theEvita, changedScope);
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("statesAndScopes")
	@DisplayName("an index object the former indexing left is retired with its last owner, its key kept within the root")
	void shouldRetireTheIndexObjectOfTheFormerIndexingWithItsLastOwner(CatalogState state, Scope changedScope) {
		final Evita theEvita = start("formerObjectRetired", state, true);
		final int owner = ownerIn(changedScope);
		final int mover = moverIn(changedScope);
		final int ownerPartition = assertPartitionObject(
			theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner
		);
		final int moverPartition = assertPartitionObject(
			theEvita, ENTITY_PRODUCT, changedScope, category(mover), mover
		);
		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, false);

		// the only owner leaves and enters again in one mutation - the object is retired, and the owner enters the
		// partition as its row under the primary key the object had
		update(
			theEvita,
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, owner, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingCategoryReferenceKey(session, owner, owner)),
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_CATEGORIES, owner, -1))
				)
			)
		);
		assertEquals(
			ownerPartition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner),
			"the owner entering the partition again within the root mutation keeps its primary key"
		);

		// the only owner leaves - the object is retired with it, nothing represents the partition any more
		update(theEvita, updateProduct(mover, it -> it.removeReference(REFERENCE_CATEGORIES, mover)));
		assertNoPartitionObject(theEvita, ENTITY_PRODUCT, changedScope, category(mover));
		assertNoRow(theEvita, ENTITY_PRODUCT, changedScope, category(mover));
		assertNotAdvertised(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, moverPartition);

		// an owner entering it in a later root mutation brings in a row only
		update(theEvita, updateProduct(mover, it -> it.setReference(REFERENCE_CATEGORIES, mover)));
		assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(mover), mover);

		assertRowsAgreeWithBodies(theEvita, changedScope);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a reflected reference inheriting its indexing follows its source into partitioning")
	void shouldRefuseAddingToTheRowsOfAReflectedReferenceInheritingItsIndexing(CatalogState state) {
		final Evita theEvita = start("reflected", state, false);
		final int product = ownerIn(Scope.LIVE);
		final int categoryPartition = assertRow(theEvita, ENTITY_CATEGORY, Scope.LIVE, product(product), product);
		final int productPartition = assertRow(theEvita, ENTITY_PRODUCT, Scope.LIVE, category(product), product);
		assertEquals(
			ReferenceIndexType.FOR_FILTERING,
			referenceSchema(theEvita, ENTITY_CATEGORY, REFERENCE_PRODUCTS).getReferenceIndexType(Scope.LIVE)
		);
		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, true);
		assertEquals(
			ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
			referenceSchema(theEvita, ENTITY_CATEGORY, REFERENCE_PRODUCTS).getReferenceIndexType(Scope.LIVE),
			"the reflected reference must inherit the indexing of its source"
		);

		assertRefused(
			theEvita, ENTITY_CATEGORY, REFERENCE_PRODUCTS, Scope.LIVE, "the category's attribute is upserted",
			session -> session.getEntity(ENTITY_CATEGORY, product, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setAttribute(ATTRIBUTE_TITLE, "Retitled")
				.upsertVia(session)
		);
		assertRefused(
			theEvita, ENTITY_CATEGORY, REFERENCE_PRODUCTS, Scope.LIVE,
			"the category lists another product, entering the row the product's own category owns",
			session -> session.getEntity(ENTITY_CATEGORY, product, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(REFERENCE_PRODUCTS, otherIn(Scope.LIVE))
				.upsertVia(session)
		);

		update(
			theEvita,
			session -> session.getEntity(ENTITY_CATEGORY, product, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.removeReference(REFERENCE_PRODUCTS, product)
				.upsertVia(session)
		);
		assertNoRow(theEvita, ENTITY_CATEGORY, Scope.LIVE, product(product));
		assertNotAdvertised(theEvita, ENTITY_CATEGORY, REFERENCE_PRODUCTS, Scope.LIVE, categoryPartition);
		assertNoRow(theEvita, ENTITY_PRODUCT, Scope.LIVE, category(product));
		assertNotAdvertised(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, Scope.LIVE, productPartition);
		assertRowsAgreeWithBodies(theEvita, Scope.LIVE);
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("statesAndScopes")
	@DisplayName("every read of rows of a reference now indexed for partitioning answers as before or fails loudly")
	void shouldNeverReadARowOfTheFormerIndexingSilentlyWrong(CatalogState state, Scope changedScope) {
		final Evita theEvita = start("staleRowReads", state, false);
		final int owner = ownerIn(changedScope);
		final int other = otherIn(changedScope);
		update(
			theEvita,
			session -> {
				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					session.getEntity(ENTITY_CATEGORY, pk, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_TITLE, "Category " + pk)
						.upsertVia(session);
				}
				rankProducts(session);
				// the reads of the categories, their trees and the reference content read the categories of both
				// products in the scope
				placeCategoryIn(session, owner, changedScope);
				placeCategoryIn(session, other, changedScope);
			}
		);
		final int ownerPartition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner);
		final int otherPartition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(other), other);
		final SortedMap<String, String> before = readsOfPartitionContent(theEvita, changedScope, owner, other);
		final List<String> failedBefore = before.entrySet().stream()
			.filter(it -> it.getValue().startsWith(READ_FAILED))
			.map(it -> it.getKey() + " = " + it.getValue())
			.toList();
		assertTrue(failedBefore.isEmpty(), "every read must answer before the change: " + failedBefore);
		// a read answering nothing before the change compares nothing with nothing after it
		final List<String> emptyBefore = before.entrySet().stream()
			.filter(it -> EMPTY_ANSWER.equals(it.getValue()))
			.map(Map.Entry::getKey)
			.toList();
		assertTrue(emptyBefore.isEmpty(), "every read must find something before the change: " + emptyBefore);
		assertTrue(
			before.get("product/entityAttribute/filter").contains(String.valueOf(owner)),
			"the reads must find the owner before the change: " + before
		);

		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, true);
		assertEquals(ownerPartition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner));
		assertEquals(otherPartition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(other), other));

		// nothing the reads see changed but the index type, so every read answers as before - unless it needs content
		// only an index object of the partitioning holds, and then it refuses the rows it cannot read
		final SortedMap<String, String> after = readsOfPartitionContent(theEvita, changedScope, owner, other);
		final List<String> silentlyWrong = new ArrayList<>(4);
		for (final Map.Entry<String, String> read : before.entrySet()) {
			final String afterValue = after.get(read.getKey());
			if (!read.getValue().equals(afterValue) && !isRefusedAsStale(read.getKey(), afterValue, changedScope)) {
				silentlyWrong.add(
					read.getKey() + "\n   before: " + read.getValue() + "\n   after:  " + afterValue
				);
			}
		}
		if (!silentlyWrong.isEmpty()) {
			fail(
				silentlyWrong.size() + " read(s) of rows of a reference now indexed for partitioning answered " +
					"differently without refusing:\n" + String.join("\n", silentlyWrong)
			);
		}
		// a read planned over the partitions of one target needs the content an index object of the partitioning holds
		assertTrue(
			isRefusedAsStale(
				"product/entityAttribute/filter/oneTarget", after.get("product/entityAttribute/filter/oneTarget"),
				changedScope
			),
			"a read needing the entity attributes of a row must have been refused: " + after
		);
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("statesAndScopes")
	@DisplayName("a hierarchy query over a row of the former indexing answers as before")
	void shouldAnswerAHierarchyQueryOverARowOfTheFormerIndexingAsBefore(CatalogState state, Scope changedScope) {
		final int owner = ownerIn(changedScope);
		final Evita theEvita = start(
			"hierarchyOverRow", state, false,
			session -> {
				rankProducts(session);
				placeCategoryIn(session, owner, changedScope);
			}
		);
		final int partition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner);
		assertHierarchyQueryAnswersAsTheBodies(
			theEvita, changedScope, REFERENCE_CATEGORIES, owner, "before the change"
		);

		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_CATEGORIES, changedScope, true);
		assertEquals(partition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, category(owner), owner));
		assertHierarchyQueryAnswersAsTheBodies(theEvita, changedScope, REFERENCE_CATEGORIES, owner, "after the change");
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("statesAndScopes")
	@DisplayName("a hierarchy query over a duplicate-allowing row of the former indexing answers as before")
	void shouldAnswerAHierarchyQueryOverARowOfTheFormerIndexingAllowingDuplicatesAsBefore(
		CatalogState state,
		Scope changedScope
	) {
		final int owner = ownerIn(changedScope);
		final Evita theEvita = start(
			"hierarchyOverDuplicateRow", state, false,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PLACEMENTS, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs
							.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
							.withAttribute(
								ATTRIBUTE_SLOT, String.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).representative()
							)
					)
					.updateVia(session);
				updateProduct(
					owner,
					it -> it.setOrUpdateReference(
						REFERENCE_PLACEMENTS, owner, Functions.alwaysFalse(),
						ref -> ref.setAttribute(ATTRIBUTE_SLOT, "A")
					)
				).accept(session);
				rankProducts(session);
				placeCategoryIn(session, owner, changedScope);
			}
		);
		final RepresentativeReferenceKey placement = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_PLACEMENTS, owner), new Serializable[]{"A"}
		);
		final int partition = assertRow(theEvita, ENTITY_PRODUCT, changedScope, placement, owner);
		assertHierarchyQueryAnswersAsTheBodies(
			theEvita, changedScope, REFERENCE_PLACEMENTS, owner, "before the change"
		);

		changeIndexType(theEvita, ENTITY_PRODUCT, REFERENCE_PLACEMENTS, changedScope, true);
		assertEquals(partition, assertRow(theEvita, ENTITY_PRODUCT, changedScope, placement, owner));
		assertHierarchyQueryAnswersAsTheBodies(theEvita, changedScope, REFERENCE_PLACEMENTS, owner, "after the change");
	}

	/**
	 * Defines the schema and the data - see the constants. With `partitioning` the source reference is indexed for
	 * filtering and partitioning in both scopes, otherwise for filtering only.
	 *
	 * @param session      the session to write in
	 * @param partitioning TRUE to index the source reference for partitioning
	 */
	private static void setUp(@Nonnull EvitaSessionContract session, boolean partitioning) {
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withHierarchyIndexedInScope(Scope.LIVE, Scope.ARCHIVED)
			.withAttribute(
				ATTRIBUTE_TITLE, String.class,
				whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
					.sortableInScope(Scope.LIVE, Scope.ARCHIVED)
					.nullable()
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withPriceIndexedInScope(Scope.LIVE, Scope.ARCHIVED)
			.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
			.withAttribute(
				ATTRIBUTE_NAME, String.class,
				whichIs -> whichIs.localized()
					.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
					.sortableInScope(Scope.LIVE, Scope.ARCHIVED)
					.nullable()
			)
			.withAttribute(ATTRIBUTE_VISIBLE, Boolean.class, AttributeSchemaEditor::nullable)
			.withAttribute(ATTRIBUTE_PINNED, Boolean.class, AttributeSchemaEditor::nullable)
			.withAssociatedData(
				ASSOCIATED_DATA_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable()
			)
			.withReferenceToEntity(
				REFERENCE_TAGS, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.facetedInScope(Scope.LIVE, Scope.ARCHIVED)
					.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(FACETED_WHEN))
					.facetedPartiallyInScope(Scope.ARCHIVED, ExpressionFactory.parse(FACETED_WHEN))
			)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (partitioning) {
						whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED);
					} else {
						whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED);
					}
					whichIs
						.facetedInScope(Scope.LIVE, Scope.ARCHIVED)
						.withGroupType(GROUP_TYPE)
						.withAttribute(
							ATTRIBUTE_ORDER, Integer.class,
							thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
								.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
						)
						.withAttribute(
							ATTRIBUTE_LABEL, String.class,
							thatIs -> thatIs.localized().sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
						);
				}
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> whichIs.withCardinality(Cardinality.ZERO_OR_MORE).withAttributesInherited(ATTRIBUTE_ORDER)
			)
			.updateVia(session);
		for (int pk = 1; pk <= CATEGORY_COUNT; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
		}
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			final boolean german = pk % 2 == 0;
			final int target = pk;
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, pk)
				.setAttribute(ATTRIBUTE_CODE, "P" + pk)
				.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Product " + pk)
				.setPrice(1, PRICE_LIST, CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setReference(
					REFERENCE_CATEGORIES, target,
					whichIs -> {
						whichIs.setAttribute(ATTRIBUTE_ORDER, target)
							.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Label " + target);
						if (german) {
							whichIs.setAttribute(ATTRIBUTE_LABEL, Locale.GERMAN, "Etikett " + target);
						}
					}
				);
			if (german) {
				product.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt " + pk);
			}
			product.upsertVia(session);
		}
		for (final int archived : new int[]{3, 4, 6}) {
			session.archiveEntity(ENTITY_PRODUCT, archived);
		}
	}

	/**
	 * Starts an engine hosting a catalog that represents its single-owner partitions virtually and writes them so,
	 * prepares the fixture in it and brings it to the given state.
	 *
	 * @param label        a label naming the engine's directories
	 * @param state        the catalog state the engine should end in
	 * @param partitioning TRUE to index the source reference for partitioning
	 * @return the engine
	 */
	@Nonnull
	private Evita start(@Nonnull String label, @Nonnull CatalogState state, boolean partitioning) {
		return start(label, state, partitioning, session -> {});
	}

	/**
	 * Starts an engine as {@link #start(String, CatalogState, boolean)} does, extending the fixture with a write applied
	 * right after it, while the catalog is still warming up.
	 *
	 * @param label        a label naming the engine's directories
	 * @param state        the catalog state the engine should end in
	 * @param partitioning TRUE to index the source reference for partitioning
	 * @param extension    the write extending the fixture
	 * @return the engine
	 */
	@Nonnull
	private Evita start(
		@Nonnull String label,
		@Nonnull CatalogState state,
		boolean partitioning,
		@Nonnull Consumer<EvitaSessionContract> extension
	) {
		final TestPaths thePaths = createTestPaths(getClass().getSimpleName() + "_" + label);
		this.paths = thePaths;
		final Evita theEvita = new Evita(configuration(thePaths));
		this.evita = theEvita;
		theEvita.defineCatalog(CATALOG).updateViaNewSession(theEvita);
		update(theEvita, session -> setUp(session, partitioning));
		update(theEvita, extension);
		if (state == CatalogState.ALIVE) {
			theEvita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		}
		assertEquals(state, catalogOf(theEvita).getCatalogState());
		return theEvita;
	}

	/**
	 * Runs every read of the scope whose answer could need content that only an index object of a partition of a
	 * reference indexed for partitioning holds - entity attributes and prices filtered or sorted within the partitions
	 * of a target, the facet summary of a target, a reference attribute, the `pickFirst` and the block-by-block
	 * orderings, the filtered reference content, and the same through the reflected reference of the category - and
	 * describes the answer of each, or the failure it raised, together with the query lines of the whole catalog (see
	 * {@link CatalogDescriber#describeQueriesOf}). A read that finds nothing answers {@link #EMPTY_ANSWER}.
	 *
	 * The categories of both products must be in the scope, so that the trees, the reference content and the reads of
	 * the categories find them.
	 *
	 * @param theEvita the engine
	 * @param scope    the scope to read
	 * @param owner    a product of the scope
	 * @param other    another product of the scope
	 * @return the answers by the name of the read
	 */
	@Nonnull
	private static SortedMap<String, String> readsOfPartitionContent(
		@Nonnull Evita theEvita,
		@Nonnull Scope scope,
		int owner,
		int other
	) {
		final SortedMap<String, String> answers = new TreeMap<>();
		final FilterConstraint ofBoth = referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(owner, other));
		readOwners(
			answers, theEvita, "product/entityAttribute/filter", ENTITY_PRODUCT,
			filterBy(
				scope(scope), entityLocaleEquals(Locale.ENGLISH), ofBoth,
				attributeEquals(ATTRIBUTE_NAME, "Product " + owner)
			),
			null
		);
		readOwners(
			answers, theEvita, "product/entityAttribute/order", ENTITY_PRODUCT,
			filterBy(scope(scope), entityLocaleEquals(Locale.ENGLISH), ofBoth),
			orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
		);
		final FilterConstraint ofOwner = referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(owner));
		readOwners(
			answers, theEvita, "product/entityAttribute/filter/oneTarget", ENTITY_PRODUCT,
			filterBy(
				scope(scope), entityLocaleEquals(Locale.ENGLISH), ofOwner,
				attributeEquals(ATTRIBUTE_NAME, "Product " + owner)
			),
			null
		);
		readOwners(
			answers, theEvita, "product/entityAttribute/order/oneTarget", ENTITY_PRODUCT,
			filterBy(scope(scope), entityLocaleEquals(Locale.ENGLISH), ofOwner),
			orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
		);
		readOwners(
			answers, theEvita, "product/price/filter/oneTarget", ENTITY_PRODUCT,
			filterBy(scope(scope), ofOwner, priceInPriceLists(PRICE_LIST), priceInCurrency(CZK)),
			null
		);
		readOwners(
			answers, theEvita, "product/hierarchyWithin", ENTITY_PRODUCT,
			filterBy(scope(scope), hierarchyWithin(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(owner))),
			null
		);
		// a non-localized attribute - the locale of a query also restricts the parent filter of its hierarchy
		// constraint, and the categories hold no localized data, so a query with a locale selects no node
		readOwners(
			answers, theEvita, "product/hierarchyWithin/entityAttribute", ENTITY_PRODUCT,
			filterBy(
				scope(scope),
				hierarchyWithin(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(owner)),
				attributeEquals(ATTRIBUTE_RANK, owner)
			),
			null
		);
		readOwners(
			answers, theEvita, "product/hierarchyWithinRoot", ENTITY_PRODUCT,
			filterBy(scope(scope), hierarchyWithinRoot(REFERENCE_CATEGORIES)),
			null
		);
		read(
			answers, theEvita, "product/hierarchyStatistics",
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(scope)),
						require(
							hierarchyOfReference(
								REFERENCE_CATEGORIES,
								fromRoot(
									"tree",
									statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);
				return response.getExtraResult(Hierarchy.class).getReferenceHierarchy(REFERENCE_CATEGORIES, "tree")
					.isEmpty() ? EMPTY_ANSWER : response.getExtraResults().toString();
			}
		);
		readOwners(
			answers, theEvita, "product/price/filter", ENTITY_PRODUCT,
			filterBy(scope(scope), ofBoth, priceInPriceLists(PRICE_LIST), priceInCurrency(CZK)),
			null
		);
		readOwners(
			answers, theEvita, "product/price/order", ENTITY_PRODUCT,
			filterBy(scope(scope), ofBoth, priceInPriceLists(PRICE_LIST), priceInCurrency(CZK)),
			orderBy(priceNatural(OrderDirection.DESC))
		);
		read(
			answers, theEvita, "product/facetSummary",
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(scope), referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(owner))),
						require(facetSummary(FacetStatisticsDepth.COUNTS))
					),
					EntityReference.class
				);
				return response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty() ?
					EMPTY_ANSWER : response.getExtraResults().toString();
			}
		);
		readOwners(
			answers, theEvita, "product/referenceAttribute", ENTITY_PRODUCT,
			filterBy(scope(scope), referenceHaving(REFERENCE_CATEGORIES, attributeEquals(ATTRIBUTE_ORDER, owner))),
			null
		);
		readOwners(
			answers, theEvita, "product/pickFirst", ENTITY_PRODUCT, filterBy(scope(scope)),
			orderBy(
				referenceProperty(
					REFERENCE_CATEGORIES,
					pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.DESC)),
					attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)
				)
			)
		);
		readOwners(
			answers, theEvita, "product/traverse", ENTITY_PRODUCT, filterBy(scope(scope)),
			orderBy(
				referenceProperty(
					REFERENCE_CATEGORIES,
					traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
					attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)
				)
			)
		);
		read(
			answers, theEvita, "product/referenceContent",
			session -> {
				final List<SealedEntity> products = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(scope)),
						require(
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_CATEGORIES, filterBy(entityPrimaryKeyInSet(owner, other))
								)
							)
						)
					),
					SealedEntity.class
				).getRecordData();
				return products.stream().allMatch(it -> it.getReferences(REFERENCE_CATEGORIES).isEmpty()) ?
					EMPTY_ANSWER : products.toString();
			}
		);
		final FilterConstraint ofBothProducts = referenceHaving(
			REFERENCE_PRODUCTS, entityPrimaryKeyInSet(owner, other)
		);
		readOwners(
			answers, theEvita, "category/entityAttribute/filter", ENTITY_CATEGORY,
			filterBy(scope(scope), ofBothProducts, attributeEquals(ATTRIBUTE_TITLE, "Category " + owner)),
			null
		);
		readOwners(
			answers, theEvita, "category/entityAttribute/order", ENTITY_CATEGORY,
			filterBy(scope(scope), ofBothProducts), orderBy(attributeNatural(ATTRIBUTE_TITLE, OrderDirection.DESC))
		);
		read(answers, "catalog/queryLines", () -> CatalogDescriber.describeQueriesOf(theEvita, CATALOG).toString());
		return answers;
	}

	/**
	 * Runs a query of the primary keys of the entities of a collection and records them, or the failure the query
	 * raised.
	 *
	 * @param answers    the answers to record into
	 * @param theEvita   the engine
	 * @param name       the name of the read
	 * @param entityType the collection
	 * @param filterBy   the filter
	 * @param orderBy    the ordering, `null` for none
	 */
	private static void readOwners(
		@Nonnull SortedMap<String, String> answers,
		@Nonnull Evita theEvita,
		@Nonnull String name,
		@Nonnull String entityType,
		@Nonnull FilterBy filterBy,
		@Nullable OrderBy orderBy
	) {
		read(
			answers, theEvita, name,
			session -> session.query(
				orderBy == null ?
					query(collection(entityType), filterBy) : query(collection(entityType), filterBy, orderBy),
				EntityReference.class
			).getRecordData().stream().map(EntityReference::getPrimaryKey).toList().toString()
		);
	}

	/**
	 * Asserts that a query of the products of the scope within the subtree of a category through the reference,
	 * combined with the rank of the owner, answers what the entity bodies answer - and what the same query answers with
	 * the hierarchy constraint swapped for the primary keys of the products whose bodies reference the subtree.
	 *
	 * The swapped query has no hierarchy constraint, so no plan within the partitions of the nodes is offered for it
	 * and the global index answers it alone; a hierarchy query answering differently from it answers from a plan within
	 * the partitions of the nodes. That plan is offered only within the cardinality limit, so the telemetry of the
	 * hierarchy query must show the option of the nodes registered and within the limit - otherwise the limit alone
	 * would keep it out of a separate plan and the query would prove nothing about the rows.
	 *
	 * @param theEvita      the engine
	 * @param scope         the scope to query
	 * @param referenceName the reference to the categories the hierarchy constraint names
	 * @param owner         the product whose category is the node of the hierarchy constraint and whose rank is
	 *                      filtered
	 * @param when          names the moment in failure messages
	 */
	private static void assertHierarchyQueryAnswersAsTheBodies(
		@Nonnull Evita theEvita,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int owner,
		@Nonnull String when
	) {
		final List<Integer> referencing = productsReferencingSubtreeByBodies(theEvita, scope, referenceName, owner);
		final List<Integer> expected = theEvita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<Integer>>) session -> referencing.stream()
				.filter(
					pk -> Integer.valueOf(owner).equals(
						session.getEntity(ENTITY_PRODUCT, pk, new Scope[]{scope}, entityFetchAllContent())
							.orElseThrow()
							.getAttribute(ATTRIBUTE_RANK)
					)
				)
				.toList()
		);
		assertEquals(List.of(owner), expected, when + ": the bodies must select the owner alone");
		assertEquals(
			expected,
			theEvita.queryCatalog(
				CATALOG,
				(Function<EvitaSessionContract, List<Integer>>) session -> session.queryList(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(
							scope(scope),
							entityPrimaryKeyInSet(referencing.stream().mapToInt(Integer::intValue).toArray()),
							attributeEquals(ATTRIBUTE_RANK, owner)
						)
					),
					EntityReference.class
				).stream().map(EntityReference::getPrimaryKey).toList()
			),
			when + ": the query over the primary keys the hierarchy constraint selects"
		);

		final EvitaResponse<EntityReference> response = theEvita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, EvitaResponse<EntityReference>>) session -> session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(
						scope(scope),
						hierarchyWithin(referenceName, entityPrimaryKeyInSet(owner)),
						attributeEquals(ATTRIBUTE_RANK, owner)
					),
					require(queryTelemetry())
				),
				EntityReference.class
			)
		);
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, when + ": the telemetry of the hierarchy query must be present");
		final List<String> options = new ArrayList<>(1);
		collectReducedIndexOptions(telemetry, options);
		assertEquals(1, options.size(), when + ": the option of the nodes must be registered once: " + options);
		assertFalse(
			options.getFirst().contains(EligibilityObstacle.HIGH_CARDINALITY.name()) ||
				options.getFirst().contains(EligibilityObstacle.PARTIAL_SCOPE_COVERAGE.name()),
			when + ": the option of the nodes must be within the cardinality limit and cover the scope: " + options
		);
		assertEquals(
			expected,
			response.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
			when + ": the hierarchy query, planned as " + options
		);
	}

	/**
	 * Walks a telemetry tree collecting the description of every planned alternative within the partitions of reduced
	 * indexes - the `REFERENCED_ENTITY` options index selection registers for a hierarchy or reference constraint. The
	 * step summarizing the selection repeats the description of the winner, so only the alternatives are read.
	 *
	 * @param node the node to walk
	 * @param sink the collected descriptions
	 */
	private static void collectReducedIndexOptions(@Nonnull QueryTelemetry node, @Nonnull List<String> sink) {
		if (node.getOperation() == QueryPhase.PLANNING_FILTER_ALTERNATIVE) {
			for (final String argument : node.getArguments()) {
				if (argument.contains(EntityIndexType.REFERENCED_ENTITY.name() + " composed of")) {
					sink.add(argument);
				}
			}
		}
		for (final QueryTelemetry step : node.getSteps()) {
			collectReducedIndexOptions(step, sink);
		}
	}

	/**
	 * Returns the products of the scope whose bodies reference, through the reference, the category or a category of
	 * its subtree, ascending. The subtree is derived from the parents the category bodies of the scope name.
	 *
	 * @param theEvita      the engine
	 * @param scope         the scope
	 * @param referenceName the reference to the categories
	 * @param category      the root of the subtree
	 * @return the primary keys of the products
	 */
	@Nonnull
	private static List<Integer> productsReferencingSubtreeByBodies(
		@Nonnull Evita theEvita,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int category
	) {
		return theEvita.queryCatalog(
			CATALOG,
			session -> {
				final Map<Integer, Integer> parents = new TreeMap<>();
				for (int pk = 1; pk <= CATEGORY_COUNT; pk++) {
					session.getEntity(ENTITY_CATEGORY, pk, new Scope[]{scope}, entityFetchAllContent())
						.ifPresent(
							body -> parents.put(
								body.getPrimaryKeyOrThrowException(),
								body.getParentEntity().map(EntityClassifier::getPrimaryKeyOrThrowException).orElse(null)
							)
						);
				}
				assertTrue(parents.containsKey(category), "the tree of " + scope + " must hold category " + category);
				final Set<Integer> subtree = createHashSet(CATEGORY_COUNT);
				subtree.add(category);
				boolean grown = true;
				while (grown) {
					grown = false;
					for (final Map.Entry<Integer, Integer> node : parents.entrySet()) {
						if (node.getValue() != null && subtree.contains(node.getValue())) {
							grown |= subtree.add(node.getKey());
						}
					}
				}
				final List<Integer> result = new ArrayList<>(PRODUCT_COUNT);
				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					session.getEntity(ENTITY_PRODUCT, pk, new Scope[]{scope}, entityFetchAllContent())
						.filter(
							body -> body.getReferences(referenceName)
								.stream()
								.anyMatch(it -> subtree.contains(it.getReferencedPrimaryKey()))
						)
						.ifPresent(body -> result.add(body.getPrimaryKeyOrThrowException()));
				}
				return result;
			}
		);
	}

	/**
	 * Defines {@link #ATTRIBUTE_RANK} on the product and ranks every product of the fixture by its primary key.
	 *
	 * @param session the session to write in
	 */
	private static void rankProducts(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTRIBUTE_RANK, Integer.class,
				whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
			)
			.updateVia(session);
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			final int rank = pk;
			updateProduct(pk, it -> it.setAttribute(ATTRIBUTE_RANK, rank)).accept(session);
		}
	}

	/**
	 * Archives the category when the scope is the archived one, so that the tree of the scope holds it - the categories
	 * of the fixture all live.
	 *
	 * @param session  the session to write in
	 * @param category the category
	 * @param scope    the scope whose tree must hold the category
	 */
	private static void placeCategoryIn(@Nonnull EvitaSessionContract session, int category, @Nonnull Scope scope) {
		if (scope == Scope.ARCHIVED) {
			session.archiveEntity(ENTITY_CATEGORY, category);
		}
	}

	/**
	 * Runs a read in a read-only session and records its answer, or the failure it raised - a refusal to read rows of
	 * a reference now indexed for partitioning as {@link #READ_REFUSED} with the entity type, the reference and the
	 * scope the refusal names, any other failure as {@link #READ_FAILED} with its type and message.
	 *
	 * @param answers  the answers to record into
	 * @param theEvita the engine
	 * @param name     the name of the read
	 * @param read     the read, describing its answer
	 */
	private static void read(
		@Nonnull SortedMap<String, String> answers,
		@Nonnull Evita theEvita,
		@Nonnull String name,
		@Nonnull Function<EvitaSessionContract, String> read
	) {
		read(answers, name, () -> theEvita.queryCatalog(CATALOG, read));
	}

	/**
	 * Runs a read and records its answer, or the failure it raised - see
	 * {@link #read(SortedMap, Evita, String, Function)}.
	 *
	 * @param answers the answers to record into
	 * @param name    the name of the read
	 * @param read    the read, describing its answer
	 */
	private static void read(
		@Nonnull SortedMap<String, String> answers,
		@Nonnull String name,
		@Nonnull Supplier<String> read
	) {
		String answer;
		try {
			answer = read.get();
		} catch (RuntimeException ex) {
			final ReferencePartitionReindexRequiredException refusal =
				ExceptionUtils.findInCauseChain(ex, ReferencePartitionReindexRequiredException.class);
			answer = refusal == null ?
				READ_FAILED + ex.getClass().getSimpleName() + ": " + ex.getMessage() :
				READ_REFUSED + refusal.getEntityType() + "/" + refusal.getReferenceName() + "/" + refusal.getScope();
		}
		answers.put(name, answer);
	}

	/**
	 * Returns TRUE when a read was refused for the rows of the reference whose index type changed - the product's
	 * category reference, or the category's reflection of it, in the changed scope.
	 *
	 * @param name   the name of the read
	 * @param answer the answer recorded for it
	 * @param scope  the scope whose index type changed
	 * @return TRUE for a refusal naming the reference the read reads
	 */
	private static boolean isRefusedAsStale(@Nonnull String name, @Nullable String answer, @Nonnull Scope scope) {
		if (answer == null || !answer.startsWith(READ_REFUSED)) {
			return false;
		}
		final String refused = answer.substring(READ_REFUSED.length());
		return refused.equals(ENTITY_PRODUCT + "/" + REFERENCE_CATEGORIES + "/" + scope) ||
			refused.equals(ENTITY_CATEGORY + "/" + REFERENCE_PRODUCTS + "/" + scope) ||
			// the query lines read every reference of the catalog
			(name.startsWith("catalog/") && refused.endsWith("/" + scope));
	}

	/**
	 * Builds the configuration of an engine over the given directories.
	 *
	 * @param thePaths the directories
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths thePaths) {
		return newTestEvitaConfigurationBuilder(thePaths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Closes the engine and starts a fresh one over the same directories, told nothing about the catalog, and waits
	 * until it has loaded the catalog.
	 *
	 * @param theEvita the engine to close
	 * @return the restarted engine
	 */
	@Nonnull
	private Evita restart(@Nonnull Evita theEvita) {
		theEvita.close();
		final Evita restarted = new Evita(configuration(Objects.requireNonNull(this.paths)));
		this.evita = restarted;
		CatalogReloadTestSupport.awaitCatalogLoaded(restarted, CATALOG);
		return restarted;
	}

	/**
	 * Changes the index type of a reference in one scope, leaving the other scope indexed as the fixture indexes it -
	 * for filtering and partitioning when the change is to filtering only, and the other way round.
	 *
	 * @param theEvita      the engine
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @param scope         the scope whose indexing changes
	 * @param partitioning  TRUE to index the reference for filtering and partitioning, FALSE for filtering only
	 */
	private static void changeIndexType(
		@Nonnull Evita theEvita,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		boolean partitioning
	) {
		final ReferenceIndexType changed = partitioning ?
			ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING : ReferenceIndexType.FOR_FILTERING;
		final ReferenceIndexType kept = partitioning ?
			ReferenceIndexType.FOR_FILTERING : ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING;
		update(
			theEvita,
			session -> session.updateEntitySchema(
				new ModifyEntitySchemaMutation(
					entityType,
					new SetReferenceSchemaIndexedMutation(
						referenceName,
						new ScopedReferenceIndexType[]{
							new ScopedReferenceIndexType(scope, changed),
							new ScopedReferenceIndexType(otherScope(scope), kept)
						}
					)
				)
			)
		);
	}

	/**
	 * Applies a write that must be refused with {@link ReferencePartitionReindexRequiredException} naming the entity
	 * type, the reference and the scope, and asserts that the catalog is exactly as it was - inside the session right
	 * after the refusal and once the session has closed.
	 *
	 * @param theEvita      the engine
	 * @param entityType    the entity type the error must name
	 * @param referenceName the reference the error must name
	 * @param scope         the scope the error must name
	 * @param label         names the write in failure messages
	 * @param write         the write
	 */
	private static void assertRefused(
		@Nonnull Evita theEvita,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull String label,
		@Nonnull Consumer<EvitaSessionContract> write
	) {
		final SortedMap<String, String> committedBefore = theEvita.queryCatalog(
			CATALOG, (Function<EvitaSessionContract, SortedMap<String, String>>) session -> describe(theEvita, session)
		);
		update(
			theEvita,
			session -> {
				final SortedMap<String, String> before = describe(theEvita, session);
				final ReferencePartitionReindexRequiredException error = assertThrows(
					ReferencePartitionReindexRequiredException.class, () -> write.accept(session), label
				);
				assertEquals(entityType, error.getEntityType(), label);
				assertEquals(referenceName, error.getReferenceName(), label);
				assertEquals(scope, error.getScope(), label);
				assertEquals(before, describe(theEvita, session), label + ": the refused write changed the catalog");
			}
		);
		assertEquals(
			committedBefore,
			theEvita.queryCatalog(
				CATALOG,
				(Function<EvitaSessionContract, SortedMap<String, String>>) session -> describe(theEvita, session)
			),
			label + ": the refused write changed the committed catalog"
		);
	}

	/**
	 * Describes the catalog of the engine as the session sees it: the indexes of both collections, the registry
	 * included (see {@link CatalogDescriber#describeIndexesOf}), and the version of every entity.
	 *
	 * @param theEvita the engine
	 * @param session  the session
	 * @return the lines, sorted by path
	 */
	@Nonnull
	private static SortedMap<String, String> describe(@Nonnull Evita theEvita, @Nonnull EvitaSessionContract session) {
		final SortedMap<String, String> lines = new TreeMap<>();
		for (final String entityType : List.of(ENTITY_PRODUCT, ENTITY_CATEGORY)) {
			final EntityCollection collection = (EntityCollection) catalogOf(theEvita)
				.getCollectionForEntity(entityType)
				.orElseThrow();
			lines.putAll(CatalogDescriber.describeIndexesOf(collection));
			final int count = ENTITY_PRODUCT.equals(entityType) ? PRODUCT_COUNT : CATEGORY_COUNT;
			for (int pk = 1; pk <= count; pk++) {
				final SealedEntity entity = session.getEntity(entityType, pk, Scope.values(), entityFetchAllContent())
					.orElse(null);
				lines.put(
					"entity/" + entityType + "/" + pk,
					entity == null ? "absent" : "version " + entity.version() + ", scope " + entity.getScope()
				);
			}
		}
		return lines;
	}

	/**
	 * Applies a write in a session of its own.
	 *
	 * @param theEvita the engine
	 * @param write    the write
	 */
	private static void update(@Nonnull Evita theEvita, @Nonnull Consumer<EvitaSessionContract> write) {
		theEvita.updateCatalog(CATALOG, write);
	}

	/**
	 * Creates a write updating one product in whichever scope it is.
	 *
	 * @param primaryKey the product primary key
	 * @param change     the change to make
	 * @return the write
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> updateProduct(
		int primaryKey,
		@Nonnull Consumer<EntityBuilder> change
	) {
		return session -> {
			final EntityBuilder builder = session
				.getEntity(ENTITY_PRODUCT, primaryKey, Scope.values(), entityFetchAllContent())
				.orElseThrow()
				.openForWrite();
			change.accept(builder);
			builder.upsertVia(session);
		};
	}

	/**
	 * Creates a write changing the title of a category.
	 *
	 * @param primaryKey the category primary key
	 * @param title      the new title
	 * @return the write
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> retitleCategory(int primaryKey, @Nonnull String title) {
		return session -> session.getEntity(ENTITY_CATEGORY, primaryKey, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTRIBUTE_TITLE, title)
			.upsertVia(session);
	}

	/**
	 * Creates a write moving a product to a scope.
	 *
	 * @param scope      the scope to move to
	 * @param primaryKey the product primary key
	 * @return the write
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> moveTo(@Nonnull Scope scope, int primaryKey) {
		return session -> {
			if (scope == Scope.ARCHIVED) {
				session.archiveEntity(ENTITY_PRODUCT, primaryKey);
			} else {
				session.restoreEntity(ENTITY_PRODUCT, primaryKey);
			}
		};
	}

	/**
	 * Asserts that the registry of the reference of the key holds the row of the key's partition naming the owner and
	 * that the partition has no index object.
	 *
	 * @param theEvita   the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @param owner      the expected owner
	 * @return the primary key of the partition
	 */
	private static int assertRow(
		@Nonnull Evita theEvita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key,
		int owner
	) {
		final int partition = SingleOwnerPartitionFixture.registeredPartitionOf(
			theEvita, entityType, scope, key
		);
		assertTrue(partition > 0, "the registry must hold the row of " + key + " in " + scope);
		final EntityCollection collection = SingleOwnerPartitionFixture.findCollectionOf(theEvita, entityType);
		assertNotNull(collection);
		final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, key.referenceName())
		);
		assertNotNull(typeIndex);
		final long ownerAndTarget = ((ReferencedTypeEntityIndex) typeIndex)
			.getVirtualReferencePartitionIndex()
			.getPartitionOwner(partition)
			.orElseThrow();
		assertEquals(owner, NumberUtils.unpackHigh(ownerAndTarget), "the owner of the row of " + key);
		assertNoPartitionObject(theEvita, entityType, scope, key);
		assertNull(collection.getIndexByPrimaryKeyIfExists(partition), "the row of " + key + " has no index object");
		return partition;
	}

	/**
	 * Asserts that the registry of the reference of the key holds no row of the key's partition.
	 *
	 * @param theEvita   the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 */
	private static void assertNoRow(
		@Nonnull Evita theEvita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		assertEquals(
			-1, SingleOwnerPartitionFixture.registeredPartitionOf(theEvita, entityType, scope, key),
			"the registry must hold no row of " + key + " in " + scope
		);
	}

	/**
	 * Asserts that the partition of the key is an index object with exactly the given owners.
	 *
	 * @param theEvita   the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 * @param owners     the expected owners, ascending
	 * @return the primary key of the partition
	 */
	private static int assertPartitionObject(
		@Nonnull Evita theEvita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key,
		int... owners
	) {
		final EntityCollection collection = SingleOwnerPartitionFixture.findCollectionOf(theEvita, entityType);
		assertNotNull(collection);
		final EntityIndex partition = collection.getIndexByKeyIfExists(ReferencePartitions.createKey(scope, key));
		assertNotNull(partition, "the partition of " + key + " in " + scope + " must be an index object");
		assertEquals(
			Arrays.stream(owners).boxed().toList(),
			Arrays.stream(partition.getAllPrimaryKeys().getArray()).boxed().toList(),
			"the owners of the index object of " + key + " in " + scope
		);
		return partition.getPrimaryKey();
	}

	/**
	 * Asserts that the partition of the key has no index object.
	 *
	 * @param theEvita   the engine
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope
	 * @param key        the key of the partition
	 */
	private static void assertNoPartitionObject(
		@Nonnull Evita theEvita,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		final EntityCollection collection = SingleOwnerPartitionFixture.findCollectionOf(theEvita, entityType);
		assertNotNull(collection);
		assertNull(
			collection.getIndexByKeyIfExists(ReferencePartitions.createKey(scope, key)),
			"the partition of " + key + " in " + scope + " must have no index object"
		);
	}

	/**
	 * Returns the key, with its internal primary key, of the only existing category reference of a product to a
	 * category, in whichever scope the product is.
	 *
	 * @param session  the session to read in
	 * @param product  the product
	 * @param category the referenced category
	 * @return the reference key
	 */
	@Nonnull
	private static ReferenceKey existingCategoryReferenceKey(
		@Nonnull EvitaSessionContract session,
		int product,
		int category
	) {
		final List<ReferenceKey> keys = session.getEntity(ENTITY_PRODUCT, product, Scope.values(), referenceContentAll())
			.orElseThrow()
			.getReferences(REFERENCE_CATEGORIES)
			.stream()
			.filter(it -> it.getReferencedPrimaryKey() == category)
			.map(ReferenceContract::getReferenceKey)
			.toList();
		assertEquals(1, keys.size(), "product " + product + " must reference category " + category + " once: " + keys);
		return keys.getFirst();
	}

	/**
	 * Asserts that the referenced-type index of the reference does not advertise the partition any more.
	 *
	 * @param theEvita      the engine
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @param scope         the scope
	 * @param partition     the primary key of the partition
	 */
	private static void assertNotAdvertised(
		@Nonnull Evita theEvita,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		int partition
	) {
		final EntityCollection collection = SingleOwnerPartitionFixture.findCollectionOf(theEvita, entityType);
		assertNotNull(collection);
		final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName)
		);
		assertTrue(
			typeIndex == null || !typeIndex.getAllPrimaryKeys().contains(partition),
			"the type index of " + referenceName + " in " + scope + " must not advertise partition " + partition
		);
	}

	/**
	 * Returns TRUE when the registry of the source reference in the scope holds a sort value of the partition under a
	 * key whose description contains the given text.
	 *
	 * @param theEvita  the engine
	 * @param scope     the scope
	 * @param partition the primary key of the partition
	 * @param keyText   a text the description of the sort key contains - its attribute name or its locale
	 * @return TRUE when such a sort value exists
	 */
	private static boolean hasSortValue(
		@Nonnull Evita theEvita,
		@Nonnull Scope scope,
		int partition,
		@Nonnull String keyText
	) {
		final String prefix = "index/" + ENTITY_PRODUCT + "/" + scope + "/" +
			EntityIndexType.REFERENCED_ENTITY_TYPE + "/" + REFERENCE_CATEGORIES + "/registry/row/" + partition +
			"/sort/";
		final EntityCollection collection = SingleOwnerPartitionFixture.findCollectionOf(
			theEvita, ENTITY_PRODUCT
		);
		assertNotNull(collection);
		return CatalogDescriber.describeIndexesOf(collection).keySet().stream()
			.anyMatch(path -> path.startsWith(prefix) && path.substring(prefix.length()).contains(keyText));
	}

	/**
	 * Asserts that every row of the source reference in the scope names an owner whose body in the scope references
	 * the row's target, and that every reference of a body in the scope has its partition - a row or an index
	 * object, never both.
	 *
	 * @param theEvita the engine
	 * @param scope    the scope
	 */
	private static void assertRowsAgreeWithBodies(@Nonnull Evita theEvita, @Nonnull Scope scope) {
		theEvita.queryCatalog(
			CATALOG,
			session -> {
				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					final SealedEntity product = session.getEntity(
						ENTITY_PRODUCT, pk, new Scope[]{scope}, entityFetchAllContent()
					).orElse(null);
					if (product == null) {
						continue;
					}
					product.getReferences(REFERENCE_CATEGORIES).forEach(reference -> {
						final RepresentativeReferenceKey key = category(reference.getReferencedPrimaryKey());
						final int row = SingleOwnerPartitionFixture.registeredPartitionOf(
							theEvita, ENTITY_PRODUCT, scope, key
						);
						final EntityCollection collection = SingleOwnerPartitionFixture.findCollectionOf(
							theEvita, ENTITY_PRODUCT
						);
						assertNotNull(collection);
						final EntityIndex object = collection.getIndexByKeyIfExists(
							ReferencePartitions.createKey(scope, key)
						);
						if ((row > 0) == (object != null)) {
							fail(
								"the partition of " + key + " in " + scope + " must be a row or an index object, " +
									"but its row is " + row + " and its index object " + object
							);
						}
					});
				}
				return null;
			}
		);
	}

	/**
	 * Returns the reference schema as the committed catalog declares it.
	 *
	 * @param theEvita      the engine
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @return the reference schema
	 */
	@Nonnull
	private static ReferenceSchemaContract referenceSchema(
		@Nonnull Evita theEvita,
		@Nonnull String entityType,
		@Nonnull String referenceName
	) {
		return theEvita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, ReferenceSchemaContract>) session ->
				session.getEntitySchemaOrThrowException(entityType).getReferenceOrThrowException(referenceName)
		);
	}

	/**
	 * Returns the currently published catalog of the engine.
	 *
	 * @param theEvita the engine
	 * @return the catalog
	 */
	@Nonnull
	private static Catalog catalogOf(@Nonnull Evita theEvita) {
		return (Catalog) theEvita.getCatalogInstance(CATALOG).orElseThrow();
	}

	/**
	 * Returns the product owning a row of the fixture in the scope whose indexing changes.
	 *
	 * @param scope the scope
	 * @return the product primary key
	 */
	private static int ownerIn(@Nonnull Scope scope) {
		return scope == Scope.LIVE ? 1 : 3;
	}

	/**
	 * Returns another product of the scope, with German values.
	 *
	 * @param scope the scope
	 * @return the product primary key
	 */
	private static int otherIn(@Nonnull Scope scope) {
		return scope == Scope.LIVE ? 2 : 4;
	}

	/**
	 * Returns the product of the scope that moves to the other scope and back.
	 *
	 * @param scope the scope
	 * @return the product primary key
	 */
	private static int moverIn(@Nonnull Scope scope) {
		return scope == Scope.LIVE ? 5 : 6;
	}

	/**
	 * Returns the scope other than the given one.
	 *
	 * @param scope the scope
	 * @return the other scope
	 */
	@Nonnull
	private static Scope otherScope(@Nonnull Scope scope) {
		return scope == Scope.LIVE ? Scope.ARCHIVED : Scope.LIVE;
	}

	/**
	 * Creates the key of a category partition of the product.
	 *
	 * @param category the category
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey category(int category) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, category));
	}

	/**
	 * Creates the key of a label partition of the product.
	 *
	 * @param category the labelling category
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey label(int category) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_LABELS, category));
	}

	/**
	 * Creates the key of a reflected product partition of the category.
	 *
	 * @param product the product
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey product(int product) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_PRODUCTS, product));
	}

}
