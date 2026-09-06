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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesAvailabilityChecker;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.REQUIRE;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.CURRENCY_EUR;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_B2B;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_BASIC;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_VIP;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional tests verifying that several content requirements of one kind written side by side in a single
 * `entityFetch` are folded into the one requirement the query executes with, instead of one of them being dropped or
 * the query being refused.
 *
 * The tests exercise complete query results obtained through the embedded {@link EvitaSessionContract}, so they cover
 * the whole path from the requirement fold performed on the request down to the entity bodies that reach the caller.
 * They complement the unit-level coverage of the fold itself, which asserts the shape of the reduced requirement
 * rather than the data it produces.
 *
 * Four behaviours are covered:
 *
 * - **union** — siblings addressing the same thing are merged, and the entity carries everything either of them
 *   asked for
 * - **specificity** — a requirement naming a reference and the catch-all requirement for every reference are *not*
 *   merged, so the reference-specific body keeps winning over the default one
 * - **refusal** — siblings that address the same thing but cannot be reconciled raise an
 *   {@link EvitaInvalidUsageException} naming the part they disagree on. A restriction (`filterBy`, chunking)
 *   carried by only one of them counts as a disagreement, because the pair fills a single output slot and neither
 *   returning what the restricting side excluded nor hiding what the unrestricted side asked for may be chosen on
 *   the client's behalf. An `orderBy` is the one exception — it removes no reference, so the only order present is
 *   kept
 * - **widening** — the requirements the query planner contributes on the client's behalf join a *prefetch* union
 *   that strips those restrictions, so an ordinary query filtering a reference it also orders by is planned rather
 *   than refused, and still returns exactly the references the client projected
 *
 * This class is also the single home for the refusal cases, both for the Java API and for the EvitaQL text surface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Evita duplicate content requirement handling")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REQUIRE)
class EntityDuplicateContentRequirementFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	/**
	 * Returns the number of references of the passed name each of the requested products carries when it is fetched
	 * with the passed content requirements. Comparing two such maps is how the tests express "the same references came
	 * back" without depending on the concrete reference primary keys the data generator produced.
	 *
	 * @param session         session to execute the query in
	 * @param primaryKeys     primary keys of the products to fetch
	 * @param referenceName   name of the reference whose occurrences are counted
	 * @param requirements    content requirements the products are fetched with
	 * @return map of product primary key to the number of its references of the passed name
	 */
	@Nonnull
	private static Map<Integer, Integer> countReferencesPerProduct(
		@Nonnull EvitaSessionContract session,
		@Nonnull Integer[] primaryKeys,
		@Nonnull String referenceName,
		@Nonnull EntityContentRequire... requirements
	) {
		final EvitaResponse<SealedEntity> response = session.querySealedEntity(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(primaryKeys)
				),
				require(
					page(1, Integer.MAX_VALUE),
					entityFetch(requirements)
				)
			)
		);

		assertFalse(response.getRecordData().isEmpty(), "No product matched the query, the test would prove nothing!");
		return response.getRecordData()
			.stream()
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(referenceName).size()
				)
			);
	}

	/**
	 * Returns the primary keys of the references of the passed name each returned product carries, in the order they
	 * were delivered. Comparing two such maps is how a test asserts that two execution paths returned the very same
	 * reference projection - the same references, in the same order, for the same products.
	 *
	 * @param session       session to execute the query in
	 * @param theQuery      query to execute
	 * @param referenceName name of the reference whose primary keys are collected
	 * @return map of product primary key to the primary keys of its references of the passed name
	 */
	@Nonnull
	private static Map<Integer, List<Integer>> collectReferencedPrimaryKeys(
		@Nonnull EvitaSessionContract session,
		@Nonnull Query theQuery,
		@Nonnull String referenceName
	) {
		final EvitaResponse<SealedEntity> response = session.querySealedEntity(theQuery);

		assertFalse(response.getRecordData().isEmpty(), "No product matched the query, the test would prove nothing!");
		return response.getRecordData()
			.stream()
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(referenceName)
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toList()
				)
			);
	}

	/**
	 * Asserts that the product carries at least one reference of the passed name and that the body of every
	 * referenced entity was fetched with its `code` attribute. This is what a `referenceContent` naming several
	 * references and carrying a nested `entityFetch` has to deliver for each of the names it lists.
	 *
	 * @param product       product whose references are examined
	 * @param referenceName name of the reference whose referenced bodies are examined
	 */
	private static void assertReferencedEntitiesCarryCode(
		@Nonnull SealedEntity product,
		@Nonnull String referenceName
	) {
		final Collection<ReferenceContract> references = product.getReferences(referenceName);
		assertFalse(
			references.isEmpty(),
			"Product " + product.getPrimaryKey() + " lost its `" + referenceName + "` references!"
		);
		for (final ReferenceContract reference : references) {
			assertNotNull(
				reference.getReferencedEntity().orElseThrow().getAttribute(ATTRIBUTE_CODE),
				"The body requested for `" + referenceName + "` was not fetched!"
			);
		}
	}

	/**
	 * Returns a `referenceContent` naming several references and filtering all of them. The fluent factories do not
	 * offer this shape - a filter is only meaningful for one reference at a time - so it is built through the
	 * constructor the GraphQL layer uses for cloning; it is the shortest way to make two requirements with
	 * overlapping name sets disagree about the reference they share.
	 *
	 * @param filterBy       filter applied to every reference the requirement names
	 * @param referenceNames names of the references the requirement addresses
	 * @return the requirement addressing all the passed references with the passed filter
	 */
	@Nonnull
	private static ReferenceContent filteredReferenceContent(
		@Nonnull FilterBy filterBy,
		@Nonnull String... referenceNames
	) {
		return new ReferenceContent(
			null,
			ManagedReferencesBehaviour.ANY,
			referenceNames,
			new RequireConstraint[0],
			new Constraint<?>[]{filterBy}
		);
	}

	/**
	 * Returns the primary key of a category referenced by a product that carries more than one of them. Filtering or
	 * paging that reference therefore has something to remove, which is what makes an assertion about the projected
	 * references meaningful.
	 *
	 * @param originalProducts products the dataset was generated with
	 * @return primary key of a category referenced by a product carrying several categories
	 */
	private static int findCategoryOfProductWithManyCategories(@Nonnull List<SealedEntity> originalProducts) {
		return findEntityByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.CATEGORY).size() > 1
		).getReferences(Entities.CATEGORY)
			.iterator()
			.next()
			.getReferencedPrimaryKey();
	}

	/**
	 * Returns the primary keys of the products that carry several categories and reference the passed one among
	 * them, so that a `referenceContent` filtered down to that single category returns exactly one reference for
	 * each of them.
	 *
	 * @param originalProducts    products the dataset was generated with
	 * @param categoryPrimaryKey  primary key of the category the products have to reference
	 * @return primary keys of the matching products
	 */
	@Nonnull
	private static Integer[] getProductsReferencingCategory(
		@Nonnull List<SealedEntity> originalProducts,
		int categoryPrimaryKey
	) {
		return getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.CATEGORY).size() > 1 &&
				it.getReferences(Entities.CATEGORY)
					.stream()
					.anyMatch(ref -> ref.getReferencedPrimaryKey() == categoryPrimaryKey)
		);
	}

	/**
	 * Returns a query fetching the passed products ordered by their `categoryPriority` reference attribute - the
	 * ordering that makes the query planner contribute a `referenceContent` of the `CATEGORY` reference of its own -
	 * with the passed content requirement in the `entityFetch`.
	 *
	 * @param primaryKeys      primary keys of the products to fetch
	 * @param requirement      the reference content requirement the client wrote
	 * @param preferPrefetching when true, the query asks the planner to prefer the prefetch path over the index one
	 * @return the assembled query
	 */
	@Nonnull
	private static Query orderedByCategoryPriority(
		@Nonnull Integer[] primaryKeys,
		@Nonnull ReferenceContent requirement,
		boolean preferPrefetching
	) {
		return query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(primaryKeys)
			),
			orderBy(
				referenceProperty(
					Entities.CATEGORY,
					attributeNatural(ATTRIBUTE_CATEGORY_PRIORITY, OrderDirection.DESC)
				)
			),
			preferPrefetching ?
				require(
					debug(DebugMode.PREFER_PREFETCHING),
					page(1, Integer.MAX_VALUE),
					entityFetch(requirement)
				) :
				require(
					page(1, Integer.MAX_VALUE),
					entityFetch(requirement)
				)
		);
	}

	/**
	 * Returns the primary key of the deepest category of the generated hierarchy, i.e. one that is guaranteed to have
	 * at least one parent.
	 *
	 * @param categoryHierarchy hierarchy of the generated categories
	 * @return primary key of the deepest category
	 */
	private static int findDeepestCategoryPrimaryKey(@Nonnull Hierarchy categoryHierarchy) {
		final HierarchyItem theChild = categoryHierarchy.getRootItems()
			.stream()
			.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
			.max(Comparator.comparingInt(HierarchyItem::getLevel))
			.orElseThrow();
		return Integer.parseInt(theChild.getCode());
	}

	@Nested
	@DisplayName("Reference content siblings")
	@Tag(REFERENCE)
	class ReferenceContentSiblings {

		/**
		 * The shape that motivated the fold - one reference requested twice, each time with a different body. Both
		 * bodies have to reach the referenced entity; without the fold the second requirement replaced the first one
		 * and the attribute it asked for silently disappeared.
		 */
		@DisplayName("Referenced entity should carry the bodies of both reference content siblings")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchBothBodiesWhenOneReferenceIsRequestedTwice(Evita evita, List<SealedEntity> originalProducts) {
			final Integer[] productsWithBrand = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.BRAND).isEmpty() && it.getLocales().contains(LOCALE_CZECH)
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								and(
									entityPrimaryKeyInSet(productsWithBrand),
									entityLocaleEquals(LOCALE_CZECH)
								)
							),
							require(
								page(1, 4),
								entityFetch(
									referenceContent(Entities.BRAND, entityFetch(attributeContent(ATTRIBUTE_CODE))),
									referenceContent(Entities.BRAND, entityFetch(attributeContent(ATTRIBUTE_NAME)))
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						final Collection<ReferenceContract> brands = product.getReferences(Entities.BRAND);
						assertFalse(brands.isEmpty(), "Product " + product.getPrimaryKey() + " lost its brand!");
						for (final ReferenceContract brand : brands) {
							final SealedEntity brandEntity = brand.getReferencedEntity().orElseThrow();
							assertNotNull(
								brandEntity.getAttribute(ATTRIBUTE_CODE),
								"The body of the first requirement was dropped!"
							);
							assertNotNull(
								brandEntity.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH),
								"The body of the second requirement was dropped!"
							);
						}
					}
					return null;
				}
			);
		}

		/**
		 * The two siblings fill one output slot, and a filter only one of them carries has no union: dropping it
		 * would return the references the filtering sibling excluded, honouring it would hide the ones the bare
		 * sibling asked for. The query is refused so that the client says which one he meant.
		 */
		@DisplayName("Should throw exception when only one sibling filters the references")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldThrowExceptionWhenOnlyOneSiblingFiltersThem(Evita evita, List<SealedEntity> originalProducts) {
			final Integer[] productsWithCategories = getRequestedIdsByPredicate(
				originalProducts,
				it -> it.getReferences(Entities.CATEGORY).size() > 1
			);
			final Integer someCategoryPrimaryKey = findEntityByPredicate(
				originalProducts,
				it -> it.getReferences(Entities.CATEGORY).size() > 1
			).getReferences(Entities.CATEGORY)
				.iterator()
				.next()
				.getReferencedPrimaryKey();

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> countReferencesPerProduct(
							session, productsWithCategories, Entities.CATEGORY,
							referenceContent(Entities.CATEGORY),
							referenceContent(
								Entities.CATEGORY,
								filterBy(entityPrimaryKeyInSet(someCategoryPrimaryKey))
							)
						)
					);
					assertTrue(
						exception.getMessage().contains("only one of them declares a filter constraint"),
						exception.getMessage()
					);
					return null;
				}
			);
		}

		/**
		 * Chunking follows the very same rule as the filter - a page carried by a single sibling drops references
		 * the other sibling asked for, so the pair is refused rather than silently reconciled.
		 */
		@DisplayName("Should throw exception when only one sibling pages the references")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldThrowExceptionWhenOnlyOneSiblingPagesThem(Evita evita, List<SealedEntity> originalProducts) {
			final Integer[] productsWithCategories = getRequestedIdsByPredicate(
				originalProducts,
				it -> it.getReferences(Entities.CATEGORY).size() > 1
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> countReferencesPerProduct(
							session, productsWithCategories, Entities.CATEGORY,
							referenceContent(Entities.CATEGORY),
							referenceContent(Entities.CATEGORY, page(1, 1))
						)
					);
					assertTrue(
						exception.getMessage().contains("only one of them declares a chunking constraint"),
						exception.getMessage()
					);
					return null;
				}
			);
		}

		/**
		 * An order drops no reference, so the only order present is retained rather than refused - the pair returns
		 * every reference, in the order the single ordered sibling asked for. This is the single deliberate
		 * exception to the rule the two refusals above assert.
		 */
		@DisplayName("Order of a single sibling should shape all fetched references")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldKeepOrderWhenOnlyOneSiblingOrdersReferences(Evita evita, List<SealedEntity> originalProducts) {
			final SealedEntity productWithManyStores = findEntityByPredicate(
				originalProducts,
				it -> it.getReferences(Entities.STORE).size() > 5
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(productWithManyStores.getPrimaryKey())
							),
							require(
								entityFetch(
									referenceContent(Entities.STORE),
									referenceContent(
										Entities.STORE,
										orderBy(entityPrimaryKeyNatural(OrderDirection.DESC))
									)
								)
							)
						)
					);

					assertEquals(1, response.getRecordData().size());
					final SealedEntity product = response.getRecordData().get(0);
					final int[] receivedPrimaryKeys = product.getReferences(Entities.STORE)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray();

					assertEquals(
						productWithManyStores.getReferences(Entities.STORE).size(),
						receivedPrimaryKeys.length,
						"The ordered sibling narrowed the fetched references!"
					);
					final int[] expectedPrimaryKeys = Arrays.stream(receivedPrimaryKeys)
						.boxed()
						.sorted(Comparator.reverseOrder())
						.mapToInt(Integer::intValue)
						.toArray();
					assertArrayEquals(
						expectedPrimaryKeys, receivedPrimaryKeys,
						"The order of the single ordered sibling was lost!"
					);
					return null;
				}
			);
		}

		/**
		 * Two catch-all requirements share the DEFAULT key and are merged, so every reference is delivered exactly
		 * once rather than twice.
		 */
		@DisplayName("References should be delivered once when the catch-all requirement is written twice")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldFetchReferencesOnceWhenCatchAllRequirementIsWrittenTwice(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final Integer[] productsWithReferences = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.CATEGORY).isEmpty()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Map<Integer, Integer> once = countReferencesPerProduct(
						session, productsWithReferences, Entities.CATEGORY,
						referenceContentAll()
					);
					final Map<Integer, Integer> twice = countReferencesPerProduct(
						session, productsWithReferences, Entities.CATEGORY,
						referenceContentAll(),
						referenceContentAll()
					);

					assertEquals(once, twice, "The repeated catch-all requirement changed the fetched references!");
					return null;
				}
			);
		}

		/**
		 * The two catch-all variants differ only in the reference attributes they ask for, so merging them yields the
		 * richer one and the attributes are fetched.
		 */
		@DisplayName("Reference attributes should be fetched when the richer catch-all sibling asks for them")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchReferenceAttributesWhenRicherCatchAllSiblingAsksForThem(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final Integer[] productsWithParameters = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.PARAMETER).isEmpty()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(productsWithParameters)
							),
							require(
								page(1, 4),
								entityFetch(
									referenceContentAll(),
									referenceContentAllWithAttributes()
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						final Collection<ReferenceContract> parameters = product.getReferences(Entities.PARAMETER);
						assertFalse(parameters.isEmpty());
						for (final ReferenceContract parameter : parameters) {
							assertTrue(
								parameter.attributesAvailable(),
								"The attribute-less sibling swallowed the richer one!"
							);
							assertFalse(parameter.getAttributeValues().isEmpty());
						}
					}
					return null;
				}
			);
		}

		/**
		 * A requirement naming a reference and the catch-all requirement do not share a key and are therefore never
		 * merged - the reference-specific one keeps winning for the reference it names, and the catch-all applies to
		 * every other reference. This is the precedence the fold must leave untouched.
		 */
		@DisplayName("Reference named without attributes should keep them unfetched beside the richer catch-all")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldNotFetchAttributesOfReferenceNamedWithoutThem(Evita evita, List<SealedEntity> originalProducts) {
			final Integer[] products = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.PARAMETER).isEmpty()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(products)
							),
							require(
								page(1, 4),
								entityFetch(
									referenceContentAllWithAttributes(),
									referenceContent(Entities.CATEGORY)
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						final Collection<ReferenceContract> categories = product.getReferences(Entities.CATEGORY);
						assertFalse(categories.isEmpty());
						assertTrue(
							categories.stream().noneMatch(AttributesAvailabilityChecker::attributesAvailable),
							"The catch-all requirement widened the body of the reference named without attributes!"
						);

						final Collection<ReferenceContract> parameters = product.getReferences(Entities.PARAMETER);
						assertFalse(parameters.isEmpty());
						assertTrue(
							parameters.stream().allMatch(AttributesAvailabilityChecker::attributesAvailable),
							"The catch-all requirement stopped applying to the references it was not written for!"
						);
					}
					return null;
				}
			);
		}

		/**
		 * Two requirements whose reference name sets overlap without being equal do not share a key, yet both
		 * describe how the shared reference should be fetched. The request projects each of them onto every name it
		 * lists and folds the projections per name, so the shared reference carries both bodies while the references
		 * named by a single requirement keep theirs.
		 */
		@DisplayName("Reference named by both siblings should carry both bodies")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchBothBodiesWhenReferenceNameSetsOverlap(Evita evita, List<SealedEntity> originalProducts) {
			final Integer[] products = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.BRAND).isEmpty() &&
					!it.getReferences(Entities.CATEGORY).isEmpty() &&
					!it.getReferences(Entities.PARAMETER).isEmpty() &&
					it.getLocales().contains(LOCALE_CZECH)
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								and(
									entityPrimaryKeyInSet(products),
									entityLocaleEquals(LOCALE_CZECH)
								)
							),
							require(
								page(1, 4),
								entityFetch(
									referenceContent(
										new String[]{Entities.BRAND, Entities.CATEGORY},
										entityFetch(attributeContent(ATTRIBUTE_CODE))
									),
									referenceContent(
										new String[]{Entities.CATEGORY, Entities.PARAMETER},
										entityFetch(attributeContent(ATTRIBUTE_NAME))
									)
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						for (final ReferenceContract category : product.getReferences(Entities.CATEGORY)) {
							final SealedEntity categoryEntity = category.getReferencedEntity().orElseThrow();
							assertNotNull(
								categoryEntity.getAttribute(ATTRIBUTE_CODE),
								"The body of the first requirement was dropped for the shared reference!"
							);
							assertNotNull(
								categoryEntity.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH),
								"The body of the second requirement was dropped for the shared reference!"
							);
						}
						for (final ReferenceContract brand : product.getReferences(Entities.BRAND)) {
							final SealedEntity brandEntity = brand.getReferencedEntity().orElseThrow();
							assertNotNull(brandEntity.getAttribute(ATTRIBUTE_CODE));
							assertThrows(
								ContextMissingException.class,
								() -> brandEntity.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH),
								"The body of the other requirement widened the reference it does not name!"
							);
						}
						for (final ReferenceContract parameter : product.getReferences(Entities.PARAMETER)) {
							final SealedEntity parameterEntity = parameter.getReferencedEntity().orElseThrow();
							assertNotNull(parameterEntity.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH));
							assertThrows(
								ContextMissingException.class,
								() -> parameterEntity.getAttribute(ATTRIBUTE_CODE),
								"The body of the other requirement widened the reference it does not name!"
							);
						}
					}
					return null;
				}
			);
		}

	}

	@Nested
	@DisplayName("Requirements contributed by the query planner")
	@Tag(REFERENCE)
	class PlannerGeneratedRequirements {

		/**
		 * `referenceHaving` makes the planner contribute a bare `referenceContent` of the filtered reference to the
		 * prefetch union, so a client requirement naming that reference together with another one meets a sibling it
		 * never wrote. The pair has to be folded per reference name; refusing it would make the query succeed or fail
		 * depending on whether the planner chose to prefetch.
		 */
		@DisplayName("Filtered reference should be fetched beside a sibling reference when prefetching is forced")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchMultiNameReferenceContentBesideReferenceHavingWhenPrefetching(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final Integer[] products = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.BRAND).isEmpty()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								and(
									entityPrimaryKeyInSet(products),
									referenceHaving(
										Entities.CATEGORY,
										entityPrimaryKeyInSet(1, 2, 3, 4, 5)
									)
								)
							),
							require(
								debug(DebugMode.PREFER_PREFETCHING),
								page(1, 4),
								entityFetch(
									referenceContent(
										new String[]{Entities.CATEGORY, Entities.BRAND},
										entityFetch(attributeContent(ATTRIBUTE_CODE))
									)
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						assertReferencedEntitiesCarryCode(product, Entities.CATEGORY);
						assertReferencedEntitiesCarryCode(product, Entities.BRAND);
					}
					return null;
				}
			);
		}

		/**
		 * Ordering by a reference property makes the planner contribute a `referenceContent` of the ordered reference
		 * carrying the sort attribute, which reaches the prefetch union beside the client's multi-name requirement -
		 * the same shape as the filtered one above, produced by the sort translator instead of the filter one.
		 */
		@DisplayName("Ordered reference should be fetched beside a sibling reference when prefetching is forced")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchMultiNameReferenceContentBesideReferenceOrderingWhenPrefetching(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final Integer[] products = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.BRAND).isEmpty()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(products)
							),
							orderBy(
								referenceProperty(
									Entities.CATEGORY,
									attributeNatural(ATTRIBUTE_CATEGORY_PRIORITY, OrderDirection.DESC)
								)
							),
							require(
								debug(DebugMode.PREFER_PREFETCHING),
								page(1, 4),
								entityFetch(
									referenceContent(
										new String[]{Entities.CATEGORY, Entities.BRAND},
										entityFetch(attributeContent(ATTRIBUTE_CODE))
									)
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						assertReferencedEntitiesCarryCode(product, Entities.CATEGORY);
						assertReferencedEntitiesCarryCode(product, Entities.BRAND);
					}
					return null;
				}
			);
		}

		/**
		 * The shape that made the strict client rule and the query planner collide: the client filters the very
		 * reference he orders by, so the bare `referenceContentWithAttributes` the sort translator contributes for
		 * `categoryPriority` meets his filtered `referenceContent` for the same reference. Judged by the
		 * client-facing rule the pair has no union and the query would be refused during *planning* - on the index
		 * path as much as on the prefetch one, for a conflict the client never wrote. The prefetch union strips both
		 * sides' restrictions instead, so the query plans, and the response still carries only the filtered
		 * references.
		 */
		@DisplayName("Filtered reference should be fetched when the same reference is ordered by")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchFilteredReferenceWhenTheSameReferenceIsOrderedBy(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final int someCategoryPrimaryKey = findCategoryOfProductWithManyCategories(originalProducts);
			final Integer[] products = getProductsReferencingCategory(originalProducts, someCategoryPrimaryKey);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						orderedByCategoryPriority(
							products,
							referenceContent(
								Entities.CATEGORY,
								filterBy(entityPrimaryKeyInSet(someCategoryPrimaryKey)),
								entityFetch(attributeContent(ATTRIBUTE_CODE))
							),
							false
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						assertEquals(
							List.of(someCategoryPrimaryKey),
							product.getReferences(Entities.CATEGORY)
								.stream()
								.map(ReferenceContract::getReferencedPrimaryKey)
								.toList(),
							"The client's filter was lost for product " + product.getPrimaryKey() + "!"
						);
						assertReferencedEntitiesCarryCode(product, Entities.CATEGORY);
					}
					return null;
				}
			);
		}

		/**
		 * The chunking half of the shape above - the client pages the reference he orders by. The page is an output
		 * projection just like the filter, so it neither reaches the prefetch union nor survives it, and the
		 * response still carries exactly the single reference the page asked for.
		 */
		@DisplayName("Paged reference should be fetched when the same reference is ordered by")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchPagedReferenceWhenTheSameReferenceIsOrderedBy(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final Integer[] products = getRequestedIdsByPredicate(
				originalProducts,
				it -> it.getReferences(Entities.CATEGORY).size() > 1
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						orderedByCategoryPriority(
							products,
							referenceContent(
								Entities.CATEGORY,
								null,
								null,
								entityFetch(attributeContent(ATTRIBUTE_CODE)),
								page(1, 1)
							),
							false
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						assertEquals(
							1, product.getReferences(Entities.CATEGORY).size(),
							"The client's page was lost for product " + product.getPrimaryKey() + "!"
						);
						assertReferencedEntitiesCarryCode(product, Entities.CATEGORY);
					}
					return null;
				}
			);
		}

		/**
		 * The guarantee the whole widening rests on: the requirement the prefetch union loads is broader than the
		 * one the client wrote, and he must never see the difference. The prefetched entity is narrowed back down
		 * from his own `EvitaRequest` - `EntityCollection#limitEntityInternal` rebuilds the predicates from it and
		 * the reference filter, order and page come from the query he actually sent - so the very same query, run
		 * once over the index path and once with prefetching forced, has to deliver the identical references.
		 */
		@DisplayName("Widened prefetch requirement should never be exposed to the client")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldNeverExposeTheWidenedPrefetchRequirementToTheClient(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final int someCategoryPrimaryKey = findCategoryOfProductWithManyCategories(originalProducts);
			final Integer[] filteredProducts = getProductsReferencingCategory(
				originalProducts, someCategoryPrimaryKey
			);
			final Integer[] pagedProducts = getRequestedIdsByPredicate(
				originalProducts,
				it -> it.getReferences(Entities.CATEGORY).size() > 1
			);
			final ReferenceContent filteredRequirement = referenceContent(
				Entities.CATEGORY,
				filterBy(entityPrimaryKeyInSet(someCategoryPrimaryKey)),
				entityFetch(attributeContent(ATTRIBUTE_CODE))
			);
			final ReferenceContent pagedRequirement = referenceContent(
				Entities.CATEGORY,
				null,
				null,
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				page(1, 1)
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Map<Integer, List<Integer>> filteredOverIndex = collectReferencedPrimaryKeys(
						session,
						orderedByCategoryPriority(filteredProducts, filteredRequirement, false),
						Entities.CATEGORY
					);
					final Map<Integer, List<Integer>> filteredOverPrefetch = collectReferencedPrimaryKeys(
						session,
						orderedByCategoryPriority(filteredProducts, filteredRequirement, true),
						Entities.CATEGORY
					);
					assertEquals(
						filteredOverIndex, filteredOverPrefetch,
						"The prefetch path returned other references than the index path!"
					);
					assertTrue(
						filteredOverIndex.values().stream()
							.allMatch(it -> it.equals(List.of(someCategoryPrimaryKey))),
						"Neither path honoured the client's filter, the test would prove nothing!"
					);

					final Map<Integer, List<Integer>> pagedOverIndex = collectReferencedPrimaryKeys(
						session,
						orderedByCategoryPriority(pagedProducts, pagedRequirement, false),
						Entities.CATEGORY
					);
					final Map<Integer, List<Integer>> pagedOverPrefetch = collectReferencedPrimaryKeys(
						session,
						orderedByCategoryPriority(pagedProducts, pagedRequirement, true),
						Entities.CATEGORY
					);
					assertEquals(
						pagedOverIndex, pagedOverPrefetch,
						"The prefetch path returned other references than the index path!"
					);
					assertTrue(
						pagedOverIndex.values().stream().allMatch(it -> it.size() == 1),
						"Neither path honoured the client's page, the test would prove nothing!"
					);
					return null;
				}
			);
		}

	}

	@Nested
	@DisplayName("Content requirement siblings of other kinds")
	class OtherContentRequirementSiblings {

		/**
		 * `entityFetchAllContentAnd` places an extra requirement beside the complete set of catch-all requirements,
		 * so the resulting fetch always holds two `attributeContent` requirements. The pair is merged into the
		 * catch-all one, which swallows the named attribute.
		 */
		@DisplayName("Entity should be fetched whole when the complete content set is extended with an attribute")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchWholeEntityWhenCompleteContentSetIsExtended(Evita evita, List<SealedEntity> originalProducts) {
			final Integer[] richProducts = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences().isEmpty() && !it.getPrices().isEmpty() &&
					it.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES) != null
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(richProducts)
							),
							require(
								page(1, 4),
								entityFetch(
									entityFetchAllContentAnd(attributeContent(ATTRIBUTE_CODE))
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						assertProduct(product, product.getPrimaryKey(), true, true, true, true);
					}
					return null;
				}
			);
		}

		/**
		 * Two `priceContent` requirements are merged into the richer fetch mode, so the entity carries every price
		 * even though one of the siblings asked only for the prices matching the query filter.
		 */
		@DisplayName("All prices should be fetched when one price content sibling asks for them")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(PRICE)
		@Test
		void shouldFetchAllPricesWhenOnePriceContentSiblingAsksForThem(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final Integer[] productsWithSeveralPriceLists = getRequestedIdsByPredicate(
				originalProducts,
				it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
					it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
					it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals)
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Map<Integer, Integer> filteredPrices = countPricesPerProduct(
						session, productsWithSeveralPriceLists,
						priceContent(PriceContentMode.RESPECTING_FILTER)
					);
					final Map<Integer, Integer> allPrices = countPricesPerProduct(
						session, productsWithSeveralPriceLists,
						priceContentAll()
					);
					final Map<Integer, Integer> united = countPricesPerProduct(
						session, productsWithSeveralPriceLists,
						priceContent(PriceContentMode.RESPECTING_FILTER),
						priceContentAll()
					);

					assertEquals(allPrices, united, "The poorer price content sibling won the merge!");
					assertTrue(
						allPrices.entrySet()
							.stream()
							.anyMatch(it -> it.getValue() > filteredPrices.get(it.getKey())),
						"The two price fetch modes deliver the same prices, the test would prove nothing!"
					);
					return null;
				}
			);
		}

		/**
		 * Returns the number of prices each of the requested products carries when fetched with the passed price
		 * requirements under the very same price filter.
		 *
		 * @param session      session to execute the query in
		 * @param primaryKeys  primary keys of the products to fetch
		 * @param requirements content requirements the products are fetched with
		 * @return map of product primary key to the number of its fetched prices
		 */
		@Nonnull
		private Map<Integer, Integer> countPricesPerProduct(
			@Nonnull EvitaSessionContract session,
			@Nonnull Integer[] primaryKeys,
			@Nonnull EntityContentRequire... requirements
		) {
			final EvitaResponse<SealedEntity> response = session.querySealedEntity(
				query(
					collection(Entities.PRODUCT),
					filterBy(
						entityPrimaryKeyInSet(primaryKeys),
						priceInCurrency(CURRENCY_EUR),
						priceInPriceLists(PRICE_LIST_BASIC)
					),
					require(
						page(1, Integer.MAX_VALUE),
						entityFetch(requirements)
					)
				)
			);

			assertFalse(
				response.getRecordData().isEmpty(),
				"No product matched the query, the test would prove nothing!"
			);
			return response.getRecordData()
				.stream()
				.collect(
					Collectors.toMap(
						EntityContract::getPrimaryKey,
						it -> it.getPrices().size()
					)
				);
		}

		/**
		 * Two `dataInLocales` requirements are merged into the wider one, so localized data of every locale are
		 * materialised even though one of the siblings asked for a single locale.
		 */
		@DisplayName("Localized data of all locales should be fetched when one locale sibling asks for them")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchAllLocalesWhenOneLocaleSiblingAsksForThem(Evita evita, List<SealedEntity> originalProducts) {
			final Integer[] bilingualProducts = getRequestedIdsByPredicate(
				originalProducts,
				it -> it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH) != null &&
					it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(bilingualProducts)
							),
							require(
								page(1, 4),
								entityFetch(
									attributeContentAll(),
									dataInLocales(LOCALE_CZECH),
									dataInLocalesAll()
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						assertProductHasAttributesInLocale(product, LOCALE_CZECH, ATTRIBUTE_NAME);
						assertProductHasAttributesInLocale(product, Locale.ENGLISH, ATTRIBUTE_NAME);
					}
					return null;
				}
			);
		}

		/**
		 * Two `hierarchyContent` requirements are merged into one, so the parent chain is delivered instead of the
		 * query being refused for holding more than a single requirement of that kind.
		 */
		@DisplayName("Parents should be fetched when hierarchy content is written twice")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(HIERARCHY)
		@Test
		void shouldFetchParentsWhenHierarchyContentIsWrittenTwice(Evita evita, Hierarchy categoryHierarchy) {
			final int theChildPrimaryKey = findDeepestCategoryPrimaryKey(categoryHierarchy);
			final int theParentPrimaryKey = Integer.parseInt(
				categoryHierarchy.getParentItem(String.valueOf(theChildPrimaryKey)).getCode()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.CATEGORY),
							filterBy(
								entityPrimaryKeyInSet(theChildPrimaryKey)
							),
							require(
								entityFetch(
									hierarchyContent(),
									hierarchyContent()
								)
							)
						)
					);

					assertEquals(1, response.getRecordData().size());
					assertEquals(
						theParentPrimaryKey,
						response.getRecordData()
							.get(0)
							.getParentEntity()
							.orElseThrow()
							.getPrimaryKey()
					);
					return null;
				}
			);
		}

	}

	@Nested
	@DisplayName("Nested fetch scopes")
	@Tag(REFERENCE)
	class NestedFetchScopes {

		/**
		 * The fold is shallow, so an `entityFetch` nested inside a `referenceContent` is reduced by the request that
		 * fetches the referenced entity. Two attribute requirements written inside that nested body must therefore be
		 * merged just like two written in the outermost one.
		 */
		@DisplayName("Referenced entity should carry both attributes requested inside its own body")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchBothAttributesRequestedInsideReferencedBody(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final Integer[] productsWithBrand = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.BRAND).isEmpty() && it.getLocales().contains(LOCALE_CZECH)
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								and(
									entityPrimaryKeyInSet(productsWithBrand),
									entityLocaleEquals(LOCALE_CZECH)
								)
							),
							require(
								page(1, 4),
								entityFetch(
									referenceContent(
										Entities.BRAND,
										entityFetch(
											attributeContent(ATTRIBUTE_CODE),
											attributeContent(ATTRIBUTE_NAME)
										)
									)
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						for (final ReferenceContract brand : product.getReferences(Entities.BRAND)) {
							final SealedEntity brandEntity = brand.getReferencedEntity().orElseThrow();
							assertNotNull(brandEntity.getAttribute(ATTRIBUTE_CODE));
							assertNotNull(brandEntity.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH));
						}
					}
					return null;
				}
			);
		}

		/**
		 * The same reduction has to happen at every level of the fetch tree - here the duplicate sits two levels deep,
		 * in the body of the entity referenced by the entity referenced by the queried product.
		 */
		@DisplayName("Duplicates should be folded two levels deep in the fetch tree")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFoldDuplicatesTwoLevelsDeep(Evita evita, List<SealedEntity> originalProducts) {
			final Integer[] productsWithBrand = getRequestedIdsByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.BRAND).isEmpty()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(productsWithBrand)
							),
							require(
								page(1, 4),
								entityFetch(
									referenceContent(
										Entities.BRAND,
										entityFetch(
											referenceContent(Entities.STORE),
											referenceContent(
												Entities.STORE,
												entityFetch(attributeContent(ATTRIBUTE_CODE))
											)
										)
									)
								)
							)
						)
					);

					assertFalse(response.getRecordData().isEmpty());
					for (final SealedEntity product : response.getRecordData()) {
						for (final ReferenceContract brand : product.getReferences(Entities.BRAND)) {
							final SealedEntity brandEntity = brand.getReferencedEntity().orElseThrow();
							final Collection<ReferenceContract> stores = brandEntity.getReferences(Entities.STORE);
							assertFalse(stores.isEmpty(), "The brand lost the store it always references!");
							for (final ReferenceContract store : stores) {
								final SealedEntity storeEntity = store.getReferencedEntity()
									.orElseThrow(
										() -> new AssertionError(
											"The body written beside the bare requirement was dropped!"
										)
									);
								assertNotNull(storeEntity.getAttribute(ATTRIBUTE_CODE));
							}
						}
					}
					return null;
				}
			);
		}

	}

	@Nested
	@DisplayName("Irreconcilable siblings")
	@Tag(REFERENCE)
	class IrreconcilableSiblings {

		/**
		 * A filter is a selection rather than a richness level, so two different selections of one reference cannot be
		 * united - neither of them may be silently preferred.
		 */
		@DisplayName("Should throw exception when two reference content siblings filter differently")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldThrowExceptionWhenReferenceFiltersDiffer(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.querySealedEntity(
							query(
								collection(Entities.PRODUCT),
								require(
									entityFetch(
										referenceContent(
											Entities.CATEGORY,
											filterBy(entityPrimaryKeyInSet(1, 2))
										),
										referenceContent(
											Entities.CATEGORY,
											filterBy(entityPrimaryKeyInSet(3, 4))
										)
									)
								)
							)
						)
					);
					assertTrue(
						exception.getMessage().contains("different filter constraints"),
						exception.getMessage()
					);
					return null;
				}
			);
		}

		/**
		 * Two different orders of one reference contradict each other just like two different filters do.
		 */
		@DisplayName("Should throw exception when two reference content siblings order differently")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldThrowExceptionWhenReferenceOrdersDiffer(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.querySealedEntity(
							query(
								collection(Entities.PRODUCT),
								require(
									entityFetch(
										referenceContent(
											Entities.CATEGORY,
											orderBy(entityPrimaryKeyNatural(OrderDirection.ASC))
										),
										referenceContent(
											Entities.CATEGORY,
											orderBy(entityPrimaryKeyNatural(OrderDirection.DESC))
										)
									)
								)
							)
						)
					);
					assertTrue(
						exception.getMessage().contains("different order constraints"),
						exception.getMessage()
					);
					return null;
				}
			);
		}

		/**
		 * Two different pages of one reference select unrelated slices, so the pair is refused rather than one of the
		 * pages being picked.
		 */
		@DisplayName("Should throw exception when two reference content siblings page differently")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldThrowExceptionWhenReferencePagesDiffer(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.querySealedEntity(
							query(
								collection(Entities.PRODUCT),
								require(
									entityFetch(
										referenceContent(Entities.CATEGORY, page(1, 2)),
										referenceContent(Entities.CATEGORY, page(2, 2))
									)
								)
							)
						)
					);
					assertTrue(
						exception.getMessage().contains("different chunking constraints"),
						exception.getMessage()
					);
					return null;
				}
			);
		}

		/**
		 * Two requirements whose reference name sets overlap are folded per reference name, so their disagreements
		 * are judged per name too - two different filters applied to the reference they share have no union and are
		 * refused, exactly as they would be if both requirements named that reference alone.
		 */
		@DisplayName("Should throw exception when siblings filter their shared reference differently")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldThrowExceptionWhenOverlappingSiblingsFilterSharedReferenceDifferently(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.querySealedEntity(
							query(
								collection(Entities.PRODUCT),
								require(
									entityFetch(
										filteredReferenceContent(
											filterBy(entityPrimaryKeyInSet(1)),
											Entities.BRAND, Entities.CATEGORY
										),
										filteredReferenceContent(
											filterBy(entityPrimaryKeyInSet(2)),
											Entities.CATEGORY, Entities.PARAMETER
										)
									)
								)
							)
						)
					);
					assertTrue(
						exception.getMessage().contains("different filter constraints"),
						exception.getMessage()
					);
					assertFalse(exception.getMessage().contains("null"), exception.getMessage());
					return null;
				}
			);
		}

		/**
		 * The price lists of an accompanying price form an ordered priority sequence, so two different sequences
		 * calculating one price cannot be united.
		 */
		@DisplayName("Should throw exception when two accompanying price siblings use different price lists")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(PRICE)
		@Test
		void shouldThrowExceptionWhenAccompanyingPriceListsDiffer(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.querySealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									priceInCurrency(CURRENCY_EUR),
									priceInPriceLists(PRICE_LIST_BASIC)
								),
								require(
									entityFetch(
										priceContent(PriceContentMode.RESPECTING_FILTER),
										accompanyingPriceContent("myPrice", PRICE_LIST_VIP),
										accompanyingPriceContent("myPrice", PRICE_LIST_B2B)
									)
								)
							)
						)
					);
					assertTrue(
						exception.getMessage().contains("different price lists"),
						exception.getMessage()
					);
					assertTrue(exception.getMessage().contains("myPrice"), exception.getMessage());
					return null;
				}
			);
		}

	}

	@Nested
	@DisplayName("EvitaQL text surface")
	@Tag(REFERENCE)
	class EvitaQlTextSurface {

		/**
		 * Parser shared by the tests of this class; the default parser is documented as thread safe and stateless.
		 */
		private final DefaultQueryParser parser = DefaultQueryParser.getInstance();

		/**
		 * Proves the duplicate survives the text surface: a query written in EvitaQL, parsed into constraints and
		 * executed, delivers both bodies exactly as the same query assembled through the Java API does.
		 */
		@DisplayName("Parsed query repeating a reference content should fetch both bodies")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Tag(ATTRIBUTE)
		@Test
		void shouldFetchBothBodiesWhenParsedQueryRepeatsReferenceContent(
			Evita evita,
			List<SealedEntity> originalProducts
		) {
			final SealedEntity productWithBrand = findEntityByPredicate(
				originalProducts,
				it -> !it.getReferences(Entities.BRAND).isEmpty() && it.getLocales().contains(LOCALE_CZECH)
			);

			final Query parsedQuery = this.parser.parseQuery(
				"query(" +
					"collection(?)," +
					"filterBy(" +
					"  entityPrimaryKeyInSet(?)," +
					"  entityLocaleEquals(?)" +
					")," +
					"require(" +
					"  entityFetch(" +
					"    referenceContent(?, entityFetch(attributeContent(?)))," +
					"    referenceContent(?, entityFetch(attributeContent(?)))" +
					"  )" +
					")" +
					")",
				Entities.PRODUCT,
				productWithBrand.getPrimaryKey(),
				LOCALE_CZECH,
				Entities.BRAND, ATTRIBUTE_CODE,
				Entities.BRAND, ATTRIBUTE_NAME
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<SealedEntity> response = session.querySealedEntity(parsedQuery);

					assertEquals(1, response.getRecordData().size());
					final Collection<ReferenceContract> brands = response.getRecordData()
						.get(0)
						.getReferences(Entities.BRAND);
					assertFalse(brands.isEmpty());
					for (final ReferenceContract brand : brands) {
						final SealedEntity brandEntity = brand.getReferencedEntity().orElseThrow();
						assertNotNull(brandEntity.getAttribute(ATTRIBUTE_CODE));
						assertNotNull(brandEntity.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH));
					}
					return null;
				}
			);
		}

		/**
		 * The refusal has to reach the text surface as well - the conflicting pair is syntactically valid EvitaQL and
		 * is rejected only when the request folds it.
		 */
		@DisplayName("Parsed query with two differently filtered reference contents should be refused")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldThrowExceptionWhenParsedQueryFiltersOneReferenceDifferently(Evita evita) {
			final Query parsedQuery = this.parser.parseQuery(
				"query(" +
					"collection(?)," +
					"require(" +
					"  entityFetch(" +
					"    referenceContent(" +
					"      ?, filterBy(entityPrimaryKeyInSet(?)), entityFetch(attributeContentAll())" +
					"    )," +
					"    referenceContent(" +
					"      ?, filterBy(entityPrimaryKeyInSet(?)), entityFetch(attributeContentAll())" +
					"    )" +
					"  )" +
					")" +
					")",
				Entities.PRODUCT,
				Entities.CATEGORY, 1,
				Entities.CATEGORY, 2
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.querySealedEntity(parsedQuery)
					);
					assertTrue(
						exception.getMessage().contains("different filter constraints"),
						exception.getMessage()
					);
					return null;
				}
			);
		}

	}

}
