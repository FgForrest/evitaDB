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
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.debug.CacheableVariantsGeneratingVisitor;
import io.evitadb.core.query.algebra.reference.ReferencedOwnerExistenceFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that `referenceHaving` - and the translation of partitions to their targets behind `facetHaving` - answers
 * from the registry rows of single-owner partitions exactly what it answers from index objects holding the same data:
 * every body - the positive bodies answered from the candidate set and the bodies answered exactly: negations,
 * `attributeIs`, `entityPrimaryKeyInSet` under a negation including missing managed targets, `groupHaving`,
 * `entityLocaleEquals` and `inScope` - and for a `referenceHaving` the bidirectional rewrite answering from the
 * counterpart's single-owner partitions.
 *
 * Every catalog is built twice in one engine, as the {@link MaterializedPartitionTwin} describes: the row catalog as
 * the fixture builds it, its single-owner partitions registry rows, and the object catalog with every owner of the
 * queried collection cloned, so every partition of its references is an index object. The answer of the object
 * catalog, mapped back to the originals, is what the row catalog must answer. The fixtures:
 *
 * - {@link RelatedProductsShapedFixture} - thousands of generated owners mixing single-owner and shared partitions,
 *   duplicates, value-less rows, missing managed targets, groups, both scopes, and categories reflecting the products
 *   they hold, some of them through single-owner partitions of the product side;
 * - {@link SingleOwnerPartitionFixture} with the reflected `Category.products` reference and one archived product -
 *   with the products cloned for the cases of the product side and the categories for those of the reflection;
 * - {@link CrossScopeSingletonFixture} - one key held by partitions of both scopes, and a reference whose partitions
 *   are all single-owner;
 * - {@link SingleOwnerPartitionFixture} extended for every read route, with one product of the shared category alone
 *   archived - so the bidirectional rewrite reaches the shared category through a shared live partition and a
 *   single-owner archived one at once.
 *
 * Every case runs in the scope sets its constraints admit, and must select a single-owner partition in at least one
 * of them: the rows its `referenceHaving` body matches, read from the object catalog, must include one whose partition
 * has a single owner in the row catalog - an equal answer of a case selecting no such partition would compare the
 * index objects with themselves.
 *
 * The two verification debug modes run on a representative subset only - the cases marked with the read routes they
 * represent (see {@link ParityCase#verifying(VerifiedRoute, VerifiedRoute...)}) and the routes of
 * {@link #ROUTES_OF_THE_TEST_METHODS}, together covering every {@link VerifiedRoute}; every other case is compared
 * plain. On the whole case list the modes cost several minutes and almost all of it is spent by a handful of negated
 * bodies over the generated catalog: the caching-tree verification re-executes the plan once per cacheable formula,
 * and such a body holds one per materialized partition. The modes run on the row catalog. What they add over the
 * plain comparison:
 *
 * - `VERIFY_POSSIBLE_CACHING_TREES` executes the plan once more for every cacheable formula of its filter tree, with
 *   that formula exchanged for a constant of its result: the formulas above it are recreated with the constant as
 *   their child and computed again, the rest of the tree keeps its results, and the answers are compared. A plain run
 *   recreates nothing - the cache of this test's engine is off. Each verified case must therefore leave the mode a
 *   cacheable formula in a plan reading registry rows, which a bare constant of the owners of the rows does not. A
 *   `facetHaving` is answered by a facet formula that is never cacheable, so no plan holding one has a variant to
 *   verify - the mode leaves the translation of partitions to targets behind it unverified.
 * - `VERIFY_ALTERNATIVE_INDEX_RESULTS` executes the plan of every eligible alternative index set, and in this class
 *   there is none: only a reference indexed for filtering gets registry rows, the reduced-index option of such a
 *   reference is never eligible for a plan of its own, and every reference the cases query is one - so each
 *   planning step, nested ones included, has the global index alone. The mode stays on the verified cases, where it
 *   costs one execution more.
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
	 * The page large enough to hold every entity of every catalog, clones included.
	 */
	private static final int PAGE_SIZE = 100_000;
	/**
	 * The size of the pages the paged cases request - prime, so no page boundary meets a regularity of the fixture.
	 */
	private static final int PAGED_SIZE = 97;
	/**
	 * The read routes a test method verifies under the debug modes rather than a case - the bidirectional rewrite,
	 * whose plans over the generated catalog consist of the existence formula alone and hold no cacheable formula, is
	 * verified by the owner reached through both representations, whose plan leaves a variant recreating it.
	 */
	private static final Set<VerifiedRoute> ROUTES_OF_THE_TEST_METHODS = Collections.unmodifiableSet(
		EnumSet.of(VerifiedRoute.BIDIRECTIONAL_REWRITE)
	);

	private TestPaths paths;
	private Evita evita;
	/**
	 * The twins of the catalogs by {@link #twinKey(String, String)} - one per catalog and cloned collection.
	 */
	private final Map<String, MaterializedPartitionTwin> twins = new HashMap<>(8);

	/**
	 * The read routes of the virtual reading the verification debug modes verify in this class - each must be
	 * represented by a verified case or a test method (see `shouldVerifyEveryRouteUnderTheDebugModes`). The routes
	 * this class leaves to others, with the reason:
	 *
	 * - the translation of partitions to their targets behind `facetHaving` - it is answered by a
	 *   `FacetHavingFormula`, a `NonCacheableFormula`, so no plan holding it leaves the modes a variant, and no
	 *   reference of the class has an alternative index set;
	 * - the fetch routes - this filter class fetches no reference content;
	 * - the hierarchy routes - no case filters or summarizes by a hierarchy;
	 * - the ordering routes - the only ordering by a reference property is the paged case, which pages without them;
	 * - facet summaries, histograms and entity bodies - they read nothing a single-owner partition answers differently
	 *   as a row, and no case asks for them.
	 */
	private enum VerifiedRoute {
		/**
		 * The body of `referenceHaving` evaluated over the candidate partitions.
		 */
		REFERENCE_HAVING_BODY,
		/**
		 * The target index list a `referenceHaving` plans over.
		 */
		REFERENCE_HAVING_TARGET_INDEXES,
		/**
		 * A negation or `attributeIs` inside a reference body.
		 */
		NEGATION_IN_BODY,
		/**
		 * `groupHaving` inside a reference body.
		 */
		GROUP_IN_BODY,
		/**
		 * `entityLocaleEquals` inside a reference body.
		 */
		LOCALE_IN_BODY,
		/**
		 * `entityHaving` / `entityPrimaryKeyInSet` inside a `referenceHaving` body.
		 */
		REFERENCED_ENTITY_IN_BODY,
		/**
		 * A `referenceHaving` answered from the counterpart of a bidirectional reference pair.
		 */
		BIDIRECTIONAL_REWRITE
	}

	/**
	 * A positive parity case - one filter over one collection, compared in each of its scope sets.
	 *
	 * @param name           the name of the case
	 * @param catalog        the row catalog queried
	 * @param entityType     the collection queried, whose owners its twin clones
	 * @param scopeSets      the scope sets the case runs in
	 * @param selectsRows    TRUE when the case must select a single-owner partition in at least one scope set
	 * @param verifiedRoutes the read routes the case represents under the verification debug modes, empty when it is
	 *                       compared plain
	 * @param constraints    the filter constraints following the scope
	 */
	private record ParityCase(
		@Nonnull String name,
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope[][] scopeSets,
		boolean selectsRows,
		@Nonnull Set<VerifiedRoute> verifiedRoutes,
		@Nonnull FilterConstraint... constraints
	) {

		/**
		 * Creates a case compared plain, without the verification debug modes.
		 *
		 * @param name        the name of the case
		 * @param catalog     the row catalog queried
		 * @param entityType  the collection queried, whose owners its twin clones
		 * @param scopeSets   the scope sets the case runs in
		 * @param selectsRows TRUE when the case must select a single-owner partition in at least one scope set
		 * @param constraints the filter constraints following the scope
		 */
		ParityCase(
			@Nonnull String name,
			@Nonnull String catalog,
			@Nonnull String entityType,
			@Nonnull Scope[][] scopeSets,
			boolean selectsRows,
			@Nonnull FilterConstraint... constraints
		) {
			this(name, catalog, entityType, scopeSets, selectsRows, Set.of(), constraints);
		}

		/**
		 * Returns this case verified by the debug modes as the representative of the given read routes.
		 *
		 * @param route  a route the case represents
		 * @param routes further routes the case represents
		 * @return the verified case
		 */
		@Nonnull
		ParityCase verifying(@Nonnull VerifiedRoute route, @Nonnull VerifiedRoute... routes) {
			return new ParityCase(
				this.name, this.catalog, this.entityType, this.scopeSets, this.selectsRows,
				Collections.unmodifiableSet(EnumSet.of(route, routes)), this.constraints
			);
		}

		/**
		 * Tells whether the queries of the case run with the verification debug modes.
		 *
		 * @return TRUE when the case represents a read route under the debug modes
		 */
		boolean isVerified() {
			return !this.verifiedRoutes.isEmpty();
		}

		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * The reference rows a case selects - those the body of its `referenceHaving` or `facetHaving` matches - which
	 * must include a single-owner partition of the row catalog (see
	 * {@link MaterializedPartitionTwin#selectsSingleOwnerRow}).
	 *
	 * @param referenceName the reference the body is evaluated over
	 * @param rowFilter     the body, `null` for an empty one, which matches every row
	 * @param locale        the locale of the case, which localized attributes of the body are read in; `null` for none
	 */
	private record Selection(
		@Nonnull String referenceName,
		@Nullable FilterConstraint rowFilter,
		@Nullable Locale locale
	) {
	}

	/**
	 * What the caching-tree verification has to work with in one plan - the plan the verification debug modes plan,
	 * censused without executing a single variant.
	 *
	 * @param variants           the cacheable variants of the plan's filter tree, each one tree the verification
	 *                           executes
	 * @param carriers           the counterpart existence formulas of the bidirectional rewrite in the plan - one at
	 *                           least when the rewrite answered the query
	 * @param carrierFlattens    the variants exchanging a subtree that holds a counterpart existence formula of the
	 *                           bidirectional rewrite - the existence formula answers through the constant of the
	 *                           subtree's result
	 * @param carrierRecreations the variants exchanging a formula inside a counterpart existence formula - the
	 *                           existence formula is recreated over the constant and computed again
	 */
	private record VariantCensus(int variants, int carriers, int carrierFlattens, int carrierRecreations) {
	}

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("VirtualReferenceHavingParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		build(
			CATALOG_RELATED, RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive,
			Map.of(
				RelatedProductsShapedFixture.ENTITY_PRODUCT,
				new String[]{RelatedProductsShapedFixture.REFERENCE_RELATED, RelatedProductsShapedFixture.REFERENCE_CATEGORIES}
			)
		);
		build(
			CATALOG_SINGLE_OWNER, SingleOwnerPartitionFixture::setUpWithReflectedProducts,
			session -> session.archiveEntity(SingleOwnerPartitionFixture.ENTITY_PRODUCT, 5),
			Map.of(
				SingleOwnerPartitionFixture.ENTITY_PRODUCT,
				new String[]{SingleOwnerPartitionFixture.REFERENCE_CATEGORIES, SingleOwnerPartitionFixture.REFERENCE_GALLERY},
				SingleOwnerPartitionFixture.ENTITY_CATEGORY,
				new String[]{SingleOwnerPartitionFixture.REFERENCE_PRODUCTS}
			)
		);
		build(
			CATALOG_CROSS_SCOPE, CrossScopeSingletonFixture::setUp, CrossScopeSingletonFixture::archive,
			Map.of(
				CrossScopeSingletonFixture.ENTITY_OWNER,
				new String[]{
					CrossScopeSingletonFixture.REFERENCE_ITEMS, CrossScopeSingletonFixture.REFERENCE_MEDIA,
					CrossScopeSingletonFixture.REFERENCE_SOLO
				}
			)
		);
		// the first filler product, held by the shared category alone, moves to the archive - the shared category is
		// then reached through a shared live partition of `categories` and a single-owner archived one at once
		build(
			CATALOG_EVERY_ROUTE, SingleOwnerPartitionFixture::setUpWithEveryReadRoute,
			session -> session.archiveEntity(
				SingleOwnerPartitionFixture.ENTITY_PRODUCT, SingleOwnerPartitionFixture.FIRST_FILLER_PRODUCT
			),
			Map.of(
				SingleOwnerPartitionFixture.ENTITY_PRODUCT, new String[]{SingleOwnerPartitionFixture.REFERENCE_CATEGORIES}
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
			).verifying(VerifiedRoute.REFERENCE_HAVING_BODY, VerifiedRoute.REFERENCE_HAVING_TARGET_INDEXES),
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
			).verifying(VerifiedRoute.REFERENCED_ENTITY_IN_BODY),
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
			).verifying(VerifiedRoute.REFERENCE_HAVING_BODY),
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
		// the categories held by one product each - every partition of theirs is a registry row
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
			).verifying(VerifiedRoute.NEGATION_IN_BODY),
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
			).verifying(VerifiedRoute.NEGATION_IN_BODY, VerifiedRoute.REFERENCED_ENTITY_IN_BODY),
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
			// groupHaving - over `categories`, indexed in the live scope only, whose singleton categories are registry
			// rows; a product's singleton row is in another group and has another priority than its row of a popular
			// category, so a cross-row reading of the conjunction would differ
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
			).verifying(VerifiedRoute.GROUP_IN_BODY),
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
			).verifying(VerifiedRoute.LOCALE_IN_BODY),
			// inScope
			new ParityCase(
				"related: inScope of the live scope", CATALOG_RELATED, relatedProduct, liveAndBoth, true,
				referenceHaving(related, inScope(Scope.LIVE, spares))
			),
			new ParityCase(
				"related: inScope of the archive", CATALOG_RELATED, relatedProduct, archivedAndBoth, true,
				referenceHaving(related, inScope(Scope.ARCHIVED, spares))
			).verifying(VerifiedRoute.REFERENCE_HAVING_BODY),
			new ParityCase(
				"related: not inScope of the live scope", CATALOG_RELATED, relatedProduct, liveAndBoth, true,
				referenceHaving(related, not(inScope(Scope.LIVE, spares)))
			).verifying(VerifiedRoute.NEGATION_IN_BODY),
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
	 * the product side - the products' `categories`, some partitions of which are single-owner registry rows. The
	 * object catalog clones the products, so every partition of `categories` the rewrite reads there is an index
	 * object.
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

	/**
	 * Returns the orderings the accessory owners of the generated catalog are paged through.
	 *
	 * @return the name of the ordering, its constraints and whether the full answer runs under the debug modes
	 */
	@Nonnull
	static Stream<Arguments> pagedOrderings() {
		return Stream.of(
			Arguments.of(
				"by the rank of the owner",
				new OrderConstraint[]{attributeNatural(RelatedProductsShapedFixture.ATTRIBUTE_RANK)},
				true
			),
			// the first related row of an owner in target order carrying an order picks it - a single-owner
			// partition of the row catalog takes part in the walk as its row, and the run its claims form is merged
			// into the page the request asks for
			Arguments.of(
				"by the order of the first related row",
				new OrderConstraint[]{
					referenceProperty(
						RelatedProductsShapedFixture.REFERENCE_RELATED,
						attributeNatural(RelatedProductsShapedFixture.ATTRIBUTE_ORDER)
					),
					attributeNatural(RelatedProductsShapedFixture.ATTRIBUTE_RANK)
				},
				false
			)
		);
	}

	@DisplayName("a body answers from the rows what it answers from the index objects")
	@ParameterizedTest(name = "{0}")
	@MethodSource({"positiveCases", "exactCases"})
	void shouldAnswerABodyAsTheIndexObjectsDo(@Nonnull ParityCase parityCase) {
		assertParity(parityCase);
	}

	@DisplayName("the bidirectional rewrite answers from the counterpart's rows what it answers from its index objects")
	@ParameterizedTest(name = "{0}")
	@MethodSource("bidirectionalCases")
	void shouldAnswerABidirectionalRewriteAsTheIndexObjectsDo(@Nonnull ParityCase parityCase) {
		final MaterializedPartitionTwin twin = twinOf(CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_PRODUCT);
		final Set<Integer> singleOwnerCategories = twin.singleOwnerTargets(
			RelatedProductsShapedFixture.REFERENCE_CATEGORIES, Scope.LIVE
		);
		boolean selectedRows = false;
		for (final Scope[] scopes : parityCase.scopeSets()) {
			final List<Integer> expected = twin.expectedAnswer(
				parityCase.entityType(),
				queryPrimaryKeys(twin.objectCatalog(), parityCase.entityType(), scopes, false, parityCase.constraints())
			);
			assertEquals(
				expected,
				queryPrimaryKeys(twin.rowCatalog(), parityCase.entityType(), scopes, false, parityCase.constraints()),
				parityCase + " in " + Arrays.toString(scopes) + " must answer from the rows what it answers from the " +
					"index objects"
			);
			// a category in the answer held by a single product was reached through the row of that product
			selectedRows |= expected.stream().anyMatch(singleOwnerCategories::contains);
			// both catalogs answer through the rewrite, so the rows and the objects compared are the product side's
			for (final String catalog : new String[]{twin.rowCatalog(), twin.objectCatalog()}) {
				assertTrue(
					planCensus(catalog, parityCase.entityType(), scopes, parityCase.constraints()).carriers() > 0,
					parityCase + " must be answered by the bidirectional rewrite in `" + catalog + "`"
				);
			}
		}
		assertTrue(selectedRows, parityCase + " must answer a category held by a single-owner partition");
	}

	@Test
	@DisplayName("the debug modes verify every read route they can verify in this class")
	void shouldVerifyEveryRouteUnderTheDebugModes() {
		final Set<VerifiedRoute> verifiedRoutes = EnumSet.copyOf(ROUTES_OF_THE_TEST_METHODS);
		Stream.of(positiveCases(), exactCases(), bidirectionalCases())
			.flatMap(Function.identity())
			.forEach(parityCase -> verifiedRoutes.addAll(parityCase.verifiedRoutes()));
		assertEquals(
			EnumSet.allOf(VerifiedRoute.class), verifiedRoutes,
			"Every read route the debug modes can verify must have a case or a test method verified by them"
		);
	}

	@Test
	@DisplayName("the bidirectional rewrite reaches the categories held only by single-owner partitions")
	void shouldReachTheSingletonCategoriesThroughTheRewrite() {
		final MaterializedPartitionTwin twin = twinOf(CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_PRODUCT);
		final String category = RelatedProductsShapedFixture.ENTITY_CATEGORY;
		final FilterConstraint referenceHaving = referenceHaving(RelatedProductsShapedFixture.REFERENCE_PRODUCTS);
		final List<Integer> expected = twin.expectedAnswer(
			category, queryPrimaryKeys(twin.objectCatalog(), category, LIVE, false, referenceHaving)
		);
		final List<Integer> singletonCategories = IntStream.rangeClosed(
			RelatedProductsShapedFixture.POPULAR_CATEGORY_COUNT + 1,
			RelatedProductsShapedFixture.POPULAR_CATEGORY_COUNT + RelatedProductsShapedFixture.SINGLETON_CATEGORY_COUNT
		).boxed().toList();
		assertTrue(expected.containsAll(singletonCategories), expected.toString());
		assertEquals(expected, queryPrimaryKeys(twin.rowCatalog(), category, LIVE, false, referenceHaving));
		// each of them is held by one product alone - a registry row of the row catalog, which the rewrite reads
		assertTrue(
			twin.singleOwnerTargets(RelatedProductsShapedFixture.REFERENCE_CATEGORIES, Scope.LIVE)
				.containsAll(singletonCategories),
			"Every singleton category must be a single-owner partition of the products"
		);
		assertTrue(
			planCensus(twin.rowCatalog(), category, LIVE, referenceHaving).carriers() > 0,
			"The rewrite must answer the categories"
		);
	}

	@Test
	@DisplayName("the bidirectional rewrite unites the rows and the index objects of an owner reached through both")
	void shouldUniteTheMaterializedAndTheVirtualRowsOfOneOwner() {
		final MaterializedPartitionTwin twin = twinOf(CATALOG_EVERY_ROUTE, SingleOwnerPartitionFixture.ENTITY_PRODUCT);
		final String category = SingleOwnerPartitionFixture.ENTITY_CATEGORY;
		final String categories = SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
		// the shared category is the owner holding a shared live partition and a single-owner archived one
		assertFalse(
			twin.singleOwnerTargets(categories, Scope.LIVE).contains(SingleOwnerPartitionFixture.SHARED_CATEGORY),
			"The live partition of the shared category must be shared"
		);
		assertTrue(
			twin.singleOwnerTargets(categories, Scope.ARCHIVED).contains(SingleOwnerPartitionFixture.SHARED_CATEGORY),
			"The archived partition of the shared category must be single-owner"
		);
		final FilterConstraint referenceHaving = referenceHaving(SingleOwnerPartitionFixture.REFERENCE_PRODUCTS);
		long verifiedVariants = 0;
		long carrierVariants = 0;
		for (final Scope[] scopes : new Scope[][]{LIVE, BOTH}) {
			final List<Integer> expected = twin.expectedAnswer(
				category, queryPrimaryKeys(twin.objectCatalog(), category, scopes, false, referenceHaving)
			);
			assertEquals(
				expected, queryPrimaryKeys(twin.rowCatalog(), category, scopes, true, referenceHaving),
				referenceHaving + " in " + Arrays.toString(scopes)
			);
			assertTrue(expected.contains(SingleOwnerPartitionFixture.SHARED_CATEGORY), expected.toString());
			final VariantCensus census = planCensus(twin.rowCatalog(), category, scopes, referenceHaving);
			assertTrue(census.carriers() > 0, "The rewrite must answer the categories in " + Arrays.toString(scopes));
			// the variants verify a plan reading rows only when the answer holds a category held by a single owner
			boolean readsRows = false;
			for (final Scope scope : scopes) {
				readsRows |= expected.stream().anyMatch(twin.singleOwnerTargets(categories, scope)::contains);
			}
			if (readsRows) {
				verifiedVariants += census.variants();
				carrierVariants += census.carrierFlattens() + census.carrierRecreations();
			}
		}
		assertTrue(verifiedVariants > 0, "The rewrite must leave the debug modes a cacheable variant to verify");
		// the test verifies the rewrite for the class, so a variant must reach the existence formula it builds - the
		// one formula of a reading of rows a planned tree tells apart
		assertTrue(
			carrierVariants > 0,
			"The rewrite must leave the debug modes a variant exchanging or recreating its existence formula"
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("pagedOrderings")
	@DisplayName("every page of the accessory owners is the slice of the order the index objects give")
	void shouldPageThroughTheExpectedOrder(
		@Nonnull String name,
		@Nonnull OrderConstraint[] ordering,
		boolean verified
	) {
		final MaterializedPartitionTwin twin = twinOf(CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_PRODUCT);
		final String product = RelatedProductsShapedFixture.ENTITY_PRODUCT;
		final FilterConstraint accessories = attributeEquals(
			RelatedProductsShapedFixture.ATTRIBUTE_RELATION_TYPE, RelatedProductsShapedFixture.TYPE_ACCESSORY
		);
		final FilterConstraint referenceHaving = referenceHaving(RelatedProductsShapedFixture.REFERENCE_RELATED, accessories);
		assertTrue(
			twin.selectsSingleOwnerRow(BOTH, RelatedProductsShapedFixture.REFERENCE_RELATED, accessories, null),
			"The accessory owners must select a single-owner partition"
		);
		// the full order of the object catalog, obtained without a page offset
		final List<Integer> expected = twin.expectedAnswer(
			product, queryOrdered(twin.objectCatalog(), referenceHaving, ordering, 1, PAGE_SIZE, false)
		);
		// the whole order of the row catalog in one page - a defect of an offset leaves it as it is
		assertEquals(
			expected, queryOrdered(twin.rowCatalog(), referenceHaving, ordering, 1, PAGE_SIZE, verified),
			name + ": the whole order"
		);
		MaterializedPartitionTwin.assertPages(
			name, expected, PAGED_SIZE,
			pageNumber -> queryOrdered(twin.rowCatalog(), referenceHaving, ordering, pageNumber, PAGED_SIZE, false)
		);
		if (verified) {
			// the ordering and the page leave the filter tree as it is, so the census of the filter alone stands for it
			assertTrue(
				planCensus(twin.rowCatalog(), product, BOTH, referenceHaving).variants() > 0,
				"The pages must leave the debug modes a cacheable variant to verify"
			);
		}
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
		// owners holding a row next to a row of a shared partition
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
	 * Asserts a case answers from the rows what it answers from the index objects in each of its scope sets, and -
	 * when it must - that it selects a single-owner partition and that some scope set matched an owner. A case verified
	 * by the debug modes must also leave them a cacheable variant of a plan selecting a single-owner partition -
	 * otherwise the modes verified nothing of the reading of rows the case represents.
	 *
	 * @param parityCase the case
	 */
	private void assertParity(@Nonnull ParityCase parityCase) {
		final MaterializedPartitionTwin twin = twinOf(parityCase.catalog(), parityCase.entityType());
		final Selection selection = selectionOf(parityCase.constraints());
		long verifiedVariants = 0;
		boolean selectedRows = false;
		boolean matchedAny = false;
		for (final Scope[] scopes : parityCase.scopeSets()) {
			final List<Integer> expected = twin.expectedAnswer(
				parityCase.entityType(),
				queryPrimaryKeys(twin.objectCatalog(), parityCase.entityType(), scopes, false, parityCase.constraints())
			);
			assertEquals(
				expected,
				queryPrimaryKeys(
					twin.rowCatalog(), parityCase.entityType(), scopes, parityCase.isVerified(), parityCase.constraints()
				),
				parityCase + " in " + Arrays.toString(scopes) + " must answer from the rows what it answers from the " +
					"index objects"
			);
			matchedAny |= !expected.isEmpty();
			final boolean selectsRow = selection != null && twin.selectsSingleOwnerRow(
				scopes, selection.referenceName(), selection.rowFilter(), selection.locale()
			);
			selectedRows |= selectsRow;
			if (parityCase.isVerified() && selectsRow) {
				verifiedVariants += planCensus(
					twin.rowCatalog(), parityCase.entityType(), scopes, parityCase.constraints()
				).variants();
			}
		}
		if (parityCase.selectsRows()) {
			assertTrue(
				selectedRows,
				parityCase + " must select a single-owner partition - a registry row of the row catalog - in at " +
					"least one scope set"
			);
			assertTrue(matchedAny, parityCase + " must match an owner in at least one scope set");
		}
		if (parityCase.isVerified()) {
			assertTrue(
				verifiedVariants > 0,
				parityCase + " is verified by the debug modes, so a plan of it selecting a single-owner partition " +
					"must leave them a cacheable variant to verify"
			);
		}
	}

	/**
	 * Derives the rows a case selects from its constraints: the reference and the body of the first `referenceHaving`
	 * or `facetHaving` found depth first - inside a negation or a disjunction too - with the locale of a top-level
	 * `entityLocaleEquals`.
	 *
	 * @param constraints the filter constraints of the case
	 * @return the selection, `null` when the case holds neither constraint
	 */
	@Nullable
	private static Selection selectionOf(@Nonnull FilterConstraint[] constraints) {
		Locale locale = null;
		for (final FilterConstraint constraint : constraints) {
			if (constraint instanceof EntityLocaleEquals localeEquals) {
				locale = localeEquals.getLocale();
			}
		}
		for (final FilterConstraint constraint : constraints) {
			final Selection selection = findSelection(constraint, locale);
			if (selection != null) {
				return selection;
			}
		}
		return null;
	}

	/**
	 * Finds the first `referenceHaving` or `facetHaving` in a constraint tree, depth first.
	 *
	 * @param constraint the root of the tree
	 * @param locale     the locale of the case, `null` for none
	 * @return the selection of the constraint found, `null` when the tree holds none
	 */
	@Nullable
	private static Selection findSelection(@Nonnull FilterConstraint constraint, @Nullable Locale locale) {
		if (constraint instanceof ReferenceHaving referenceHaving) {
			return new Selection(referenceHaving.getReferenceName(), bodyOf(referenceHaving.getChildren()), locale);
		} else if (constraint instanceof FacetHaving facetHaving) {
			return new Selection(facetHaving.getReferenceName(), bodyOf(facetHaving.getChildren()), locale);
		} else if (constraint instanceof ConstraintContainer<?> container) {
			for (final Object child : container.getChildren()) {
				if (child instanceof FilterConstraint filterConstraint) {
					final Selection selection = findSelection(filterConstraint, locale);
					if (selection != null) {
						return selection;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Returns the body of a `referenceHaving` or `facetHaving` as one constraint.
	 *
	 * @param children the children of the constraint
	 * @return `null` for no child, the child for one, their conjunction for more
	 */
	@Nullable
	private static FilterConstraint bodyOf(@Nonnull FilterConstraint[] children) {
		return children.length == 0 ? null : children.length == 1 ? children[0] : and(children);
	}

	/**
	 * Plans a query the way the verification debug modes plan it - under the planning policy they share with
	 * `PREFER_INDEX_SCAN`, which favours bitmaps and bypasses the cache - computes its filter tree and runs the
	 * engine's own generator of cacheable variants over it, without executing a variant. The planning context takes
	 * the engine's own session only, which the engine hands to the logic of a read-write session; the session writes
	 * nothing, so its transaction commits nothing and the catalog version stays.
	 *
	 * @param catalog     the catalog
	 * @param entityType  the collection
	 * @param scopes      the scopes
	 * @param constraints the filter constraints following the scope
	 * @return the census of the plan
	 */
	@Nonnull
	private VariantCensus planCensus(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint... constraints
	) {
		final EntityCollection entityCollection = (EntityCollection) this.evita.getCatalogInstance(catalog)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
		final Query planned = query(
			collection(entityType),
			filterBy(filterOf(scopes, constraints)),
			require(page(1, PAGE_SIZE), debug(DebugMode.PREFER_INDEX_SCAN))
		);
		return this.evita.updateCatalog(
			catalog,
			session -> {
				final QueryPlanningContext context = entityCollection.createQueryContext(
					new EvitaRequest(planned.normalizeQuery(), OffsetDateTime.now(), EntityReference.class, null),
					session
				);
				final Formula filter = QueryPlanner.planQuery(context).getFilter();
				try (final QueryExecutionContext executionContext = context.createExecutionContext()) {
					// the verification derives the variants from the computed tree of the plan it executed first
					filter.initialize(executionContext);
					filter.compute();
					final CacheableVariantsGeneratingVisitor generator = new CacheableVariantsGeneratingVisitor();
					filter.accept(generator);
					final List<Formula> variants = generator.getFormulaVariants();
					final Set<Formula> carriers = counterpartExistenceFormulasOf(filter);
					int carrierFlattens = 0;
					int carrierRecreations = 0;
					if (!carriers.isEmpty()) {
						for (final Formula variant : variants) {
							// the cloner keeps every formula off the path to the exchanged one, so a variant
							// missing an existence formula of the plan either left it out or recreated it
							final Set<Formula> variantCarriers = counterpartExistenceFormulasOf(variant);
							if (variantCarriers.size() < carriers.size()) {
								carrierFlattens++;
							} else if (!variantCarriers.containsAll(carriers)) {
								carrierRecreations++;
							}
						}
					}
					return new VariantCensus(variants.size(), carriers.size(), carrierFlattens, carrierRecreations);
				}
			}
		);
	}

	/**
	 * Returns the counterpart existence formulas of the bidirectional rewrite in a formula tree, by identity.
	 *
	 * @param formula the root of the tree
	 * @return the existence formulas of the tree
	 */
	@Nonnull
	private static Set<Formula> counterpartExistenceFormulasOf(@Nonnull Formula formula) {
		final Set<Formula> result = Collections.newSetFromMap(new IdentityHashMap<>());
		collectCounterpartExistenceFormulas(formula, result);
		return result;
	}

	/**
	 * Collects the counterpart existence formulas of the bidirectional rewrite in a formula tree.
	 *
	 * @param formula the root of the tree
	 * @param result  the collected existence formulas
	 */
	private static void collectCounterpartExistenceFormulas(@Nonnull Formula formula, @Nonnull Set<Formula> result) {
		if (formula instanceof ReferencedOwnerExistenceFormula) {
			result.add(formula);
		}
		for (final Formula innerFormula : formula.getInnerFormulas()) {
			collectCounterpartExistenceFormulas(innerFormula, result);
		}
	}

	/**
	 * Queries the primary keys of the entities of a collection matching the constraints in the scopes.
	 *
	 * @param catalog     the catalog
	 * @param entityType  the collection
	 * @param scopes      the scopes
	 * @param verified    TRUE to run the query with the verification debug modes
	 * @param constraints the filter constraints following the scope
	 * @return the primary keys in the order the query returns them
	 */
	@Nonnull
	private List<Integer> queryPrimaryKeys(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope[] scopes,
		boolean verified,
		@Nonnull FilterConstraint... constraints
	) {
		final Query theQuery = query(
			collection(entityType),
			filterBy(filterOf(scopes, constraints)),
			verified ?
				// every alternative index set and every cacheable subtree must answer the same
				require(
					page(1, PAGE_SIZE),
					debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
				) :
				require(page(1, PAGE_SIZE))
		);
		return this.evita.queryCatalog(
			catalog,
			session -> {
				return session.query(theQuery, EntityReference.class)
					.getRecordData()
					.stream()
					.map(EntityReference::getPrimaryKey)
					.toList();
			}
		);
	}

	/**
	 * Queries one page of the products of the generated catalog in both scopes matching a filter, in an ordering. A
	 * query without the debug modes prefers the index scan, so an ordering by a reference property sorts through the
	 * indexes - the rows of the single-owner partitions among them - rather than through prefetched entities.
	 *
	 * @param catalog    the catalog
	 * @param filter     the filter following the scope
	 * @param ordering   the ordering
	 * @param pageNumber the page, counted from one
	 * @param pageSize   the size of the page
	 * @param verified   TRUE to run the query with the verification debug modes
	 * @return the primary keys of the page in the order the query returns them
	 */
	@Nonnull
	private List<Integer> queryOrdered(
		@Nonnull String catalog,
		@Nonnull FilterConstraint filter,
		@Nonnull OrderConstraint[] ordering,
		int pageNumber,
		int pageSize,
		boolean verified
	) {
		final Query theQuery = query(
			collection(RelatedProductsShapedFixture.ENTITY_PRODUCT),
			filterBy(scope(BOTH), filter),
			orderBy(ordering),
			require(
				page(pageNumber, pageSize),
				verified ?
					debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES) :
					debug(DebugMode.PREFER_INDEX_SCAN)
			)
		);
		return this.evita.queryCatalog(
			catalog,
			session -> {
				return session.query(theQuery, EntityReference.class)
					.getRecordData()
					.stream()
					.map(EntityReference::getPrimaryKey)
					.toList();
			}
		);
	}

	/**
	 * Returns the filter constraints of a query - the scope followed by the constraints.
	 *
	 * @param scopes      the scopes
	 * @param constraints the filter constraints following the scope
	 * @return the filter constraints
	 */
	@Nonnull
	private static FilterConstraint[] filterOf(@Nonnull Scope[] scopes, @Nonnull FilterConstraint... constraints) {
		final FilterConstraint[] filter = new FilterConstraint[constraints.length + 1];
		filter[0] = scope(scopes);
		System.arraycopy(constraints, 0, filter, 1, constraints.length);
		return filter;
	}

	/**
	 * Builds the row catalog and the twins of one fixture: fills the row catalog in warm-up, takes it live and applies
	 * the changes of the live catalog, then builds one object catalog per cloned collection the same way.
	 *
	 * @param catalog        the row catalog
	 * @param warmUp         fills a catalog in warm-up
	 * @param live           changes a live catalog
	 * @param readReferences the references the cases read, by the collection owning them, whose owners a twin clones
	 */
	private void build(
		@Nonnull String catalog,
		@Nonnull Consumer<EvitaSessionContract> warmUp,
		@Nonnull Consumer<EvitaSessionContract> live,
		@Nonnull Map<String, String[]> readReferences
	) {
		MaterializedPartitionTwin.createRowCatalog(this.evita, catalog, warmUp, live);
		for (final Map.Entry<String, String[]> entry : new TreeMap<>(readReferences).entrySet()) {
			this.twins.put(
				twinKey(catalog, entry.getKey()),
				MaterializedPartitionTwin.create(this.evita, catalog, warmUp, live, entry.getKey(), entry.getValue())
			);
		}
	}

	/**
	 * Returns the twin of a row catalog cloning a collection.
	 *
	 * @param catalog   the row catalog
	 * @param ownerType the cloned collection
	 * @return the twin
	 */
	@Nonnull
	private MaterializedPartitionTwin twinOf(@Nonnull String catalog, @Nonnull String ownerType) {
		final MaterializedPartitionTwin twin = this.twins.get(twinKey(catalog, ownerType));
		assertTrue(twin != null, "No twin of `" + catalog + "` clones `" + ownerType + "`");
		return twin;
	}

	/**
	 * Returns the key of the twin of a row catalog cloning a collection.
	 *
	 * @param catalog   the row catalog
	 * @param ownerType the cloned collection
	 * @return the key
	 */
	@Nonnull
	private static String twinKey(@Nonnull String catalog, @Nonnull String ownerType) {
		return catalog + "/" + ownerType;
	}

}
