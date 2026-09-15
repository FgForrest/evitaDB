/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared, fully deterministic fixture for the *bidirectional reference rewrite* test family and for the
 * `attributeIs(NULL)` planning-skip family that rides on the same data.
 *
 * ## Why this fixture is shaped the way it is
 *
 * The rewrite the family exercises replaces an owner-side evaluation of `referenceHaving(<reference>, ...)` — which
 * opens one reduced index per *owner* — with a counterpart-side evaluation that opens one reduced index per
 * *candidate owner discovered on the other end of the relation*. It only pays off when the two ends of the relation
 * are wildly asymmetric, and the planner therefore gates it on `candidates * 4 <= buckets`.
 *
 * **The 11-versus-230 asymmetry is the entire point of this fixture.** `CATEGORY` is deliberately tiny (12 rows) and
 * `PRODUCT` deliberately large (240 rows), mirroring the production topology the optimisation was written for — a
 * small, hierarchical, reflected side pointing at a large original side. Asked from `collection(CATEGORY)` the gate
 * reads `11 * 4 = 44 <= 230` and the rewrite **fires**; asked from `collection(PRODUCT)` — the very same relation,
 * read from the other end — it reads `230 * 4 = 920 > 11` and the rewrite **declines**. One dataset therefore yields
 * both a rewritten and a non-rewritten evaluation of the same relation, which is what lets the correctness rows use
 * the un-rewritten direction as an oracle for the rewritten one, and what gives the `attributeIs(NULL)` family a
 * guaranteed un-rewritten path with no extra schema of its own.
 *
 * Shrinking the fixture "for speed" silently destroys that: every row that claims to exercise the rewrite would
 * quietly fall through to the ordinary path and still pass its result assertion. That is why the `@DataSet` method
 * ends with a block of {@link org.junit.jupiter.api.Assertions#assertEquals} guards on exactly the counts the gate
 * arithmetic is built from — a shrunken fixture must fail loudly at setup time, not lie at assertion time.
 *
 * ## Other deliberate asymmetries encoded here
 *
 * - **Scopes.** Category 12 is `ARCHIVED` and reachable only from *live* products 1-10; products 231-240 are
 *   `ARCHIVED` and are the only rows reaching *live* category 11. The two directions of that cross-scope asymmetry
 *   are what the archived-owner rows pin. Every reference is declared `indexedInScope(LIVE, ARCHIVED)` because a
 *   cross-scope relation survives only when both schemas are indexed in every scope it spans.
 *
 *   **Measured, and load-bearing for anyone writing an archived-scope row:** that declaration is honoured for
 *   *original* references but not for *reflected* ones. Archived category 12 still carries its ten reflected
 *   `products` rows in its body, and the schema reports `indexedInScopes=[LIVE, ARCHIVED]`, yet no ARCHIVED
 *   `CATEGORY.products` type index exists at all — while `CATEGORY.curated` and `CATEGORY.plainProducts`, original
 *   references on that same archived category, do get one. Each half of a cross-scope relation is filed under the
 *   scope of the entity whose rows it holds, so the counterpart half `PRODUCT.(categories, 12)` sits in the LIVE
 *   family with all ten owning products, and the owner half is filed nowhere.
 * - **`taxonomy`.** The category-to-taxonomy assignment is load-bearing and must not be "simplified": exactly four
 *   live categories sit under taxonomy node 1, which keeps the hierarchy index option under its
 *   `mainIndexCardinality / 2` eligibility threshold (11 live categories, threshold 5, `4 <= 5`) so the hierarchy
 *   plan is actually eligible and the hierarchy post-processor can arm.
 * - **`orphanCategories` / `orphanProducts`.** Declared in the schema, never written to. With zero rows no
 *   counterpart type index exists at all, which is the only way to exercise the "declines cleanly when the
 *   counterpart index is absent" branch.
 * - **`weakTags` / `weakProducts`.** Indexed for filtering only — no partitioning — to prove the rewrite does not
 *   silently require a partitioned index.
 * - **`plainProducts`.** An original reference with no reflected counterpart at all, so the rewrite has nothing to
 *   find.
 * - **`taxonomyStats`.** Declared on *both* collections under the same name, neither end reflected — the shared
 *   name is what makes the reference-name collision in the hierarchy-statistics strip reachable. The `CATEGORY`
 *   end is deliberately `indexedForFiltering` only: a statistics reference that is partitioned in every requested
 *   scope routes the planner down the constraint-tree fallback, which strips a nested `hierarchyWithin`
 *   identically on both sides of a paired oracle and makes the row pass for no reason. The product assignment is
 *   correlated with the round-robin category on purpose so that each category's whole product block sits inside
 *   one taxonomy subtree.
 * - **`scopedCategories` / `scopedProducts`.** The only reference in the fixture that is *not* usable in every
 *   scope: the original is indexed in both, the reflected end in `LIVE` only. That asymmetry is what lets a row
 *   assert `ReferenceNotIndexedException` still surfaces naming the owner reference, and the orientation is the
 *   only one that works — see the comment on the declaration.
 * - **`note` / `ownNote`.** The original end declares `note` and the reflected end excludes it from inheritance and
 *   declares its own attribute instead, so the two ends carry unrelated values — `"o" + p` against `"r" + c`. A
 *   rewrite that resolved an attribute against the wrong end of the relation therefore returns a visibly wrong set
 *   rather than throwing.
 *
 *   **VERIFY-1, answered: the reflected end's own attribute may NOT reuse the excluded name.** Declaring
 *   `withAttributesInheritedExcept("note")` together with `withAttribute("note", ...)` is rejected outright with
 *   `InvalidSchemaMutationException: Attribute inherited from original reference \`categories\` in entity type
 *   \`PRODUCT\` cannot be modified directly via. reflected reference schema!` — the exclusion removes the attribute
 *   from *inheritance*, but the name still resolves against the original reference schema, so any `withAttribute`
 *   carrying it is read as an attempt to modify the inherited one. The reflected end therefore declares
 *   `ownNote` ({@link #REF_ATTR_OWN_NOTE}) instead.
 * - **`label`.** Localized, with the German value present only on even products, so a locale that fails to survive
 *   the rewrite's isolated formula stack shows up as a wrong result set rather than an exception.
 *
 * The data is built with plain loops over primary keys — no `DataGenerator`, no `Faker`, no `Random`, no seed — so
 * every count above can be re-derived by reading this class rather than by running it.
 *
 * ## Three places where the schema API forced the fixture's hand
 *
 * - **`code` is declared `unique()` only, never `unique().filterable()`.** evitaDB rejects the pair outright
 *   (`InvalidSchemaMutationException: Attribute \`code\` cannot be both unique and filterable. Unique attributes are
 *   implicitly filterable!`), so the intended "unique **and** filterable" semantics are obtained by declaring only
 *   uniqueness.
 * - **`label` is declared `nullable()`.** A non-nullable localized reference attribute must be present in every
 *   locale its owner carries, and the German value is deliberately set on even products only — categories 1-7 carry
 *   German and would then reject every reflected row inherited from an odd product.
 * - **The reflected end's own note is `ownNote`, not `note`** — see the `note` / `ownNote` bullet above. Writing
 *   `ownNote` onto the materialised reflected rows *is* accepted at the data level; only the schema-level name
 *   collision is refused.
 *
 * A finding worth keeping rather than treating as a mere workaround: the hazard that the reflected-reference
 * inheritance check defends against — the same attribute name carrying unrelated values on the two ends of one
 * relation — appears to be **unconstructible through the schema API**, because the name stays reserved even after
 * `withAttributesInheritedExcept` removes it from inheritance. That check is therefore defensive rather than
 * load-bearing at the functional level; it is covered directly by the mock-level unit test, and nobody should
 * later read it as dead code.
 *
 * Concrete test rows live in the subclasses; this class carries no test methods of its own. The dataset is
 * discovered through the superclass walk in {@link EvitaParameterResolver}, so subclasses only need
 * `@UseDataSet(BIDI_REWRITE)` on their methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
public abstract class AbstractBidirectionalReferenceRewriteFunctionalTest {

	public static final String BIDI_REWRITE = "bidiRewrite";

	// collections (Entities.CATEGORY / PRODUCT / BRAND come from io.evitadb.test.Entities)
	public static final String ENTITY_TAXONOMY = "TAXONOMY";

	// references declared on PRODUCT
	public static final String REF_PRODUCT_CATEGORIES = "categories";       // original  -> CATEGORY
	public static final String REF_PRODUCT_CURATED_BY = "curatedBy";        // reflected of CATEGORY.curated
	public static final String REF_PRODUCT_VARIANTS = "variants";           // original  -> CATEGORY, DUPLICATES
	public static final String REF_PRODUCT_WEAK_TAGS = "weakTags";          // original  -> CATEGORY, filtering-only
	public static final String REF_PRODUCT_ORPHAN_CATEGORIES = "orphanCategories"; // original -> CATEGORY, ZERO ROWS
	public static final String REF_PRODUCT_BRAND = "brand";                 // original  -> BRAND, faceted
	public static final String REF_PRODUCT_TAXONOMY = "taxonomy";           // original  -> TAXONOMY
	// original -> CATEGORY, indexed in BOTH scopes; its reflected counterpart is LIVE-only on purpose
	public static final String REF_PRODUCT_SCOPED_CATEGORIES = "scopedCategories";

	// references declared on CATEGORY
	public static final String REF_CATEGORY_PRODUCTS = "products";          // reflected of PRODUCT.categories
	public static final String REF_CATEGORY_CURATED = "curated";            // original  -> PRODUCT
	public static final String REF_CATEGORY_VARIANT_PRODUCTS = "variantProducts"; // reflected of PRODUCT.variants
	public static final String REF_CATEGORY_WEAK_PRODUCTS = "weakProducts"; // reflected of PRODUCT.weakTags
	public static final String REF_CATEGORY_PLAIN_PRODUCTS = "plainProducts"; // original -> PRODUCT, NO reflection
	public static final String REF_CATEGORY_ORPHAN_PRODUCTS = "orphanProducts"; // reflected of PRODUCT.orphanCategories
	public static final String REF_CATEGORY_BRAND = "brand";                // original  -> BRAND, faceted
	public static final String REF_CATEGORY_TAXONOMY = "taxonomy";          // original  -> TAXONOMY
	// reflected of PRODUCT.scopedCategories - indexed in LIVE ONLY, deliberately
	public static final String REF_CATEGORY_SCOPED_PRODUCTS = "scopedProducts";

	// declared on BOTH collections under the same name, neither one reflected - the shared name is the point
	public static final String REF_TAXONOMY_STATS = "taxonomyStats";

	// reference attributes on PRODUCT.categories (inherited by CATEGORY.products except `note`)
	public static final String REF_ATTR_RELEVANCE = "relevance";            // Long, filterable + sortable
	public static final String REF_ATTR_NOTE = "note";                      // String, filterable - NOT inherited
	// String, filterable - the reflected end's OWN note (see VERIFY-1 in the class JavaDoc)
	public static final String REF_ATTR_OWN_NOTE = "ownNote";
	public static final String REF_ATTR_LABEL = "label";                    // String, filterable, LOCALIZED
	public static final String REF_ATTR_ALWAYS_SET = "refAlwaysSet";        // String, filterable, on EVERY row
	public static final String REF_ATTR_SOMETIMES_SET = "refSometimesSet";  // Long, filterable, nullable

	public static final String REF_ATTR_RANK = "rank";                      // Long filterable, on CATEGORY.curated
	public static final String REF_ATTR_WEAK = "weakAttr";                  // Long filterable, on PRODUCT.weakTags
	// Long filterable, on CATEGORY.plainProducts
	public static final String REF_ATTR_PLAIN = "plainAttr";
	// String filterable REPRESENTATIVE, on PRODUCT.variants
	public static final String REF_ATTR_VARIANT_TAG = "variantTag";

	// entity attributes on CATEGORY
	public static final String ATTR_CODE = "code";                          // String, unique + filterable
	public static final String ATTR_ACTIVE = "active";                      // Boolean, filterable
	public static final String ATTR_ALWAYS_SET = "alwaysSet";               // String, filterable, on ALL 12
	public static final String ATTR_SOMETIMES_SET = "sometimesSet";         // Long, filterable, on 8 of 12
	public static final String ATTR_LOCALIZED_LABEL = "localizedLabel";     // String, filterable, localized
	public static final String ATTR_UNIQUE_SOMETIMES = "uniqueSometimes";   // String, unique, on 5 of 12

	// entity attributes on PRODUCT
	//   ATTR_CODE (String unique filterable, all)
	public static final String ATTR_PRODUCT_ACTIVE = "productActive";       // Boolean, filterable

	// counts
	public static final int TAXONOMY_COUNT = 6;
	public static final int CATEGORY_COUNT = 12;
	public static final int PRODUCT_COUNT = 240;
	public static final int BRAND_COUNT = 8;
	public static final int ARCHIVED_CATEGORY_PK = 12;
	public static final int FIRST_ARCHIVED_PRODUCT_PK = 231;  // 231..240 archived

	/**
	 * Primary key of the single live category that no live product points at - it is reachable through
	 * `CATEGORY.products` only from the archived products 231-240.
	 */
	public static final int CROSS_SCOPE_CATEGORY_PK = 11;

	/**
	 * Number of categories the round-robin `PRODUCT.categories` assignment spreads live products over. Categories
	 * 11 and 12 are deliberately excluded from it - they carry the cross-scope rows instead.
	 */
	public static final int ROUND_ROBIN_CATEGORY_COUNT = 10;

	/**
	 * Number of products each category curates through `CATEGORY.curated`. The twelve blocks are disjoint and cover
	 * all 240 products.
	 */
	public static final int CURATED_PRODUCTS_PER_CATEGORY = 20;

	/**
	 * Number of products each category claims through `CATEGORY.plainProducts` - the original reference that has no
	 * reflected counterpart.
	 */
	public static final int PLAIN_PRODUCTS_PER_CATEGORY = 5;

	/**
	 * Highest product primary key carrying `PRODUCT.variants` rows. Each of these products carries three rows, two of
	 * which point at the same category and are told apart only by the representative `variantTag`.
	 */
	public static final int LAST_VARIANT_PRODUCT_PK = 30;

	/**
	 * Number of categories the `PRODUCT.variants` rows spread over.
	 */
	public static final int VARIANT_CATEGORY_COUNT = 2;

	/**
	 * Category whose two `variants` partitions hold **disjoint** product sets, unlike category 1 whose `a` and `b`
	 * partitions both hold every variant product.
	 *
	 * Without it no query can tell a rewrite that keeps every qualifying reduced index from one that keeps only the
	 * first: on category 1 the two partitions carry identical bitmaps, so dropping either changes no answer.
	 */
	public static final int DISJOINT_VARIANT_CATEGORY_PK = 3;
	/**
	 * Product carrying the `x` partition of {@link #DISJOINT_VARIANT_CATEGORY_PK}, and no other row on that category.
	 */
	public static final int DISJOINT_VARIANT_X_PRODUCT_PK = 1;
	/**
	 * Product carrying the `y` partition of {@link #DISJOINT_VARIANT_CATEGORY_PK}, and no other row on that category.
	 */
	public static final int DISJOINT_VARIANT_Y_PRODUCT_PK = 2;

	/**
	 * Highest product primary key carrying a `PRODUCT.weakTags` row.
	 */
	public static final int LAST_WEAK_TAG_PRODUCT_PK = 60;

	/**
	 * Number of categories the `PRODUCT.weakTags` rows spread over.
	 */
	public static final int WEAK_TAG_CATEGORY_COUNT = 6;

	/**
	 * Taxonomy nodes covered by `hierarchyWithin(REF_CATEGORY_TAXONOMY, node(1))` - the subtree `1 -> {2, 3}`.
	 */
	public static final Set<Integer> TAXONOMY_SUBTREE_OF_NODE_1 = Set.of(1, 2, 3);

	/**
	 * Brand that is referenced by at least one category **and** at least one product, so the very same facet primary
	 * key exists in the `CATEGORY.brand` and `PRODUCT.brand` namespaces at once.
	 */
	public static final int SHARED_BRAND_PK = 7;

	/**
	 * Builds the shared `BIDI_REWRITE` catalog and returns the four fully fetched entity lists the test rows compute
	 * their expected sets from.
	 *
	 * The write order is not incidental:
	 *
	 * 1. `TAXONOMY` and `BRAND` schemas, then *empty* `CATEGORY` and `PRODUCT` collections, so that every managed
	 *    reference declared afterwards resolves against a collection that already exists;
	 * 2. the full `PRODUCT` schema, then the full `CATEGORY` schema (its reflected references need the original
	 *    references on `PRODUCT` to exist, while `PRODUCT.curatedBy` tolerates a `CATEGORY.curated` that does not
	 *    exist yet);
	 * 3. taxonomy nodes, brands, categories, then products - writing a product materialises the reflected
	 *    `CATEGORY.products`, `variantProducts` and `weakProducts` rows;
	 * 4. a second pass over the categories adds `curated` and `plainProducts` (materialising the reflected
	 *    `PRODUCT.curatedBy` rows) and stamps the reflected end's own, non-inherited `note`;
	 * 5. **archiving happens last**, so that every cross-scope relation is established while both of its ends are
	 *    still live - the archived rows are what the scope-asymmetry rows are built on.
	 *
	 * @param evita the evitaDB instance the dataset is built in
	 * @return carrier with `originalCategories`, `originalProducts`, `originalBrands` and `originalTaxonomy`
	 */
	@Nonnull
	@DataSet(value = BIDI_REWRITE)
	protected DataCarrier setUpBidirectionalReferenceRewriteDataSet(@Nonnull Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createSchema(session);

				for (int taxonomyPk = 1; taxonomyPk <= TAXONOMY_COUNT; taxonomyPk++) {
					writeTaxonomyNode(session, taxonomyPk);
				}
				for (int brandPk = 1; brandPk <= BRAND_COUNT; brandPk++) {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, brandPk));
				}
				for (int categoryPk = 1; categoryPk <= CATEGORY_COUNT; categoryPk++) {
					writeCategory(session, categoryPk);
				}
				for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
					writeProduct(session, productPk);
				}
				for (int categoryPk = 1; categoryPk <= CATEGORY_COUNT; categoryPk++) {
					writeCategoryToProductRelations(session, categoryPk);
				}

				// archiving is the very last step - see the JavaDoc above
				session.archiveEntity(Entities.CATEGORY, ARCHIVED_CATEGORY_PK);
				for (int productPk = FIRST_ARCHIVED_PRODUCT_PK; productPk <= PRODUCT_COUNT; productPk++) {
					session.archiveEntity(Entities.PRODUCT, productPk);
				}

				final List<SealedEntity> categories = fetchAllInBothScopes(session, Entities.CATEGORY);
				final List<SealedEntity> products = fetchAllInBothScopes(session, Entities.PRODUCT);
				final List<SealedEntity> brands = fetchAllInBothScopes(session, Entities.BRAND);
				final List<SealedEntity> taxonomy = fetchAllInBothScopes(session, ENTITY_TAXONOMY);

				assertFixtureGates(categories, products, brands, taxonomy);

				return new DataCarrier(
					"originalCategories", categories,
					"originalProducts", products,
					"originalBrands", brands,
					"originalTaxonomy", taxonomy
				);
			}
		);
	}

	/**
	 * Returns the taxonomy node a category points at through `CATEGORY.taxonomy`.
	 *
	 * This assignment is load-bearing and must not be "simplified": categories 1-4 land under taxonomy node 1
	 * (`1 -> {2, 3}`) and categories 5-12 land outside it, which is what makes an over-wide hierarchy result visible
	 * and what keeps the hierarchy index option eligible.
	 *
	 * @param categoryPk primary key of the category, 1..12
	 * @return primary key of the taxonomy node, 1..6
	 */
	protected static int taxonomyOfCategory(int categoryPk) {
		if (categoryPk <= 2) {
			return 2;
		} else if (categoryPk <= 4) {
			return 3;
		} else if (categoryPk <= 8) {
			return 5;
		} else if (categoryPk <= CATEGORY_COUNT) {
			return 6;
		} else {
			throw new IllegalArgumentException(
				"Category primary key `" + categoryPk + "` is out of the fixture range!"
			);
		}
	}

	/**
	 * Returns the distinct referenced primary keys a reference of the given name reaches from the passed entities.
	 * This is the quantity both sides of the rewrite gate are measured in - "candidates" when read off the
	 * counterpart's rows, "buckets" when read off the owner's own rows.
	 *
	 * @param entities      entities to collect the reference rows from
	 * @param referenceName name of the reference to collect
	 * @return distinct referenced primary keys, never null
	 */
	@Nonnull
	protected static Set<Integer> referencedPrimaryKeys(
		@Nonnull List<SealedEntity> entities,
		@Nonnull String referenceName
	) {
		return entities.stream()
			.flatMap(entity -> entity.getReferences(referenceName).stream())
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
	}

	/**
	 * Returns the total number of reference rows of the given name present on the passed entities. Unlike
	 * {@link #referencedPrimaryKeys(List, String)} duplicate rows pointing at the same primary key are counted
	 * separately, which is what the `variants` guard needs.
	 *
	 * @param entities      entities to count the reference rows on
	 * @param referenceName name of the reference to count
	 * @return number of reference rows
	 */
	protected static int referenceRowCount(
		@Nonnull List<SealedEntity> entities,
		@Nonnull String referenceName
	) {
		return entities.stream()
			.mapToInt(entity -> entity.getReferences(referenceName).size())
			.sum();
	}

	/**
	 * Returns the subset of the passed entities that resides in the given scope.
	 *
	 * @param entities entities to filter
	 * @param scope    scope the returned entities must reside in
	 * @return entities in the requested scope, never null
	 */
	@Nonnull
	protected static List<SealedEntity> inScope(@Nonnull List<SealedEntity> entities, @Nonnull Scope scope) {
		return entities.stream()
			.filter(entity -> entity.getScope() == scope)
			.toList();
	}

	/**
	 * Declares every collection, attribute and reference of the fixture.
	 *
	 * `CATEGORY` and `PRODUCT` are created empty first so that both collections exist before any managed reference
	 * between them is declared; `PRODUCT` is then fully declared before `CATEGORY`, because `CATEGORY`'s reflected
	 * references need the original references they reflect to be present, while `PRODUCT.curatedBy` tolerates a
	 * `CATEGORY.curated` that does not exist yet.
	 *
	 * Every reference is indexed in **both** scopes: a relation that spans the live and the archived scope is kept
	 * only when both participating schemas are indexed in every scope it spans, and the archived rows of this fixture
	 * are precisely such relations.
	 *
	 * @param session session to declare the schemas through
	 */
	private static void createSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_TAXONOMY)
			.withoutGeneratedPrimaryKey()
			.withHierarchy()
			.updateVia(session);

		session.defineEntitySchema(Entities.BRAND)
			.withoutGeneratedPrimaryKey()
			.updateVia(session);

		session.defineEntitySchema(Entities.CATEGORY)
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.updateVia(session);

		session.defineEntitySchema(Entities.PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.updateVia(session);

		session.defineEntitySchema(Entities.PRODUCT)
			// `unique()` is implicitly filterable and declaring both is rejected - see the class JavaDoc
			.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::unique)
			.withAttribute(ATTR_PRODUCT_ACTIVE, Boolean.class, thatIs -> thatIs.filterableInScope(Scope.values()))
			.withReferenceToEntity(
				REF_PRODUCT_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.withAttribute(
						REF_ATTR_RELEVANCE, Long.class,
						thatIs -> thatIs.filterableInScope(Scope.values()).sortableInScope(Scope.values())
					)
					.withAttribute(
						REF_ATTR_NOTE, String.class,
						thatIs -> thatIs.filterableInScope(Scope.values())
					)
					.withAttribute(
						REF_ATTR_LABEL, String.class,
						thatIs -> thatIs.filterableInScope(Scope.values()).localized().nullable()
					)
					.withAttribute(
						REF_ATTR_ALWAYS_SET, String.class,
						thatIs -> thatIs.filterableInScope(Scope.values())
					)
					.withAttribute(
						REF_ATTR_SOMETIMES_SET, Long.class,
						thatIs -> thatIs.filterableInScope(Scope.values()).nullable()
					)
			)
			.withReferenceToEntity(
				REF_PRODUCT_VARIANTS, Entities.CATEGORY, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.withAttribute(
						REF_ATTR_VARIANT_TAG, String.class,
						thatIs -> thatIs.filterableInScope(Scope.values()).representative()
					)
			)
			.withReferenceToEntity(
				REF_PRODUCT_WEAK_TAGS, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.values())
					.withAttribute(
						REF_ATTR_WEAK, Long.class,
						thatIs -> thatIs.filterableInScope(Scope.values())
					)
			)
			.withReferenceToEntity(
				REF_PRODUCT_ORPHAN_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
			)
			.withReferenceToEntity(
				REF_PRODUCT_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.faceted()
			)
			.withReferenceToEntity(
				REF_PRODUCT_TAXONOMY, ENTITY_TAXONOMY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
			)
			.withReferenceToEntity(
				REF_TAXONOMY_STATS, ENTITY_TAXONOMY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
			)
			.withReferenceToEntity(
				REF_PRODUCT_SCOPED_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
			)
			.withReflectedReferenceToEntity(
				REF_PRODUCT_CURATED_BY, Entities.CATEGORY, REF_CATEGORY_CURATED,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.withAttributesInherited()
			)
			.updateVia(session);

		session.defineEntitySchema(Entities.CATEGORY)
			// `unique()` is implicitly filterable and declaring both is rejected - see the class JavaDoc
			.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::unique)
			.withAttribute(ATTR_ACTIVE, Boolean.class, thatIs -> thatIs.filterableInScope(Scope.values()))
			.withAttribute(ATTR_ALWAYS_SET, String.class, thatIs -> thatIs.filterableInScope(Scope.values()).nullable())
			.withAttribute(
				ATTR_SOMETIMES_SET, Long.class,
				thatIs -> thatIs.filterableInScope(Scope.values()).nullable()
			)
			.withAttribute(
				ATTR_LOCALIZED_LABEL, String.class,
				thatIs -> thatIs.filterableInScope(Scope.values()).localized().nullable()
			)
			.withAttribute(ATTR_UNIQUE_SOMETIMES, String.class, thatIs -> thatIs.unique().nullable())
			.withReferenceToEntity(
				REF_CATEGORY_CURATED, Entities.PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.withAttribute(
						REF_ATTR_RANK, Long.class,
						thatIs -> thatIs.filterableInScope(Scope.values())
					)
			)
			.withReferenceToEntity(
				REF_CATEGORY_PLAIN_PRODUCTS, Entities.PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.withAttribute(
						REF_ATTR_PLAIN, Long.class,
						thatIs -> thatIs.filterableInScope(Scope.values())
					)
			)
			.withReferenceToEntity(
				REF_CATEGORY_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.faceted()
			)
			.withReferenceToEntity(
				REF_CATEGORY_TAXONOMY, ENTITY_TAXONOMY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
			)
			// DELIBERATELY filtering-only, not partitioned - this is NOT an oversight, do not "fix" it.
			// `ExtraResultPlanningVisitor` only takes the formula-tree hierarchy strip when
			// `canUseShortcut(referenceSchema)` holds, and that is false when the statistics reference is
			// FOR_FILTERING_AND_PARTITIONING in every requested scope. Partitioning this end would route the
			// hierarchy-statistics row down the constraint-tree fallback, which strips the nested
			// `hierarchyWithin` identically in the rewritten and the declined variant - and the row would pass
			// for a reason that has nothing to do with the rewrite.
			.withReferenceToEntity(
				REF_TAXONOMY_STATS, ENTITY_TAXONOMY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringInScope(Scope.values())
			)
			.withReflectedReferenceToEntity(
				REF_CATEGORY_PRODUCTS, Entities.PRODUCT, REF_PRODUCT_CATEGORIES,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.withAttributesInheritedExcept(REF_ATTR_NOTE)
					.withAttribute(
						REF_ATTR_OWN_NOTE, String.class,
						thatIs -> thatIs.filterableInScope(Scope.values()).nullable()
					)
			)
			.withReflectedReferenceToEntity(
				REF_CATEGORY_VARIANT_PRODUCTS, Entities.PRODUCT, REF_PRODUCT_VARIANTS,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.withAttributesInherited()
			)
			.withReflectedReferenceToEntity(
				REF_CATEGORY_WEAK_PRODUCTS, Entities.PRODUCT, REF_PRODUCT_WEAK_TAGS,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.values())
					.withAttributesInherited()
			)
			.withReflectedReferenceToEntity(
				REF_CATEGORY_ORPHAN_PRODUCTS, Entities.PRODUCT, REF_PRODUCT_ORPHAN_CATEGORIES,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.withAttributesInherited()
			)
			// DELIBERATELY LIVE-only while its original is indexed in both scopes - this is NOT an oversight.
			// It is the one reference in the fixture that is unusable in the archived scope, which is what lets a
			// row assert that `ReferenceNotIndexedException` still surfaces naming the *owner* reference. The
			// orientation is load-bearing and only this direction works: were the LIVE-only reference the
			// original on CATEGORY, its mirror rows on the archived products would be dropped by
			// `isRelationMaintained`, no archived counterpart type index would exist, and the rewrite would
			// decline on its own - leaving the row green even if the owner-end scope check were deleted.
			.withReflectedReferenceToEntity(
				REF_CATEGORY_SCOPED_PRODUCTS, Entities.PRODUCT, REF_PRODUCT_SCOPED_CATEGORIES,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
					.withAttributesInherited()
			)
			.updateVia(session);
	}

	/**
	 * Writes a single taxonomy node. The tree is `1 -> {2, 3}` and `4 -> {5, 6}`, so `node(1)` covers `{1, 2, 3}`
	 * and `node(4)` covers `{4, 5, 6}`.
	 *
	 * @param session    session to write through
	 * @param taxonomyPk primary key of the node, 1..6
	 */
	private static void writeTaxonomyNode(@Nonnull EvitaSessionContract session, int taxonomyPk) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_TAXONOMY, taxonomyPk);
		if (taxonomyPk == 2 || taxonomyPk == 3) {
			builder.setParent(1);
		} else if (taxonomyPk == 5 || taxonomyPk == 6) {
			builder.setParent(4);
		}
		session.upsertEntity(builder);
	}

	/**
	 * Writes a single category with its entity attributes and the two references that do not target `PRODUCT`.
	 * The `PRODUCT`-facing references are added in a second pass, after the products exist - see
	 * {@link #writeCategoryToProductRelations(EvitaSessionContract, int)}.
	 *
	 * @param session    session to write through
	 * @param categoryPk primary key of the category, 1..12
	 */
	private static void writeCategory(@Nonnull EvitaSessionContract session, int categoryPk) {
		final EntityBuilder builder = session.createNewEntity(Entities.CATEGORY, categoryPk)
			.setAttribute(ATTR_CODE, "category-" + categoryPk)
			.setAttribute(ATTR_ACTIVE, true)
			.setAttribute(ATTR_ALWAYS_SET, "always-" + categoryPk)
			.setAttribute(ATTR_LOCALIZED_LABEL, Locale.ENGLISH, "label-en-" + categoryPk);
		if (categoryPk <= 8) {
			builder.setAttribute(ATTR_SOMETIMES_SET, (long) categoryPk);
		}
		if (categoryPk <= 7) {
			builder.setAttribute(ATTR_LOCALIZED_LABEL, Locale.GERMAN, "label-de-" + categoryPk);
		}
		if (categoryPk <= 5) {
			builder.setAttribute(ATTR_UNIQUE_SOMETIMES, "uniq-" + categoryPk);
		}
		builder.setReference(REF_CATEGORY_BRAND, ((categoryPk - 1) % BRAND_COUNT) + 1);
		builder.setReference(REF_CATEGORY_TAXONOMY, taxonomyOfCategory(categoryPk));
		// every taxonomy node 1-6 is used exactly twice, and deliberately NOT the same node `taxonomy` picks,
		// so a row that confuses the two references returns a visibly wrong set
		builder.setReference(
			REF_TAXONOMY_STATS,
			categoryPk <= TAXONOMY_COUNT ? categoryPk : categoryPk - TAXONOMY_COUNT
		);
		session.upsertEntity(builder);
	}

	/**
	 * Writes a single product with its entity attributes and every reference it owns. Writing a product is what
	 * materialises the reflected `CATEGORY.products`, `CATEGORY.variantProducts` and `CATEGORY.weakProducts` rows.
	 *
	 * `PRODUCT.orphanCategories` is deliberately left empty for every product - that is the whole purpose of the
	 * `orphanCategories`/`orphanProducts` pair.
	 *
	 * @param session   session to write through
	 * @param productPk primary key of the product, 1..240
	 */
	private static void writeProduct(@Nonnull EvitaSessionContract session, int productPk) {
		final EntityBuilder builder = session.createNewEntity(Entities.PRODUCT, productPk)
			.setAttribute(ATTR_CODE, "product-" + productPk)
			.setAttribute(ATTR_PRODUCT_ACTIVE, productPk % 2 == 1);

		final int roundRobinCategoryPk = ((productPk - 1) % ROUND_ROBIN_CATEGORY_COUNT) + 1;

		if (productPk < FIRST_ARCHIVED_PRODUCT_PK) {
			setCategoriesReference(builder, productPk, roundRobinCategoryPk);
			if (productPk <= ROUND_ROBIN_CATEGORY_COUNT) {
				// the only rows reaching the archived category - and they come from *live* products
				setCategoriesReference(builder, productPk, ARCHIVED_CATEGORY_PK);
			}
		} else {
			// the archived products reach one live category and nothing else
			setCategoriesReference(builder, productPk, CROSS_SCOPE_CATEGORY_PK);
		}

		if (productPk <= LAST_VARIANT_PRODUCT_PK) {
			// two of these three rows point at the same category and differ only in the representative attribute
			addVariantReference(builder, 1, "a");
			addVariantReference(builder, 1, "b");
			addVariantReference(builder, VARIANT_CATEGORY_COUNT, "a");
		}
		// exactly one product per partition, so category 3's two reduced indexes hold DISJOINT product sets - the
		// only shape in this fixture where losing one qualifying index changes the answer
		if (productPk == DISJOINT_VARIANT_X_PRODUCT_PK) {
			addVariantReference(builder, DISJOINT_VARIANT_CATEGORY_PK, "x");
		}
		if (productPk == DISJOINT_VARIANT_Y_PRODUCT_PK) {
			addVariantReference(builder, DISJOINT_VARIANT_CATEGORY_PK, "y");
		}

		if (productPk <= LAST_WEAK_TAG_PRODUCT_PK) {
			final long weakValue = productPk % 3;
			builder.setReference(
				REF_PRODUCT_WEAK_TAGS, ((productPk - 1) % WEAK_TAG_CATEGORY_COUNT) + 1,
				whichIs -> whichIs.setAttribute(REF_ATTR_WEAK, weakValue)
			);
		}

		builder.setReference(REF_PRODUCT_BRAND, ((productPk - 1) % BRAND_COUNT) + 1);
		builder.setReference(REF_PRODUCT_TAXONOMY, ((productPk - 1) % TAXONOMY_COUNT) + 1);

		// correlated with the product's round-robin category so that a category's WHOLE product block sits
		// inside ONE taxonomy subtree. The obvious `((productPk - 1) % TAXONOMY_COUNT) + 1` is wrong here:
		// a category's products are `c + 10k` and `10 = 4 (mod 6)`, so that walks one parity class mod 6 and
		// straddles both subtrees - the nested hierarchy filter would then exclude nothing for any node and
		// stripping it would be unobservable.
		builder.setReference(REF_TAXONOMY_STATS, roundRobinCategoryPk <= 5 ? 2 : 5);

		// written for EVERY product, the archived ten included - that archived counterpart type index is the
		// only thing that keeps the LIVE-only `scopedProducts` row non-vacuous
		builder.setReference(REF_PRODUCT_SCOPED_CATEGORIES, roundRobinCategoryPk);

		session.upsertEntity(builder);
	}

	/**
	 * Adds one `PRODUCT.categories` row with the full set of row attributes.
	 *
	 * @param builder    builder of the owning product
	 * @param productPk  primary key of the owning product - every row attribute is derived from it
	 * @param categoryPk primary key of the referenced category
	 */
	private static void setCategoriesReference(@Nonnull EntityBuilder builder, int productPk, int categoryPk) {
		builder.setReference(
			REF_PRODUCT_CATEGORIES, categoryPk,
			whichIs -> {
				whichIs.setAttribute(REF_ATTR_RELEVANCE, (long) (productPk % 5));
				whichIs.setAttribute(REF_ATTR_NOTE, "o" + productPk);
				whichIs.setAttribute(REF_ATTR_LABEL, Locale.ENGLISH, "lab-en-" + productPk);
				if (productPk % 2 == 0) {
					whichIs.setAttribute(REF_ATTR_LABEL, Locale.GERMAN, "lab-de-" + productPk);
				}
				whichIs.setAttribute(REF_ATTR_ALWAYS_SET, "ra-" + productPk);
				if (productPk % 3 == 0) {
					whichIs.setAttribute(REF_ATTR_SOMETIMES_SET, (long) productPk);
				}
			}
		);
	}

	/**
	 * Adds one `PRODUCT.variants` row. The reference is declared with duplicates, so an always-false filter is used
	 * to force a brand new row instead of updating the row that already points at the same category.
	 *
	 * @param builder    builder of the owning product
	 * @param categoryPk primary key of the referenced category
	 * @param variantTag value of the representative `variantTag` attribute
	 */
	private static void addVariantReference(
		@Nonnull EntityBuilder builder,
		int categoryPk,
		@Nonnull String variantTag
	) {
		builder.setOrUpdateReference(
			REF_PRODUCT_VARIANTS, categoryPk,
			Functions.alwaysFalse(),
			whichIs -> whichIs.setAttribute(REF_ATTR_VARIANT_TAG, variantTag)
		);
	}

	/**
	 * Second pass over a category: adds the two `PRODUCT`-facing original references (which materialise the reflected
	 * `PRODUCT.curatedBy` rows) and stamps the reflected `CATEGORY.products` rows with the reflected end's **own**,
	 * non-inherited `ownNote` value.
	 *
	 * `ownNote` is what makes an attribute resolved against the wrong end of the relation visible: the original end
	 * carries `note = "o" + productPk`, this end carries `ownNote = "r" + categoryPk`, and the two never coincide.
	 *
	 * @param session    session to write through
	 * @param categoryPk primary key of the category, 1..12
	 */
	private static void writeCategoryToProductRelations(@Nonnull EvitaSessionContract session, int categoryPk) {
		final SealedEntity category = session.getEntity(Entities.CATEGORY, categoryPk, entityFetchAllContent())
			.orElseThrow();
		final EntityBuilder builder = category.openForWrite();

		final int firstCuratedProduct = (categoryPk - 1) * CURATED_PRODUCTS_PER_CATEGORY + 1;
		final int lastCuratedProduct = firstCuratedProduct + CURATED_PRODUCTS_PER_CATEGORY - 1;
		for (int productPk = firstCuratedProduct; productPk <= lastCuratedProduct; productPk++) {
			final long rank = categoryPk % 4;
			builder.setReference(
				REF_CATEGORY_CURATED, productPk,
				whichIs -> whichIs.setAttribute(REF_ATTR_RANK, rank)
			);
		}

		final int firstPlainProduct = (categoryPk - 1) * PLAIN_PRODUCTS_PER_CATEGORY + 1;
		final int lastPlainProduct = firstPlainProduct + PLAIN_PRODUCTS_PER_CATEGORY - 1;
		for (int productPk = firstPlainProduct; productPk <= lastPlainProduct; productPk++) {
			final long plain = productPk % 7;
			builder.setReference(
				REF_CATEGORY_PLAIN_PRODUCTS, productPk,
				whichIs -> whichIs.setAttribute(REF_ATTR_PLAIN, plain)
			);
		}

		final Collection<ReferenceContract> reflectedProducts = category.getReferences(REF_CATEGORY_PRODUCTS);
		assertTrue(
			!reflectedProducts.isEmpty(),
			"Category `" + categoryPk + "` has no reflected `" + REF_CATEGORY_PRODUCTS + "` rows - the products " +
				"were not written before this pass, so the reflected end's own `" + REF_ATTR_OWN_NOTE +
				"` would stay unset."
		);
		for (ReferenceContract reflectedProduct : reflectedProducts) {
			builder.updateReference(
				REF_CATEGORY_PRODUCTS, reflectedProduct.getReferencedPrimaryKey(),
				whichIs -> whichIs.setAttribute(REF_ATTR_OWN_NOTE, "r" + categoryPk)
			);
		}

		session.upsertEntity(builder);
	}

	/**
	 * Fetches every entity of the given collection, in both scopes, with all content, ordered by primary key
	 * ascending. The explicit `scope(LIVE, ARCHIVED)` is mandatory - a plain query would silently return only the
	 * live rows and the carrier would be missing exactly the entities the scope-asymmetry rows are about.
	 *
	 * @param session    session to query through
	 * @param entityType collection to fetch
	 * @return all entities of the collection, never null
	 */
	@Nonnull
	private static List<SealedEntity> fetchAllInBothScopes(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType
	) {
		return session.queryListOfSealedEntities(
			query(
				collection(entityType),
				filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
				orderBy(entityPrimaryKeyNatural(OrderDirection.ASC)),
				require(entityFetch(entityFetchAllContent()), page(1, Integer.MAX_VALUE))
			)
		);
	}

	/**
	 * Asserts every count the rewrite gate arithmetic is built from. Each guard names the test row that stops
	 * proving anything if the number moves - a shrunken or re-balanced fixture must fail here, loudly, rather than
	 * turn a "rewrite fires" row into a "rewrite declines" row that still passes its result assertion.
	 *
	 * "Candidates" is the distinct referenced-primary-key set of the **counterpart's** reference in the requested
	 * scope, "buckets" the same quantity for the owner's own reference; the rewrite fires when
	 * `candidates * 4 <= buckets`.
	 *
	 * @param categories all categories, both scopes
	 * @param products   all products, both scopes
	 * @param brands     all brands
	 * @param taxonomy   all taxonomy nodes
	 */
	private static void assertFixtureGates(
		@Nonnull List<SealedEntity> categories,
		@Nonnull List<SealedEntity> products,
		@Nonnull List<SealedEntity> brands,
		@Nonnull List<SealedEntity> taxonomy
	) {
		assertEquals(CATEGORY_COUNT, categories.size(), "The fixture must contain exactly 12 categories.");
		assertEquals(PRODUCT_COUNT, products.size(), "The fixture must contain exactly 240 products.");
		assertEquals(BRAND_COUNT, brands.size(), "The fixture must contain exactly 8 brands.");
		assertEquals(TAXONOMY_COUNT, taxonomy.size(), "The fixture must contain exactly 6 taxonomy nodes.");

		final List<SealedEntity> liveCategories = inScope(categories, Scope.LIVE);
		final List<SealedEntity> archivedCategories = inScope(categories, Scope.ARCHIVED);
		final List<SealedEntity> liveProducts = inScope(products, Scope.LIVE);
		final List<SealedEntity> archivedProducts = inScope(products, Scope.ARCHIVED);

		// breaks: every row that asks anything under scope(LIVE) or scope(ARCHIVED), and the mainIndexCardinality
		// threshold (11 / 2 = 5) the hierarchy-eligibility row depends on
		assertEquals(
			11, liveCategories.size(),
			"Categories 1-11 must stay LIVE - only category 12 is archived. Eleven is also the mainIndexCardinality " +
				"the hierarchy-eligibility threshold (11 / 2 = 5) is derived from, so adding a LIVE category breaks it."
		);
		assertEquals(
			Set.of(ARCHIVED_CATEGORY_PK),
			archivedCategories.stream()
				.map(SealedEntity::getPrimaryKeyOrThrowException)
				.collect(Collectors.toSet()),
			"Exactly category 12 must be ARCHIVED."
		);

		// breaks: gate row 1 (LIVE collection(CATEGORY) + referenceHaving(products)) - buckets side,
		// and gate row 3 (collection(PRODUCT) + referenceHaving(categories)) - candidates side.
		// Measured on this fixture: the LIVE `CATEGORY.products` type index advertises 240 reduced indexes, not
		// 230 - the cross-scope rows of the ten archived products are carried in the LIVE family too. The verdict
		// is unchanged either way (11 * 4 = 44 is far below both numbers), but do not read 230 as the bucket count.
		assertEquals(230, liveProducts.size(), "Products 1-230 must stay LIVE - only 231-240 are archived.");
		// breaks: gate row 2 (scope(ARCHIVED) collection(CATEGORY) + products).
		// Measured on this fixture: the ARCHIVED `CATEGORY.products` type index does **not exist** even though the
		// archived category still carries its ten reflected rows in its body and the schema declares the reference
		// indexed in both scopes. The gate therefore never fires in the archived scope - it declines on the missing
		// owner-side index - and the archived-owner row fails on the ordinary owner-side path instead.
		assertEquals(10, archivedProducts.size(), "Products 231-240 must be ARCHIVED - exactly ten of them.");

		// breaks: gate row 1 candidates side (11) and gate row 3 buckets side (11)
		final Set<Integer> categoriesSeenFromLiveProducts = referencedPrimaryKeys(liveProducts, REF_PRODUCT_CATEGORIES);
		assertEquals(
			11, categoriesSeenFromLiveProducts.size(),
			"The LIVE `" + REF_PRODUCT_CATEGORIES + "` rows must reach exactly 11 categories (1-10 plus the archived " +
				"12); 11 * 4 = 44 is what the rewrite gate weighs against the owner-side bucket count from " +
				"collection(CATEGORY)."
		);
		assertTrue(
			categoriesSeenFromLiveProducts.contains(ARCHIVED_CATEGORY_PK),
			"The archived category 12 must be reachable from LIVE products 1-10."
		);

		// breaks: gate row 2 candidates side (1) - and the archived-owner row, which is empty unless category 12 is
		// absent from the ARCHIVED candidate set
		final Set<Integer> categoriesSeenFromArchivedProducts = referencedPrimaryKeys(
			archivedProducts, REF_PRODUCT_CATEGORIES
		);
		assertEquals(
			Set.of(CROSS_SCOPE_CATEGORY_PK), categoriesSeenFromArchivedProducts,
			"The ARCHIVED `" + REF_PRODUCT_CATEGORIES + "` rows must reach category 11 and nothing else - no " +
				"archived " +
				"product may reference category 12, that absence is what the archived-owner row pins."
		);

		// breaks: gate row 4 (collection(CATEGORY) + curated) - candidates 12, buckets 220
		final Set<Integer> categoriesSeenFromCuratedBy = referencedPrimaryKeys(liveProducts, REF_PRODUCT_CURATED_BY);
		assertEquals(
			CATEGORY_COUNT, categoriesSeenFromCuratedBy.size(),
			"All 12 categories must curate at least one LIVE product; 12 * 4 = 48 <= 220 is what makes `curated` fire."
		);
		final Set<Integer> productsSeenFromLiveCurated = referencedPrimaryKeys(liveCategories, REF_CATEGORY_CURATED);
		assertEquals(
			220, productsSeenFromLiveCurated.size(),
			"LIVE categories 1-11 must curate products 1-220 - category 12 is archived, so its 20 products drop out."
		);

		// breaks: gate row 5 (collection(CATEGORY) + weakProducts) - candidates 6, buckets 60, on a
		// filtering-only (non-partitioned) index
		final Set<Integer> categoriesSeenFromWeakTags = referencedPrimaryKeys(liveProducts, REF_PRODUCT_WEAK_TAGS);
		assertEquals(
			WEAK_TAG_CATEGORY_COUNT, categoriesSeenFromWeakTags.size(),
			"`" + REF_PRODUCT_WEAK_TAGS + "` must reach exactly 6 categories; 6 * 4 = 24 <= 60 is what makes it fire."
		);
		final Set<Integer> productsSeenFromWeakProducts = referencedPrimaryKeys(
			liveCategories, REF_CATEGORY_WEAK_PRODUCTS
		);
		assertEquals(
			LAST_WEAK_TAG_PRODUCT_PK, productsSeenFromWeakProducts.size(),
			"Exactly products 1-60 must carry a `" + REF_PRODUCT_WEAK_TAGS + "` row."
		);

		// breaks: gate row 6 (collection(CATEGORY) + orphanProducts) - with zero rows written no counterpart type
		// index exists at all, which is the only way to reach the "declines cleanly, index absent" branch
		assertEquals(
			0, referenceRowCount(products, REF_PRODUCT_ORPHAN_CATEGORIES),
			"`" + REF_PRODUCT_ORPHAN_CATEGORIES + "` must stay empty - a single row would create the counterpart " +
				"type " +
				"index and the decline would happen for a different reason."
		);
		assertEquals(
			0, referenceRowCount(categories, REF_CATEGORY_ORPHAN_PRODUCTS),
			"`" + REF_CATEGORY_ORPHAN_PRODUCTS + "` must stay empty."
		);

		// breaks: the hierarchy rows - `mainIndexCardinality` is 11 LIVE categories, threshold 11 / 2 = 5, so the
		// four categories under taxonomy node 1 keep the hierarchy index option eligible (4 <= 5)
		final long categoriesUnderTaxonomyNodeOne = liveCategories.stream()
			.filter(
				category -> category.getReferences(REF_CATEGORY_TAXONOMY)
					.stream()
					.anyMatch(reference -> TAXONOMY_SUBTREE_OF_NODE_1.contains(reference.getReferencedPrimaryKey()))
			)
			.count();
		assertEquals(
			4, categoriesUnderTaxonomyNodeOne,
			"Exactly four LIVE categories (1-4) must sit under taxonomy node 1 - five or more would earn " +
				"HIGH_CARDINALITY and the hierarchy plan would never be eligible."
		);

		// breaks: the nested-facet row - the same facet primary key must exist in both facet namespaces at once
		assertTrue(
			referencedPrimaryKeys(categories, REF_CATEGORY_BRAND).contains(SHARED_BRAND_PK),
			"At least one category must reference brand 7, or facet 7 never appears in the CATEGORY.brand summary."
		);
		assertTrue(
			referencedPrimaryKeys(products, REF_PRODUCT_BRAND).contains(SHARED_BRAND_PK),
			"At least one product must reference brand 7, or the nested facet selection has nothing to select."
		);

		// breaks: the facet-summary-baseline row - `alwaysSet` must be present on every category, so that
		// attributeIs(alwaysSet, NULL) collapses to the empty set at planning time
		assertEquals(
			CATEGORY_COUNT,
			categories.stream().filter(category -> category.getAttribute(ATTR_ALWAYS_SET) != null).count(),
			"`" + ATTR_ALWAYS_SET + "` must be set on all 12 categories."
		);
		assertEquals(
			8,
			categories.stream().filter(category -> category.getAttribute(ATTR_SOMETIMES_SET) != null).count(),
			"`" + ATTR_SOMETIMES_SET + "` must be set on exactly 8 of the 12 categories."
		);
		assertEquals(
			5,
			categories.stream().filter(category -> category.getAttribute(ATTR_UNIQUE_SOMETIMES) != null).count(),
			"`" + ATTR_UNIQUE_SOMETIMES + "` must be set on exactly 5 of the 12 categories."
		);

		// breaks: the planning-skip rows - `refAlwaysSet` must be present on every single reference row, so that
		// attributeIsNull(refAlwaysSet) is answerable without touching an index
		final long categoriesRowsWithoutAlwaysSet = products.stream()
			.flatMap(product -> product.getReferences(REF_PRODUCT_CATEGORIES).stream())
			.filter(reference -> reference.getAttribute(REF_ATTR_ALWAYS_SET) == null)
			.count();
		assertEquals(
			0, categoriesRowsWithoutAlwaysSet,
			"`" + REF_ATTR_ALWAYS_SET + "` must be set on every `" + REF_PRODUCT_CATEGORIES + "` row."
		);

		// breaks: the "attribute resolved against the wrong end" rows - the reflected end must carry its own,
		// non-inherited note on every row
		final long reflectedRowsWithoutOwnNote = categories.stream()
			.flatMap(category -> category.getReferences(REF_CATEGORY_PRODUCTS).stream())
			.filter(reference -> reference.getAttribute(REF_ATTR_OWN_NOTE) == null)
			.count();
		assertEquals(
			0, reflectedRowsWithoutOwnNote,
			"The reflected `" + REF_CATEGORY_PRODUCTS + "` rows must all carry the reflected end's own `" +
				REF_ATTR_OWN_NOTE + "` value."
		);

		// breaks: the duplicate-cardinality row - 30 products with three rows each, two of them sharing a category,
		// plus the two single rows that give category 3 its disjoint partitions
		assertEquals(
			3 * LAST_VARIANT_PRODUCT_PK + 2, referenceRowCount(products, REF_PRODUCT_VARIANTS),
			"Products 1-30 must carry exactly three `" + REF_PRODUCT_VARIANTS + "` rows each, and products " +
				DISJOINT_VARIANT_X_PRODUCT_PK + " and " + DISJOINT_VARIANT_Y_PRODUCT_PK + " one extra row each " +
				"on category " + DISJOINT_VARIANT_CATEGORY_PK + "."
		);
		// breaks: the "no reflected counterpart" decline row
		assertEquals(
			CATEGORY_COUNT * PLAIN_PRODUCTS_PER_CATEGORY, referenceRowCount(categories, REF_CATEGORY_PLAIN_PRODUCTS),
			"Every category must claim exactly 5 products through `" + REF_CATEGORY_PLAIN_PRODUCTS + "`."
		);

		// breaks: the ReferenceNotIndexedException row - the archived products must reach ten distinct categories
		// through `scopedCategories`, because that archived counterpart type index is the only thing that keeps
		// the row from declining at `worthRewriting` for an unrelated reason
		final Set<Integer> scopedCategoriesFromArchivedProducts = referencedPrimaryKeys(
			archivedProducts, REF_PRODUCT_SCOPED_CATEGORIES
		);
		assertEquals(
			ROUND_ROBIN_CATEGORY_COUNT, scopedCategoriesFromArchivedProducts.size(),
			"The ARCHIVED products must reach 10 distinct categories through `" + REF_PRODUCT_SCOPED_CATEGORIES +
				"`."
		);

		// breaks: the hierarchy-statistics row - every category's whole product block must sit inside ONE
		// taxonomy subtree, otherwise the nested hierarchy filter excludes nothing and stripping it is
		// unobservable. See the comment on the `taxonomyStats` assignment in writeProduct.
		for (int categoryPk = 1; categoryPk <= ROUND_ROBIN_CATEGORY_COUNT; categoryPk++) {
			final int block = categoryPk;
			final Set<Integer> statsNodesOfBlock = products.stream()
				.filter(
					product -> ((product.getPrimaryKeyOrThrowException() - 1) % ROUND_ROBIN_CATEGORY_COUNT) + 1 == block
				)
				.flatMap(product -> product.getReferences(REF_TAXONOMY_STATS).stream())
				.map(ReferenceContract::getReferencedPrimaryKey)
				.collect(Collectors.toSet());
			assertEquals(
				1, statsNodesOfBlock.size(),
				"The products of round-robin category " + block + " must all point at a single `" +
					REF_TAXONOMY_STATS + "` node."
			);
		}
		assertEquals(
			Set.of(2, 5), referencedPrimaryKeys(products, REF_TAXONOMY_STATS),
			"`" + REF_TAXONOMY_STATS + "` on PRODUCT must reach taxonomy node 2 (subtree of node 1) and node 5 " +
				"(subtree of node 4) and nothing else."
		);
		assertEquals(
			TAXONOMY_COUNT, referencedPrimaryKeys(categories, REF_TAXONOMY_STATS).size(),
			"`" + REF_TAXONOMY_STATS + "` on CATEGORY must use every one of the six taxonomy nodes."
		);

		// breaks: every row computing an expected set from the carrier - the lists must be ordered by primary key
		assertTrue(
			isOrderedByPrimaryKey(categories) && isOrderedByPrimaryKey(products)
				&& isOrderedByPrimaryKey(brands) && isOrderedByPrimaryKey(taxonomy),
			"The carrier lists must be ordered by primary key ascending."
		);
	}

	/**
	 * Returns true when the passed entities are ordered by primary key ascending.
	 *
	 * @param entities entities to check
	 * @return true when ordered ascending
	 */
	private static boolean isOrderedByPrimaryKey(@Nonnull List<SealedEntity> entities) {
		for (int i = 1; i < entities.size(); i++) {
			final int previousPk = entities.get(i - 1).getPrimaryKeyOrThrowException();
			if (previousPk >= entities.get(i).getPrimaryKeyOrThrowException()) {
				return false;
			}
		}
		return true;
	}

}
