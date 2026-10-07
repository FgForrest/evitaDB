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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.virtual.VirtualReadParity.Comparison;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that `referenceHaving` - and the translation of partitions to their targets behind `facetHaving` - answers
 * the same with the read switch on as with it off, on the same engine and the same data: the virtual reading of the
 * registered single-owner partitions must be indistinguishable from the reading of their reduced entity indexes for
 * every body - the positive bodies answered from the candidate set and the bodies answered exactly:
 * negations, `attributeIs`, `entityPrimaryKeyInSet` under a negation including missing managed targets,
 * `groupHaving`, `entityLocaleEquals` and `inScope` - and for a `referenceHaving` the bidirectional rewrite answers
 * from the counterpart's registered partitions.
 *
 * Four catalogs, each maintained from its first write so every single-owner partition has its row:
 *
 * - {@link RelatedProductsShapedFixture} - thousands of generated owners mixing single-owner and shared partitions,
 *   duplicates, value-less rows, missing managed targets, groups, both scopes, and categories reflecting the products
 *   they hold, some of them through single-owner partitions of the product side;
 * - {@link SingleOwnerPartitionFixture} with the reflected `Category.products` reference and one archived product;
 * - {@link CrossScopeSingletonFixture} - one key held by partitions of both scopes, and a reference whose partitions
 *   are all single-owner;
 * - {@link SingleOwnerPartitionFixture} extended for every read route, with one product of the shared category alone
 *   archived - so the bidirectional rewrite reaches the shared category through a shared live partition and a
 *   single-owner archived one at once.
 *
 * Every case runs in the scope sets its constraints admit, and must read virtual rows in at least one of them - an
 * equal answer of a run that read nothing virtually would compare the object reading with itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read parity of referenceHaving over virtual reference partitions")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualReferenceHavingParityTest implements EvitaTestSupport {
	private static final String CATALOG_RELATED = "virtualHavingParityRelated";
	private static final String CATALOG_SINGLE_OWNER = "virtualHavingParitySingleOwner";
	private static final String CATALOG_CROSS_SCOPE = "virtualHavingParityCrossScope";
	private static final String CATALOG_EVERY_ROUTE = "virtualHavingParityEveryRoute";
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] ARCHIVED = {Scope.ARCHIVED};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	private static final Scope[][] EVERY_SCOPE_SET = {LIVE, ARCHIVED, BOTH};
	/**
	 * The page large enough to hold every entity of every catalog.
	 */
	private static final int PAGE_SIZE = 100_000;

	private TestPaths paths;
	private Evita evita;

	/**
	 * A positive parity case - one filter over one collection, compared in each of its scope sets.
	 *
	 * @param name            the name of the case
	 * @param catalog         the catalog queried
	 * @param entityType      the collection queried
	 * @param scopeSets       the scope sets the case runs in
	 * @param readsVirtualRows TRUE when the read-on arm must read virtual rows in at least one scope set
	 * @param constraints     the filter constraints following the scope
	 */
	private record ParityCase(
		@Nonnull String name,
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope[][] scopeSets,
		boolean readsVirtualRows,
		@Nonnull FilterConstraint... constraints
	) {

		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}

	}

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("VirtualReferenceHavingParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		build(
			CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_TYPES,
			RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive
		);
		build(
			CATALOG_SINGLE_OWNER, SingleOwnerPartitionFixture.ENTITY_TYPES,
			SingleOwnerPartitionFixture::setUpWithReflectedProducts,
			session -> session.archiveEntity(SingleOwnerPartitionFixture.ENTITY_PRODUCT, 5)
		);
		build(
			CATALOG_CROSS_SCOPE, CrossScopeSingletonFixture.ENTITY_TYPES,
			CrossScopeSingletonFixture::setUp, CrossScopeSingletonFixture::archive
		);
		// the first filler product, held by the shared category alone, moves to the archive - the shared category is
		// then reached through a shared live partition of `categories` and a single-owner archived one at once
		build(
			CATALOG_EVERY_ROUTE, SingleOwnerPartitionFixture.EVERY_ROUTE_ENTITY_TYPES,
			SingleOwnerPartitionFixture::setUpWithEveryReadRoute,
			session -> session.archiveEntity(
				SingleOwnerPartitionFixture.ENTITY_PRODUCT, SingleOwnerPartitionFixture.FIRST_FILLER_PRODUCT
			)
		);
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Returns the positive parity cases.
	 *
	 * @return the cases
	 */
	@Nonnull
	static Stream<ParityCase> positiveCases() {
		final String related = RelatedProductsShapedFixture.REFERENCE_RELATED;
		final String relatedProduct = RelatedProductsShapedFixture.ENTITY_PRODUCT;
		final String relationType = RelatedProductsShapedFixture.ATTRIBUTE_RELATION_TYPE;
		final String order = RelatedProductsShapedFixture.ATTRIBUTE_ORDER;
		final String rank = RelatedProductsShapedFixture.ATTRIBUTE_RANK;
		final Integer[] hotTargetsAndMissing = Stream.concat(
			IntStream.rangeClosed(1, 40).boxed(),
			IntStream.rangeClosed(
				RelatedProductsShapedFixture.PRODUCT_COUNT + 1, RelatedProductsShapedFixture.PRODUCT_COUNT + 10
			).boxed()
		).toArray(Integer[]::new);
		final Integer[] missingOnly = IntStream.rangeClosed(
			RelatedProductsShapedFixture.PRODUCT_COUNT + 1,
			RelatedProductsShapedFixture.PRODUCT_COUNT + RelatedProductsShapedFixture.MISSING_TARGET_COUNT
		).boxed().toArray(Integer[]::new);
		final String product = SingleOwnerPartitionFixture.ENTITY_PRODUCT;
		final String category = SingleOwnerPartitionFixture.ENTITY_CATEGORY;
		final String owner = CrossScopeSingletonFixture.ENTITY_OWNER;
		return Stream.of(
			// the generated relatedProducts shape
			new ParityCase(
				"related: attributeEquals", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(related, attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_ACCESSORY))
			),
			new ParityCase(
				"related: attributeInSet", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(
					related,
					attributeInSet(
						relationType, RelatedProductsShapedFixture.TYPE_ALTERNATIVE, RelatedProductsShapedFixture.TYPE_SPARE
					)
				)
			),
			new ParityCase(
				"related: attributeBetween", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(related, attributeBetween(order, 20, 40))
			),
			new ParityCase(
				"related: attributeStartsWith on a localized attribute", CATALOG_RELATED, relatedProduct,
				EVERY_SCOPE_SET, true,
				entityLocaleEquals(RelatedProductsShapedFixture.LOCALE_EN),
				referenceHaving(related, attributeStartsWith(RelatedProductsShapedFixture.ATTRIBUTE_LABEL, "l1"))
			),
			new ParityCase(
				"related: and across the rows of a mixed owner", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(
					related,
					attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_ACCESSORY),
					attributeGreaterThanEquals(order, 50)
				)
			),
			new ParityCase(
				"related: or", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(
					related,
					or(
						attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_SPARE),
						attributeLessThan(order, 5)
					)
				)
			),
			new ParityCase(
				"related: entityHaving", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(related, entityHaving(attributeLessThan(rank, 3_000)))
			),
			new ParityCase(
				"related: entityPrimaryKeyInSet with missing targets", CATALOG_RELATED, relatedProduct,
				EVERY_SCOPE_SET, true,
				referenceHaving(related, entityPrimaryKeyInSet(hotTargetsAndMissing))
			),
			new ParityCase(
				"related: entityPrimaryKeyInSet of missing targets only", CATALOG_RELATED, relatedProduct,
				EVERY_SCOPE_SET, false,
				referenceHaving(related, entityPrimaryKeyInSet(missingOnly))
			),
			new ParityCase(
				"related: entityPrimaryKeyInSet and attributeEquals", CATALOG_RELATED, relatedProduct,
				EVERY_SCOPE_SET, true,
				referenceHaving(
					related,
					entityPrimaryKeyInSet(IntStream.rangeClosed(1, 100).boxed().toArray(Integer[]::new)),
					attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_ALTERNATIVE)
				)
			),
			new ParityCase(
				"related: empty body", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(related)
			),
			new ParityCase(
				"related: negated referenceHaving", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				not(referenceHaving(related, attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_ACCESSORY)))
			),
			new ParityCase(
				"related: referenceHaving in a conjunction", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				attributeLessThan(rank, 20_000),
				referenceHaving(related, attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_SPARE))
			),
			new ParityCase(
				"related: referenceHaving in a disjunction", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				or(
					attributeEquals(RelatedProductsShapedFixture.ATTRIBUTE_CODE, "P7"),
					referenceHaving(related, attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_SPARE))
				)
			),
			new ParityCase(
				"related: facetHaving translates partitions to targets", CATALOG_RELATED, relatedProduct,
				EVERY_SCOPE_SET, true,
				facetHaving(related, attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_SPARE))
			),
			new ParityCase(
				"related: facetHaving with a negated body", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				facetHaving(related, not(attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_SPARE)))
			),
			new ParityCase(
				"related: facetHaving with entityHaving", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				facetHaving(related, entityHaving(attributeLessThan(rank, 2_000)))
			),
			// the small catalog with the reflected reference
			new ParityCase(
				"single owner: categories by attribute", CATALOG_SINGLE_OWNER, product, new Scope[][]{LIVE}, true,
				referenceHaving(
					SingleOwnerPartitionFixture.REFERENCE_CATEGORIES,
					attributeLessThan(SingleOwnerPartitionFixture.ATTRIBUTE_ORDER, 5)
				)
			),
			new ParityCase(
				"single owner: categories, empty body", CATALOG_SINGLE_OWNER, product, EVERY_SCOPE_SET, true,
				referenceHaving(SingleOwnerPartitionFixture.REFERENCE_CATEGORIES)
			),
			new ParityCase(
				"single owner: gallery by role", CATALOG_SINGLE_OWNER, product, new Scope[][]{LIVE}, true,
				referenceHaving(
					SingleOwnerPartitionFixture.REFERENCE_GALLERY,
					attributeEquals(SingleOwnerPartitionFixture.ATTRIBUTE_ROLE, SingleOwnerPartitionFixture.ROLE_DETAIL)
				)
			),
			new ParityCase(
				"single owner: gallery by target", CATALOG_SINGLE_OWNER, product, EVERY_SCOPE_SET, true,
				referenceHaving(
					SingleOwnerPartitionFixture.REFERENCE_GALLERY,
					entityPrimaryKeyInSet(1, 2, 3, 5, SingleOwnerPartitionFixture.SHARED_MEDIA)
				)
			),
			new ParityCase(
				"single owner: reflected products by attribute", CATALOG_SINGLE_OWNER, category, new Scope[][]{LIVE},
				true,
				referenceHaving(
					SingleOwnerPartitionFixture.REFERENCE_PRODUCTS,
					attributeLessThan(SingleOwnerPartitionFixture.ATTRIBUTE_ORDER, 5)
				)
			),
			new ParityCase(
				"single owner: reflected products by target", CATALOG_SINGLE_OWNER, category, EVERY_SCOPE_SET, true,
				referenceHaving(SingleOwnerPartitionFixture.REFERENCE_PRODUCTS, entityPrimaryKeyInSet(1, 2, 4, 6))
			),
			new ParityCase(
				"single owner: facetHaving on categories", CATALOG_SINGLE_OWNER, product, new Scope[][]{LIVE}, true,
				facetHaving(
					SingleOwnerPartitionFixture.REFERENCE_CATEGORIES,
					attributeGreaterThan(SingleOwnerPartitionFixture.ATTRIBUTE_ORDER, 2)
				)
			),
			// one key in both scopes, and a reference read wholly virtually
			new ParityCase(
				"cross scope: items by weight", CATALOG_CROSS_SCOPE, owner, EVERY_SCOPE_SET, true,
				referenceHaving(
					CrossScopeSingletonFixture.REFERENCE_ITEMS,
					attributeGreaterThan(CrossScopeSingletonFixture.ATTRIBUTE_WEIGHT, 0)
				)
			),
			new ParityCase(
				"cross scope: media by role", CATALOG_CROSS_SCOPE, owner, EVERY_SCOPE_SET, true,
				referenceHaving(
					CrossScopeSingletonFixture.REFERENCE_MEDIA,
					attributeEquals(CrossScopeSingletonFixture.ATTRIBUTE_ROLE, CrossScopeSingletonFixture.ROLE_A)
				)
			),
			new ParityCase(
				"cross scope: all partitions virtual, unnarrowed", CATALOG_CROSS_SCOPE, owner, EVERY_SCOPE_SET, true,
				referenceHaving(CrossScopeSingletonFixture.REFERENCE_SOLO)
			)
		);
	}

	/**
	 * Returns the parity cases whose body is answered exactly - each holds a constraint whose answer
	 * for the referenced-type entity index differs from the answer per partition.
	 *
	 * @return the cases
	 */
	@Nonnull
	static Stream<ParityCase> exactCases() {
		final String related = RelatedProductsShapedFixture.REFERENCE_RELATED;
		final String relatedProduct = RelatedProductsShapedFixture.ENTITY_PRODUCT;
		final String relationType = RelatedProductsShapedFixture.ATTRIBUTE_RELATION_TYPE;
		final String order = RelatedProductsShapedFixture.ATTRIBUTE_ORDER;
		final FilterConstraint accessories = attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_ACCESSORY);
		final FilterConstraint spares = attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_SPARE);
		final Integer[] missingOnly = IntStream.rangeClosed(
			RelatedProductsShapedFixture.PRODUCT_COUNT + 1,
			RelatedProductsShapedFixture.PRODUCT_COUNT + RelatedProductsShapedFixture.MISSING_TARGET_COUNT
		).boxed().toArray(Integer[]::new);
		final String categories = RelatedProductsShapedFixture.REFERENCE_CATEGORIES;
		// the categories held by one product each - every partition of theirs is read virtually
		final Integer[] singletonCategories = IntStream.rangeClosed(
			RelatedProductsShapedFixture.POPULAR_CATEGORY_COUNT + 1,
			RelatedProductsShapedFixture.POPULAR_CATEGORY_COUNT + RelatedProductsShapedFixture.SINGLETON_CATEGORY_COUNT
		).boxed().toArray(Integer[]::new);
		final String priority = RelatedProductsShapedFixture.ATTRIBUTE_PRIORITY;
		final Scope[][] liveAndBoth = {LIVE, BOTH};
		final Scope[][] archivedAndBoth = {ARCHIVED, BOTH};
		return Stream.of(
			// negations and attributeIs
			new ParityCase(
				"related: not", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(related, not(accessories))
			),
			new ParityCase(
				"related: and with a negated sibling", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(related, spares, not(attributeLessThan(order, 50)))
			),
			new ParityCase(
				"related: not of or", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(related, not(or(accessories, attributeGreaterThan(order, 80))))
			),
			new ParityCase(
				"related: attributeIs NULL on value-less rows", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET,
				true,
				referenceHaving(related, attributeIs(order, AttributeSpecialValue.NULL))
			),
			new ParityCase(
				"related: attributeIs NOT_NULL", CATALOG_RELATED, relatedProduct, EVERY_SCOPE_SET, true,
				referenceHaving(related, attributeIs(order, AttributeSpecialValue.NOT_NULL), accessories)
			),
			new ParityCase(
				"related: nested not(referenceHaving) of a negated body", CATALOG_RELATED, relatedProduct,
				EVERY_SCOPE_SET, true,
				not(referenceHaving(related, not(accessories)))
			),
			// entityPrimaryKeyInSet of missing managed targets under a negation
			new ParityCase(
				"related: not entityPrimaryKeyInSet of missing targets", CATALOG_RELATED, relatedProduct,
				EVERY_SCOPE_SET, true,
				referenceHaving(related, not(entityPrimaryKeyInSet(missingOnly)))
			),
			new ParityCase(
				"related: not of or with entityPrimaryKeyInSet of missing targets", CATALOG_RELATED, relatedProduct,
				EVERY_SCOPE_SET, true,
				referenceHaving(related, not(or(entityPrimaryKeyInSet(missingOnly), accessories)))
			),
			new ParityCase(
				"related: and with not entityPrimaryKeyInSet of missing targets", CATALOG_RELATED, relatedProduct,
				EVERY_SCOPE_SET, true,
				referenceHaving(related, and(not(entityPrimaryKeyInSet(missingOnly)), spares))
			),
			// groupHaving - over `categories`, indexed in the live scope only, whose singleton categories are read
			// virtually; a product's singleton row is in another group and has another priority than its row of a
			// popular category, so a cross-row reading of the conjunction would differ
			new ParityCase(
				"categories: groupHaving", CATALOG_RELATED, relatedProduct, new Scope[][]{LIVE}, true,
				referenceHaving(
					categories, groupHaving(attributeEquals(RelatedProductsShapedFixture.ATTRIBUTE_CODE, "C2"))
				)
			),
			new ParityCase(
				"categories: groupHaving and an attribute of the same row", CATALOG_RELATED, relatedProduct,
				new Scope[][]{LIVE}, true,
				referenceHaving(categories, groupHaving(entityPrimaryKeyInSet(2, 3)), attributeEquals(priority, 6))
			),
			new ParityCase(
				"categories: not groupHaving", CATALOG_RELATED, relatedProduct, new Scope[][]{LIVE}, true,
				referenceHaving(categories, not(groupHaving(entityPrimaryKeyInSet(1, 3, 5))))
			),
			new ParityCase(
				"categories: groupHaving of the singleton rows", CATALOG_RELATED, relatedProduct, new Scope[][]{LIVE},
				true,
				referenceHaving(
					categories, entityPrimaryKeyInSet(singletonCategories), groupHaving(entityPrimaryKeyInSet(2, 3))
				)
			),
			new ParityCase(
				"categories: not groupHaving of the singleton rows", CATALOG_RELATED, relatedProduct,
				new Scope[][]{LIVE}, true,
				referenceHaving(
					categories, entityPrimaryKeyInSet(singletonCategories), not(groupHaving(entityPrimaryKeyInSet(2)))
				)
			),
			new ParityCase(
				"categories: groupHaving inside or", CATALOG_RELATED, relatedProduct, new Scope[][]{LIVE}, true,
				referenceHaving(categories, or(groupHaving(entityPrimaryKeyInSet(4)), attributeEquals(priority, 0)))
			),
			// entityLocaleEquals - not in the live scope alone, where the reduced entity indexes of `relatedProducts`
			// keep the locale of an archived owner of a sibling partition and so answer it (a pre-existing defect); the
			// archive and both scopes answer every owner by a partition of its own
			new ParityCase(
				"related: entityLocaleEquals of a locale half the owners have", CATALOG_RELATED, relatedProduct,
				archivedAndBoth, true,
				referenceHaving(related, entityLocaleEquals(RelatedProductsShapedFixture.LOCALE_DE))
			),
			new ParityCase(
				"related: entityLocaleEquals of a locale every owner has", CATALOG_RELATED, relatedProduct,
				archivedAndBoth, true,
				referenceHaving(related, entityLocaleEquals(RelatedProductsShapedFixture.LOCALE_EN), spares)
			),
			new ParityCase(
				"related: entityLocaleEquals and an attribute", CATALOG_RELATED, relatedProduct, archivedAndBoth, true,
				referenceHaving(related, entityLocaleEquals(RelatedProductsShapedFixture.LOCALE_DE), spares)
			),
			new ParityCase(
				"related: entityLocaleEquals inside or", CATALOG_RELATED, relatedProduct, archivedAndBoth, true,
				referenceHaving(
					related, or(entityLocaleEquals(RelatedProductsShapedFixture.LOCALE_DE), attributeLessThan(order, 3))
				)
			),
			// inScope
			new ParityCase(
				"related: inScope of the live scope", CATALOG_RELATED, relatedProduct, liveAndBoth, true,
				referenceHaving(related, inScope(Scope.LIVE, spares))
			),
			new ParityCase(
				"related: inScope of the archive", CATALOG_RELATED, relatedProduct, archivedAndBoth, true,
				referenceHaving(related, inScope(Scope.ARCHIVED, spares))
			),
			new ParityCase(
				"related: not inScope of the live scope", CATALOG_RELATED, relatedProduct, liveAndBoth, true,
				referenceHaving(related, not(inScope(Scope.LIVE, spares)))
			),
			new ParityCase(
				"related: inScope of each scope inside or", CATALOG_RELATED, relatedProduct, new Scope[][]{BOTH}, true,
				referenceHaving(
					related, or(inScope(Scope.LIVE, spares), inScope(Scope.ARCHIVED, attributeLessThan(order, 10)))
				)
			),
			// the small catalogs
			new ParityCase(
				"single owner: gallery without the shared thumbnail", CATALOG_SINGLE_OWNER,
				SingleOwnerPartitionFixture.ENTITY_PRODUCT, EVERY_SCOPE_SET, true,
				referenceHaving(
					SingleOwnerPartitionFixture.REFERENCE_GALLERY,
					not(entityPrimaryKeyInSet(SingleOwnerPartitionFixture.SHARED_MEDIA))
				)
			),
			new ParityCase(
				"cross scope: items not of weight zero", CATALOG_CROSS_SCOPE, CrossScopeSingletonFixture.ENTITY_OWNER,
				EVERY_SCOPE_SET, true,
				referenceHaving(
					CrossScopeSingletonFixture.REFERENCE_ITEMS,
					not(attributeEquals(CrossScopeSingletonFixture.ATTRIBUTE_WEIGHT, 0))
				)
			)
		);
	}

	/**
	 * Returns the parity cases of a `referenceHaving` over the categories, which the bidirectional rewrite answers from
	 * the product side - the products' `categories`, some partitions of which are single-owner and read virtually. Only
	 * the product collection is read virtually, so every virtual row a case reads is read by the rewrite.
	 *
	 * @return the cases
	 */
	@Nonnull
	static Stream<ParityCase> bidirectionalCases() {
		final String products = RelatedProductsShapedFixture.REFERENCE_PRODUCTS;
		final String category = RelatedProductsShapedFixture.ENTITY_CATEGORY;
		final String priority = RelatedProductsShapedFixture.ATTRIBUTE_PRIORITY;
		final String rank = RelatedProductsShapedFixture.ATTRIBUTE_RANK;
		// `products` is indexed in the live scope only - no scope set holding the archive admits it
		final Scope[][] live = {LIVE};
		return Stream.of(
			new ParityCase(
				"bidirectional: bare", CATALOG_RELATED, category, live, true, referenceHaving(products)
			),
			new ParityCase(
				"bidirectional: attribute", CATALOG_RELATED, category, live, true,
				referenceHaving(products, attributeEquals(priority, 3))
			),
			new ParityCase(
				"bidirectional: or of attributes", CATALOG_RELATED, category, live, true,
				referenceHaving(products, or(attributeEquals(priority, 1), attributeEquals(priority, 7)))
			),
			new ParityCase(
				"bidirectional: negated attribute", CATALOG_RELATED, category, live, true,
				referenceHaving(products, not(attributeLessThan(priority, 5)))
			),
			new ParityCase(
				"bidirectional: entityHaving", CATALOG_RELATED, category, live, true,
				referenceHaving(products, entityHaving(attributeLessThan(rank, 2_000)))
			),
			new ParityCase(
				"bidirectional: entityHaving and an attribute", CATALOG_RELATED, category, live, true,
				referenceHaving(
					products, entityHaving(attributeLessThan(rank, 5_000)), attributeGreaterThan(priority, 4)
				)
			)
		);
	}

	@DisplayName("a body answers the same with the read switch on")
	@ParameterizedTest(name = "{0}")
	@MethodSource({"positiveCases", "exactCases"})
	void shouldAnswerABodyAsTheIndexObjectsDo(@Nonnull ParityCase parityCase) {
		assertParity(parityCase, entityTypesOf(parityCase.catalog()));
	}

	@DisplayName("the bidirectional rewrite answers the same with the counterpart read virtually")
	@ParameterizedTest(name = "{0}")
	@MethodSource("bidirectionalCases")
	void shouldAnswerABidirectionalRewriteAsTheIndexObjectsDo(@Nonnull ParityCase parityCase) {
		// the category side is read through its index objects, so the virtual rows are the rewrite's alone
		assertParity(parityCase, Set.of(RelatedProductsShapedFixture.ENTITY_PRODUCT));
	}

	@Test
	@DisplayName("the bidirectional rewrite reaches the categories held only by single-owner partitions")
	void shouldReachTheSingletonCategoriesThroughTheRewrite() {
		final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
			maintenance(CATALOG_RELATED), Set.of(RelatedProductsShapedFixture.ENTITY_PRODUCT),
			() -> queryPrimaryKeys(
				CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_CATEGORY, LIVE,
				referenceHaving(RelatedProductsShapedFixture.REFERENCE_PRODUCTS)
			)
		);
		final List<Integer> singletonCategories = IntStream.rangeClosed(
			RelatedProductsShapedFixture.POPULAR_CATEGORY_COUNT + 1,
			RelatedProductsShapedFixture.POPULAR_CATEGORY_COUNT + RelatedProductsShapedFixture.SINGLETON_CATEGORY_COUNT
		).boxed().toList();
		assertTrue(comparison.objectReading().containsAll(singletonCategories), comparison.objectReading().toString());
		assertEquals(comparison.objectReading(), comparison.virtualReading().answer());
		// one virtual row per singleton category at least - the rows the rewrite reads virtually
		assertTrue(
			comparison.virtualReading().virtualRowsRead() >= singletonCategories.size(),
			"The rewrite must read the singleton rows virtually: " + comparison.virtualReading().virtualRowsRead()
		);
	}

	@Test
	@DisplayName("the bidirectional rewrite unites the rows of an owner reached through both representations at once")
	void shouldUniteTheMaterializedAndTheVirtualRowsOfOneOwner() {
		final FilterConstraint referenceHaving = referenceHaving(SingleOwnerPartitionFixture.REFERENCE_PRODUCTS);
		long virtualRowsRead = 0;
		for (final Scope[] scopes : new Scope[][]{LIVE, BOTH}) {
			final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
				maintenance(CATALOG_EVERY_ROUTE), Set.of(SingleOwnerPartitionFixture.ENTITY_PRODUCT),
				() -> queryPrimaryKeys(
					CATALOG_EVERY_ROUTE, SingleOwnerPartitionFixture.ENTITY_CATEGORY, scopes, referenceHaving
				)
			);
			assertEquals(
				comparison.objectReading(), comparison.virtualReading().answer(),
				referenceHaving + " in " + Arrays.toString(scopes)
			);
			// the shared category is the owner holding a shared live partition and a single-owner archived one
			assertTrue(
				comparison.objectReading().contains(SingleOwnerPartitionFixture.SHARED_CATEGORY),
				comparison.objectReading().toString()
			);
			virtualRowsRead += comparison.virtualReading().virtualRowsRead();
		}
		// only the product side is read virtually, so the virtual rows are the rewrite's alone
		assertTrue(virtualRowsRead > 0, "The rewrite must read the counterpart rows virtually");
	}

	/**
	 * Asserts a case answers the same read-off and read-on in each of its scope sets, and - when it must - that the
	 * read-on run read virtual rows and some scope set matched an owner.
	 *
	 * @param parityCase  the case
	 * @param entityTypes the collections read virtually in the read-on run
	 */
	private void assertParity(@Nonnull ParityCase parityCase, @Nonnull Set<String> entityTypes) {
		long virtualRowsRead = 0;
		boolean matchedAny = false;
		for (final Scope[] scopes : parityCase.scopeSets()) {
			final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
				maintenance(parityCase.catalog()), entityTypes,
				() -> queryPrimaryKeys(parityCase.catalog(), parityCase.entityType(), scopes, parityCase.constraints())
			);
			assertEquals(
				comparison.objectReading(), comparison.virtualReading().answer(),
				parityCase + " in " + Arrays.toString(scopes) + " must answer the same with the read switch on"
			);
			virtualRowsRead += comparison.virtualReading().virtualRowsRead();
			matchedAny |= !comparison.objectReading().isEmpty();
		}
		if (parityCase.readsVirtualRows()) {
			assertTrue(virtualRowsRead > 0, parityCase + " must read virtual rows in at least one scope set");
			assertTrue(matchedAny, parityCase + " must match an owner in at least one scope set");
		}
	}

	@Test
	@DisplayName("every positive case reads the same set of owners page by page")
	void shouldPageThroughTheSameOwners() {
		final FilterConstraint referenceHaving = referenceHaving(
			RelatedProductsShapedFixture.REFERENCE_RELATED,
			attributeEquals(
				RelatedProductsShapedFixture.ATTRIBUTE_RELATION_TYPE, RelatedProductsShapedFixture.TYPE_ACCESSORY
			)
		);
		final Comparison<List<List<Integer>>> comparison = VirtualReadParity.compare(
			maintenance(CATALOG_RELATED), entityTypesOf(CATALOG_RELATED),
			() -> {
				final List<List<Integer>> pages = new ArrayList<>();
				for (int page = 1; page <= 4; page++) {
					final int pageNumber = page;
					pages.add(
						this.evita.queryCatalog(
							CATALOG_RELATED,
							session -> {
								return session.query(
									query(
										collection(RelatedProductsShapedFixture.ENTITY_PRODUCT),
										filterBy(scope(BOTH), referenceHaving),
										orderBy(attributeNatural(RelatedProductsShapedFixture.ATTRIBUTE_RANK)),
										require(
											page(pageNumber, 97),
											debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
										)
									),
									EntityReference.class
								).getRecordData().stream().map(EntityReference::getPrimaryKey).toList();
							}
						)
					);
				}
				return pages;
			}
		);
		assertEquals(comparison.objectReading(), comparison.virtualReading().answer());
		assertTrue(comparison.virtualReading().virtualRowsRead() > 0);
		assertTrue(comparison.objectReading().stream().allMatch(it -> it.size() == 97));
	}

	@Test
	@DisplayName("the generated catalog has the relatedProducts shape the parity cases rely on")
	void shouldHaveTheRelatedProductsShape() {
		final EntityCollection products = (EntityCollection) this.evita.getCatalogInstance(CATALOG_RELATED)
			.orElseThrow()
			.getCollectionForEntity(RelatedProductsShapedFixture.ENTITY_PRODUCT)
			.orElseThrow();
		final TreeMap<Integer, Long> registered = new TreeMap<>();
		final TreeSet<Integer> advertised = new TreeSet<>();
		for (final Scope scope : BOTH) {
			final EntityIndex index = products.getIndexByKeyIfExists(
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE, scope, RelatedProductsShapedFixture.REFERENCE_RELATED
				)
			);
			final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) index;
			final int registeredBefore = registered.size();
			typeIndex.forEachReferenceIndexPrimaryKey(advertised::add);
			typeIndex.getVirtualReferencePartitionIndex().forEachEntry(
				VirtualReferencePartitionSlot.PARTITION_OWNER, registered::put
			);
			assertTrue(registered.size() > registeredBefore, "The " + scope + " scope must hold registered partitions");
		}
		final double singleOwnerShare = (double) registered.size() / advertised.size();
		assertTrue(
			singleOwnerShare > 0.25 && singleOwnerShare < 0.5,
			"About a third of the partitions must be single-owner: " + registered.size() + " of " + advertised.size()
		);
		// owners holding a row read virtually next to a row of a shared partition
		final TreeSet<Integer> virtualOwners = new TreeSet<>();
		for (final Long row : registered.values()) {
			virtualOwners.add((int) (row >>> 32));
		}
		final TreeSet<Integer> sharedOwners = new TreeSet<>();
		for (final Integer partitionPrimaryKey : advertised) {
			if (!registered.containsKey(partitionPrimaryKey)) {
				final EntityIndex partition = products.getIndexByPrimaryKeyIfExists(partitionPrimaryKey);
				for (final int owner : partition.getAllPrimaryKeys().getArray()) {
					sharedOwners.add(owner);
				}
			}
		}
		virtualOwners.retainAll(sharedOwners);
		assertTrue(
			virtualOwners.size() > 900,
			"About a thousand owners must hold rows of both representations: " + virtualOwners.size()
		);
	}

	/**
	 * Queries the primary keys of the entities of a collection matching the constraints in the scopes.
	 *
	 * @param catalog     the catalog
	 * @param entityType  the collection
	 * @param scopes      the scopes
	 * @param constraints the filter constraints following the scope
	 * @return the primary keys in the order the query returns them
	 */
	@Nonnull
	private List<Integer> queryPrimaryKeys(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint... constraints
	) {
		final FilterConstraint[] filter = new FilterConstraint[constraints.length + 1];
		filter[0] = scope(scopes);
		System.arraycopy(constraints, 0, filter, 1, constraints.length);
		return this.evita.queryCatalog(
			catalog,
			session -> {
				return session.query(
						query(
							collection(entityType),
							filterBy(filter),
							// every alternative index set and every cacheable subtree must answer the same
							require(
								page(1, PAGE_SIZE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					)
					.getRecordData()
					.stream()
					.map(EntityReference::getPrimaryKey)
					.toList();
			}
		);
	}

	/**
	 * Creates a catalog maintained from its first write, fills it in warm-up, takes it live and then applies the
	 * changes of the live catalog.
	 *
	 * @param catalog     the catalog
	 * @param entityTypes its collections, all maintained
	 * @param warmUp      fills the catalog in warm-up
	 * @param live        changes the live catalog
	 */
	private void build(
		@Nonnull String catalog,
		@Nonnull Set<String> entityTypes,
		@Nonnull Consumer<EvitaSessionContract> warmUp,
		@Nonnull Consumer<EvitaSessionContract> live
	) {
		// TODO JNO - remove at the end of #1615
		this.evita.createCatalogWithVirtualReferencePartitions(catalog, false);
		this.evita.defineCatalog(catalog).updateViaNewSession(this.evita);
		for (final String entityType : entityTypes) {
			maintenance(catalog).setMaintained(entityType, true);
		}
		this.evita.updateCatalog(catalog, warmUp);
		this.evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(catalog, live);
		for (final String entityType : entityTypes) {
			final EntityCollection collection = (EntityCollection) this.evita.getCatalogInstance(catalog)
				.orElseThrow()
				.getCollectionForEntity(entityType)
				.orElseThrow();
			VirtualReferencePartitionOracle.assertConsistent(catalog, collection);
		}
	}

	/**
	 * Returns the collections of a catalog of this test.
	 *
	 * @param catalog the catalog
	 * @return its collections
	 */
	@Nonnull
	private static Set<String> entityTypesOf(@Nonnull String catalog) {
		return switch (catalog) {
			case CATALOG_RELATED -> RelatedProductsShapedFixture.ENTITY_TYPES;
			case CATALOG_SINGLE_OWNER -> SingleOwnerPartitionFixture.ENTITY_TYPES;
			case CATALOG_CROSS_SCOPE -> CrossScopeSingletonFixture.ENTITY_TYPES;
			case CATALOG_EVERY_ROUTE -> SingleOwnerPartitionFixture.EVERY_ROUTE_ENTITY_TYPES;
			default -> throw new IllegalArgumentException(catalog);
		};
	}

	/**
	 * Returns the switch holder of a catalog.
	 *
	 * @param catalog the catalog
	 * @return the switch holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenance(@Nonnull String catalog) {
		return ((Catalog) this.evita.getCatalogInstance(catalog).orElseThrow())
			.getVirtualReferencePartitionMaintenance();
	}

}
