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

package io.evitadb.core.query.fetch;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.fetch.ReferenceRowFilterCompiler.CompileContext;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ReferenceRowFilterCompiler} and the {@link ReferenceRowFilter} it builds: the meaning of every node on a
 * single reference row, the locale the attribute leaves read, the readings chosen per scope of the owner, the nested
 * `entityHaving` / `groupHaving` sets and when they are resolved, and every refusal.
 *
 * Everything runs against a real catalog: the schemas, the planning context and the visitor the compiler is given are
 * those the engine creates, the owners are the raw entities the engine stores, and the nested sets are planned as real
 * nested queries. The session handed to the planning context is the only mock (see
 * `NestedConstraintCheckFunctionalTest` for why).
 *
 * ## The fixture
 *
 * `rowProduct` (locales EN, DE) owns the reference `brands` to `rowBrand`, grouped by `rowGroup`. Its attributes:
 * `label` (localized), `tag` (localized, unique across locales in LIVE and within a locale in ARCHIVED), `code`
 * (unique in LIVE only), `alias` (localized, unique across locales in LIVE only), `price` (`BigDecimal` at two
 * decimal places), `weight`, `keywords` (array), `validity` (`DateTimeRange`) and `name` (localized, unique nowhere).
 * Apart from the unique ones, none is filterable - the row filter needs no index.
 *
 * | owner | scope | locales | row | group | weight | further values |
 * |---|---|---|---|---|---|---|
 * | 1 | LIVE | EN, DE | brand 1 | 10 | 5 | label `Red` (EN) `Rot` (DE), tag `t-1`, alias `a-1` (EN), code `c1` |
 * | 1 | LIVE | EN, DE | brand 2 | 11 | 10 | label `Café` (EN), price 12345678901.23 |
 * | 2 | ARCHIVED | EN | brand 1 | 10 | 5 | label `Red` (EN), tag `t-2`, alias `a-2` (EN), code `c1` |
 * | 2 | ARCHIVED | EN | brand 3 | - | 7 | |
 *
 * The row of owner 1 to brand 1 also holds the price 1.01, the keywords `x` and `y` and the validity of the year 2026.
 *
 * Brands 1 (`a`, EN + DE) and 2 (`b`, EN) are live, brand 3 (`a`, EN) is archived; group 10 is `g1`, group 11 `g2`.
 * `externals` references the unmanaged type `rowExternal`, `ungrouped` references the brands without a group type,
 * `foreignGroup` references them grouped by the unmanaged type `rowExternalGroup`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Row filter of the fetched references")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(FILTER)
@Tag(REFERENCE)
class ReferenceRowFilterCompilerTest {
	private static final String ROW_FILTER = "referenceRowFilter";
	private static final String ENTITY_PRODUCT = "rowProduct";
	private static final String ENTITY_BRAND = "rowBrand";
	private static final String ENTITY_GROUP = "rowGroup";
	private static final String REF_BRANDS = "brands";
	private static final String REF_EXTERNALS = "externals";
	private static final String REF_UNGROUPED = "ungrouped";
	private static final String REF_FOREIGN_GROUP = "foreignGroup";
	private static final String ATTR_PRODUCT_NAME = "productName";
	private static final String ATTR_CODE = "code";
	private static final String ATTR_TITLE = "title";
	private static final String ATTR_LABEL = "label";
	private static final String ATTR_TAG = "tag";
	private static final String ATTR_ALIAS = "alias";
	private static final String ATTR_PRICE = "price";
	private static final String ATTR_WEIGHT = "weight";
	private static final String ATTR_KEYWORDS = "keywords";
	private static final String ATTR_VALIDITY = "validity";
	private static final String ATTR_NAME = "name";
	private static final Set<Scope> BOTH_SCOPES = EnumSet.of(Scope.LIVE, Scope.ARCHIVED);
	private static final BigDecimal HUGE_PRICE = new BigDecimal("12345678901.23");

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = ROW_FILTER, destroyAfterClass = true)
	void setUpRowFilterDataSet(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_GROUP)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(Scope.values()))
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_GROUP, 10).setAttribute(ATTR_CODE, "g1"));
				session.upsertEntity(session.createNewEntity(ENTITY_GROUP, 11).setAttribute(ATTR_CODE, "g2"));
				session.defineEntitySchema(ENTITY_BRAND)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(Scope.values()))
					.withAttribute(ATTR_TITLE, String.class, thatIs -> thatIs.localized().nullable())
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(ENTITY_BRAND, 1).setAttribute(ATTR_CODE, "a")
						.setAttribute(ATTR_TITLE, Locale.ENGLISH, "One").setAttribute(ATTR_TITLE, Locale.GERMAN, "Eins")
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_BRAND, 2).setAttribute(ATTR_CODE, "b")
						.setAttribute(ATTR_TITLE, Locale.ENGLISH, "Two")
				);
				session.upsertEntity(
					session.createNewEntity(ENTITY_BRAND, 3).setAttribute(ATTR_CODE, "a")
						.setAttribute(ATTR_TITLE, Locale.ENGLISH, "Three")
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
							.indexedForFilteringAndPartitioningInScope(Scope.values())
							.withAttribute(ATTR_LABEL, String.class, thatIs -> thatIs.localized().nullable())
							.withAttribute(
								ATTR_TAG, String.class,
								thatIs -> thatIs.uniqueInScope(Scope.values()).localized().nullable()
							)
							.withAttribute(
								ATTR_CODE, String.class, thatIs -> thatIs.uniqueInScope(Scope.LIVE).nullable()
							)
							.withAttribute(
								ATTR_ALIAS, String.class,
								thatIs -> thatIs.uniqueInScope(Scope.LIVE).localized().nullable()
							)
							.withAttribute(
								ATTR_PRICE, BigDecimal.class, thatIs -> thatIs.indexDecimalPlaces(2).nullable()
							)
							.withAttribute(ATTR_WEIGHT, Integer.class, AttributeSchemaEditor::nullable)
							.withAttribute(ATTR_KEYWORDS, String[].class, AttributeSchemaEditor::nullable)
							.withAttribute(ATTR_VALIDITY, DateTimeRange.class, AttributeSchemaEditor::nullable)
							.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized().nullable())
					)
					.withReferenceTo(
						REF_EXTERNALS, "rowExternal", Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withAttribute(ATTR_WEIGHT, Integer.class, AttributeSchemaEditor::nullable)
					)
					.withReferenceToEntity(REF_UNGROUPED, ENTITY_BRAND, Cardinality.ZERO_OR_MORE)
					.withReferenceToEntity(
						REF_FOREIGN_GROUP, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withGroupType("rowExternalGroup")
					)
					.updateVia(session);
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
						)
					)
				);
				// owner 2 first: `code` is unique in LIVE, so its `c1` must leave LIVE before owner 1 takes it
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setAttribute(ATTR_PRODUCT_NAME, Locale.ENGLISH, "Second")
						.setReference(
							REF_BRANDS, 1,
							whichIs -> whichIs.setGroup(10)
								.setAttribute(ATTR_LABEL, Locale.ENGLISH, "Red")
								.setAttribute(ATTR_TAG, Locale.ENGLISH, "t-2")
								.setAttribute(ATTR_ALIAS, Locale.ENGLISH, "a-2")
								.setAttribute(ATTR_CODE, "c1")
								.setAttribute(ATTR_WEIGHT, 5)
						)
						.setReference(REF_BRANDS, 3, whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 7))
				);
				session.archiveEntity(ENTITY_PRODUCT, 2);
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_PRODUCT_NAME, Locale.ENGLISH, "First")
						.setAttribute(ATTR_PRODUCT_NAME, Locale.GERMAN, "Erste")
						.setReference(
							REF_BRANDS, 1,
							whichIs -> whichIs.setGroup(10)
								.setAttribute(ATTR_LABEL, Locale.ENGLISH, "Red")
								.setAttribute(ATTR_LABEL, Locale.GERMAN, "Rot")
								.setAttribute(ATTR_TAG, Locale.ENGLISH, "t-1")
								.setAttribute(ATTR_ALIAS, Locale.ENGLISH, "a-1")
								.setAttribute(ATTR_CODE, "c1")
								.setAttribute(ATTR_PRICE, new BigDecimal("1.01"))
								.setAttribute(ATTR_WEIGHT, 5)
								.setAttribute(ATTR_KEYWORDS, new String[]{"x", "y"})
								.setAttribute(
									ATTR_VALIDITY,
									DateTimeRange.between(
										OffsetDateTime.parse("2026-01-01T00:00:00Z"),
										OffsetDateTime.parse("2026-12-31T23:59:59Z")
									)
								)
								.setAttribute(ATTR_NAME, Locale.ENGLISH, "n")
						)
						.setReference(
							REF_BRANDS, 2,
							whichIs -> whichIs.setGroup(11)
								.setAttribute(ATTR_LABEL, Locale.ENGLISH, "Café")
								.setAttribute(ATTR_WEIGHT, 10)
								.setAttribute(ATTR_PRICE, HUGE_PRICE)
						)
						.setReference(REF_EXTERNALS, 100, whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 1))
				);
			}
		);
	}

	/**
	 * Returns the raw owner entities - not the decorators the session hands out, which hide what was not requested -
	 * by primary key.
	 *
	 * @param evita the engine instance
	 * @return the owners
	 */
	@Nonnull
	private static Map<Integer, Entity> fetchOwners(@Nonnull Evita evita) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Map<Integer, Entity> owners = new TreeMap<>();
				for (SealedEntity owner : session.queryListOfSealedEntities(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
						require(entityFetch(entityFetchAllContent()), dataInLocalesAll())
					)
				)) {
					owners.put(owner.getPrimaryKeyOrThrowException(), ((EntityDecorator) owner).getDelegate());
				}
				assertEquals(2, owners.size(), "Fixture guard: two owners expected!");
				return owners;
			}
		);
	}

	/**
	 * Creates the planning context of a query over the owners in both scopes, against the real product collection.
	 *
	 * @param evita the engine instance
	 * @return the planning context
	 */
	@Nonnull
	private static QueryPlanningContext createQueryContext(@Nonnull Evita evita) {
		return ((Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG))
			.getCollectionForEntityInternal(ENTITY_PRODUCT)
			.orElseThrow()
			.createQueryContext(
				new EvitaRequest(
					query(collection(ENTITY_PRODUCT), filterBy(scope(Scope.LIVE, Scope.ARCHIVED))).normalizeQuery(),
					OffsetDateTime.now(),
					EntityReference.class,
					null
				),
				Mockito.mock(EvitaSession.class)
			);
	}

	/**
	 * The compiler's inputs over the real catalog.
	 *
	 * @param evita the engine instance
	 */
	private record Fixture(@Nonnull Evita evita) {

		/**
		 * Compiles the filter of the `brands` reference for a fetch, with the owner request in the locale.
		 *
		 * @param filterBy    the filter
		 * @param ownerLocale the locale of the owner request
		 * @return the compiled filter
		 */
		@Nonnull
		ReferenceRowFilter compile(@Nullable FilterBy filterBy, @Nullable Locale ownerLocale) {
			return compile(REF_BRANDS, filterBy, ownerLocale, BOTH_SCOPES, false, null);
		}

		/**
		 * Compiles the filter of a reference.
		 *
		 * @param referenceName   the name of the reference
		 * @param filterBy        the filter
		 * @param ownerLocale     the locale of the owner request
		 * @param requestScopes   the scopes of the owner request
		 * @param check           true for a visitor that only checks the filter
		 * @param existingTargets the existing targets restricting the nested queries, NULL for none
		 * @return the compiled filter
		 */
		@Nonnull
		ReferenceRowFilter compile(
			@Nonnull String referenceName,
			@Nullable FilterBy filterBy,
			@Nullable Locale ownerLocale,
			@Nonnull Set<Scope> requestScopes,
			boolean check,
			@Nullable Supplier<Bitmap> existingTargets
		) {
			return compile(
				createQueryContext(this.evita), referenceName, filterBy, ownerLocale, requestScopes, check,
				existingTargets
			);
		}

		/**
		 * Compiles the filter of a reference in the passed planning context.
		 *
		 * @param queryContext    the planning context
		 * @param referenceName   the name of the reference
		 * @param filterBy        the filter
		 * @param ownerLocale     the locale of the owner request
		 * @param requestScopes   the scopes of the owner request
		 * @param check           true for a visitor that only checks the filter
		 * @param existingTargets the existing targets restricting the nested queries, NULL for none
		 * @return the compiled filter
		 */
		@Nonnull
		static ReferenceRowFilter compile(
			@Nonnull QueryPlanningContext queryContext,
			@Nonnull String referenceName,
			@Nullable FilterBy filterBy,
			@Nullable Locale ownerLocale,
			@Nonnull Set<Scope> requestScopes,
			boolean check,
			@Nullable Supplier<Bitmap> existingTargets
		) {
			final FilterByVisitor visitor = check ?
				FilterByVisitor.createConstraintCheckVisitor(queryContext) :
				new FilterByVisitor(queryContext, Collections.emptyList(), TargetIndexes.EMPTY);
			final EntitySchemaContract entitySchema = queryContext.getSchema();
			final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
			return ReferenceRowFilterCompiler.compile(
				filterBy,
				new CompileContext(
					entitySchema, referenceSchema, visitor, requestScopes, ownerLocale, existingTargets,
					existingTargets == null ? null : () -> new BaseBitmap(10, 11)
				)
			);
		}

	}

	/**
	 * Returns the verdicts of the filter on every `brands` row of every owner, as `owner/brand` keys in fetch order.
	 *
	 * @param filter the compiled filter
	 * @param owners the owners
	 * @return the verdicts
	 */
	@Nonnull
	private static Map<String, Boolean> verdicts(
		@Nonnull ReferenceRowFilter filter,
		@Nonnull Map<Integer, Entity> owners
	) {
		final Map<String, Boolean> result = new LinkedHashMap<>(8);
		for (Entity owner : owners.values()) {
			for (ReferenceContract row : owner.getReferences(REF_BRANDS)) {
				result.put(
					owner.getPrimaryKeyOrThrowException() + "/" + row.getReferencedPrimaryKey(),
					filter.test(owner.getScope(), owner, row)
				);
			}
		}
		return result;
	}

	/**
	 * Returns the expected verdicts on the four `brands` rows of the fixture.
	 *
	 * @param p1b1 owner 1, brand 1
	 * @param p1b2 owner 1, brand 2
	 * @param p2b1 owner 2, brand 1
	 * @param p2b3 owner 2, brand 3
	 * @return the verdicts
	 */
	@Nonnull
	private static Map<String, Boolean> expected(boolean p1b1, boolean p1b2, boolean p2b1, boolean p2b3) {
		final Map<String, Boolean> result = new LinkedHashMap<>(8);
		result.put("1/1", p1b1);
		result.put("1/2", p1b2);
		result.put("2/1", p2b1);
		result.put("2/3", p2b3);
		return result;
	}

	/**
	 * Asserts the verdicts of the filter on the four `brands` rows.
	 *
	 * @param evita       the engine instance
	 * @param filterBy    the filter
	 * @param ownerLocale the locale of the owner request
	 * @param expected    the expected verdicts
	 */
	private static void assertVerdicts(
		@Nonnull Evita evita,
		@Nullable FilterBy filterBy,
		@Nullable Locale ownerLocale,
		@Nonnull Map<String, Boolean> expected
	) {
		assertEquals(
			expected,
			verdicts(new Fixture(evita).compile(filterBy, ownerLocale), fetchOwners(evita)),
			() -> "Verdicts of " + filterBy + " with the owner locale " + ownerLocale
		);
	}

	/**
	 * Returns a hand-built `brands` row of brand 1 - for states the engine never hands a fetch outside a transaction.
	 *
	 * @param evita      the engine instance
	 * @param group      the group of the row, NULL for none
	 * @param attributes the attribute values of the row
	 * @return the row
	 */
	@Nonnull
	private static ReferenceContract handBuiltRow(
		@Nonnull Evita evita,
		@Nullable GroupEntityReference group,
		@Nonnull AttributeValue... attributes
	) {
		final EntitySchemaContract entitySchema = createQueryContext(evita).getSchema();
		final Map<AttributeKey, AttributeValue> values = new LinkedHashMap<>(4);
		for (AttributeValue attribute : attributes) {
			values.put(attribute.key(), attribute);
		}
		return new Reference(
			entitySchema, entitySchema.getReferenceOrThrowException(REF_BRANDS), 1,
			new ReferenceKey(REF_BRANDS, 1), group, values, false
		);
	}

	@Nested
	@DisplayName("Meaning of the nodes on a row")
	class RowSemantics {

		@Test
		@DisplayName("a filter without a condition on the row - none, or only a root scope - accepts every row")
		@UseDataSet(ROW_FILTER)
		void shouldAcceptEveryRowWithoutCondition(Evita evita) {
			assertVerdicts(evita, null, null, expected(true, true, true, true));
			assertVerdicts(evita, filterBy(scope(Scope.LIVE, Scope.ARCHIVED)), null, expected(true, true, true, true));
			// non-applicable containers are dropped as the query model drops them, and a container left empty with them
			assertVerdicts(evita, new FilterBy(new Not(new And())), null, expected(true, true, true, true));
			assertVerdicts(
				evita, new FilterBy(new Or(new And(), new And()), attributeEquals(ATTR_WEIGHT, 5)), null,
				expected(true, false, true, false)
			);
		}

		@Test
		@DisplayName("an attribute leaf reads the attribute of the row itself")
		@UseDataSet(ROW_FILTER)
		void shouldReadAttributeOfTheRow(Evita evita) {
			assertVerdicts(evita, filterBy(attributeEquals(ATTR_WEIGHT, 5)), null, expected(true, false, true, false));
			assertVerdicts(
				evita, filterBy(attributeLessThan(ATTR_WEIGHT, 7)), null, expected(true, false, true, false)
			);
			assertVerdicts(
				evita, filterBy(attributeLessThanEquals(ATTR_WEIGHT, 7)), null, expected(true, false, true, true)
			);
			assertVerdicts(
				evita, filterBy(attributeGreaterThan(ATTR_WEIGHT, 7)), null, expected(false, true, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeGreaterThanEquals(ATTR_WEIGHT, 7)), null, expected(false, true, false, true)
			);
			assertVerdicts(
				evita, filterBy(attributeBetween(ATTR_WEIGHT, 6, 10)), null, expected(false, true, false, true)
			);
			assertVerdicts(
				evita, filterBy(attributeInSet(ATTR_WEIGHT, 7, 10)), null, expected(false, true, false, true)
			);
			assertVerdicts(evita, filterBy(attributeIsNull(ATTR_PRICE)), null, expected(false, false, true, true));
			assertVerdicts(evita, filterBy(attributeIsNotNull(ATTR_PRICE)), null, expected(true, true, false, false));
		}

		@Test
		@DisplayName("and, or and not combine the verdicts of the same row")
		@UseDataSet(ROW_FILTER)
		void shouldCombineVerdictsOfTheSameRow(Evita evita) {
			assertVerdicts(
				evita,
				filterBy(or(attributeEquals(ATTR_WEIGHT, 10), not(attributeLessThan(ATTR_WEIGHT, 6)))),
				null,
				expected(false, true, false, true)
			);
			assertVerdicts(
				evita,
				filterBy(attributeEquals(ATTR_WEIGHT, 5), not(attributeIsNull(ATTR_PRICE))),
				null,
				expected(true, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(not(attributeEquals(ATTR_WEIGHT, 5))), null, expected(false, true, false, true)
			);
		}

		@Test
		@DisplayName("entityPrimaryKeyInSet tests the referenced key, in any order of the keys")
		@UseDataSet(ROW_FILTER)
		void shouldTestReferencedKey(Evita evita) {
			assertVerdicts(evita, filterBy(entityPrimaryKeyInSet(3, 1)), null, expected(true, false, true, true));
			assertVerdicts(evita, filterBy(not(entityPrimaryKeyInSet(1))), null, expected(false, true, false, true));
		}

		@Test
		@DisplayName("entityLocaleEquals tests the locales of the owner, the same for all its rows")
		@UseDataSet(ROW_FILTER)
		void shouldTestLocaleOfTheOwner(Evita evita) {
			assertVerdicts(
				evita, filterBy(entityLocaleEquals(Locale.GERMAN)), null, expected(true, true, false, false)
			);
			assertVerdicts(
				evita, filterBy(entityLocaleEquals(Locale.ENGLISH)), null, expected(true, true, true, true)
			);
		}

		@Test
		@DisplayName("inScope applies its children to the owners of its scope and lets the rows of others pass")
		@UseDataSet(ROW_FILTER)
		void shouldApplyInScopeToOwnersOfItsScopeOnly(Evita evita) {
			assertVerdicts(
				evita, filterBy(inScope(Scope.ARCHIVED, attributeEquals(ATTR_WEIGHT, 7))), null,
				expected(true, true, false, true)
			);
			assertVerdicts(
				evita, filterBy(inScope(Scope.LIVE, attributeEquals(ATTR_WEIGHT, 10))), null,
				expected(false, true, true, true)
			);
		}

		@Test
		@DisplayName("an array matches by any element, ranges and moments as the index reads them")
		@UseDataSet(ROW_FILTER)
		void shouldReadArraysRangesAndMoments(Evita evita) {
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_KEYWORDS, "y")), null, expected(true, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeInRange(ATTR_VALIDITY, OffsetDateTime.parse("2026-06-01T00:00:00+02:00"))),
				null, expected(true, false, false, false)
			);
			assertVerdicts(
				evita,
				filterBy(attributeBetween(ATTR_VALIDITY, OffsetDateTime.parse("2027-01-01T00:00:00Z"), null)),
				null, expected(false, false, false, false)
			);
			assertVerdicts(
				evita,
				filterBy(attributeBetween(ATTR_VALIDITY, null, OffsetDateTime.parse("2026-01-01T00:00:00Z"))),
				null, expected(true, false, false, false)
			);
		}

		@Test
		@DisplayName("a BigDecimal is scaled half up, also beyond the int range the index encodes into")
		@UseDataSet(ROW_FILTER)
		void shouldReadBigDecimalScaledHalfUp(Evita evita) {
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_PRICE, new BigDecimal("1.005"))), null,
				expected(true, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_PRICE, new BigDecimal("1.0149"))), null,
				expected(true, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeGreaterThan(ATTR_PRICE, new BigDecimal("99999999999"))), null,
				expected(false, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_PRICE, HUGE_PRICE)), null, expected(false, true, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeGreaterThan(ATTR_PRICE, 2)), null, expected(false, true, false, false)
			);
		}

		@Test
		@DisplayName("strings are compared in Unicode NFD on both sides")
		@UseDataSet(ROW_FILTER)
		void shouldCompareStringsInNfd(Evita evita) {
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_LABEL, "Café")), Locale.ENGLISH,
				expected(false, true, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeStartsWith(ATTR_LABEL, "Café")), Locale.ENGLISH,
				expected(false, true, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeEndsWith(ATTR_LABEL, "é")), Locale.ENGLISH,
				expected(false, true, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeContains(ATTR_LABEL, "e")), Locale.ENGLISH, expected(true, true, true, false)
			);
		}

		@Test
		@DisplayName("a dropped attribute value, an empty array and a dropped group count as nothing")
		@UseDataSet(ROW_FILTER)
		void shouldReadDroppedDataAndEmptyArrayAsAbsent(Evita evita) {
			final Entity owner = fetchOwners(evita).get(1);
			final AttributeKey keywords = new AttributeKey(ATTR_KEYWORDS);
			final AttributeKey weight = new AttributeKey(ATTR_WEIGHT);
			final ReferenceContract emptyArray = handBuiltRow(evita, null, new AttributeValue(keywords, new String[0]));
			final ReferenceContract droppedValue = handBuiltRow(
				evita, null, new AttributeValue(1, weight, 5, true)
			);
			final ReferenceContract droppedGroup = handBuiltRow(
				evita, new GroupEntityReference(ENTITY_GROUP, 10, 1, true), new AttributeValue(weight, 5)
			);
			final Fixture fixture = new Fixture(evita);

			final ReferenceRowFilter keywordsNull = fixture.compile(filterBy(attributeIsNull(ATTR_KEYWORDS)), null);
			assertTrue(keywordsNull.test(Scope.LIVE, owner, emptyArray));
			final ReferenceRowFilter keywordsAny = fixture.compile(
				filterBy(attributeLessThan(ATTR_KEYWORDS, "z")), null
			);
			assertFalse(keywordsAny.test(Scope.LIVE, owner, emptyArray));

			assertFalse(
				fixture.compile(filterBy(attributeEquals(ATTR_WEIGHT, 5)), null).test(Scope.LIVE, owner, droppedValue)
			);
			assertTrue(
				fixture.compile(filterBy(attributeIsNull(ATTR_WEIGHT)), null).test(Scope.LIVE, owner, droppedValue)
			);

			final ReferenceRowFilter inGroup = fixture.compile(
				filterBy(groupHaving(attributeEquals(ATTR_CODE, "g1"))), null
			);
			assertFalse(inGroup.test(Scope.LIVE, owner, droppedGroup));
			assertTrue(
				inGroup.test(Scope.LIVE, owner, handBuiltRow(evita, new GroupEntityReference(ENTITY_GROUP, 10)))
			);
		}

	}

	@Nested
	@DisplayName("Locale of the attribute leaves")
	class LeafLocale {

		@Test
		@DisplayName("the locale of the filter wins over the locale of the owner request")
		@UseDataSet(ROW_FILTER)
		void shouldReadLocaleOfTheFilterBeforeLocaleOfTheOwner(Evita evita) {
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_LABEL, "Red")), Locale.ENGLISH, expected(true, false, true, false)
			);
			assertVerdicts(
				evita, filterBy(entityLocaleEquals(Locale.GERMAN), attributeEquals(ATTR_LABEL, "Rot")), Locale.ENGLISH,
				expected(true, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(entityLocaleEquals(Locale.GERMAN), attributeEquals(ATTR_LABEL, "Red")), Locale.ENGLISH,
				expected(false, false, false, false)
			);
			// no locale in the owner request at all - the filter's own is enough
			assertVerdicts(
				evita, filterBy(entityLocaleEquals(Locale.GERMAN), attributeEquals(ATTR_LABEL, "Rot")), null,
				expected(true, false, false, false)
			);
		}

		@Test
		@DisplayName("a locale found inside entityHaving is the locale of the leaves, as the body fetch reads it")
		@UseDataSet(ROW_FILTER)
		void shouldReadLocaleFoundInsideEntityHaving(Evita evita) {
			// brand 1 alone holds German; owner 1 calls it `Rot` in German and `Red` in English
			assertVerdicts(
				evita,
				filterBy(entityHaving(entityLocaleEquals(Locale.GERMAN)), attributeEquals(ATTR_LABEL, "Rot")),
				Locale.ENGLISH,
				expected(true, false, false, false)
			);
			assertVerdicts(
				evita,
				filterBy(entityHaving(entityLocaleEquals(Locale.GERMAN)), attributeEquals(ATTR_LABEL, "Red")),
				Locale.ENGLISH,
				expected(false, false, false, false)
			);
		}

		@Test
		@DisplayName("a localized leaf without any locale is refused, attributeIs included")
		@UseDataSet(ROW_FILTER)
		void shouldRefuseLocalizedLeafWithoutLocale(Evita evita) {
			final Fixture fixture = new Fixture(evita);
			assertThrows(
				EntityLocaleMissingException.class,
				() -> fixture.compile(filterBy(attributeEquals(ATTR_LABEL, "Red")), null)
			);
			assertThrows(
				EntityLocaleMissingException.class,
				() -> fixture.compile(filterBy(attributeIsNull(ATTR_NAME)), null)
			);
			assertThrows(
				EntityLocaleMissingException.class,
				() -> fixture.compile(filterBy(attributeStartsWith(ATTR_LABEL, "R")), null)
			);
		}

		@Test
		@DisplayName("each scope reads by its own declaration - any locale where unique across locales")
		@UseDataSet(ROW_FILTER)
		void shouldReadEachScopeByItsOwnDeclaration(Evita evita) {
			// LIVE: unique across locales - the English `t-1` counts under a German leaf
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_TAG, "t-1")), Locale.GERMAN, expected(true, false, false, false)
			);
			// ARCHIVED: unique within a locale - the English `t-2` does not count under a German leaf
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_TAG, "t-2")), Locale.GERMAN, expected(false, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_TAG, "t-2")), Locale.ENGLISH, expected(false, false, true, false)
			);
			assertVerdicts(
				evita, filterBy(attributeInSet(ATTR_TAG, "t-1", "t-2")), Locale.GERMAN,
				expected(true, false, false, false)
			);
			// comparisons read the leaf locale in every scope, as the filter index of the locale does
			assertVerdicts(
				evita, filterBy(attributeStartsWith(ATTR_TAG, "t-")), Locale.GERMAN,
				expected(false, false, false, false)
			);
		}

		@Test
		@DisplayName("without any locale an attribute unique across locales somewhere is admitted and read per scope")
		@UseDataSet(ROW_FILTER)
		void shouldReadAttributeUniqueAcrossLocalesWithoutLocale(Evita evita) {
			assertVerdicts(evita, filterBy(attributeIsNotNull(ATTR_TAG)), null, expected(true, false, true, false));
			assertVerdicts(evita, filterBy(attributeIsNull(ATTR_TAG)), null, expected(false, true, false, true));
			// LIVE reads any locale, ARCHIVED is bound to a locale that is not known - nothing counts there
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_TAG, "t-1")), null, expected(true, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_TAG, "t-2")), null, expected(false, false, false, false)
			);
			// within `inScope(ARCHIVED)` the attribute is unique within a locale only, so no locale is refused
			assertThrows(
				EntityLocaleMissingException.class,
				() -> new Fixture(evita).compile(filterBy(inScope(Scope.ARCHIVED, attributeIsNotNull(ATTR_TAG))), null)
			);
		}

		@Test
		@DisplayName("an attribute unique in one scope only is read in the other scope by that scope's declaration")
		@UseDataSet(ROW_FILTER)
		void shouldReadAttributeUniqueInOneScopeInEveryScope(Evita evita) {
			assertVerdicts(evita, filterBy(attributeEquals(ATTR_CODE, "c1")), null, expected(true, false, true, false));
			assertVerdicts(
				evita, filterBy(attributeInSet(ATTR_CODE, "c1", "zz")), null, expected(true, false, true, false)
			);
			// `alias` is unique across locales in LIVE and not unique in ARCHIVED: LIVE reads any locale, ARCHIVED the
			// leaf locale alone - its uniqueness in LIVE does not make ARCHIVED read any locale as well
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_ALIAS, "a-1")), Locale.GERMAN, expected(true, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeInSet(ATTR_ALIAS, "a-1", "a-2")), Locale.GERMAN,
				expected(true, false, false, false)
			);
			assertVerdicts(
				evita, filterBy(attributeEquals(ATTR_ALIAS, "a-2")), Locale.ENGLISH, expected(false, false, true, false)
			);
		}

	}

	@Nested
	@DisplayName("Nested entityHaving and groupHaving")
	class NestedFilters {

		@Test
		@DisplayName("entityHaving tests the target against the nested query in the scopes it is compiled for")
		@UseDataSet(ROW_FILTER)
		void shouldTestTargetAgainstNestedQuery(Evita evita) {
			assertVerdicts(
				evita, filterBy(entityHaving(attributeEquals(ATTR_CODE, "a"))), null, expected(true, false, true, true)
			);
			// within `inScope(LIVE)` the nested query looks into LIVE only, and the archived owner passes
			assertVerdicts(
				evita, filterBy(inScope(Scope.LIVE, entityHaving(attributeEquals(ATTR_CODE, "a")))), null,
				expected(true, false, true, true)
			);
			assertVerdicts(
				evita, filterBy(not(entityHaving(attributeEquals(ATTR_CODE, "a")))), null,
				expected(false, true, false, false)
			);
		}

		@Test
		@DisplayName("groupHaving tests the group against the nested query, a row without a group fails it")
		@UseDataSet(ROW_FILTER)
		void shouldTestGroupAgainstNestedQuery(Evita evita) {
			assertVerdicts(
				evita, filterBy(groupHaving(attributeEquals(ATTR_CODE, "g1"))), null, expected(true, false, true, false)
			);
			assertVerdicts(
				evita, filterBy(groupHaving(attributeEquals(ATTR_CODE, "g1")), attributeEquals(ATTR_WEIGHT, 5)), null,
				expected(true, false, true, false)
			);
		}

		@Test
		@DisplayName("the nested query is restricted to the supplied existing targets")
		@UseDataSet(ROW_FILTER)
		void shouldRestrictNestedQueryToSuppliedTargets(Evita evita) {
			final ReferenceRowFilter filter = new Fixture(evita).compile(
				REF_BRANDS, filterBy(entityHaving(attributeEquals(ATTR_CODE, "a"))), null, BOTH_SCOPES, false,
				() -> new BaseBitmap(2, 3)
			);
			// brand 1 matches the nested filter, but the restriction leaves it out of the nested query
			assertEquals(expected(false, false, false, true), verdicts(filter, fetchOwners(evita)));
		}

		@Test
		@DisplayName("a fetch resolves a nested set when the first row reaches it, a check when it is compiled")
		@UseDataSet(ROW_FILTER)
		void shouldResolveNestedSetsLazilyInFetchAndEagerlyInCheck(Evita evita) {
			final Fixture fixture = new Fixture(evita);
			for (FilterConstraint invalidNested : List.of(
				entityHaving(attributeEquals("unknown", 1)),
				groupHaving(attributeEquals("unknown", 1))
			)) {
				final FilterBy filterBy = filterBy(invalidNested);
				assertThrows(
					AttributeNotFoundException.class,
					() -> fixture.compile(REF_BRANDS, filterBy, null, BOTH_SCOPES, true, null),
					"A check validates " + invalidNested + " whatever the data"
				);
				final ReferenceRowFilter fetchFilter = fixture.compile(filterBy, null);
				final Entity owner = fetchOwners(evita).get(1);
				final ReferenceContract row = owner.getReferences(REF_BRANDS).iterator().next();
				assertThrows(
					AttributeNotFoundException.class,
					() -> fetchFilter.test(owner.getScope(), owner, row),
					"A fetch plans " + invalidNested + " when a row reaches it"
				);
			}
		}

		@Test
		@DisplayName("a check hands the capabilities of nested filters to the query, the row leaves request none")
		@UseDataSet(ROW_FILTER)
		void shouldCountNestedCapabilitiesOnlyInCheck(Evita evita) {
			final QueryPlanningContext rowLeafContext = createQueryContext(evita);
			Fixture.compile(
				rowLeafContext, REF_BRANDS, filterBy(attributeEquals(ATTR_WEIGHT, 5), attributeEquals(ATTR_LABEL, "x")),
				Locale.ENGLISH, BOTH_SCOPES, true, null
			);
			assertTrue(rowLeafContext.drainRequestedCapabilities().isEmpty(), "Row leaves request no capability");

			final QueryPlanningContext nestedContext = createQueryContext(evita);
			Fixture.compile(
				nestedContext, REF_BRANDS, filterBy(entityHaving(attributeEquals(ATTR_CODE, "a"))),
				null, BOTH_SCOPES, true, null
			);
			assertFalse(
				nestedContext.drainRequestedCapabilities().isEmpty(),
				"The nested filter requests the filterable `code` of the brand"
			);
		}

	}

	@Nested
	@DisplayName("Refusals")
	class Refusals {

		@Test
		@DisplayName("a constraint without a meaning on a row is refused, naming itself and the reference")
		@UseDataSet(ROW_FILTER)
		void shouldRefuseConstraintWithoutMeaningOnRow(Evita evita) {
			final Fixture fixture = new Fixture(evita);
			for (FilterConstraint constraint : List.of(
				entityPrimaryKeyBetween(1, 5),
				referenceHaving(REF_EXTERNALS, attributeEquals(ATTR_WEIGHT, 1)),
				userFilter(attributeEquals(ATTR_WEIGHT, 5)),
				priceBetween(BigDecimal.ONE, BigDecimal.TEN),
				hierarchyWithinRootSelf()
			)) {
				final EvitaInvalidUsageException exception = assertThrows(
					EvitaInvalidUsageException.class,
					() -> fixture.compile(filterBy(or(attributeEquals(ATTR_WEIGHT, 5), constraint)), null),
					() -> constraint + " must be refused"
				);
				assertTrue(exception.getMessage().contains(constraint.toString()), exception.getMessage());
				assertTrue(exception.getMessage().contains("`" + REF_BRANDS + "`"), exception.getMessage());
			}
		}

		@Test
		@DisplayName("scope below the root is refused, never read as true")
		@UseDataSet(ROW_FILTER)
		void shouldRefuseScopeBelowRoot(Evita evita) {
			final Fixture fixture = new Fixture(evita);
			for (FilterBy filterBy : List.of(
				filterBy(or(scope(Scope.LIVE), attributeEquals(ATTR_WEIGHT, 5))),
				filterBy(not(scope(Scope.LIVE))),
				filterBy(inScope(Scope.LIVE, scope(Scope.LIVE))),
				filterBy(and(scope(Scope.LIVE), attributeEquals(ATTR_WEIGHT, 5)))
			)) {
				final EvitaInvalidUsageException exception = assertThrows(
					EvitaInvalidUsageException.class, () -> fixture.compile(filterBy, null), filterBy::toString
				);
				assertTrue(exception.getMessage().contains("only directly in its `filterBy`"), exception.getMessage());
			}
		}

		@Test
		@DisplayName("an inverted interval over a scalar is refused with the message of the index")
		@UseDataSet(ROW_FILTER)
		void shouldRefuseInvertedInterval(Evita evita) {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new Fixture(evita).compile(filterBy(attributeBetween(ATTR_WEIGHT, 10, 5)), null)
			);
			assertEquals("From must be lower than to: 10 vs. 5", exception.getMessage());
		}

		@Test
		@DisplayName("leaves that cannot be evaluated whatever the data are refused")
		@UseDataSet(ROW_FILTER)
		void shouldRefuseLeavesThatCannotBeEvaluated(Evita evita) {
			final Fixture fixture = new Fixture(evita);
			assertThrows(
				AttributeNotFoundException.class,
				() -> fixture.compile(filterBy(attributeEquals("unknown", 1)), null)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> fixture.compile(filterBy(attributeStartsWith(ATTR_WEIGHT, "1")), null)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> fixture.compile(filterBy(attributeInRange(ATTR_WEIGHT, 5)), null)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> fixture.compile(filterBy(attributeEquals(ATTR_WEIGHT, "five")), null)
			);
		}

		@Test
		@DisplayName("nested filters over unmanaged or undeclared types are refused")
		@UseDataSet(ROW_FILTER)
		void shouldRefuseNestedFiltersOverUnmanagedTypes(Evita evita) {
			final Fixture fixture = new Fixture(evita);
			assertThrows(
				EntityNotManagedException.class,
				() -> fixture.compile(
					REF_EXTERNALS, filterBy(entityHaving(attributeEquals(ATTR_CODE, "a"))), null, BOTH_SCOPES,
					false, null
				)
			);
			final EvitaInvalidUsageException noGroup = assertThrows(
				EvitaInvalidUsageException.class,
				() -> fixture.compile(
					REF_UNGROUPED, filterBy(groupHaving(attributeEquals(ATTR_CODE, "g1"))), null, BOTH_SCOPES,
					false, null
				)
			);
			assertTrue(noGroup.getMessage().contains("does not reference any group type"), noGroup.getMessage());
			final EvitaInvalidUsageException unmanagedGroup = assertThrows(
				EvitaInvalidUsageException.class,
				() -> fixture.compile(
					REF_FOREIGN_GROUP, filterBy(groupHaving(attributeEquals(ATTR_CODE, "g1"))), null, BOTH_SCOPES,
					false, null
				)
			);
			assertTrue(unmanagedGroup.getMessage().contains("not managed by evitaDB"), unmanagedGroup.getMessage());
		}

		@Test
		@DisplayName("inScope of a scope the request does not ask for and two filter locales are refused")
		@UseDataSet(ROW_FILTER)
		void shouldRefuseUnrequestedScopeAndSecondLocale(Evita evita) {
			final Fixture fixture = new Fixture(evita);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> fixture.compile(
					REF_BRANDS, filterBy(inScope(Scope.ARCHIVED, attributeEquals(ATTR_WEIGHT, 5))), null,
					EnumSet.of(Scope.LIVE), false, null
				)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> fixture.compile(
					filterBy(entityLocaleEquals(Locale.ENGLISH), entityHaving(entityLocaleEquals(Locale.GERMAN))),
					null
				)
			);
		}

		@Test
		@DisplayName("the leaves of a reference indexed nowhere and of attributes filterable nowhere are evaluated")
		@UseDataSet(ROW_FILTER)
		void shouldEvaluateLeavesWithoutAnyIndex(Evita evita) {
			final Fixture fixture = new Fixture(evita);
			final Entity owner = fetchOwners(evita).get(1);
			final ReferenceContract external = owner.getReferences(REF_EXTERNALS).iterator().next();
			final ReferenceRowFilter filter = fixture.compile(
				REF_EXTERNALS, filterBy(attributeEquals(ATTR_WEIGHT, 1), entityPrimaryKeyInSet(100)), null, BOTH_SCOPES,
				false, null
			);
			assertTrue(filter.test(Scope.LIVE, owner, external));
		}

	}

	@Nested
	@DisplayName("Owners the rows are read off")
	class OwnerCompleteness {

		@Test
		@DisplayName("an owner read with the filtered reference hands over its raw entity")
		@UseDataSet(ROW_FILTER)
		void shouldHandOverRawEntityOfOwnerReadWithTheReference(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity owner = session.getEntity(ENTITY_PRODUCT, 1, referenceContentAll())
						.orElseThrow();
					assertTrue(
						ReferencedEntityFetcher.getOwnerCarryingEveryRow(owner, REF_BRANDS)
							== ((EntityDecorator) owner).getDelegate()
					);
					return null;
				}
			);
		}

		@Test
		@DisplayName("an owner read without any reference counts as read again with all of them")
		@UseDataSet(ROW_FILTER)
		void shouldHandOverRawEntityOfOwnerReadWithoutReferences(Evita evita) {
			// `EntityCollection#ensureReferencesFetched` reads such an owner again with every reference before the
			// fetch reaches it, keeping the original predicate
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity owner = session.getEntity(ENTITY_PRODUCT, 1, attributeContentAll())
						.orElseThrow();
					assertTrue(
						ReferencedEntityFetcher.getOwnerCarryingEveryRow(owner, REF_BRANDS)
							== ((EntityDecorator) owner).getDelegate()
					);
					return null;
				}
			);
		}

		@Test
		@DisplayName("an owner read with other references only is refused")
		@UseDataSet(ROW_FILTER)
		void shouldRefuseOwnerReadWithOtherReferencesOnly(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity owner = session.getEntity(ENTITY_PRODUCT, 1, referenceContent(REF_EXTERNALS))
						.orElseThrow();
					assertThrows(
						GenericEvitaInternalError.class,
						() -> ReferencedEntityFetcher.getOwnerCarryingEveryRow(owner, REF_BRANDS)
					);
					return null;
				}
			);
		}

		@Test
		@DisplayName("an owner that is no decorator is refused")
		@UseDataSet(ROW_FILTER)
		void shouldRefuseOwnerThatIsNoDecorator(Evita evita) {
			final Entity raw = fetchOwners(evita).get(1);
			assertThrows(
				GenericEvitaInternalError.class, () -> ReferencedEntityFetcher.getOwnerCarryingEveryRow(raw, REF_BRANDS)
			);
		}

	}

}
