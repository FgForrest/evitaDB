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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.ChunkingRequireConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.Scope;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
 * Verifies that `referenceContent(filterBy)` keeps the same rows of every owner with the read switch on as with it off,
 * on the same engine and the same data. The rows of one representative reference key may be held by a partition read
 * virtually and by a reduced entity index - in one scope or in two - and the owners matching each must be united before
 * the key is restricted, exactly once: restricting it once per partition keeps only the owners of the last one. A
 * partition read virtually contributes its owner only when its single row satisfies the body - from the candidate set
 * for a row-exact body, by the exact evaluation for a negation, `attributeIs`, `groupHaving` or `inScope`, the last
 * read as this route reads it, one copy of the body per requested scope.
 *
 * Two catalogs, each maintained from its first write: {@link CrossScopeSingletonFixture} for the hand-built shapes of
 * one key in both scopes, with the expected rows spelled out, and {@link RelatedProductsShapedFixture} for thousands of
 * generated owners, chunked.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read parity of referenceContent(filterBy) over virtual reference partitions")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualReferenceContentFilterParityTest implements EvitaTestSupport {
	private static final String CATALOG_CROSS_SCOPE = "virtualContentParityCrossScope";
	private static final String CATALOG_RELATED = "virtualContentParityRelated";
	private static final String CATALOG_LOCALIZED_TARGETS = "virtualContentParityLocalizedTargets";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	/**
	 * The collections of {@link #CATALOG_LOCALIZED_TARGETS}.
	 */
	private static final Set<String> LOCALIZED_TARGET_ENTITY_TYPES = Set.of(ENTITY_PRODUCT, ENTITY_BRAND);
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
	private static final Scope[] ARCHIVED = {Scope.ARCHIVED};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	private static final Scope[][] EVERY_SCOPE_SET = {LIVE, ARCHIVED, BOTH};
	private static final int PAGE_SIZE = 100_000;

	private TestPaths paths;
	private Evita evita;

	/**
	 * A parity case of the generated catalog - one reference filter, optionally chunked.
	 *
	 * @param name      the name of the case
	 * @param filter    the reference filter
	 * @param chunk     the chunk of the references of each owner, `null` for all of them
	 * @param scopeSets the scope sets the case runs in
	 */
	private record ParityCase(
		@Nonnull String name,
		@Nonnull FilterConstraint filter,
		@Nullable ChunkingRequireConstraint chunk,
		@Nonnull Scope[][] scopeSets
	) {

		/**
		 * Creates a case run in the live scope, the archive and both.
		 *
		 * @param name   the name of the case
		 * @param filter the reference filter
		 * @param chunk  the chunk of the references of each owner, `null` for all of them
		 */
		ParityCase(@Nonnull String name, @Nonnull FilterConstraint filter, @Nullable ChunkingRequireConstraint chunk) {
			this(name, filter, chunk, EVERY_SCOPE_SET);
		}

		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}

	}

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("VirtualReferenceContentFilterParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		build(
			CATALOG_CROSS_SCOPE, CrossScopeSingletonFixture.ENTITY_TYPES,
			CrossScopeSingletonFixture::setUp, CrossScopeSingletonFixture::archive
		);
		build(
			CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_TYPES,
			RelatedProductsShapedFixture::setUp, RelatedProductsShapedFixture::archive
		);
		build(
			CATALOG_LOCALIZED_TARGETS, LOCALIZED_TARGET_ENTITY_TYPES,
			VirtualReferenceContentFilterParityTest::setUpLocalizedTargets, session -> {
			}
		);
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("two virtual singletons of one key in two scopes keep the reference of both owners")
	void shouldKeepTheKeyOfTwoVirtualSingletonsInTwoScopes() {
		final Map<Integer, List<String>> expected = new TreeMap<>();
		expected.put(1, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_TWO_SINGLETONS)));
		expected.put(2, List.of(String.valueOf(CrossScopeSingletonFixture.TARGET_TWO_SINGLETONS)));
		// a virtual singleton of the archive next to a shared live partition of the same key
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
	@DisplayName("the hand-built shapes keep the same rows in every scope set with the read switch on")
	void shouldKeepTheSameCrossScopeRowsInEveryScopeSet() {
		long virtualRowsRead = 0;
		for (final String referenceName : new String[]{
			CrossScopeSingletonFixture.REFERENCE_ITEMS, CrossScopeSingletonFixture.REFERENCE_MEDIA,
			CrossScopeSingletonFixture.REFERENCE_SOLO
		}) {
			for (final Scope[] scopes : EVERY_SCOPE_SET) {
				final FilterConstraint filter = CrossScopeSingletonFixture.REFERENCE_SOLO.equals(referenceName) ?
					entityPrimaryKeyInSet(101, 102, 103, 105) :
					attributeGreaterThan(CrossScopeSingletonFixture.ATTRIBUTE_WEIGHT, 0);
				final Comparison<Map<Integer, List<String>>> comparison = VirtualReadParity.compare(
					maintenance(CATALOG_CROSS_SCOPE), CrossScopeSingletonFixture.ENTITY_TYPES,
					() -> queryRows(
						CATALOG_CROSS_SCOPE, CrossScopeSingletonFixture.ENTITY_OWNER, scopes, referenceName,
						roleOf(referenceName), filter, null
					)
				);
				assertEquals(
					comparison.objectReading(), comparison.virtualReading().answer(),
					referenceName + " in " + Arrays.toString(scopes)
				);
				virtualRowsRead += comparison.virtualReading().virtualRowsRead();
			}
		}
		assertTrue(virtualRowsRead > 0, "The cases must read virtual rows");
	}

	/**
	 * Returns the parity cases of the generated catalog.
	 *
	 * @return the cases
	 */
	@Nonnull
	static Stream<ParityCase> relatedProductsCases() {
		final String relationType = RelatedProductsShapedFixture.ATTRIBUTE_RELATION_TYPE;
		final FilterConstraint accessories = attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_ACCESSORY);
		return Stream.of(
			new ParityCase("attributeEquals", accessories, null),
			new ParityCase("attributeEquals, first page of two", accessories, page(1, 2)),
			new ParityCase("attributeEquals, second page of two", accessories, page(2, 2)),
			new ParityCase("attributeEquals, strip from the second", accessories, strip(1, 2)),
			new ParityCase(
				"and across the rows of a mixed owner",
				and(accessories, attributeGreaterThanEquals(RelatedProductsShapedFixture.ATTRIBUTE_ORDER, 50)),
				null
			),
			new ParityCase(
				"entityHaving",
				entityHaving(attributeLessThan(RelatedProductsShapedFixture.ATTRIBUTE_RANK, 3_000)),
				page(1, 3)
			),
			new ParityCase(
				"entityPrimaryKeyInSet with missing targets",
				entityPrimaryKeyInSet(
					Stream.concat(
						IntStream.rangeClosed(1, 60).boxed(),
						IntStream.rangeClosed(
							RelatedProductsShapedFixture.PRODUCT_COUNT + 1,
							RelatedProductsShapedFixture.PRODUCT_COUNT + RelatedProductsShapedFixture.MISSING_TARGET_COUNT
						).boxed()
					).toArray(Integer[]::new)
				),
				null
			),
			new ParityCase(
				"or",
				or(
					attributeEquals(relationType, RelatedProductsShapedFixture.TYPE_SPARE),
					attributeLessThan(RelatedProductsShapedFixture.ATTRIBUTE_ORDER, 5)
				),
				strip(0, 3)
			),
			// bodies answered exactly
			new ParityCase("not", not(accessories), null),
			new ParityCase("not, second page of two", not(accessories), page(2, 2)),
			new ParityCase(
				"attributeIs NULL on value-less rows",
				attributeIs(RelatedProductsShapedFixture.ATTRIBUTE_ORDER, AttributeSpecialValue.NULL),
				null
			),
			new ParityCase(
				"not entityPrimaryKeyInSet of missing targets",
				not(entityPrimaryKeyInSet(missingTargets())),
				null
			),
			new ParityCase(
				"and with not entityPrimaryKeyInSet of missing targets",
				and(not(entityPrimaryKeyInSet(missingTargets())), accessories),
				null
			),
			new ParityCase(
				"inScope of the live scope", inScope(Scope.LIVE, accessories), null, new Scope[][]{LIVE, BOTH}
			),
			new ParityCase(
				"inScope of the archive", inScope(Scope.ARCHIVED, accessories), null, new Scope[][]{ARCHIVED, BOTH}
			),
			new ParityCase(
				"not inScope of the live scope", not(inScope(Scope.LIVE, accessories)), null, new Scope[][]{LIVE, BOTH}
			)
		);
	}

	@DisplayName("the generated catalog keeps the same rows of every owner with the read switch on")
	@ParameterizedTest(name = "{0}")
	@MethodSource("relatedProductsCases")
	void shouldKeepTheSameRowsOfTheGeneratedCatalog(@Nonnull ParityCase parityCase) {
		long virtualRowsRead = 0;
		boolean keptAny = false;
		for (final Scope[] scopes : parityCase.scopeSets()) {
			final Comparison<Map<Integer, List<String>>> comparison = VirtualReadParity.compare(
				maintenance(CATALOG_RELATED), Set.of(RelatedProductsShapedFixture.ENTITY_PRODUCT),
				() -> queryRows(
					CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_PRODUCT, scopes,
					RelatedProductsShapedFixture.REFERENCE_RELATED, RelatedProductsShapedFixture.ATTRIBUTE_RELATION_TYPE,
					parityCase.filter(), parityCase.chunk()
				)
			);
			assertEquals(
				comparison.objectReading(), comparison.virtualReading().answer(),
				parityCase + " in " + Arrays.toString(scopes)
			);
			keptAny |= comparison.objectReading().values().stream().anyMatch(it -> !it.isEmpty());
			virtualRowsRead += comparison.virtualReading().virtualRowsRead();
		}
		assertTrue(keptAny, "The premise of the case: some owner keeps a row in some scope set");
		assertTrue(virtualRowsRead > 0, parityCase + " must read virtual rows");
	}

	@DisplayName("the categories of every product keep the same rows under a group filter with the read switch on")
	@ParameterizedTest(name = "{0}")
	@MethodSource("groupFilters")
	void shouldKeepTheSameCategoryRowsUnderAGroupFilter(@Nonnull String name, @Nonnull FilterConstraint filter) {
		// `categories` is indexed in the live scope only - its singleton categories are the rows read virtually
		final Comparison<Map<Integer, List<String>>> comparison = VirtualReadParity.compare(
			maintenance(CATALOG_RELATED), Set.of(RelatedProductsShapedFixture.ENTITY_PRODUCT),
			() -> queryRows(
				CATALOG_RELATED, RelatedProductsShapedFixture.ENTITY_PRODUCT, LIVE,
				RelatedProductsShapedFixture.REFERENCE_CATEGORIES, null, filter, null
			)
		);
		assertEquals(comparison.objectReading(), comparison.virtualReading().answer(), name);
		assertTrue(
			comparison.objectReading().values().stream().anyMatch(it -> !it.isEmpty()),
			"The premise of the case: some owner keeps a row"
		);
		assertTrue(comparison.virtualReading().virtualRowsRead() > 0, name + " must read virtual rows");
	}

	/**
	 * Returns the group filters of `categories` - each keeps `groupHaving` in the body of the reference filter, where
	 * it is evaluated per row rather than separated out as a pre-filter of the targets.
	 *
	 * @return the name and the filter of each case
	 */
	@Nonnull
	static Stream<Arguments> groupFilters() {
		final String priority = RelatedProductsShapedFixture.ATTRIBUTE_PRIORITY;
		return Stream.of(
			Arguments.of(
				"groupHaving inside or", or(groupHaving(entityPrimaryKeyInSet(2)), attributeEquals(priority, 0))
			),
			Arguments.of("not groupHaving", not(groupHaving(entityPrimaryKeyInSet(1, 2, 3)))),
			Arguments.of(
				"groupHaving and an attribute of the same row",
				and(groupHaving(entityPrimaryKeyInSet(2, 4, 6)), not(attributeEquals(priority, 1)))
			)
		);
	}

	@Test
	@DisplayName("a filter locale orders the references by the localized attribute of their entities")
	void shouldOrderTheReferencesByTheFilterLocale() {
		final VirtualReferencePartitionMaintenance maintenance = maintenance(CATALOG_LOCALIZED_TARGETS);
		// without a locale of the query itself, the filter locale is the only one the ordering can use
		final Comparison<List<Integer>> withoutQueryLocale = VirtualReadParity.compare(
			maintenance, Set.of(ENTITY_PRODUCT), () -> queryBrandOrder(null)
		);
		assertEquals(List.of(3, 2, 1), withoutQueryLocale.objectReading(), "The index objects order by Czech names");
		assertEquals(
			withoutQueryLocale.objectReading(), withoutQueryLocale.virtualReading().answer(),
			"The virtual reading must order by the filter locale too"
		);
		assertTrue(
			withoutQueryLocale.virtualReading().virtualRowsRead() > 0,
			"Every brand partition is single-owner, so the fetch must read virtual rows"
		);

		final Comparison<List<Integer>> withQueryLocale = VirtualReadParity.compare(
			maintenance, Set.of(ENTITY_PRODUCT), () -> queryBrandOrder(Locale.ENGLISH)
		);
		assertEquals(List.of(3, 2, 1), withQueryLocale.objectReading(), "The filter locale wins over the query one");
		assertEquals(
			withQueryLocale.objectReading(), withQueryLocale.virtualReading().answer(),
			"The virtual reading must order by the filter locale too"
		);
		assertTrue(
			withQueryLocale.virtualReading().virtualRowsRead() > 0,
			"Every brand partition is single-owner, so the fetch must read virtual rows"
		);
	}

	@DisplayName("a filter locale inside an inScope the rows are not decided by visiting orders the references")
	@ParameterizedTest(name = "{0}")
	@MethodSource("localeConfinedToAScopeFilters")
	void shouldOrderTheReferencesByAFilterLocaleConfinedToAScope(
		@Nonnull String name,
		@Nonnull FilterConstraint referenceFilter
	) {
		// both scopes requested, so the reference filter is evaluated in a copy per scope, and the query locale
		// conflicts with the Czech one of the filter
		final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
			maintenance(CATALOG_LOCALIZED_TARGETS), Set.of(ENTITY_PRODUCT),
			() -> queryBrandOrder(Locale.ENGLISH, BOTH, referenceFilter)
		);
		assertEquals(List.of(3, 2, 1), comparison.objectReading(), name + ": the index objects order by Czech names");
		assertEquals(
			comparison.objectReading(), comparison.virtualReading().answer(),
			name + ": the virtual reading must order by the filter locale too"
		);
		assertTrue(
			comparison.virtualReading().virtualRowsRead() > 0,
			name + ": every brand partition is single-owner, so the fetch must read virtual rows"
		);
	}

	/**
	 * Returns the reference filters whose Czech `entityLocaleEquals` sits in an `inScope` of the archive, which the
	 * live rows of {@link #CATALOG_LOCALIZED_TARGETS} decide without translating its children - directly, and nested
	 * in a logical container.
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
	 * Returns the primary keys past the last product rows reference - targets the managed collection never holds.
	 *
	 * @return the missing targets
	 */
	@Nonnull
	private static Integer[] missingTargets() {
		return IntStream.rangeClosed(
			RelatedProductsShapedFixture.PRODUCT_COUNT + 1,
			RelatedProductsShapedFixture.PRODUCT_COUNT + RelatedProductsShapedFixture.MISSING_TARGET_COUNT
		).boxed().toArray(Integer[]::new);
	}

	/**
	 * Asserts the rows `referenceContent(filterBy(attributeGreaterThan(weight, 0)))` keeps of every owner of the
	 * hand-built catalog, read over both scopes with the read switch off and on, and that the read-on run read virtual
	 * rows.
	 *
	 * @param referenceName the reference
	 * @param expected      the kept rows of every owner, `target` or `target/role`
	 */
	private void assertCrossScopeRows(@Nonnull String referenceName, @Nonnull Map<Integer, List<String>> expected) {
		final Comparison<Map<Integer, List<String>>> comparison = VirtualReadParity.compare(
			maintenance(CATALOG_CROSS_SCOPE), CrossScopeSingletonFixture.ENTITY_TYPES,
			() -> queryRows(
				CATALOG_CROSS_SCOPE, CrossScopeSingletonFixture.ENTITY_OWNER, BOTH, referenceName, roleOf(referenceName),
				attributeGreaterThan(CrossScopeSingletonFixture.ATTRIBUTE_WEIGHT, 0), null
			)
		);
		assertEquals(expected, sorted(comparison.objectReading()), "The index objects must keep the expected rows");
		assertEquals(
			comparison.objectReading(), comparison.virtualReading().answer(),
			"The virtual reading must keep the same rows, in the same order"
		);
		assertTrue(comparison.virtualReading().virtualRowsRead() > 0, "The read-on run must read virtual rows");
	}

	/**
	 * Returns the kept rows of every entity sorted, for a comparison with expected rows that does not depend on the
	 * order an entity returns its references in.
	 *
	 * @param rows the kept rows by entity primary key
	 * @return the same rows, each list sorted
	 */
	@Nonnull
	private static Map<Integer, List<String>> sorted(@Nonnull Map<Integer, List<String>> rows) {
		final Map<Integer, List<String>> result = new TreeMap<>();
		rows.forEach((owner, kept) -> result.put(owner, kept.stream().sorted().toList()));
		return result;
	}

	/**
	 * Fetches every entity of a collection in the scopes with the references a filter keeps, and describes the kept
	 * rows of every entity - `target` for a reference without a representative attribute, `target/role` for one
	 * with - in the order the entity returns them, followed by the total of a chunk.
	 *
	 * @param catalog       the catalog
	 * @param entityType    the collection
	 * @param scopes        the scopes
	 * @param referenceName the reference
	 * @param roleAttribute the representative attribute of the reference, `null` for a reference without one
	 * @param filter        the reference filter
	 * @param chunk         the chunk of the references of each entity, `null` for all of them
	 * @return the kept rows by entity primary key
	 */
	@Nonnull
	private Map<Integer, List<String>> queryRows(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope[] scopes,
		@Nonnull String referenceName,
		@Nullable String roleAttribute,
		@Nonnull FilterConstraint filter,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return this.evita.queryCatalog(
			catalog,
			session -> {
				final List<SealedEntity> entities = session.query(
					query(
						collection(entityType),
						filterBy(scope(scopes)),
						require(
							page(1, PAGE_SIZE),
							entityFetch(referenceContentWithAttributes(referenceName, filterBy(filter), chunk)),
							// every alternative index set and every cacheable subtree must answer the same
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					SealedEntity.class
				).getRecordData();
				final Map<Integer, List<String>> rows = new TreeMap<>();
				for (final SealedEntity entity : entities) {
					final List<String> kept = entity.getReferences(referenceName).stream()
						.map(it -> describe(it, roleAttribute))
						.collect(Collectors.toCollection(ArrayList::new));
					if (chunk != null) {
						final DataChunk<ReferenceContract> referenceChunk = entity.getReferenceChunk(referenceName);
						kept.add("total " + referenceChunk.getTotalRecordCount());
					}
					rows.put(entity.getPrimaryKeyOrThrowException(), kept);
				}
				return rows;
			}
		);
	}

	/**
	 * Returns the representative attribute of a reference of the hand-built catalog.
	 *
	 * @param referenceName the reference
	 * @return the role attribute of `media`, `null` for the other references
	 */
	@Nullable
	private static String roleOf(@Nonnull String referenceName) {
		return CrossScopeSingletonFixture.REFERENCE_MEDIA.equals(referenceName) ?
			CrossScopeSingletonFixture.ATTRIBUTE_ROLE : null;
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
	 * brands - each brand by this product alone, so every partition of `brands` is single-owner. The reference and the
	 * name are indexed in both scopes, so a query may request both, while every entity stays live.
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
	 * Fetches product 1 of {@link #CATALOG_LOCALIZED_TARGETS} with its brands filtered by the Czech locale and ordered
	 * by their localized name, and returns the brands in the order the product returns them.
	 *
	 * @param queryLocale the locale of the query itself, `null` for none
	 * @return the brand primary keys in reference order
	 */
	@Nonnull
	private List<Integer> queryBrandOrder(@Nullable Locale queryLocale) {
		return queryBrandOrder(queryLocale, LIVE, entityLocaleEquals(CZECH));
	}

	/**
	 * Fetches product 1 of {@link #CATALOG_LOCALIZED_TARGETS} with its brands filtered by a reference filter and ordered
	 * by their localized name, and returns the brands in the order the product returns them.
	 *
	 * @param queryLocale     the locale of the query itself, `null` for none
	 * @param scopes          the scopes the query requests
	 * @param referenceFilter the filter of the brands
	 * @return the brand primary keys in reference order
	 */
	@Nonnull
	private List<Integer> queryBrandOrder(
		@Nullable Locale queryLocale,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint referenceFilter
	) {
		return this.evita.queryCatalog(
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
		this.evita.defineCatalog(catalog).updateViaNewSession(this.evita);
		for (final String entityType : entityTypes) {
			maintenance(catalog).setMaintained(entityType, true);
		}
		this.evita.updateCatalog(catalog, warmUp);
		this.evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(catalog, live);
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
