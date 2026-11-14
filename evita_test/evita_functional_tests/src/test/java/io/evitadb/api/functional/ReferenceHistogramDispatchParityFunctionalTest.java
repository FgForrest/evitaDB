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

package io.evitadb.api.functional;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.functional.indexing.IndexingTestSupport;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.HistogramCapableEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that the cross-entity path writes and withdraws the units of a reference histogram exactly as the local
 * path does for the same stored data. The histogram reads an attribute of the referenced entity; when that entity
 * changes - its value, or the condition admitting the reference - the cross-entity path re-evaluates the owners, and
 * every owner must then hold what a fresh store of it would hold: one unit per element of an array, nothing for an
 * empty array, the default only where the value is missing.
 *
 * Every scenario runs in both catalog states and checks the histogram after every step and again after a restart. An
 * index lists an owner under a value once, however many units of the value the owner holds, so units are counted by
 * removals: the owner under test holds a sibling reference besides the subject reference, and every scenario ends by
 * removing the owner's references one at a time. A unit counted twice survives the last removal. A withdrawal of the
 * cross-entity path that finds no unit of the owner is skipped without a trace, so a missing unit, or a unit withdrawn
 * once too often, shows only where the sibling holds the same value: it takes the sibling's unit, and the sibling's
 * value then disappears too early or the sibling's removal refuses. The scenario of an owner referencing itself has
 * no sibling holding the repeated value; it checks that replacing the owner's own array leaves the new value and
 * nothing of the old one, not how many units of the repeated value were written. A scenario named `CONTROL_` uses
 * distinct values, so it shows the fixture itself working.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A reference histogram over the referenced entity, written by the cross-entity path")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class ReferenceHistogramDispatchParityFunctionalTest implements EvitaTestSupport {

	private static final String PREFIX = "ReferenceHistogramDispatchParity";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_BRAND_GROUP = "brandGroup";
	private static final String REFERENCE_BRAND = "brand";
	private static final String REFERENCE_RELATED = "related";
	private static final String ATTRIBUTE_SIZES = "sizes";
	private static final String ATTRIBUTE_ACTIVE = "active";
	private static final String ATTRIBUTE_LEVEL = "level";
	private static final String HISTOGRAM_SIZE = "sizeHistogram";
	private static final String HISTOGRAM_SECOND_SIZE = "secondSizeHistogram";
	private static final String SIZES_VALUE = "$reference.referencedEntity?.attributes['" + ATTRIBUTE_SIZES + "']";
	private static final String LOCALIZED_SIZES_VALUE =
		"$reference.referencedEntity?.localizedAttributes['" + ATTRIBUTE_SIZES + "']";
	private static final String BRAND_ACTIVE =
		"($reference.referencedEntity?.attributes['" + ATTRIBUTE_ACTIVE + "'] ?? false) == true";
	private static final String GROUP_ACTIVE =
		"($reference.groupEntity?.attributes['" + ATTRIBUTE_ACTIVE + "'] ?? false) == true";
	private static final String LEVEL_ABOVE_ZERO = "($reference.attributes['" + ATTRIBUTE_LEVEL + "'] ?? 0) > 0";
	private static final Scope[] ALL_SCOPES = Scope.values();
	private static final int PRODUCT_PK = 1;
	private static final int BRAND_PK = 10;
	private static final int SIBLING_BRAND_PK = 11;
	private static final int GROUP_PK = 1;
	/**
	 * The default of the histogram value where the brand has no `sizes`.
	 */
	private static final int DEFAULT_SIZE = 7;
	/**
	 * The default of the second histogram reading the same `sizes`.
	 */
	private static final int SECOND_DEFAULT_SIZE = 9;

	/**
	 * One part of the state a scenario checks: a label and the reader producing the description of the part.
	 *
	 * @param label  the label written in front of the description
	 * @param reader reads the description from the running engine
	 */
	private record Probe(
		@Nonnull String label,
		@Nonnull Function<Evita, String> reader
	) {
	}

	/**
	 * A fact the fixture must establish before the first step, checked once.
	 *
	 * @param name     names the fact in the failure message
	 * @param reader   reads the fact from the running engine
	 * @param expected the description of the fact the fixture must establish
	 */
	private record Premise(
		@Nonnull String name,
		@Nonnull Function<Evita, String> reader,
		@Nonnull String expected
	) {
	}

	/**
	 * One update with the state it must leave behind.
	 *
	 * @param name     the name of the update
	 * @param update   the update, applied in one read-write session
	 * @param expected the expected state after the update, see {@link #describeState}
	 */
	private record Step(
		@Nonnull String name,
		@Nonnull Consumer<EvitaSessionContract> update,
		@Nonnull String expected
	) {
	}

	/**
	 * A fixture stored in the warm-up session, the state it must start from, and the steps applied afterwards.
	 *
	 * @param name                 the name of the scenario
	 * @param setUp                defines the schemas and stores the entities of the fixture
	 * @param probes               the parts of the state checked before the first step and after every step
	 * @param premises             the facts the fixture must establish before the first step
	 * @param restartedBeforeSteps TRUE when the engine is restarted before the first step, so that the first step
	 *                             reads every stored part from the disk rather than from what storing the fixture
	 *                             left in memory
	 * @param before               the expected state before the first step
	 * @param steps                the steps, in the order they are applied
	 */
	private record Scenario(
		@Nonnull String name,
		@Nonnull Consumer<EvitaSessionContract> setUp,
		@Nonnull List<Probe> probes,
		@Nonnull List<Premise> premises,
		boolean restartedBeforeSteps,
		@Nonnull String before,
		@Nonnull List<Step> steps
	) {

		/**
		 * Returns the name of the scenario, which names the case of the parameterized test.
		 *
		 * @return the name
		 */
		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}
	}

	/**
	 * A histogram on the reference of a product to a brand, reading the brand's array `sizes`.
	 *
	 * @param name         the name of the histogram
	 * @param defaultValue the default of the value where the brand has no `sizes`, or `null` for none
	 */
	private record ArrayHistogramDefinition(
		@Nonnull String name,
		@Nullable Integer defaultValue
	) {

		/**
		 * Returns the expression of the histogram's value: the brand's `sizes`, with the default where there is one.
		 *
		 * @return the expression
		 */
		@Nonnull
		String valueExpression() {
			return this.defaultValue == null ? SIZES_VALUE : SIZES_VALUE + " ?? " + this.defaultValue;
		}
	}

	/**
	 * The fixture of a product referencing a subject brand and a sibling brand, with histograms over the brand's array
	 * `sizes`.
	 *
	 * The brand has the filterable array `sizes` of `sizesType`, indexed with `decimalPlaces`, and the filterable flag
	 * `active`. The reference of the product to a brand is not grouped; it is indexed for filtering - and for
	 * partitioning where `partitioned` says so - faceted, and carries `histograms`, which admit a reference only while
	 * the brand is active where `conditional` says so. The product 1 references the sibling brand 11, which is active,
	 * and - where `subjectReferenced` says so - the subject brand 10.
	 *
	 * @param sizesType              the type of `sizes`
	 * @param decimalPlaces          the decimal places `sizes` is indexed with
	 * @param partitioned            TRUE when the reference is indexed for partitioning too
	 * @param conditional            TRUE when the histograms admit a reference only while the brand is active
	 * @param histograms             the histograms of the reference
	 * @param subjectSizes           the `sizes` of the subject brand, or `null` for none
	 * @param subjectSizesByMutation TRUE when the subject's `sizes` are written by an attribute mutation of a second
	 *                               upsert rather than by the entity builder creating the brand
	 * @param subjectActive          the `active` flag of the subject brand
	 * @param subjectReferenced      TRUE when the product references the subject brand from the start
	 * @param siblingSizes           the `sizes` of the sibling brand
	 */
	private record ArrayCatalog(
		@Nonnull Class<? extends Serializable> sizesType,
		int decimalPlaces,
		boolean partitioned,
		boolean conditional,
		@Nonnull List<ArrayHistogramDefinition> histograms,
		@Nullable Serializable[] subjectSizes,
		boolean subjectSizesByMutation,
		boolean subjectActive,
		boolean subjectReferenced,
		@Nonnull Serializable[] siblingSizes
	) {

		/**
		 * Defines the schemas and stores the brands and the product.
		 *
		 * @param session the session of the catalog in warm-up
		 */
		void setUp(@Nonnull EvitaSessionContract session) {
			session.defineEntitySchema(ENTITY_BRAND)
				.withoutGeneratedPrimaryKey()
				.withAttribute(
					ATTRIBUTE_SIZES, this.sizesType,
					whichIs -> whichIs.filterable().indexDecimalPlaces(this.decimalPlaces).nullable()
				)
				.withAttribute(ATTRIBUTE_ACTIVE, Boolean.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceToEntity(
					REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
					whichIs -> {
						if (this.partitioned) {
							whichIs.indexedForFilteringAndPartitioning();
						} else {
							whichIs.indexedForFiltering();
						}
						whichIs.faceted();
						for (final ArrayHistogramDefinition histogram : this.histograms) {
							whichIs.bucketed(histogram.name(), ExpressionFactory.parse(histogram.valueExpression()));
						}
						if (this.conditional) {
							whichIs.bucketedPartially(ExpressionFactory.parse(BRAND_ACTIVE));
						}
					}
				)
				.updateVia(session);
			final EntityBuilder subject = session.createNewEntity(ENTITY_BRAND, BRAND_PK)
				.setAttribute(ATTRIBUTE_ACTIVE, this.subjectActive);
			if (this.subjectSizes != null && !this.subjectSizesByMutation) {
				subject.setAttribute(ATTRIBUTE_SIZES, this.subjectSizes);
			}
			subject.upsertVia(session);
			if (this.subjectSizes != null && this.subjectSizesByMutation) {
				upsertOf(ENTITY_BRAND, BRAND_PK, new UpsertAttributeMutation(ATTRIBUTE_SIZES, this.subjectSizes))
					.accept(session);
			}
			session.createNewEntity(ENTITY_BRAND, SIBLING_BRAND_PK)
				.setAttribute(ATTRIBUTE_SIZES, this.siblingSizes)
				.setAttribute(ATTRIBUTE_ACTIVE, true)
				.upsertVia(session);
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
				.setReference(REFERENCE_BRAND, SIBLING_BRAND_PK);
			if (this.subjectReferenced) {
				product.setReference(REFERENCE_BRAND, BRAND_PK);
			}
			product.upsertVia(session);
		}
	}

	/*
		ARRAY VALUES
	 */

	/**
	 * The scenarios of the histograms over an array of the referenced brand.
	 *
	 * @return arguments of {@link ArrayValues#shouldHoldEveryArrayElementAsAFreshlyStoredOwnerWhenTheBrandChanges}
	 */
	@Nonnull
	static Stream<Arguments> arrayScenarios() {
		final List<Probe> live = List.of(typeIndexHistogram("live", REFERENCE_BRAND, Scope.LIVE, HISTOGRAM_SIZE, null));
		final Step deactivate = new Step("BRAND_DEACTIVATED", brandActive(false), "live {10=[1]}");
		final Step removeSubject = new Step(
			"SUBJECT_REFERENCE_REMOVED", removeBrandReference(BRAND_PK), "live {10=[1]}"
		);
		final Step removeSibling = new Step(
			"SIBLING_REFERENCE_REMOVED", removeBrandReference(SIBLING_BRAND_PK), "live {}"
		);
		final List<Scenario> scenarios = new ArrayList<>(24);

		// the brand admits or withdraws an array; the sibling holds an element of the subject's array
		scenarios.add(scenario(
			"CONTROL_DISTINCT_ARRAY_ELEMENTS_ADMITTED_BY_BRAND",
			conditionalIntegers(new Integer[]{10, 11}, false)::setUp, live, List.of(), "live {10=[1]}",
			new Step("BRAND_ACTIVATED", brandActive(true), "live {10=[1], 11=[1]}"), removeSubject, removeSibling
		));
		scenarios.add(scenario(
			"CONTROL_DISTINCT_ARRAY_ELEMENTS_WITHDRAWN_BY_BRAND",
			conditionalIntegers(new Integer[]{10, 11}, true)::setUp, live, List.of(), "live {10=[1], 11=[1]}",
			deactivate, removeSubject, removeSibling
		));

		// decimals with more places than the attribute indexes are rounded onto its keys; the sibling holds 1.00
		scenarios.add(scenario(
			"CONTROL_DECIMALS_ON_DISTINCT_KEYS_ADMITTED_BY_BRAND",
			conditionalDecimals(new BigDecimal[]{new BigDecimal("1.001"), new BigDecimal("1.104")})::setUp,
			live, List.of(storedSubjectSizes("[1.001, 1.104]")), "live {100=[1]}",
			new Step("BRAND_ACTIVATED", brandActive(true), "live {100=[1], 110=[1]}"),
			new Step("SUBJECT_REFERENCE_REMOVED", removeBrandReference(BRAND_PK), "live {100=[1]}"),
			removeSibling
		));

		// after a restart, one upsert of the brand changes the array and turns the condition on
		scenarios.add(new Scenario(
			"CONTROL_ARRAY_AND_CONDITION_CHANGED_TO_DISTINCT_ELEMENTS_IN_ONE_UPSERT",
			conditionalIntegers(new Integer[]{10, 11}, false)::setUp, live, List.of(), true, "live {10=[1]}",
			List.of(
				new Step(
					"SIZES_CHANGED_AND_BRAND_ACTIVATED", sizesAndBrandActivated(new Integer[]{10, 12}),
					"live {10=[1], 12=[1]}"
				),
				removeSubject, removeSibling
			)
		));

		// one session upserts the brand twice: an English change, then a first German array and the activation
		final List<Probe> localized = List.of(
			typeIndexHistogram("en", REFERENCE_BRAND, Scope.LIVE, HISTOGRAM_SIZE, Locale.ENGLISH),
			typeIndexHistogram("de", REFERENCE_BRAND, Scope.LIVE, HISTOGRAM_SIZE, Locale.GERMAN)
		);
		final Step removeLocalizedSubject = new Step(
			"SUBJECT_REFERENCE_REMOVED", removeBrandReference(BRAND_PK), "en {6=[1]} | de {10=[1]}"
		);
		final Step removeLocalizedSibling = new Step(
			"SIBLING_REFERENCE_REMOVED", removeBrandReference(SIBLING_BRAND_PK), "en {} | de {}"
		);
		scenarios.add(scenario(
			"CONTROL_ARRAY_GAINS_LOCALE_WITH_DISTINCT_ELEMENTS_AFTER_AN_EARLIER_WRITE_IN_ONE_TRANSACTION",
			ReferenceHistogramDispatchParityFunctionalTest::setUpLocalizedArrayCatalog, localized, List.of(),
			"en {6=[1]} | de {10=[1]}",
			new Step(
				"TWO_BRAND_UPSERTS_IN_ONE_SESSION", twoBrandUpsertsGainingGerman(new Integer[]{10, 11}),
				"en {6=[1]} | de {10=[1], 11=[1]}"
			),
			removeLocalizedSubject, removeLocalizedSibling
		));

		// the condition reads the group of the reference; the group's deactivation withdraws every brand in it
		final List<Probe> group = List.of(groupStoreHistogram("group", HISTOGRAM_SIZE));
		final Step deactivateGroup = new Step(
			"GROUP_DEACTIVATED",
			upsertOf(ENTITY_BRAND_GROUP, GROUP_PK, new UpsertAttributeMutation(ATTRIBUTE_ACTIVE, false)),
			"group {}"
		);
		final Step removeGroupedSubject = new Step(
			"SUBJECT_REFERENCE_REMOVED", removeBrandReference(BRAND_PK), "group {}"
		);
		final Step removeGroupedSibling = new Step(
			"SIBLING_REFERENCE_REMOVED", removeBrandReference(SIBLING_BRAND_PK), "group {}"
		);
		scenarios.add(scenario(
			"CONTROL_GROUP_CONDITION_WITHDRAWS_DISTINCT_ELEMENTS",
			session -> setUpGroupArrayCatalog(session, new Integer[]{10, 11}), group, List.of(),
			"group {10=[1], 11=[1]}",
			deactivateGroup, removeGroupedSubject, removeGroupedSibling
		));

		// an empty array written by a mutation, with a default: stored as an empty array, it holds no value, so the
		// subject contributes nothing while the sibling holds the default
		final Premise emptyArrayStored = storedSubjectSizes("[]");
		final Step removeSiblingOfEmptyArray = new Step(
			"SIBLING_REFERENCE_REMOVED", removeBrandReference(SIBLING_BRAND_PK), "live {}"
		);
		final Step removeSubjectOfEmptyArray = new Step(
			"SUBJECT_REFERENCE_REMOVED", removeBrandReference(BRAND_PK), "live {}"
		);
		scenarios.add(scenario(
			"CONTROL_EMPTY_ARRAY_WRITTEN_BY_MUTATION_OF_ACTIVE_BRAND_REFERENCED",
			conditionalEmptyArray(true, false)::setUp, live, List.of(emptyArrayStored), "live {7=[1]}",
			new Step("SUBJECT_REFERENCE_INSERTED", insertBrandReference(BRAND_PK), "live {7=[1]}"),
			removeSiblingOfEmptyArray, removeSubjectOfEmptyArray
		));

		// two histograms read the same optional array with different defaults; the subject has no array at all
		final List<Probe> twoDefaults = List.of(
			typeIndexHistogram("default 7", REFERENCE_BRAND, Scope.LIVE, HISTOGRAM_SIZE, null),
			typeIndexHistogram("default 9", REFERENCE_BRAND, Scope.LIVE, HISTOGRAM_SECOND_SIZE, null)
		);
		scenarios.add(scenario(
			"TWO_DEFAULTS_ON_ONE_OPTIONAL_ARRAY",
			new ArrayCatalog(
				Integer[].class, 0, false, true,
				List.of(
					new ArrayHistogramDefinition(HISTOGRAM_SIZE, DEFAULT_SIZE),
					new ArrayHistogramDefinition(HISTOGRAM_SECOND_SIZE, SECOND_DEFAULT_SIZE)
				),
				null, false, false, true, new Integer[]{8}
			)::setUp,
			twoDefaults, List.of(storedSubjectSizes("null")), "default 7 {8=[1]} | default 9 {8=[1]}",
			new Step("BRAND_ACTIVATED", brandActive(true), "default 7 {7=[1], 8=[1]} | default 9 {8=[1], 9=[1]}"),
			new Step(
				"SUBJECT_REFERENCE_REMOVED", removeBrandReference(BRAND_PK), "default 7 {8=[1]} | default 9 {8=[1]}"
			),
			new Step(
				"SIBLING_REFERENCE_REMOVED", removeBrandReference(SIBLING_BRAND_PK), "default 7 {} | default 9 {}"
			)
		));

		// an unconditional array repeating an element is withdrawn as often as it was added when it is replaced; the
		// sibling holds the repeated element, so a withdrawal too few leaves 10 after the last removal and a withdrawal
		// too many takes the sibling's 10 at the replacement
		scenarios.add(scenario(
			"REPEATED_ELEMENTS_REPLACED",
			new ArrayCatalog(
				Integer[].class, 0, false, false, List.of(new ArrayHistogramDefinition(HISTOGRAM_SIZE, null)),
				new Integer[]{10, 10}, false, true, true, new Integer[]{10}
			)::setUp,
			live, List.of(), "live {10=[1]}",
			new Step(
				"BRAND_SIZES_REPLACED",
				upsertOf(ENTITY_BRAND, BRAND_PK, new UpsertAttributeMutation(ATTRIBUTE_SIZES, new Integer[]{20})),
				"live {10=[1], 20=[1]}"
			),
			new Step("SUBJECT_REFERENCE_REMOVED", removeBrandReference(BRAND_PK), "live {10=[1]}"),
			removeSibling
		));

		// the owner's own array becomes a repeated element, which the cross-entity path writes, and is replaced again;
		// no sibling of the owner holds 10, so this checks that the replacement leaves the new value and nothing of the
		// old one, not how many units of the repeated element were written
		final List<Probe> self = List.of(
			typeIndexHistogram("live", REFERENCE_RELATED, Scope.LIVE, HISTOGRAM_SIZE, null),
			typeIndexHistogram("archived", REFERENCE_RELATED, Scope.ARCHIVED, HISTOGRAM_SIZE, null)
		);
		scenarios.add(scenario(
			"OWN_SIZES_REPEATED_THEN_REPLACED",
			session -> setUpSelfReferenceArrayCatalog(session, new Integer[]{30}),
			self, List.of(), "live {9=[1], 20=[3], 30=[1]} | archived {9=[5]}",
			new Step(
				"OWN_SIZES_REPEATED",
				upsertOf(
					ENTITY_PRODUCT, PRODUCT_PK, new UpsertAttributeMutation(ATTRIBUTE_SIZES, new Integer[]{10, 10})
				),
				"live {9=[1], 10=[1], 20=[3]} | archived {9=[5]}"
			),
			new Step(
				"OWN_SIZES_REPLACED",
				upsertOf(ENTITY_PRODUCT, PRODUCT_PK, new UpsertAttributeMutation(ATTRIBUTE_SIZES, new Integer[]{20})),
				"live {9=[1], 20=[1, 3]} | archived {9=[5]}"
			),
			new Step(
				"SELF_REFERENCE_REMOVED",
				upsertOf(
					ENTITY_PRODUCT, PRODUCT_PK,
					new RemoveReferenceMutation(new ReferenceKey(REFERENCE_RELATED, PRODUCT_PK))
				),
				"live {9=[1], 20=[3]} | archived {9=[5]}"
			),
			new Step(
				"LIVE_SIBLING_REMOVED",
				upsertOf(
					ENTITY_PRODUCT, PRODUCT_PK, new RemoveReferenceMutation(new ReferenceKey(REFERENCE_RELATED, 6))
				),
				"live {20=[3]} | archived {9=[5]}"
			)
		));
		return inBothStates(scenarios);
	}

	/**
	 * Creates the fixture of a conditional histogram without a default over the brand's integer array, on a reference
	 * indexed for partitioning; the subject is referenced and the sibling holds `[10]`.
	 *
	 * @param subjectSizes  the `sizes` of the subject brand
	 * @param subjectActive the `active` flag of the subject brand
	 * @return the fixture
	 */
	@Nonnull
	private static ArrayCatalog conditionalIntegers(@Nonnull Integer[] subjectSizes, boolean subjectActive) {
		return new ArrayCatalog(
			Integer[].class, 0, true, true, List.of(new ArrayHistogramDefinition(HISTOGRAM_SIZE, null)),
			subjectSizes, false, subjectActive, true, new Integer[]{10}
		);
	}

	/**
	 * Creates the fixture of a conditional histogram without a default over the brand's decimal array, indexed with two
	 * decimal places, on a reference indexed for partitioning; the subject is inactive and referenced and the sibling
	 * holds `[1.00]`.
	 *
	 * @param subjectSizes the `sizes` of the subject brand
	 * @return the fixture
	 */
	@Nonnull
	private static ArrayCatalog conditionalDecimals(@Nonnull BigDecimal[] subjectSizes) {
		return new ArrayCatalog(
			BigDecimal[].class, 2, true, true, List.of(new ArrayHistogramDefinition(HISTOGRAM_SIZE, null)),
			subjectSizes, false, false, true, new BigDecimal[]{new BigDecimal("1.00")}
		);
	}

	/**
	 * Creates the fixture of a conditional histogram with the default 7 over the brand's integer array, where the
	 * subject's array is empty and written by an attribute mutation; the sibling holds `[7]`.
	 *
	 * @param subjectActive     the `active` flag of the subject brand
	 * @param subjectReferenced TRUE when the product references the subject brand from the start
	 * @return the fixture
	 */
	@Nonnull
	private static ArrayCatalog conditionalEmptyArray(boolean subjectActive, boolean subjectReferenced) {
		return new ArrayCatalog(
			Integer[].class, 0, false, true, List.of(new ArrayHistogramDefinition(HISTOGRAM_SIZE, DEFAULT_SIZE)),
			new Integer[0], true, subjectActive, subjectReferenced, new Integer[]{DEFAULT_SIZE}
		);
	}

	/**
	 * Defines the schemas of the localized array fixture and stores the brands and the product.
	 *
	 * The brand has the locales English and German, the localized filterable integer array `sizes` and the filterable
	 * flag `active`. The reference of the product to a brand is indexed for filtering and partitioning, faceted, and
	 * carries the histogram of the brand's localized `sizes` without a default, admitted while the brand is active.
	 * The subject brand 10 has `[5]` in English only and is inactive; the sibling brand 11 has `[6]` in English and
	 * `[10]` in German and is active. The product 1 references both.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void setUpLocalizedArrayCatalog(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTRIBUTE_SIZES, Integer[].class, whichIs -> whichIs.localized().filterable().nullable())
			.withAttribute(ATTRIBUTE_ACTIVE, Boolean.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.bucketed(HISTOGRAM_SIZE, ExpressionFactory.parse(LOCALIZED_SIZES_VALUE))
					.bucketedPartially(ExpressionFactory.parse(BRAND_ACTIVE))
			)
			.updateVia(session);
		session.createNewEntity(ENTITY_BRAND, BRAND_PK)
			.setAttribute(ATTRIBUTE_SIZES, Locale.ENGLISH, new Integer[]{5})
			.setAttribute(ATTRIBUTE_ACTIVE, false)
			.upsertVia(session);
		session.createNewEntity(ENTITY_BRAND, SIBLING_BRAND_PK)
			.setAttribute(ATTRIBUTE_SIZES, Locale.ENGLISH, new Integer[]{6})
			.setAttribute(ATTRIBUTE_SIZES, Locale.GERMAN, new Integer[]{10})
			.setAttribute(ATTRIBUTE_ACTIVE, true)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(REFERENCE_BRAND, BRAND_PK)
			.setReference(REFERENCE_BRAND, SIBLING_BRAND_PK)
			.upsertVia(session);
	}

	/**
	 * Defines the schemas of the group fixture and stores the group, the brands and the product.
	 *
	 * The brand group has the filterable flag `active`, the brand the filterable integer array `sizes`. The reference
	 * of the product to a brand is grouped by a brand group, indexed for filtering with both its components, faceted,
	 * and carries the histogram of the brand's `sizes` without a default, admitted while the group is active. The
	 * group 1 is active. The product 1 references the subject brand 10 of `subjectSizes` and the sibling brand 11 of
	 * `[10]`, both in the group 1.
	 *
	 * @param session      the session of the catalog in warm-up
	 * @param subjectSizes the `sizes` of the subject brand
	 */
	private static void setUpGroupArrayCatalog(@Nonnull EvitaSessionContract session, @Nonnull Integer[] subjectSizes) {
		session.defineEntitySchema(ENTITY_BRAND_GROUP)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_ACTIVE, Boolean.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_SIZES, Integer[].class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withGroupTypeRelatedToEntity(ENTITY_BRAND_GROUP)
					.indexedForFiltering()
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.faceted()
					.bucketed(HISTOGRAM_SIZE, ExpressionFactory.parse(SIZES_VALUE))
					.bucketedPartially(ExpressionFactory.parse(GROUP_ACTIVE))
			)
			.updateVia(session);
		session.createNewEntity(ENTITY_BRAND_GROUP, GROUP_PK)
			.setAttribute(ATTRIBUTE_ACTIVE, true)
			.upsertVia(session);
		session.createNewEntity(ENTITY_BRAND, BRAND_PK)
			.setAttribute(ATTRIBUTE_SIZES, subjectSizes)
			.upsertVia(session);
		session.createNewEntity(ENTITY_BRAND, SIBLING_BRAND_PK)
			.setAttribute(ATTRIBUTE_SIZES, new Integer[]{10})
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setGroup(ENTITY_BRAND_GROUP, GROUP_PK))
			.setReference(REFERENCE_BRAND, SIBLING_BRAND_PK, whichIs -> whichIs.setGroup(ENTITY_BRAND_GROUP, GROUP_PK))
			.upsertVia(session);
	}

	/**
	 * Defines the schema of a product referencing products, whose histogram reads the referenced product's integer
	 * array, and stores the products.
	 *
	 * The product has the integer array `sizes`, filterable in both scopes. Its reference to products carries the
	 * filterable `level` and, in both scopes, the histogram of the referenced product's `sizes`, admitted while the
	 * level is above zero. The live product 1 of sizes `ownSizes` references itself, the archived product 2 of sizes
	 * `[9]` and the live product 6 of sizes `[9]`; the live anchor 3 references the live product 4 of sizes `[20]`, and
	 * the archived anchor 5 references the archived product 2. Every reference has the level 1.
	 *
	 * @param session  the session of the catalog in warm-up
	 * @param ownSizes the sizes of the product 1
	 */
	private static void setUpSelfReferenceArrayCatalog(
		@Nonnull EvitaSessionContract session,
		@Nonnull Integer[] ownSizes
	) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withAttribute(
				ATTRIBUTE_SIZES, Integer[].class, whichIs -> whichIs.filterableInScope(ALL_SCOPES).nullable()
			)
			.withReferenceToEntity(
				REFERENCE_RELATED, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					whichIs.indexedForFilteringInScope(ALL_SCOPES)
						.facetedInScope(ALL_SCOPES)
						.withAttribute(
							ATTRIBUTE_LEVEL, Integer.class, thatIs -> thatIs.filterableInScope(ALL_SCOPES).nullable()
						);
					for (final Scope scope : ALL_SCOPES) {
						whichIs.bucketedInScope(scope, HISTOGRAM_SIZE, ExpressionFactory.parse(SIZES_VALUE))
							.bucketedPartiallyInScope(scope, ExpressionFactory.parse(LEVEL_ABOVE_ZERO));
					}
				}
			)
			.updateVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 2).setScope(Scope.ARCHIVED)
			.setAttribute(ATTRIBUTE_SIZES, new Integer[]{9}).upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 6).setAttribute(ATTRIBUTE_SIZES, new Integer[]{9}).upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 4).setAttribute(ATTRIBUTE_SIZES, new Integer[]{20}).upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 3)
			.setReference(REFERENCE_RELATED, 4, whichIs -> whichIs.setAttribute(ATTRIBUTE_LEVEL, 1))
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 5).setScope(Scope.ARCHIVED)
			.setReference(REFERENCE_RELATED, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_LEVEL, 1))
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK).setAttribute(ATTRIBUTE_SIZES, ownSizes).upsertVia(session);
		session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent()).orElseThrow().openForWrite()
			.setReference(REFERENCE_RELATED, PRODUCT_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_LEVEL, 1))
			.setReference(REFERENCE_RELATED, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_LEVEL, 1))
			.setReference(REFERENCE_RELATED, 6, whichIs -> whichIs.setAttribute(ATTRIBUTE_LEVEL, 1))
			.upsertVia(session);
	}

	/*
		STEPS, PROBES AND PREMISES
	 */

	/**
	 * Creates the update setting the subject brand's `active` flag.
	 *
	 * @param active the new flag
	 * @return the update
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> brandActive(boolean active) {
		return upsertOf(ENTITY_BRAND, BRAND_PK, new UpsertAttributeMutation(ATTRIBUTE_ACTIVE, active));
	}

	/**
	 * Creates the update replacing the subject brand's `sizes` and activating it in one upsert.
	 *
	 * @param sizes the new `sizes`
	 * @return the update
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> sizesAndBrandActivated(@Nonnull Integer[] sizes) {
		return upsertOf(
			ENTITY_BRAND, BRAND_PK,
			new UpsertAttributeMutation(ATTRIBUTE_SIZES, sizes),
			new UpsertAttributeMutation(ATTRIBUTE_ACTIVE, true)
		);
	}

	/**
	 * Creates the update upserting the subject brand of the localized fixture twice in one session: the first upsert
	 * changes its English `sizes` to `[6]`, the second gives it its first German `sizes` and activates it.
	 *
	 * @param germanSizes the German `sizes` of the second upsert
	 * @return the update
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> twoBrandUpsertsGainingGerman(@Nonnull Integer[] germanSizes) {
		return session -> {
			upsertOf(
				ENTITY_BRAND, BRAND_PK, new UpsertAttributeMutation(ATTRIBUTE_SIZES, Locale.ENGLISH, new Integer[]{6})
			).accept(session);
			upsertOf(
				ENTITY_BRAND, BRAND_PK,
				new UpsertAttributeMutation(ATTRIBUTE_SIZES, Locale.GERMAN, germanSizes),
				new UpsertAttributeMutation(ATTRIBUTE_ACTIVE, true)
			).accept(session);
		};
	}

	/**
	 * Creates the update removing the product's reference to a brand.
	 *
	 * @param brandPk the primary key of the referenced brand
	 * @return the update
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> removeBrandReference(int brandPk) {
		return upsertOf(
			ENTITY_PRODUCT, PRODUCT_PK, new RemoveReferenceMutation(new ReferenceKey(REFERENCE_BRAND, brandPk))
		);
	}

	/**
	 * Creates the update adding the product's reference to a brand through the entity builder.
	 *
	 * @param brandPk the primary key of the referenced brand
	 * @return the update
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> insertBrandReference(int brandPk) {
		return session -> session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(REFERENCE_BRAND, brandPk)
			.upsertVia(session);
	}

	/**
	 * Creates one upsert of an existing entity made of `mutations`, applied in the order given.
	 *
	 * @param entityType the entity type
	 * @param primaryKey the primary key
	 * @param mutations  the local mutations of the upsert
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> upsertOf(
		@Nonnull String entityType,
		int primaryKey,
		@Nonnull LocalMutation<?, ?>... mutations
	) {
		return session -> session.upsertEntity(
			new EntityUpsertMutation(entityType, primaryKey, EntityExistence.MUST_EXIST, mutations)
		);
	}

	/**
	 * Creates the probe of a histogram in the referenced-type index of a product's reference without a group type.
	 *
	 * @param label         the label of the probe
	 * @param referenceName the name of the reference
	 * @param scope         the scope of the index
	 * @param histogramName the name of the histogram
	 * @param locale        the locale of a localized histogram, or `null`
	 * @return the probe
	 */
	@Nonnull
	private static Probe typeIndexHistogram(
		@Nonnull String label,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull String histogramName,
		@Nullable Locale locale
	) {
		return new Probe(
			label,
			evita -> describeFilterIndex(
				IndexingTestSupport.getReferencedEntityTypeIndex(product(evita), scope, referenceName),
				histogramName, locale
			)
		);
	}

	/**
	 * Creates the probe of a histogram in the histogram store of the live group 1 of the product's reference to a
	 * brand.
	 *
	 * @param label         the label of the probe
	 * @param histogramName the name of the histogram
	 * @return the probe
	 */
	@Nonnull
	private static Probe groupStoreHistogram(@Nonnull String label, @Nonnull String histogramName) {
		return new Probe(
			label,
			evita -> describeFilterIndex(
				IndexingTestSupport.getGroupHistogramStore(product(evita), Scope.LIVE, REFERENCE_BRAND, GROUP_PK),
				histogramName, null
			)
		);
	}

	/**
	 * Creates the premise of what the live subject brand stores as its non-localized `sizes`, read through the public
	 * API.
	 *
	 * @param expected the stored value, element by element, or `null` when the brand has no `sizes`
	 * @return the premise
	 */
	@Nonnull
	private static Premise storedSubjectSizes(@Nonnull String expected) {
		return new Premise(
			"the subject brand stores its sizes as " + expected,
			evita -> evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(ENTITY_BRAND, BRAND_PK, entityFetchAllContent())
						.map(entity -> {
							final Serializable value = entity.getAttribute(ATTRIBUTE_SIZES);
							return value instanceof Object[] array ? Arrays.toString(array) : String.valueOf(value);
						})
						.orElse("absent");
				}
			),
			expected
		);
	}

	/*
		THE RUN
	 */

	/**
	 * Creates a scenario that applies its first step to the engine that stored its fixture.
	 *
	 * @param name     the name
	 * @param setUp    the fixture
	 * @param probes   the probes
	 * @param premises the premises
	 * @param before   the state before the first step
	 * @param steps    the steps
	 * @return the scenario
	 */
	@Nonnull
	private static Scenario scenario(
		@Nonnull String name,
		@Nonnull Consumer<EvitaSessionContract> setUp,
		@Nonnull List<Probe> probes,
		@Nonnull List<Premise> premises,
		@Nonnull String before,
		@Nonnull Step... steps
	) {
		return new Scenario(name, setUp, probes, premises, false, before, List.of(steps));
	}

	/**
	 * Expands every scenario to both catalog states.
	 *
	 * @param scenarios the scenarios
	 * @return the arguments of a parameterized test taking the state and the scenario
	 */
	@Nonnull
	private static Stream<Arguments> inBothStates(@Nonnull List<Scenario> scenarios) {
		final List<Arguments> result = new ArrayList<>(scenarios.size() * 2);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final Scenario scenario : scenarios) {
				result.add(Arguments.of(state, scenario));
			}
		}
		return result.stream();
	}

	/**
	 * Stores the fixture of the scenario, moves the catalog to `state`, checks the premises and the starting state,
	 * applies every step and checks the state after it - in memory and after a restart - and fails with every check
	 * that did not hold. An update that throws is a failed check carrying the exception and its message; every
	 * scenario expects its updates to be accepted.
	 *
	 * @param state    the state the catalog is in when the steps are applied
	 * @param scenario the scenario
	 */
	private void runScenario(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		final TestPaths paths = createTestPaths(PREFIX + "_" + state + "_" + scenario.name());
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final List<Executable> checks = new ArrayList<>(32);
		Evita evita = new Evita(configuration);
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				scenario.setUp().accept(session);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			for (final Premise premise : scenario.premises()) {
				recordPremise(evita, scenario, premise, checks);
			}
			recordState(evita, scenario, "before the first step", scenario.before(), checks);
			if (scenario.restartedBeforeSteps()) {
				evita = restart(evita, configuration);
				recordState(evita, scenario, "before the first step and a restart", scenario.before(), checks);
			}
			int stepNumber = 0;
			for (final Step step : scenario.steps()) {
				stepNumber++;
				final String stage = "after step " + stepNumber + " " + step.name();
				applyUpdate(evita, scenario + " " + stage, checks, step.update());
				recordState(evita, scenario, stage, step.expected(), checks);
				evita = restart(evita, configuration);
				recordState(evita, scenario, stage + " and a restart", step.expected(), checks);
			}
		} catch (RuntimeException ex) {
			checks.add(() -> fail(scenario + ": the scenario threw " + ex.getClass().getSimpleName() + ": " +
				ex.getMessage(), ex));
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Applies `update` in its own read-write session and records a failed check when it throws.
	 *
	 * @param evita  the engine
	 * @param label  names the update in the failure message
	 * @param checks the collected checks
	 * @param update the update
	 */
	private static void applyUpdate(
		@Nonnull Evita evita,
		@Nonnull String label,
		@Nonnull List<Executable> checks,
		@Nonnull Consumer<EvitaSessionContract> update
	) {
		try {
			evita.updateCatalog(TEST_CATALOG, update);
		} catch (RuntimeException ex) {
			checks.add(() -> fail(label + ": the update threw " + ex.getClass().getSimpleName() + ": " +
				ex.getMessage(), ex));
		}
	}

	/**
	 * Closes the engine and starts a new one on the stored data.
	 *
	 * @param evita         the running engine
	 * @param configuration the configuration of the engine
	 * @return the new engine, fully initialized
	 */
	@Nonnull
	private static Evita restart(@Nonnull Evita evita, @Nonnull EvitaConfiguration configuration) {
		evita.close();
		final Evita restarted = new Evita(configuration);
		restarted.waitUntilFullyInitialized();
		return restarted;
	}

	/**
	 * Records the check of one premise of the scenario.
	 *
	 * @param evita    the engine
	 * @param scenario the scenario
	 * @param premise  the premise
	 * @param checks   the collected checks
	 */
	private static void recordPremise(
		@Nonnull Evita evita,
		@Nonnull Scenario scenario,
		@Nonnull Premise premise,
		@Nonnull List<Executable> checks
	) {
		final String stage = scenario + " premise: " + premise.name();
		try {
			final String actual = premise.reader().apply(evita);
			checks.add(() -> assertEquals(premise.expected(), actual, stage));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": reading the premise threw " + ex, ex));
		}
	}

	/**
	 * Records the check that the catalog is in the expected state at the moment `stage` names.
	 *
	 * @param evita    the engine
	 * @param scenario the scenario
	 * @param stage    names the moment of the check
	 * @param expected the expected state
	 * @param checks   the collected checks
	 */
	private static void recordState(
		@Nonnull Evita evita,
		@Nonnull Scenario scenario,
		@Nonnull String stage,
		@Nonnull String expected,
		@Nonnull List<Executable> checks
	) {
		final String label = scenario + " " + stage;
		try {
			final String actual = describeState(evita, scenario.probes());
			checks.add(() -> assertEquals(expected, actual, label));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(label + ": reading the state threw " + ex, ex));
		}
	}

	/**
	 * Describes the state as the probes read it: `label description` per probe, joined by ` | `.
	 *
	 * @param evita  the engine
	 * @param probes the probes
	 * @return the description
	 */
	@Nonnull
	private static String describeState(@Nonnull Evita evita, @Nonnull List<Probe> probes) {
		final StringBuilder result = new StringBuilder(64 * probes.size());
		for (final Probe probe : probes) {
			if (!result.isEmpty()) {
				result.append(" | ");
			}
			result.append(probe.label()).append(' ').append(probe.reader().apply(evita));
		}
		return result.toString();
	}

	/**
	 * Returns the product collection of the running engine.
	 *
	 * @param evita the engine
	 * @return the collection
	 */
	@Nonnull
	private static EntityCollectionContract product(@Nonnull Evita evita) {
		return evita.getCatalogInstance(TEST_CATALOG)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
	}

	/**
	 * Describes the values a histogram holds in an index and the owners holding each of them.
	 *
	 * @param index         the index, or `null` when it does not exist
	 * @param histogramName the name of the histogram
	 * @param locale        the locale of a localized histogram, or `null`
	 * @return the description, `{}` when the index or the histogram holds nothing
	 */
	@Nonnull
	private static String describeFilterIndex(
		@Nullable EntityIndex index,
		@Nonnull String histogramName,
		@Nullable Locale locale
	) {
		if (!(index instanceof HistogramCapableEntityIndex histogramIndex)) {
			return "{}";
		}
		final FilterIndex filterIndex = histogramIndex.getHistogramFilterIndex(histogramName, locale);
		if (filterIndex == null) {
			return "{}";
		}
		final ValueToRecord[] buckets = filterIndex.getHistogramOfAllRecords().getBuckets();
		final StringBuilder result = new StringBuilder(16 + buckets.length * 8);
		result.append('{');
		boolean first = true;
		for (final ValueToRecord bucket : buckets) {
			final int[] owners = bucket.getRecordIds().getArray();
			if (owners.length > 0) {
				if (!first) {
					result.append(", ");
				}
				first = false;
				result.append(bucket.getValue()).append('=').append(Arrays.toString(owners));
			}
		}
		return result.append('}').toString();
	}

	/**
	 * Histograms reading an array of the referenced brand.
	 */
	@Nested
	@DisplayName("An array value of the referenced brand")
	class ArrayValues {

		/**
		 * Checks the histogram after the brand's array or the condition admitting it changes, against what a freshly
		 * stored owner would hold.
		 *
		 * @param state    the state the catalog is in when the steps are applied
		 * @param scenario the scenario
		 */
		@ParameterizedTest(name = "{0} {1}")
		@MethodSource("io.evitadb.api.functional.ReferenceHistogramDispatchParityFunctionalTest#arrayScenarios")
		@DisplayName("holds every element of the brand's array as often as a freshly stored owner holds it")
		void shouldHoldEveryArrayElementAsAFreshlyStoredOwnerWhenTheBrandChanges(
			@Nonnull CatalogState state,
			@Nonnull Scenario scenario
		) {
			runScenario(state, scenario);
		}

	}

}
