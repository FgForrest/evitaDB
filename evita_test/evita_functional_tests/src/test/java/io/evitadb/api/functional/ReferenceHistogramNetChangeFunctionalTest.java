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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Verifies that a reference histogram holds, for every reference of a product, exactly the values the stored
 * reference implies - once each - after one entity upsert that changes what the histogram derives from: a reference
 * attribute, the group of the reference, an attribute of the product the histogram condition reads, the scope of the
 * product, the locales of the product, or the existence of the reference.
 *
 * A histogram keeps each value of a product once however often it was added and counts the additions aside, so a
 * value added twice looks the same as one added once until it is removed. Every scenario therefore checks the
 * histogram right after the upsert - which tells a stale or a missing value - and again after a follow-up upsert
 * that moves the value into the range the anchor products span, or removes it, after which nothing but the anchors
 * may remain, in memory and after a restart.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("One net change of a reference histogram per reference in one upsert")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class ReferenceHistogramNetChangeFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_BRAND_GROUP = "brandGroup";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_SHARE = "share";
	private static final String ATTRIBUTE_SHARES = "shares";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final String ATTRIBUTE_HIDDEN = "hidden";
	private static final String ATTRIBUTE_ACTIVE = "active";
	private static final String ATTRIBUTE_NOTE = "note";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_VISIBLE = "visible";
	private static final String ATTRIBUTE_SIZE = "size";
	private static final String HISTOGRAM_SHARE = "shareHistogram";
	private static final String HISTOGRAM_SECOND_SHARE = "secondShareHistogram";
	private static final String HISTOGRAM_WEIGHT = "weightHistogram";
	private static final String HISTOGRAM_SIZE = "sizeHistogram";
	private static final Scope[] ALL_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final int PRODUCT_PK = 1;
	private static final int LIVE_ANCHOR_PK = 100;
	private static final int ARCHIVED_ANCHOR_PK = 101;
	private static final int BRAND_PK = 10;
	private static final int SIBLING_BRAND_PK = 11;
	private static final int FIRST_ANCHOR_BRAND_PK = 12;
	private static final int LAST_BRAND_PK = 15;
	private static final int FIRST_GROUP_PK = 1;
	private static final int SECOND_GROUP_PK = 2;
	private static final int ANCHOR_VALUE = 10;
	private static final int SECOND_ANCHOR_VALUE = 20;
	private static final int INSIDE_ANCHOR_RANGE = 15;
	private static final int BUCKET_COUNT = 10;
	/**
	 * The description of a histogram holding only the values of the anchor product.
	 */
	private static final String ANCHORS_ONLY = range(ANCHOR_VALUE, SECOND_ANCHOR_VALUE);
	/**
	 * The description of a histogram that does not exist.
	 */
	private static final String NO_HISTOGRAM = "no histogram";
	private static final String SHARE_VALUE = "$reference.attributes['" + ATTRIBUTE_SHARE + "']";
	private static final String LOCALIZED_SHARE_VALUE = "$reference.localizedAttributes['" + ATTRIBUTE_SHARE + "']";
	private static final String SHARES_VALUE = "$reference.attributes['" + ATTRIBUTE_SHARES + "']";
	private static final String WEIGHT_VALUE = "$reference.attributes['" + ATTRIBUTE_WEIGHT + "']";
	private static final String SIZE_VALUE = "$reference.referencedEntity?.attributes['" + ATTRIBUTE_SIZE + "']";
	private static final String REFERENCE_NOT_HIDDEN =
		"($reference.attributes['" + ATTRIBUTE_HIDDEN + "'] ?? false) == false";
	private static final String REFERENCE_ACTIVE =
		"($reference.attributes['" + ATTRIBUTE_ACTIVE + "'] ?? false) == true";
	private static final String ENTITY_VISIBLE = "($entity.attributes['" + ATTRIBUTE_VISIBLE + "'] ?? false) == true";
	private static final String ENTITY_PRIORITIZED = "($entity.attributes['" + ATTRIBUTE_PRIORITY + "'] ?? 0) > 0";

	/**
	 * The schema and the anchor products one scenario runs on.
	 *
	 * @param grouped             TRUE when the reference to the brand is grouped by a brand group and indexed with
	 *                            both its entity and its group component
	 * @param anchorsInFirstGroup TRUE when the anchor products reference brands in the first group as well, FALSE
	 *                            when only in the second one; ignored where the reference is not grouped
	 * @param weightDefault       the default value of the `weight` reference attribute, or `null` for none
	 * @param histograms          the value expression of every histogram of the reference, by the histogram name
	 * @param condition           the condition admitting a reference to the histograms, or `null` for none
	 * @param localized           TRUE when the `share` reference attribute is localized, and the anchor products
	 *                            carry their `title` and their shares in English and in German
	 */
	private record Fixture(
		boolean grouped,
		boolean anchorsInFirstGroup,
		@Nullable Integer weightDefault,
		@Nonnull Map<String, String> histograms,
		@Nullable String condition,
		boolean localized
	) {

		/**
		 * Creates the fixture of a reference that is not grouped and carries one share histogram.
		 *
		 * @param condition the condition admitting a reference to the histogram, or `null` for none
		 * @return the fixture
		 */
		@Nonnull
		static Fixture ungrouped(@Nullable String condition) {
			return ungrouped(histogramsOf(HISTOGRAM_SHARE, SHARE_VALUE), condition, null);
		}

		/**
		 * Creates the fixture of a reference that is not grouped.
		 *
		 * @param histograms    the value expression of every histogram, by the histogram name
		 * @param condition     the condition admitting a reference to the histograms, or `null` for none
		 * @param weightDefault the default value of the `weight` reference attribute, or `null` for none
		 * @return the fixture
		 */
		@Nonnull
		static Fixture ungrouped(
			@Nonnull Map<String, String> histograms,
			@Nullable String condition,
			@Nullable Integer weightDefault
		) {
			return new Fixture(false, false, weightDefault, histograms, condition, false);
		}

		/**
		 * Creates the fixture of a grouped reference with anchors in both groups.
		 *
		 * @param histograms the value expression of every histogram, by the histogram name
		 * @param condition  the condition admitting a reference to the histograms, or `null` for none
		 * @return the fixture
		 */
		@Nonnull
		static Fixture grouped(@Nonnull Map<String, String> histograms, @Nullable String condition) {
			return new Fixture(true, true, null, histograms, condition, false);
		}

		/**
		 * Creates the fixture of a grouped reference carrying one share histogram whose anchors sit in the second
		 * group only, so a product referencing a brand in the first group is the only owner of that group.
		 *
		 * @return the fixture
		 */
		@Nonnull
		static Fixture groupedWithEmptyFirstGroup() {
			return new Fixture(true, false, null, histogramsOf(HISTOGRAM_SHARE, SHARE_VALUE), null, false);
		}

		/**
		 * Creates the fixture of a reference that is not grouped and carries one share histogram over the localized
		 * `share` attribute.
		 *
		 * @return the fixture
		 */
		@Nonnull
		static Fixture localizedShare() {
			return new Fixture(false, false, null, histogramsOf(HISTOGRAM_SHARE, LOCALIZED_SHARE_VALUE), null, true);
		}
	}

	/**
	 * One expected state of a histogram.
	 *
	 * @param scope     the scope whose histogram is checked
	 * @param groupPk   the primary key of the group whose histogram is checked, or `null` for the ungrouped one
	 * @param histogram the name of the histogram
	 * @param expected  the expected description, see {@link #describeHistogram}
	 * @param locale    the locale of the histogram, or `null` for a histogram over a non-localized value
	 */
	private record Expectation(
		@Nonnull Scope scope,
		@Nullable Integer groupPk,
		@Nonnull String histogram,
		@Nonnull String expected,
		@Nullable Locale locale
	) {
	}

	/**
	 * One upsert under test, with the data it runs on and the states of the histograms it must produce.
	 *
	 * @param name          the name of the scenario
	 * @param fixture       the schema and the anchor products
	 * @param product       sets up the product before it is stored for the first time
	 * @param preparation   changes the stored product before the scenario starts, or `null` for no change
	 * @param before        the histograms before the upsert, proving the starting state is the intended one
	 * @param upsert        the upsert under test
	 * @param afterUpsert   the histograms right after the upsert
	 * @param followUp      the upsert moving the values away, or `null` when the upsert under test left none
	 * @param afterFollowUp the histograms after the follow-up, in memory and after a restart
	 */
	private record Scenario(
		@Nonnull String name,
		@Nonnull Fixture fixture,
		@Nonnull Consumer<EntityBuilder> product,
		@Nullable Consumer<EvitaSessionContract> preparation,
		@Nonnull List<Expectation> before,
		@Nonnull Consumer<EvitaSessionContract> upsert,
		@Nonnull List<Expectation> afterUpsert,
		@Nullable Consumer<EvitaSessionContract> followUp,
		@Nonnull List<Expectation> afterFollowUp
	) {

		/**
		 * Creates a scenario whose product needs no change after it is stored.
		 *
		 * @param name          the name of the scenario
		 * @param fixture       the schema and the anchor products
		 * @param product       sets up the product before it is stored for the first time
		 * @param before        the histograms before the upsert
		 * @param upsert        the upsert under test
		 * @param afterUpsert   the histograms right after the upsert
		 * @param followUp      the upsert moving the values away, or `null`
		 * @param afterFollowUp the histograms after the follow-up, in memory and after a restart
		 */
		Scenario(
			@Nonnull String name,
			@Nonnull Fixture fixture,
			@Nonnull Consumer<EntityBuilder> product,
			@Nonnull List<Expectation> before,
			@Nonnull Consumer<EvitaSessionContract> upsert,
			@Nonnull List<Expectation> afterUpsert,
			@Nullable Consumer<EvitaSessionContract> followUp,
			@Nonnull List<Expectation> afterFollowUp
		) {
			this(name, fixture, product, null, before, upsert, afterUpsert, followUp, afterFollowUp);
		}

		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}
	}

	/**
	 * The scenarios of the upserts suspected or known to leave a histogram that does not match the stored
	 * references, together with their control variants.
	 *
	 * @return arguments of {@link #shouldHoldEachStoredValueOnceWhenUpsertReEvaluatesReference}
	 */
	@Nonnull
	static Stream<Arguments> reEvaluationScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(32);
		addHistogramPairScenarios(scenarios);
		addEntityAttributeScenarios(scenarios);
		addGroupAndRemovalScenarios(scenarios);
		addScopeChangeScenarios(scenarios);
		return inBothCatalogStates(scenarios);
	}

	/**
	 * The scenarios the engine handles correctly, which a change of the way the histogram is maintained must keep.
	 *
	 * @return arguments of {@link #shouldKeepHistogramExactWhenReferenceLeavesItsGroupOrIsReplaced}
	 */
	@Nonnull
	static Stream<Arguments> guardScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(16);
		final Fixture soleOwner = Fixture.groupedWithEmptyFirstGroup();
		final Consumer<EntityBuilder> productInFirstGroup = product -> product.setReference(
			REFERENCE_BRAND, BRAND_PK,
			whichIs -> whichIs.setGroup(ENTITY_BRAND_GROUP, FIRST_GROUP_PK).setAttribute(ATTRIBUTE_SHARE, 1)
		);
		final List<Expectation> soleOwnerBefore = List.of(
			group(Scope.LIVE, FIRST_GROUP_PK, HISTOGRAM_SHARE, range(1, 1)),
			group(Scope.LIVE, SECOND_GROUP_PK, HISTOGRAM_SHARE, ANCHORS_ONLY)
		);
		final List<Expectation> soleOwnerGone = List.of(
			group(Scope.LIVE, FIRST_GROUP_PK, HISTOGRAM_SHARE, NO_HISTOGRAM),
			group(Scope.LIVE, SECOND_GROUP_PK, HISTOGRAM_SHARE, ANCHORS_ONLY)
		);
		scenarios.add(new Scenario(
			"SOLE_OWNER_OF_GROUP_CHANGES_GROUP", soleOwner, productInFirstGroup, soleOwnerBefore,
			upsertOf(new SetReferenceGroupMutation(brand(BRAND_PK), SECOND_GROUP_PK)),
			List.of(
				group(Scope.LIVE, FIRST_GROUP_PK, HISTOGRAM_SHARE, NO_HISTOGRAM),
				group(Scope.LIVE, SECOND_GROUP_PK, HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE))
			),
			upsertOf(referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)),
			soleOwnerGone
		));
		scenarios.add(new Scenario(
			"SOLE_OWNER_OF_GROUP_LOSES_REFERENCE", soleOwner, productInFirstGroup, soleOwnerBefore,
			upsertOf(new RemoveReferenceMutation(brand(BRAND_PK))),
			soleOwnerGone, null, soleOwnerGone
		));
		scenarios.add(new Scenario(
			"SOLE_OWNER_OF_GROUP_IS_REMOVED", soleOwner, productInFirstGroup, soleOwnerBefore,
			session -> session.deleteEntity(ENTITY_PRODUCT, PRODUCT_PK),
			soleOwnerGone, null, soleOwnerGone
		));

		final Consumer<EntityBuilder> productWithShare = product -> product.setReference(
			REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 1)
		);
		final List<Expectation> replaced = List.of(live(HISTOGRAM_SHARE, range(5, SECOND_ANCHOR_VALUE)));
		final Consumer<EvitaSessionContract> moveShare =
			upsertOf(referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE));
		scenarios.add(new Scenario(
			"REFERENCE_REMOVED_AND_INSERTED_AGAIN", Fixture.ungrouped(null), productWithShare,
			List.of(live(HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE))),
			session -> {
				final ReferenceKey stored = session
					.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
					.orElseThrow()
					.getReference(REFERENCE_BRAND, BRAND_PK)
					.orElseThrow()
					.getReferenceKey();
				final ReferenceKey inserted = new ReferenceKey(REFERENCE_BRAND, BRAND_PK, -1);
				upsertOf(
					new RemoveReferenceMutation(stored),
					new InsertReferenceMutation(inserted),
					new ReferenceAttributeMutation(inserted, new UpsertAttributeMutation(ATTRIBUTE_SHARE, 5))
				).accept(session);
			},
			replaced, moveShare, List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY))
		));
		scenarios.add(new Scenario(
			"REFERENCE_REMOVED_AND_INSERTED_AGAIN_BY_BUILDER", Fixture.ungrouped(null), productWithShare,
			List.of(live(HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE))),
			session -> session
				.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.removeReference(REFERENCE_BRAND, BRAND_PK)
				.setReference(REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 5))
				.upsertVia(session),
			replaced, moveShare, List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY))
		));

		return inBothCatalogStates(scenarios);
	}

	/**
	 * The scenarios of a histogram over a localized reference attribute: the upsert that moves the product to the
	 * archive adds or removes a locale of the product first, and a control changes one localized value twice without
	 * any locale or scope change.
	 *
	 * @return arguments of {@link #shouldHoldEachLocalizedValueOnceWhenUpsertChangesLocalesOrScope}
	 */
	@Nonnull
	static Stream<Arguments> localizedScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(4);
		final Fixture fixture = Fixture.localizedShare();
		// a localized share reaches the histogram only in the locales the product had before the upsert that sets it,
		// so the product gets its locales by its titles first and its reference to the brand in a second upsert
		final Consumer<EntityBuilder> englishProduct =
			builder -> builder.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "product");
		final Consumer<EvitaSessionContract> englishShare = session -> session
			.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, 1)
			)
			.upsertVia(session);
		final List<Expectation> englishBefore = List.of(
			localized(Scope.LIVE, Locale.ENGLISH, range(1, SECOND_ANCHOR_VALUE)),
			localized(Scope.LIVE, Locale.GERMAN, ANCHORS_ONLY),
			localized(Scope.ARCHIVED, Locale.ENGLISH, ANCHORS_ONLY),
			localized(Scope.ARCHIVED, Locale.GERMAN, ANCHORS_ONLY)
		);
		final List<Expectation> anchorsOnly = List.of(
			localized(Scope.LIVE, Locale.ENGLISH, ANCHORS_ONLY),
			localized(Scope.LIVE, Locale.GERMAN, ANCHORS_ONLY),
			localized(Scope.ARCHIVED, Locale.ENGLISH, ANCHORS_ONLY),
			localized(Scope.ARCHIVED, Locale.GERMAN, ANCHORS_ONLY)
		);
		// the German title gives the product the German locale, its German share is set, and it moves to the archive
		scenarios.add(new Scenario(
			"LOCALE_ADDED_THEN_SCOPE_CHANGE", fixture, englishProduct, englishShare, englishBefore,
			upsertOf(
				new UpsertAttributeMutation(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt"),
				localizedShare(Locale.GERMAN, 2),
				new SetEntityScopeMutation(Scope.ARCHIVED)
			),
			List.of(
				localized(Scope.LIVE, Locale.ENGLISH, ANCHORS_ONLY),
				localized(Scope.LIVE, Locale.GERMAN, ANCHORS_ONLY),
				localized(Scope.ARCHIVED, Locale.ENGLISH, range(1, SECOND_ANCHOR_VALUE)),
				localized(Scope.ARCHIVED, Locale.GERMAN, range(2, SECOND_ANCHOR_VALUE))
			),
			upsertOf(
				localizedShare(Locale.ENGLISH, INSIDE_ANCHOR_RANGE),
				localizedShare(Locale.GERMAN, INSIDE_ANCHOR_RANGE)
			),
			anchorsOnly
		));
		// the German title and share are removed, which takes the German locale from the product, and it moves to the
		// archive; a histogram reads only the products having its locale, so the follow-up gives the product the
		// German locale back, and a German value left in the archive would show then
		scenarios.add(new Scenario(
			"LOCALE_REMOVED_THEN_SCOPE_CHANGE", fixture,
			builder -> builder
				.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "product")
				.setAttribute(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt"),
			session -> session
				.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REFERENCE_BRAND, BRAND_PK,
					whichIs -> whichIs
						.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, 1)
						.setAttribute(ATTRIBUTE_SHARE, Locale.GERMAN, 3)
				)
				.upsertVia(session),
			List.of(
				localized(Scope.LIVE, Locale.ENGLISH, range(1, SECOND_ANCHOR_VALUE)),
				localized(Scope.LIVE, Locale.GERMAN, range(3, SECOND_ANCHOR_VALUE)),
				localized(Scope.ARCHIVED, Locale.ENGLISH, ANCHORS_ONLY),
				localized(Scope.ARCHIVED, Locale.GERMAN, ANCHORS_ONLY)
			),
			upsertOf(
				new RemoveAttributeMutation(ATTRIBUTE_TITLE, Locale.GERMAN),
				new ReferenceAttributeMutation(
					brand(BRAND_PK), new RemoveAttributeMutation(ATTRIBUTE_SHARE, Locale.GERMAN)
				),
				new SetEntityScopeMutation(Scope.ARCHIVED)
			),
			List.of(
				localized(Scope.LIVE, Locale.ENGLISH, ANCHORS_ONLY),
				localized(Scope.LIVE, Locale.GERMAN, ANCHORS_ONLY),
				localized(Scope.ARCHIVED, Locale.ENGLISH, range(1, SECOND_ANCHOR_VALUE)),
				localized(Scope.ARCHIVED, Locale.GERMAN, ANCHORS_ONLY)
			),
			upsertOf(
				new UpsertAttributeMutation(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt"),
				localizedShare(Locale.ENGLISH, INSIDE_ANCHOR_RANGE),
				localizedShare(Locale.GERMAN, INSIDE_ANCHOR_RANGE)
			),
			anchorsOnly
		));
		// no locale and no scope change: the English share changes twice
		scenarios.add(new Scenario(
			"LOCALIZED_SHARE_CHANGED_TWICE", fixture, englishProduct, englishShare, englishBefore,
			upsertOf(localizedShare(Locale.ENGLISH, 2), localizedShare(Locale.ENGLISH, 3)),
			List.of(
				localized(Scope.LIVE, Locale.ENGLISH, range(3, SECOND_ANCHOR_VALUE)),
				localized(Scope.LIVE, Locale.GERMAN, ANCHORS_ONLY),
				localized(Scope.ARCHIVED, Locale.ENGLISH, ANCHORS_ONLY),
				localized(Scope.ARCHIVED, Locale.GERMAN, ANCHORS_ONLY)
			),
			upsertOf(localizedShare(Locale.ENGLISH, INSIDE_ANCHOR_RANGE)),
			anchorsOnly
		));
		return inBothCatalogStates(scenarios);
	}

	/**
	 * Applies the upsert of the scenario and checks the histograms right after it, after the follow-up that moves
	 * the values away, and after a restart.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("reEvaluationScenarios")
	@DisplayName("a reference re-evaluated by one upsert leaves each stored value in the histogram once")
	void shouldHoldEachStoredValueOnceWhenUpsertReEvaluatesReference(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Applies the upsert of the scenario - a product that was the only owner of a group leaving it, or a reference
	 * removed and inserted again - and checks the histograms right after it, after the follow-up that moves the
	 * values away, and after a restart.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("guardScenarios")
	@DisplayName("a reference leaving its group or replaced by a new one leaves an exact histogram")
	void shouldKeepHistogramExactWhenReferenceLeavesItsGroupOrIsReplaced(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Changes the locales of the product together with its localized share and its scope, or changes the share
	 * twice without either, and checks the histogram of each locale in both scopes right after the upsert, after the
	 * follow-up that moves the shares among the anchors, and after a restart.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("localizedScenarios")
	@DisplayName("a localized reference value reaches the histogram of each of its locales once")
	void shouldHoldEachLocalizedValueOnceWhenUpsertChangesLocalesOrScope(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Adds the scenarios of a reference carrying two histograms.
	 *
	 * @param scenarios the collected scenarios
	 */
	private static void addHistogramPairScenarios(@Nonnull List<Scenario> scenarios) {
		// one attribute changes, the histogram of the other one must stay as it is
		final Consumer<EntityBuilder> product = builder -> builder.setReference(
			REFERENCE_BRAND, BRAND_PK,
			whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 1).setAttribute(ATTRIBUTE_WEIGHT, 1)
		);
		scenarios.add(new Scenario(
			"TWO_HISTOGRAMS_ONE_SOURCE_CHANGES",
			Fixture.ungrouped(
				histogramsOf(HISTOGRAM_SHARE, SHARE_VALUE, HISTOGRAM_WEIGHT, WEIGHT_VALUE), null, null
			),
			product,
			List.of(
				live(HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE)),
				live(HISTOGRAM_WEIGHT, range(1, SECOND_ANCHOR_VALUE))
			),
			upsertOf(referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, 2)),
			List.of(
				live(HISTOGRAM_SHARE, range(2, SECOND_ANCHOR_VALUE)),
				live(HISTOGRAM_WEIGHT, range(1, SECOND_ANCHOR_VALUE))
			),
			upsertOf(referenceAttribute(BRAND_PK, ATTRIBUTE_WEIGHT, INSIDE_ANCHOR_RANGE)),
			List.of(
				live(HISTOGRAM_SHARE, range(2, SECOND_ANCHOR_VALUE)),
				live(HISTOGRAM_WEIGHT, ANCHORS_ONLY)
			)
		));
		// both histograms read the one attribute that changes
		scenarios.add(new Scenario(
			"TWO_HISTOGRAMS_SHARING_ONE_SOURCE",
			Fixture.ungrouped(
				histogramsOf(HISTOGRAM_SHARE, SHARE_VALUE, HISTOGRAM_SECOND_SHARE, SHARE_VALUE), null, null
			),
			product,
			List.of(
				live(HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE)),
				live(HISTOGRAM_SECOND_SHARE, range(1, SECOND_ANCHOR_VALUE))
			),
			upsertOf(referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, 2)),
			List.of(
				live(HISTOGRAM_SHARE, range(2, SECOND_ANCHOR_VALUE)),
				live(HISTOGRAM_SECOND_SHARE, range(2, SECOND_ANCHOR_VALUE))
			),
			upsertOf(referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)),
			List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY), live(HISTOGRAM_SECOND_SHARE, ANCHORS_ONLY))
		));
		// a new reference gets its share explicitly and its weight from the default value, in a later phase
		scenarios.add(new Scenario(
			"TWO_HISTOGRAMS_ONE_SOURCE_DEFAULTED",
			Fixture.ungrouped(
				histogramsOf(HISTOGRAM_SHARE, SHARE_VALUE, HISTOGRAM_WEIGHT, WEIGHT_VALUE), null, 5
			),
			builder -> {
			},
			List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY), live(HISTOGRAM_WEIGHT, ANCHORS_ONLY)),
			upsertOf(new InsertReferenceMutation(brand(BRAND_PK)), referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, 1)),
			List.of(
				live(HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE)),
				live(HISTOGRAM_WEIGHT, range(5, SECOND_ANCHOR_VALUE))
			),
			upsertOf(referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)),
			List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY))
		));
	}

	/**
	 * Adds the scenarios of an attribute of the product read by the histogram condition changing.
	 *
	 * @param scenarios the collected scenarios
	 */
	private static void addEntityAttributeScenarios(@Nonnull List<Scenario> scenarios) {
		// only one of two references sharing a share satisfies the reference half of the condition
		for (boolean contributingFirst : new boolean[]{true, false}) {
			final int contributing = contributingFirst ? BRAND_PK : SIBLING_BRAND_PK;
			final int other = contributingFirst ? SIBLING_BRAND_PK : BRAND_PK;
			scenarios.add(new Scenario(
				"ENTITY_ATTRIBUTE_CHANGES_CONTRIBUTING_REFERENCE_" + (contributingFirst ? "FIRST" : "SECOND"),
				Fixture.ungrouped(ENTITY_PRIORITIZED + " && " + REFERENCE_ACTIVE),
				builder -> builder.setAttribute(ATTRIBUTE_PRIORITY, 1),
				// the references come in an upsert of their own, which does not touch the attribute of the product
				session -> twoReferencesWithOneActive(
					session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent()).orElseThrow().openForWrite(),
					contributing, other
				).upsertVia(session),
				List.of(live(HISTOGRAM_SHARE, range(5, SECOND_ANCHOR_VALUE))),
				upsertOf(new UpsertAttributeMutation(ATTRIBUTE_PRIORITY, 2)),
				List.of(live(HISTOGRAM_SHARE, range(5, SECOND_ANCHOR_VALUE))),
				upsertOf(referenceAttribute(contributing, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)),
				List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY))
			));
		}
		// the attribute the condition reads is set by the upsert that gives the product both references
		for (boolean contributingFirst : new boolean[]{true, false}) {
			final int contributing = contributingFirst ? BRAND_PK : SIBLING_BRAND_PK;
			final int other = contributingFirst ? SIBLING_BRAND_PK : BRAND_PK;
			scenarios.add(new Scenario(
				"ENTITY_ATTRIBUTE_SET_WITH_NEW_REFERENCES_CONTRIBUTING_" + (contributingFirst ? "FIRST" : "SECOND"),
				Fixture.ungrouped(ENTITY_PRIORITIZED + " && " + REFERENCE_ACTIVE),
				builder -> {
				},
				List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY)),
				session -> twoReferencesWithOneActive(
					session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent()).orElseThrow().openForWrite(),
					contributing, other
				).setAttribute(ATTRIBUTE_PRIORITY, 1).upsertVia(session),
				List.of(live(HISTOGRAM_SHARE, range(5, SECOND_ANCHOR_VALUE))),
				upsertOf(referenceAttribute(contributing, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)),
				List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY))
			));
		}
		// the product becomes visible and the share of its reference changes
		final Consumer<EntityBuilder> invisible = builder -> builder
			.setAttribute(ATTRIBUTE_VISIBLE, false)
			.setReference(REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 1));
		final Consumer<EvitaSessionContract> showAndChangeByBuilder = session -> session
			.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTRIBUTE_VISIBLE, true)
			.updateReference(REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 2))
			.upsertVia(session);
		final Consumer<EvitaSessionContract> showAndChange = upsertOf(
			new UpsertAttributeMutation(ATTRIBUTE_VISIBLE, true), referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, 2)
		);
		for (boolean byBuilder : new boolean[]{false, true}) {
			scenarios.add(new Scenario(
				"ENTITY_ATTRIBUTE_AND_REFERENCE_ATTRIBUTE" + (byBuilder ? "_BY_BUILDER" : ""),
				Fixture.ungrouped(ENTITY_VISIBLE),
				invisible,
				List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY)),
				byBuilder ? showAndChangeByBuilder : showAndChange,
				List.of(live(HISTOGRAM_SHARE, range(2, SECOND_ANCHOR_VALUE))),
				upsertOf(referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)),
				List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY))
			));
		}
		// the same, with a sibling reference that is in the histogram from the start with the same share
		scenarios.add(new Scenario(
			"ENTITY_ATTRIBUTE_AND_REFERENCE_ATTRIBUTE_WITH_SIBLING",
			Fixture.ungrouped(ENTITY_VISIBLE + " || " + REFERENCE_ACTIVE),
			builder -> {
				invisible.accept(builder);
				builder.setReference(
					REFERENCE_BRAND, SIBLING_BRAND_PK,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 1).setAttribute(ATTRIBUTE_ACTIVE, true)
				);
			},
			List.of(live(HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE))),
			showAndChange,
			List.of(live(HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE))),
			upsertOf(
				referenceAttribute(BRAND_PK, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE),
				referenceAttribute(SIBLING_BRAND_PK, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)
			),
			List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY))
		));
	}

	/**
	 * Gives the product two references with the share 5, of which only the one to `contributing` is active.
	 *
	 * @param product      the product being built
	 * @param contributing the primary key of the brand the active reference targets
	 * @param other        the primary key of the brand the inactive reference targets
	 * @return the product being built
	 */
	@Nonnull
	private static EntityBuilder twoReferencesWithOneActive(
		@Nonnull EntityBuilder product,
		int contributing,
		int other
	) {
		return product
			.setReference(
				REFERENCE_BRAND, contributing,
				whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 5).setAttribute(ATTRIBUTE_ACTIVE, true)
			)
			.setReference(
				REFERENCE_BRAND, other,
				whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 5).setAttribute(ATTRIBUTE_ACTIVE, false)
			);
	}

	/**
	 * Adds the scenarios of a reference changing its group or being removed.
	 *
	 * @param scenarios the collected scenarios
	 */
	private static void addGroupAndRemovalScenarios(@Nonnull List<Scenario> scenarios) {
		// a hidden reference moves to another group, its sibling with the same share stays
		final Consumer<EntityBuilder> hiddenAndSibling = builder -> builder
			.setReference(
				REFERENCE_BRAND, BRAND_PK,
				whichIs -> whichIs
					.setGroup(ENTITY_BRAND_GROUP, FIRST_GROUP_PK)
					.setAttribute(ATTRIBUTE_SHARE, 1)
					.setAttribute(ATTRIBUTE_HIDDEN, true)
			)
			.setReference(
				REFERENCE_BRAND, SIBLING_BRAND_PK,
				whichIs -> whichIs.setGroup(ENTITY_BRAND_GROUP, FIRST_GROUP_PK).setAttribute(ATTRIBUTE_SHARE, 1)
			);
		final List<Expectation> siblingInFirstGroup = List.of(
			group(Scope.LIVE, FIRST_GROUP_PK, HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE)),
			group(Scope.LIVE, SECOND_GROUP_PK, HISTOGRAM_SHARE, ANCHORS_ONLY)
		);
		final List<Expectation> anchorsInBothGroups = List.of(
			group(Scope.LIVE, FIRST_GROUP_PK, HISTOGRAM_SHARE, ANCHORS_ONLY),
			group(Scope.LIVE, SECOND_GROUP_PK, HISTOGRAM_SHARE, ANCHORS_ONLY)
		);
		for (boolean byBuilder : new boolean[]{false, true}) {
			scenarios.add(new Scenario(
				"GROUP_CHANGE_OF_REFERENCE_OUTSIDE_HISTOGRAM" + (byBuilder ? "_BY_BUILDER" : ""),
				Fixture.grouped(histogramsOf(HISTOGRAM_SHARE, SHARE_VALUE), REFERENCE_NOT_HIDDEN),
				hiddenAndSibling,
				siblingInFirstGroup,
				byBuilder ?
					session -> session
						.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.updateReference(
							REFERENCE_BRAND, BRAND_PK,
							whichIs -> whichIs.setGroup(ENTITY_BRAND_GROUP, SECOND_GROUP_PK)
						)
						.upsertVia(session) :
					upsertOf(new SetReferenceGroupMutation(brand(BRAND_PK), SECOND_GROUP_PK)),
				siblingInFirstGroup,
				upsertOf(referenceAttribute(SIBLING_BRAND_PK, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)),
				anchorsInBothGroups
			));
		}
		// a reference contributing the fallback value is touched and removed, its sibling carries the same value
		final Consumer<EntityBuilder> fallbackAndSibling = builder -> builder
			.setReference(REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_HIDDEN, false))
			.setReference(REFERENCE_BRAND, SIBLING_BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 5));
		for (boolean touchedFirst : new boolean[]{true, false}) {
			final LocalMutation<?, ?> removal = new RemoveReferenceMutation(brand(BRAND_PK));
			scenarios.add(new Scenario(
				touchedFirst ? "CONDITION_ATTRIBUTE_REMOVED_THEN_REFERENCE_REMOVED" : "REFERENCE_REMOVED_ALONE",
				Fixture.ungrouped(
					histogramsOf(HISTOGRAM_SHARE, SHARE_VALUE + " ?? 5"), REFERENCE_NOT_HIDDEN, null
				),
				fallbackAndSibling,
				List.of(live(HISTOGRAM_SHARE, range(5, SECOND_ANCHOR_VALUE))),
				touchedFirst ?
					upsertOf(
						new ReferenceAttributeMutation(
							brand(BRAND_PK), new RemoveAttributeMutation(ATTRIBUTE_HIDDEN)
						),
						removal
					) :
					upsertOf(removal),
				List.of(live(HISTOGRAM_SHARE, range(5, SECOND_ANCHOR_VALUE))),
				upsertOf(new RemoveReferenceMutation(brand(SIBLING_BRAND_PK))),
				List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY))
			));
		}
		// a reference whose value comes from the brand it targets changes its group and is removed
		scenarios.add(new Scenario(
			"GROUP_CHANGE_THEN_REMOVAL_WITH_VALUE_OF_REFERENCED_ENTITY",
			Fixture.grouped(histogramsOf(HISTOGRAM_SIZE, SIZE_VALUE), null),
			builder -> builder.setReference(
				REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setGroup(ENTITY_BRAND_GROUP, FIRST_GROUP_PK)
			),
			List.of(
				group(Scope.LIVE, FIRST_GROUP_PK, HISTOGRAM_SIZE, range(1, SECOND_ANCHOR_VALUE)),
				group(Scope.LIVE, SECOND_GROUP_PK, HISTOGRAM_SIZE, ANCHORS_ONLY)
			),
			upsertOf(
				new SetReferenceGroupMutation(brand(BRAND_PK), SECOND_GROUP_PK),
				new RemoveReferenceMutation(brand(BRAND_PK))
			),
			List.of(
				group(Scope.LIVE, FIRST_GROUP_PK, HISTOGRAM_SIZE, ANCHORS_ONLY),
				group(Scope.LIVE, SECOND_GROUP_PK, HISTOGRAM_SIZE, ANCHORS_ONLY)
			),
			null,
			List.of(
				group(Scope.LIVE, FIRST_GROUP_PK, HISTOGRAM_SIZE, ANCHORS_ONLY),
				group(Scope.LIVE, SECOND_GROUP_PK, HISTOGRAM_SIZE, ANCHORS_ONLY)
			)
		));
	}

	/**
	 * Adds the scenarios of an upsert that moves the product to the archive and then changes or removes a reference.
	 *
	 * @param scenarios the collected scenarios
	 */
	private static void addScopeChangeScenarios(@Nonnull List<Scenario> scenarios) {
		// after the move a reference is hidden and changes its group, its sibling with the same share stays
		scenarios.add(new Scenario(
			"SCOPE_CHANGE_THEN_HIDING_AND_GROUP_CHANGE_WITH_SIBLING",
			Fixture.grouped(histogramsOf(HISTOGRAM_SHARE, SHARE_VALUE), REFERENCE_NOT_HIDDEN),
			builder -> builder
				.setReference(
					REFERENCE_BRAND, BRAND_PK,
					whichIs -> whichIs
						.setGroup(ENTITY_BRAND_GROUP, FIRST_GROUP_PK)
						.setAttribute(ATTRIBUTE_SHARE, 1)
						.setAttribute(ATTRIBUTE_HIDDEN, false)
				)
				.setReference(
					REFERENCE_BRAND, SIBLING_BRAND_PK,
					whichIs -> whichIs.setGroup(ENTITY_BRAND_GROUP, FIRST_GROUP_PK).setAttribute(ATTRIBUTE_SHARE, 1)
				),
			List.of(
				group(Scope.LIVE, FIRST_GROUP_PK, HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE)),
				group(Scope.LIVE, SECOND_GROUP_PK, HISTOGRAM_SHARE, ANCHORS_ONLY)
			),
			upsertOf(
				new SetEntityScopeMutation(Scope.ARCHIVED),
				referenceAttribute(BRAND_PK, ATTRIBUTE_HIDDEN, true),
				new SetReferenceGroupMutation(brand(BRAND_PK), SECOND_GROUP_PK)
			),
			List.of(
				group(Scope.ARCHIVED, FIRST_GROUP_PK, HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE)),
				group(Scope.ARCHIVED, SECOND_GROUP_PK, HISTOGRAM_SHARE, ANCHORS_ONLY)
			),
			upsertOf(referenceAttribute(SIBLING_BRAND_PK, ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)),
			List.of(
				group(Scope.ARCHIVED, FIRST_GROUP_PK, HISTOGRAM_SHARE, ANCHORS_ONLY),
				group(Scope.ARCHIVED, SECOND_GROUP_PK, HISTOGRAM_SHARE, ANCHORS_ONLY)
			)
		));
		// after the move the only reference is removed, and its value expression has a fallback
		scenarios.add(new Scenario(
			"SCOPE_CHANGE_THEN_REMOVAL_OF_REFERENCE_WITH_FALLBACK_VALUE",
			Fixture.ungrouped(histogramsOf(HISTOGRAM_SHARE, SHARE_VALUE + " ?? 7"), null, null),
			builder -> builder.setReference(
				REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, 1)
			),
			List.of(live(HISTOGRAM_SHARE, range(1, SECOND_ANCHOR_VALUE))),
			upsertOf(new SetEntityScopeMutation(Scope.ARCHIVED), new RemoveReferenceMutation(brand(BRAND_PK))),
			List.of(ungrouped(Scope.ARCHIVED, HISTOGRAM_SHARE, ANCHORS_ONLY)),
			null,
			List.of(ungrouped(Scope.ARCHIVED, HISTOGRAM_SHARE, ANCHORS_ONLY))
		));
	}

	/**
	 * Combines every scenario with both catalog states.
	 *
	 * @param scenarios the scenarios
	 * @return the arguments of a scenario test
	 */
	@Nonnull
	private static Stream<Arguments> inBothCatalogStates(@Nonnull List<Scenario> scenarios) {
		final List<Arguments> result = new ArrayList<>(scenarios.size() * 2);
		for (CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (Scenario scenario : scenarios) {
				result.add(Arguments.of(state, scenario));
			}
		}
		return result.stream();
	}

	/**
	 * The scenario of a histogram over an array reference attribute whose every element is a histogram value: one
	 * upsert changes the array twice.
	 *
	 * @return arguments of {@link #shouldHoldEachArrayElementOnceWhenUpsertChangesArrayTwice}
	 */
	@Nonnull
	static Stream<Arguments> arrayScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(1);
		// the stored array and both arrays of the upsert under test keep one element below the anchor range and one
		// above it, so the smallest and the largest value of the histogram show a stale element of the stored array
		// and of the intermediate one alike
		scenarios.add(new Scenario(
			"ARRAY_SHARES_CHANGED_TWICE", Fixture.ungrouped(histogramsOf(HISTOGRAM_SHARE, SHARES_VALUE), null, null),
			builder -> builder.setReference(
				REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARES, new Integer[]{1, 30})
			),
			List.of(live(HISTOGRAM_SHARE, range(1, 30))),
			upsertOf(
				referenceAttribute(BRAND_PK, ATTRIBUTE_SHARES, new Integer[]{2, 29}),
				referenceAttribute(BRAND_PK, ATTRIBUTE_SHARES, new Integer[]{3, 28})
			),
			List.of(live(HISTOGRAM_SHARE, range(3, 28))),
			upsertOf(referenceAttribute(BRAND_PK, ATTRIBUTE_SHARES, new Integer[]{INSIDE_ANCHOR_RANGE})),
			List.of(live(HISTOGRAM_SHARE, ANCHORS_ONLY))
		));
		return inBothCatalogStates(scenarios);
	}

	/**
	 * Changes an array reference attribute twice in one upsert and checks that the histogram holds every element of
	 * the final array once and nothing of the stored or the intermediate one - right after the upsert, after the
	 * follow-up that moves the array among the anchors, and after a restart.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("arrayScenarios")
	@DisplayName("every element of an array reference value reaches the histogram once")
	void shouldHoldEachArrayElementOnceWhenUpsertChangesArrayTwice(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Runs one scenario: sets the catalog up, checks the starting histograms, applies the upsert under test and
	 * checks the histograms, applies the follow-up - where there is one - and checks them again, and checks them
	 * once more after the engine restarted on the stored data. All failed checks are reported together.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	private void runScenario(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		final TestPaths paths = createTestPaths("ReferenceHistogramNetChange_" + state + "_" + scenario.name());
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final List<Executable> checks = new ArrayList<>(16);
		Evita evita = new Evita(configuration);
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpCatalog(session, scenario.fixture(), scenario.product());
				if (scenario.preparation() != null) {
					scenario.preparation().accept(session);
				}
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			recordHistograms(evita, "before the upsert", scenario.before(), checks);

			applyUpdate(evita, "the upsert", checks, scenario.upsert());
			recordHistograms(evita, "after the upsert", scenario.afterUpsert(), checks);

			final String lastStage;
			if (scenario.followUp() == null) {
				lastStage = "after the upsert";
			} else {
				// a value added twice survives the removal the follow-up makes, and nothing else shows it
				applyUpdate(evita, "the follow-up", checks, scenario.followUp());
				lastStage = "after the follow-up";
				recordHistograms(evita, lastStage, scenario.afterFollowUp(), checks);
			}

			// the histograms the engine loads on restart must agree with the ones it kept in memory
			evita.close();
			evita = new Evita(configuration);
			evita.waitUntilFullyInitialized();
			recordHistograms(evita, lastStage + " and restart", scenario.afterFollowUp(), checks);
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Describes a histogram spanning the values `min` to `max`.
	 *
	 * @param min the smallest value
	 * @param max the largest value
	 * @return the description {@link #describeHistogram} produces for such a histogram
	 */
	@Nonnull
	private static String range(int min, int max) {
		return "min=" + min + " max=" + max;
	}

	/**
	 * Creates the map of one histogram name to its value expression.
	 *
	 * @param name  the name of the histogram
	 * @param value the value expression of the histogram
	 * @return the map, in insertion order
	 */
	@Nonnull
	private static Map<String, String> histogramsOf(@Nonnull String name, @Nonnull String value) {
		final Map<String, String> result = new LinkedHashMap<>(2);
		result.put(name, value);
		return result;
	}

	/**
	 * Creates the map of two histogram names to their value expressions.
	 *
	 * @param firstName   the name of the first histogram
	 * @param firstValue  the value expression of the first histogram
	 * @param secondName  the name of the second histogram
	 * @param secondValue the value expression of the second histogram
	 * @return the map, in insertion order
	 */
	@Nonnull
	private static Map<String, String> histogramsOf(
		@Nonnull String firstName,
		@Nonnull String firstValue,
		@Nonnull String secondName,
		@Nonnull String secondValue
	) {
		final Map<String, String> result = histogramsOf(firstName, firstValue);
		result.put(secondName, secondValue);
		return result;
	}

	/**
	 * Expects the ungrouped histogram `histogram` of the live scope to be described as `expected`.
	 *
	 * @param histogram the name of the histogram
	 * @param expected  the expected description
	 * @return the expectation
	 */
	@Nonnull
	private static Expectation live(@Nonnull String histogram, @Nonnull String expected) {
		return new Expectation(Scope.LIVE, null, histogram, expected, null);
	}

	/**
	 * Expects the ungrouped histogram `histogram` of `scope` to be described as `expected`.
	 *
	 * @param scope     the scope
	 * @param histogram the name of the histogram
	 * @param expected  the expected description
	 * @return the expectation
	 */
	@Nonnull
	private static Expectation ungrouped(@Nonnull Scope scope, @Nonnull String histogram, @Nonnull String expected) {
		return new Expectation(scope, null, histogram, expected, null);
	}

	/**
	 * Expects the histogram `histogram` of the group `groupPk` in `scope` to be described as `expected`.
	 *
	 * @param scope     the scope
	 * @param groupPk   the primary key of the group
	 * @param histogram the name of the histogram
	 * @param expected  the expected description
	 * @return the expectation
	 */
	@Nonnull
	private static Expectation group(
		@Nonnull Scope scope,
		int groupPk,
		@Nonnull String histogram,
		@Nonnull String expected
	) {
		return new Expectation(scope, groupPk, histogram, expected, null);
	}

	/**
	 * Expects the ungrouped share histogram of `scope` in `locale` to be described as `expected`.
	 *
	 * @param scope    the scope
	 * @param locale   the locale of the histogram
	 * @param expected the expected description
	 * @return the expectation
	 */
	@Nonnull
	private static Expectation localized(@Nonnull Scope scope, @Nonnull Locale locale, @Nonnull String expected) {
		return new Expectation(scope, null, HISTOGRAM_SHARE, expected, locale);
	}

	/**
	 * Creates the change of the localized share of the product's reference to the brand in `locale` to `share`.
	 *
	 * @param locale the locale of the share
	 * @param share  the new share
	 * @return the mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation localizedShare(@Nonnull Locale locale, int share) {
		return new ReferenceAttributeMutation(
			brand(BRAND_PK), new UpsertAttributeMutation(ATTRIBUTE_SHARE, locale, share)
		);
	}

	/**
	 * Creates the key of the product's reference to the brand `brandPk`, without an internal primary key.
	 *
	 * @param brandPk the primary key of the brand
	 * @return the reference key
	 */
	@Nonnull
	private static ReferenceKey brand(int brandPk) {
		return new ReferenceKey(REFERENCE_BRAND, brandPk);
	}

	/**
	 * Creates the mutation setting the attribute `attribute` of the product's reference to the brand `brandPk`.
	 *
	 * @param brandPk   the primary key of the brand
	 * @param attribute the name of the reference attribute
	 * @param value     the value to set
	 * @return the mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation referenceAttribute(
		int brandPk,
		@Nonnull String attribute,
		@Nonnull Serializable value
	) {
		return new ReferenceAttributeMutation(brand(brandPk), new UpsertAttributeMutation(attribute, value));
	}

	/**
	 * Creates one upsert of the product made of `mutations`.
	 *
	 * @param mutations the local mutations of the upsert, in the order they are applied
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> upsertOf(@Nonnull LocalMutation<?, ?>... mutations) {
		return session -> session.upsertEntity(
			new EntityUpsertMutation(ENTITY_PRODUCT, PRODUCT_PK, EntityExistence.MUST_EXIST, mutations)
		);
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
	 * Records one check per expectation: that the histogram is described as expected at the moment `stage` names.
	 *
	 * @param evita        the engine
	 * @param stage        names the moment of the check
	 * @param expectations the expected states of the histograms
	 * @param checks       the collected checks
	 */
	private static void recordHistograms(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull List<Expectation> expectations,
		@Nonnull List<Executable> checks
	) {
		for (Expectation expectation : expectations) {
			final String subject = expectation.histogram() + " of " + expectation.scope() +
				(expectation.groupPk() == null ? "" : ", group " + expectation.groupPk()) +
				(expectation.locale() == null ? "" : ", locale " + expectation.locale());
			try {
				final String actual = evita.queryCatalog(TEST_CATALOG, session -> {
					return describeHistogram(
						session, expectation.scope(), expectation.groupPk(), expectation.histogram(),
						expectation.locale()
					);
				});
				checks.add(() -> assertEquals(expectation.expected(), actual, stage + ": " + subject));
			} catch (RuntimeException ex) {
				checks.add(() -> fail(stage + ": query of the " + subject + " threw " + ex, ex));
			}
		}
	}

	/**
	 * Describes the histogram `histogramName` the reference summary of `scope` computes - for the group `groupPk`,
	 * or the ungrouped one when it is `null`: its smallest and largest value, or the absence of the histogram. A
	 * histogram over a localized value is read in `locale`, among the products that have that locale.
	 *
	 * @param session       the session to query
	 * @param scope         the scope to query
	 * @param groupPk       the primary key of the group whose histogram is described, or `null` for the ungrouped one
	 * @param histogramName the name of the histogram
	 * @param locale        the locale of a histogram over a localized value, or `null` for a non-localized one
	 * @return the description
	 */
	@Nonnull
	private static String describeHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope scope,
		@Nullable Integer groupPk,
		@Nonnull String histogramName,
		@Nullable Locale locale
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				locale == null ? filterBy(scope(scope)) : filterBy(scope(scope), entityLocaleEquals(locale)),
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
		final ReferenceGroupStatistics statistics;
		if (summary == null) {
			statistics = null;
		} else if (groupPk == null) {
			statistics = summary.getReferenceGroupStatistics(REFERENCE_BRAND);
		} else {
			statistics = summary.getReferenceGroupStatistics(REFERENCE_BRAND, groupPk);
		}
		final HistogramContract histogram = statistics == null ?
			null : statistics.getHistogramStatistics(histogramName);
		return histogram == null ?
			NO_HISTOGRAM : "min=" + plain(histogram.getMin()) + " max=" + plain(histogram.getMax());
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
	 * Defines the schemas and stores the brands, the groups, the anchor products and the product.
	 *
	 * The product has a localized `title` and the locales English and German. The reference of a product to a brand
	 * is indexed for filtering and faceted in both scopes, grouped where `fixture` says, and carries its histograms
	 * and condition; its `share` is localized where `fixture` says, and its `shares` is an array. The brands
	 * of the product and of its sibling reference have the size 1. An anchor product in each scope is visible and
	 * prioritized and references two brands - in each of its groups, where grouped - with the shares, the weights
	 * and the brand sizes 10 and 20, active and not hidden, so every histogram of every group spans the range 10 to
	 * 20 before the product enters it. Where the share is localized, the anchor products carry their title and their
	 * shares in both locales, so the histogram of each locale spans that range too - the titles stored by one upsert,
	 * the references by another, because a localized share reaches the histogram only in the locales the product had
	 * before the upsert that sets it. The product is stored in the live scope, set up by `product`.
	 *
	 * @param session the session of the catalog in warm-up
	 * @param fixture the schema and the anchor products
	 * @param product sets up the product before it is stored
	 */
	private static void setUpCatalog(
		@Nonnull EvitaSessionContract session,
		@Nonnull Fixture fixture,
		@Nonnull Consumer<EntityBuilder> product
	) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_SIZE, Integer.class, whichIs -> whichIs.filterableInScope(ALL_SCOPES).nullable())
			.updateVia(session);
		if (fixture.grouped()) {
			session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		}
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTRIBUTE_TITLE, String.class, whichIs -> whichIs.localized().nullable())
			.withAttribute(
				ATTRIBUTE_PRIORITY, Integer.class, whichIs -> whichIs.filterableInScope(ALL_SCOPES).nullable()
			)
			.withAttribute(
				ATTRIBUTE_VISIBLE, Boolean.class, whichIs -> whichIs.filterableInScope(ALL_SCOPES).nullable()
			)
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (fixture.grouped()) {
						whichIs.withGroupTypeRelatedToEntity(ENTITY_BRAND_GROUP);
					}
					whichIs
						.indexedForFilteringInScope(ALL_SCOPES)
						.facetedInScope(ALL_SCOPES)
						.withAttribute(
							ATTRIBUTE_SHARE, Integer.class,
							thatIs -> {
								thatIs.filterableInScope(ALL_SCOPES).nullable();
								if (fixture.localized()) {
									thatIs.localized();
								}
							}
						)
						.withAttribute(
							ATTRIBUTE_SHARES, Integer[].class, thatIs -> thatIs.filterableInScope(ALL_SCOPES).nullable()
						)
						.withAttribute(
							ATTRIBUTE_WEIGHT, Integer.class,
							thatIs -> {
								thatIs.filterableInScope(ALL_SCOPES).nullable();
								if (fixture.weightDefault() != null) {
									thatIs.withDefaultValue(fixture.weightDefault());
								}
							}
						)
						.withAttribute(
							ATTRIBUTE_HIDDEN, Boolean.class, thatIs -> thatIs.filterableInScope(ALL_SCOPES).nullable()
						)
						.withAttribute(
							ATTRIBUTE_ACTIVE, Boolean.class, thatIs -> thatIs.filterableInScope(ALL_SCOPES).nullable()
						)
						.withAttribute(
							ATTRIBUTE_NOTE, String.class, thatIs -> thatIs.filterableInScope(ALL_SCOPES).nullable()
						);
					for (Scope scope : ALL_SCOPES) {
						if (fixture.grouped()) {
							whichIs.indexedWithComponentsInScope(
								scope,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							);
						}
						for (Map.Entry<String, String> histogram : fixture.histograms().entrySet()) {
							whichIs.bucketedInScope(
								scope, histogram.getKey(), ExpressionFactory.parse(histogram.getValue())
							);
						}
						if (fixture.condition() != null) {
							whichIs.bucketedPartiallyInScope(scope, ExpressionFactory.parse(fixture.condition()));
						}
					}
				}
			)
			.updateVia(session);

		if (fixture.grouped()) {
			for (int groupPk : new int[]{FIRST_GROUP_PK, SECOND_GROUP_PK}) {
				session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, groupPk));
			}
		}
		// the brand of the product and of its sibling reference, and the brands of the anchors
		for (int brandPk = BRAND_PK; brandPk <= LAST_BRAND_PK; brandPk++) {
			final int size;
			if (brandPk < FIRST_ANCHOR_BRAND_PK) {
				size = 1;
			} else {
				size = (brandPk - FIRST_ANCHOR_BRAND_PK) % 2 == 0 ? ANCHOR_VALUE : SECOND_ANCHOR_VALUE;
			}
			session.createNewEntity(ENTITY_BRAND, brandPk).setAttribute(ATTRIBUTE_SIZE, size).upsertVia(session);
		}
		final int[] anchorGroups;
		if (!fixture.grouped()) {
			anchorGroups = new int[]{0};
		} else if (fixture.anchorsInFirstGroup()) {
			anchorGroups = new int[]{FIRST_GROUP_PK, SECOND_GROUP_PK};
		} else {
			anchorGroups = new int[]{SECOND_GROUP_PK};
		}
		for (Scope scope : ALL_SCOPES) {
			final int anchorPk = scope == Scope.LIVE ? LIVE_ANCHOR_PK : ARCHIVED_ANCHOR_PK;
			final EntityBuilder newAnchor = session
				.createNewEntity(ENTITY_PRODUCT, anchorPk)
				.setScope(scope)
				.setAttribute(ATTRIBUTE_PRIORITY, 1)
				.setAttribute(ATTRIBUTE_VISIBLE, true);
			final EntityBuilder anchor;
			if (fixture.localized()) {
				// a localized share reaches the histogram only in the locales the product had before the upsert that
				// sets it, so the anchor gets its locales by its titles first and its references in a second upsert
				newAnchor
					.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "anchor")
					.setAttribute(ATTRIBUTE_TITLE, Locale.GERMAN, "Anker")
					.upsertVia(session);
				anchor = session.getEntity(ENTITY_PRODUCT, anchorPk, ALL_SCOPES, entityFetchAllContent())
					.orElseThrow()
					.openForWrite();
			} else {
				anchor = newAnchor;
			}
			int brandPk = FIRST_ANCHOR_BRAND_PK;
			for (int groupPk : anchorGroups) {
				for (int anchorValue : new int[]{ANCHOR_VALUE, SECOND_ANCHOR_VALUE}) {
					// The predicate matches no stored reference, so each anchor reference is created anew.
					anchor.setOrUpdateReference(
						REFERENCE_BRAND, brandPk++, reference -> false,
						whichIs -> setUpAnchorReference(whichIs, fixture, groupPk, anchorValue)
					);
				}
			}
			anchor.upsertVia(session);
		}
		final EntityBuilder productBuilder = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK);
		product.accept(productBuilder);
		productBuilder.upsertVia(session);
	}

	/**
	 * Sets up one reference of an anchor product: its group, where grouped, its share - in English and in German,
	 * where it is localized - and weight, the flag that makes it active.
	 *
	 * @param reference   the reference being built
	 * @param fixture     the schema and the anchor products
	 * @param groupPk     the primary key of the group, ignored where the reference is not grouped
	 * @param anchorValue the share, the only element of the shares and the weight of the reference
	 */
	private static void setUpAnchorReference(
		@Nonnull ReferenceBuilder reference,
		@Nonnull Fixture fixture,
		int groupPk,
		int anchorValue
	) {
		if (fixture.grouped()) {
			reference.setGroup(ENTITY_BRAND_GROUP, groupPk);
		}
		if (fixture.localized()) {
			reference
				.setAttribute(ATTRIBUTE_SHARE, Locale.ENGLISH, anchorValue)
				.setAttribute(ATTRIBUTE_SHARE, Locale.GERMAN, anchorValue);
		} else {
			reference.setAttribute(ATTRIBUTE_SHARE, anchorValue);
		}
		reference
			.setAttribute(ATTRIBUTE_SHARES, new Integer[]{anchorValue})
			.setAttribute(ATTRIBUTE_WEIGHT, anchorValue)
			.setAttribute(ATTRIBUTE_ACTIVE, true);
	}

}
