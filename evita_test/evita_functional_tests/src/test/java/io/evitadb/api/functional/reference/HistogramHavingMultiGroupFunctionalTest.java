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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
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
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.histogramHaving;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.or;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Row semantics of `histogramHaving` with a group selector - and of the `referenceHaving(entityHaving, groupHaving)`
 * body it is rewritten to - on a reference whose referenced entities belong to **several groups sharing one value
 * attribute**, which is the shape of a production parameter catalog: widths, heights and weights all carry their
 * value in the same `basicUnitValue`, so a value range alone selects rows of every group and only the group selector
 * tells them apart.
 *
 * That shape is what made the row-scoped evaluation of #1644 expensive: the candidate reduced indexes were selected
 * by the value range across all groups, and every later per-index step multiplied that count. The engine now narrows
 * the candidates by the group and settles the value condition once per index; this class pins that the answers stay
 * row-exact while it does, across the shapes the narrowing and the per-index evaluation have to agree on - two
 * sliders at once, ranges that match many values of other groups and few of the selected one, group sets, negations
 * in every position, archived owners and targets, duplicate rows told apart by a representative attribute, and the
 * `referenceContent` fetch path.
 *
 * The expected answers are never written down. They are derived from the fixture's own row model by a row
 * predicate - the SQL `EXISTS` reading of `referenceHaving` - so a fixture change cannot make a shape agree with a
 * wrong engine. Every query is also verified against every alternative index plan and caching tree the engine can
 * build.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("histogramHaving / referenceHaving — row semantics over several groups sharing one value attribute")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
@Tag(HISTOGRAM)
public class HistogramHavingMultiGroupFunctionalTest implements EvitaTestSupport {

	/** Shared read-only fixture seeded once per run. */
	public static final String MULTI_GROUP_DATA_SET = "histogramHavingMultiGroupDataSet";

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER_TYPE = "parameterType";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";

	/** The reference the histogram lives on - one row per referenced value. */
	private static final String REF_PARAMETER_VALUES = "parameterValues";
	/** A reference allowing duplicates, whose rows are told apart by the representative `variant`. */
	private static final String REF_PARAMETER_VARIANTS = "parameterVariants";

	private static final String ATTR_CODE = "code";
	private static final String ATTR_HAS_RANGE_BASIC_UNIT_VALUE = "hasRangeBasicUnitValue";
	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";
	private static final String ATTR_BASIC_UNIT_VALUE = "basicUnitValue";
	private static final String ATTR_VARIANT = "variant";

	private static final String HISTOGRAM_INTERVAL = "intervalParameterValues";
	private static final String INPUT_WIDGET_INTERVAL = "INTERVAL_INPUT";

	/** Groups; the width group holds few values, the other two many, all sharing the value attribute. */
	private static final int GROUP_WIDTH_PK = 1;
	private static final int GROUP_HEIGHT_PK = 2;
	private static final int GROUP_WEIGHT_PK = 3;
	/** A group primary key no entity carries. */
	private static final int GROUP_NONEXISTENT_PK = 999;
	private static final String CODE_WIDTH = "width";
	private static final String CODE_HEIGHT = "height";
	private static final String CODE_WEIGHT = "weight";
	/** Highest value of every group - the width group deliberately holds a third of the others. */
	private static final Map<Integer, Integer> MAX_VALUE_OF_GROUP = Map.of(
		GROUP_WIDTH_PK, 40, GROUP_HEIGHT_PK, 120, GROUP_WEIGHT_PK, 120
	);
	private static final Map<Integer, String> CODE_OF_GROUP = Map.of(
		GROUP_WIDTH_PK, CODE_WIDTH, GROUP_HEIGHT_PK, CODE_HEIGHT, GROUP_WEIGHT_PK, CODE_WEIGHT
	);

	/** The single archived parameter value - a weight valued 60, inside most ranges below. */
	private static final int ARCHIVED_PARAMETER_VALUE_PK = parameterValuePk(GROUP_WEIGHT_PK, 60);

	/** The live product guaranteed to hold a row pointing at the archived parameter value. */
	private static final int PRODUCT_WITH_ARCHIVED_VALUE_PK = 2;

	private static final int PRODUCT_COUNT = 90;
	/** Products from this primary key on are archived. */
	private static final int FIRST_ARCHIVED_PRODUCT_PK = 81;
	/** Products up to this primary key carry rows of the duplicate-allowing reference. */
	private static final int LAST_VARIANT_PRODUCT_PK = 30;
	/** Fixed seed, so the fixture - and every expectation derived from it - is the same on every run. */
	private static final long SEED = 1644L;

	/** Every reference row of the fixture, in insertion order. */
	private static final List<Row> ROWS = generateRows();

	/**
	 * One reference row of the fixture.
	 *
	 * @param ownerPk          primary key of the owning product
	 * @param referenceName    reference the row belongs to
	 * @param parameterValuePk primary key of the referenced parameter value
	 * @param groupPk          primary key of the row's group, NULL for an ungrouped row
	 * @param variant          the representative attribute of a duplicate-allowing row, NULL otherwise
	 */
	private record Row(
		int ownerPk,
		@Nonnull String referenceName,
		int parameterValuePk,
		@Nullable Integer groupPk,
		@Nullable String variant
	) {

		/**
		 * Returns the row's value as seen by a query over the passed scopes - absent when the referenced parameter
		 * value lives in a scope the query does not reach, because the nested query cannot see it there.
		 *
		 * @param scopes the queried scopes
		 * @return the value, or NULL when the referenced entity is not visible
		 */
		@Nullable
		Integer visibleValue(@Nonnull Set<Scope> scopes) {
			return scopes.contains(scopeOfParameterValue(this.parameterValuePk)) ?
				valueOf(this.parameterValuePk) : null;
		}

		/**
		 * Returns TRUE when the row's value is visible and lies inside the inclusive range.
		 *
		 * @param scopes the queried scopes
		 * @param from   inclusive lower bound
		 * @param to     inclusive upper bound
		 * @return whether the value condition holds on this row
		 */
		boolean valueBetween(@Nonnull Set<Scope> scopes, int from, int to) {
			final Integer value = visibleValue(scopes);
			return value != null && value >= from && value <= to;
		}

		/**
		 * Returns TRUE when the row belongs to one of the passed groups.
		 *
		 * @param groupPks the accepted groups
		 * @return whether the group condition holds on this row
		 */
		boolean groupIn(@Nonnull Integer... groupPks) {
			return this.groupPk != null && Set.of(groupPks).contains(this.groupPk);
		}
	}

	/**
	 * Returns the primary key of the parameter value of `groupPk` valued `value`.
	 *
	 * @param groupPk the group the value belongs to
	 * @param value   the value
	 * @return the primary key
	 */
	private static int parameterValuePk(int groupPk, int value) {
		return groupPk * 1000 + value;
	}

	/**
	 * Returns the value of a parameter value - it is encoded in its primary key.
	 *
	 * @param parameterValuePk primary key of the parameter value
	 * @return its `basicUnitValue`
	 */
	private static int valueOf(int parameterValuePk) {
		return parameterValuePk % 1000;
	}

	/**
	 * Returns the scope the parameter value lives in.
	 *
	 * @param parameterValuePk primary key of the parameter value
	 * @return its scope
	 */
	@Nonnull
	private static Scope scopeOfParameterValue(int parameterValuePk) {
		return parameterValuePk == ARCHIVED_PARAMETER_VALUE_PK ? Scope.ARCHIVED : Scope.LIVE;
	}

	/**
	 * Returns the scope the product lives in.
	 *
	 * @param productPk primary key of the product
	 * @return its scope
	 */
	@Nonnull
	private static Scope scopeOfProduct(int productPk) {
		return productPk >= FIRST_ARCHIVED_PRODUCT_PK ? Scope.ARCHIVED : Scope.LIVE;
	}

	/**
	 * Generates the row model. Most rows carry the group their value naturally belongs to, one in ten a random
	 * other group and one in twelve no group at all - so a row's group is a property of the row, not of the value,
	 * and a narrowing that assumed otherwise would drop rows. Product 1 additionally holds, on the duplicate-allowing
	 * reference, the same parameter value twice: once as a width and once as a height, told apart by the variant.
	 *
	 * @return the rows
	 */
	@Nonnull
	private static List<Row> generateRows() {
		final Random random = new Random(SEED);
		final List<Row> rows = new ArrayList<>(PRODUCT_COUNT * 4);
		for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
			final Set<Integer> usedValues = new LinkedHashSet<>();
			if (productPk == PRODUCT_WITH_ARCHIVED_VALUE_PK) {
				// a live owner's row pointing at the archived value, grouped as the weight it is
				usedValues.add(ARCHIVED_PARAMETER_VALUE_PK);
				rows.add(new Row(productPk, REF_PARAMETER_VALUES, ARCHIVED_PARAMETER_VALUE_PK, GROUP_WEIGHT_PK, null));
			}
			final int rowCount = usedValues.size() + 1 + random.nextInt(5);
			while (usedValues.size() < rowCount) {
				final int naturalGroup = 1 + random.nextInt(3);
				usedValues.add(
					parameterValuePk(naturalGroup, 1 + random.nextInt(MAX_VALUE_OF_GROUP.get(naturalGroup)))
				);
			}
			for (Integer parameterValuePk : usedValues) {
				if (parameterValuePk == ARCHIVED_PARAMETER_VALUE_PK && productPk == PRODUCT_WITH_ARCHIVED_VALUE_PK) {
					// its row was added above with a fixed group
					continue;
				}
				final int roll = random.nextInt(120);
				final Integer groupPk = roll < 10 ? null : roll < 22 ? 1 + random.nextInt(3) : parameterValuePk / 1000;
				rows.add(new Row(productPk, REF_PARAMETER_VALUES, parameterValuePk, groupPk, null));
			}
			if (productPk <= LAST_VARIANT_PRODUCT_PK) {
				if (productPk == 1) {
					final int shared = parameterValuePk(GROUP_WIDTH_PK, 10);
					rows.add(new Row(productPk, REF_PARAMETER_VARIANTS, shared, GROUP_WIDTH_PK, "a"));
					rows.add(new Row(productPk, REF_PARAMETER_VARIANTS, shared, GROUP_HEIGHT_PK, "b"));
				} else {
					final int variantRows = 1 + random.nextInt(3);
					for (int i = 0; i < variantRows; i++) {
						final int naturalGroup = 1 + random.nextInt(3);
						final int parameterValuePk = parameterValuePk(
							naturalGroup, 1 + random.nextInt(MAX_VALUE_OF_GROUP.get(naturalGroup))
						);
						rows.add(
							new Row(
								productPk, REF_PARAMETER_VARIANTS, parameterValuePk,
								random.nextInt(4) == 0 ? null : 1 + random.nextInt(3),
								i % 2 == 0 ? "a" : "b"
							)
						);
					}
				}
			}
		}
		return rows;
	}

	/**
	 * Returns the products in the queried scopes holding at least one row of the reference that satisfies the
	 * predicate - the `EXISTS` reading of `referenceHaving`.
	 *
	 * @param scopes        the queried scopes
	 * @param referenceName the reference
	 * @param rowPredicate  the condition one row has to satisfy entirely
	 * @return ordered product primary keys
	 */
	@Nonnull
	private static Set<Integer> expectedOwners(
		@Nonnull Set<Scope> scopes,
		@Nonnull String referenceName,
		@Nonnull Predicate<Row> rowPredicate
	) {
		return ROWS.stream()
			.filter(row -> row.referenceName().equals(referenceName))
			.filter(row -> scopes.contains(scopeOfProduct(row.ownerPk())))
			.filter(rowPredicate)
			.map(Row::ownerPk)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * Returns every product in the queried scopes.
	 *
	 * @param scopes the queried scopes
	 * @return ordered product primary keys
	 */
	@Nonnull
	private static Set<Integer> allOwners(@Nonnull Set<Scope> scopes) {
		final Set<Integer> result = new TreeSet<>();
		for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
			if (scopes.contains(scopeOfProduct(productPk))) {
				result.add(productPk);
			}
		}
		return result;
	}

	/**
	 * Defines the schema: a group type, a referenced type whose value attribute is shared by every group, and a
	 * product with two references to it - one row per value (carrying the bucketed histogram), and one allowing
	 * duplicates told apart by a representative attribute. Both are indexed in both scopes with the referenced
	 * entity and referenced group entity components.
	 *
	 * @param session write session on the test catalog
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PARAMETER_TYPE)
			.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(Scope.values()))
			.withAttribute(ATTR_HAS_RANGE_BASIC_UNIT_VALUE, Boolean.class, AttributeSchemaEditor::filterable)
			.withAttribute(ATTR_INPUT_WIDGET_TYPE, String.class, AttributeSchemaEditor::filterable)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withAttribute(
				ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
				whichIs -> whichIs.filterableInScope(Scope.values()).indexDecimalPlaces(2).nullable()
			)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					whichIs
						.indexedForFilteringInScope(Scope.values())
						.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_TYPE)
						.bucketedInScope(
							Scope.LIVE,
							HISTOGRAM_INTERVAL,
							ExpressionFactory.parse(
								"$reference.referencedEntity?.attributes['" + ATTR_BASIC_UNIT_VALUE + "'] ?? 0.0"
							),
							ExpressionFactory.parse(
								"$reference.groupEntity?.attributes['" + ATTR_HAS_RANGE_BASIC_UNIT_VALUE + "'] == false"
							)
						)
						.bucketedPartially(
							ExpressionFactory.parse(
								"$reference.groupEntity?.attributes['" + ATTR_INPUT_WIDGET_TYPE + "'] == '" +
									INPUT_WIDGET_INTERVAL + "'"
							)
						);
					for (final Scope scope : Scope.values()) {
						whichIs.indexedWithComponentsInScope(
							scope,
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						);
					}
				}
			)
			.withReferenceToEntity(
				REF_PARAMETER_VARIANTS, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> {
					whichIs
						.indexedForFilteringInScope(Scope.values())
						.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_TYPE)
						.withAttribute(
							ATTR_VARIANT, String.class,
							thatIs -> thatIs.filterableInScope(Scope.values()).representative()
						);
					for (final Scope scope : Scope.values()) {
						whichIs.indexedWithComponentsInScope(
							scope,
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						);
					}
				}
			)
			.updateVia(session);
	}

	/**
	 * Seeds the groups, every parameter value of every group, the products carrying the generated rows, and finally
	 * archives the products and the parameter value that live in the archive.
	 *
	 * @param session write session on the test catalog
	 */
	private static void seedData(@Nonnull EvitaSessionContract session) {
		for (Map.Entry<Integer, String> group : new TreeMap<>(CODE_OF_GROUP).entrySet()) {
			session.createNewEntity(ENTITY_PARAMETER_TYPE, group.getKey())
				.setAttribute(ATTR_CODE, group.getValue())
				.setAttribute(ATTR_HAS_RANGE_BASIC_UNIT_VALUE, false)
				.setAttribute(ATTR_INPUT_WIDGET_TYPE, INPUT_WIDGET_INTERVAL)
				.upsertVia(session);
		}
		for (Map.Entry<Integer, Integer> group : new TreeMap<>(MAX_VALUE_OF_GROUP).entrySet()) {
			for (int value = 1; value <= group.getValue(); value++) {
				session.createNewEntity(ENTITY_PARAMETER_VALUE, parameterValuePk(group.getKey(), value))
					.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal(value))
					.upsertVia(session);
			}
		}
		for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
			final int ownerPk = productPk;
			final var builder = session.createNewEntity(ENTITY_PRODUCT, ownerPk);
			ROWS.stream()
				.filter(row -> row.ownerPk() == ownerPk)
				.forEach(row -> {
					if (row.variant() == null) {
						builder.setReference(
							row.referenceName(), row.parameterValuePk(),
							whichIs -> {
								if (row.groupPk() != null) {
									whichIs.setGroup(ENTITY_PARAMETER_TYPE, row.groupPk());
								}
							}
						);
					} else {
						// the reference allows duplicates - an always-false filter forces a new row rather than an
						// update of the row already pointing at the same parameter value
						builder.setOrUpdateReference(
							row.referenceName(), row.parameterValuePk(),
							Functions.alwaysFalse(),
							whichIs -> {
								whichIs.setAttribute(ATTR_VARIANT, row.variant());
								if (row.groupPk() != null) {
									whichIs.setGroup(ENTITY_PARAMETER_TYPE, row.groupPk());
								}
							}
						);
					}
				});
			builder.upsertVia(session);
		}
		// archiving is the last step, so every row above is indexed while its owner and target are still live
		session.archiveEntity(ENTITY_PARAMETER_VALUE, ARCHIVED_PARAMETER_VALUE_PK);
		for (int productPk = FIRST_ARCHIVED_PRODUCT_PK; productPk <= PRODUCT_COUNT; productPk++) {
			session.archiveEntity(ENTITY_PRODUCT, productPk);
		}
	}

	/**
	 * Installs schema and data once and exposes the catalog via `@UseDataSet`.
	 *
	 * @param evita the shared evitaDB instance
	 * @return empty data carrier; tests read the catalog through the session
	 */
	@DataSet(MULTI_GROUP_DATA_SET)
	DataCarrier setUp(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG, session -> {
				defineSchema(session);
				seedData(session);
			}
		);
		return new DataCarrier();
	}

	/**
	 * Runs the filter on the product collection in the passed scopes and returns the matched primary keys. Every
	 * alternative index plan and caching tree the engine can produce is verified against the primary one, so a
	 * plan-dependent answer fails here rather than passing on the plan that happened to be chosen.
	 *
	 * @param evita  the evitaDB instance holding the shared dataset
	 * @param scopes the scopes to query
	 * @param filter the filter constraint
	 * @return ordered set of matched product primary keys
	 */
	@Nonnull
	private static Set<Integer> matchingProducts(
		@Nonnull Evita evita,
		@Nonnull Set<Scope> scopes,
		@Nonnull FilterConstraint filter
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(scopes.toArray(Scope[]::new)), filter),
						require(
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							page(1, Integer.MAX_VALUE)
						)
					),
					EntityReference.class
				);
				return result.getRecordData()
					.stream()
					.map(EntityReference::getPrimaryKey)
					.collect(Collectors.toCollection(TreeSet::new));
			}
		);
	}

	/**
	 * Runs the filter on live products and returns the matched primary keys.
	 *
	 * @param evita  the evitaDB instance holding the shared dataset
	 * @param filter the filter constraint
	 * @return ordered set of matched product primary keys
	 */
	@Nonnull
	private static Set<Integer> matchingLiveProducts(@Nonnull Evita evita, @Nonnull FilterConstraint filter) {
		return matchingProducts(evita, EnumSet.of(Scope.LIVE), filter);
	}

	/**
	 * Asserts that the expectation is non-trivial - neither empty nor every queried owner - so that a green result
	 * actually discriminates between a correct and a broken engine.
	 *
	 * @param expected the expectation
	 * @param scopes   the queried scopes
	 */
	private static void assertDiscriminating(@Nonnull Set<Integer> expected, @Nonnull Set<Scope> scopes) {
		assertFalse(
			expected.isEmpty(), "The fixture must make this shape match something, or the test proves nothing."
		);
		assertNotEquals(allOwners(scopes), expected, "The fixture must make this shape reject some owner.");
	}

	/**
	 * The histogram selector of one group.
	 *
	 * @param code code of the group
	 * @return group selector constraint
	 */
	@Nonnull
	private static GroupHaving groupCoded(@Nonnull String code) {
		return groupHaving(attributeEquals(ATTR_CODE, code));
	}

	/**
	 * The value condition as the histogram rewrite spells it.
	 *
	 * @param from inclusive lower bound
	 * @param to   inclusive upper bound
	 * @return referenced entity value condition
	 */
	@Nonnull
	private static FilterConstraint valueBetween(int from, int to) {
		return entityHaving(attributeBetween(ATTR_BASIC_UNIT_VALUE, from, to));
	}

	/**
	 * Proves the fixture separates the row-scoped reading from the pooled one on the two-slider shape before any
	 * test relies on it.
	 */
	@Nested
	@DisplayName("Fixture discriminates the pooled reading from the row-scoped one")
	class FixtureShape {

		@Test
		@DisplayName("the two-slider shape admits more owners when its conditions are pooled across rows")
		void shouldSeparatePooledFromRowScopedReading() {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> rowScoped = expectedOwners(
				live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_HEIGHT_PK) && row.valueBetween(live, 50, 90)
			);
			final Set<Integer> pooled = new TreeSet<>(
				expectedOwners(live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_HEIGHT_PK))
			);
			pooled.retainAll(expectedOwners(live, REF_PARAMETER_VALUES, row -> row.valueBetween(live, 50, 90)));
			assertNotEquals(
				rowScoped, pooled, "The fixture must hold owners whose group and value sit on different rows."
			);
		}
	}

	/**
	 * The shapes a storefront sends: one or more `histogramHaving` sliders, each with a group selector.
	 */
	@Nested
	@DisplayName("histogramHaving sliders")
	class HistogramSliders {

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("a range matching many values of other groups and few of the selected one")
		void shouldBindTheSliderToTheSelectedGroupWhenOtherGroupsDominateTheRange(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_WIDTH_PK) && row.valueBetween(live, 30, 120)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita, histogramHaving(REF_PARAMETER_VALUES, HISTOGRAM_INTERVAL, 30, 120, groupCoded(CODE_WIDTH))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("a range wider than every value of the selected group")
		void shouldMatchEveryRowOfTheGroupWhenTheRangeCoversAllOfIt(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_HEIGHT_PK) && row.valueBetween(live, 0, 1000)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita, histogramHaving(REF_PARAMETER_VALUES, HISTOGRAM_INTERVAL, 0, 1000, groupCoded(CODE_HEIGHT))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("two sliders on different groups, each bound to its own row")
		void shouldBindEachOfTwoSlidersToItsOwnRow(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = new TreeSet<>(
				expectedOwners(
					live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_WIDTH_PK) && row.valueBetween(live, 10, 35)
				)
			);
			expected.retainAll(
				expectedOwners(
					live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_HEIGHT_PK) && row.valueBetween(live, 50, 90)
				)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita,
					and(
						histogramHaving(REF_PARAMETER_VALUES, HISTOGRAM_INTERVAL, 10, 35, groupCoded(CODE_WIDTH)),
						histogramHaving(REF_PARAMETER_VALUES, HISTOGRAM_INTERVAL, 50, 90, groupCoded(CODE_HEIGHT))
					)
				)
			);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita,
					userFilter(
						histogramHaving(REF_PARAMETER_VALUES, HISTOGRAM_INTERVAL, 10, 35, groupCoded(CODE_WIDTH)),
						histogramHaving(REF_PARAMETER_VALUES, HISTOGRAM_INTERVAL, 50, 90, groupCoded(CODE_HEIGHT))
					)
				),
				"`userFilter` must not change which rows the sliders bind to"
			);
		}
	}

	/**
	 * The body the slider is rewritten to, written by hand - and the shapes around it the rewrite never produces but
	 * the same evaluation has to answer.
	 */
	@Nested
	@DisplayName("referenceHaving with entityHaving and groupHaving")
	class ReferenceHavingBodies {

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("value and group on one row")
		void shouldBindValueAndGroupToOneRow(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_HEIGHT_PK) && row.valueBetween(live, 50, 70)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita, referenceHaving(REF_PARAMETER_VALUES, valueBetween(50, 70), groupCoded(CODE_HEIGHT))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("a group selector matching two groups")
		void shouldAcceptEitherOfTwoGroupsOnTheRowCarryingTheValue(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VALUES,
				row -> row.groupIn(GROUP_WIDTH_PK, GROUP_HEIGHT_PK) && row.valueBetween(live, 35, 45)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita,
					referenceHaving(
						REF_PARAMETER_VALUES,
						valueBetween(35, 45),
						groupHaving(entityPrimaryKeyInSet(GROUP_WIDTH_PK, GROUP_HEIGHT_PK))
					)
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("a group selector matching no group answers nothing and does not fail")
		void shouldAnswerNothingForAGroupSelectorMatchingNoGroup(@Nonnull Evita evita) {
			assertEquals(
				Set.of(),
				matchingLiveProducts(
					evita,
					referenceHaving(
						REF_PARAMETER_VALUES, valueBetween(1, 120),
						groupHaving(entityPrimaryKeyInSet(GROUP_NONEXISTENT_PK))
					)
				)
			);
			assertEquals(
				Set.of(),
				matchingLiveProducts(
					evita, referenceHaving(REF_PARAMETER_VALUES, groupHaving(attributeEquals(ATTR_CODE, "nonexistent")))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("a negated group on the row carrying the value")
		void shouldComplementTheGroupPerRow(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VALUES, row -> !row.groupIn(GROUP_WIDTH_PK) && row.valueBetween(live, 20, 30)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita, referenceHaving(REF_PARAMETER_VALUES, valueBetween(20, 30), not(groupCoded(CODE_WIDTH)))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("a negated value on the row carrying the group")
		void shouldComplementTheValuePerRow(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_WEIGHT_PK) && !row.valueBetween(live, 1, 60)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita, referenceHaving(REF_PARAMETER_VALUES, groupCoded(CODE_WEIGHT), not(valueBetween(1, 60)))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("value or group on one row")
		void shouldAcceptEitherConditionOnARow(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VALUES, row -> row.valueBetween(live, 100, 110) || row.groupIn(GROUP_WIDTH_PK)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita, referenceHaving(REF_PARAMETER_VALUES, or(valueBetween(100, 110), groupCoded(CODE_WIDTH)))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("the whole reference condition negated at the owner level")
		void shouldComplementTheWholeConditionPerOwner(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = new TreeSet<>(allOwners(live));
			expected.removeAll(
				expectedOwners(
					live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_HEIGHT_PK) && row.valueBetween(live, 50, 90)
				)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita, not(referenceHaving(REF_PARAMETER_VALUES, valueBetween(50, 90), groupCoded(CODE_HEIGHT)))
				)
			);
		}
	}

	/**
	 * Archived owners and an archived referenced entity: the value of a row is only visible to a query reaching the
	 * scope its referenced entity lives in.
	 */
	@Nested
	@DisplayName("Scopes")
	class Scopes {

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("live and archived owners queried together")
		void shouldBindValueAndGroupToOneRowAcrossBothScopes(@Nonnull Evita evita) {
			final Set<Scope> both = EnumSet.allOf(Scope.class);
			final Set<Integer> expected = expectedOwners(
				both, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_WEIGHT_PK) && row.valueBetween(both, 55, 65)
			);
			assertDiscriminating(expected, both);
			assertEquals(
				expected,
				matchingProducts(
					evita, both, referenceHaving(REF_PARAMETER_VALUES, valueBetween(55, 65), groupCoded(CODE_WEIGHT))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("archived owners alone, with a negated group")
		void shouldComplementTheGroupPerRowForArchivedOwners(@Nonnull Evita evita) {
			final Set<Scope> archived = EnumSet.of(Scope.ARCHIVED);
			final Set<Integer> expected = expectedOwners(
				archived, REF_PARAMETER_VALUES,
				row -> !row.groupIn(GROUP_HEIGHT_PK) && row.valueBetween(archived, 1, 120)
			);
			assertEquals(
				expected,
				matchingProducts(
					evita, archived,
					referenceHaving(REF_PARAMETER_VALUES, valueBetween(1, 120), not(groupCoded(CODE_HEIGHT)))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("a live owner's row pointing at an archived value counts only when the archive is queried")
		void shouldSeeTheArchivedValueOnlyWhenItsScopeIsQueried(@Nonnull Evita evita) {
			final FilterConstraint filter = referenceHaving(
				REF_PARAMETER_VALUES, valueBetween(60, 60), groupCoded(CODE_WEIGHT)
			);
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Scope> both = EnumSet.allOf(Scope.class);
			final Set<Integer> expectedLive = expectedOwners(
				live, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_WEIGHT_PK) && row.valueBetween(live, 60, 60)
			);
			final Set<Integer> expectedBoth = expectedOwners(
				both, REF_PARAMETER_VALUES, row -> row.groupIn(GROUP_WEIGHT_PK) && row.valueBetween(both, 60, 60)
			);
			assertNotEquals(expectedLive, expectedBoth, "The fixture must hold a row pointing at the archived value.");
			assertEquals(expectedLive, matchingProducts(evita, live, filter));
			assertEquals(expectedBoth, matchingProducts(evita, both, filter));
		}
	}

	/**
	 * A reference allowing duplicates: one owner may point at the same parameter value several times, each row with
	 * its own group, told apart by the representative `variant`. Product 1 holds one value as a width (variant `a`)
	 * and as a height (variant `b`).
	 */
	@Nested
	@DisplayName("Duplicate rows told apart by a representative attribute")
	class DuplicateRows {

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("value and group bind to one of the duplicate rows")
		void shouldBindValueAndGroupToOneDuplicateRow(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VARIANTS, row -> row.groupIn(GROUP_HEIGHT_PK) && row.valueBetween(live, 5, 15)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita, referenceHaving(REF_PARAMETER_VARIANTS, valueBetween(5, 15), groupCoded(CODE_HEIGHT))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("the representative attribute and the group bind to the same duplicate row")
		void shouldBindRepresentativeAttributeAndGroupToOneDuplicateRow(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VARIANTS, row -> "a".equals(row.variant()) && row.groupIn(GROUP_HEIGHT_PK)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita,
					referenceHaving(REF_PARAMETER_VARIANTS, attributeEquals(ATTR_VARIANT, "a"), groupCoded(CODE_HEIGHT))
				)
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("a negated group on the duplicate row carrying the value")
		void shouldComplementTheGroupPerDuplicateRow(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			final Set<Integer> expected = expectedOwners(
				live, REF_PARAMETER_VARIANTS, row -> !row.groupIn(GROUP_WIDTH_PK) && row.valueBetween(live, 8, 12)
			);
			assertDiscriminating(expected, live);
			assertEquals(
				expected,
				matchingLiveProducts(
					evita, referenceHaving(REF_PARAMETER_VARIANTS, valueBetween(8, 12), not(groupCoded(CODE_WIDTH)))
				)
			);
		}
	}

	/**
	 * The `referenceContent` fetch path evaluates the same per-row branch once per reduced index; the rows it keeps
	 * must be exactly the rows satisfying the filter.
	 */
	@Nested
	@DisplayName("Filtered referenceContent")
	class FilteredReferenceContent {

		/**
		 * Fetches every live product with the reference filtered and returns, per product, the referenced parameter
		 * values of the rows that survived.
		 *
		 * @param evita  the evitaDB instance holding the shared dataset
		 * @param filter the filter of the fetched reference
		 * @return surviving referenced primary keys by owner, owners without surviving rows omitted
		 */
		@Nonnull
		private Map<Integer, Set<Integer>> fetchedRows(@Nonnull Evita evita, @Nonnull FilterConstraint filter) {
			return evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Map<Integer, Set<Integer>> result = new TreeMap<>();
					for (SealedEntity product : session.queryList(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								entityFetch(referenceContent(REF_PARAMETER_VALUES, filterBy(filter)))
							)
						),
						SealedEntity.class
					)) {
						final Set<Integer> surviving = product.getReferences(REF_PARAMETER_VALUES)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.collect(Collectors.toCollection(TreeSet::new));
						if (!surviving.isEmpty()) {
							result.put(product.getPrimaryKeyOrThrowException(), surviving);
						}
					}
					return result;
				}
			);
		}

		/**
		 * Returns, per live product, the referenced parameter values of the rows satisfying the predicate. A live
		 * fetch never returns a row pointing at an archived entity, filtered or not, so such rows are left out.
		 *
		 * @param rowPredicate the condition a row has to satisfy
		 * @return expected surviving referenced primary keys by owner, owners without surviving rows omitted
		 */
		@Nonnull
		private Map<Integer, Set<Integer>> expectedRows(@Nonnull Predicate<Row> rowPredicate) {
			return ROWS.stream()
				.filter(row -> row.referenceName().equals(REF_PARAMETER_VALUES))
				.filter(row -> scopeOfProduct(row.ownerPk()) == Scope.LIVE)
				.filter(row -> scopeOfParameterValue(row.parameterValuePk()) == Scope.LIVE)
				.filter(rowPredicate)
				.collect(
					Collectors.groupingBy(
						Row::ownerPk, TreeMap::new,
						Collectors.mapping(Row::parameterValuePk, Collectors.toCollection(TreeSet::new))
					)
				);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("a negated group keeps exactly the rows of other groups and the ungrouped ones")
		void shouldKeepRowsOutsideTheNegatedGroup(@Nonnull Evita evita) {
			assertEquals(
				expectedRows(row -> !row.groupIn(GROUP_WIDTH_PK)),
				fetchedRows(evita, not(groupCoded(CODE_WIDTH)))
			);
		}

		@Test
		@UseDataSet(MULTI_GROUP_DATA_SET)
		@DisplayName("value and group keep exactly the rows satisfying both")
		void shouldKeepRowsSatisfyingValueAndGroup(@Nonnull Evita evita) {
			final Set<Scope> live = EnumSet.of(Scope.LIVE);
			assertEquals(
				expectedRows(row -> row.groupIn(GROUP_HEIGHT_PK) && row.valueBetween(live, 40, 80)),
				fetchedRows(evita, and(valueBetween(40, 80), groupCoded(CODE_HEIGHT)))
			);
		}
	}
}
