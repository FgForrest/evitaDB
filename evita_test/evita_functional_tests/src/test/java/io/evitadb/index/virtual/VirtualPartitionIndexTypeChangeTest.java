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
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.ReferencePartitionReindexRequiredException;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.index.EntityIndexType;
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
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
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
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.index.virtual.DifferentialEngines.CATALOG;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// TODO JNO - remove at the end of #1615
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
	@DisplayName("the index objects a reference indexed for partitioning left are read, written, joined and demoted")
	void shouldKeepTheIndexObjectsOfTheFormerIndexingWorking(CatalogState state, Scope changedScope) {
		final Evita theEvita = start("formerObjects", state, true);
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

	/**
	 * Defines the schema and the data - see the constants. With `partitioning` the source reference is indexed for
	 * filtering and partitioning in both scopes, otherwise for filtering only.
	 *
	 * @param session      the session to write in
	 * @param partitioning TRUE to index the source reference for partitioning
	 */
	private static void setUp(@Nonnull EvitaSessionContract session, boolean partitioning) {
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withAttribute(
				ATTRIBUTE_TITLE, String.class,
				whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withPriceIndexedInScope(Scope.LIVE, Scope.ARCHIVED)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
			.withAttribute(
				ATTRIBUTE_NAME, String.class,
				whichIs -> whichIs.localized().filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
			)
			.withAttribute(ATTRIBUTE_VISIBLE, Boolean.class, whichIs -> whichIs.nullable())
			.withAttribute(ATTRIBUTE_PINNED, Boolean.class, whichIs -> whichIs.nullable())
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
		final TestPaths thePaths = createTestPaths(getClass().getSimpleName() + "_" + label);
		this.paths = thePaths;
		final Evita theEvita = new Evita(
			newTestEvitaConfigurationBuilder(thePaths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.build()
		);
		this.evita = theEvita;
		theEvita.createCatalogWithVirtualReferencePartitions(CATALOG, true);
		theEvita.defineCatalog(CATALOG).updateViaNewSession(theEvita);
		update(theEvita, session -> setUp(session, partitioning));
		if (state == CatalogState.ALIVE) {
			theEvita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		}
		assertEquals(state, catalogOf(theEvita).getCatalogState());
		assertTrue(
			catalogOf(theEvita).getVirtualReferencePartitionMaintenance().isWrittenVirtually(),
			"the catalog must write its single-owner partitions virtually"
		);
		return theEvita;
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
	 * included (see {@link DifferentialEngines#describeIndexesOf}), and the version of every entity.
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
			lines.putAll(DifferentialEngines.describeIndexesOf(collection));
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
		final int partition = VirtualPartitionSingletonLifecycleTest.registeredPartitionOf(
			theEvita, entityType, scope, key
		);
		assertTrue(partition > 0, "the registry must hold the row of " + key + " in " + scope);
		final EntityCollection collection = VirtualPartitionSingletonLifecycleTest.collectionOf(theEvita, entityType);
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
			-1, VirtualPartitionSingletonLifecycleTest.registeredPartitionOf(theEvita, entityType, scope, key),
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
		final EntityCollection collection = VirtualPartitionSingletonLifecycleTest.collectionOf(theEvita, entityType);
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
		final EntityCollection collection = VirtualPartitionSingletonLifecycleTest.collectionOf(theEvita, entityType);
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
		return keys.get(0);
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
		final EntityCollection collection = VirtualPartitionSingletonLifecycleTest.collectionOf(theEvita, entityType);
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
		final EntityCollection collection = VirtualPartitionSingletonLifecycleTest.collectionOf(
			theEvita, ENTITY_PRODUCT
		);
		assertNotNull(collection);
		return DifferentialEngines.describeIndexesOf(collection).keySet().stream()
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
						final int row = VirtualPartitionSingletonLifecycleTest.registeredPartitionOf(
							theEvita, ENTITY_PRODUCT, scope, key
						);
						final EntityCollection collection = VirtualPartitionSingletonLifecycleTest.collectionOf(
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
