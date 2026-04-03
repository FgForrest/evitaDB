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

package io.evitadb.api;

import io.evitadb.api.GenerationalTestSupport.TestState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import lombok.extern.apachecommons.CommonsLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedEntityTypeIndex;
import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedGroupEntityIndex;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.apache.commons.io.FileUtils.byteCountToDisplaySize;
import static org.apache.commons.io.FileUtils.sizeOfDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational tests for the conditional bucketed histogram indexing feature (`bucketedPartially`
 * expressions). Each test targets a single condition expression path (entity attribute, reference
 * attribute, group entity attribute, referenced entity attribute) and verifies that the histogram
 * FilterIndex stays consistent with a simplified in-memory shadow state across many random mutations.
 *
 * All tests exercise both grouped and ungrouped references within the same schema — random mutations
 * include `setGroup` / `removeGroup` operations that move histogram data between
 * `ReducedGroupEntityIndex` and `ReferencedTypeEntityIndex`.
 *
 * Test 5 additionally exercises scope changes (LIVE ↔ ARCHIVED) to verify histogram data correctly
 * transitions between scope-specific indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@CommonsLog
@DisplayName("Conditional bucket histogram generational tests")
class EvitaConditionalBucketGenerationalTest implements EvitaTestSupport, TimeBoundedTestSupport, GenerationalTestSupport {
	private static final String DIR_TEST = "conditionalBucketGenerationalTest";
	private static final String DIR_TEST_EXPORT = "conditionalBucketGenerationalTest_export";

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";
	private static final String ENTITY_PARAMETER = "parameter";

	private static final String REF_BY_ENTITY_ATTR = "refByEntityAttr";
	private static final String REF_BY_REF_ATTR = "refByRefAttr";
	private static final String REF_BY_GROUP_ATTR = "refByGroupAttr";
	private static final String REF_BY_REF_ENTITY_ATTR = "refByRefEntityAttr";
	private static final String REF_BY_SCOPE = "refByScope";

	private static final String HISTOGRAM_ENTITY = "entityHistogram";
	private static final String HISTOGRAM_REF_ATTR = "refAttrHistogram";
	private static final String HISTOGRAM_GROUP = "groupHistogram";
	private static final String HISTOGRAM_STATUS = "statusHistogram";
	private static final String HISTOGRAM_SCOPE = "scopeHistogram";

	private static final String ATTR_IS_ACTIVE = "isActive";
	private static final String ATTR_BASIC_UNIT_VALUE = "basicUnitValue";
	private static final String ATTR_PRIORITY = "priority";
	private static final String ATTR_SOME_VALUE = "someValue";
	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";
	private static final String ATTR_STATUS = "status";

	private static final int MAX_PRODUCTS = 20;
	private static final int MAX_PARAM_VALUES = 5;
	private static final int MAX_GROUPS = 3;

	private static final BigDecimal[] VALUE_POOL = {
		new BigDecimal("1.5"), new BigDecimal("2"), new BigDecimal("3.75"),
		new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("0.5"),
		new BigDecimal("7.25"), new BigDecimal("15")
	};

	private final StringBuilder operationLog = new StringBuilder(4096);
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(DIR_TEST);
		cleanTestSubDirectory(DIR_TEST_EXPORT);
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() throws IOException {
		this.evita.close();
		cleanTestSubDirectory(DIR_TEST);
		cleanTestSubDirectory(DIR_TEST_EXPORT);
	}

	// --- Test 1: Entity Attribute Condition ---

	@ParameterizedTest(name = "Conditional histogram by entity attribute should survive generational test")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: bucketedPartially by entity attribute")
	void shouldSurviveGenerationalTestWithEntityAttributeExpression(@Nonnull GenerationalTestInput input) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
				.withAttribute(ATTR_BASIC_UNIT_VALUE, BigDecimal.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withAttribute(ATTR_IS_ACTIVE, Boolean.class, AttributeSchemaEditor::filterable)
				.withReferenceToEntity(
					REF_BY_ENTITY_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.bucketed(
							HISTOGRAM_ENTITY,
							ExpressionFactory.parse(
								"$reference.referencedEntity?.attributes['basicUnitValue']"
							)
						)
						.bucketedPartially(
							ExpressionFactory.parse(
								"($entity.attributes['isActive'] ?? false) == true"
							)
						)
				)
				.updateVia(session);

			for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
				session.createNewEntity(ENTITY_PARAMETER_VALUE, i)
					.setAttribute(ATTR_BASIC_UNIT_VALUE, VALUE_POOL[i % VALUE_POOL.length])
					.upsertVia(session);
			}
			session.goLiveAndClose();
		});

		// shadow state
		final Map<Integer, Boolean> productIsActive = new HashMap<>();
		final Map<Integer, Set<Integer>> productRefs = new HashMap<>();
		final Map<Integer, BigDecimal> paramValues = new HashMap<>();
		for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
			paramValues.put(i, normalize(VALUE_POOL[i % VALUE_POOL.length]));
		}
		final int[] nextProductPK = {1};

		final TestState finalState = runFor(
			input, 100, new TestState(0),
			(random, testState) -> {
				final int gen = testState.generation() + 1;
				this.operationLog.setLength(0);
				this.operationLog.append("=== Generation ").append(gen).append(" ===\n");

				this.evita.updateCatalog(TEST_CATALOG, session -> {
					final int ops = random.nextInt(10) + 5;
					for (int i = 0; i < ops; i++) {
						final int roll = random.nextInt(100);
						if (roll < 35) {
							toggleEntityAttribute(random, session, productIsActive, productRefs);
						} else if (roll < 55) {
							addRefEntityAttr(random, session, productIsActive, productRefs);
						} else if (roll < 70) {
							removeRefEntityAttr(random, session, productRefs);
						} else if (roll < 85) {
							createProductEntityAttr(
								random, session, productIsActive, productRefs, nextProductPK
							);
						} else {
							deleteProductEntityAttr(
								random, session, productIsActive, productRefs
							);
						}
					}
				});

				// compute expected (ungrouped only — no group support in this test)
				final Map<Serializable, Set<Integer>> expected = new HashMap<>();
				for (Entry<Integer, Set<Integer>> entry : productRefs.entrySet()) {
					final int productPK = entry.getKey();
					if (!Boolean.TRUE.equals(productIsActive.get(productPK))) {
						continue;
					}
					for (int paramPK : entry.getValue()) {
						final BigDecimal value = paramValues.get(paramPK);
						if (value == null) {
							continue;
						}
						expected.computeIfAbsent(value, k -> new HashSet<>()).add(productPK);
					}
				}

				this.operationLog.append("Shadow: productIsActive=").append(productIsActive).append('\n');
				this.operationLog.append("Shadow: productRefs=").append(productRefs).append('\n');
				this.operationLog.append("Expected: ").append(expected).append('\n');

				assertWithLog(
					() -> assertHistogramMatchesExpected(
						REF_BY_ENTITY_ATTR, HISTOGRAM_ENTITY, Map.of(), expected
					)
				);

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(
						() -> assertHistogramMatchesExpected(
							REF_BY_ENTITY_ATTR, HISTOGRAM_ENTITY, Map.of(), expected
						)
					);
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// --- Test 2: Reference Attribute Condition ---

	@ParameterizedTest(name = "Conditional histogram by reference attribute should survive generational test")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: bucketedPartially by reference attribute")
	void shouldSurviveGenerationalTestWithReferenceAttributeExpression(@Nonnull GenerationalTestInput input) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER_VALUE).updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					REF_BY_REF_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.withAttribute(ATTR_PRIORITY, Integer.class, a -> a.filterable().nullable())
						.withAttribute(ATTR_SOME_VALUE, BigDecimal.class, a -> a.filterable().nullable())
						.bucketed(
							HISTOGRAM_REF_ATTR,
							ExpressionFactory.parse("$reference.attributes['someValue']")
						)
						.bucketedPartially(
							ExpressionFactory.parse("($reference.attributes['priority'] ?? 0) > 0")
						)
				)
				.updateVia(session);

			for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
				session.createNewEntity(ENTITY_PARAMETER_VALUE, i).upsertVia(session);
			}
			session.goLiveAndClose();
		});

		final Map<Integer, Set<Integer>> productRefs = new HashMap<>();
		final Map<Long, Integer> refPriority = new HashMap<>();
		final Map<Long, BigDecimal> refSomeValue = new HashMap<>();
		final int[] nextProductPK = {1};

		final TestState finalState = runFor(
			input, 100, new TestState(0),
			(random, testState) -> {
				final int gen = testState.generation() + 1;
				this.operationLog.setLength(0);
				this.operationLog.append("=== Generation ").append(gen).append(" ===\n");

				this.evita.updateCatalog(TEST_CATALOG, session -> {
					final int ops = random.nextInt(10) + 5;
					for (int i = 0; i < ops; i++) {
						final int roll = random.nextInt(100);
						if (roll < 30) {
							changeRefPriority(random, session, productRefs, refPriority);
						} else if (roll < 50) {
							changeRefSomeValue(random, session, productRefs, refSomeValue);
						} else if (roll < 65) {
							addRefWithAttributes(
								random, session, productRefs, refPriority, refSomeValue
							);
						} else if (roll < 80) {
							removeRefForRefAttr(
								random, session, productRefs, refPriority, refSomeValue
							);
						} else if (roll < 90) {
							createProductForRefAttr(random, session, productRefs, nextProductPK);
						} else {
							deleteProductForRefAttr(
								random, session, productRefs, refPriority, refSomeValue
							);
						}
					}
				});

				// compute expected (ungrouped only)
				final Map<Serializable, Set<Integer>> expected = new HashMap<>();
				for (Entry<Integer, Set<Integer>> entry : productRefs.entrySet()) {
					final int productPK = entry.getKey();
					for (int paramPK : entry.getValue()) {
						final long refKey = GenerationalTestSupport.encodeRefKey(productPK, paramPK);
						final int priority = refPriority.getOrDefault(refKey, 0);
						final BigDecimal someValue = refSomeValue.get(refKey);
						if (priority > 0 && someValue != null) {
							expected.computeIfAbsent(someValue, k -> new HashSet<>()).add(productPK);
						}
					}
				}

				this.operationLog.append("Shadow: productRefs=").append(productRefs).append('\n');
				this.operationLog.append("Shadow: refPriority=").append(refPriority).append('\n');
				this.operationLog.append("Shadow: refSomeValue=").append(refSomeValue).append('\n');
				this.operationLog.append("Expected: ").append(expected).append('\n');

				assertWithLog(
					() -> assertHistogramMatchesExpected(
						REF_BY_REF_ATTR, HISTOGRAM_REF_ATTR, Map.of(), expected
					)
				);

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(
						() -> assertHistogramMatchesExpected(
							REF_BY_REF_ATTR, HISTOGRAM_REF_ATTR, Map.of(), expected
						)
					);
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// --- Test 3: Group Entity Attribute Condition (cross-entity fan-out) ---

	@ParameterizedTest(name = "Conditional histogram by group entity attribute should survive generational test")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: bucketedPartially by group entity attribute")
	void shouldSurviveGenerationalTestWithGroupEntityAttributeExpression(@Nonnull GenerationalTestInput input) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER)
				.withAttribute(ATTR_INPUT_WIDGET_TYPE, String.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);
			session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
				.withAttribute(ATTR_BASIC_UNIT_VALUE, BigDecimal.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					REF_BY_GROUP_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.indexedWithComponents(ReferenceIndexedComponents.values())
						.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
						.bucketed(
							HISTOGRAM_GROUP,
							ExpressionFactory.parse(
								"$reference.referencedEntity?.attributes['basicUnitValue']"
							)
						)
						.bucketedPartially(
							ExpressionFactory.parse(
								"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
							)
						)
				)
				.updateVia(session);

			for (int i = 1; i <= MAX_GROUPS; i++) {
				session.createNewEntity(ENTITY_PARAMETER, i)
					.setAttribute(ATTR_INPUT_WIDGET_TYPE, i == 1 ? "INTERVAL" : "CHECKBOX")
					.upsertVia(session);
			}
			for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
				session.createNewEntity(ENTITY_PARAMETER_VALUE, i)
					.setAttribute(ATTR_BASIC_UNIT_VALUE, VALUE_POOL[i % VALUE_POOL.length])
					.upsertVia(session);
			}
			session.goLiveAndClose();
		});

		final Map<Integer, String> groupWidgetType = new HashMap<>();
		groupWidgetType.put(1, "INTERVAL");
		for (int i = 2; i <= MAX_GROUPS; i++) {
			groupWidgetType.put(i, "CHECKBOX");
		}
		final Map<Integer, BigDecimal> paramValues = new HashMap<>();
		for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
			paramValues.put(i, normalize(VALUE_POOL[i % VALUE_POOL.length]));
		}
		// productPK -> (paramValuePK -> groupPK or null for ungrouped)
		final Map<Integer, Map<Integer, Integer>> productRefGroups = new HashMap<>();
		final Set<Integer> existingGroups = new HashSet<>(groupWidgetType.keySet());
		final Set<Integer> existingParamValues = new HashSet<>();
		for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
			existingParamValues.add(i);
		}
		final int[] nextProductPK = {1};

		final TestState finalState = runFor(
			input, 100, new TestState(0),
			(random, testState) -> {
				final int gen = testState.generation() + 1;
				this.operationLog.setLength(0);
				this.operationLog.append("=== Generation ").append(gen).append(" ===\n");

				this.evita.updateCatalog(TEST_CATALOG, session -> {
					final int ops = random.nextInt(10) + 5;
					for (int i = 0; i < ops; i++) {
						final int roll = random.nextInt(100);
						if (roll < 25) {
							changeGroupWidgetType(random, session, groupWidgetType, existingGroups);
						} else if (roll < 35) {
							changeParamValueForGroup(random, session, paramValues, existingParamValues);
						} else if (roll < 47) {
							addGroupedRefForGroupTest(
								random, session, productRefGroups, existingGroups,
								existingParamValues, groupWidgetType
							);
						} else if (roll < 55) {
							addUngroupedRefForGroupTest(
								random, session, productRefGroups, existingParamValues
							);
						} else if (roll < 65) {
							removeRefForGroupTest(random, session, productRefGroups, groupWidgetType);
						} else if (roll < 73) {
							assignGroupForGroupTest(
								random, session, productRefGroups, existingGroups, groupWidgetType
							);
						} else if (roll < 80) {
							removeGroupForGroupTest(random, session, productRefGroups, groupWidgetType);
						} else if (roll < 88) {
							createProductForGroupTest(random, session, productRefGroups, nextProductPK);
						} else if (roll < 93) {
							deleteProductForGroupTest(random, session, productRefGroups, groupWidgetType);
						} else {
							deleteGroupEntity(
								random, session, groupWidgetType, existingGroups, productRefGroups
							);
						}
					}
				});

				this.operationLog.append("Shadow: groupWidgetType=").append(groupWidgetType).append('\n');
				this.operationLog.append("Shadow: paramValues=").append(paramValues).append('\n');
				this.operationLog.append("Shadow: productRefGroups=").append(productRefGroups).append('\n');

				assertWithLog(
					() -> assertHistogramMatchesQueriedState(
						REF_BY_GROUP_ATTR, HISTOGRAM_GROUP, Scope.LIVE, true
					)
				);

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(
						() -> assertHistogramMatchesQueriedState(
							REF_BY_GROUP_ATTR, HISTOGRAM_GROUP, Scope.LIVE, true
						)
					);
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// --- Test 4: Referenced Entity Attribute Condition (cross-entity fan-out) ---

	@ParameterizedTest(name = "Conditional histogram by referenced entity attribute should survive generational test")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: bucketedPartially by referenced entity attribute")
	void shouldSurviveGenerationalTestWithReferencedEntityAttributeExpression(
		@Nonnull GenerationalTestInput input
	) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
				.withAttribute(ATTR_STATUS, String.class, whichIs -> whichIs.filterable().nullable())
				.withAttribute(ATTR_BASIC_UNIT_VALUE, BigDecimal.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					REF_BY_REF_ENTITY_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.bucketed(
							HISTOGRAM_STATUS,
							ExpressionFactory.parse(
								"$reference.referencedEntity?.attributes['basicUnitValue']"
							)
						)
						.bucketedPartially(
							ExpressionFactory.parse(
								"($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'"
							)
						)
				)
				.updateVia(session);

			for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
				session.createNewEntity(ENTITY_PARAMETER_VALUE, i)
					.setAttribute(ATTR_STATUS, i <= 2 ? "ACTIVE" : "INACTIVE")
					.setAttribute(ATTR_BASIC_UNIT_VALUE, VALUE_POOL[i % VALUE_POOL.length])
					.upsertVia(session);
			}
			session.goLiveAndClose();
		});

		final Map<Integer, String> paramStatus = new HashMap<>();
		final Map<Integer, BigDecimal> paramValues = new HashMap<>();
		for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
			paramStatus.put(i, i <= 2 ? "ACTIVE" : "INACTIVE");
			paramValues.put(i, normalize(VALUE_POOL[i % VALUE_POOL.length]));
		}
		final Map<Integer, Set<Integer>> productRefs = new HashMap<>();
		final Set<Integer> existingParamValues = new HashSet<>(paramStatus.keySet());
		final int[] nextProductPK = {1};

		final TestState finalState = runFor(
			input, 100, new TestState(0),
			(random, testState) -> {
				final int gen = testState.generation() + 1;
				this.operationLog.setLength(0);
				this.operationLog.append("=== Generation ").append(gen).append(" ===\n");

				this.evita.updateCatalog(TEST_CATALOG, session -> {
					final int ops = random.nextInt(10) + 5;
					for (int i = 0; i < ops; i++) {
						final int roll = random.nextInt(100);
						if (roll < 30) {
							changeParamStatus(random, session, paramStatus, existingParamValues);
						} else if (roll < 45) {
							changeParamValueForStatus(
								random, session, paramValues, existingParamValues
							);
						} else if (roll < 60) {
							addRefForStatusTest(
								random, session, productRefs, existingParamValues
							);
						} else if (roll < 70) {
							removeRefForStatusTest(random, session, productRefs);
						} else if (roll < 80) {
							createProductForStatusTest(random, session, productRefs, nextProductPK);
						} else if (roll < 90) {
							deleteProductForStatusTest(random, session, productRefs);
						} else {
							deleteParamValueEntity(
								random, session, paramStatus, paramValues,
								existingParamValues, productRefs
							);
						}
					}
				});

				this.operationLog.append("Shadow: paramStatus=").append(paramStatus).append('\n');
				this.operationLog.append("Shadow: paramValues=").append(paramValues).append('\n');
				this.operationLog.append("Shadow: productRefs=").append(productRefs).append('\n');

				assertWithLog(
					() -> assertHistogramMatchesQueriedState(
						REF_BY_REF_ENTITY_ATTR, HISTOGRAM_STATUS, Scope.LIVE, false
					)
				);

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(
						() -> assertHistogramMatchesQueriedState(
							REF_BY_REF_ENTITY_ATTR, HISTOGRAM_STATUS, Scope.LIVE, false
						)
					);
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// --- Test 5: Scope Changes (LIVE ↔ ARCHIVED) ---

	@ParameterizedTest(name = "Histogram indexing should survive scope changes in generational test")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: histogram with LIVE/ARCHIVED scope transitions")
	void shouldSurviveGenerationalTestWithScopeChanges(@Nonnull GenerationalTestInput input) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER)
				.withAttribute(
					ATTR_INPUT_WIDGET_TYPE, String.class,
					whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
				)
				.updateVia(session);
			session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
				.withAttribute(
					ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
					whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
				)
				.updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					REF_BY_SCOPE, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
						.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.values())
						.indexedWithComponentsInScope(Scope.ARCHIVED, ReferenceIndexedComponents.values())
						.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
						.bucketedInScope(
							Scope.LIVE, HISTOGRAM_SCOPE,
							ExpressionFactory.parse(
								"$reference.referencedEntity?.attributes['basicUnitValue']"
							)
						)
						.bucketedInScope(
							Scope.ARCHIVED, HISTOGRAM_SCOPE,
							ExpressionFactory.parse(
								"$reference.referencedEntity?.attributes['basicUnitValue']"
							)
						)
						.bucketedPartiallyInScope(
							Scope.LIVE,
							ExpressionFactory.parse(
								"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
							)
						)
						.bucketedPartiallyInScope(
							Scope.ARCHIVED,
							ExpressionFactory.parse(
								"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
							)
						)
				)
				.updateVia(session);

			for (int i = 1; i <= MAX_GROUPS; i++) {
				session.createNewEntity(ENTITY_PARAMETER, i)
					.setAttribute(ATTR_INPUT_WIDGET_TYPE, i == 1 ? "INTERVAL" : "CHECKBOX")
					.upsertVia(session);
			}
			for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
				session.createNewEntity(ENTITY_PARAMETER_VALUE, i)
					.setAttribute(ATTR_BASIC_UNIT_VALUE, VALUE_POOL[i % VALUE_POOL.length])
					.upsertVia(session);
			}
			session.goLiveAndClose();
		});

		final Map<Integer, Scope> productScope = new HashMap<>();
		final Map<Integer, String> groupWidgetType = new HashMap<>();
		groupWidgetType.put(1, "INTERVAL");
		for (int i = 2; i <= MAX_GROUPS; i++) {
			groupWidgetType.put(i, "CHECKBOX");
		}
		final Map<Integer, BigDecimal> paramValues = new HashMap<>();
		for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
			paramValues.put(i, normalize(VALUE_POOL[i % VALUE_POOL.length]));
		}
		final Map<Integer, Map<Integer, Integer>> productRefGroups = new HashMap<>();
		final Set<Integer> existingGroups = new HashSet<>(groupWidgetType.keySet());
		final Set<Integer> existingParamValues = new HashSet<>();
		for (int i = 1; i <= MAX_PARAM_VALUES; i++) {
			existingParamValues.add(i);
		}
		final int[] nextProductPK = {1};

		final TestState finalState = runFor(
			input, 100, new TestState(0),
			(random, testState) -> {
				final int gen = testState.generation() + 1;
				this.operationLog.setLength(0);
				this.operationLog.append("=== Generation ").append(gen).append(" ===\n");

				this.evita.updateCatalog(TEST_CATALOG, session -> {
					final int ops = random.nextInt(10) + 5;
					for (int i = 0; i < ops; i++) {
						final int roll = random.nextInt(100);
						if (roll < 15) {
							archiveProduct(random, session, productScope);
						} else if (roll < 30) {
							restoreProduct(random, session, productScope);
						} else if (roll < 40) {
							changeGroupWidgetType(random, session, groupWidgetType, existingGroups);
						} else if (roll < 50) {
							changeParamValueForGroup(
								random, session, paramValues, existingParamValues
							);
						} else if (roll < 60) {
							addGroupedRefForScopeTest(
								random, session, productRefGroups, existingGroups,
								existingParamValues, productScope
							);
						} else if (roll < 70) {
							removeRefForScopeTest(
								random, session, productRefGroups, productScope
							);
						} else if (roll < 75) {
							createProductForScopeTest(
								random, session, productRefGroups, productScope, nextProductPK
							);
						} else if (roll < 80) {
							deleteProductForScopeTest(
								random, session, productRefGroups, productScope
							);
						} else if (roll < 90) {
							assignGroupForScopeTest(
								random, session, productRefGroups, existingGroups, productScope
							);
						} else {
							removeGroupForScopeTest(
								random, session, productRefGroups, productScope
							);
						}
					}
				});

				this.operationLog.append("Shadow: productScope=").append(productScope).append('\n');
				this.operationLog.append("Shadow: groupWidgetType=").append(groupWidgetType).append('\n');
				this.operationLog.append("Shadow: productRefGroups=").append(productRefGroups).append('\n');

				assertWithLog(
					() -> assertHistogramMatchesQueriedState(
						REF_BY_SCOPE, HISTOGRAM_SCOPE, Scope.LIVE, true
					)
				);
				assertWithLog(
					() -> assertHistogramMatchesQueriedState(
						REF_BY_SCOPE, HISTOGRAM_SCOPE, Scope.ARCHIVED, true
					)
				);

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(
						() -> assertHistogramMatchesQueriedState(
							REF_BY_SCOPE, HISTOGRAM_SCOPE, Scope.LIVE, true
						)
					);
					assertWithLog(
						() -> assertHistogramMatchesQueriedState(
							REF_BY_SCOPE, HISTOGRAM_SCOPE, Scope.ARCHIVED, true
						)
					);
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// ==================== Shared Helpers ====================

	/**
	 * Appends a single operation line to the operation log.
	 */
	private void logOp(@Nonnull String message) {
		this.operationLog.append("  ").append(message).append('\n');
	}

	/**
	 * Runs an assertion and, on failure, wraps the error with the full operation log.
	 */
	private void assertWithLog(@Nonnull Runnable assertion) {
		try {
			assertion.run();
		} catch (AssertionError e) {
			throw new AssertionError(
				this.operationLog.toString() + "\n" + e.getMessage(), e
			);
		}
	}

	/**
	 * Restarts the Evita instance to verify histogram state survives persistence round-trips.
	 */
	private void restartEvita(int generation) {
		this.evita.close();
		System.out.println(
			"Survived " + generation + " generations, size on disk is "
				+ byteCountToDisplaySize(sizeOfDirectory(getTestDirectory().toFile()))
		);
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Logs the final state of the generational test.
	 */
	private void logFinished(@Nonnull TestState finalState) {
		System.out.println(
			"Finished " + finalState.generation() + " generations, size on disk is "
				+ byteCountToDisplaySize(sizeOfDirectory(getTestDirectory().toFile()))
		);
	}

	/**
	 * Builds the Evita configuration for this test suite.
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_TEST_EXPORT))
					.build()
			)
			.build();
	}

	/**
	 * Normalizes a BigDecimal value by stripping trailing zeros.
	 */
	@Nullable
	private static BigDecimal normalize(@Nullable BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros();
	}

	// ==================== Test 1 Helpers: Entity Attribute ====================

	/**
	 * Toggles the `isActive` attribute on a random existing product.
	 */
	private void toggleEntityAttribute(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Boolean> productIsActive,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		if (productIsActive.isEmpty()) {
			logOp("TOGGLE_ATTR (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productIsActive);
		final boolean oldValue = Boolean.TRUE.equals(productIsActive.get(productPK));
		final boolean newValue = !oldValue;
		session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_IS_ACTIVE, newValue)
			.upsertVia(session);
		productIsActive.put(productPK, newValue);
		logOp("TOGGLE_ATTR product=" + productPK + " isActive: " + oldValue + " -> " + newValue);
	}

	/**
	 * Adds a reference from a random product to a random paramValue for Test 1.
	 */
	private void addRefEntityAttr(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Boolean> productIsActive,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		if (productIsActive.isEmpty()) {
			logOp("ADD_REF (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productIsActive);
		final int paramPK = random.nextInt(MAX_PARAM_VALUES) + 1;
		final Set<Integer> refs = productRefs.computeIfAbsent(productPK, k -> new HashSet<>());
		if (refs.add(paramPK)) {
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(REF_BY_ENTITY_ATTR, paramPK)
				.upsertVia(session);
			logOp("ADD_REF product=" + productPK + " -> pv=" + paramPK);
		} else {
			logOp("ADD_REF product=" + productPK + " -> pv=" + paramPK + " (already exists)");
		}
	}

	/**
	 * Removes a random reference for Test 1.
	 */
	private void removeRefEntityAttr(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		final int[] pair = GenerationalTestSupport.pickRandomRef(random, productRefs);
		if (pair == null) {
			logOp("REMOVE_REF (no-op: no refs)");
			return;
		}
		session.getEntity(ENTITY_PRODUCT, pair[0], entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeReference(REF_BY_ENTITY_ATTR, pair[1])
			.upsertVia(session);
		final Set<Integer> refs = productRefs.get(pair[0]);
		refs.remove(pair[1]);
		if (refs.isEmpty()) {
			productRefs.remove(pair[0]);
		}
		logOp("REMOVE_REF product=" + pair[0] + " -> pv=" + pair[1]);
	}

	/**
	 * Changes `basicUnitValue` on a random paramValue entity.
	 */
	private void changeParamValue(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, BigDecimal> paramValues
	) {
		if (paramValues.isEmpty()) {
			logOp("CHANGE_PARAM_VALUE (no-op: no params)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomKey(random, paramValues);
		final BigDecimal oldValue = paramValues.get(paramPK);
		final BigDecimal newValue = normalize(
			GenerationalTestSupport.pickRandomValue(random, VALUE_POOL)
		);
		session.getEntity(ENTITY_PARAMETER_VALUE, paramPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_BASIC_UNIT_VALUE, newValue)
			.upsertVia(session);
		paramValues.put(paramPK, newValue);
		logOp("CHANGE_PARAM_VALUE pv=" + paramPK + " value: " + oldValue + " -> " + newValue);
	}

	/**
	 * Creates a new product with random `isActive` for Test 1.
	 */
	private void createProductEntityAttr(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Boolean> productIsActive,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull int[] nextProductPK
	) {
		if (productIsActive.size() >= MAX_PRODUCTS) {
			logOp("CREATE_PRODUCT (no-op: max reached)");
			return;
		}
		final int pk = nextProductPK[0]++;
		final boolean active = random.nextBoolean();
		session.createNewEntity(ENTITY_PRODUCT, pk)
			.setAttribute(ATTR_IS_ACTIVE, active)
			.upsertVia(session);
		productIsActive.put(pk, active);
		productRefs.put(pk, new HashSet<>());
		logOp("CREATE_PRODUCT pk=" + pk + " isActive=" + active);
	}

	/**
	 * Deletes a random product for Test 1.
	 */
	private void deleteProductEntityAttr(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Boolean> productIsActive,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		if (productIsActive.isEmpty()) {
			logOp("DELETE_PRODUCT (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productIsActive);
		session.deleteEntity(ENTITY_PRODUCT, productPK);
		productIsActive.remove(productPK);
		final Set<Integer> removedRefs = productRefs.remove(productPK);
		logOp("DELETE_PRODUCT pk=" + productPK + " (had refs: " + removedRefs + ")");
	}

	// ==================== Test 2 Helpers: Reference Attribute ====================

	/**
	 * Changes the `priority` attribute on a random existing reference.
	 */
	private void changeRefPriority(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Map<Long, Integer> refPriority
	) {
		final int[] pair = GenerationalTestSupport.pickRandomRef(random, productRefs);
		if (pair == null) {
			logOp("CHANGE_PRIORITY (no-op: no refs)");
			return;
		}
		final long refKey = GenerationalTestSupport.encodeRefKey(pair[0], pair[1]);
		final int oldPriority = refPriority.getOrDefault(refKey, 0);
		final int newPriority = random.nextInt(11) - 3; // range [-3, 7]
		session.getEntity(ENTITY_PRODUCT, pair[0], entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_BY_REF_ATTR, pair[1],
				whichIs -> whichIs.setAttribute(ATTR_PRIORITY, newPriority)
			)
			.upsertVia(session);
		refPriority.put(refKey, newPriority);
		logOp("CHANGE_PRIORITY product=" + pair[0] + " pv=" + pair[1]
			+ " priority: " + oldPriority + " -> " + newPriority);
	}

	/**
	 * Changes the `someValue` attribute on a random existing reference.
	 */
	private void changeRefSomeValue(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Map<Long, BigDecimal> refSomeValue
	) {
		final int[] pair = GenerationalTestSupport.pickRandomRef(random, productRefs);
		if (pair == null) {
			logOp("CHANGE_SOME_VALUE (no-op: no refs)");
			return;
		}
		final long refKey = GenerationalTestSupport.encodeRefKey(pair[0], pair[1]);
		final BigDecimal oldValue = refSomeValue.get(refKey);
		final BigDecimal newValue = normalize(
			GenerationalTestSupport.pickRandomValue(random, VALUE_POOL)
		);
		session.getEntity(ENTITY_PRODUCT, pair[0], entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_BY_REF_ATTR, pair[1],
				whichIs -> whichIs.setAttribute(ATTR_SOME_VALUE, newValue)
			)
			.upsertVia(session);
		if (newValue != null) {
			refSomeValue.put(refKey, newValue);
		} else {
			refSomeValue.remove(refKey);
		}
		logOp("CHANGE_SOME_VALUE product=" + pair[0] + " pv=" + pair[1]
			+ " someValue: " + oldValue + " -> " + newValue);
	}

	/**
	 * Adds a reference with random priority and someValue for Test 2.
	 */
	private void addRefWithAttributes(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Map<Long, Integer> refPriority,
		@Nonnull Map<Long, BigDecimal> refSomeValue
	) {
		if (productRefs.isEmpty()) {
			logOp("ADD_REF_ATTR (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefs);
		final int paramPK = random.nextInt(MAX_PARAM_VALUES) + 1;
		final Set<Integer> refs = productRefs.computeIfAbsent(productPK, k -> new HashSet<>());
		if (refs.add(paramPK)) {
			final long refKey = GenerationalTestSupport.encodeRefKey(productPK, paramPK);
			final int priority = random.nextInt(11) - 3;
			final BigDecimal someValue = normalize(
				GenerationalTestSupport.pickRandomValue(random, VALUE_POOL)
			);
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REF_BY_REF_ATTR, paramPK,
					whichIs -> {
						whichIs.setAttribute(ATTR_PRIORITY, priority);
						if (someValue != null) {
							whichIs.setAttribute(ATTR_SOME_VALUE, someValue);
						}
					}
				)
				.upsertVia(session);
			refPriority.put(refKey, priority);
			if (someValue != null) {
				refSomeValue.put(refKey, someValue);
			}
			logOp("ADD_REF_ATTR product=" + productPK + " -> pv=" + paramPK
				+ " priority=" + priority + " someValue=" + someValue);
		} else {
			logOp("ADD_REF_ATTR product=" + productPK + " -> pv=" + paramPK + " (already exists)");
		}
	}

	/**
	 * Removes a random reference for Test 2.
	 */
	private void removeRefForRefAttr(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Map<Long, Integer> refPriority,
		@Nonnull Map<Long, BigDecimal> refSomeValue
	) {
		final int[] pair = GenerationalTestSupport.pickRandomRef(random, productRefs);
		if (pair == null) {
			logOp("REMOVE_REF (no-op: no refs)");
			return;
		}
		session.getEntity(ENTITY_PRODUCT, pair[0], entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeReference(REF_BY_REF_ATTR, pair[1])
			.upsertVia(session);
		final long refKey = GenerationalTestSupport.encodeRefKey(pair[0], pair[1]);
		refPriority.remove(refKey);
		refSomeValue.remove(refKey);
		final Set<Integer> refs = productRefs.get(pair[0]);
		refs.remove(pair[1]);
		if (refs.isEmpty()) {
			productRefs.remove(pair[0]);
		}
		logOp("REMOVE_REF product=" + pair[0] + " -> pv=" + pair[1]);
	}

	/**
	 * Creates a new product for Test 2.
	 */
	private void createProductForRefAttr(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull int[] nextProductPK
	) {
		if (productRefs.size() >= MAX_PRODUCTS) {
			logOp("CREATE_PRODUCT (no-op: max reached)");
			return;
		}
		final int pk = nextProductPK[0]++;
		session.createNewEntity(ENTITY_PRODUCT, pk).upsertVia(session);
		productRefs.put(pk, new HashSet<>());
		logOp("CREATE_PRODUCT pk=" + pk);
	}

	/**
	 * Deletes a random product for Test 2.
	 */
	private void deleteProductForRefAttr(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Map<Long, Integer> refPriority,
		@Nonnull Map<Long, BigDecimal> refSomeValue
	) {
		if (productRefs.isEmpty()) {
			logOp("DELETE_PRODUCT (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefs);
		session.deleteEntity(ENTITY_PRODUCT, productPK);
		final Set<Integer> refs = productRefs.remove(productPK);
		if (refs != null) {
			for (int paramPK : refs) {
				final long refKey = GenerationalTestSupport.encodeRefKey(productPK, paramPK);
				refPriority.remove(refKey);
				refSomeValue.remove(refKey);
			}
		}
		logOp("DELETE_PRODUCT pk=" + productPK + " (had refs: " + refs + ")");
	}

	// ==================== Test 3 Helpers: Group Entity Attribute ====================

	/**
	 * Changes the `inputWidgetType` on a random group entity (triggers cross-entity fan-out).
	 */
	private void changeGroupWidgetType(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, String> groupWidgetType,
		@Nonnull Set<Integer> existingGroups
	) {
		if (existingGroups.isEmpty()) {
			logOp("CHANGE_GROUP_TYPE (no-op: no groups)");
			return;
		}
		final int groupPK = GenerationalTestSupport.pickRandomFromSet(random, existingGroups);
		final String oldType = groupWidgetType.get(groupPK);
		final String newType = random.nextBoolean() ? "INTERVAL" : "CHECKBOX";
		session.getEntity(ENTITY_PARAMETER, groupPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, newType)
			.upsertVia(session);
		groupWidgetType.put(groupPK, newType);
		logOp("CHANGE_GROUP_TYPE group=" + groupPK + " widgetType: " + oldType + " -> " + newType
			+ ("INTERVAL".equals(newType) ? " [NOW BUCKETED]" : " [NOW HIDDEN]"));
	}

	/**
	 * Changes `basicUnitValue` on a random paramValue (cross-entity value fan-out).
	 */
	private void changeParamValueForGroup(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, BigDecimal> paramValues,
		@Nonnull Set<Integer> existingParamValues
	) {
		if (existingParamValues.isEmpty()) {
			logOp("CHANGE_PARAM_VALUE (no-op: no params)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParamValues);
		final BigDecimal oldValue = paramValues.get(paramPK);
		final BigDecimal newValue = normalize(
			GenerationalTestSupport.pickRandomValue(random, VALUE_POOL)
		);
		session.getEntity(ENTITY_PARAMETER_VALUE, paramPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_BASIC_UNIT_VALUE, newValue)
			.upsertVia(session);
		paramValues.put(paramPK, newValue);
		logOp("CHANGE_PARAM_VALUE pv=" + paramPK + " value: " + oldValue + " -> " + newValue);
	}

	/**
	 * Adds a grouped reference for Test 3.
	 */
	private void addGroupedRefForGroupTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Set<Integer> existingGroups,
		@Nonnull Set<Integer> existingParamValues,
		@Nonnull Map<Integer, String> groupWidgetType
	) {
		if (productRefGroups.isEmpty() || existingGroups.isEmpty() || existingParamValues.isEmpty()) {
			logOp("ADD_GROUPED_REF (no-op: no products, groups, or params)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParamValues);
		final int groupPK = GenerationalTestSupport.pickRandomFromSet(random, existingGroups);
		final Map<Integer, Integer> refs = productRefGroups.computeIfAbsent(
			productPK, k -> new HashMap<>()
		);
		if (!refs.containsKey(paramPK)) {
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REF_BY_GROUP_ATTR, paramPK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, groupPK)
				)
				.upsertVia(session);
			refs.put(paramPK, groupPK);
			logOp("ADD_GROUPED_REF product=" + productPK + " -> pv=" + paramPK
				+ " group=" + groupPK + " [" + groupWidgetType.get(groupPK) + "]");
		} else {
			logOp("ADD_GROUPED_REF product=" + productPK + " -> pv=" + paramPK + " (already exists)");
		}
	}

	/**
	 * Adds an ungrouped reference for Test 3.
	 */
	private void addUngroupedRefForGroupTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Set<Integer> existingParamValues
	) {
		if (productRefGroups.isEmpty() || existingParamValues.isEmpty()) {
			logOp("ADD_UNGROUPED_REF (no-op: no products or params)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParamValues);
		final Map<Integer, Integer> refs = productRefGroups.computeIfAbsent(
			productPK, k -> new HashMap<>()
		);
		if (!refs.containsKey(paramPK)) {
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(REF_BY_GROUP_ATTR, paramPK)
				.upsertVia(session);
			refs.put(paramPK, null); // null = ungrouped
			logOp("ADD_UNGROUPED_REF product=" + productPK + " -> pv=" + paramPK);
		} else {
			logOp("ADD_UNGROUPED_REF product=" + productPK + " -> pv=" + paramPK + " (already exists)");
		}
	}

	/**
	 * Removes a random reference for Test 3.
	 */
	private void removeRefForGroupTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, String> groupWidgetType
	) {
		if (productRefGroups.isEmpty()) {
			logOp("REMOVE_REF (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		final Map<Integer, Integer> refs = productRefGroups.get(productPK);
		if (refs == null || refs.isEmpty()) {
			logOp("REMOVE_REF (no-op: product=" + productPK + " has no refs)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomKey(random, refs);
		session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeReference(REF_BY_GROUP_ATTR, paramPK)
			.upsertVia(session);
		final Integer groupPK = refs.remove(paramPK);
		if (refs.isEmpty()) {
			productRefGroups.remove(productPK);
		}
		logOp("REMOVE_REF product=" + productPK + " -> pv=" + paramPK
			+ " (was group=" + groupPK + ")");
	}

	/**
	 * Assigns a group to an ungrouped reference for Test 3.
	 */
	private void assignGroupForGroupTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Set<Integer> existingGroups,
		@Nonnull Map<Integer, String> groupWidgetType
	) {
		if (productRefGroups.isEmpty() || existingGroups.isEmpty()) {
			logOp("ASSIGN_GROUP (no-op)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		final Map<Integer, Integer> refs = productRefGroups.get(productPK);
		if (refs == null || refs.isEmpty()) {
			logOp("ASSIGN_GROUP (no-op: no refs for product=" + productPK + ")");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomKey(random, refs);
		if (refs.get(paramPK) != null) {
			logOp("ASSIGN_GROUP product=" + productPK + " pv=" + paramPK + " (already grouped)");
			return;
		}
		final int groupPK = GenerationalTestSupport.pickRandomFromSet(random, existingGroups);
		session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_BY_GROUP_ATTR, paramPK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, groupPK)
			)
			.upsertVia(session);
		refs.put(paramPK, groupPK);
		logOp("ASSIGN_GROUP product=" + productPK + " pv=" + paramPK
			+ " -> group=" + groupPK + " [" + groupWidgetType.get(groupPK) + "]");
	}

	/**
	 * Removes a group from a grouped reference for Test 3.
	 */
	private void removeGroupForGroupTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, String> groupWidgetType
	) {
		if (productRefGroups.isEmpty()) {
			logOp("REMOVE_GROUP (no-op)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		final Map<Integer, Integer> refs = productRefGroups.get(productPK);
		if (refs == null || refs.isEmpty()) {
			logOp("REMOVE_GROUP (no-op: no refs)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomKey(random, refs);
		final Integer oldGroupPK = refs.get(paramPK);
		if (oldGroupPK == null) {
			logOp("REMOVE_GROUP product=" + productPK + " pv=" + paramPK + " (already ungrouped)");
			return;
		}
		session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_BY_GROUP_ATTR, paramPK,
				ReferenceEditor.ReferenceBuilder::removeGroup
			)
			.upsertVia(session);
		refs.put(paramPK, null);
		logOp("REMOVE_GROUP product=" + productPK + " pv=" + paramPK
			+ " (was group=" + oldGroupPK + " [" + groupWidgetType.get(oldGroupPK) + "])");
	}

	/**
	 * Creates a new product for Test 3.
	 */
	private void createProductForGroupTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull int[] nextProductPK
	) {
		if (productRefGroups.size() >= MAX_PRODUCTS) {
			logOp("CREATE_PRODUCT (no-op: max reached)");
			return;
		}
		final int pk = nextProductPK[0]++;
		session.createNewEntity(ENTITY_PRODUCT, pk).upsertVia(session);
		productRefGroups.put(pk, new HashMap<>());
		logOp("CREATE_PRODUCT pk=" + pk);
	}

	/**
	 * Deletes a random product for Test 3.
	 */
	private void deleteProductForGroupTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, String> groupWidgetType
	) {
		if (productRefGroups.isEmpty()) {
			logOp("DELETE_PRODUCT (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		session.deleteEntity(ENTITY_PRODUCT, productPK);
		productRefGroups.remove(productPK);
		logOp("DELETE_PRODUCT pk=" + productPK);
	}

	/**
	 * Deletes a random group entity after removing all references pointing to it.
	 */
	private void deleteGroupEntity(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, String> groupWidgetType,
		@Nonnull Set<Integer> existingGroups,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups
	) {
		if (existingGroups.isEmpty()) {
			logOp("DELETE_GROUP (no-op: no groups)");
			return;
		}
		final int groupPK = GenerationalTestSupport.pickRandomFromSet(random, existingGroups);
		// remove all refs pointing to this group
		for (Entry<Integer, Map<Integer, Integer>> productEntry : productRefGroups.entrySet()) {
			final int productPK = productEntry.getKey();
			final Map<Integer, Integer> refs = productEntry.getValue();
			final Set<Integer> toRemove = new HashSet<>();
			for (Entry<Integer, Integer> refEntry : refs.entrySet()) {
				if (Integer.valueOf(groupPK).equals(refEntry.getValue())) {
					toRemove.add(refEntry.getKey());
				}
			}
			if (!toRemove.isEmpty()) {
				var builder = session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
					.orElseThrow()
					.openForWrite();
				for (int paramPK : toRemove) {
					builder = builder.removeReference(REF_BY_GROUP_ATTR, paramPK);
					refs.remove(paramPK);
				}
				builder.upsertVia(session);
			}
		}
		productRefGroups.entrySet().removeIf(entry -> entry.getValue().isEmpty());
		session.deleteEntity(ENTITY_PARAMETER, groupPK);
		groupWidgetType.remove(groupPK);
		existingGroups.remove(groupPK);
		logOp("DELETE_GROUP group=" + groupPK);
	}

	// ==================== Test 4 Helpers: Referenced Entity Attribute ====================

	/**
	 * Changes the `status` attribute on a random paramValue (cross-entity condition fan-out).
	 */
	private void changeParamStatus(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, String> paramStatus,
		@Nonnull Set<Integer> existingParamValues
	) {
		if (existingParamValues.isEmpty()) {
			logOp("CHANGE_PARAM_STATUS (no-op: no params)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParamValues);
		final String oldStatus = paramStatus.get(paramPK);
		final String newStatus = random.nextBoolean() ? "ACTIVE" : "INACTIVE";
		session.getEntity(ENTITY_PARAMETER_VALUE, paramPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_STATUS, newStatus)
			.upsertVia(session);
		paramStatus.put(paramPK, newStatus);
		logOp("CHANGE_PARAM_STATUS pv=" + paramPK + " status: " + oldStatus + " -> " + newStatus);
	}

	/**
	 * Changes `basicUnitValue` on a random paramValue for Test 4.
	 */
	private void changeParamValueForStatus(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, BigDecimal> paramValues,
		@Nonnull Set<Integer> existingParamValues
	) {
		changeParamValueForGroup(random, session, paramValues, existingParamValues);
	}

	/**
	 * Adds a reference (randomly grouped/ungrouped) for Test 4.
	 */
	private void addRefForStatusTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Set<Integer> existingParamValues
	) {
		if (productRefs.isEmpty() || existingParamValues.isEmpty()) {
			logOp("ADD_REF (no-op: no products or params)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefs);
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParamValues);
		final Set<Integer> refs = productRefs.computeIfAbsent(productPK, k -> new HashSet<>());
		if (refs.add(paramPK)) {
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(REF_BY_REF_ENTITY_ATTR, paramPK)
				.upsertVia(session);
			logOp("ADD_REF product=" + productPK + " -> pv=" + paramPK);
		} else {
			logOp("ADD_REF product=" + productPK + " -> pv=" + paramPK + " (already exists)");
		}
	}

	/**
	 * Removes a random reference for Test 4.
	 */
	private void removeRefForStatusTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		final int[] pair = GenerationalTestSupport.pickRandomRef(random, productRefs);
		if (pair == null) {
			logOp("REMOVE_REF (no-op: no refs)");
			return;
		}
		session.getEntity(ENTITY_PRODUCT, pair[0], entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeReference(REF_BY_REF_ENTITY_ATTR, pair[1])
			.upsertVia(session);
		final Set<Integer> refs = productRefs.get(pair[0]);
		refs.remove(pair[1]);
		if (refs.isEmpty()) {
			productRefs.remove(pair[0]);
		}
		logOp("REMOVE_REF product=" + pair[0] + " -> pv=" + pair[1]);
	}

	/**
	 * Creates a new product for Test 4.
	 */
	private void createProductForStatusTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull int[] nextProductPK
	) {
		if (productRefs.size() >= MAX_PRODUCTS) {
			logOp("CREATE_PRODUCT (no-op: max reached)");
			return;
		}
		final int pk = nextProductPK[0]++;
		session.createNewEntity(ENTITY_PRODUCT, pk).upsertVia(session);
		productRefs.put(pk, new HashSet<>());
		logOp("CREATE_PRODUCT pk=" + pk);
	}

	/**
	 * Deletes a random product for Test 4.
	 */
	private void deleteProductForStatusTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		if (productRefs.isEmpty()) {
			logOp("DELETE_PRODUCT (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefs);
		session.deleteEntity(ENTITY_PRODUCT, productPK);
		productRefs.remove(productPK);
		logOp("DELETE_PRODUCT pk=" + productPK);
	}

	/**
	 * Deletes a random paramValue entity after removing all references pointing to it.
	 */
	private void deleteParamValueEntity(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, String> paramStatus,
		@Nonnull Map<Integer, BigDecimal> paramValues,
		@Nonnull Set<Integer> existingParamValues,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		if (existingParamValues.isEmpty()) {
			logOp("DELETE_PARAM (no-op: no params)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParamValues);
		// remove all references pointing to this paramValue
		for (Entry<Integer, Set<Integer>> entry : productRefs.entrySet()) {
			final int productPK = entry.getKey();
			if (entry.getValue().remove(paramPK)) {
				session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(REF_BY_REF_ENTITY_ATTR, paramPK)
					.upsertVia(session);
			}
		}
		productRefs.entrySet().removeIf(entry -> entry.getValue().isEmpty());
		session.deleteEntity(ENTITY_PARAMETER_VALUE, paramPK);
		paramStatus.remove(paramPK);
		paramValues.remove(paramPK);
		existingParamValues.remove(paramPK);
		logOp("DELETE_PARAM pv=" + paramPK);
	}

	// ==================== Test 5 Helpers: Scope Changes ====================

	/**
	 * Archives a random LIVE product.
	 */
	private void archiveProduct(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Scope> productScope
	) {
		if (productScope.isEmpty()) {
			logOp("ARCHIVE (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productScope);
		if (productScope.get(productPK) == Scope.ARCHIVED) {
			logOp("ARCHIVE product=" + productPK + " (already ARCHIVED)");
			return;
		}
		session.archiveEntity(ENTITY_PRODUCT, productPK);
		productScope.put(productPK, Scope.ARCHIVED);
		logOp("ARCHIVE product=" + productPK + " LIVE -> ARCHIVED");
	}

	/**
	 * Restores a random ARCHIVED product.
	 */
	private void restoreProduct(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Scope> productScope
	) {
		if (productScope.isEmpty()) {
			logOp("RESTORE (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productScope);
		if (productScope.get(productPK) == Scope.LIVE) {
			logOp("RESTORE product=" + productPK + " (already LIVE)");
			return;
		}
		session.restoreEntity(ENTITY_PRODUCT, productPK);
		productScope.put(productPK, Scope.LIVE);
		logOp("RESTORE product=" + productPK + " ARCHIVED -> LIVE");
	}

	/**
	 * Adds a grouped reference for Test 5.
	 */
	private void addGroupedRefForScopeTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Set<Integer> existingGroups,
		@Nonnull Set<Integer> existingParamValues,
		@Nonnull Map<Integer, Scope> productScope
	) {
		if (productRefGroups.isEmpty() || existingGroups.isEmpty() || existingParamValues.isEmpty()) {
			logOp("ADD_REF (no-op)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		if (productScope.getOrDefault(productPK, Scope.LIVE) == Scope.ARCHIVED) {
			logOp("ADD_REF (no-op: product=" + productPK + " is ARCHIVED)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParamValues);
		final int groupPK = GenerationalTestSupport.pickRandomFromSet(random, existingGroups);
		final Map<Integer, Integer> refs = productRefGroups.computeIfAbsent(
			productPK, k -> new HashMap<>()
		);
		if (!refs.containsKey(paramPK)) {
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REF_BY_SCOPE, paramPK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, groupPK)
				)
				.upsertVia(session);
			refs.put(paramPK, groupPK);
			logOp("ADD_REF product=" + productPK + " -> pv=" + paramPK + " group=" + groupPK
				+ " scope=" + productScope.getOrDefault(productPK, Scope.LIVE));
		} else {
			logOp("ADD_REF product=" + productPK + " -> pv=" + paramPK + " (already exists)");
		}
	}

	/**
	 * Removes a random reference for Test 5.
	 */
	private void removeRefForScopeTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, Scope> productScope
	) {
		if (productRefGroups.isEmpty()) {
			logOp("REMOVE_REF (no-op)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		if (productScope.getOrDefault(productPK, Scope.LIVE) == Scope.ARCHIVED) {
			logOp("REMOVE_REF (no-op: product=" + productPK + " is ARCHIVED)");
			return;
		}
		final Map<Integer, Integer> refs = productRefGroups.get(productPK);
		if (refs == null || refs.isEmpty()) {
			logOp("REMOVE_REF (no-op: no refs for product=" + productPK + ")");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomKey(random, refs);
		session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeReference(REF_BY_SCOPE, paramPK)
			.upsertVia(session);
		refs.remove(paramPK);
		if (refs.isEmpty()) {
			productRefGroups.remove(productPK);
		}
		logOp("REMOVE_REF product=" + productPK + " -> pv=" + paramPK);
	}

	/**
	 * Creates a new product in LIVE scope for Test 5.
	 */
	private void createProductForScopeTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, Scope> productScope,
		@Nonnull int[] nextProductPK
	) {
		if (productRefGroups.size() >= MAX_PRODUCTS) {
			logOp("CREATE_PRODUCT (no-op: max reached)");
			return;
		}
		final int pk = nextProductPK[0]++;
		session.createNewEntity(ENTITY_PRODUCT, pk).upsertVia(session);
		productRefGroups.put(pk, new HashMap<>());
		productScope.put(pk, Scope.LIVE);
		logOp("CREATE_PRODUCT pk=" + pk + " scope=LIVE");
	}

	/**
	 * Deletes a random product for Test 5.
	 */
	private void deleteProductForScopeTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, Scope> productScope
	) {
		if (productRefGroups.isEmpty()) {
			logOp("DELETE_PRODUCT (no-op)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		session.deleteEntity(ENTITY_PRODUCT, productPK);
		productRefGroups.remove(productPK);
		productScope.remove(productPK);
		logOp("DELETE_PRODUCT pk=" + productPK);
	}

	/**
	 * Assigns a group to an ungrouped reference for Test 5.
	 */
	private void assignGroupForScopeTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Set<Integer> existingGroups,
		@Nonnull Map<Integer, Scope> productScope
	) {
		if (productRefGroups.isEmpty() || existingGroups.isEmpty()) {
			logOp("ASSIGN_GROUP (no-op)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		if (productScope.getOrDefault(productPK, Scope.LIVE) == Scope.ARCHIVED) {
			logOp("ASSIGN_GROUP (no-op: product=" + productPK + " is ARCHIVED)");
			return;
		}
		final Map<Integer, Integer> refs = productRefGroups.get(productPK);
		if (refs == null || refs.isEmpty()) {
			logOp("ASSIGN_GROUP (no-op: no refs)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomKey(random, refs);
		if (refs.get(paramPK) != null) {
			logOp("ASSIGN_GROUP product=" + productPK + " pv=" + paramPK + " (already grouped)");
			return;
		}
		final int groupPK = GenerationalTestSupport.pickRandomFromSet(random, existingGroups);
		session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_BY_SCOPE, paramPK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, groupPK)
			)
			.upsertVia(session);
		refs.put(paramPK, groupPK);
		logOp("ASSIGN_GROUP product=" + productPK + " pv=" + paramPK + " -> group=" + groupPK);
	}

	/**
	 * Removes a group from a grouped reference for Test 5.
	 */
	private void removeGroupForScopeTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, Scope> productScope
	) {
		if (productRefGroups.isEmpty()) {
			logOp("REMOVE_GROUP (no-op)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		if (productScope.getOrDefault(productPK, Scope.LIVE) == Scope.ARCHIVED) {
			logOp("REMOVE_GROUP (no-op: product=" + productPK + " is ARCHIVED)");
			return;
		}
		final Map<Integer, Integer> refs = productRefGroups.get(productPK);
		if (refs == null || refs.isEmpty()) {
			logOp("REMOVE_GROUP (no-op: no refs)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomKey(random, refs);
		final Integer oldGroupPK = refs.get(paramPK);
		if (oldGroupPK == null) {
			logOp("REMOVE_GROUP product=" + productPK + " pv=" + paramPK + " (already ungrouped)");
			return;
		}
		session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_BY_SCOPE, paramPK,
				ReferenceEditor.ReferenceBuilder::removeGroup
			)
			.upsertVia(session);
		refs.put(paramPK, null);
		logOp("REMOVE_GROUP product=" + productPK + " pv=" + paramPK
			+ " (was group=" + oldGroupPK + ")");
	}

	// ==================== Verification ====================

	/**
	 * Shadow-based verification: asserts histogram FilterIndex matches expected state for both
	 * grouped (`ReducedGroupEntityIndex`) and ungrouped (`ReferencedTypeEntityIndex`) paths.
	 */
	private void assertHistogramMatchesExpected(
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Map<Integer, Map<Serializable, Set<Integer>>> expectedGrouped,
		@Nonnull Map<Serializable, Set<Integer>> expectedUngrouped
	) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final EntityCollectionContract collection =
				this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow()
					.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow();

			// verify grouped indexes
			final Set<Integer> allGroupPKs = new HashSet<>(expectedGrouped.keySet());
			// also check groups 1..MAX_GROUPS for spurious data
			for (int g = 1; g <= MAX_GROUPS; g++) {
				allGroupPKs.add(g);
			}
			for (int groupPK : allGroupPKs) {
				final Map<Serializable, Set<Integer>> expected =
					expectedGrouped.getOrDefault(groupPK, Map.of());
				final EntityIndex entityIndex = getReferencedGroupEntityIndex(
					collection, Scope.LIVE, referenceName, groupPK
				);
				final Map<Serializable, Set<Integer>> actual;
				if (entityIndex instanceof ReducedGroupEntityIndex groupIndex) {
					actual = extractHistogramState(
						groupIndex.getHistogramFilterIndex(histogramName, null)
					);
				} else {
					actual = Map.of();
				}
				assertEquals(
					expected, actual,
					"Histogram mismatch for grouped ref '" + referenceName
						+ "' group=" + groupPK + " histogram='" + histogramName + "'"
				);
			}

			// verify ungrouped index
			final EntityIndex typeIndex = getReferencedEntityTypeIndex(
				collection, Scope.LIVE, referenceName
			);
			final Map<Serializable, Set<Integer>> actualUngrouped;
			if (typeIndex instanceof ReferencedTypeEntityIndex rti) {
				actualUngrouped = extractHistogramState(
					rti.getHistogramFilterIndex(histogramName, null)
				);
			} else {
				actualUngrouped = Map.of();
			}
			assertEquals(
				expectedUngrouped, actualUngrouped,
				"Histogram mismatch for ungrouped ref '" + referenceName
					+ "' histogram='" + histogramName + "'"
			);
			return null;
		});
	}

	/**
	 * Query-based verification: queries all products with reference content, evaluates condition
	 * and value from queried data, compares with actual histogram indexes.
	 *
	 * @param referenceName reference schema name
	 * @param histogramName histogram definition name
	 * @param scope         scope to verify (LIVE or ARCHIVED)
	 * @param grouped       true if condition uses group entity attribute
	 */
	private void assertHistogramMatchesQueriedState(
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Scope scope,
		boolean grouped
	) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			// build expected from queried entity data
			final Map<Integer, Map<Serializable, Set<Integer>>> expectedPerGroup = new HashMap<>();
			final Map<Serializable, Set<Integer>> expectedUngrouped = new HashMap<>();

			int pageNumber = 1;
			EvitaResponse<SealedEntity> response;
			do {
				response = session.querySealedEntity(
					query(
						collection(ENTITY_PRODUCT),
						scope == Scope.ARCHIVED ? filterBy(scope(Scope.ARCHIVED)) : null,
						require(
							entityFetch(
								referenceContent(
									referenceName,
									entityFetch(attributeContentAll()),
									entityGroupFetch(attributeContentAll())
								)
							),
							page(pageNumber++, 100)
						)
					)
				);
				for (SealedEntity product : response.getRecordPage().getData()) {
					for (ReferenceContract ref : product.getReferences(referenceName)) {
						final boolean conditionMet;
						if (grouped) {
							conditionMet = ref.getGroupEntity()
								.map(ge -> "INTERVAL".equals(
									ge.getAttribute(ATTR_INPUT_WIDGET_TYPE, String.class))
								)
								.orElse(false);
						} else {
							conditionMet = ref.getReferencedEntity()
								.map(re -> "ACTIVE".equals(
									re.getAttribute(ATTR_STATUS, String.class))
								)
								.orElse(false);
						}
						if (!conditionMet) {
							continue;
						}
						final BigDecimal value = ref.getReferencedEntity()
							.map(re -> re.getAttribute(ATTR_BASIC_UNIT_VALUE, BigDecimal.class))
							.orElse(null);
						if (value == null) {
							continue;
						}
						final BigDecimal normalizedValue = normalize(value);
						final int productPK = product.getPrimaryKey();
						if (ref.getGroup().isPresent()) {
							final int groupPK = ref.getGroup()
								.map(GroupEntityReference::getPrimaryKey)
								.orElse(0);
							expectedPerGroup
								.computeIfAbsent(groupPK, k -> new HashMap<>())
								.computeIfAbsent(normalizedValue, k -> new HashSet<>())
								.add(productPK);
						} else {
							expectedUngrouped
								.computeIfAbsent(normalizedValue, k -> new HashSet<>())
								.add(productPK);
						}
					}
				}
			} while (response.getRecordPage().hasNext());

			// compare with actual
			final EntityCollectionContract collection =
				this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow()
					.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow();

			// verify grouped indexes
			final Set<Integer> allGroupPKs = new HashSet<>(expectedPerGroup.keySet());
			for (int g = 1; g <= MAX_GROUPS; g++) {
				allGroupPKs.add(g);
			}
			for (int groupPK : allGroupPKs) {
				final Map<Serializable, Set<Integer>> expected =
					expectedPerGroup.getOrDefault(groupPK, Map.of());
				final EntityIndex entityIndex = getReferencedGroupEntityIndex(
					collection, scope, referenceName, groupPK
				);
				final Map<Serializable, Set<Integer>> actual;
				if (entityIndex instanceof ReducedGroupEntityIndex groupIndex) {
					actual = extractHistogramState(
						groupIndex.getHistogramFilterIndex(histogramName, null)
					);
				} else {
					actual = Map.of();
				}
				assertEquals(
					expected, actual,
					"Histogram mismatch (query-based) for grouped ref '" + referenceName
						+ "' group=" + groupPK + " histogram='" + histogramName
						+ "' scope=" + scope
				);
			}

			// verify ungrouped index
			final EntityIndex typeIndex = getReferencedEntityTypeIndex(
				collection, scope, referenceName
			);
			final Map<Serializable, Set<Integer>> actualUngrouped;
			if (typeIndex instanceof ReferencedTypeEntityIndex rti) {
				actualUngrouped = extractHistogramState(
					rti.getHistogramFilterIndex(histogramName, null)
				);
			} else {
				actualUngrouped = Map.of();
			}
			assertEquals(
				expectedUngrouped, actualUngrouped,
				"Histogram mismatch (query-based) for ungrouped ref '" + referenceName
					+ "' histogram='" + histogramName + "' scope=" + scope
			);
			return null;
		});
	}

	/**
	 * Extracts the complete histogram state from a FilterIndex as a value-to-ownerPKs map.
	 */
	@Nonnull
	private static Map<Serializable, Set<Integer>> extractHistogramState(
		@Nullable FilterIndex filterIndex
	) {
		if (filterIndex == null) {
			return Map.of();
		}
		final ValueToRecordBitmap[] buckets =
			filterIndex.getHistogramOfAllRecords().getHistogramBuckets();
		final Map<Serializable, Set<Integer>> result = new HashMap<>(buckets.length);
		for (ValueToRecordBitmap bucket : buckets) {
			final Set<Integer> pks = new HashSet<>();
			bucket.getRecordIds().forEach(pks::add);
			if (!pks.isEmpty()) {
				result.put(bucket.getValue(), pks);
			}
		}
		return result;
	}
}
