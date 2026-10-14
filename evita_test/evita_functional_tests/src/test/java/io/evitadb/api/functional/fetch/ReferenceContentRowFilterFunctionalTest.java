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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the filter of the fetched references (`referenceContent(..., filterBy(...))`) admits: every reference row
 * of every fetched owner is judged by itself - by the scope and the locales of its owner and by the row - never by
 * the other owners fetched along and never by whether a scope indexes the reference.
 *
 * ## The fixture
 *
 * `rfBrand` (locales EN, DE; `title` localized and sortable) - brands 1 (`a`, EN `Zulu`, DE `Alpha`; `brandGroups`
 * 10 with `w` 1 and 11 with `w` 2) and 2 (`b`, EN `Yankee`, DE `Beta`; `brandGroups` 10 with `w` 2) live, brand 3
 * (`a`, EN `Xray`) archived, brand 4 never created. The `brandGroups` rows also carry `note`, localized and neither
 * filterable nor unique: EN `one-ten` / DE `eins-zehn` (brand 1, group 10), EN `one-eleven` / DE `eins-elf` (brand 1,
 * group 11) and EN `two-ten` / DE `zwei-zehn` (brand 2, group 10). `rfGroup` - groups 10 (`g1`)
 * and 11 (`g2`) live, 13 (`g4`) archived, 12 never created.
 *
 * `rfProduct` (locales EN, DE) references them as:
 *
 * - `brands` - grouped, indexed in both scopes with both components; `x` and `label` (localized) filterable, `tag`
 *   (localized) unique across locales in LIVE and within a locale in ARCHIVED, `u` unique in LIVE and only filterable
 *   in ARCHIVED, `price` (`BigDecimal`, two decimal places), `keywords` (array) and `name` (localized) neither
 *   filterable nor unique,
 * - `brandsByGroup` - grouped, indexed in LIVE without the group component,
 * - `brandsUnindexed` - indexed nowhere,
 * - `brandDuplicates` - duplicates told apart by the representative `country`, grouped, indexed in LIVE, with `x` and
 *   the sortable `rank`,
 * - `externals` - to the unmanaged `rfExternal`, grouped by `rfGroup`.
 *
 * The owners:
 *
 * - **1** (LIVE; EN, DE): `brands` 1 (g10, x 5, label EN `Red` DE `Rot`, tag EN `t-1`, u `c1`, price 1.01, keywords
 *   `x y`), 2 (g11, x 7, label EN `Blue`, price 12345678901.23, keywords empty) and 4 (g10, x 5); `brandsByGroup`
 *   1 (g10) and 2 (g11); `brandsUnindexed` 1 and 2; `brandDuplicates` 1/DE (g11, x 5, rank 1), 1/CZ (g10, x 5,
 *   rank 3), 2/CZ (g10, x 7, rank 2) and 4/SK (g10, x 5, rank 4); `externals` 100 (g10), 101 (g12), 102 (g11) and
 *   103 (g13),
 * - **2** (LIVE; EN): `brands` 1 (g11, x 5, label EN `Red`); `brandsByGroup` 2 (g11),
 * - **3** (ARCHIVED; EN, DE): `brands` 1 (g10, x 5, tag EN `t-3`, u `c3`) and 3 (x 5),
 * - **5** (LIVE; EN): no reference at all.
 *
 * The planning check compiles the filter with the very compiler the fetch uses, so every case runs as a query; the
 * cases are run through `getEntity` too where the fetch has to stand on its own, as `getEntity` plans nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Row filter of referenceContent")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REFERENCE)
class ReferenceContentRowFilterFunctionalTest {
	private static final String DATA_SET = "referenceContentRowFilter";
	private static final String MUTABLE_DATA_SET = "referenceContentRowFilterMutable";
	private static final String ENTITY_PRODUCT = "rfProduct";
	private static final String ENTITY_BRAND = "rfBrand";
	private static final String ENTITY_GROUP = "rfGroup";
	private static final String ENTITY_EXTERNAL = "rfExternal";
	private static final String REF_BRANDS = "brands";
	private static final String REF_BY_GROUP = "brandsByGroup";
	private static final String REF_UNINDEXED = "brandsUnindexed";
	private static final String REF_DUPLICATES = "brandDuplicates";
	private static final String REF_EXTERNALS = "externals";
	private static final String REF_BRAND_GROUPS = "brandGroups";
	private static final String ATTR_CODE = "code";
	private static final String ATTR_TITLE = "title";
	private static final String ATTR_PRODUCT_NAME = "productName";
	private static final String ATTR_X = "x";
	private static final String ATTR_LABEL = "label";
	private static final String ATTR_TAG = "tag";
	private static final String ATTR_U = "u";
	private static final String ATTR_PRICE = "price";
	private static final String ATTR_KEYWORDS = "keywords";
	private static final String ATTR_NAME = "name";
	private static final String ATTR_COUNTRY = "country";
	private static final String ATTR_RANK = "rank";
	private static final String ATTR_W = "w";
	private static final String ATTR_NOTE = "note";
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	private static final BigDecimal HUGE_PRICE = new BigDecimal("12345678901.23");

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = DATA_SET, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, ReferenceContentRowFilterFunctionalTest::createFixture);
	}

	/**
	 * Builds the fixture described on the class for the tests changing it.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = MUTABLE_DATA_SET, readOnly = false, destroyAfterClass = true)
	void setUpMutable(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, ReferenceContentRowFilterFunctionalTest::createFixture);
	}

	/**
	 * Creates the schemas and the entities of the fixture.
	 *
	 * @param session the session to write in
	 */
	private static void createFixture(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_GROUP)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(BOTH))
			.updateVia(session);
		session.upsertEntity(session.createNewEntity(ENTITY_GROUP, 10).setAttribute(ATTR_CODE, "g1"));
		session.upsertEntity(session.createNewEntity(ENTITY_GROUP, 11).setAttribute(ATTR_CODE, "g2"));
		session.upsertEntity(session.createNewEntity(ENTITY_GROUP, 13).setAttribute(ATTR_CODE, "g4"));
		session.archiveEntity(ENTITY_GROUP, 13);

		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(BOTH))
			.withAttribute(ATTR_TITLE, String.class, thatIs -> thatIs.localized().sortableInScope(BOTH).nullable())
			.withReferenceToEntity(
				REF_BRAND_GROUPS, ENTITY_GROUP, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(BOTH)
					.withAttribute(ATTR_W, Integer.class, thatIs -> thatIs.filterableInScope(BOTH).nullable())
					.withAttribute(ATTR_NOTE, String.class, thatIs -> thatIs.localized().nullable())
			)
			.updateVia(session);
		session.upsertEntity(
			session.createNewEntity(ENTITY_BRAND, 1).setAttribute(ATTR_CODE, "a")
				.setAttribute(ATTR_TITLE, Locale.ENGLISH, "Zulu").setAttribute(ATTR_TITLE, Locale.GERMAN, "Alpha")
				.setReference(
					REF_BRAND_GROUPS, 10,
					whichIs -> whichIs.setAttribute(ATTR_W, 1)
						.setAttribute(ATTR_NOTE, Locale.ENGLISH, "one-ten")
						.setAttribute(ATTR_NOTE, Locale.GERMAN, "eins-zehn")
				)
				.setReference(
					REF_BRAND_GROUPS, 11,
					whichIs -> whichIs.setAttribute(ATTR_W, 2)
						.setAttribute(ATTR_NOTE, Locale.ENGLISH, "one-eleven")
						.setAttribute(ATTR_NOTE, Locale.GERMAN, "eins-elf")
				)
		);
		session.upsertEntity(
			session.createNewEntity(ENTITY_BRAND, 2).setAttribute(ATTR_CODE, "b")
				.setAttribute(ATTR_TITLE, Locale.ENGLISH, "Yankee").setAttribute(ATTR_TITLE, Locale.GERMAN, "Beta")
				.setReference(
					REF_BRAND_GROUPS, 10,
					whichIs -> whichIs.setAttribute(ATTR_W, 2)
						.setAttribute(ATTR_NOTE, Locale.ENGLISH, "two-ten")
						.setAttribute(ATTR_NOTE, Locale.GERMAN, "zwei-zehn")
				)
		);
		session.upsertEntity(
			session.createNewEntity(ENTITY_BRAND, 3).setAttribute(ATTR_CODE, "a")
				.setAttribute(ATTR_TITLE, Locale.ENGLISH, "Xray")
		);
		session.archiveEntity(ENTITY_BRAND, 3);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTR_PRODUCT_NAME, String.class, thatIs -> thatIs.localized().nullable())
			.withReferenceToEntity(
				REF_BRANDS, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withGroupTypeRelatedToEntity(ENTITY_GROUP)
					.indexedForFilteringAndPartitioningInScope(BOTH)
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withAttribute(ATTR_X, Integer.class, thatIs -> thatIs.filterableInScope(BOTH).nullable())
					.withAttribute(
						ATTR_LABEL, String.class, thatIs -> thatIs.localized().filterableInScope(BOTH).nullable()
					)
					.withAttribute(ATTR_TAG, String.class, thatIs -> thatIs.localized().uniqueInScope(BOTH).nullable())
					.withAttribute(ATTR_U, String.class, thatIs -> thatIs.uniqueInScope(Scope.LIVE).nullable())
					.withAttribute(ATTR_PRICE, BigDecimal.class, thatIs -> thatIs.indexDecimalPlaces(2).nullable())
					.withAttribute(ATTR_KEYWORDS, String[].class, thatIs -> thatIs.nullable())
					.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized().nullable())
			)
			.withReferenceToEntity(
				REF_BY_GROUP, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withGroupTypeRelatedToEntity(ENTITY_GROUP)
					.indexedForFilteringInScope(Scope.LIVE)
					.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY)
			)
			.withReferenceToEntity(REF_UNINDEXED, ENTITY_BRAND, Cardinality.ZERO_OR_MORE)
			.withReferenceToEntity(
				REF_DUPLICATES, ENTITY_BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.withGroupTypeRelatedToEntity(ENTITY_GROUP)
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withAttribute(
						ATTR_COUNTRY, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).representative()
					)
					.withAttribute(ATTR_X, Integer.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable())
					.withAttribute(
						ATTR_RANK, Integer.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
					)
			)
			.withReferenceTo(
				REF_EXTERNALS, ENTITY_EXTERNAL, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.withGroupTypeRelatedToEntity(ENTITY_GROUP)
			)
			.updateVia(session);
		// `tag` is unique across locales in LIVE and within a locale in ARCHIVED - the builder sets one uniqueness for
		// every scope it names - and `u` is unique in LIVE and only filterable in ARCHIVED, which the builder refuses
		// as a whole ("cannot be both unique and filterable"), so both go through the raw mutations
		session.updateEntitySchema(
			new ModifyEntitySchemaMutation(
				ENTITY_PRODUCT,
				new ModifyReferenceAttributeSchemaMutation(
					REF_BRANDS,
					new SetAttributeSchemaUniqueMutation(
						ATTR_TAG,
						new ScopedAttributeUniquenessType[]{
							new ScopedAttributeUniquenessType(
								Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
							),
							new ScopedAttributeUniquenessType(
								Scope.ARCHIVED, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
							)
						}
					)
				),
				new ModifyReferenceAttributeSchemaMutation(
					REF_BRANDS, new SetAttributeSchemaFilterableMutation(ATTR_U, new Scope[]{Scope.ARCHIVED})
				)
			)
		);

		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setAttribute(ATTR_PRODUCT_NAME, Locale.ENGLISH, "One")
				.setAttribute(ATTR_PRODUCT_NAME, Locale.GERMAN, "Eins")
				.setReference(
					REF_BRANDS, 1,
					whichIs -> whichIs.setGroup(10)
						.setAttribute(ATTR_X, 5)
						.setAttribute(ATTR_LABEL, Locale.ENGLISH, "Red")
						.setAttribute(ATTR_LABEL, Locale.GERMAN, "Rot")
						.setAttribute(ATTR_TAG, Locale.ENGLISH, "t-1")
						.setAttribute(ATTR_U, "c1")
						.setAttribute(ATTR_PRICE, new BigDecimal("1.01"))
						.setAttribute(ATTR_KEYWORDS, new String[]{"x", "y"})
				)
				.setReference(
					REF_BRANDS, 2,
					whichIs -> whichIs.setGroup(11)
						.setAttribute(ATTR_X, 7)
						.setAttribute(ATTR_LABEL, Locale.ENGLISH, "Blue")
						.setAttribute(ATTR_PRICE, HUGE_PRICE)
						.setAttribute(ATTR_KEYWORDS, new String[0])
				)
				.setReference(REF_BRANDS, 4, whichIs -> whichIs.setGroup(10).setAttribute(ATTR_X, 5))
				.setReference(REF_BY_GROUP, 1, whichIs -> whichIs.setGroup(10))
				.setReference(REF_BY_GROUP, 2, whichIs -> whichIs.setGroup(11))
				.setReference(REF_UNINDEXED, 1)
				.setReference(REF_UNINDEXED, 2)
				.setOrUpdateReference(
					REF_DUPLICATES, 1, Functions.alwaysFalse(),
					whichIs -> whichIs.setGroup(11).setAttribute(ATTR_COUNTRY, "DE").setAttribute(ATTR_X, 5)
						.setAttribute(ATTR_RANK, 1)
				)
				.setOrUpdateReference(
					REF_DUPLICATES, 1, Functions.alwaysFalse(),
					whichIs -> whichIs.setGroup(10).setAttribute(ATTR_COUNTRY, "CZ").setAttribute(ATTR_X, 5)
						.setAttribute(ATTR_RANK, 3)
				)
				.setOrUpdateReference(
					REF_DUPLICATES, 2, Functions.alwaysFalse(),
					whichIs -> whichIs.setGroup(10).setAttribute(ATTR_COUNTRY, "CZ").setAttribute(ATTR_X, 7)
						.setAttribute(ATTR_RANK, 2)
				)
				.setOrUpdateReference(
					REF_DUPLICATES, 4, Functions.alwaysFalse(),
					whichIs -> whichIs.setGroup(10).setAttribute(ATTR_COUNTRY, "SK").setAttribute(ATTR_X, 5)
						.setAttribute(ATTR_RANK, 4)
				)
				.setReference(REF_EXTERNALS, 100, whichIs -> whichIs.setGroup(10))
				.setReference(REF_EXTERNALS, 101, whichIs -> whichIs.setGroup(12))
				.setReference(REF_EXTERNALS, 102, whichIs -> whichIs.setGroup(11))
				.setReference(REF_EXTERNALS, 103, whichIs -> whichIs.setGroup(13))
		);
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, 2)
				.setAttribute(ATTR_PRODUCT_NAME, Locale.ENGLISH, "Two")
				.setReference(
					REF_BRANDS, 1,
					whichIs -> whichIs.setGroup(11)
						.setAttribute(ATTR_X, 5)
						.setAttribute(ATTR_LABEL, Locale.ENGLISH, "Red")
				)
				.setReference(REF_BY_GROUP, 2, whichIs -> whichIs.setGroup(11))
		);
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, 3)
				.setAttribute(ATTR_PRODUCT_NAME, Locale.ENGLISH, "Three")
				.setAttribute(ATTR_PRODUCT_NAME, Locale.GERMAN, "Drei")
				.setReference(
					REF_BRANDS, 1,
					whichIs -> whichIs.setGroup(10)
						.setAttribute(ATTR_X, 5)
						.setAttribute(ATTR_TAG, Locale.ENGLISH, "t-3")
						.setAttribute(ATTR_U, "c3")
				)
				.setReference(REF_BRANDS, 3, whichIs -> whichIs.setAttribute(ATTR_X, 5))
		);
		session.archiveEntity(ENTITY_PRODUCT, 3);
		session.upsertEntity(
			session.createNewEntity(ENTITY_PRODUCT, 5).setAttribute(ATTR_PRODUCT_NAME, Locale.ENGLISH, "Five")
		);
	}

	/**
	 * Fetches the owners with the references the requirement keeps and describes the kept rows of every owner, sorted:
	 * `target`, or `target/country` for `brandDuplicates`.
	 *
	 * @param evita            the engine instance
	 * @param scopes           the scopes of the query
	 * @param queryLocale      the locale of the query, NULL for none
	 * @param referenceName    the reference whose rows are described
	 * @param referenceContent the requirement
	 * @param owners           the primary keys of the owners
	 * @return the kept rows by owner
	 */
	@Nonnull
	private static Map<Integer, List<String>> queryRows(
		@Nonnull Evita evita,
		@Nonnull Scope[] scopes,
		@Nullable Locale queryLocale,
		@Nonnull String referenceName,
		@Nonnull ReferenceContent referenceContent,
		@Nonnull int... owners
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return queryRows(session, scopes, queryLocale, referenceName, referenceContent, owners);
			}
		);
	}

	/**
	 * Fetches the owners in the session - see the variant taking the engine instance.
	 *
	 * @param session          the session
	 * @param scopes           the scopes of the query
	 * @param queryLocale      the locale of the query, NULL for none
	 * @param referenceName    the reference whose rows are described
	 * @param referenceContent the requirement
	 * @param owners           the primary keys of the owners
	 * @return the kept rows by owner
	 */
	@Nonnull
	private static Map<Integer, List<String>> queryRows(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope[] scopes,
		@Nullable Locale queryLocale,
		@Nonnull String referenceName,
		@Nonnull ReferenceContent referenceContent,
		@Nonnull int... owners
	) {
		final FilterConstraint[] ownerFilter = queryLocale == null ?
			new FilterConstraint[]{entityPrimaryKeyInSet(owners), scope(scopes)} :
			new FilterConstraint[]{entityPrimaryKeyInSet(owners), scope(scopes), entityLocaleEquals(queryLocale)};
		final Map<Integer, List<String>> rows = new TreeMap<>();
		for (SealedEntity owner : session.queryListOfSealedEntities(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(ownerFilter),
				require(page(1, 100), entityFetch(referenceContent))
			)
		)) {
			rows.put(owner.getPrimaryKeyOrThrowException(), describeSorted(owner.getReferences(referenceName)));
		}
		return rows;
	}

	/**
	 * Queries owner 1, in no locale, with the brands the requirement keeps, and describes the groups each kept brand
	 * keeps of its own `brandGroups`, sorted.
	 *
	 * @param evita  the engine instance
	 * @param brands the requirement of the brands, fetching their bodies with their `brandGroups`
	 * @return the kept groups by kept brand
	 */
	@Nonnull
	private static Map<Integer, List<String>> groupsOfBrandsOfOwnerOne(
		@Nonnull Evita evita,
		@Nonnull ReferenceContent brands
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity owner = session.queryOneSealedEntity(
					query(collection(ENTITY_PRODUCT), filterBy(entityPrimaryKeyInSet(1)), require(entityFetch(brands)))
				).orElseThrow();
				final Map<Integer, List<String>> result = new TreeMap<>();
				for (ReferenceContract brand : owner.getReferences(REF_BRANDS)) {
					result.put(
						brand.getReferencedPrimaryKey(),
						describeSorted(brand.getReferencedEntity().orElseThrow().getReferences(REF_BRAND_GROUPS))
					);
				}
				return result;
			}
		);
	}

	/**
	 * Fetches one owner through `getEntity`, which plans no query, and describes the kept rows, sorted.
	 *
	 * @param evita            the engine instance
	 * @param owner            the primary key of the owner
	 * @param scopes           the scopes the owner is looked up in
	 * @param referenceName    the reference whose rows are described
	 * @param requirements     the requirements
	 * @return the kept rows
	 */
	@Nonnull
	private static List<String> getRows(
		@Nonnull Evita evita,
		int owner,
		@Nonnull Scope[] scopes,
		@Nonnull String referenceName,
		@Nonnull EntityContentRequire... requirements
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return describeSorted(
					session.getEntity(ENTITY_PRODUCT, owner, scopes, requirements)
						.orElseThrow()
						.getReferences(referenceName)
				);
			}
		);
	}

	/**
	 * Describes the rows, sorted.
	 *
	 * @param references the rows
	 * @return `target`, or `target/country` for `brandDuplicates`
	 */
	@Nonnull
	private static List<String> describeSorted(@Nonnull Collection<ReferenceContract> references) {
		return describe(references).stream().sorted().toList();
	}

	/**
	 * Describes the rows in their order.
	 *
	 * @param references the rows
	 * @return `target`, or `target/country` for `brandDuplicates`
	 */
	@Nonnull
	private static List<String> describe(@Nonnull Collection<ReferenceContract> references) {
		final List<String> result = new ArrayList<>(references.size());
		for (ReferenceContract reference : references) {
			result.add(
				REF_DUPLICATES.equals(reference.getReferenceName()) ?
					reference.getReferencedPrimaryKey() + "/" + reference.getAttribute(ATTR_COUNTRY) :
					String.valueOf(reference.getReferencedPrimaryKey())
			);
		}
		return result;
	}

	/**
	 * Builds the expected rows by owner.
	 *
	 * @param ownersAndRows alternating owner primary keys and lists of rows
	 * @return the rows by owner
	 */
	@Nonnull
	private static Map<Integer, List<String>> rows(@Nonnull Object... ownersAndRows) {
		final Map<Integer, List<String>> result = new TreeMap<>();
		for (int i = 0; i < ownersAndRows.length; i += 2) {
			@SuppressWarnings("unchecked") final List<String> rows = (List<String>) ownersAndRows[i + 1];
			result.put((Integer) ownersAndRows[i], rows);
		}
		return result;
	}

	/**
	 * Asserts that fetching owner 1 through `getEntity` refuses the filter with an {@link EvitaInvalidUsageException}
	 * whose message contains all fragments.
	 *
	 * @param evita     the engine instance
	 * @param owner     the primary key of the owner
	 * @param filterBy  the filter of `brands`
	 * @param fragments the fragments of the expected message
	 */
	private static void assertRefusedAtFetch(
		@Nonnull Evita evita,
		int owner,
		@Nonnull FilterBy filterBy,
		@Nonnull String... fragments
	) {
		final EvitaInvalidUsageException refusal = assertThrows(
			EvitaInvalidUsageException.class,
			() -> getRows(evita, owner, LIVE, REF_BRANDS, referenceContent(REF_BRANDS, filterBy))
		);
		for (String fragment : fragments) {
			assertTrue(
				refusal.getMessage().contains(fragment),
				() -> "The message `" + refusal.getMessage() + "` must contain `" + fragment + "`"
			);
		}
	}

	/**
	 * Asserts that a query refuses the filter while it is planned, whatever the data: it is run for owner 5, which
	 * holds no reference, and for no owner at all - so the fetch has no row to evaluate the filter on, or nothing to
	 * fetch in the first place.
	 *
	 * @param evita         the engine instance
	 * @param referenceName the reference whose fetched rows are filtered
	 * @param filterBy      the filter of the reference
	 * @param expectedType  the class of the expected refusal
	 * @param fragments     the fragments of the expected message
	 */
	private static void assertRefusedAtPlanning(
		@Nonnull Evita evita,
		@Nonnull String referenceName,
		@Nonnull FilterBy filterBy,
		@Nonnull Class<? extends EvitaInvalidUsageException> expectedType,
		@Nonnull String... fragments
	) {
		for (int owner : new int[]{5, 999}) {
			final EvitaInvalidUsageException refusal = assertThrows(
				expectedType,
				() -> queryRows(evita, LIVE, null, referenceName, referenceContent(referenceName, filterBy), owner),
				() -> filterBy + " must be refused while planning a query for owner " + owner
			);
			for (String fragment : fragments) {
				assertTrue(
					refusal.getMessage().contains(fragment),
					() -> "The message `" + refusal.getMessage() + "` must contain `" + fragment + "`"
				);
			}
		}
	}

	@Nested
	@DisplayName("Verdicts per row")
	class VerdictsPerRow {

		@Test
		@DisplayName("a root groupHaving and its sibling judge the same row of each owner")
		@UseDataSet(DATA_SET)
		void shouldJudgeRootGroupHavingAndSiblingOnTheSameRow(Evita evita) {
			final FilterBy filter = filterBy(groupHaving(entityPrimaryKeyInSet(10)), attributeEquals(ATTR_X, 5));
			// owner 2 holds brand 1 with x 5 in group 11 - owner 1's row in group 10 must not admit it
			assertEquals(
				rows(1, List.of("1"), 2, List.of()),
				queryRows(evita, LIVE, null, REF_BRANDS, referenceContent(REF_BRANDS, filter), 1, 2)
			);
			// the same within one owner: duplicate 1/DE is in group 11, duplicate 1/CZ in group 10
			assertEquals(
				rows(1, List.of("1/CZ")),
				queryRows(
					evita, LIVE, null, REF_DUPLICATES, referenceContentWithAttributes(REF_DUPLICATES, filter), 1
				)
			);
		}

		@Test
		@DisplayName("a group filter needs no indexed group component and fails on no data")
		@UseDataSet(DATA_SET)
		void shouldFilterByGroupWithoutGroupComponent(Evita evita) {
			final ReferenceContent byGroup = referenceContent(
				REF_BY_GROUP, filterBy(groupHaving(attributeEquals(ATTR_CODE, "g1")))
			);
			assertEquals(rows(1, List.of("1")), queryRows(evita, LIVE, null, REF_BY_GROUP, byGroup, 1));
			assertEquals(rows(2, List.of()), queryRows(evita, LIVE, null, REF_BY_GROUP, byGroup, 2));
			// below the root too - a group filter of the rows needs the group component nowhere
			final FilterConstraint inGroupG1 = groupHaving(attributeEquals(ATTR_CODE, "g1"));
			assertEquals(
				rows(1, List.of("1"), 2, List.of()),
				queryRows(
					evita, LIVE, null, REF_BY_GROUP,
					referenceContent(REF_BY_GROUP, filterBy(or(inGroupG1, entityPrimaryKeyInSet(999)))), 1, 2
				)
			);
			assertEquals(
				rows(1, List.of("2"), 2, List.of("2")),
				queryRows(
					evita, LIVE, null, REF_BY_GROUP, referenceContent(REF_BY_GROUP, filterBy(not(inGroupG1))), 1, 2
				)
			);
		}

		@Test
		@DisplayName("a filter of a reference indexed nowhere is applied")
		@UseDataSet(DATA_SET)
		void shouldApplyFilterOfReferenceIndexedNowhere(Evita evita) {
			// not a root `entityPrimaryKeyInSet`: that one narrows the rows read from the storage, filter or not
			assertEquals(
				rows(1, List.of("1")),
				queryRows(
					evita, LIVE, null, REF_UNINDEXED,
					referenceContent(REF_UNINDEXED, filterBy(not(entityPrimaryKeyInSet(2)))), 1
				)
			);
		}

		@Test
		@DisplayName("a filter of referenceContent() without a name is applied to every reference")
		@UseDataSet(DATA_SET)
		void shouldApplyFilterOfReferenceContentWithoutNameToEveryReference(Evita evita) {
			final ReferenceContent everyReference = new ReferenceContent(
				null, ManagedReferencesBehaviour.ANY, ArrayUtils.EMPTY_STRING_ARRAY,
				new RequireConstraint[]{attributeContentAll()},
				new Constraint<?>[]{filterBy(entityPrimaryKeyInSet(1, 100))}
			);
			assertEquals(rows(1, List.of("1")), queryRows(evita, LIVE, null, REF_BRANDS, everyReference, 1));
			assertEquals(rows(1, List.of("1")), queryRows(evita, LIVE, null, REF_BY_GROUP, everyReference, 1));
			assertEquals(rows(1, List.of("1")), queryRows(evita, LIVE, null, REF_UNINDEXED, everyReference, 1));
			assertEquals(
				rows(1, List.of("1/CZ", "1/DE")), queryRows(evita, LIVE, null, REF_DUPLICATES, everyReference, 1)
			);
			assertEquals(rows(1, List.of("100")), queryRows(evita, LIVE, null, REF_EXTERNALS, everyReference, 1));
		}

		@Test
		@DisplayName("a row is judged by its own attribute value - an array by any element, a string by a search")
		@UseDataSet(DATA_SET)
		void shouldJudgeArrayAndStringLeaves(Evita evita) {
			for (FilterBy byKeyword : List.of(
				filterBy(attributeEquals(ATTR_KEYWORDS, "y")), filterBy(attributeInSet(ATTR_KEYWORDS, "q", "x"))
			)) {
				final ReferenceContent requirement = referenceContent(REF_BRANDS, byKeyword);
				assertEquals(List.of("1"), getRows(evita, 1, LIVE, REF_BRANDS, requirement));
				// `keywords` is filterable nowhere - the planning check admits it all the same
				assertEquals(rows(1, List.of("1")), queryRows(evita, LIVE, null, REF_BRANDS, requirement, 1));
			}
			assertEquals(
				rows(1, List.of("1"), 2, List.of("1")),
				queryRows(
					evita, LIVE, Locale.ENGLISH, REF_BRANDS,
					referenceContent(REF_BRANDS, filterBy(attributeStartsWith(ATTR_LABEL, "Re"))), 1, 2
				)
			);
		}

		@Test
		@DisplayName("a negated entityHaving and a groupHaving are judged per row when fetched by getEntity")
		@UseDataSet(DATA_SET)
		void shouldJudgeNestedFiltersWhenFetchedByGetEntity(Evita evita) {
			assertEquals(
				List.of("2"),
				getRows(
					evita, 1, LIVE, REF_BRANDS,
					referenceContent(REF_BRANDS, filterBy(not(entityHaving(attributeEquals(ATTR_CODE, "a")))))
				)
			);
			assertEquals(
				List.of("2"),
				getRows(
					evita, 1, LIVE, REF_BRANDS,
					referenceContent(REF_BRANDS, filterBy(groupHaving(attributeEquals(ATTR_CODE, "g2"))))
				)
			);
		}

		@Test
		@DisplayName("the references of a fetched referenced entity are judged per row of that entity")
		@UseDataSet(DATA_SET)
		void shouldJudgeRowsOfReferencedEntityAtSecondLevel(Evita evita) {
			final Map<Integer, List<String>> groupsOfBrands = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity owner = session.getEntity(
						ENTITY_PRODUCT, 1,
						referenceContent(
							REF_BRANDS,
							filterBy(entityPrimaryKeyInSet(1, 2)),
							entityFetch(referenceContent(REF_BRAND_GROUPS, filterBy(attributeEquals(ATTR_W, 2))))
						)
					).orElseThrow();
					final Map<Integer, List<String>> result = new TreeMap<>();
					for (ReferenceContract brand : owner.getReferences(REF_BRANDS)) {
						result.put(
							brand.getReferencedPrimaryKey(),
							describeSorted(brand.getReferencedEntity().orElseThrow().getReferences(REF_BRAND_GROUPS))
						);
					}
					return result;
				}
			);
			assertEquals(rows(1, List.of("11"), 2, List.of("10")), groupsOfBrands);
		}

		@Test
		@DisplayName("an enriched entity gets the rows the filter admits")
		@UseDataSet(DATA_SET)
		void shouldFilterRowsOfEnrichedEntity(Evita evita) {
			final List<String> enriched = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity owner = session.getEntity(ENTITY_PRODUCT, 1, attributeContentAll())
						.orElseThrow();
					return describeSorted(
						session.enrichEntity(
							owner,
							referenceContent(REF_BRANDS, filterBy(entityHaving(attributeEquals(ATTR_CODE, "a"))))
						).getReferences(REF_BRANDS)
					);
				}
			);
			assertEquals(List.of("1"), enriched);
		}

		@Test
		@DisplayName("under EXISTING only duplicates to existing entities pass, with a filter or without")
		@UseDataSet(DATA_SET)
		void shouldAdmitExistingDuplicatesOnly(Evita evita) {
			// 4/SK refers to the missing brand 4 - it holds x 5, so only its target keeps it out under the filter
			assertEquals(
				rows(1, List.of("1/CZ", "1/DE", "2/CZ", "4/SK")),
				queryRows(evita, LIVE, null, REF_DUPLICATES, duplicates(ManagedReferencesBehaviour.ANY), 1)
			);
			assertEquals(
				rows(1, List.of("1/CZ", "1/DE", "2/CZ")),
				queryRows(evita, LIVE, null, REF_DUPLICATES, duplicates(ManagedReferencesBehaviour.EXISTING), 1)
			);
			for (ManagedReferencesBehaviour behaviour : ManagedReferencesBehaviour.values()) {
				assertEquals(
					rows(1, List.of("1/CZ", "1/DE")),
					queryRows(
						evita, LIVE, null, REF_DUPLICATES,
						duplicates(behaviour, filterBy(attributeEquals(ATTR_X, 5))), 1
					),
					behaviour::toString
				);
			}
		}

		/**
		 * Returns the requirement of `brandDuplicates` with their attributes.
		 *
		 * @param behaviour whether only the rows to existing referenced entities are fetched
		 * @param filterBy  the filter of the rows, none when empty
		 * @return the requirement
		 */
		@Nonnull
		private static ReferenceContent duplicates(
			@Nonnull ManagedReferencesBehaviour behaviour,
			@Nonnull FilterBy... filterBy
		) {
			return new ReferenceContent(
				null, behaviour, new String[]{REF_DUPLICATES}, new RequireConstraint[]{attributeContentAll()}, filterBy
			);
		}

	}

	@Nested
	@DisplayName("Locale and per-scope readings of the attribute leaves")
	class Readings {

		@Test
		@DisplayName("a localized leaf reads the locale of the filter, not of the query")
		@UseDataSet(DATA_SET)
		void shouldReadLocaleOfTheFilter(Evita evita) {
			assertEquals(
				rows(1, List.of("1")),
				queryRows(
					evita, LIVE, Locale.ENGLISH, REF_BRANDS,
					referenceContent(
						REF_BRANDS, filterBy(entityLocaleEquals(Locale.GERMAN), attributeEquals(ATTR_LABEL, "Rot"))
					),
					1
				)
			);
			// a query naming no locale passes the planning check with the locale of the filter
			assertEquals(
				rows(1, List.of("1")),
				queryRows(
					evita, LIVE, null, REF_BRANDS,
					referenceContent(
						REF_BRANDS, filterBy(entityLocaleEquals(Locale.GERMAN), attributeEquals(ATTR_LABEL, "Rot"))
					),
					1
				)
			);
		}

		@Test
		@DisplayName("a nested referenceContent reads the locale of the enclosing filter, at planning too")
		@UseDataSet(DATA_SET)
		void shouldReadLocaleOfEnclosingFilterAtSecondLevel(Evita evita) {
			// the query names no locale and the nested filter none either: the brands are fetched in German, the
			// locale of their filter, so the localized `note` of their rows is read in German - by the fetch and by
			// the planning check alike
			final Function<String, ReferenceContent> brandsWithGroupsNoted =
				note -> referenceContent(
					REF_BRANDS,
					filterBy(entityLocaleEquals(Locale.GERMAN), entityPrimaryKeyInSet(1, 2)),
					entityFetch(referenceContent(REF_BRAND_GROUPS, filterBy(attributeEquals(ATTR_NOTE, note))))
				);
			assertEquals(
				rows(1, List.of("11"), 2, List.of()),
				groupsOfBrandsOfOwnerOne(evita, brandsWithGroupsNoted.apply("eins-elf"))
			);
			assertEquals(
				rows(1, List.of(), 2, List.of()),
				groupsOfBrandsOfOwnerOne(evita, brandsWithGroupsNoted.apply("one-eleven"))
			);
			// without the enclosing locale the nested leaf has none and is refused while planning, whatever the data
			assertThrows(
				EntityLocaleMissingException.class,
				() -> queryRows(
					evita, LIVE, null, REF_BRANDS,
					referenceContent(
						REF_BRANDS,
						filterBy(entityPrimaryKeyInSet(1, 2)),
						entityFetch(
							referenceContent(REF_BRAND_GROUPS, filterBy(attributeEquals(ATTR_NOTE, "eins-elf")))
						)
					),
					999
				)
			);
		}

		@Test
		@DisplayName("a localized leaf reads the locale of the filter found inside entityHaving")
		@UseDataSet(DATA_SET)
		void shouldReadLocaleFoundInsideEntityHaving(Evita evita) {
			assertEquals(
				rows(1, List.of("1")),
				queryRows(
					evita, LIVE, Locale.ENGLISH, REF_BRANDS,
					referenceContent(
						REF_BRANDS,
						filterBy(entityHaving(entityLocaleEquals(Locale.GERMAN)), attributeEquals(ATTR_LABEL, "Rot"))
					),
					1
				)
			);
		}

		@Test
		@DisplayName("each owner reads the attribute by the declaration of its own scope")
		@UseDataSet(DATA_SET)
		void shouldReadEachOwnerByTheDeclarationOfItsScope(Evita evita) {
			// `tag` is unique across locales in LIVE: owner 1's English value counts under a German query; within
			// a locale in ARCHIVED: owner 3's English value does not
			assertEquals(
				rows(1, List.of("1"), 3, List.of()),
				queryRows(
					evita, BOTH, Locale.GERMAN, REF_BRANDS,
					referenceContent(REF_BRANDS, filterBy(attributeInSet(ATTR_TAG, "t-1", "t-3"))), 1, 3
				)
			);
		}

		@Test
		@DisplayName("inScope of a scope named by the root scope of the filter is answered by a query of another scope")
		@UseDataSet(DATA_SET)
		void shouldAnswerInScopeOfScopeNamedByRootScope(Evita evita) {
			// the owners live in LIVE, so the rows satisfy a condition confined to ARCHIVED; brand 4 does not exist
			assertEquals(
				rows(1, List.of("1", "2"), 2, List.of("1")),
				queryRows(
					evita, new Scope[]{Scope.LIVE}, null, REF_BRANDS,
					referenceContent(
						REF_BRANDS, filterBy(scope(Scope.ARCHIVED), inScope(Scope.ARCHIVED, attributeEquals(ATTR_X, 5)))
					),
					1, 2
				)
			);
		}

		@Test
		@DisplayName("an attribute unique in one scope and only filterable in another matches in both")
		@UseDataSet(DATA_SET)
		void shouldMatchAttributeUniqueInOneScopeAndFilterableInAnother(Evita evita) {
			// an attribute neither filterable in every scope nor unique in every scope is admitted, and each owner
			// reads it by the declaration of its own scope - through a query and through `getEntity` alike
			assertEquals(
				rows(1, List.of(), 3, List.of("1")),
				queryRows(
					evita, BOTH, null, REF_BRANDS,
					referenceContent(REF_BRANDS, filterBy(attributeEquals(ATTR_U, "c3"))), 1, 3
				)
			);
			assertEquals(
				rows(1, List.of("1"), 3, List.of("1")),
				queryRows(
					evita, BOTH, null, REF_BRANDS,
					referenceContent(REF_BRANDS, filterBy(attributeInSet(ATTR_U, "c1", "c3"))), 1, 3
				)
			);
			assertEquals(
				List.of(),
				getRows(
					evita, 1, BOTH, REF_BRANDS, referenceContent(REF_BRANDS, filterBy(attributeEquals(ATTR_U, "c3")))
				)
			);
			assertEquals(
				List.of("1"),
				getRows(
					evita, 3, BOTH, REF_BRANDS, referenceContent(REF_BRANDS, filterBy(attributeEquals(ATTR_U, "c3")))
				)
			);
			for (int owner : new int[]{1, 3}) {
				assertEquals(
					List.of("1"),
					getRows(
						evita, owner, BOTH, REF_BRANDS,
						referenceContent(REF_BRANDS, filterBy(attributeInSet(ATTR_U, "c1", "c3")))
					)
				);
			}
		}

		@Test
		@DisplayName("attributeIs without any locale reads a value in any locale in every scope")
		@UseDataSet(DATA_SET)
		void shouldReadAttributeIsWithoutLocaleInAnyLocale(Evita evita) {
			assertEquals(
				rows(1, List.of("1"), 3, List.of("1")),
				queryRows(
					evita, BOTH, null, REF_BRANDS,
					referenceContent(REF_BRANDS, filterBy(attributeIsNotNull(ATTR_TAG))), 1, 3
				)
			);
			assertEquals(
				rows(1, List.of("2"), 3, List.of("3")),
				queryRows(
					evita, BOTH, null, REF_BRANDS,
					referenceContent(REF_BRANDS, filterBy(attributeIsNull(ATTR_TAG))), 1, 3
				)
			);
		}

		@Test
		@DisplayName("attributeIs of a localized attribute without any locale is refused at fetch")
		@UseDataSet(DATA_SET)
		void shouldRefuseAttributeIsWithoutLocale(Evita evita) {
			assertThrows(
				EntityLocaleMissingException.class,
				() -> getRows(
					evita, 1, LIVE, REF_BRANDS, referenceContent(REF_BRANDS, filterBy(attributeIsNull(ATTR_NAME)))
				)
			);
		}

		@Test
		@DisplayName("a non-filterable BigDecimal compares as the index would, beyond the int range too")
		@UseDataSet(DATA_SET)
		void shouldCompareNonFilterableBigDecimalAsTheIndexWould(Evita evita) {
			// 1.005 is 101 at two decimal places rounded half up - the stored 1.01
			final Map<FilterBy, String> admittedBrandByFilter = Map.of(
				filterBy(attributeEquals(ATTR_PRICE, new BigDecimal("1.005"))), "1",
				filterBy(attributeLessThanEquals(ATTR_PRICE, new BigDecimal("1.014"))), "1",
				filterBy(attributeGreaterThan(ATTR_PRICE, new BigDecimal("12345678901.22"))), "2"
			);
			admittedBrandByFilter.forEach(
				(byPrice, brand) -> {
					final ReferenceContent requirement = referenceContent(REF_BRANDS, byPrice);
					assertEquals(List.of(brand), getRows(evita, 1, LIVE, REF_BRANDS, requirement), byPrice::toString);
					assertEquals(
						rows(1, List.of(brand)), queryRows(evita, LIVE, null, REF_BRANDS, requirement, 1),
						byPrice::toString
					);
				}
			);
		}

		@Test
		@DisplayName("an empty array is no value")
		@UseDataSet(DATA_SET)
		void shouldReadEmptyArrayAsNoValue(Evita evita) {
			final ReferenceContent withoutKeywords = referenceContent(
				REF_BRANDS, filterBy(attributeIsNull(ATTR_KEYWORDS))
			);
			assertEquals(List.of("2"), getRows(evita, 1, LIVE, REF_BRANDS, withoutKeywords));
			assertEquals(rows(1, List.of("2")), queryRows(evita, LIVE, null, REF_BRANDS, withoutKeywords, 1));
		}

		@Test
		@DisplayName("the references are ordered in the locale of the filter")
		@UseDataSet(DATA_SET)
		void shouldOrderInTheLocaleOfTheFilter(Evita evita) {
			final List<String> ordered = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity owner = session.queryOneSealedEntity(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(entityPrimaryKeyInSet(1), entityLocaleEquals(Locale.ENGLISH)),
							require(
								entityFetch(
									referenceContent(
										REF_BRANDS,
										filterBy(entityHaving(entityLocaleEquals(Locale.GERMAN))),
										orderBy(entityProperty(attributeNatural(ATTR_TITLE, OrderDirection.ASC))),
										entityFetch(attributeContent(ATTR_TITLE))
									)
								)
							)
						)
					).orElseThrow();
					return describe(owner.getReferences(REF_BRANDS));
				}
			);
			// German titles: Alpha (1), Beta (2); the English ones would order 2 before 1
			assertEquals(List.of("1", "2"), ordered);
		}

	}

	@Nested
	@DisplayName("Refusals while planning, whatever the data")
	class RefusalsAtPlanning {

		@Test
		@DisplayName("a constraint without a meaning on a row is refused, naming itself and the reference")
		@UseDataSet(DATA_SET)
		void shouldRefuseConstraintWithoutMeaningOnRow(Evita evita) {
			assertRefusedAtPlanning(
				evita, REF_BRANDS, filterBy(entityPrimaryKeyBetween(1, 2)), EvitaInvalidUsageException.class,
				"entityPrimaryKeyBetween", REF_BRANDS
			);
		}

		@Test
		@DisplayName("a scope below the root of the filter is refused rather than read as true")
		@UseDataSet(DATA_SET)
		void shouldRefuseScopeBelowRoot(Evita evita) {
			for (FilterBy filterBy : List.of(
				filterBy(or(scope(Scope.LIVE), attributeEquals(ATTR_X, 5))),
				filterBy(not(scope(Scope.LIVE))),
				filterBy(inScope(Scope.LIVE, scope(Scope.LIVE)))
			)) {
				assertRefusedAtPlanning(
					evita, REF_BRANDS, filterBy, EvitaInvalidUsageException.class, "scope", REF_BRANDS
				);
			}
		}

		@Test
		@DisplayName("an inverted interval of a scalar attribute is refused with the message of the index")
		@UseDataSet(DATA_SET)
		void shouldRefuseInvertedInterval(Evita evita) {
			assertRefusedAtPlanning(
				evita, REF_BRANDS, filterBy(attributeBetween(ATTR_X, 10, 5)), EvitaInvalidUsageException.class,
				"From must be lower than to: 10 vs. 5"
			);
		}

		@Test
		@DisplayName("attributeIs of a localized attribute without any locale is refused")
		@UseDataSet(DATA_SET)
		void shouldRefuseAttributeIsWithoutLocale(Evita evita) {
			assertRefusedAtPlanning(
				evita, REF_BRANDS, filterBy(attributeIsNull(ATTR_NAME)), EntityLocaleMissingException.class, ATTR_NAME
			);
		}

		@Test
		@DisplayName("a filter of a reference indexed nowhere is validated")
		@UseDataSet(DATA_SET)
		void shouldValidateFilterOfReferenceIndexedNowhere(Evita evita) {
			assertRefusedAtPlanning(
				evita, REF_UNINDEXED, filterBy(attributeEquals("unknown", 1)), AttributeNotFoundException.class,
				"unknown"
			);
		}

		@Test
		@DisplayName("a nested filter is validated without any row to test it on")
		@UseDataSet(DATA_SET)
		void shouldValidateNestedFilterWithoutAnyRow(Evita evita) {
			for (FilterBy filterBy : List.of(
				filterBy(entityHaving(attributeEquals("unknown", 1))),
				filterBy(not(groupHaving(attributeEquals("unknown", 1))))
			)) {
				assertRefusedAtPlanning(evita, REF_BRANDS, filterBy, AttributeNotFoundException.class, "unknown");
			}
			// on a reference indexed nowhere as well
			assertRefusedAtPlanning(
				evita, REF_UNINDEXED, filterBy(entityHaving(attributeEquals("unknown", 1))),
				AttributeNotFoundException.class, "unknown"
			);
		}

	}

	@Nested
	@DisplayName("Refusals at fetch")
	class RefusalsAtFetch {

		@Test
		@DisplayName("a constraint without a meaning on a row is refused, whether or not the owner holds a row")
		@UseDataSet(DATA_SET)
		void shouldRefuseConstraintWithoutMeaningOnRow(Evita evita) {
			for (int owner : new int[]{1, 5}) {
				assertRefusedAtFetch(
					evita, owner, filterBy(entityPrimaryKeyBetween(1, 2)), "entityPrimaryKeyBetween", REF_BRANDS
				);
			}
		}

		@Test
		@DisplayName("a scope below the root of the filter is refused rather than read as true")
		@UseDataSet(DATA_SET)
		void shouldRefuseScopeBelowRoot(Evita evita) {
			assertRefusedAtFetch(
				evita, 1, filterBy(or(scope(Scope.LIVE), attributeEquals(ATTR_X, 5))), "scope", REF_BRANDS
			);
			assertRefusedAtFetch(evita, 1, filterBy(not(scope(Scope.LIVE))), "scope", REF_BRANDS);
			assertRefusedAtFetch(evita, 1, filterBy(inScope(Scope.LIVE, scope(Scope.LIVE))), "scope", REF_BRANDS);
		}

		@Test
		@DisplayName("an inverted interval of a scalar attribute is refused, with or without a row to test")
		@UseDataSet(DATA_SET)
		void shouldRefuseInvertedInterval(Evita evita) {
			for (int owner : new int[]{1, 5}) {
				assertRefusedAtFetch(
					evita, owner, filterBy(attributeBetween(ATTR_X, 10, 5)), "From must be lower than to"
				);
			}
		}

	}

	@Nested
	@DisplayName("Slicing and groups")
	class SlicingAndGroups {

		@Test
		@DisplayName("each named set pages its own admitted duplicates, sorted, with their bodies")
		@UseDataSet(DATA_SET)
		void shouldPageAdmittedDuplicatesOfEachNamedSet(Evita evita) {
			// 1/DE (rank 1) sorts first; set `cz` rejects it, while its pk is admitted through 1/CZ (rank 3)
			for (RequireConstraint chunk : new RequireConstraint[]{page(1, 1), strip(0, 1)}) {
				final Map<String, List<String>> pages = evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final ServerEntityDecorator owner = (ServerEntityDecorator) session.queryOneSealedEntity(
							query(
								collection(ENTITY_PRODUCT),
								filterBy(entityPrimaryKeyInSet(1)),
								require(
									entityFetch(
										namedDuplicates("cz", "CZ", chunk),
										namedDuplicates("de", "DE", chunk)
									)
								)
							)
						).orElseThrow();
						final Map<String, List<String>> result = new TreeMap<>();
						for (String instance : new String[]{"cz", "de"}) {
							final ReferenceContentKey key = new ReferenceContentKey(instance, REF_DUPLICATES);
							final DataChunk<ReferenceContract> references = owner
								.getReferencesForReferenceContentInstance(key)
								.orElseThrow();
							final List<String> page = new ArrayList<>(2);
							for (ReferenceContract reference : references) {
								page.add(
									reference.getReferencedPrimaryKey() + "/" + reference.getAttribute(ATTR_COUNTRY) +
										(reference.getReferencedEntity().isPresent() ? " with body" : " without body")
								);
							}
							result.put(instance, page);
						}
						return result;
					}
				);
				assertEquals(List.of("2/CZ with body"), pages.get("cz"), chunk.toString());
				assertEquals(List.of("1/DE with body"), pages.get("de"), chunk.toString());
			}
		}

		@Test
		@DisplayName("each page of duplicates without an ordering carries the bodies of its rows")
		@UseDataSet(DATA_SET)
		void shouldPageDuplicatesWithoutOrderingWithTheirBodies(Evita evita) {
			// the rows 1/CZ, 1/DE and 2/CZ (4/SK refers to a missing brand) are paged one per page; the referenced key
			// of a row on one page may differ from the key at the same position among the distinct keys {1, 2}
			final Map<String, ReferenceContent> pagedBy = new TreeMap<>();
			for (int page = 1; page <= 3; page++) {
				// without a filter the verdicts are kept per referenced key, with a filter per row
				pagedBy.put(
					"EXISTING, page " + page,
					new ReferenceContent(
						null, ManagedReferencesBehaviour.EXISTING, new String[]{REF_DUPLICATES},
						new RequireConstraint[]{
							attributeContentAll(), entityFetch(attributeContent(ATTR_CODE)), page(page, 1)
						},
						new Constraint<?>[0]
					)
				);
				pagedBy.put(
					"filtered, page " + page,
					new ReferenceContent(
						null, ManagedReferencesBehaviour.ANY, new String[]{REF_DUPLICATES},
						new RequireConstraint[]{
							attributeContentAll(), entityFetch(attributeContent(ATTR_CODE)), page(page, 1)
						},
						new Constraint<?>[]{filterBy(attributeInSet(ATTR_COUNTRY, "CZ", "DE"))}
					)
				);
			}
			final Map<String, List<String>> pages = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Map<String, List<String>> result = new TreeMap<>();
					pagedBy.forEach(
						(label, requirement) -> {
							final List<String> page = new ArrayList<>(1);
							for (ReferenceContract reference : session.getEntity(ENTITY_PRODUCT, 1, requirement)
								.orElseThrow()
								.getReferenceChunk(REF_DUPLICATES)) {
								page.add(
									reference.getReferencedPrimaryKey() + "/" + reference.getAttribute(ATTR_COUNTRY) +
										(reference.getReferencedEntity().isPresent() ? " with body" : " without body")
								);
							}
							result.put(label, page);
						}
					);
					return result;
				}
			);
			for (String variant : new String[]{"EXISTING", "filtered"}) {
				final List<String> rowsOverPages = new ArrayList<>(3);
				for (int page = 1; page <= 3; page++) {
					final List<String> rowsOfPage = pages.get(variant + ", page " + page);
					assertEquals(1, rowsOfPage.size(), () -> variant + ": " + pages);
					rowsOverPages.addAll(rowsOfPage);
				}
				assertEquals(
					List.of("1/CZ with body", "1/DE with body", "2/CZ with body"),
					rowsOverPages.stream().sorted().toList(),
					() -> variant + ": " + pages
				);
			}
		}

		@Test
		@DisplayName("a strip starting past the last row starts at the first one, with its body")
		@UseDataSet(DATA_SET)
		void shouldFetchBodiesOfStripStartingPastTheEnd(Evita evita) {
			// the decorator starts a strip past the last row at the first one - so must the slice whose bodies are
			// fetched, ordered or not: owner 1 holds the existing brands 1 and 2, and the Czech duplicates 2/CZ
			// (rank 2) and 1/CZ (rank 3)
			final ReferenceContent brands = new ReferenceContent(
				null, ManagedReferencesBehaviour.EXISTING, new String[]{REF_BRANDS},
				new RequireConstraint[]{entityFetch(attributeContent(ATTR_CODE)), strip(5, 1)},
				new Constraint<?>[0]
			);
			final ReferenceContent duplicates = new ReferenceContent(
				null, ManagedReferencesBehaviour.ANY, new String[]{REF_DUPLICATES},
				new RequireConstraint[]{attributeContentAll(), entityFetch(attributeContent(ATTR_CODE)), strip(5, 1)},
				new Constraint<?>[]{
					filterBy(attributeEquals(ATTR_COUNTRY, "CZ")),
					orderBy(attributeNatural(ATTR_RANK, OrderDirection.ASC))
				}
			);
			final Map<String, List<String>> strips = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity owner = session.queryOneSealedEntity(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(entityPrimaryKeyInSet(1)),
							require(entityFetch(brands, duplicates))
						)
					).orElseThrow();
					final Map<String, List<String>> result = new TreeMap<>();
					for (String referenceName : new String[]{REF_BRANDS, REF_DUPLICATES}) {
						final List<String> strip = new ArrayList<>(1);
						for (ReferenceContract reference : owner.getReferenceChunk(referenceName)) {
							strip.add(
								describe(List.of(reference)).get(0) +
									(reference.getReferencedEntity().isPresent() ? " with body" : " without body")
							);
						}
						result.put(referenceName, strip);
					}
					return result;
				}
			);
			assertEquals(List.of("1 with body"), strips.get(REF_BRANDS));
			assertEquals(List.of("2/CZ with body"), strips.get(REF_DUPLICATES));
		}

		/**
		 * Returns the named set of `brandDuplicates` of one country, ordered by rank and chunked.
		 *
		 * @param instance the name of the set
		 * @param country  the country the filter admits
		 * @param chunk    the chunk
		 * @return the requirement
		 */
		@Nonnull
		private static ReferenceContent namedDuplicates(
			@Nonnull String instance,
			@Nonnull String country,
			@Nonnull RequireConstraint chunk
		) {
			return new ReferenceContent(
				instance, ManagedReferencesBehaviour.ANY, new String[]{REF_DUPLICATES},
				new RequireConstraint[]{attributeContentAll(), entityFetch(attributeContent(ATTR_CODE)), chunk},
				new Constraint<?>[]{
					filterBy(attributeEquals(ATTR_COUNTRY, country)),
					orderBy(attributeNatural(ATTR_RANK, OrderDirection.ASC))
				}
			);
		}

		@Test
		@DisplayName("a missing and an archived group have no body under EXISTING, an existing one has")
		@UseDataSet(DATA_SET)
		void shouldFetchNoBodyOfMissingOrArchivedGroup(Evita evita) {
			final List<String> rows = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity owner = session.getEntity(
						ENTITY_PRODUCT, 1, LIVE,
						new ReferenceContent(
							ManagedReferencesBehaviour.EXISTING, new String[]{REF_EXTERNALS}, null,
							entityGroupFetch(attributeContent(ATTR_CODE))
						)
					).orElseThrow();
					final List<String> result = new ArrayList<>(4);
					for (ReferenceContract reference : owner.getReferences(REF_EXTERNALS)) {
						result.add(
							reference.getReferencedPrimaryKey() + ":" +
								reference.getGroupEntity().map(it -> it.getAttribute(ATTR_CODE)).orElse("-")
						);
					}
					return result.stream().sorted().toList();
				}
			);
			// the targets are not managed, so every row stays; group 12 does not exist, group 13 is archived
			assertEquals(List.of("100:g1", "101:-", "102:g2", "103:-"), rows);
		}

		@Test
		@DisplayName("both duplicates of one target in two groups carry the body of their own group")
		@UseDataSet(DATA_SET)
		void shouldFetchBodyOfEveryGroupOfDuplicatesOfOneTarget(Evita evita) {
			final List<String> rows = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// owner 1 holds target 1 twice - 1/DE in group 11 and 1/CZ in group 10; only target 1 is fetched,
					// so no other row of the owner and no other owner can fetch the group the other duplicate misses
					final SealedEntity owner = session.queryListOfSealedEntities(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(entityPrimaryKeyInSet(1), scope(LIVE)),
							require(
								entityFetch(
									new ReferenceContent(
										null, ManagedReferencesBehaviour.ANY, new String[]{REF_DUPLICATES},
										new RequireConstraint[]{
											attributeContentAll(),
											entityFetch(attributeContent(ATTR_CODE)),
											entityGroupFetch(attributeContent(ATTR_CODE))
										},
										new Constraint<?>[]{filterBy(entityPrimaryKeyInSet(1))}
									)
								)
							)
						)
					).get(0);
					final List<String> result = new ArrayList<>(2);
					for (ReferenceContract reference : owner.getReferences(REF_DUPLICATES)) {
						result.add(
							reference.getReferencedPrimaryKey() + "/" + reference.getAttribute(ATTR_COUNTRY) + ":" +
								reference.getGroupEntity()
									.map(it -> it.getPrimaryKeyOrThrowException() + "=" + it.getAttribute(ATTR_CODE))
									.orElse("-")
						);
					}
					return result.stream().sorted().toList();
				}
			);
			assertEquals(List.of("1/CZ:10=g1", "1/DE:11=g2"), rows);
		}

	}

	@Nested
	@DisplayName("Owners outside the scopes of the request")
	class OwnersOutsideRequestScopes {

		@Test
		@DisplayName("an owner archived by archiveEntity is judged by the declaration of its new scope")
		@UseDataSet(value = MUTABLE_DATA_SET, destroyAfterTest = true)
		void shouldJudgeOwnerArchivedByArchiveEntity(Evita evita) {
			// brand 4 does not exist, brand 2 holds x 7 and no tag; `tag` is unique within a locale in ARCHIVED, so
			// the German locale of the filter finds no value of owner 1 there, while LIVE would read its English `t-1`
			final Map<FilterBy, List<String>> expectedRows = CollectionUtils.createLinkedHashMap(3);
			expectedRows.put(filterBy(attributeEquals(ATTR_X, 5)), List.of("1"));
			expectedRows.put(filterBy(attributeIsNotNull(ATTR_TAG)), List.of("1"));
			expectedRows.put(
				filterBy(entityLocaleEquals(Locale.GERMAN), attributeEquals(ATTR_TAG, "t-1")), List.of()
			);
			assertAll(
				expectedRows.entrySet().stream().map(
					entry -> () -> assertEquals(
						entry.getValue(),
						inRolledBackTransaction(
							evita,
							session -> {
								// the request of `archiveEntity` names no scope, so it reads LIVE - the owner it
								// returns lives in ARCHIVED
								final SealedEntity archived = session.archiveEntity(
									ENTITY_PRODUCT, 1, referenceContentWithAttributes(REF_BRANDS, entry.getKey())
								).orElseThrow();
								assertEquals(Scope.ARCHIVED, archived.getScope());
								return describeSorted(archived.getReferences(REF_BRANDS));
							}
						),
						entry.getKey()::toString
					)
				)
			);
		}

		@Test
		@DisplayName("an archived owner enriched by a request in no scope is judged by the declaration of its scope")
		@UseDataSet(DATA_SET)
		void shouldJudgeArchivedOwnerOfEnrichment(Evita evita) {
			// brand 3 is archived, so it does not exist in the scope of the request
			final Map<FilterBy, List<String>> expectedRows = CollectionUtils.createLinkedHashMap(3);
			expectedRows.put(filterBy(attributeEquals(ATTR_X, 5)), List.of("1"));
			expectedRows.put(filterBy(attributeIsNotNull(ATTR_TAG)), List.of("1"));
			expectedRows.put(filterBy(attributeIsNull(ATTR_X)), List.of());
			assertAll(
				expectedRows.entrySet().stream().map(
					entry -> () -> assertEquals(
						entry.getValue(),
						evita.queryCatalog(
							TEST_CATALOG,
							session -> {
								// the enrichment request has no filter, so it reads LIVE - owner 3 lives in ARCHIVED
								final SealedEntity owner = session.getEntity(
									ENTITY_PRODUCT, 3, BOTH, attributeContentAll()
								).orElseThrow();
								return describeSorted(
									session.enrichEntity(
										owner, referenceContentWithAttributes(REF_BRANDS, entry.getKey())
									).getReferences(REF_BRANDS)
								);
							}
						),
						entry.getKey()::toString
					)
				)
			);
		}

		@Test
		@DisplayName("an owner moved to ARCHIVED by upsertAndFetchEntity is judged by the declaration of its new scope")
		@UseDataSet(value = MUTABLE_DATA_SET, destroyAfterTest = true)
		void shouldJudgeOwnerArchivedByUpsertAndFetch(Evita evita) {
			assertAll(
				Stream.of(filterBy(attributeEquals(ATTR_X, 5)), filterBy(attributeIsNull(ATTR_TAG))).map(
					filterBy -> () -> assertEquals(
						List.of("1"),
						inRolledBackTransaction(
							evita,
							session -> {
								// the request of `upsertAndFetchEntity` names no scope, so it reads LIVE
								final SealedEntity archived = session.upsertAndFetchEntity(
									session.getEntity(ENTITY_PRODUCT, 2, attributeContentAll())
										.orElseThrow()
										.openForWrite()
										.setScope(Scope.ARCHIVED),
									referenceContentWithAttributes(REF_BRANDS, filterBy)
								);
								assertEquals(Scope.ARCHIVED, archived.getScope());
								return describeSorted(archived.getReferences(REF_BRANDS));
							}
						),
						filterBy::toString
					)
				)
			);
		}

		/**
		 * Runs the function in a transaction that is rolled back afterwards, so the changes it makes are seen by
		 * nothing but the function itself.
		 *
		 * @param evita    the engine instance
		 * @param function the function, given the session of the transaction
		 * @return the result of the function
		 */
		@Nonnull
		private static List<String> inRolledBackTransaction(
			@Nonnull Evita evita,
			@Nonnull Function<EvitaSessionContract, List<String>> function
		) {
			final List<List<String>> result = new ArrayList<>(1);
			assertThrows(
				RollbackException.class,
				() -> evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						result.add(function.apply(session));
						session.setRollbackOnly();
					}
				)
			);
			return result.get(0);
		}

	}

	@Nested
	@DisplayName("Dropped data")
	class DroppedData {

		@Test
		@DisplayName("a row whose group was dropped carries no group body")
		@UseDataSet(value = MUTABLE_DATA_SET, destroyAfterTest = true)
		void shouldAttachNoBodyToDroppedGroup(Evita evita) {
			// the changes are read inside the open transaction, which is rolled back afterwards
			assertThrows(
				RollbackException.class,
				() -> evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(REF_BRANDS, 1, whichIs -> whichIs.removeGroup().setAttribute(ATTR_X, 5))
							.upsertVia(session);
						final SealedEntity owner = session.queryOneSealedEntity(
							query(
								collection(ENTITY_PRODUCT),
								filterBy(entityPrimaryKeyInSet(1)),
								require(
									entityFetch(
										new ReferenceContent(
											null, ManagedReferencesBehaviour.ANY, new String[]{REF_BRANDS},
											new RequireConstraint[]{entityGroupFetch(attributeContent(ATTR_CODE))},
											new Constraint<?>[0]
										)
									)
								)
							)
						).orElseThrow();
						final Map<Integer, String> groups = new TreeMap<>();
						for (ReferenceContract brand : owner.getReferences(REF_BRANDS)) {
							groups.put(
								brand.getReferencedPrimaryKey(),
								brand.getGroup().map(it -> String.valueOf(it.getPrimaryKey())).orElse("-") + "/" +
									brand.getGroupEntity().map(it -> it.getAttribute(ATTR_CODE)).orElse("-")
							);
						}
						// row 1 held group 10 before its group was dropped, rows 2 and 4 hold groups 11 and 10
						assertEquals(Map.of(1, "-/-", 2, "11/g2", 4, "10/g1"), groups);
						session.setRollbackOnly();
					}
				)
			);
		}

		@Test
		@DisplayName("a dropped row, a dropped group and a dropped attribute value count as absent")
		@UseDataSet(value = MUTABLE_DATA_SET, destroyAfterTest = true)
		void shouldReadDroppedDataAsAbsent(Evita evita) {
			// the changes are read inside the open transaction, which is rolled back afterwards
			assertThrows(
				RollbackException.class,
				() -> evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeReference(REF_BRANDS, 2)
							.setReference(REF_BRANDS, 1, whichIs -> whichIs.removeGroup().removeAttribute(ATTR_X))
							.upsertVia(session);
						// owner 2 holds brand 1 in group 10 now, so the nested query of `groupHaving` - restricted to
						// the groups of the tested rows - selects group 10 and only the row itself can tell that its
						// group was dropped
						session.getEntity(ENTITY_PRODUCT, 2, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.updateReference(REF_BRANDS, 1, whichIs -> whichIs.setGroup(10))
							.upsertVia(session);
						// row 2 held x 7
						assertEquals(
							rows(1, List.of()),
							queryRows(
								session, LIVE, null, REF_BRANDS,
								referenceContent(REF_BRANDS, filterBy(attributeEquals(ATTR_X, 7))), 1
							)
						);
						// row 1 of owner 1 held group 10, row 4 is a missing target
						assertEquals(
							rows(1, List.of(), 2, List.of("1")),
							queryRows(
								session, LIVE, null, REF_BRANDS,
								referenceContent(REF_BRANDS, filterBy(groupHaving(entityPrimaryKeyInSet(10)))), 1, 2
							)
						);
						assertEquals(
							rows(1, List.of("1")),
							queryRows(
								session, LIVE, null, REF_BRANDS,
								referenceContent(REF_BRANDS, filterBy(attributeIsNull(ATTR_X))), 1
							)
						);
						session.setRollbackOnly();
					}
				)
			);
		}

	}

}
