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
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.histogramHaving;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression suite for issue #1644: inside one `referenceHaving`, a `groupHaving(...)` and an `entityHaving(...)`
 * must hold on the **same** reference row. An owner holding the group on one row and the value on another row
 * must not match. `histogramHaving(..., groupHaving(...))` is rewritten to exactly that shape by
 * `HistogramHavingTranslator#buildRewrite`, so a slider on one histogram group must not admit values of other
 * groups sharing the same reference and histogram.
 *
 * The fixture mirrors the schema of the report: a non-faceted reference indexed {@code FOR_FILTERING} with both
 * {@link ReferenceIndexedComponents#REFERENCED_ENTITY} and {@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY},
 * a bucketed histogram sourced from the referenced entity's attribute, an `assignedWhen` partition selector on a
 * group attribute and a `bucketedPartially` gate on another group attribute.
 *
 * The decisive owner is product 1: it holds a `width` row whose value is 120 and a `height` row whose value is 60.
 * A pooled reading answers "has a width row" (true) and "has a row valued 60" (true) separately and admits it.
 * The row-scoped reading asks for one row that is both, and there is none.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("histogramHaving / referenceHaving — group and value conditions bind one reference row")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
@Tag(HISTOGRAM)
public class HistogramHavingGroupRowBindingFunctionalTest implements EvitaTestSupport {

	/** Shared read-only fixture seeded once per run. */
	public static final String GROUP_ROW_BINDING_DATA_SET = "histogramHavingGroupRowBindingDataSet";

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER_TYPE = "parameterType";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";

	private static final String REF_PARAMETER_VALUES = "parameterValues";

	private static final String ATTR_CODE = "code";
	private static final String ATTR_HAS_RANGE_BASIC_UNIT_VALUE = "hasRangeBasicUnitValue";
	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";
	private static final String ATTR_BASIC_UNIT_VALUE = "basicUnitValue";

	private static final String HISTOGRAM_INTERVAL = "intervalParameterValues";
	private static final String INPUT_WIDGET_INTERVAL = "INTERVAL_INPUT";

	/** Group `width` (PK 1) and group `height` (PK 2). */
	private static final int GROUP_WIDTH_PK = 1;
	private static final String GROUP_WIDTH_CODE = "width";
	private static final int GROUP_HEIGHT_PK = 2;
	private static final String GROUP_HEIGHT_CODE = "height";

	/** Parameter values: a width of 120, a width of 60, and a height of 60. */
	private static final int PV_WIDTH_120_PK = 10;
	private static final int PV_WIDTH_60_PK = 11;
	private static final int PV_HEIGHT_60_PK = 20;

	/** The slider range of the report, which covers the value 60 and excludes 120. */
	private static final int SLIDER_FROM = 59;
	private static final int SLIDER_TO = 63;

	/**
	 * Products and the rows they hold:
	 *
	 * - 1: width = 120 **and** height = 60 — the reported owner; matches only under the pooled reading
	 * - 2: width = 60 — the only owner with a width row inside the slider range
	 * - 3: height = 60 — value in range, but on a height row
	 * - 4: width = 120 — width row, but value out of range
	 */
	private static final int PRODUCT_CROSS_ROW_PK = 1;
	private static final int PRODUCT_WIDTH_IN_RANGE_PK = 2;
	private static final int PRODUCT_HEIGHT_ONLY_PK = 3;
	private static final int PRODUCT_WIDTH_OUT_OF_RANGE_PK = 4;

	/** The correct answer for "a `width` row valued inside [59, 63]". */
	private static final Set<Integer> ROW_SCOPED_ANSWER = Set.of(PRODUCT_WIDTH_IN_RANGE_PK);

	/** What the pooled reading answers: any width row, plus any row valued inside [59, 63], on separate rows. */
	private static final Set<Integer> POOLED_ANSWER = Set.of(PRODUCT_CROSS_ROW_PK, PRODUCT_WIDTH_IN_RANGE_PK);

	/**
	 * Defines the schema of the report: group type `parameterType`, referenced type `parameterValue`, and a
	 * `product.parameterValues` reference indexed for filtering only, with both the referenced entity and the
	 * referenced group entity components, one bucketed histogram over the referenced entity's `basicUnitValue`
	 * whose partition selector and participation gate both read attributes of the group entity.
	 *
	 * @param session write session on the test catalog
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PARAMETER_TYPE)
			.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::filterable)
			.withAttribute(ATTR_HAS_RANGE_BASIC_UNIT_VALUE, Boolean.class, AttributeSchemaEditor::filterable)
			.withAttribute(ATTR_INPUT_WIDGET_TYPE, String.class, AttributeSchemaEditor::filterable)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withAttribute(
				ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
			)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
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
					)
			)
			.updateVia(session);
	}

	/**
	 * Seeds the two groups, the three parameter values and the four products described on the constants above.
	 *
	 * @param session write session on the test catalog
	 */
	private static void seedData(@Nonnull EvitaSessionContract session) {
		session.createNewEntity(ENTITY_PARAMETER_TYPE, GROUP_WIDTH_PK)
			.setAttribute(ATTR_CODE, GROUP_WIDTH_CODE)
			.setAttribute(ATTR_HAS_RANGE_BASIC_UNIT_VALUE, false)
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, INPUT_WIDGET_INTERVAL)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER_TYPE, GROUP_HEIGHT_PK)
			.setAttribute(ATTR_CODE, GROUP_HEIGHT_CODE)
			.setAttribute(ATTR_HAS_RANGE_BASIC_UNIT_VALUE, false)
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, INPUT_WIDGET_INTERVAL)
			.upsertVia(session);

		session.createNewEntity(ENTITY_PARAMETER_VALUE, PV_WIDTH_120_PK)
			.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("120"))
			.upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER_VALUE, PV_WIDTH_60_PK)
			.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("60"))
			.upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER_VALUE, PV_HEIGHT_60_PK)
			.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("60"))
			.upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_CROSS_ROW_PK)
			.setReference(
				REF_PARAMETER_VALUES, PV_WIDTH_120_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER_TYPE, GROUP_WIDTH_PK)
			)
			.setReference(
				REF_PARAMETER_VALUES, PV_HEIGHT_60_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER_TYPE, GROUP_HEIGHT_PK)
			)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_WIDTH_IN_RANGE_PK)
			.setReference(
				REF_PARAMETER_VALUES, PV_WIDTH_60_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER_TYPE, GROUP_WIDTH_PK)
			)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_HEIGHT_ONLY_PK)
			.setReference(
				REF_PARAMETER_VALUES, PV_HEIGHT_60_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER_TYPE, GROUP_HEIGHT_PK)
			)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_WIDTH_OUT_OF_RANGE_PK)
			.setReference(
				REF_PARAMETER_VALUES, PV_WIDTH_120_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER_TYPE, GROUP_WIDTH_PK)
			)
			.upsertVia(session);
	}

	/**
	 * Installs schema and data once and exposes the catalog via `@UseDataSet`.
	 *
	 * @param evita the shared evitaDB instance
	 * @return empty data carrier; tests read the catalog through the session
	 */
	@DataSet(GROUP_ROW_BINDING_DATA_SET)
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
	 * Runs the filter on the product collection and returns the matched primary keys in ascending order. Every
	 * alternative index plan and caching tree the engine can produce is verified against the primary one, so a
	 * plan-dependent answer fails here rather than passing on the plan that happened to be chosen.
	 *
	 * @param evita  the evitaDB instance holding the shared dataset
	 * @param filter the top-level filter constraint
	 * @return ordered set of matched product primary keys
	 */
	@Nonnull
	private static Set<Integer> matchingProducts(@Nonnull Evita evita, @Nonnull FilterConstraint filter) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(filter),
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
	 * The `groupHaving` selector of the report, resolving the `width` group by its code.
	 *
	 * @return group selector constraint
	 */
	@Nonnull
	private static FilterConstraint widthGroup() {
		return groupHaving(attributeEquals(ATTR_CODE, GROUP_WIDTH_CODE));
	}

	/**
	 * The value condition of the report as the histogram rewrite spells it: `entityHaving(attributeBetween(...))`.
	 *
	 * @return referenced entity value condition
	 */
	@Nonnull
	private static FilterConstraint valueInSliderRange() {
		return entityHaving(attributeBetween(ATTR_BASIC_UNIT_VALUE, SLIDER_FROM, SLIDER_TO));
	}

	/**
	 * Proves the fixture can tell the two readings apart before any assertion relies on it. Each conjunct alone
	 * admits product 1, so pooling them across rows admits it too, while no single row of product 1 satisfies both.
	 */
	@Nested
	@DisplayName("Fixture discriminates the pooled reading from the row-scoped one")
	class FixtureShape {

		@Test
		@UseDataSet(GROUP_ROW_BINDING_DATA_SET)
		@DisplayName("each conjunct alone admits the cross-row owner; their intersection is not the row-scoped answer")
		void shouldAdmitTheCrossRowOwnerUnderEachConjunctAlone(@Nonnull Evita evita) {
			final Set<Integer> byGroupAlone = matchingProducts(
				evita, referenceHaving(REF_PARAMETER_VALUES, widthGroup())
			);
			final Set<Integer> byValueAlone = matchingProducts(
				evita, referenceHaving(REF_PARAMETER_VALUES, valueInSliderRange())
			);

			assertEquals(
				Set.of(PRODUCT_CROSS_ROW_PK, PRODUCT_WIDTH_IN_RANGE_PK, PRODUCT_WIDTH_OUT_OF_RANGE_PK),
				byGroupAlone,
				"every owner holding any `width` row"
			);
			assertEquals(
				Set.of(PRODUCT_CROSS_ROW_PK, PRODUCT_WIDTH_IN_RANGE_PK, PRODUCT_HEIGHT_ONLY_PK),
				byValueAlone,
				"every owner holding any row valued inside the slider range"
			);

			final Set<Integer> pooled = new TreeSet<>(byGroupAlone);
			pooled.retainAll(byValueAlone);
			assertEquals(POOLED_ANSWER, pooled, "the pooled reading is the intersection of the two owner sets");
			assertNotEquals(
				ROW_SCOPED_ANSWER, pooled,
				"the fixture must separate the two readings, or every test below proves nothing"
			);
		}
	}

	/**
	 * The shape of the report as the user writes it: a `histogramHaving` with a `groupHaving` selector.
	 */
	@Nested
	@DisplayName("histogramHaving with a group selector")
	class HistogramHavingWithGroupSelector {

		@Test
		@UseDataSet(GROUP_ROW_BINDING_DATA_SET)
		@DisplayName("slider on the width group does not admit an owner whose in-range value sits on a height row")
		void shouldNotMatchOwnerWhoseGroupAndValueAreOnDifferentRows(@Nonnull Evita evita) {
			assertEquals(
				ROW_SCOPED_ANSWER,
				matchingProducts(
					evita,
					histogramHaving(
						REF_PARAMETER_VALUES, HISTOGRAM_INTERVAL, SLIDER_FROM, SLIDER_TO,
						groupHaving(attributeEquals(ATTR_CODE, GROUP_WIDTH_CODE))
					)
				),
				"product " + PRODUCT_CROSS_ROW_PK + " has no `width` row valued inside [" + SLIDER_FROM + ", " +
					SLIDER_TO + "]; the pooled reading would return " + POOLED_ANSWER
			);
		}

		@Test
		@UseDataSet(GROUP_ROW_BINDING_DATA_SET)
		@DisplayName("the same slider inside userFilter narrows the result identically")
		void shouldNotMatchOwnerWhoseGroupAndValueAreOnDifferentRowsInsideUserFilter(@Nonnull Evita evita) {
			assertEquals(
				ROW_SCOPED_ANSWER,
				matchingProducts(
					evita,
					userFilter(
						histogramHaving(
							REF_PARAMETER_VALUES, HISTOGRAM_INTERVAL, SLIDER_FROM, SLIDER_TO,
							groupHaving(attributeEquals(ATTR_CODE, GROUP_WIDTH_CODE))
						)
					)
				),
				"`userFilter` must not change which rows the group selector and the range bind to"
			);
		}

		@Test
		@UseDataSet(GROUP_ROW_BINDING_DATA_SET)
		@DisplayName("slider on the height group admits the owner through its height row")
		void shouldMatchOwnerThroughTheRowThatCarriesBothGroupAndValue(@Nonnull Evita evita) {
			assertEquals(
				Set.of(PRODUCT_CROSS_ROW_PK, PRODUCT_HEIGHT_ONLY_PK),
				matchingProducts(
					evita,
					histogramHaving(
						REF_PARAMETER_VALUES, HISTOGRAM_INTERVAL, SLIDER_FROM, SLIDER_TO,
						groupHaving(attributeEquals(ATTR_CODE, GROUP_HEIGHT_CODE))
					)
				),
				"a `height` row valued 60 satisfies both conditions on one row"
			);
		}
	}

	/**
	 * The rewrite the translator produces, written by hand: `referenceHaving(R, entityHaving(...), groupHaving(...))`.
	 */
	@Nested
	@DisplayName("referenceHaving with entityHaving and groupHaving siblings")
	class ReferenceHavingWithEntityAndGroupSiblings {

		@Test
		@UseDataSet(GROUP_ROW_BINDING_DATA_SET)
		@DisplayName("entityHaving followed by groupHaving binds both to one row")
		void shouldBindEntityHavingAndGroupHavingToTheSameRow(@Nonnull Evita evita) {
			assertEquals(
				ROW_SCOPED_ANSWER,
				matchingProducts(
					evita,
					referenceHaving(REF_PARAMETER_VALUES, valueInSliderRange(), widthGroup())
				),
				"the pooled reading would return " + POOLED_ANSWER
			);
		}

		@Test
		@UseDataSet(GROUP_ROW_BINDING_DATA_SET)
		@DisplayName("groupHaving followed by entityHaving binds both to one row")
		void shouldBindGroupHavingAndEntityHavingToTheSameRowRegardlessOfOrder(@Nonnull Evita evita) {
			assertEquals(
				ROW_SCOPED_ANSWER,
				matchingProducts(
					evita,
					referenceHaving(REF_PARAMETER_VALUES, widthGroup(), valueInSliderRange())
				),
				"the order of the siblings must not change the row they bind to"
			);
		}

		@Test
		@UseDataSet(GROUP_ROW_BINDING_DATA_SET)
		@DisplayName("an explicit and(...) around the siblings binds both to one row")
		void shouldBindEntityHavingAndGroupHavingToTheSameRowInsideExplicitAnd(@Nonnull Evita evita) {
			assertEquals(
				ROW_SCOPED_ANSWER,
				matchingProducts(
					evita,
					referenceHaving(REF_PARAMETER_VALUES, and(valueInSliderRange(), widthGroup()))
				),
				"an explicit conjunction is the same body as implicit siblings"
			);
		}
	}
}
