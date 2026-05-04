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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.api.GenerationalTestSupport.TestState;
import lombok.extern.apachecommons.CommonsLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.api.functional.indexing.IndexingTestSupport.getGlobalIndex;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.apache.commons.io.FileUtils.byteCountToDisplaySize;
import static org.apache.commons.io.FileUtils.sizeOfDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.FACET;

/**
 * Generational tests for the conditional facet indexing feature (`facetedPartially` expressions).
 * Each test targets a single expression path (entity attribute, reference attribute, group entity
 * attribute, referenced entity attribute, parent entity attribute) and verifies that the facet index
 * stays consistent with a simplified in-memory shadow state across many random mutations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@CommonsLog
@DisplayName("Conditional facet generational tests")
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(FACET)
class EvitaConditionalFacetGenerationalTest implements EvitaTestSupport, TimeBoundedTestSupport, GenerationalTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_PARAMETER_GROUP = "parameterGroup";

	private static final String ATTR_IS_ACTIVE = "isActive";
	private static final String ATTR_PRIORITY = "priority";
	private static final String ATTR_STATUS = "status";
	private static final String ATTR_WIDGET_TYPE = "widgetType";
	private static final String ATTR_CODE = "code";

	private static final int MAX_PRODUCTS = 20;
	private static final int MAX_PARAMETERS = 5;
	private static final int MAX_GROUPS = 3;
	/** Number of root-eligible products (used as potential parents in test 5). */
	private static final int MAX_PARENTS = 5;

	private final StringBuilder operationLog = new StringBuilder(4096);
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("EvitaConditionalFacetGenerationalTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	// --- Test 1: Entity Attribute ---

	@ParameterizedTest(name = "Conditional facet by entity attribute should survive generational test")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: facetedPartially by entity attribute")
	void shouldSurviveGenerationalTestWithEntityAttributeExpression(GenerationalTestInput input) {
		// schema setup
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER).updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withAttribute(ATTR_IS_ACTIVE, Boolean.class, AttributeSchemaEditor::filterable)
				.withReferenceToEntity(
					ENTITY_PARAMETER, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.faceted()
						.facetedPartially(
							ExpressionFactory.parse(
								"($entity.attributes['isActive'] ?? false) == true"
							)
						)
				)
				.updateVia(session);

			// seed parameters
			for (int i = 1; i <= MAX_PARAMETERS; i++) {
				session.createNewEntity(ENTITY_PARAMETER, i).upsertVia(session);
			}
			session.goLiveAndClose();
		});

		// shadow state
		final Map<Integer, Boolean> productIsActive = new HashMap<>();
		// productPK -> set of parameterPKs
		final Map<Integer, Set<Integer>> productRefs = new HashMap<>();
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
						if (roll < 40) {
							// toggle isActive on a random existing product
							toggleEntityAttribute(random, session, productIsActive, productRefs);
						} else if (roll < 65) {
							// add a reference
							addReference(random, session, productIsActive, productRefs, MAX_PARAMETERS);
						} else if (roll < 80) {
							// remove a reference
							removeReference(random, session, productRefs);
						} else if (roll < 90) {
							// create a new product
							createProduct(random, session, productIsActive, productRefs, nextProductPK);
						} else {
							// delete a product
							deleteProduct(random, session, productIsActive, productRefs);
						}
					}
				});

				// verify: compute expected facets
				final Map<Integer, Set<Integer>> expected = new HashMap<>();
				for (Entry<Integer, Set<Integer>> entry : productRefs.entrySet()) {
					final int productPK = entry.getKey();
					final boolean active = Boolean.TRUE.equals(productIsActive.get(productPK));
					if (active) {
						for (int paramPK : entry.getValue()) {
							expected.computeIfAbsent(paramPK, k -> new HashSet<>()).add(productPK);
						}
					}
				}

				this.operationLog.append("Shadow: productIsActive=").append(productIsActive).append('\n');
				this.operationLog.append("Shadow: productRefs=").append(productRefs).append('\n');
				this.operationLog.append("Expected facets: ").append(expected).append('\n');

				assertWithLog(
					() -> assertFacetIndexMatchesExpected(ENTITY_PARAMETER, null, expected)
				);

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(
						() -> assertFacetIndexMatchesExpected(ENTITY_PARAMETER, null, expected)
					);
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// --- Test 2: Reference Attribute ---

	@ParameterizedTest(name = "Conditional facet by reference attribute should survive generational test")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: facetedPartially by reference attribute")
	void shouldSurviveGenerationalTestWithReferenceAttributeExpression(GenerationalTestInput input) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER).updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					ENTITY_PARAMETER, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.faceted()
						.withAttribute(ATTR_PRIORITY, Integer.class, AttributeSchemaEditor::nullable)
						.facetedPartially(
							ExpressionFactory.parse("($reference.attributes['priority'] ?? 0) > 0")
						)
				)
				.updateVia(session);

			for (int i = 1; i <= MAX_PARAMETERS; i++) {
				session.createNewEntity(ENTITY_PARAMETER, i).upsertVia(session);
			}
			session.goLiveAndClose();
		});

		// shadow: (productPK, paramPK) -> priority value
		final Map<Long, Integer> refPriority = new HashMap<>();
		final Map<Integer, Set<Integer>> productRefs = new HashMap<>();
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
						if (roll < 40) {
							// change priority on an existing reference
							changeRefPriority(random, session, productRefs, refPriority);
						} else if (roll < 65) {
							// add a reference with random priority
							addRefWithPriority(random, session, productRefs, refPriority, MAX_PARAMETERS);
						} else if (roll < 80) {
							// remove a reference
							removeRefWithPriority(random, session, productRefs, refPriority);
						} else if (roll < 90) {
							// create a new product
							createProductForRefAttr(random, session, productRefs, nextProductPK);
						} else {
							// delete a product
							deleteProductForRefAttr(random, session, productRefs, refPriority);
						}
					}
				});

				// verify
				final Map<Integer, Set<Integer>> expected = new HashMap<>();
				for (Entry<Integer, Set<Integer>> entry : productRefs.entrySet()) {
					final int productPK = entry.getKey();
					for (int paramPK : entry.getValue()) {
						final int priority = refPriority.getOrDefault(
							GenerationalTestSupport.encodeRefKey(productPK, paramPK), 0
						);
						if (priority > 0) {
							expected.computeIfAbsent(paramPK, k -> new HashSet<>()).add(productPK);
						}
					}
				}

				this.operationLog.append("Shadow: productRefs=").append(productRefs).append('\n');
				this.operationLog.append("Shadow: refPriority=").append(refPriority).append('\n');
				this.operationLog.append("Expected facets: ").append(expected).append('\n');

				assertWithLog(
					() -> assertFacetIndexMatchesExpected(ENTITY_PARAMETER, null, expected)
				);

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(
						() -> assertFacetIndexMatchesExpected(ENTITY_PARAMETER, null, expected)
					);
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// --- Test 3: Group Entity Attribute (cross-entity fan-out) ---

	@ParameterizedTest(name = "Conditional facet by group entity attribute should survive generational test")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: facetedPartially by group entity attribute")
	void shouldSurviveGenerationalTestWithGroupEntityAttributeExpression(GenerationalTestInput input) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER_GROUP)
				.withAttribute(ATTR_WIDGET_TYPE, String.class, AttributeSchemaEditor::filterable)
				.updateVia(session);
			session.defineEntitySchema(ENTITY_PARAMETER).updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					ENTITY_PARAMETER, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.indexedWithComponents(ReferenceIndexedComponents.values())
						.faceted()
						.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_GROUP)
						.facetedPartially(
							ExpressionFactory.parse(
								"($reference.groupEntity?.attributes['widgetType'] ?? '') == 'CHECKBOX'"
							)
						)
				)
				.updateVia(session);

			for (int i = 1; i <= MAX_PARAMETERS; i++) {
				session.createNewEntity(ENTITY_PARAMETER, i).upsertVia(session);
			}
			for (int i = 1; i <= MAX_GROUPS; i++) {
				session.createNewEntity(ENTITY_PARAMETER_GROUP, i)
					.setAttribute(ATTR_WIDGET_TYPE, i == 1 ? "CHECKBOX" : "RADIO")
					.upsertVia(session);
			}
			session.goLiveAndClose();
		});

		// shadow state
		final Map<Integer, String> groupWidgetType = new HashMap<>();
		groupWidgetType.put(1, "CHECKBOX");
		for (int i = 2; i <= MAX_GROUPS; i++) {
			groupWidgetType.put(i, "RADIO");
		}
		// productPK -> (paramPK -> groupPK)
		final Map<Integer, Map<Integer, Integer>> productRefGroups = new HashMap<>();
		final Set<Integer> existingGroups = new HashSet<>(groupWidgetType.keySet());
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
							changeGroupWidgetType(random, session, groupWidgetType, existingGroups);
						} else if (roll < 55) {
							addGroupedReference(
								random, session, productRefGroups, existingGroups, MAX_PARAMETERS,
								groupWidgetType
							);
						} else if (roll < 70) {
							removeGroupedReference(random, session, productRefGroups, groupWidgetType);
						} else if (roll < 85) {
							createProductForGroup(random, session, productRefGroups, nextProductPK);
						} else {
							deleteProductForGroup(random, session, productRefGroups, groupWidgetType);
						}
					}
				});

				this.operationLog.append("Shadow: groupWidgetType=").append(groupWidgetType).append('\n');
				this.operationLog.append("Shadow: productRefGroups=").append(productRefGroups).append('\n');
				this.operationLog.append("Expected facet state:\n")
					.append(formatExpectedFacetState(productRefGroups, groupWidgetType));

				// verify facet index matches queried entity state
				assertWithLog(() -> assertFacetIndexMatchesQueriedState(
					ENTITY_PARAMETER, ATTR_WIDGET_TYPE, "CHECKBOX", true
				));

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(() -> assertFacetIndexMatchesQueriedState(
						ENTITY_PARAMETER, ATTR_WIDGET_TYPE, "CHECKBOX", true
					));
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// --- Test 4: Referenced Entity Attribute (cross-entity fan-out) ---

	@ParameterizedTest(name = "Conditional facet by referenced entity attribute should survive generational test")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: facetedPartially by referenced entity attribute")
	void shouldSurviveGenerationalTestWithReferencedEntityAttributeExpression(GenerationalTestInput input) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER)
				.withAttribute(ATTR_STATUS, String.class, AttributeSchemaEditor::filterable)
				.updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					ENTITY_PARAMETER, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.faceted()
						.facetedPartially(
							ExpressionFactory.parse(
								"($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'"
							)
						)
				)
				.updateVia(session);

			for (int i = 1; i <= MAX_PARAMETERS; i++) {
				session.createNewEntity(ENTITY_PARAMETER, i)
					.setAttribute(ATTR_STATUS, i <= 2 ? "ACTIVE" : "INACTIVE")
					.upsertVia(session);
			}
			session.goLiveAndClose();
		});

		// shadow state
		final Map<Integer, String> paramStatus = new HashMap<>();
		for (int i = 1; i <= MAX_PARAMETERS; i++) {
			paramStatus.put(i, i <= 2 ? "ACTIVE" : "INACTIVE");
		}
		final Map<Integer, Set<Integer>> productRefs = new HashMap<>();
		final Set<Integer> existingParameters = new HashSet<>(paramStatus.keySet());
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
							changeParamStatus(random, session, paramStatus, existingParameters);
						} else if (roll < 55) {
							addReferenceToParam(random, session, productRefs, existingParameters);
						} else if (roll < 70) {
							removeReferenceSimple(random, session, productRefs);
						} else if (roll < 85) {
							createProductSimple(random, session, productRefs, nextProductPK);
						} else {
							deleteProductSimple(random, session, productRefs);
						}
					}
				});

				this.operationLog.append("Shadow: paramStatus=").append(paramStatus).append('\n');
				this.operationLog.append("Shadow: productRefs=").append(productRefs).append('\n');

				// verify facet index matches queried entity state
				assertWithLog(() -> assertFacetIndexMatchesQueriedState(
					ENTITY_PARAMETER, ATTR_STATUS, "ACTIVE", false
				));

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(() -> assertFacetIndexMatchesQueriedState(
						ENTITY_PARAMETER, ATTR_STATUS, "ACTIVE", false
					));
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// --- Test 5: Parent Entity Attribute (cross-entity fan-out) ---

	@ParameterizedTest(name = "Conditional facet by parent entity attribute should survive generational test")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational test: facetedPartially by parent entity attribute")
	void shouldSurviveGenerationalTestWithParentEntityAttributeExpression(GenerationalTestInput input) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_PARAMETER).updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withHierarchy()
				.withAttribute(ATTR_CODE, String.class, whichIs -> whichIs.filterable().nullable())
				.withReferenceToEntity(
					ENTITY_PARAMETER, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.faceted()
						.facetedPartially(
							ExpressionFactory.parse(
								"($entity.parentEntity?.attributes['code'] ?? '') == 'ROOT'"
							)
						)
				)
				.updateVia(session);

			for (int i = 1; i <= MAX_PARAMETERS; i++) {
				session.createNewEntity(ENTITY_PARAMETER, i).upsertVia(session);
			}
			// seed parent-eligible products (PKs 1..MAX_PARENTS) with code attribute
			for (int i = 1; i <= MAX_PARENTS; i++) {
				session.createNewEntity(ENTITY_PRODUCT, i)
					.setAttribute(ATTR_CODE, i <= 2 ? "ROOT" : "OTHER")
					.upsertVia(session);
			}
			session.goLiveAndClose();
		});

		// shadow state
		final Map<Integer, String> productCode = new HashMap<>();
		for (int i = 1; i <= MAX_PARENTS; i++) {
			productCode.put(i, i <= 2 ? "ROOT" : "OTHER");
		}
		// child productPK -> parentPK (null if no parent)
		final Map<Integer, Integer> productParent = new HashMap<>();
		final Map<Integer, Set<Integer>> productRefs = new HashMap<>();
		final Set<Integer> existingProducts = new HashSet<>();
		for (int i = 1; i <= MAX_PARENTS; i++) {
			existingProducts.add(i);
		}
		// parent-eligible PKs are separate from child PKs; children start at MAX_PARENTS + 1
		final int[] mutableNextChildPK = {MAX_PARENTS + 1};
		// new parent-eligible PKs start beyond the child range

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
						if (roll < 20) {
							changeParentCode(random, session, productCode, existingProducts);
						} else if (roll < 35) {
							changeParent(
								random, session, productParent, productCode, existingProducts
							);
						} else if (roll < 55) {
							addReferenceForParentTest(
								random, session, productRefs, productParent, existingProducts,
								MAX_PARAMETERS
							);
						} else if (roll < 70) {
							removeReferenceForParentTest(random, session, productRefs);
						} else {
							createChildProduct(
								random, session, productParent, productCode, productRefs,
								existingProducts, mutableNextChildPK
							);
						}
					}
				});

				this.operationLog.append("Shadow: productCode=").append(productCode).append('\n');
				this.operationLog.append("Shadow: productParent=").append(productParent).append('\n');
				this.operationLog.append("Shadow: productRefs=").append(productRefs).append('\n');

				assertWithLog(this::assertFacetIndexMatchesQueriedParentState);

				if (gen % 3 == 0) {
					restartEvita(gen);
					this.operationLog.append("--- After restart ---\n");
					assertWithLog(this::assertFacetIndexMatchesQueriedParentState);
				}
				return new TestState(gen);
			}
		);
		logFinished(finalState);
	}

	// ==================== Shared Helpers ====================

	/**
	 * Formats a reference map as `{param=group[TYPE], ...}` showing facet state for each ref.
	 *
	 * @param refs             param PK -> group PK mapping
	 * @param groupWidgetType  group PK -> widget type mapping
	 * @return formatted string, e.g. `{1=grp2[CHECKBOX], 5=grp3[RADIO]}`
	 */
	@Nonnull
	private static String formatRefsWithFacetState(
		@Nullable Map<Integer, Integer> refs,
		@Nonnull Map<Integer, String> groupWidgetType
	) {
		if (refs == null || refs.isEmpty()) {
			return "{}";
		}
		final StringBuilder sb = new StringBuilder(64);
		sb.append('{');
		boolean first = true;
		for (Entry<Integer, Integer> e : refs.entrySet()) {
			if (!first) {
				sb.append(", ");
			}
			first = false;
			final int paramPK = e.getKey();
			final int groupPK = e.getValue();
			final String type = groupWidgetType.getOrDefault(groupPK, "?");
			sb.append("param=").append(paramPK)
				.append("->grp").append(groupPK)
				.append('[').append(type).append(']');
		}
		sb.append('}');
		return sb.toString();
	}

	/**
	 * Builds a readable expected-facet-state summary from the shadow state.
	 * Shows which products should be faceted under which groups.
	 *
	 * @param productRefGroups product PK -> (param PK -> group PK)
	 * @param groupWidgetType  group PK -> widget type
	 * @return formatted string like `group1[CHECKBOX]: {param1=[prod1,prod2]}, group3[RADIO]: (hidden)`
	 */
	@Nonnull
	private static String formatExpectedFacetState(
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, String> groupWidgetType
	) {
		// group -> (param -> set of product PKs)
		final Map<Integer, Map<Integer, Set<Integer>>> byGroup = new java.util.TreeMap<>();
		for (Entry<Integer, Map<Integer, Integer>> pe : productRefGroups.entrySet()) {
			final int productPK = pe.getKey();
			for (Entry<Integer, Integer> re : pe.getValue().entrySet()) {
				byGroup.computeIfAbsent(re.getValue(), k -> new java.util.TreeMap<>())
					.computeIfAbsent(re.getKey(), k -> new java.util.TreeSet<>())
					.add(productPK);
			}
		}
		final StringBuilder sb = new StringBuilder(128);
		for (Entry<Integer, Map<Integer, Set<Integer>>> ge : byGroup.entrySet()) {
			final int groupPK = ge.getKey();
			final String type = groupWidgetType.getOrDefault(groupPK, "?");
			final boolean faceted = "CHECKBOX".equals(type);
			sb.append("  grp").append(groupPK).append('[').append(type).append("]: ");
			if (faceted) {
				sb.append(ge.getValue());
			} else {
				sb.append("(hidden)");
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	/**
	 * Appends a single operation line to the operation log.
	 *
	 * @param message the operation description
	 */
	private void logOp(@Nonnull String message) {
		this.operationLog.append("  ").append(message).append('\n');
	}

	/**
	 * Runs an assertion and, on failure, wraps the error with the full operation log
	 * so that the cause of the mismatch is immediately visible.
	 *
	 * @param assertion the assertion to run
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
	 * Restarts the Evita instance and waits until fully initialized. Used to verify
	 * that the facet index state survives persistence round-trips.
	 *
	 * @param generation current generation number for logging
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
	 *
	 * @param finalState the test state after the last generation
	 */
	private void logFinished(@Nonnull TestState finalState) {
		System.out.println(
			"Finished " + finalState.generation() + " generations, size on disk is "
				+ byteCountToDisplaySize(sizeOfDirectory(getTestDirectory().toFile()))
		);
	}

	/**
	 * Builds the Evita configuration for this test suite.
	 *
	 * @return the Evita configuration
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
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
	 * Adds a reference from a random product to a random parameter for Test 1.
	 */
	private void addReference(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Boolean> productIsActive,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		int maxParams
	) {
		if (productIsActive.isEmpty()) {
			logOp("ADD_REF (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productIsActive);
		final int paramPK = random.nextInt(maxParams) + 1;
		final Set<Integer> refs = productRefs.computeIfAbsent(productPK, k -> new HashSet<>());
		if (refs.add(paramPK)) {
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(ENTITY_PARAMETER, paramPK)
				.upsertVia(session);
			logOp("ADD_REF product=" + productPK + " -> param=" + paramPK);
		} else {
			logOp("ADD_REF product=" + productPK + " -> param=" + paramPK + " (already exists)");
		}
	}

	/**
	 * Removes a random reference from a random product for Test 1.
	 */
	private void removeReference(
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
			.removeReference(ENTITY_PARAMETER, pair[1])
			.upsertVia(session);
		final Set<Integer> refs = productRefs.get(pair[0]);
		refs.remove(pair[1]);
		if (refs.isEmpty()) {
			productRefs.remove(pair[0]);
		}
		logOp("REMOVE_REF product=" + pair[0] + " -> param=" + pair[1]);
	}

	/**
	 * Creates a new product with a random `isActive` value for Test 1.
	 */
	private void createProduct(
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
	private void deleteProduct(
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
		logOp("DELETE_PRODUCT pk=" + productPK + " (had refs: " + (removedRefs != null ? removedRefs : "[]") + ")");
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
		final int oldPriority = refPriority.getOrDefault(GenerationalTestSupport.encodeRefKey(pair[0], pair[1]), 0);
		final int newPriority = random.nextInt(11) - 3; // range [-3, 7]
		session.getEntity(ENTITY_PRODUCT, pair[0], entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				ENTITY_PARAMETER, pair[1],
				whichIs -> whichIs.setAttribute(ATTR_PRIORITY, newPriority)
			)
			.upsertVia(session);
		refPriority.put(GenerationalTestSupport.encodeRefKey(pair[0], pair[1]), newPriority);
		logOp("CHANGE_PRIORITY product=" + pair[0] + " param=" + pair[1] +
			" priority: " + oldPriority + " -> " + newPriority);
	}

	/**
	 * Adds a reference with a random priority value for Test 2.
	 */
	private void addRefWithPriority(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Map<Long, Integer> refPriority,
		int maxParams
	) {
		if (productRefs.isEmpty()) {
			logOp("ADD_REF_PRIORITY (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefs);
		final int paramPK = random.nextInt(maxParams) + 1;
		final Set<Integer> refs = productRefs.computeIfAbsent(productPK, k -> new HashSet<>());
		if (refs.add(paramPK)) {
			final int priority = random.nextInt(11) - 3;
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					ENTITY_PARAMETER, paramPK,
					whichIs -> whichIs.setAttribute(ATTR_PRIORITY, priority)
				)
				.upsertVia(session);
			refPriority.put(GenerationalTestSupport.encodeRefKey(productPK, paramPK), priority);
			logOp("ADD_REF_PRIORITY product=" + productPK + " -> param=" + paramPK +
				" priority=" + priority);
		} else {
			logOp("ADD_REF_PRIORITY product=" + productPK + " -> param=" + paramPK +
				" (already exists)");
		}
	}

	/**
	 * Removes a random reference for Test 2.
	 */
	private void removeRefWithPriority(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Map<Long, Integer> refPriority
	) {
		final int[] pair = GenerationalTestSupport.pickRandomRef(random, productRefs);
		if (pair == null) {
			logOp("REMOVE_REF_PRIORITY (no-op: no refs)");
			return;
		}
		final int oldPriority = refPriority.getOrDefault(GenerationalTestSupport.encodeRefKey(pair[0], pair[1]), 0);
		session.getEntity(ENTITY_PRODUCT, pair[0], entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeReference(ENTITY_PARAMETER, pair[1])
			.upsertVia(session);
		refPriority.remove(GenerationalTestSupport.encodeRefKey(pair[0], pair[1]));
		final Set<Integer> refs = productRefs.get(pair[0]);
		refs.remove(pair[1]);
		if (refs.isEmpty()) {
			productRefs.remove(pair[0]);
		}
		logOp("REMOVE_REF_PRIORITY product=" + pair[0] + " -> param=" + pair[1] +
			" (had priority=" + oldPriority + ")");
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
		@Nonnull Map<Long, Integer> refPriority
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
				refPriority.remove(GenerationalTestSupport.encodeRefKey(productPK, paramPK));
			}
		}
		logOp("DELETE_PRODUCT pk=" + productPK + " (had refs: " + (refs != null ? refs : "[]") + ")");
	}

	// ==================== Test 3 Helpers: Group Entity Attribute ====================

	/**
	 * Changes the `widgetType` on a random existing group entity (triggers fan-out).
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
		final String newType = random.nextBoolean() ? "CHECKBOX" : "RADIO";
		session.getEntity(ENTITY_PARAMETER_GROUP, groupPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_WIDGET_TYPE, newType)
			.upsertVia(session);
		groupWidgetType.put(groupPK, newType);
		final boolean faceted = "CHECKBOX".equals(newType);
		logOp("CHANGE_GROUP_TYPE group=" + groupPK + " widgetType: " + oldType + " -> " + newType +
			(faceted ? " [NOW FACETED]" : " [NOW HIDDEN]"));
	}

	/**
	 * Adds a grouped reference from a random product to a random parameter with a random group.
	 */
	private void addGroupedReference(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Set<Integer> existingGroups,
		int maxParams,
		@Nonnull Map<Integer, String> groupWidgetType
	) {
		if (productRefGroups.isEmpty() || existingGroups.isEmpty()) {
			logOp("ADD_GROUPED_REF (no-op: no products or groups)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		final int paramPK = random.nextInt(maxParams) + 1;
		final int groupPK = GenerationalTestSupport.pickRandomFromSet(random, existingGroups);
		final Map<Integer, Integer> refs = productRefGroups.computeIfAbsent(
			productPK, k -> new HashMap<>()
		);
		if (!refs.containsKey(paramPK)) {
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					ENTITY_PARAMETER, paramPK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, groupPK)
				)
				.upsertVia(session);
			refs.put(paramPK, groupPK);
			logOp("ADD_GROUPED_REF product=" + productPK + " -> param=" + paramPK +
				" group=" + groupPK +
				(" [" + groupWidgetType.get(groupPK) + "]"));
		} else {
			logOp("ADD_GROUPED_REF product=" + productPK + " -> param=" + paramPK +
				" (already exists, group=" + refs.get(paramPK) + ")");
		}
	}

	/**
	 * Removes a random grouped reference.
	 */
	private void removeGroupedReference(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, String> groupWidgetType
	) {
		if (productRefGroups.isEmpty()) {
			logOp("REMOVE_GROUPED_REF (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefGroups);
		final Map<Integer, Integer> refs = productRefGroups.get(productPK);
		if (refs == null || refs.isEmpty()) {
			logOp("REMOVE_GROUPED_REF (no-op: product=" + productPK + " has no refs)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomKey(random, refs);
		final int groupPK = refs.get(paramPK);
		session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeReference(ENTITY_PARAMETER, paramPK)
			.upsertVia(session);
		refs.remove(paramPK);
		if (refs.isEmpty()) {
			productRefGroups.remove(productPK);
		}
		logOp("REMOVE_GROUPED_REF product=" + productPK + " -> param=" + paramPK +
			" (was group=" + groupPK + " [" + groupWidgetType.get(groupPK) + "])");
	}

	/**
	 * Creates a new product for Test 3.
	 */
	private void createProductForGroup(
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
	private void deleteProductForGroup(
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
		final Map<Integer, Integer> removedRefs = productRefGroups.get(productPK);
		session.deleteEntity(ENTITY_PRODUCT, productPK);
		productRefGroups.remove(productPK);
		logOp("DELETE_PRODUCT pk=" + productPK + " (had refs: " +
			formatRefsWithFacetState(removedRefs, groupWidgetType) + ")");
	}

	/**
	 * Deletes a random group entity. All references pointing to this group are
	 * removed from their owner products first to avoid index inconsistencies,
	 * then the group entity is deleted.
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
		// first, remove all references that point to this group
		for (Entry<Integer, Map<Integer, Integer>> productEntry : productRefGroups.entrySet()) {
			final int productPK = productEntry.getKey();
			final Map<Integer, Integer> refs = productEntry.getValue();
			final Set<Integer> toRemove = new HashSet<>();
			for (Entry<Integer, Integer> refEntry : refs.entrySet()) {
				if (refEntry.getValue() == groupPK) {
					toRemove.add(refEntry.getKey());
				}
			}
			if (!toRemove.isEmpty()) {
				var builder = session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
					.orElseThrow()
					.openForWrite();
				for (int paramPK : toRemove) {
					builder = builder.removeReference(ENTITY_PARAMETER, paramPK);
					refs.remove(paramPK);
				}
				builder.upsertVia(session);
			}
		}
		productRefGroups.entrySet().removeIf(entry -> entry.getValue().isEmpty());
		// then delete the group entity
		session.deleteEntity(ENTITY_PARAMETER_GROUP, groupPK);
		groupWidgetType.remove(groupPK);
		existingGroups.remove(groupPK);
		logOp("DELETE_GROUP group=" + groupPK);
	}

	// ==================== Test 4 Helpers: Referenced Entity Attribute ====================

	/**
	 * Changes the `status` attribute on a random parameter (triggers fan-out).
	 */
	private void changeParamStatus(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, String> paramStatus,
		@Nonnull Set<Integer> existingParameters
	) {
		if (existingParameters.isEmpty()) {
			logOp("CHANGE_PARAM_STATUS (no-op: no params)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParameters);
		final String oldStatus = paramStatus.get(paramPK);
		final String newStatus = random.nextBoolean() ? "ACTIVE" : "INACTIVE";
		session.getEntity(ENTITY_PARAMETER, paramPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_STATUS, newStatus)
			.upsertVia(session);
		paramStatus.put(paramPK, newStatus);
		logOp("CHANGE_PARAM_STATUS param=" + paramPK + " status: " + oldStatus + " -> " + newStatus);
	}

	/**
	 * Adds a reference from a random product to a random existing parameter for Test 4.
	 */
	private void addReferenceToParam(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Set<Integer> existingParameters
	) {
		if (productRefs.isEmpty() || existingParameters.isEmpty()) {
			logOp("ADD_REF (no-op: no products or params)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefs);
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParameters);
		final Set<Integer> refs = productRefs.computeIfAbsent(productPK, k -> new HashSet<>());
		if (refs.add(paramPK)) {
			session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(ENTITY_PARAMETER, paramPK)
				.upsertVia(session);
			logOp("ADD_REF product=" + productPK + " -> param=" + paramPK);
		} else {
			logOp("ADD_REF product=" + productPK + " -> param=" + paramPK + " (already exists)");
		}
	}

	/**
	 * Removes a random reference for Test 4.
	 */
	private void removeReferenceSimple(
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
			.removeReference(ENTITY_PARAMETER, pair[1])
			.upsertVia(session);
		final Set<Integer> refs = productRefs.get(pair[0]);
		refs.remove(pair[1]);
		if (refs.isEmpty()) {
			productRefs.remove(pair[0]);
		}
		logOp("REMOVE_REF product=" + pair[0] + " -> param=" + pair[1]);
	}

	/**
	 * Creates a new product for Test 4.
	 */
	private void createProductSimple(
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
	private void deleteProductSimple(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		if (productRefs.isEmpty()) {
			logOp("DELETE_PRODUCT (no-op: no products)");
			return;
		}
		final int productPK = GenerationalTestSupport.pickRandomKey(random, productRefs);
		final Set<Integer> removedRefs = productRefs.get(productPK);
		session.deleteEntity(ENTITY_PRODUCT, productPK);
		productRefs.remove(productPK);
		logOp("DELETE_PRODUCT pk=" + productPK + " (had refs: " + removedRefs + ")");
	}

	/**
	 * Deletes a random parameter entity. All references pointing to this parameter
	 * are removed from their owner products first to avoid index inconsistencies,
	 * then the parameter entity is deleted.
	 */
	private void deleteParameterEntity(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, String> paramStatus,
		@Nonnull Set<Integer> existingParameters,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		if (existingParameters.isEmpty()) {
			logOp("DELETE_PARAM (no-op: no params)");
			return;
		}
		final int paramPK = GenerationalTestSupport.pickRandomFromSet(random, existingParameters);
		// first, remove all references pointing to this parameter from products
		for (Entry<Integer, Set<Integer>> entry : productRefs.entrySet()) {
			final int productPK = entry.getKey();
			if (entry.getValue().remove(paramPK)) {
				session.getEntity(ENTITY_PRODUCT, productPK, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(ENTITY_PARAMETER, paramPK)
					.upsertVia(session);
			}
		}
		productRefs.entrySet().removeIf(entry -> entry.getValue().isEmpty());
		// then delete the parameter entity
		session.deleteEntity(ENTITY_PARAMETER, paramPK);
		paramStatus.remove(paramPK);
		existingParameters.remove(paramPK);
		logOp("DELETE_PARAM param=" + paramPK);
	}

	// ==================== Test 5 Helpers: Parent Entity Attribute ====================

	/**
	 * Changes the `code` attribute on a random parent-eligible product (triggers fan-out).
	 */
	private void changeParentCode(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, String> productCode,
		@Nonnull Set<Integer> existingProducts
	) {
		if (productCode.isEmpty()) {
			logOp("CHANGE_PARENT_CODE (no-op: no parents)");
			return;
		}
		final int parentPK = GenerationalTestSupport.pickRandomKey(random, productCode);
		if (!existingProducts.contains(parentPK)) {
			logOp("CHANGE_PARENT_CODE (no-op: parent=" + parentPK + " not in existingProducts)");
			return;
		}
		final String oldCode = productCode.get(parentPK);
		final String newCode = random.nextBoolean() ? "ROOT" : "OTHER";
		session.getEntity(ENTITY_PRODUCT, parentPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_CODE, newCode)
			.upsertVia(session);
		productCode.put(parentPK, newCode);
		logOp("CHANGE_PARENT_CODE parent=" + parentPK + " code: " + oldCode + " -> " + newCode);
	}

	/**
	 * Assigns or changes the parent for a random child product.
	 */
	private void changeParent(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Integer> productParent,
		@Nonnull Map<Integer, String> productCode,
		@Nonnull Set<Integer> existingProducts
	) {
		if (productParent.isEmpty() || productCode.isEmpty()) {
			logOp("CHANGE_PARENT (no-op: no children or parents)");
			return;
		}
		// pick a child product that has refs tracked
		final int childPK = GenerationalTestSupport.pickRandomKey(random, productParent);
		if (!existingProducts.contains(childPK)) {
			logOp("CHANGE_PARENT (no-op: child=" + childPK + " not in existingProducts)");
			return;
		}
		// pick a parent from existing parent-eligible products
		final Set<Integer> parentEligible = productCode.keySet();
		if (parentEligible.isEmpty()) {
			logOp("CHANGE_PARENT (no-op: no parent-eligible products)");
			return;
		}
		final int parentPK = GenerationalTestSupport.pickRandomFromSet(random, parentEligible);
		if (parentPK == childPK) {
			logOp("CHANGE_PARENT (no-op: self-reference child=" + childPK + ")");
			return; // avoid self-reference
		}
		final Integer oldParent = productParent.get(childPK);
		session.getEntity(ENTITY_PRODUCT, childPK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setParent(parentPK)
			.upsertVia(session);
		productParent.put(childPK, parentPK);
		logOp("CHANGE_PARENT child=" + childPK + " parent: " + oldParent + " -> " + parentPK);
	}

	/**
	 * Adds a reference to a random child product for Test 5.
	 */
	private void addReferenceForParentTest(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Map<Integer, Integer> productParent,
		@Nonnull Set<Integer> existingProducts,
		int maxParams
	) {
		if (productParent.isEmpty()) {
			logOp("ADD_REF (no-op: no children)");
			return;
		}
		// pick a child product that exists
		final int childPK = GenerationalTestSupport.pickRandomKey(random, productParent);
		if (!existingProducts.contains(childPK)) {
			logOp("ADD_REF (no-op: child=" + childPK + " not in existingProducts)");
			return;
		}
		final int paramPK = random.nextInt(maxParams) + 1;
		final Set<Integer> refs = productRefs.computeIfAbsent(childPK, k -> new HashSet<>());
		if (refs.add(paramPK)) {
			session.getEntity(ENTITY_PRODUCT, childPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(ENTITY_PARAMETER, paramPK)
				.upsertVia(session);
			logOp("ADD_REF child=" + childPK + " -> param=" + paramPK);
		} else {
			logOp("ADD_REF child=" + childPK + " -> param=" + paramPK + " (already exists)");
		}
	}

	/**
	 * Removes a random reference for Test 5.
	 */
	private void removeReferenceForParentTest(
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
			.removeReference(ENTITY_PARAMETER, pair[1])
			.upsertVia(session);
		final Set<Integer> refs = productRefs.get(pair[0]);
		refs.remove(pair[1]);
		if (refs.isEmpty()) {
			productRefs.remove(pair[0]);
		}
		logOp("REMOVE_REF child=" + pair[0] + " -> param=" + pair[1]);
	}

	/**
	 * Creates a new child product with optional parent for Test 5.
	 */
	private void createChildProduct(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Integer> productParent,
		@Nonnull Map<Integer, String> productCode,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Set<Integer> existingProducts,
		@Nonnull int[] nextChildPK
	) {
		if (existingProducts.size() >= MAX_PRODUCTS + MAX_PARENTS) {
			logOp("CREATE_CHILD (no-op: max reached)");
			return;
		}
		final int pk = nextChildPK[0]++;
		final var builder = session.createNewEntity(ENTITY_PRODUCT, pk);
		Integer parentPK = null;
		if (!productCode.isEmpty() && random.nextBoolean()) {
			parentPK = GenerationalTestSupport.pickRandomFromSet(random, productCode.keySet());
			builder.setParent(parentPK);
		}
		builder.upsertVia(session);
		productParent.put(pk, parentPK);
		productRefs.put(pk, new HashSet<>());
		existingProducts.add(pk);
		logOp("CREATE_CHILD pk=" + pk + " parent=" + parentPK);
	}

	/**
	 * Deletes a random child product (not parent-eligible) for Test 5.
	 * If the child has a parent that was already deleted, we must first
	 * remove the parent from the child to avoid hierarchy index errors.
	 */
	private void deleteChildProduct(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Integer> productParent,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Set<Integer> existingProducts
	) {
		// only delete child products (those in productParent)
		if (productParent.isEmpty()) {
			logOp("DELETE_CHILD (no-op: no children)");
			return;
		}
		final int childPK = GenerationalTestSupport.pickRandomKey(random, productParent);
		if (!existingProducts.contains(childPK)) {
			productParent.remove(childPK);
			logOp("DELETE_CHILD (cleanup: child=" + childPK + " already gone from existingProducts)");
			return;
		}
		// if parent was deleted, remove parent reference before deleting
		final Integer parentPK = productParent.get(childPK);
		if (parentPK != null && !existingProducts.contains(parentPK)) {
			session.getEntity(ENTITY_PRODUCT, childPK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.removeParent()
				.upsertVia(session);
		}
		final Set<Integer> removedRefs = productRefs.get(childPK);
		session.deleteEntity(ENTITY_PRODUCT, childPK);
		productParent.remove(childPK);
		productRefs.remove(childPK);
		existingProducts.remove(childPK);
		logOp("DELETE_CHILD pk=" + childPK + " parent=" + parentPK +
			" (had refs: " + (removedRefs != null ? removedRefs : "[]") + ")");
	}

	/**
	 * Deletes a random parent-eligible product and unparents all children.
	 */
	private void deleteParentProduct(
		@Nonnull Random random,
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, Integer> productParent,
		@Nonnull Map<Integer, String> productCode,
		@Nonnull Map<Integer, Set<Integer>> productRefs,
		@Nonnull Set<Integer> existingProducts
	) {
		if (productCode.isEmpty()) {
			logOp("DELETE_PARENT (no-op: no parents)");
			return;
		}
		final int parentPK = GenerationalTestSupport.pickRandomKey(random, productCode);
		if (!existingProducts.contains(parentPK)) {
			logOp("DELETE_PARENT (no-op: parent=" + parentPK + " not in existingProducts)");
			return;
		}
		// unparent all children that point to this parent
		for (Entry<Integer, Integer> entry : productParent.entrySet()) {
			if (Integer.valueOf(parentPK).equals(entry.getValue())) {
				final int childPK = entry.getKey();
				if (existingProducts.contains(childPK)) {
					session.getEntity(ENTITY_PRODUCT, childPK, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeParent()
						.upsertVia(session);
				}
				entry.setValue(null);
			}
		}
		session.deleteEntity(ENTITY_PRODUCT, parentPK);
		productCode.remove(parentPK);
		existingProducts.remove(parentPK);
		// also remove its refs if any
		productRefs.remove(parentPK);
		logOp("DELETE_PARENT pk=" + parentPK);
	}

	// ==================== Verification ====================

	/**
	 * Asserts that the actual facet index for the given reference (ungrouped facets) matches the expected state.
	 *
	 * @param referenceName the reference name to check
	 * @param groupPK       the group PK (null for ungrouped)
	 * @param expected      paramPK -> set of product PKs that should be faceted
	 */
	private void assertFacetIndexMatchesExpected(
		@Nonnull String referenceName,
		@Nullable Integer groupPK,
		@Nonnull Map<Integer, Set<Integer>> expected
	) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final EntityIndex globalIndex = getGlobalIndex(
				this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow()
					.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow()
			);
			if (globalIndex == null) {
				assertTrue(expected.isEmpty(), "Expected facets but no global index exists");
				return null;
			}

			final FacetReferenceIndex facetRefIndex =
				globalIndex.getFacetingEntities().get(referenceName);

			if (facetRefIndex == null) {
				assertTrue(
					expected.isEmpty(),
					"Expected facets but no FacetReferenceIndex exists for '" + referenceName + "'"
				);
				return null;
			}

			final FacetGroupIndex facetGroupIndex = facetRefIndex.getFacetsInGroup(groupPK);

			// collect actual state
			final Map<Integer, Set<Integer>> actual = new HashMap<>();
			if (facetGroupIndex != null) {
				for (Entry<Integer, FacetIdIndex> entry : facetGroupIndex.getFacetIdIndexes().entrySet()) {
					final Set<Integer> productPKs = new HashSet<>();
					entry.getValue().getRecords().forEach(productPKs::add);
					if (!productPKs.isEmpty()) {
						actual.put(entry.getKey(), productPKs);
					}
				}
			}

			assertEquals(
				expected, actual,
				"Facet index mismatch for reference '" + referenceName + "', group " + groupPK
			);
			return null;
		});
	}

	/**
	 * Asserts grouped facet index: each product's reference has a specific group, so we need to
	 * verify per-group facet indexes.
	 *
	 * @param referenceName    the reference name to check
	 * @param productRefGroups productPK -> (paramPK -> groupPK)
	 * @param expected         paramPK -> set of product PKs that should be faceted (across all groups)
	 */
	private void assertGroupedFacetIndexMatchesExpected(
		@Nonnull String referenceName,
		@Nonnull Map<Integer, Map<Integer, Integer>> productRefGroups,
		@Nonnull Map<Integer, Set<Integer>> expected
	) {
		// build per-group expected: groupPK -> (paramPK -> set of productPKs)
		final Map<Integer, Map<Integer, Set<Integer>>> expectedPerGroup = new HashMap<>();
		for (Entry<Integer, Map<Integer, Integer>> productEntry : productRefGroups.entrySet()) {
			final int productPK = productEntry.getKey();
			for (Entry<Integer, Integer> refEntry : productEntry.getValue().entrySet()) {
				final int paramPK = refEntry.getKey();
				final int groupPK = refEntry.getValue();
				final Set<Integer> expectedProducts = expected.getOrDefault(paramPK, Set.of());
				if (expectedProducts.contains(productPK)) {
					expectedPerGroup
						.computeIfAbsent(groupPK, k -> new HashMap<>())
						.computeIfAbsent(paramPK, k -> new HashSet<>())
						.add(productPK);
				}
			}
		}

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final EntityIndex globalIndex = getGlobalIndex(
				this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow()
					.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow()
			);
			if (globalIndex == null) {
				assertTrue(
					expectedPerGroup.isEmpty(),
					"Expected grouped facets but no global index exists"
				);
				return null;
			}

			final FacetReferenceIndex facetRefIndex =
				globalIndex.getFacetingEntities().get(referenceName);

			// collect actual per-group state
			final Map<Integer, Map<Integer, Set<Integer>>> actualPerGroup = new HashMap<>();
			if (facetRefIndex != null) {
				for (FacetGroupIndex groupIndex : facetRefIndex.getGroupedFacets()) {
					final Integer groupId = groupIndex.getGroupId();
					if (groupId == null) {
						continue;
					}
					for (Entry<Integer, FacetIdIndex> entry : groupIndex.getFacetIdIndexes().entrySet()) {
						final Set<Integer> productPKs = new HashSet<>();
						entry.getValue().getRecords().forEach(productPKs::add);
						if (!productPKs.isEmpty()) {
							actualPerGroup
								.computeIfAbsent(groupId, k -> new HashMap<>())
								.put(entry.getKey(), productPKs);
						}
					}
				}
			}

			assertEquals(
				expectedPerGroup, actualPerGroup,
				"Grouped facet index mismatch for reference '" + referenceName + "'"
			);
			return null;
		});
	}

	/**
	 * Query-based verification for parent entity attribute test: queries all products,
	 * checks parent entity's `code` attribute, and verifies facet index consistency.
	 */
	private void assertFacetIndexMatchesQueriedParentState() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Map<Integer, Set<Integer>> expectedFacets = new HashMap<>();

			int pageNumber = 1;
			EvitaResponse<SealedEntity> response;
			do {
				response = session.querySealedEntity(
					query(
						collection(ENTITY_PRODUCT),
						require(
							entityFetch(
								hierarchyContent(
									entityFetch(attributeContentAll())
								),
								referenceContentAllWithAttributes()
							),
							page(pageNumber++, 100)
						)
					)
				);
				for (SealedEntity product : response.getRecordPage().getData()) {
					final boolean parentIsRoot = product.getParentEntity()
						.filter(SealedEntity.class::isInstance)
						.map(parent -> "ROOT".equals(
							((SealedEntity) parent).getAttribute(ATTR_CODE, String.class))
						)
						.orElse(false);
					if (parentIsRoot) {
						for (ReferenceContract ref : product.getReferences(ENTITY_PARAMETER)) {
							expectedFacets
								.computeIfAbsent(ref.getReferencedPrimaryKey(), k -> new HashSet<>())
								.add(product.getPrimaryKey());
						}
					}
				}
			} while (response.getRecordPage().hasNext());

			// compare with actual
			final EntityIndex globalIndex = getGlobalIndex(
				this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow()
					.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow()
			);
			final Map<Integer, Set<Integer>> actual = new HashMap<>();
			if (globalIndex != null) {
				final FacetReferenceIndex facetRefIndex =
					globalIndex.getFacetingEntities().get(ENTITY_PARAMETER);
				final FacetGroupIndex fgi = facetRefIndex != null
					? facetRefIndex.getFacetsInGroup(null)
					: null;
				if (fgi != null) {
					for (Entry<Integer, FacetIdIndex> e : fgi.getFacetIdIndexes().entrySet()) {
						final Set<Integer> pks = new HashSet<>();
						e.getValue().getRecords().forEach(pks::add);
						if (!pks.isEmpty()) {
							actual.put(e.getKey(), pks);
						}
					}
				}
			}
			assertEquals(
				expectedFacets, actual,
				"Facet index does not match queried parent-entity state"
			);
			return null;
		});
	}

	/**
	 * Query-based verification for cross-entity tests: queries all products from evitaDB,
	 * evaluates the expected facet state from actual entity data, and compares with the
	 * facet index. This eliminates shadow state synchronization issues.
	 *
	 * @param referenceName the reference to verify
	 * @param attrName      the attribute name on the cross-entity
	 * @param attrValue     the expected attribute value for the expression to be true
	 * @param grouped       true if the reference has groups
	 */
	private void assertFacetIndexMatchesQueriedState(
		@Nonnull String referenceName,
		@Nonnull String attrName,
		@Nonnull String attrValue,
		boolean grouped
	) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			// query all products with full content including referenced/group entity attributes
			final Map<Integer, Set<Integer>> expectedFacets = new HashMap<>();
			final Map<Integer, Map<Integer, Set<Integer>>> expectedGroupedFacets = new HashMap<>();

			int pageNumber = 1;
			EvitaResponse<SealedEntity> response;
			do {
				response = session.querySealedEntity(
					query(
						collection(ENTITY_PRODUCT),
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
						final int paramPK = ref.getReferencedPrimaryKey();
						boolean shouldBeFaceted;
						if (grouped) {
							shouldBeFaceted = ref.getGroupEntity()
								.map(ge -> attrValue.equals(ge.getAttribute(attrName, String.class)))
								.orElse(false);
						} else {
							shouldBeFaceted = ref.getReferencedEntity()
								.map(re -> attrValue.equals(re.getAttribute(attrName, String.class)))
								.orElse(false);
						}
						if (shouldBeFaceted) {
							final int productPK = product.getPrimaryKey();
							if (grouped) {
								final int groupPK = ref.getGroup()
									.map(GroupEntityReference::getPrimaryKey)
									.orElse(0);
								expectedGroupedFacets
									.computeIfAbsent(groupPK, k -> new HashMap<>())
									.computeIfAbsent(paramPK, k -> new HashSet<>())
									.add(productPK);
							} else {
								expectedFacets
									.computeIfAbsent(paramPK, k -> new HashSet<>())
									.add(productPK);
							}
						}
					}
				}
			} while (response.getRecordPage().hasNext());

			// compare with actual facet index
			final EntityIndex globalIndex = getGlobalIndex(
				this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow()
					.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow()
			);
			final FacetReferenceIndex facetRefIndex = globalIndex != null
				? globalIndex.getFacetingEntities().get(referenceName)
				: null;

			if (grouped) {
				final Map<Integer, Map<Integer, Set<Integer>>> actualGrouped = new HashMap<>();
				if (facetRefIndex != null) {
					for (FacetGroupIndex gi : facetRefIndex.getGroupedFacets()) {
						if (gi.getGroupId() == null) {
							continue;
						}
						for (Entry<Integer, FacetIdIndex> e : gi.getFacetIdIndexes().entrySet()) {
							final Set<Integer> pks = new HashSet<>();
							e.getValue().getRecords().forEach(pks::add);
							if (!pks.isEmpty()) {
								actualGrouped
									.computeIfAbsent(gi.getGroupId(), k -> new HashMap<>())
									.put(e.getKey(), pks);
							}
						}
					}
				}
				if (!expectedGroupedFacets.equals(actualGrouped)) {
					// dump diagnostic info for mismatched groups
					for (Entry<Integer, Map<Integer, Set<Integer>>> ae : actualGrouped.entrySet()) {
						if (!expectedGroupedFacets.containsKey(ae.getKey()) ||
							!expectedGroupedFacets.get(ae.getKey()).equals(ae.getValue())) {
							final int groupId = ae.getKey();
							session.getEntity(ENTITY_PARAMETER_GROUP, groupId, entityFetchAllContent())
								.ifPresent(ge -> System.out.println(
									"DIAG: group " + groupId + " " + attrName + "=" +
										ge.getAttribute(attrName, String.class)
								));
							for (Entry<Integer, Set<Integer>> fe : ae.getValue().entrySet()) {
								for (int productPK : fe.getValue()) {
									if (!expectedGroupedFacets.getOrDefault(groupId, Map.of())
										.getOrDefault(fe.getKey(), Set.of()).contains(productPK)) {
										System.out.println(
											"DIAG: unexpected facet: product=" + productPK +
												" param=" + fe.getKey() + " group=" + groupId
										);
									}
								}
							}
						}
					}
				}
				assertEquals(
					expectedGroupedFacets, actualGrouped,
					"Grouped facet index does not match queried entity state for '" + referenceName + "'"
				);
			} else {
				final Map<Integer, Set<Integer>> actual = new HashMap<>();
				final FacetGroupIndex fgi = facetRefIndex != null
					? facetRefIndex.getFacetsInGroup(null)
					: null;
				if (fgi != null) {
					for (Entry<Integer, FacetIdIndex> e : fgi.getFacetIdIndexes().entrySet()) {
						final Set<Integer> pks = new HashSet<>();
						e.getValue().getRecords().forEach(pks::add);
						if (!pks.isEmpty()) {
							actual.put(e.getKey(), pks);
						}
					}
				}
				assertEquals(
					expectedFacets, actual,
					"Facet index does not match queried entity state for '" + referenceName + "'"
				);
			}
			return null;
		});
	}

}
