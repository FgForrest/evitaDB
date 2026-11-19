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
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
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
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the locale set a histogram over a localized reference attribute enumerates its values over: the
 * locales the reference holds a value in, and - when the value has a `??` default - every locale of the owner entity,
 * associated data included, as the entity has them at the moment the value is added or withdrawn.
 *
 * Three groups of scenarios, each with its own schema:
 *
 * - {@link ValueWrittenByUpsertGivingItsLocale}: a localized value written by the very upsert that gives the product
 *   its locale, with controls that give the locale in an earlier upsert;
 * - {@link OwnerLocaleSetChange}: a defaulted value whose owner gains or loses a locale without the reference being
 *   touched, read in the type index or group histogram store, and in the ungrouped reference summary;
 * - {@link EnumeratedLocaleSet}: the owner's locale set changed by every kind of mutation that can change it - an
 *   entity attribute, a reference attribute, associated data, a reference removal, a scope change, a mutation
 *   propagated from a reflected reference - on one reference, on a sibling of it and on a reference not indexed at
 *   all; a reference holding no share in any locale, which contributes the default alone; and, as controls,
 *   defaults the implicit mutations generate beside an explicit change of the locale set, and an applied delta,
 *   which changes a value and no locale.
 *
 * Every scenario runs with the catalog in warm-up and alive, checks the histograms after a restart too, and ends
 * with a follow-up change of the same references. Assertions on the histogram contents detect missing or stale values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A localized reference histogram over the locale set of its owner")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class ReferenceHistogramLocaleSetFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REFERENCE_BRAND = "brand";
	private static final String REFERENCE_TAG = "tag";
	private static final String REFERENCE_PRODUCTS = "products";
	private static final String ATTRIBUTE_SHARE = "share";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ASSOCIATED_DATA_NOTE = "note";
	private static final String HISTOGRAM_SHARE = "shareHistogram";
	private static final String OWNER_HISTOGRAM = "localizedHistogram";
	private static final Scope[] ALL_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final Locale[] LOCALES = {Locale.ENGLISH, Locale.GERMAN};
	private static final int PRODUCT_PK = 1;
	private static final int LIVE_ANCHOR_PK = 100;
	private static final int ARCHIVED_ANCHOR_PK = 101;
	private static final int BRAND_PK = 10;
	private static final int SIBLING_BRAND_PK = 11;
	private static final int FIRST_ANCHOR_BRAND_PK = 12;
	private static final int SECOND_ANCHOR_BRAND_PK = 13;
	private static final int ANCHOR_VALUE = 10;
	private static final int SECOND_ANCHOR_VALUE = 20;
	private static final int INSIDE_ANCHOR_RANGE = 15;
	private static final int DEFAULT_SHARE = 5;
	private static final int BUCKET_COUNT = 10;
	/**
	 * The description of a histogram holding only the values of the anchor product.
	 */
	private static final String ANCHORS_ONLY = range(ANCHOR_VALUE, SECOND_ANCHOR_VALUE);
	/**
	 * The description of a histogram that does not exist.
	 */
	private static final String NO_HISTOGRAM = "no histogram";
	private static final String LOCALIZED_SHARE_VALUE = "$reference.localizedAttributes['" + ATTRIBUTE_SHARE + "']";
	private static final ReferenceKey SUBJECT = new ReferenceKey(REFERENCE_BRAND, BRAND_PK);
	/**
	 * No value of the product in a locale of a scope.
	 */
	private static final int[] NONE = new int[0];

	/**
	 * The scenarios of a locale given to the product by the upsert that writes the localized share, and their
	 * controls - the eight base scenarios.
	 *
	 * @return arguments of {@link ValueWrittenByUpsertGivingItsLocale#shouldHoldLocalizedValueWhenUpsertAddsItsLocale}
	 */
	@Nonnull
	static Stream<Arguments> localeAddedInSameUpsertScenarios() {
		final List<PreBatchScenario> scenarios = new ArrayList<>(4);
		final List<PreBatchExpectation> anchorsOnly = List.of(
			new PreBatchExpectation(Scope.LIVE, Locale.ENGLISH, ANCHORS_ONLY),
			new PreBatchExpectation(Scope.LIVE, Locale.GERMAN, ANCHORS_ONLY),
			new PreBatchExpectation(Scope.ARCHIVED, Locale.ENGLISH, ANCHORS_ONLY),
			new PreBatchExpectation(Scope.ARCHIVED, Locale.GERMAN, ANCHORS_ONLY)
		);

		// a new product: its German title and its German share come in the upsert that creates it
		final List<PreBatchExpectation> newProductStored = List.of(
			new PreBatchExpectation(Scope.LIVE, Locale.ENGLISH, ANCHORS_ONLY),
			new PreBatchExpectation(Scope.LIVE, Locale.GERMAN, range(2, SECOND_ANCHOR_VALUE)),
			new PreBatchExpectation(Scope.ARCHIVED, Locale.ENGLISH, ANCHORS_ONLY),
			new PreBatchExpectation(Scope.ARCHIVED, Locale.GERMAN, ANCHORS_ONLY)
		);
		final Consumer<EvitaSessionContract> moveGermanShare =
			upsertOf(ENTITY_PRODUCT, PRODUCT_PK, localizedShare(Locale.GERMAN, INSIDE_ANCHOR_RANGE));
		scenarios.add(new PreBatchScenario(
			"NEW_PRODUCT_LOCALE_AND_SHARE_IN_ONE_UPSERT", null, anchorsOnly,
			session -> newProductWithGermanTitle(session)
				.setReference(
					REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, Locale.GERMAN, 2)
				)
				.upsertVia(session),
			newProductStored, moveGermanShare, anchorsOnly
		));
		// the control: the product is created with its German title, its reference comes in a second upsert
		scenarios.add(new PreBatchScenario(
			"CONTROL_NEW_PRODUCT_LOCALE_THEN_SHARE", null, anchorsOnly,
			session -> {
				newProductWithGermanTitle(session).upsertVia(session);
				session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(
						REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, Locale.GERMAN, 2)
					)
					.upsertVia(session);
			},
			newProductStored, moveGermanShare, anchorsOnly
		));

		// an existing English product: one upsert adds the German title and share, archives the product and then
		// changes the German share once more
		final Consumer<EvitaSessionContract> englishProduct = session -> {
			session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
				.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "product")
				.upsertVia(session);
			session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, 1)
				)
				.upsertVia(session);
		};
		final List<PreBatchExpectation> englishBefore = List.of(
			new PreBatchExpectation(Scope.LIVE, Locale.ENGLISH, range(1, SECOND_ANCHOR_VALUE)),
			new PreBatchExpectation(Scope.LIVE, Locale.GERMAN, ANCHORS_ONLY),
			new PreBatchExpectation(Scope.ARCHIVED, Locale.ENGLISH, ANCHORS_ONLY),
			new PreBatchExpectation(Scope.ARCHIVED, Locale.GERMAN, ANCHORS_ONLY)
		);
		final List<PreBatchExpectation> archivedWithFinalShares = List.of(
			new PreBatchExpectation(Scope.LIVE, Locale.ENGLISH, ANCHORS_ONLY),
			new PreBatchExpectation(Scope.LIVE, Locale.GERMAN, ANCHORS_ONLY),
			new PreBatchExpectation(Scope.ARCHIVED, Locale.ENGLISH, range(1, SECOND_ANCHOR_VALUE)),
			new PreBatchExpectation(Scope.ARCHIVED, Locale.GERMAN, range(4, SECOND_ANCHOR_VALUE))
		);
		final Consumer<EvitaSessionContract> moveBothShares = upsertOf(
			ENTITY_PRODUCT, PRODUCT_PK,
			localizedShare(Locale.ENGLISH, INSIDE_ANCHOR_RANGE),
			localizedShare(Locale.GERMAN, INSIDE_ANCHOR_RANGE)
		);
		scenarios.add(new PreBatchScenario(
			"EXISTING_PRODUCT_LOCALE_SHARE_SCOPE_AND_SHARE_IN_ONE_UPSERT", englishProduct, englishBefore,
			upsertOf(
				ENTITY_PRODUCT, PRODUCT_PK,
				new UpsertAttributeMutation(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt"),
				localizedShare(Locale.GERMAN, 2),
				new SetEntityScopeMutation(Scope.ARCHIVED),
				localizedShare(Locale.GERMAN, 4)
			),
			archivedWithFinalShares, moveBothShares, anchorsOnly
		));
		// the control: the German title comes in an earlier upsert, the rest stays together in the second one
		scenarios.add(new PreBatchScenario(
			"CONTROL_EXISTING_PRODUCT_LOCALE_THEN_SHARE_SCOPE_AND_SHARE", englishProduct, englishBefore,
			session -> {
				upsertOf(
					ENTITY_PRODUCT, PRODUCT_PK, new UpsertAttributeMutation(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt")
				).accept(session);
				upsertOf(
					ENTITY_PRODUCT, PRODUCT_PK,
					localizedShare(Locale.GERMAN, 2),
					new SetEntityScopeMutation(Scope.ARCHIVED),
					localizedShare(Locale.GERMAN, 4)
				).accept(session);
			},
			archivedWithFinalShares, moveBothShares, anchorsOnly
		));
		return inBothCatalogStates(scenarios);
	}

	/**
	 * The scenarios of a defaulted localized share whose product gains or loses a locale through its own title: the
	 * product gains it after its reference exists, loses it and gains it back with or without the reference, and a
	 * control product has both locales before its reference is stored.
	 *
	 * @return arguments of {@link OwnerLocaleSetChange#shouldHoldTheValuesOfAFreshlyStoredProduct}
	 */
	@Nonnull
	static Stream<Arguments> ownerLocaleScenarios() {
		final List<OwnerScenario> scenarios = new ArrayList<>(4);
		final String ownerAnchors = "{10=[100], 20=[100]}";

		// the product gets the locale its reference has no share in only after the reference exists
		scenarios.add(new OwnerScenario(
			"OWNER_GAINS_LOCALE_THROUGH_ENTITY_ATTRIBUTE",
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
				session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
					.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "product")
					.upsertVia(session);
				referenceWithEnglishShare(session, 5);
			},
			ownerState("{5=[1], 10=[100], 20=[100]}", "5..20", ownerAnchors, "10..20"),
			List.of(
				new OwnerStep(
					"OWNER_GAINS_LOCALE",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, germanTitle()),
					ownerState("{5=[1], 10=[100], 20=[100]}", "5..20", "{7=[1], 10=[100], 20=[100]}", "7..20")
				),
				new OwnerStep(
					"SHARE_MOVED_INTO_ANCHOR_RANGE",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, localizedShare(Locale.ENGLISH, 15)),
					ownerState("{10=[100], 15=[1], 20=[100]}", "10..20", "{7=[1], 10=[100], 20=[100]}", "7..20")
				),
				new OwnerStep(
					"REFERENCE_REMOVED",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, new RemoveReferenceMutation(SUBJECT)),
					ownerState(ownerAnchors, "10..20", ownerAnchors, "10..20")
				)
			)
		));
		// the control: the product has both locales before the reference is stored
		scenarios.add(new OwnerScenario(
			"CONTROL_OWNER_HAS_LOCALE_BEFORE_REFERENCE",
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
				session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
					.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "product")
					.setAttribute(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt")
					.upsertVia(session);
				referenceWithEnglishShare(session, 5);
			},
			ownerState("{5=[1], 10=[100], 20=[100]}", "5..20", "{7=[1], 10=[100], 20=[100]}", "7..20"),
			List.of(
				new OwnerStep(
					"SHARE_MOVED_INTO_ANCHOR_RANGE",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, localizedShare(Locale.ENGLISH, 15)),
					ownerState("{10=[100], 15=[1], 20=[100]}", "10..20", "{7=[1], 10=[100], 20=[100]}", "7..20")
				),
				new OwnerStep(
					"REFERENCE_REMOVED",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, new RemoveReferenceMutation(SUBJECT)),
					ownerState(ownerAnchors, "10..20", ownerAnchors, "10..20")
				)
			)
		));
		// the product loses the last attribute of the locale its reference contributes the default in, and gains it
		// back later
		scenarios.add(new OwnerScenario(
			"OWNER_LOSES_LAST_ATTRIBUTE_OF_LOCALE",
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
				session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
					.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "product")
					.setAttribute(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt")
					.upsertVia(session);
				referenceWithEnglishShare(session, 5);
			},
			ownerState("{5=[1], 10=[100], 20=[100]}", "5..20", "{7=[1], 10=[100], 20=[100]}", "7..20"),
			List.of(
				new OwnerStep(
					"OWNER_LOSES_LOCALE",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, new RemoveAttributeMutation(ATTRIBUTE_TITLE, Locale.GERMAN)),
					ownerState("{5=[1], 10=[100], 20=[100]}", "5..20", ownerAnchors, "10..20")
				),
				new OwnerStep(
					"SHARE_MOVED_INTO_ANCHOR_RANGE",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, localizedShare(Locale.ENGLISH, 15)),
					ownerState("{10=[100], 15=[1], 20=[100]}", "10..20", ownerAnchors, "10..20")
				),
				new OwnerStep(
					"OWNER_GAINS_LOCALE_BACK",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, germanTitle()),
					ownerState("{10=[100], 15=[1], 20=[100]}", "10..20", "{7=[1], 10=[100], 20=[100]}", "7..20")
				),
				new OwnerStep(
					"REFERENCE_REMOVED",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, new RemoveReferenceMutation(SUBJECT)),
					ownerState(ownerAnchors, "10..20", ownerAnchors, "10..20")
				)
			)
		));
		// the product loses the locale its reference contributes the default in, the reference is removed, and the
		// product gains the locale back - with no reference left to contribute anything
		scenarios.add(new OwnerScenario(
			"OWNER_LOSES_LOCALE_THEN_REFERENCE_REMOVED_THEN_LOCALE_RETURNS",
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
				productWithBothTitles(session).upsertVia(session);
				referenceWithEnglishShare(session, 5);
			},
			ownerState("{5=[1], 10=[100], 20=[100]}", "5..20", "{7=[1], 10=[100], 20=[100]}", "7..20"),
			List.of(
				new OwnerStep(
					"OWNER_LOSES_LOCALE",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, new RemoveAttributeMutation(ATTRIBUTE_TITLE, Locale.GERMAN)),
					ownerState("{5=[1], 10=[100], 20=[100]}", "5..20", ownerAnchors, "10..20")
				),
				new OwnerStep(
					"REFERENCE_REMOVED",
					upsertOf(ENTITY_PRODUCT, PRODUCT_PK, new RemoveReferenceMutation(SUBJECT)),
					ownerState(ownerAnchors, "10..20", ownerAnchors, "10..20")
				),
				new OwnerStep(
					"OWNER_GAINS_LOCALE_BACK", upsertOf(ENTITY_PRODUCT, PRODUCT_PK, germanTitle()),
					ownerState(ownerAnchors, "10..20", ownerAnchors, "10..20")
				)
			)
		));
		return scenarios.stream().flatMap(scenario -> Stream.of(CatalogState.WARMING_UP, CatalogState.ALIVE)
			.flatMap(state -> Stream.of(false, true).map(grouped -> Arguments.of(state, scenario, grouped))));
	}

	/**
	 * The scenarios of a change of the product's locale set by each kind of mutation that can make one, alone and
	 * together with a change of the reference, on the reference itself, on a sibling and through a mutation propagated
	 * from a reflected reference; and of a reference holding no share in any locale, whose product gains or loses a
	 * locale, which gets its first share, or which loses its last one.
	 *
	 * The expected states are written as the values the product holds in each locale of each scope, see
	 * {@link #localeSetState}. The default of a defaulted fixture is {@link #DEFAULT_SHARE}; the subject reference
	 * targets brand {@link #BRAND_PK}, its sibling brand {@link #SIBLING_BRAND_PK}.
	 *
	 * @return arguments of {@link EnumeratedLocaleSet#shouldHoldValuesOfEveryLocaleOfTheProduct}
	 */
	@Nonnull
	static Stream<Arguments> localeSetScenarios() {
		final List<LocaleSetScenario> scenarios = new ArrayList<>(24);
		final int d = DEFAULT_SHARE;
		final String nothing = localeSetState(NONE, NONE, NONE, NONE);

		// the setups, each step its own upsert
		final Consumer<EvitaSessionContract> productEnglish = session -> {
			createProduct(title(Locale.ENGLISH)).accept(session);
			product(insert(BRAND_PK), share(BRAND_PK, Locale.ENGLISH, 1)).accept(session);
		};
		final String productEnglishState = localeSetState(values(1), NONE, NONE, NONE);
		final Consumer<EvitaSessionContract> productEnglishGerman = session -> {
			createProduct(title(Locale.ENGLISH), title(Locale.GERMAN)).accept(session);
			product(insert(BRAND_PK), share(BRAND_PK, Locale.ENGLISH, 1)).accept(session);
		};
		final String productEnglishGermanState = localeSetState(values(1), values(d), NONE, NONE);
		// the German locale is held by the German share of the reference only
		final Consumer<EvitaSessionContract> productGermanByReference = session -> {
			createProduct(title(Locale.ENGLISH), title(Locale.GERMAN)).accept(session);
			product(insert(BRAND_PK), share(BRAND_PK, Locale.ENGLISH, 1), share(BRAND_PK, Locale.GERMAN, 3))
				.accept(session);
			product(removeTitle(Locale.GERMAN)).accept(session);
		};
		final String productGermanByReferenceState = localeSetState(values(1), values(3), NONE, NONE);
		final Consumer<EvitaSessionContract> productGermanByReferenceWithSibling = session -> {
			productGermanByReference.accept(session);
			product(insert(SIBLING_BRAND_PK), share(SIBLING_BRAND_PK, Locale.ENGLISH, 2)).accept(session);
		};
		final String productGermanByReferenceWithSiblingState = localeSetState(values(1, 2), values(3, d), NONE, NONE);
		// the German locale is held by the German associated data only
		final Consumer<EvitaSessionContract> productGermanByAssociatedData = session -> {
			createProduct(title(Locale.ENGLISH), note(Locale.GERMAN)).accept(session);
			product(insert(BRAND_PK), share(BRAND_PK, Locale.ENGLISH, 1)).accept(session);
		};
		final String productGermanByAssociatedDataState = localeSetState(values(1), values(d), NONE, NONE);
		final Consumer<EvitaSessionContract> productEnglishWithSibling = session -> {
			productEnglish.accept(session);
			product(insert(SIBLING_BRAND_PK), share(SIBLING_BRAND_PK, Locale.ENGLISH, 2)).accept(session);
		};
		final String productEnglishWithSiblingState = localeSetState(values(1, 2), NONE, NONE, NONE);
		// the reflected reference verification resolves the references the product inserts through the keys the
		// entity builder assigns, so the reflected fixture stores and changes them through the builder
		final Consumer<EvitaSessionContract> productEnglishWithSiblingByBuilder = session -> {
			createProduct(title(Locale.ENGLISH)).accept(session);
			session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, 1)
				)
				.setReference(
					REFERENCE_BRAND, SIBLING_BRAND_PK,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, 2)
				)
				.upsertVia(session);
		};
		// the reference holds no share in any locale, so it contributes the default in every locale of the product
		final Consumer<EvitaSessionContract> productEnglishWithoutShare = session -> {
			createProduct(title(Locale.ENGLISH)).accept(session);
			product(insert(BRAND_PK)).accept(session);
		};
		final String productEnglishWithoutShareState = localeSetState(values(d), NONE, NONE, NONE);
		final Consumer<EvitaSessionContract> productEnglishGermanWithoutShare = session -> {
			createProduct(title(Locale.ENGLISH), title(Locale.GERMAN)).accept(session);
			product(insert(BRAND_PK)).accept(session);
		};
		final String productEnglishGermanWithoutShareState = localeSetState(values(d), values(d), NONE, NONE);

		// the follow-ups
		final Consumer<EvitaSessionContract> move = product(
			share(BRAND_PK, Locale.ENGLISH, INSIDE_ANCHOR_RANGE), share(BRAND_PK, Locale.GERMAN, INSIDE_ANCHOR_RANGE)
		);
		// the reference is touched before the locale returns, so a stale default is not consumed by a withdrawal
		final Consumer<EvitaSessionContract> restore = product(
			share(BRAND_PK, Locale.ENGLISH, INSIDE_ANCHOR_RANGE), share(BRAND_PK, Locale.GERMAN, INSIDE_ANCHOR_RANGE),
			title(Locale.GERMAN)
		);
		final String movedLive = localeSetState(values(INSIDE_ANCHOR_RANGE), values(INSIDE_ANCHOR_RANGE), NONE, NONE);
		final String movedArchived =
			localeSetState(NONE, NONE, values(INSIDE_ANCHOR_RANGE), values(INSIDE_ANCHOR_RANGE));
		final Consumer<EvitaSessionContract> moveBothReferencesByBuilder = session -> session
			.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REFERENCE_BRAND, BRAND_PK,
				whichIs -> whichIs
					.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, INSIDE_ANCHOR_RANGE)
					.setAttribute(ATTRIBUTE_SHARE, Locale.GERMAN, INSIDE_ANCHOR_RANGE)
			)
			.setReference(
				REFERENCE_BRAND, SIBLING_BRAND_PK,
				whichIs -> whichIs
					.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, INSIDE_ANCHOR_RANGE)
					.setAttribute(ATTRIBUTE_SHARE, Locale.GERMAN, INSIDE_ANCHOR_RANGE)
			)
			.upsertVia(session);

		// the product gains a locale through its title in the upsert that also changes the reference
		scenarios.add(new LocaleSetScenario(
			"GAINS_LOCALE_WITH_REFERENCE_CHANGE", LocaleSetFixture.DEFAULTED,
			productEnglish, productEnglishState,
			product(title(Locale.GERMAN), share(BRAND_PK, Locale.ENGLISH, 2)),
			localeSetState(values(2), values(d), NONE, NONE),
			move, movedLive
		));
		// the product loses a locale through its title in the upsert that also changes the reference
		scenarios.add(new LocaleSetScenario(
			"LOSES_LOCALE_WITH_REFERENCE_CHANGE", LocaleSetFixture.DEFAULTED,
			productEnglishGerman, productEnglishGermanState,
			product(removeTitle(Locale.GERMAN), share(BRAND_PK, Locale.ENGLISH, 2)),
			localeSetState(values(2), NONE, NONE, NONE),
			restore, movedLive
		));
		// the product gains a locale through the value of the reference itself
		scenarios.add(new LocaleSetScenario(
			"GAINS_LOCALE_BY_REFERENCE_VALUE", LocaleSetFixture.DEFAULTED,
			productEnglish, productEnglishState,
			product(share(BRAND_PK, Locale.GERMAN, 3)),
			localeSetState(values(1), values(3), NONE, NONE),
			move, movedLive
		));
		// a new product gets both locales and its reference in one upsert
		scenarios.add(new LocaleSetScenario(
			"NEW_PRODUCT_WITH_DEFAULTED_LOCALE", LocaleSetFixture.DEFAULTED,
			null, nothing,
			createProduct(
				title(Locale.ENGLISH), title(Locale.GERMAN), insert(BRAND_PK), share(BRAND_PK, Locale.ENGLISH, 1)
			),
			localeSetState(values(1), values(d), NONE, NONE),
			move, movedLive
		));
		// the product loses a locale its reference alone held, by removing the reference's value in it
		scenarios.add(new LocaleSetScenario(
			"LOSES_LOCALE_BY_REFERENCE_VALUE", LocaleSetFixture.DEFAULTED,
			productGermanByReference, productGermanByReferenceState,
			product(removeShare(BRAND_PK, Locale.GERMAN)),
			localeSetState(values(1), NONE, NONE, NONE),
			move, movedLive
		));
		// an applied delta moves the reference's own English value and gives the product no locale: the German
		// default stays as it was
		scenarios.add(new LocaleSetScenario(
			"DELTA_ON_LOCALIZED_VALUE_KEEPS_LOCALE_SET", LocaleSetFixture.DEFAULTED,
			productEnglishGerman, productEnglishGermanState,
			product(new ReferenceAttributeMutation(
				SUBJECT, new ApplyDeltaAttributeMutation<>(ATTRIBUTE_SHARE, Locale.ENGLISH, 3)
			)),
			localeSetState(values(4), values(d), NONE, NONE),
			move, movedLive
		));
		// the product gains a locale, moves to the archive and changes the reference there
		scenarios.add(new LocaleSetScenario(
			"GAINS_LOCALE_THEN_SCOPE_CHANGE_AND_REFERENCE_CHANGE", LocaleSetFixture.DEFAULTED,
			productEnglish, productEnglishState,
			product(title(Locale.GERMAN), moveTo(Scope.ARCHIVED), share(BRAND_PK, Locale.ENGLISH, 2)),
			localeSetState(NONE, NONE, values(2), values(d)),
			move, movedArchived
		));
		// the product loses a locale, moves to the archive and changes the reference there; the follow-up
		// returns it, so a value left behind in either scope shows
		scenarios.add(new LocaleSetScenario(
			"LOSES_LOCALE_THEN_SCOPE_CHANGE_AND_REFERENCE_CHANGE", LocaleSetFixture.DEFAULTED,
			productEnglishGerman, productEnglishGermanState,
			product(removeTitle(Locale.GERMAN), moveTo(Scope.ARCHIVED), share(BRAND_PK, Locale.ENGLISH, 2)),
			localeSetState(NONE, NONE, values(2), NONE),
			product(
				share(BRAND_PK, Locale.ENGLISH, INSIDE_ANCHOR_RANGE),
				share(BRAND_PK, Locale.GERMAN, INSIDE_ANCHOR_RANGE),
				title(Locale.GERMAN), moveTo(Scope.LIVE)
			),
			movedLive
		));
		// the product loses a locale its associated data alone held, together with a change of the reference
		scenarios.add(new LocaleSetScenario(
			"LOSES_ASSOCIATED_DATA_LOCALE_WITH_REFERENCE_CHANGE", LocaleSetFixture.DEFAULTED,
			productGermanByAssociatedData, productGermanByAssociatedDataState,
			product(removeNote(Locale.GERMAN), share(BRAND_PK, Locale.ENGLISH, 2)),
			localeSetState(values(2), NONE, NONE, NONE),
			product(
				share(BRAND_PK, Locale.ENGLISH, INSIDE_ANCHOR_RANGE),
				share(BRAND_PK, Locale.GERMAN, INSIDE_ANCHOR_RANGE),
				note(Locale.GERMAN)
			),
			movedLive
		));
		// the product gains a locale through its associated data
		scenarios.add(new LocaleSetScenario(
			"GAINS_ASSOCIATED_DATA_LOCALE", LocaleSetFixture.DEFAULTED,
			productEnglish, productEnglishState,
			product(note(Locale.GERMAN)),
			localeSetState(values(1), values(d), NONE, NONE),
			move, movedLive
		));
		// without a default, a locale the reference alone gives the product survives a scope change
		scenarios.add(new LocaleSetScenario(
			"REFERENCE_ONLY_LOCALE_THEN_SCOPE_CHANGE", LocaleSetFixture.PLAIN,
			productGermanByReference, productGermanByReferenceState,
			product(moveTo(Scope.ARCHIVED)),
			localeSetState(NONE, NONE, values(1), values(3)),
			move, movedArchived
		));
		// the control of the scenario above: the same locale held by the reference alone, without the scope change
		scenarios.add(new LocaleSetScenario(
			"REFERENCE_ONLY_LOCALE_WITHOUT_SCOPE_CHANGE", LocaleSetFixture.PLAIN,
			productGermanByReference, productGermanByReferenceState,
			product(share(BRAND_PK, Locale.ENGLISH, 2)),
			localeSetState(values(2), values(3), NONE, NONE),
			move, movedLive
		));
		// the reference is removed and inserted again under a new key in one upsert
		scenarios.add(new LocaleSetScenario(
			"REFERENCE_REMOVED_AND_INSERTED_AGAIN", LocaleSetFixture.DEFAULTED,
			productEnglishGerman, productEnglishGermanState,
			session -> {
				final ReferenceKey stored = session
					.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
					.orElseThrow()
					.getReference(REFERENCE_BRAND, BRAND_PK)
					.orElseThrow()
					.getReferenceKey();
				final ReferenceKey inserted = new ReferenceKey(REFERENCE_BRAND, BRAND_PK, -1);
				product(
					new RemoveReferenceMutation(stored),
					new InsertReferenceMutation(inserted),
					new ReferenceAttributeMutation(
						inserted, new UpsertAttributeMutation(ATTRIBUTE_SHARE, Locale.ENGLISH, 7)
					)
				).accept(session);
			},
			localeSetState(values(7), values(d), NONE, NONE),
			move, movedLive
		));
		// the product is removed while a sibling holds the default in a locale only the reference gave it;
		// the follow-up stores the product again, the locales first and the reference second
		scenarios.add(new LocaleSetScenario(
			"ENTITY_REMOVED_WITH_REFERENCE_ONLY_LOCALE", LocaleSetFixture.DEFAULTED,
			productGermanByReferenceWithSibling, productGermanByReferenceWithSiblingState,
			session -> session.deleteEntity(ENTITY_PRODUCT, PRODUCT_PK),
			nothing,
			session -> {
				createProduct(title(Locale.ENGLISH), title(Locale.GERMAN)).accept(session);
				product(
					insert(BRAND_PK),
					share(BRAND_PK, Locale.ENGLISH, INSIDE_ANCHOR_RANGE),
					share(BRAND_PK, Locale.GERMAN, INSIDE_ANCHOR_RANGE)
				).accept(session);
			},
			movedLive
		));
		// a sibling's value gives the product a locale the subject reference contributes its default in
		scenarios.add(new LocaleSetScenario(
			"SIBLING_VALUE_GAINS_LOCALE", LocaleSetFixture.DEFAULTED,
			productEnglishWithSibling, productEnglishWithSiblingState,
			product(share(SIBLING_BRAND_PK, Locale.GERMAN, INSIDE_ANCHOR_RANGE)),
			localeSetState(values(1, 2), values(d, INSIDE_ANCHOR_RANGE), NONE, NONE),
			move,
			localeSetState(values(2, INSIDE_ANCHOR_RANGE), values(INSIDE_ANCHOR_RANGE), NONE, NONE)
		));
		// a value of a reference that is not indexed gives the product a locale
		scenarios.add(new LocaleSetScenario(
			"NON_INDEXED_REFERENCE_VALUE_GAINS_LOCALE", LocaleSetFixture.DEFAULTED,
			productEnglish, productEnglishState,
			product(
				new InsertReferenceMutation(new ReferenceKey(REFERENCE_TAG, FIRST_ANCHOR_BRAND_PK)),
				new ReferenceAttributeMutation(
					new ReferenceKey(REFERENCE_TAG, FIRST_ANCHOR_BRAND_PK),
					new UpsertAttributeMutation(ATTRIBUTE_LABEL, Locale.GERMAN, "Etikett")
				)
			),
			localeSetState(values(1), values(d), NONE, NONE),
			move, movedLive
		));
		// the removal of the reference holding a locale alone takes the locale, and the sibling's default
		// in it, from the product
		scenarios.add(new LocaleSetScenario(
			"SIBLING_REMOVED_DROPS_LOCALE", LocaleSetFixture.DEFAULTED,
			productGermanByReferenceWithSibling, productGermanByReferenceWithSiblingState,
			product(new RemoveReferenceMutation(SUBJECT)),
			localeSetState(values(2), NONE, NONE, NONE),
			product(
				share(SIBLING_BRAND_PK, Locale.ENGLISH, INSIDE_ANCHOR_RANGE),
				share(SIBLING_BRAND_PK, Locale.GERMAN, INSIDE_ANCHOR_RANGE)
			),
			movedLive
		));
		// the removal of the reference's value holding a locale alone takes the locale, and the sibling's
		// default in it, from the product; both references are touched before the locale returns
		scenarios.add(new LocaleSetScenario(
			"SIBLING_VALUE_LOSES_LOCALE", LocaleSetFixture.DEFAULTED,
			productGermanByReferenceWithSibling, productGermanByReferenceWithSiblingState,
			product(removeShare(BRAND_PK, Locale.GERMAN)),
			localeSetState(values(1, 2), NONE, NONE, NONE),
			product(
				share(BRAND_PK, Locale.ENGLISH, INSIDE_ANCHOR_RANGE),
				share(SIBLING_BRAND_PK, Locale.ENGLISH, INSIDE_ANCHOR_RANGE),
				share(BRAND_PK, Locale.GERMAN, INSIDE_ANCHOR_RANGE),
				share(SIBLING_BRAND_PK, Locale.GERMAN, INSIDE_ANCHOR_RANGE)
			),
			movedLive
		));
		// implicit routes: the brand writes the German label through its reflected reference, and the change reaches
		// the product only as a mutation the brand's upsert propagates to it - the label is no input of the histogram,
		// yet it gives the product the German locale, and both references must contribute their default there
		scenarios.add(new LocaleSetScenario(
			"REFLECTED_PROPAGATION_GAINS_LOCALE", LocaleSetFixture.REFLECTED,
			productEnglishWithSiblingByBuilder, productEnglishWithSiblingState,
			session -> session.getEntity(ENTITY_BRAND, BRAND_PK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REFERENCE_PRODUCTS, PRODUCT_PK,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_LABEL, Locale.GERMAN, "Etikett")
				)
				.upsertVia(session),
			localeSetState(values(1, 2), values(d), NONE, NONE),
			moveBothReferencesByBuilder, movedLive
		));
		// a control, not a route that changes the locale set: generated defaults iterate the locales the entity has
		// already (`ContainerizedLocalMutationExecutor#processReferenceAttributesWithDefaultValue`), so they cannot
		// move its locale set. The explicit German title adds the locale and withdraws the references; the implicit
		// mutations of the same upsert then generate the German `label` on both references, and the scenario shows that
		// the generated values compose with the explicit change - the check of the labels proves they were applied
		scenarios.add(new LocaleSetScenario(
			"GENERATED_DEFAULT_IN_GAINED_LOCALE", LocaleSetFixture.REFLECTED,
			productEnglishWithSiblingByBuilder, productEnglishWithSiblingState,
			product(title(Locale.GERMAN)),
			localeSetState(values(1, 2), values(d), NONE, NONE),
			moveBothReferencesByBuilder, movedLive,
			BRAND_PK + "=x " + SIBLING_BRAND_PK + "=x"
		));
		// a reference holding no share in any locale contributes the default in the locale the product gains
		scenarios.add(new LocaleSetScenario(
			"REFERENCE_WITHOUT_VALUE_GAINS_LOCALE", LocaleSetFixture.DEFAULTED,
			productEnglishWithoutShare, productEnglishWithoutShareState,
			product(title(Locale.GERMAN)),
			localeSetState(values(d), values(d), NONE, NONE),
			move, movedLive
		));
		// a reference holding no share in any locale withdraws the default from the locale the product loses
		scenarios.add(new LocaleSetScenario(
			"REFERENCE_WITHOUT_VALUE_LOSES_LOCALE", LocaleSetFixture.DEFAULTED,
			productEnglishGermanWithoutShare, productEnglishGermanWithoutShareState,
			product(removeTitle(Locale.GERMAN)),
			localeSetState(values(d), NONE, NONE, NONE),
			restore, movedLive
		));
		// the first share of a reference holding none replaces the default in its own locale only
		scenarios.add(new LocaleSetScenario(
			"REFERENCE_WITHOUT_VALUE_GETS_FIRST_VALUE", LocaleSetFixture.DEFAULTED,
			productEnglishGermanWithoutShare, productEnglishGermanWithoutShareState,
			product(share(BRAND_PK, Locale.ENGLISH, 2)),
			localeSetState(values(2), values(d), NONE, NONE),
			move, movedLive
		));
		// the removal of the last share leaves the reference holding none: the default returns in the share's locale
		scenarios.add(new LocaleSetScenario(
			"REFERENCE_LOSES_LAST_VALUE", LocaleSetFixture.DEFAULTED,
			productEnglishGerman, productEnglishGermanState,
			product(removeShare(BRAND_PK, Locale.ENGLISH)),
			localeSetState(values(d), values(d), NONE, NONE),
			move, movedLive
		));
		return inBothCatalogStates(scenarios);
	}

	/**
	 * Pairs every scenario with both catalog states.
	 *
	 * @param scenarios the scenarios
	 * @return the arguments: the catalog state and the scenario
	 */
	@Nonnull
	private static Stream<Arguments> inBothCatalogStates(@Nonnull List<?> scenarios) {
		final List<Arguments> result = new ArrayList<>(scenarios.size() * 2);
		for (CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (Object scenario : scenarios) {
				result.add(Arguments.of(state, scenario));
			}
		}
		return result.stream();
	}

	/**
	 * The scenarios of a localized value written by the very upsert that gives the product its
	 * locale - a new product stored with its German title and its German share at once, and an existing product that
	 * gains its German title, its German share and a new scope in one upsert and changes the German share once more
	 * after the scope change.
	 *
	 * Every scenario has a control that writes the same data and differs in one thing only: the mutation giving the
	 * product its German locale comes in an earlier upsert. Every scenario checks the histograms right after the
	 * upsert, again after a restart, after a follow-up upsert that moves the shares into the range of the anchor
	 * products, and after one more restart - a value the upsert leaves behind shows in the follow-up, and a value
	 * missing or left behind in memory only would disappear on the restart.
	 */
	@Nested
	@DisplayName("a localized value written by the upsert that gives the product its locale")
	class ValueWrittenByUpsertGivingItsLocale {

		/**
		 * Applies the upsert of the scenario and checks the histogram of each locale in both scopes right after it,
		 * after a restart, after the follow-up that moves the shares among the anchors, and after one more restart.
		 *
		 * @param state    the state the catalog is in when the upsert is applied
		 * @param scenario the scenario
		 */
		@ParameterizedTest(name = "{0} {1}")
		@MethodSource(
			"io.evitadb.api.functional.ReferenceHistogramLocaleSetFunctionalTest#localeAddedInSameUpsertScenarios"
		)
		@DisplayName("the value of a locale the upsert adds reaches the histogram of that locale")
		void shouldHoldLocalizedValueWhenUpsertAddsItsLocale(
			@Nonnull CatalogState state,
			@Nonnull PreBatchScenario scenario
		) {
			final TestPaths paths = createTestPaths("ReferenceHistogramLocaleSet_PreBatch_" + state + "_" + scenario);
			final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
			final List<Executable> checks = new ArrayList<>(32);
			Evita evita = new Evita(configuration);
			try {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					preBatchSetUpCatalog(session);
					if (scenario.preparation() != null) {
						scenario.preparation().accept(session);
					}
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});
				recordPreBatchHistograms(evita, "before the upsert", scenario.before(), checks);

				applyUpdate(evita, "the upsert", checks, scenario.upsert());
				recordPreBatchHistograms(evita, "after the upsert", scenario.afterUpsert(), checks);
				evita = restart(evita, configuration);
				recordPreBatchHistograms(evita, "after the upsert and restart", scenario.afterUpsert(), checks);

				// a value the upsert left behind survives the removal the follow-up makes, and nothing else shows it
				applyUpdate(evita, "the follow-up", checks, scenario.followUp());
				recordPreBatchHistograms(evita, "after the follow-up", scenario.afterFollowUp(), checks);
				evita = restart(evita, configuration);
				recordPreBatchHistograms(evita, "after the follow-up and restart", scenario.afterFollowUp(), checks);
			} finally {
				evita.close();
				cleanupTestPaths(paths);
			}
			assertAll(checks);
		}

	}

	/**
	 * A defaulted localized share - `$reference.localizedAttributes['share'] ?? 7` - whose product gains or loses
	 * the locale the reference has no share in through its own title, while the reference is not touched. Every step
	 * is checked in the type index of the reference - the values of each locale and the owners holding them - and in
	 * the reference summary of each locale, then again after a restart. A unit left behind in a locale the product
	 * lacks is visible in the index only, until the product gains the locale back.
	 */
	@Nested
	@DisplayName("a defaulted localized value when the owner gains or loses a locale")
	class OwnerLocaleSetChange {

		/**
		 * Stores the fixture and the scenario's data, checks the starting state, applies every step and checks the
		 * state after it, in memory and after a restart.
		 *
		 * @param state    the state the catalog is in when the steps are applied
		 * @param scenario the scenario
		 * @param grouped whether the reference contributes to a group store
		 */
		@ParameterizedTest(name = "{0} {1} grouped={2}")
		@MethodSource("io.evitadb.api.functional.ReferenceHistogramLocaleSetFunctionalTest#ownerLocaleScenarios")
		@DisplayName("every locale of the histogram holds the values of a freshly stored product after every step")
		void shouldHoldTheValuesOfAFreshlyStoredProduct(
			@Nonnull CatalogState state,
			@Nonnull OwnerScenario scenario,
			boolean grouped
		) {
			final TestPaths paths = createTestPaths(
				"ReferenceHistogramLocaleSet_Owner_" + state + "_" + scenario + "_" + grouped
			);
			final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
			final List<Executable> checks = new ArrayList<>(32);
			Evita evita = new Evita(configuration);
			try {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					ownerSetUpCatalog(session, grouped);
					scenario.preparation().accept(session);
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});
				recordOwnerState(evita, scenario + " before the first step", scenario.before(), checks, grouped);
				int stepNumber = 0;
				for (OwnerStep step : scenario.steps()) {
					stepNumber++;
					final String stage = scenario + " after step " + stepNumber + " " + step.name();
					applyUpdate(evita, stage, checks, step.update());
					recordOwnerState(evita, stage, step.expected(), checks, grouped);
					evita = restart(evita, configuration);
					recordOwnerState(evita, stage + " and a restart", step.expected(), checks, grouped);
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

	}

	/**
	 * The product's locale set changed by each kind of mutation that can change it: an entity attribute, a reference
	 * attribute of the reference itself, of a sibling and of a reference that is not indexed, associated data, the
	 * removal of a reference, the removal of the whole product, a propagated mutation of a reflected reference -
	 * alone, together with a change of the reference, and across a scope change - and a reference holding no share in
	 * any locale, whose product gains or loses a locale, or which gets its first share or loses its last one. Every
	 * check reads the type index of the reference in both scopes - the values of each locale and the owners holding
	 * them - and the reference summary of each locale, so a unit left behind in a locale the product lacks shows right
	 * after the upsert.
	 */
	@Nested
	@DisplayName("the locale set a localized value is enumerated over")
	class EnumeratedLocaleSet {

		/**
		 * Stores the fixture and the scenario's setup, checks the starting state, applies the upsert and the
		 * follow-up and checks the state after each, in memory and after a restart.
		 *
		 * @param state    the state the catalog is in when the upsert is applied
		 * @param scenario the scenario
		 */
		@ParameterizedTest(name = "{0} {1}")
		@MethodSource("io.evitadb.api.functional.ReferenceHistogramLocaleSetFunctionalTest#localeSetScenarios")
		@DisplayName("every locale of the histogram holds the values of the product's current locales")
		void shouldHoldValuesOfEveryLocaleOfTheProduct(
			@Nonnull CatalogState state,
			@Nonnull LocaleSetScenario scenario
		) {
			final TestPaths paths = createTestPaths("ReferenceHistogramLocaleSet_" + state + "_" + scenario);
			final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
			final List<Executable> checks = new ArrayList<>(32);
			Evita evita = new Evita(configuration);
			try {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					localeSetSetUpCatalog(session, scenario.fixture());
					if (scenario.preparation() != null) {
						scenario.preparation().accept(session);
					}
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});
				recordLocaleSetState(evita, "before the upsert", scenario.before(), checks);

				applyUpdate(evita, "the upsert", checks, scenario.upsert());
				recordLocaleSetState(evita, "after the upsert", scenario.afterUpsert(), checks);
				recordGermanLabels(evita, "after the upsert", scenario.germanLabels(), checks);
				evita = restart(evita, configuration);
				recordLocaleSetState(evita, "after the upsert and restart", scenario.afterUpsert(), checks);
				recordGermanLabels(evita, "after the upsert and restart", scenario.germanLabels(), checks);

				// the follow-up withdraws what the upsert left for the current locales
				applyUpdate(evita, "the follow-up", checks, scenario.followUp());
				recordLocaleSetState(evita, "after the follow-up", scenario.afterFollowUp(), checks);
				evita = restart(evita, configuration);
				recordLocaleSetState(evita, "after the follow-up and restart", scenario.afterFollowUp(), checks);
			} catch (RuntimeException ex) {
				checks.add(() -> fail(
					"the scenario threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex
				));
			} finally {
				evita.close();
				cleanupTestPaths(paths);
			}
			assertAll(checks);
		}

	}

	/**
	 * One expected state of the share histogram in the scenarios of a locale given by the upsert.
	 *
	 * @param scope    the scope whose histogram is checked
	 * @param locale   the locale of the histogram
	 * @param expected the expected description, see {@link #describePreBatchHistogram}
	 */
	private record PreBatchExpectation(
		@Nonnull Scope scope,
		@Nonnull Locale locale,
		@Nonnull String expected
	) {
	}

	/**
	 * One upsert under test of these scenarios, with the product it runs on and the states of the
	 * histograms it must produce.
	 *
	 * @param name          the name of the scenario
	 * @param preparation   stores the product before the scenario starts, or `null` when the upsert under test
	 *                      creates it
	 * @param before        the histograms before the upsert, proving the starting state is the intended one
	 * @param upsert        the upsert under test, or the two upserts of a control
	 * @param afterUpsert   the histograms right after the upsert, in memory and after a restart
	 * @param followUp      the upsert moving the shares among the anchors
	 * @param afterFollowUp the histograms after the follow-up, in memory and after a restart
	 */
	private record PreBatchScenario(
		@Nonnull String name,
		@Nullable Consumer<EvitaSessionContract> preparation,
		@Nonnull List<PreBatchExpectation> before,
		@Nonnull Consumer<EvitaSessionContract> upsert,
		@Nonnull List<PreBatchExpectation> afterUpsert,
		@Nonnull Consumer<EvitaSessionContract> followUp,
		@Nonnull List<PreBatchExpectation> afterFollowUp
	) {

		/**
		 * Returns the name of the scenario, which the parameterized test shows as its display name.
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
	 * One update of an owner-side scenario with the state it must leave behind.
	 *
	 * @param name     the name of the update
	 * @param update   the update
	 * @param expected the expected state after the update, see {@link #ownerState}
	 */
	private record OwnerStep(
		@Nonnull String name,
		@Nonnull Consumer<EvitaSessionContract> update,
		@Nonnull String expected
	) {
	}

	/**
	 * The data an owner-side scenario stores on top of its fixture, and the steps applied afterwards.
	 *
	 * @param name        the name of the scenario
	 * @param preparation stores the subject brand and the product
	 * @param before      the expected state before the first step
	 * @param steps       the steps
	 */
	private record OwnerScenario(
		@Nonnull String name,
		@Nonnull Consumer<EvitaSessionContract> preparation,
		@Nonnull String before,
		@Nonnull List<OwnerStep> steps
	) {

		/**
		 * Returns the name of the scenario, which the parameterized test shows as its display name.
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
	 * The schema a locale-set scenario runs on. All of them share the product with the locales English and German,
	 * the localized `title`, the localized associated data `note`, the reference `brand` indexed and faceted in both
	 * scopes with the localized `share` and the histogram over it in both scopes, and the reference `tag` to a brand,
	 * not indexed, with the localized `label`.
	 */
	enum LocaleSetFixture {
		/**
		 * `$reference.localizedAttributes['share'] ?? 5`.
		 */
		DEFAULTED,
		/**
		 * `$reference.localizedAttributes['share']`, without a default.
		 */
		PLAIN,
		/**
		 * As {@link #DEFAULTED}; the product's reference `brand` carries a localized `label` with the default value
		 * `x`, and the brand has the locales of the product and a reflected reference `products` to the product
		 * inheriting `label` - an attribute no histogram reads, so a write of it on the brand's side reaches the
		 * product only as a propagated reference attribute mutation.
		 */
		REFLECTED
	}

	/**
	 * One upsert under test of a locale-set scenario, with the product it runs on and the states it must produce.
	 *
	 * @param name          the name of the scenario
	 * @param fixture       the schema
	 * @param preparation   stores the product before the scenario starts, or `null` when the upsert under test
	 *                      creates it
	 * @param before        the state before the upsert, see {@link #localeSetState}
	 * @param upsert        the upsert under test
	 * @param afterUpsert   the state right after the upsert, in memory and after a restart
	 * @param followUp      the follow-up change of the same references
	 * @param afterFollowUp the state after the follow-up, in memory and after a restart
	 * @param germanLabels  the German `label` of each brand reference of the product right after the upsert, see
	 *                      {@link #describeGermanLabels}, or `null` when the scenario does not check it
	 */
	private record LocaleSetScenario(
		@Nonnull String name,
		@Nonnull LocaleSetFixture fixture,
		@Nullable Consumer<EvitaSessionContract> preparation,
		@Nonnull String before,
		@Nonnull Consumer<EvitaSessionContract> upsert,
		@Nonnull String afterUpsert,
		@Nonnull Consumer<EvitaSessionContract> followUp,
		@Nonnull String afterFollowUp,
		@Nullable String germanLabels
	) {

		/**
		 * Creates a scenario that does not check the German labels.
		 *
		 * @param name          the name of the scenario
		 * @param fixture       the schema
		 * @param preparation   stores the product before the scenario starts, or `null` when the upsert under test
		 *                      creates it
		 * @param before        the state before the upsert
		 * @param upsert        the upsert under test
		 * @param afterUpsert   the state right after the upsert, in memory and after a restart
		 * @param followUp      the follow-up change of the same references
		 * @param afterFollowUp the state after the follow-up, in memory and after a restart
		 */
		LocaleSetScenario(
			@Nonnull String name,
			@Nonnull LocaleSetFixture fixture,
			@Nullable Consumer<EvitaSessionContract> preparation,
			@Nonnull String before,
			@Nonnull Consumer<EvitaSessionContract> upsert,
			@Nonnull String afterUpsert,
			@Nonnull Consumer<EvitaSessionContract> followUp,
			@Nonnull String afterFollowUp
		) {
			this(name, fixture, preparation, before, upsert, afterUpsert, followUp, afterFollowUp, null);
		}

		/**
		 * Returns the name of the scenario, which the parameterized test shows as its display name.
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
	 * Starts a new builder of the product carrying its German title only.
	 *
	 * @param session the session to create the product in
	 * @return the builder of the new product
	 */
	@Nonnull
	private static EntityBuilder newProductWithGermanTitle(@Nonnull EvitaSessionContract session) {
		return session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setAttribute(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt");
	}

	/**
	 * Starts a new builder of the product carrying its title in both locales.
	 *
	 * @param session the session to create the product in
	 * @return the builder
	 */
	@Nonnull
	private static EntityBuilder productWithBothTitles(@Nonnull EvitaSessionContract session) {
		return session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "product")
			.setAttribute(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt");
	}

	/**
	 * Stores the reference of the existing product to the subject brand with its English share.
	 *
	 * @param session the session to write in
	 * @param share   the English share
	 */
	private static void referenceWithEnglishShare(@Nonnull EvitaSessionContract session, int share) {
		session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REFERENCE_BRAND, BRAND_PK, whichIs -> {
					if (session.getEntitySchema(ENTITY_PRODUCT).orElseThrow()
						.getReferenceOrThrowException(REFERENCE_BRAND).getReferencedGroupType() != null) {
						whichIs.setGroup("brandGroup", 1);
					}
					whichIs.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, share);
				}
			)
			.upsertVia(session);
	}

	/**
	 * Creates the upsert of the German title of the product.
	 *
	 * @return the mutation
	 */
	@Nonnull
	private static UpsertAttributeMutation germanTitle() {
		return new UpsertAttributeMutation(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt");
	}

	/**
	 * Creates the change of the localized share of the product's reference to the subject brand in `locale` to
	 * `share`.
	 *
	 * @param locale the locale of the share
	 * @param share  the new share
	 * @return the mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation localizedShare(@Nonnull Locale locale, int share) {
		return share(BRAND_PK, locale, share);
	}

	/**
	 * Creates the change of the localized share of the product's reference to `brandPk` in `locale` to `share`.
	 *
	 * @param brandPk the referenced brand
	 * @param locale  the locale of the share
	 * @param share   the new share
	 * @return the mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation share(int brandPk, @Nonnull Locale locale, int share) {
		return new ReferenceAttributeMutation(
			new ReferenceKey(REFERENCE_BRAND, brandPk), new UpsertAttributeMutation(ATTRIBUTE_SHARE, locale, share)
		);
	}

	/**
	 * Creates the removal of the localized share of the product's reference to `brandPk` in `locale`.
	 *
	 * @param brandPk the referenced brand
	 * @param locale  the locale of the share
	 * @return the mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation removeShare(int brandPk, @Nonnull Locale locale) {
		return new ReferenceAttributeMutation(
			new ReferenceKey(REFERENCE_BRAND, brandPk), new RemoveAttributeMutation(ATTRIBUTE_SHARE, locale)
		);
	}

	/**
	 * Creates the insertion of the product's reference to `brandPk`.
	 *
	 * @param brandPk the referenced brand
	 * @return the mutation
	 */
	@Nonnull
	private static InsertReferenceMutation insert(int brandPk) {
		return new InsertReferenceMutation(new ReferenceKey(REFERENCE_BRAND, brandPk));
	}

	/**
	 * Creates the upsert of the product's title in `locale`.
	 *
	 * @param locale the locale
	 * @return the mutation
	 */
	@Nonnull
	private static UpsertAttributeMutation title(@Nonnull Locale locale) {
		return new UpsertAttributeMutation(ATTRIBUTE_TITLE, locale, "title " + locale.getLanguage());
	}

	/**
	 * Creates the removal of the product's title in `locale`.
	 *
	 * @param locale the locale
	 * @return the mutation
	 */
	@Nonnull
	private static RemoveAttributeMutation removeTitle(@Nonnull Locale locale) {
		return new RemoveAttributeMutation(ATTRIBUTE_TITLE, locale);
	}

	/**
	 * Creates the upsert of the product's associated data `note` in `locale`.
	 *
	 * @param locale the locale
	 * @return the mutation
	 */
	@Nonnull
	private static UpsertAssociatedDataMutation note(@Nonnull Locale locale) {
		return new UpsertAssociatedDataMutation(ASSOCIATED_DATA_NOTE, locale, "note " + locale.getLanguage());
	}

	/**
	 * Creates the removal of the product's associated data `note` in `locale`.
	 *
	 * @param locale the locale
	 * @return the mutation
	 */
	@Nonnull
	private static RemoveAssociatedDataMutation removeNote(@Nonnull Locale locale) {
		return new RemoveAssociatedDataMutation(ASSOCIATED_DATA_NOTE, locale);
	}

	/**
	 * Creates the move of the product to `scope`.
	 *
	 * @param scope the target scope
	 * @return the mutation
	 */
	@Nonnull
	private static SetEntityScopeMutation moveTo(@Nonnull Scope scope) {
		return new SetEntityScopeMutation(scope);
	}

	/**
	 * Creates one upsert of the existing product made of `mutations`.
	 *
	 * @param mutations the local mutations of the upsert, in the order they are applied
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> product(@Nonnull LocalMutation<?, ?>... mutations) {
		return upsertOf(ENTITY_PRODUCT, PRODUCT_PK, mutations);
	}

	/**
	 * Creates one upsert storing the product anew, made of `mutations`.
	 *
	 * @param mutations the local mutations of the upsert, in the order they are applied
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProduct(@Nonnull LocalMutation<?, ?>... mutations) {
		return session -> session.upsertEntity(
			new EntityUpsertMutation(ENTITY_PRODUCT, PRODUCT_PK, EntityExistence.MUST_NOT_EXIST, mutations)
		);
	}

	/**
	 * Creates one upsert of an existing entity made of `mutations`, applied in the given order.
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
	 * Returns the values given, for a readable expected state.
	 *
	 * @param values the values the product holds in a locale of a scope
	 * @return the same values
	 */
	@Nonnull
	private static int[] values(int... values) {
		return values;
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
			checks.add(() -> fail(label + " threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex));
		}
	}

	/**
	 * Describes a histogram spanning the values `min` to `max`.
	 *
	 * @param min the smallest value
	 * @param max the largest value
	 * @return the description {@link #describePreBatchHistogram} produces for such a histogram
	 */
	@Nonnull
	private static String range(int min, int max) {
		return "min=" + min + " max=" + max;
	}

	/**
	 * Formats `number` without trailing zeros and without an exponent.
	 *
	 * @param number the number
	 * @return the plain form of the number
	 */
	@Nonnull
	private static String plain(@Nullable BigDecimal number) {
		return number == null ? "null" : number.stripTrailingZeros().toPlainString();
	}

	/**
	 * Reads the histogram `histogramName` of the reference summary of the brand reference among the products of
	 * `scope` having `locale`.
	 *
	 * @param session       the session to query
	 * @param scope         the scope to query
	 * @param locale        the locale of the histogram
	 * @param histogramName the histogram
	 * @return the histogram, or `null` when the summary has none
	 */
	@Nullable
	private static HistogramContract readSummaryHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope scope,
		@Nonnull Locale locale,
		@Nonnull String histogramName
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(scope(scope), entityLocaleEquals(locale)),
				require(
					referenceSummaryOfReferenceWithHistograms(
						REFERENCE_BRAND, FacetStatisticsDepth.COUNTS, null, null,
						histogramStatistics(BUCKET_COUNT, histogramName)
					)
				)
			),
			EntityReference.class
		);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		final ReferenceGroupStatistics statistics = summary == null ?
			null : summary.getReferenceGroupStatistics(REFERENCE_BRAND);
		return statistics == null ? null : statistics.getHistogramStatistics(histogramName);
	}

	/**
	 * Describes the values a histogram holds in the type index of the brand reference of `scope` for `locale` and
	 * the owners holding each of them.
	 *
	 * @param evita         the engine
	 * @param scope         the scope
	 * @param locale        the locale
	 * @param histogramName the histogram
	 * @return the description, `{}` when the index or the histogram holds nothing
	 */
	@Nonnull
	private static String describeTypeIndex(
		@Nonnull Evita evita,
		@Nonnull Scope scope,
		@Nonnull Locale locale,
		@Nonnull String histogramName
	) {
		return describeTypeIndex(evita, scope, locale, histogramName, false);
	}

	/**
	 * Describes histogram values and owners in the selected type index or group store.
	 *
	 * @param evita the engine
	 * @param scope the scope
	 * @param locale the locale
	 * @param histogramName the histogram
	 * @param grouped whether to read the group store
	 * @return the values and owners
	 */
	@Nonnull
	private static String describeTypeIndex(
		@Nonnull Evita evita,
		@Nonnull Scope scope,
		@Nonnull Locale locale,
		@Nonnull String histogramName,
		boolean grouped
	) {
		final EntityCollectionContract collection = evita.getCatalogInstance(TEST_CATALOG)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
		final EntityIndex index = grouped ?
			IndexingTestSupport.getGroupHistogramStore(collection, scope, REFERENCE_BRAND, 1) :
			IndexingTestSupport.getReferencedEntityTypeIndex(collection, scope, REFERENCE_BRAND);
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
		for (ValueToRecord bucket : buckets) {
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
	 * Records one check per expectation of these scenarios: that the histogram is described as
	 * expected at the moment `stage` names.
	 *
	 * @param evita        the engine
	 * @param stage        names the moment of the check
	 * @param expectations the expected states of the histograms
	 * @param checks       the collected checks
	 */
	private static void recordPreBatchHistograms(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull List<PreBatchExpectation> expectations,
		@Nonnull List<Executable> checks
	) {
		for (PreBatchExpectation expectation : expectations) {
			final String subject = HISTOGRAM_SHARE + " of " + expectation.scope() + ", locale " + expectation.locale();
			try {
				final String actual = evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return describePreBatchHistogram(session, expectation.scope(), expectation.locale());
					}
				);
				checks.add(() -> assertEquals(expectation.expected(), actual, stage + ": " + subject));
			} catch (RuntimeException ex) {
				checks.add(() -> fail(stage + ": query of the " + subject + " threw " + ex, ex));
			}
		}
	}

	/**
	 * Describes the share histogram the reference summary of `scope` computes among the products having `locale`:
	 * its smallest and largest value, or the absence of the histogram.
	 *
	 * @param session the session to query
	 * @param scope   the scope to query
	 * @param locale  the locale of the histogram
	 * @return the description
	 */
	@Nonnull
	private static String describePreBatchHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope scope,
		@Nonnull Locale locale
	) {
		final HistogramContract histogram = readSummaryHistogram(session, scope, locale, HISTOGRAM_SHARE);
		return histogram == null ?
			NO_HISTOGRAM : "min=" + plain(histogram.getMin()) + " max=" + plain(histogram.getMax());
	}

	/**
	 * Defines the schemas of these scenarios and stores the brands and the anchor products.
	 *
	 * The product has a localized `title` and the locales English and German. Its reference to a brand is indexed for
	 * filtering and faceted in both scopes, and carries the localized `share` attribute and the share histogram over
	 * it in both scopes. An anchor product in each scope carries its title in both locales - stored by one upsert -
	 * and references two brands with the shares 10 and 20 in both locales - stored by a second upsert - so the
	 * histogram of each locale in each scope spans the range 10 to 20 before the product enters it.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void preBatchSetUpCatalog(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTRIBUTE_TITLE, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE,
				whichIs -> {
					whichIs
						.indexedForFilteringInScope(ALL_SCOPES)
						.facetedInScope(ALL_SCOPES)
						.withAttribute(
							ATTRIBUTE_SHARE, Integer.class,
							thatIs -> thatIs.filterableInScope(ALL_SCOPES).localized().nullable()
						);
					for (Scope scope : ALL_SCOPES) {
						whichIs.bucketedInScope(scope, HISTOGRAM_SHARE, ExpressionFactory.parse(LOCALIZED_SHARE_VALUE));
					}
				}
			)
			.updateVia(session);

		for (int brandPk = BRAND_PK; brandPk <= SECOND_ANCHOR_BRAND_PK; brandPk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND, brandPk));
		}
		storeAnchors(session);
	}

	/**
	 * Stores an anchor product in each scope: it carries its title in both locales - stored by one upsert - and
	 * references the two anchor brands with the shares 10 and 20 in both locales - stored by a second upsert - so the
	 * histogram of each locale in each scope spans the range 10 to 20 before the product enters it.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void storeAnchors(@Nonnull EvitaSessionContract session) {
		for (Scope scope : ALL_SCOPES) {
			final int anchorPk = scope == Scope.LIVE ? LIVE_ANCHOR_PK : ARCHIVED_ANCHOR_PK;
			// the anchor gets its locales by its titles first, and its references in a second upsert
			session.createNewEntity(ENTITY_PRODUCT, anchorPk)
				.setScope(scope)
				.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "anchor")
				.setAttribute(ATTRIBUTE_TITLE, Locale.GERMAN, "Anker")
				.upsertVia(session);
			final EntityBuilder anchor = session
				.getEntity(ENTITY_PRODUCT, anchorPk, ALL_SCOPES, entityFetchAllContent())
				.orElseThrow()
				.openForWrite();
			int brandPk = FIRST_ANCHOR_BRAND_PK;
			for (int anchorValue : new int[]{ANCHOR_VALUE, SECOND_ANCHOR_VALUE}) {
				anchor.setReference(
					REFERENCE_BRAND, brandPk++,
					whichIs -> whichIs
						.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, anchorValue)
						.setAttribute(ATTRIBUTE_SHARE, Locale.GERMAN, anchorValue)
				);
			}
			anchor.upsertVia(session);
		}
	}

	/**
	 * Describes the expected state of both locales of an owner-side scenario.
	 *
	 * @param englishIndex   the values and owners of the English histogram in the type index
	 * @param englishSummary the range of the English histogram in the reference summary
	 * @param germanIndex    the values and owners of the German histogram in the type index
	 * @param germanSummary  the range of the German histogram in the reference summary
	 * @return the description {@link #describeOwnerState} produces for that state
	 */
	@Nonnull
	private static String ownerState(
		@Nonnull String englishIndex,
		@Nonnull String englishSummary,
		@Nonnull String germanIndex,
		@Nonnull String germanSummary
	) {
		return "en index " + englishIndex + " summary " + englishSummary +
			" | de index " + germanIndex + " summary " + germanSummary;
	}

	/**
	 * Records the check that the catalog of an owner-side scenario is in the expected state at the moment `stage`
	 * names.
	 *
	 * @param evita    the engine
	 * @param stage    names the moment of the check
	 * @param expected the expected state
	 * @param checks   the collected checks
	 * @param grouped whether to read the group store
	 */
	private static void recordOwnerState(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull String expected,
		@Nonnull List<Executable> checks,
		boolean grouped
	) {
		try {
			final String actual = describeOwnerState(evita, grouped);
			final String expectedState = grouped ? expected.replaceAll(" summary (?:none|[0-9.]+)", "") : expected;
			checks.add(() -> assertEquals(expectedState, actual, stage));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": reading the state threw " + ex, ex));
		}
	}

	/**
	 * Describes, for each locale, the values the owner-side histogram holds in the live type index of the reference
	 * with their owners, and the range the reference summary computes among the live products having that locale.
	 *
	 * @param evita the engine
	 * @param grouped whether to read the group store
	 * @return the description
	 */
	@Nonnull
	private static String describeOwnerState(@Nonnull Evita evita, boolean grouped) {
		final StringBuilder result = new StringBuilder(256);
		for (Locale locale : LOCALES) {
			if (result.length() > 0) {
				result.append(" | ");
			}
			final HistogramContract histogram = grouped ? null : evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return readSummaryHistogram(session, Scope.LIVE, locale, OWNER_HISTOGRAM);
				}
			);
			result.append(locale.getLanguage())
				.append(" index ").append(describeTypeIndex(evita, Scope.LIVE, locale, OWNER_HISTOGRAM, grouped));
			if (!grouped) {
				result.append(" summary ")
					.append(histogram == null ? "none" : plain(histogram.getMin()) + ".." + plain(histogram.getMax()));
			}
		}
		return result.toString();
	}

	/**
	 * Defines the schemas of the owner-side scenarios and stores the anchor brands and the anchor product.
	 *
	 * The product has the localized `title` and the locales English and German. Its reference to a brand is indexed
	 * for filtering and faceted and carries `$reference.localizedAttributes['share'] ?? 7`. The anchor product
	 * carries its title in both locales - stored by one upsert - and references the two anchor brands with the shares
	 * 10 and 20 in both locales - stored by a second upsert.
	 *
	 * @param session the session of the catalog in warm-up
	 * @param grouped whether the reference has a group
	 */
	private static void ownerSetUpCatalog(@Nonnull EvitaSessionContract session, boolean grouped) {
		if (grouped) {
			session.defineEntitySchema("brandGroup").withoutGeneratedPrimaryKey().updateVia(session);
			session.createNewEntity("brandGroup", 1).upsertVia(session);
		}
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(LOCALES)
			.withAttribute(ATTRIBUTE_TITLE, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (grouped) {
						whichIs.withGroupTypeRelatedToEntity("brandGroup");
					}
					whichIs.indexedForFiltering().faceted()
						.withAttribute(
							ATTRIBUTE_SHARE, Integer.class,
							thatIs -> thatIs.localized().filterable().nullable()
						)
						.bucketed(
							OWNER_HISTOGRAM,
							ExpressionFactory.parse("$reference.localizedAttributes['" + ATTRIBUTE_SHARE + "'] ?? 7")
						);
					if (grouped) {
						whichIs.indexedWithComponentsInScope(
							Scope.LIVE, ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						);
					}
				}
			)
			.updateVia(session);

		final int[] anchorValues = {10, 20};
		int brandPk = FIRST_ANCHOR_BRAND_PK;
		for (int ignored : anchorValues) {
			session.createNewEntity(ENTITY_BRAND, brandPk++).upsertVia(session);
		}
		final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, LIVE_ANCHOR_PK);
		for (Locale locale : LOCALES) {
			anchor.setAttribute(ATTRIBUTE_TITLE, locale, "anchor " + locale.getLanguage());
		}
		anchor.upsertVia(session);
		final EntityBuilder anchorReferences = session
			.getEntity(ENTITY_PRODUCT, LIVE_ANCHOR_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite();
		brandPk = FIRST_ANCHOR_BRAND_PK;
		for (int anchorValue : anchorValues) {
			anchorReferences.setReference(
				REFERENCE_BRAND, brandPk++,
				whichIs -> {
					if (grouped) {
						whichIs.setGroup("brandGroup", 1);
					}
					for (Locale locale : LOCALES) {
						whichIs.setAttribute(ATTRIBUTE_SHARE, locale, anchorValue);
					}
				}
			);
		}
		anchorReferences.upsertVia(session);
	}

	/**
	 * Describes the expected state of a locale-set scenario: the values the product holds in each locale of each
	 * scope. The anchor product of each scope holds 10 and 20 in both locales; the description lists, for each scope
	 * and locale, the values of the type index with their owners and the range of the reference summary among the
	 * products having the locale - which is the anchors' range when the product holds nothing there, because the
	 * product holds a value in every locale it has when the value has a default, and a product lacking a locale
	 * holds nothing in it.
	 *
	 * @param liveEnglish     the product's values in the English histogram of the live scope
	 * @param liveGerman      the product's values in the German histogram of the live scope
	 * @param archivedEnglish the product's values in the English histogram of the archived scope
	 * @param archivedGerman  the product's values in the German histogram of the archived scope
	 * @return the description {@link #describeLocaleSetState} produces for that state
	 */
	@Nonnull
	private static String localeSetState(
		@Nonnull int[] liveEnglish,
		@Nonnull int[] liveGerman,
		@Nonnull int[] archivedEnglish,
		@Nonnull int[] archivedGerman
	) {
		return "L.en " + expectedLocale(LIVE_ANCHOR_PK, liveEnglish) +
			" | L.de " + expectedLocale(LIVE_ANCHOR_PK, liveGerman) +
			" | A.en " + expectedLocale(ARCHIVED_ANCHOR_PK, archivedEnglish) +
			" | A.de " + expectedLocale(ARCHIVED_ANCHOR_PK, archivedGerman);
	}

	/**
	 * Describes the expected state of one locale of one scope: the anchor's values 10 and 20 and the product's
	 * values in the type index, and the range of the summary.
	 *
	 * @param anchorPk      the anchor product of the scope
	 * @param productValues the values the product holds
	 * @return the description in the form {@link #describeLocaleSetState} produces
	 */
	@Nonnull
	private static String expectedLocale(int anchorPk, @Nonnull int[] productValues) {
		final Map<Integer, TreeSet<Integer>> owners = new TreeMap<>();
		owners.computeIfAbsent(ANCHOR_VALUE, ignored -> new TreeSet<>()).add(anchorPk);
		owners.computeIfAbsent(SECOND_ANCHOR_VALUE, ignored -> new TreeSet<>()).add(anchorPk);
		int min = ANCHOR_VALUE;
		int max = SECOND_ANCHOR_VALUE;
		for (int value : productValues) {
			owners.computeIfAbsent(value, ignored -> new TreeSet<>()).add(PRODUCT_PK);
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		final StringBuilder result = new StringBuilder(64);
		result.append("index {");
		boolean first = true;
		for (Entry<Integer, TreeSet<Integer>> entry : owners.entrySet()) {
			if (!first) {
				result.append(", ");
			}
			first = false;
			result.append(entry.getKey()).append('=').append(entry.getValue());
		}
		return result.append("} summary ").append(min).append("..").append(max).toString();
	}

	/**
	 * Records the check that the catalog of a locale-set scenario is in the expected state at the moment `stage`
	 * names.
	 *
	 * @param evita    the engine
	 * @param stage    names the moment of the check
	 * @param expected the expected state
	 * @param checks   the collected checks
	 */
	private static void recordLocaleSetState(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull String expected,
		@Nonnull List<Executable> checks
	) {
		try {
			final String actual = describeLocaleSetState(evita);
			checks.add(() -> assertEquals(expected, actual, stage));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": reading the state threw " + ex, ex));
		}
	}

	/**
	 * Describes, for each scope and locale, the values the share histogram holds in the type index of the brand
	 * reference with their owners, and the range the reference summary computes among the products of the scope
	 * having the locale.
	 *
	 * @param evita the engine
	 * @return the description
	 */
	@Nonnull
	private static String describeLocaleSetState(@Nonnull Evita evita) {
		final StringBuilder result = new StringBuilder(512);
		for (Scope scope : ALL_SCOPES) {
			for (Locale locale : LOCALES) {
				if (result.length() > 0) {
					result.append(" | ");
				}
				final HistogramContract histogram = evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return readSummaryHistogram(session, scope, locale, HISTOGRAM_SHARE);
					}
				);
				result.append(scope == Scope.LIVE ? "L." : "A.").append(locale.getLanguage())
					.append(" index ").append(describeTypeIndex(evita, scope, locale, HISTOGRAM_SHARE))
					.append(" summary ")
					.append(histogram == null ? "none" : plain(histogram.getMin()) + ".." + plain(histogram.getMax()));
			}
		}
		return result.toString();
	}

	/**
	 * Records the check that the brand references of the product hold the expected German `label` at the moment
	 * `stage` names; does nothing when `expected` is `null`.
	 *
	 * @param evita    the engine
	 * @param stage    names the moment of the check
	 * @param expected the expected description, see {@link #describeGermanLabels}, or `null`
	 * @param checks   the collected checks
	 */
	private static void recordGermanLabels(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nullable String expected,
		@Nonnull List<Executable> checks
	) {
		if (expected == null) {
			return;
		}
		try {
			final String actual = describeGermanLabels(evita);
			checks.add(() -> assertEquals(expected, actual, stage + ": the German labels of the brand references"));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": reading the German labels threw " + ex, ex));
		}
	}

	/**
	 * Describes the German `label` of every brand reference of the product, as `<brand>=<label>` separated by spaces
	 * in the order of the referenced brands; a missing label shows as `null`.
	 *
	 * @param evita the engine
	 * @return the description
	 */
	@Nonnull
	private static String describeGermanLabels(@Nonnull Evita evita) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session
					.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
					.orElseThrow();
				final StringBuilder result = new StringBuilder(32);
				for (ReferenceContract reference : product.getReferences(REFERENCE_BRAND)) {
					if (result.length() > 0) {
						result.append(' ');
					}
					final Serializable label = reference.getAttribute(ATTRIBUTE_LABEL, Locale.GERMAN);
					result.append(reference.getReferencedPrimaryKey()).append('=').append(label);
				}
				return result.toString();
			}
		);
	}

	/**
	 * Defines the schemas of a locale-set fixture and stores the brands and the anchor products.
	 *
	 * The product has the locales English and German, the localized `title` and the localized associated data
	 * `note`. Its reference `brand` is indexed for filtering and faceted in both scopes, carries the localized
	 * `share` and the share histogram over it in both scopes, with the default {@link #DEFAULT_SHARE} unless the
	 * fixture is {@link LocaleSetFixture#PLAIN}. Its reference `tag` to a brand is not indexed and carries the
	 * localized `label`. The {@link LocaleSetFixture#REFLECTED} fixture adds the localized `label` with a default
	 * value to the reference `brand` and a reflected reference of the brand to the product inheriting it.
	 * An anchor product in each scope holds the shares 10 and 20 in both locales.
	 *
	 * @param session the session of the catalog in warm-up
	 * @param fixture the fixture
	 */
	private static void localeSetSetUpCatalog(
		@Nonnull EvitaSessionContract session,
		@Nonnull LocaleSetFixture fixture
	) {
		if (fixture == LocaleSetFixture.REFLECTED) {
			session.defineEntitySchema(ENTITY_BRAND)
				.withoutGeneratedPrimaryKey()
				.withLocale(LOCALES)
				.updateVia(session);
		} else {
			session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		}
		final String histogramValue = fixture == LocaleSetFixture.PLAIN ?
			LOCALIZED_SHARE_VALUE : LOCALIZED_SHARE_VALUE + " ?? " + DEFAULT_SHARE;
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(LOCALES)
			.withAttribute(ATTRIBUTE_TITLE, String.class, whichIs -> whichIs.localized().nullable())
			.withAssociatedData(ASSOCIATED_DATA_NOTE, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE,
				whichIs -> {
					whichIs
						.indexedForFilteringInScope(ALL_SCOPES)
						.facetedInScope(ALL_SCOPES)
						.withAttribute(
							ATTRIBUTE_SHARE, Integer.class,
							thatIs -> thatIs.filterableInScope(ALL_SCOPES).localized().nullable()
						);
					if (fixture == LocaleSetFixture.REFLECTED) {
						whichIs.withAttribute(
							ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().withDefaultValue("x")
						);
					}
					for (Scope scope : ALL_SCOPES) {
						whichIs.bucketedInScope(scope, HISTOGRAM_SHARE, ExpressionFactory.parse(histogramValue));
					}
				}
			)
			.withReferenceToEntity(
				REFERENCE_TAG, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.withAttribute(
					ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().nullable()
				)
			)
			.updateVia(session);
		if (fixture == LocaleSetFixture.REFLECTED) {
			session.defineEntitySchema(ENTITY_BRAND)
				.withReflectedReferenceToEntity(
					REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_BRAND,
					whichIs -> whichIs.withAttributesInherited(ATTRIBUTE_LABEL)
				)
				.updateVia(session);
		}

		for (int brandPk = BRAND_PK; brandPk <= SECOND_ANCHOR_BRAND_PK; brandPk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND, brandPk));
		}
		storeAnchors(session);
	}

}
