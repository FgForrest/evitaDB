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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the rows `referenceContent(filterBy)` keeps of every owner of two hand-built catalogs, with the expected rows
 * spelled out. Every reference row of every owner is judged by itself, so a target referenced by owners of both
 * scopes - or by a single owner of each - keeps its row for exactly the owners whose row satisfies the filter.
 *
 * The fixtures: {@link CrossScopeSingletonFixture} for the shapes of one key in both scopes, and a catalog of
 * localized targets for the locale the filter orders the references by.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Rows kept by referenceContent(filterBy) across scopes")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class ReferenceContentCrossScopeRowsTest implements EvitaTestSupport {
	private static final String CATALOG_CROSS_SCOPE = "contentCrossScopeRows";
	private static final String CATALOG_LOCALIZED_TARGETS = "contentCrossScopeLocalizedTargets";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REFERENCE_BRANDS = "brands";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	/**
	 * The names of brands 1 to 3 in English, ascending in brand order.
	 */
	private static final String[] ENGLISH_NAMES = {"Apple", "Banana", "Cherry"};
	/**
	 * The names of brands 1 to 3 in Czech, descending in brand order.
	 */
	private static final String[] CZECH_NAMES = {"Zelí", "Mrkev", "Ananas"};
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * The page large enough to hold every entity of every catalog.
	 */
	private static final int PAGE_SIZE = 100_000;

	private TestPaths paths;
	private Evita evita;

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("ReferenceContentCrossScopeRowsTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		createCatalog(CATALOG_CROSS_SCOPE, CrossScopeSingletonFixture::setUp, CrossScopeSingletonFixture::archive);
		createCatalog(CATALOG_LOCALIZED_TARGETS, ReferenceContentCrossScopeRowsTest::setUpLocalizedTargets, session -> {
		});
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("two single-owner partitions of one key in two scopes keep the reference of both owners")
	void shouldKeepTheKeyOfTwoVirtualSingletonsInTwoScopes() {
		final Map<Integer, List<String>> expected = new TreeMap<>();
		expected.put(1, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_TWO_SINGLETONS)));
		expected.put(2, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_TWO_SINGLETONS)));
		// a single-owner partition of the archive next to a shared live partition of the same key
		expected.put(3, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_SHARED_AND_SINGLETON)));
		expected.put(4, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_SHARED_AND_SINGLETON)));
		expected.put(5, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_SHARED_AND_SINGLETON)));
		expected.put(6, List.of());
		expected.put(7, List.of());
		assertCrossScopeRows(CrossScopeSingletonFixture.REFERENCE_ITEMS, expected);
	}

	@Test
	@DisplayName("duplicates of one target in two scopes keep exactly the matching roles of both owners")
	void shouldKeepTheDuplicatesOfOneTargetInTwoScopes() {
		final Map<Integer, List<String>> expected = new TreeMap<>();
		final int both = CrossScopeSingletonFixture.MEDIA_BOTH_SCOPES;
		final int duplicates = CrossScopeSingletonFixture.MEDIA_DUPLICATES;
		expected.put(
			1, List.of(both + "/" + CrossScopeSingletonFixture.ROLE_A, both + "/" + CrossScopeSingletonFixture.ROLE_B)
		);
		expected.put(2, List.of(both + "/" + CrossScopeSingletonFixture.ROLE_A));
		expected.put(3, List.of());
		expected.put(4, List.of());
		expected.put(5, List.of());
		expected.put(6, List.of(duplicates + "/" + CrossScopeSingletonFixture.ROLE_A));
		expected.put(7, List.of(duplicates + "/" + CrossScopeSingletonFixture.ROLE_A));
		assertCrossScopeRows(CrossScopeSingletonFixture.REFERENCE_MEDIA, expected);
	}

	@Test
	@DisplayName("a root scope of the filter keeps the matching rows (D4)")
	void shouldKeepTheMatchingRowsUnderARootScope() {
		final Map<Integer, List<String>> rows = this.evita.queryCatalog(
			CATALOG_CROSS_SCOPE,
			session -> {
				final Map<Integer, List<String>> result = new TreeMap<>();
				for (final SealedEntity owner : session.queryListOfSealedEntities(
					query(
						collection(CrossScopeSingletonFixture.ENTITY_OWNER),
						filterBy(scope(Scope.LIVE)),
						require(
							page(1, PAGE_SIZE),
							entityFetch(
								referenceContentWithAttributes(
									CrossScopeSingletonFixture.REFERENCE_ITEMS,
									filterBy(
										scope(Scope.LIVE, Scope.ARCHIVED),
										attributeGreaterThan(CrossScopeSingletonFixture.ATTRIBUTE_WEIGHT, 0)
									)
								)
							)
						)
					)
				)) {
					result.put(
						owner.getPrimaryKeyOrThrowException(),
						owner.getReferences(CrossScopeSingletonFixture.REFERENCE_ITEMS).stream()
							.map(it -> describe(it, null))
							.sorted()
							.toList()
					);
				}
				return result;
			}
		);
		final Map<Integer, List<String>> expected = new TreeMap<>();
		expected.put(1, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_TWO_SINGLETONS)));
		expected.put(3, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_SHARED_AND_SINGLETON)));
		expected.put(4, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_SHARED_AND_SINGLETON)));
		expected.put(6, List.of());
		assertEquals(expected, rows);
	}

	@Test
	@DisplayName("a filter locale orders the references by the localized attribute of their entities")
	void shouldOrderTheReferencesByTheFilterLocale() {
		// without a locale of the query itself, the filter locale is the only one the ordering can use
		assertBrandOrder("without a query locale", null, LIVE, entityLocaleEquals(CZECH));
		// the filter locale wins over the query one
		assertBrandOrder("with a query locale", Locale.ENGLISH, LIVE, entityLocaleEquals(CZECH));
	}

	@DisplayName("a filter locale inside an inScope of another scope orders the references")
	@ParameterizedTest(name = "{0}")
	@MethodSource("localeConfinedToAScopeFilters")
	void shouldOrderTheReferencesByAFilterLocaleConfinedToAScope(
		@Nonnull String name,
		@Nonnull FilterConstraint referenceFilter
	) {
		// both scopes requested, and the query locale conflicts with the Czech one of the filter
		assertBrandOrder(name, Locale.ENGLISH, BOTH, referenceFilter);
	}

	/**
	 * Returns the reference filters whose Czech `entityLocaleEquals` sits in an `inScope` of the archive, whose scope
	 * none of the live rows of {@link #CATALOG_LOCALIZED_TARGETS} is in - directly, and nested in a logical container.
	 *
	 * @return the name and the reference filter of each case
	 */
	@Nonnull
	static Stream<Arguments> localeConfinedToAScopeFilters() {
		return Stream.of(
			Arguments.of("inScope of the archive", inScope(Scope.ARCHIVED, entityLocaleEquals(CZECH))),
			Arguments.of(
				"inScope of the archive around a disjunction",
				inScope(Scope.ARCHIVED, or(entityPrimaryKeyInSet(1), entityLocaleEquals(CZECH)))
			)
		);
	}

	/**
	 * Asserts the rows `referenceContent(filterBy(attributeGreaterThan(weight, 0)))` keeps of every owner of the
	 * hand-built catalog, read over both scopes.
	 *
	 * @param referenceName the reference
	 * @param expected      the kept rows of every owner, `target` or `target/role`
	 */
	private void assertCrossScopeRows(@Nonnull String referenceName, @Nonnull Map<Integer, List<String>> expected) {
		final String roleAttribute = CrossScopeSingletonFixture.REFERENCE_MEDIA.equals(referenceName) ?
			CrossScopeSingletonFixture.ATTRIBUTE_ROLE : null;
		assertEquals(
			expected,
			queryRows(referenceName, roleAttribute, attributeGreaterThan(CrossScopeSingletonFixture.ATTRIBUTE_WEIGHT, 0)),
			referenceName
		);
	}

	/**
	 * Fetches every owner of the hand-built catalog in both scopes with the references a filter keeps, and describes
	 * the kept rows of every owner - `target` for a reference without a representative attribute, `target/role` for
	 * one with - sorted, so that the comparison does not depend on the order an owner returns its references in.
	 *
	 * @param referenceName the reference
	 * @param roleAttribute the representative attribute of the reference, `null` for a reference without one
	 * @param filter        the reference filter
	 * @return the kept rows by owner primary key
	 */
	@Nonnull
	private Map<Integer, List<String>> queryRows(
		@Nonnull String referenceName,
		@Nullable String roleAttribute,
		@Nonnull FilterConstraint filter
	) {
		return this.evita.queryCatalog(
			CATALOG_CROSS_SCOPE,
			session -> {
				final List<SealedEntity> entities = session.query(
					query(
						collection(CrossScopeSingletonFixture.ENTITY_OWNER),
						filterBy(scope(BOTH)),
						require(
							page(1, PAGE_SIZE),
							entityFetch(referenceContentWithAttributes(referenceName, filterBy(filter))),
							// every alternative index set and every cacheable subtree must answer the same
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					SealedEntity.class
				).getRecordData();
				final Map<Integer, List<String>> rows = new TreeMap<>();
				for (final SealedEntity entity : entities) {
					rows.put(
						entity.getPrimaryKeyOrThrowException(),
						entity.getReferences(referenceName).stream()
							.map(it -> describe(it, roleAttribute))
							.sorted()
							.toList()
					);
				}
				return rows;
			}
		);
	}

	/**
	 * Describes one kept reference row.
	 *
	 * @param reference     the reference
	 * @param roleAttribute the representative attribute telling duplicates apart, `null` for a reference without one
	 * @return `target`, or `target/role` for a reference with a representative attribute
	 */
	@Nonnull
	private static String describe(@Nonnull ReferenceContract reference, @Nullable String roleAttribute) {
		return roleAttribute == null ?
			String.valueOf(reference.getReferencedPrimaryKey()) :
			reference.getReferencedPrimaryKey() + "/" + reference.getAttribute(roleAttribute);
	}

	/**
	 * Defines the catalog of {@link #CATALOG_LOCALIZED_TARGETS}: brands 1 to 3 with a localized sortable name whose
	 * Czech order is the reverse of the English one, and product 1 with a label in both languages referencing all three
	 * brands. The reference and the name are indexed in both scopes, so a query may request both, while every entity
	 * stays live.
	 *
	 * @param session the session to write in
	 */
	private static void setUpLocalizedTargets(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().sortableInScope(BOTH).nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(ATTRIBUTE_LABEL, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_BRANDS, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(BOTH)
			)
			.updateVia(session);
		for (int pk = 1; pk <= ENGLISH_NAMES.length; pk++) {
			session.upsertEntity(
				session.createNewEntity(ENTITY_BRAND, pk)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, ENGLISH_NAMES[pk - 1])
					.setAttribute(ATTRIBUTE_NAME, CZECH, CZECH_NAMES[pk - 1])
			);
		}
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Fruit")
				.setAttribute(ATTRIBUTE_LABEL, CZECH, "Ovoce")
				.setReference(REFERENCE_BRANDS, 1)
				.setReference(REFERENCE_BRANDS, 2)
				.setReference(REFERENCE_BRANDS, 3)
		);
	}

	/**
	 * Asserts product 1 of {@link #CATALOG_LOCALIZED_TARGETS} lists its brands in the Czech order of their names.
	 *
	 * @param label           names the case in failure messages
	 * @param queryLocale     the locale of the query itself, `null` for none
	 * @param scopes          the scopes the query requests
	 * @param referenceFilter the filter of the brands
	 */
	private void assertBrandOrder(
		@Nonnull String label,
		@Nullable Locale queryLocale,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint referenceFilter
	) {
		assertEquals(
			List.of(3, 2, 1),
			this.evita.queryCatalog(
				CATALOG_LOCALIZED_TARGETS,
				session -> {
					final SealedEntity product = session.queryOne(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1),
								queryLocale == null ? null : entityLocaleEquals(queryLocale),
								scope(scopes)
							),
							require(
								entityFetch(
									referenceContent(
										REFERENCE_BRANDS,
										filterBy(referenceFilter),
										orderBy(entityProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC))),
										entityFetch()
									)
								)
							)
						),
						SealedEntity.class
					).orElseThrow();
					return product.getReferences(REFERENCE_BRANDS).stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toList();
				}
			),
			label + ": the brands are ordered by their Czech names"
		);
	}

	/**
	 * Creates a catalog, fills it in warm-up, takes it live and applies the live changes.
	 *
	 * @param catalog the catalog
	 * @param warmUp  fills the catalog in warm-up
	 * @param live    changes the live catalog
	 */
	private void createCatalog(
		@Nonnull String catalog,
		@Nonnull Consumer<EvitaSessionContract> warmUp,
		@Nonnull Consumer<EvitaSessionContract> live
	) {
		this.evita.defineCatalog(catalog).updateViaNewSession(this.evita);
		this.evita.updateCatalog(catalog, warmUp);
		this.evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(catalog, live);
	}

}
