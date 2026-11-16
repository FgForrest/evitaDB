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
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
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
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Verifies that a reference histogram whose condition reads the parent of the product, or an associated data of the
 * product, follows the verdict of the condition when the product changes its parent or its associated data.
 *
 * Every change that flips the verdict has a control: a product created with the final state in the upsert under
 * test, which must end with the same histogram; and, where the verdict turns true, the same change followed by an
 * upsert that rewrites the share of the reference. Every scenario checks the histogram right after the upsert, again
 * after a restart, after a follow-up upsert that moves the share into the range of the anchor product, and after one
 * more restart - a value left behind shows in the follow-up, and a verdict kept in memory only would change on the
 * restart.
 *
 * Further scenarios change the input in one upsert together with the reference: with its share rewritten before or
 * after the change, with the reference removed or inserted, and before or after a scope change of the product. The
 * scenarios changing the input after a scope change run on a catalog whose histogram exists in both scopes, or in the
 * archive only, and check the archive the product moves to. A reference carrying a histogram conditioned on the parent
 * and another conditioned on the label must keep the histogram whose input did not change. The removal of a product
 * whose condition holds must take its value out, although the entity removal drops the parent before the references;
 * the removal of a product whose condition fails must succeed and leave nothing behind.
 *
 * Two more condition shapes are covered: one reading only whether the parent exists, which the entity body answers
 * without a nested parent proxy, and one reading a localized associated data, whose locales the withdrawal reads from
 * the body before the change and the final add after it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A histogram condition reading the parent or the associated data of the product")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class ParentAndAssociatedDataHistogramConditionFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_SHARE = "share";
	private static final String ATTRIBUTE_STATUS = "status";
	private static final String ASSOCIATED_DATA_LABEL = "label";
	/**
	 * The localized associated data of {@link Fixture#LOCALIZED_LABEL}, read in English.
	 */
	private static final String ASSOCIATED_DATA_LOCALIZED_LABEL = "localizedLabel";
	private static final String HISTOGRAM_SHARE = "shareHistogram";
	/**
	 * The histogram of {@link Fixture#TWO_HISTOGRAMS} conditioned on the label, beside the one of the scenario.
	 */
	private static final String HISTOGRAM_LABEL_SHARE = "labelShareHistogram";
	private static final String STATUS_ACTIVE = "ACTIVE";
	private static final String STATUS_INACTIVE = "INACTIVE";
	private static final String LABEL_SHOWN = "SHOWN";
	private static final String LABEL_HIDDEN = "HIDDEN";
	private static final int PRODUCT_PK = 1;
	private static final int ANCHOR_PK = 100;
	private static final int ARCHIVED_ANCHOR_PK = 101;
	private static final int ACTIVE_PARENT_PK = 200;
	private static final int INACTIVE_PARENT_PK = 300;
	private static final int ARCHIVED_ACTIVE_PARENT_PK = 400;
	private static final int ARCHIVED_INACTIVE_PARENT_PK = 500;
	private static final int BRAND_PK = 10;
	/**
	 * The brand the fixture stores but no product references, so a scenario can insert a reference to it.
	 */
	private static final int INSERTED_BRAND_PK = 11;
	private static final int FIRST_ANCHOR_BRAND_PK = 12;
	private static final int PRODUCT_SHARE = 5;
	private static final int TOUCHED_SHARE = 6;
	private static final int INSERTED_SHARE = 8;
	private static final int ANCHOR_VALUE = 10;
	private static final int SECOND_ANCHOR_VALUE = 20;
	private static final int INSIDE_ANCHOR_RANGE = 15;
	private static final int BUCKET_COUNT = 10;
	/**
	 * The description of a histogram holding only the values of the anchor product.
	 */
	private static final String ANCHORS_ONLY = range(ANCHOR_VALUE, SECOND_ANCHOR_VALUE);
	/**
	 * The description of a histogram holding the share of the product beside the values of the anchor product.
	 */
	private static final String WITH_PRODUCT = range(PRODUCT_SHARE, SECOND_ANCHOR_VALUE);
	/**
	 * The description of a histogram that does not exist.
	 */
	private static final String NO_HISTOGRAM = "no histogram";
	private static final String SHARE_VALUE = "$reference.attributes['" + ATTRIBUTE_SHARE + "']";
	private static final String PARENT_ACTIVE =
		"($entity.parentEntity?.attributes['" + ATTRIBUTE_STATUS + "'] ?? '') == '" + STATUS_ACTIVE + "'";
	/**
	 * The condition admitting a product whose parent is active or that has no parent.
	 */
	private static final String PARENT_ACTIVE_OR_ABSENT =
		"($entity.parentEntity?.attributes['" + ATTRIBUTE_STATUS + "'] ?? '" + STATUS_ACTIVE + "') == '" +
			STATUS_ACTIVE + "'";
	private static final String LABEL_IS_SHOWN =
		"($entity.associatedData['" + ASSOCIATED_DATA_LABEL + "'] ?? '') == '" + LABEL_SHOWN + "'";
	/**
	 * The condition admitting a product that has a parent, whatever the status of the parent. It reads only the
	 * existence of the parent, which the entity body answers without a nested parent proxy.
	 */
	private static final String PARENT_EXISTS = "$entity.parentEntity != null";
	/**
	 * The condition admitting a product whose English localized label is shown.
	 */
	private static final String LOCALIZED_LABEL_IS_SHOWN =
		"($entity.localizedAssociatedData['" + ASSOCIATED_DATA_LOCALIZED_LABEL + "']['en'] ?? '') == '" +
			LABEL_SHOWN + "'";

	/**
	 * One upsert under test, with the schema and the product it runs on and the states of the histogram it must
	 * produce.
	 *
	 * @param name          the name of the scenario
	 * @param condition     the condition admitting a reference to the share histogram
	 * @param product       sets up the product stored before the scenario starts, or `null` when the upsert under
	 *                      test creates it
	 * @param before        the histogram before the upsert, proving the starting state is the intended one
	 * @param upsert        the upsert under test, or the two upserts of a control
	 * @param afterUpsert   the histogram right after the upsert, in memory and after a restart
	 * @param followUp      the follow-up that exposes a value the upsert left behind or failed to add
	 * @param afterFollowUp the histogram after the follow-up, in memory and after a restart
	 * @param fixture       the catalog the scenario runs on and the histograms it checks
	 */
	private record Scenario(
		@Nonnull String name,
		@Nonnull String condition,
		@Nullable Consumer<EntityBuilder> product,
		@Nonnull String before,
		@Nonnull Consumer<EvitaSessionContract> upsert,
		@Nonnull String afterUpsert,
		@Nonnull Consumer<EvitaSessionContract> followUp,
		@Nonnull String afterFollowUp,
		@Nonnull Fixture fixture
	) {

		/**
		 * Creates a scenario on {@link Fixture#LIVE_ONLY}, whose follow-up moves the share among the anchors.
		 *
		 * @param name          the name of the scenario
		 * @param condition     the condition admitting a reference to the share histogram
		 * @param product       sets up the product stored before the scenario starts, or `null` when the upsert
		 *                      under test creates it
		 * @param before        the histogram before the upsert
		 * @param upsert        the upsert under test, or the two upserts of a control
		 * @param afterUpsert   the histogram right after the upsert
		 * @param afterFollowUp the histogram after the follow-up
		 */
		Scenario(
			@Nonnull String name,
			@Nonnull String condition,
			@Nullable Consumer<EntityBuilder> product,
			@Nonnull String before,
			@Nonnull Consumer<EvitaSessionContract> upsert,
			@Nonnull String afterUpsert,
			@Nonnull String afterFollowUp
		) {
			this(
				name, condition, product, before, upsert, afterUpsert, upsertOf(shareOf(INSIDE_ANCHOR_RANGE)),
				afterFollowUp, Fixture.LIVE_ONLY
			);
		}

		/**
		 * Creates a scenario on {@link Fixture#LIVE_ONLY} with its own follow-up.
		 *
		 * @param name          the name of the scenario
		 * @param condition     the condition admitting a reference to the share histogram
		 * @param product       sets up the product stored before the scenario starts, or `null` when the upsert
		 *                      under test creates it
		 * @param before        the histogram before the upsert
		 * @param upsert        the upsert under test
		 * @param afterUpsert   the histogram right after the upsert
		 * @param followUp      the follow-up that exposes a value the upsert left behind or failed to add
		 * @param afterFollowUp the histogram after the follow-up
		 */
		Scenario(
			@Nonnull String name,
			@Nonnull String condition,
			@Nullable Consumer<EntityBuilder> product,
			@Nonnull String before,
			@Nonnull Consumer<EvitaSessionContract> upsert,
			@Nonnull String afterUpsert,
			@Nonnull Consumer<EvitaSessionContract> followUp,
			@Nonnull String afterFollowUp
		) {
			this(name, condition, product, before, upsert, afterUpsert, followUp, afterFollowUp, Fixture.LIVE_ONLY);
		}

		/**
		 * Returns the name of the scenario, so the test report names the cell by it.
		 *
		 * @return the name of the scenario
		 */
		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}
	}

	/**
	 * The catalog a scenario runs on: where the share histogram exists and which histograms the scenario checks.
	 */
	enum Fixture {
		/**
		 * The share histogram with the condition exists in the live scope only; the live scope is checked.
		 */
		LIVE_ONLY(Scope.LIVE, false),
		/**
		 * The share histogram with the condition exists in both scopes; the archive is checked.
		 */
		BOTH_SCOPES(Scope.ARCHIVED, true),
		/**
		 * The share histogram with the condition exists in the archive only; the archive is checked.
		 */
		ARCHIVE_ONLY(Scope.ARCHIVED, true),
		/**
		 * The reference carries the share histogram, conditioned on the condition of the scenario, and the label
		 * share histogram, conditioned on the label being shown, both in the live scope; both are checked, in this
		 * order.
		 */
		TWO_HISTOGRAMS(Scope.LIVE, false),
		/**
		 * As {@link #LIVE_ONLY}, and the product has the English locale and the localized `localizedLabel` associated
		 * data, which the anchor product shows.
		 */
		LOCALIZED_LABEL(Scope.LIVE, false);

		/**
		 * The scope whose histograms the scenario checks.
		 */
		private final Scope checkedScope;
		/**
		 * TRUE when the catalog has the archived parents and the archived anchor product.
		 */
		private final boolean archive;

		/**
		 * Creates the fixture.
		 *
		 * @param checkedScope the scope whose histograms the scenario checks
		 * @param archive      TRUE when the catalog has the archived parents and the archived anchor product
		 */
		Fixture(@Nonnull Scope checkedScope, boolean archive) {
			this.checkedScope = checkedScope;
			this.archive = archive;
		}
	}

	/**
	 * The input of the product a histogram condition reads, with the states that make the condition hold or fail.
	 */
	enum Input {
		/**
		 * The parent of the product, read by a condition admitting the product when its parent is active.
		 */
		PARENT,
		/**
		 * The `label` associated data of the product, read by a condition admitting the product when it is shown.
		 */
		LABEL;

		/**
		 * Returns the condition reading this input.
		 *
		 * @return the condition admitting a reference to the share histogram
		 */
		@Nonnull
		String condition() {
			return switch (this) {
				case PARENT -> PARENT_ACTIVE;
				case LABEL -> LABEL_IS_SHOWN;
			};
		}

		/**
		 * Names the change of this input that makes the condition hold or fail.
		 *
		 * @param holds TRUE for the change making the condition hold
		 * @return the name of the change, used in the names of the scenarios
		 */
		@Nonnull
		String changedTo(boolean holds) {
			return switch (this) {
				case PARENT -> holds ? "PARENT_CHANGED_TO_ACTIVE" : "PARENT_CHANGED_TO_INACTIVE";
				case LABEL -> holds ? "LABEL_CHANGED_TO_SHOWN" : "LABEL_CHANGED_TO_HIDDEN";
			};
		}

		/**
		 * Creates the mutation changing this input so that the condition holds or fails for a live product.
		 *
		 * @param holds TRUE for the change making the condition hold
		 * @return the mutation
		 */
		@Nonnull
		LocalMutation<?, ?> changeTo(boolean holds) {
			return switch (this) {
				case PARENT -> new SetParentMutation(holds ? ACTIVE_PARENT_PK : INACTIVE_PARENT_PK);
				case LABEL -> new UpsertAssociatedDataMutation(ASSOCIATED_DATA_LABEL, label(holds));
			};
		}

		/**
		 * Sets up the product with its reference to the brand with the share 5 and with this input making the
		 * condition hold or fail for a product in the live scope.
		 *
		 * @param holds TRUE for the state making the condition hold
		 * @return the set-up of the product
		 */
		@Nonnull
		Consumer<EntityBuilder> storedWith(boolean holds) {
			return switch (this) {
				case PARENT -> product -> withShare(product.setParent(holds ? ACTIVE_PARENT_PK : INACTIVE_PARENT_PK));
				case LABEL -> product -> withShare(product.setAssociatedData(ASSOCIATED_DATA_LABEL, label(holds)));
			};
		}

		/**
		 * Returns the label making the condition hold or fail.
		 *
		 * @param holds TRUE for the shown label
		 * @return the label
		 */
		@Nonnull
		private static String label(boolean holds) {
			return holds ? LABEL_SHOWN : LABEL_HIDDEN;
		}
	}

	/**
	 * The scenarios of a product changing its parent, or removing it, under a condition reading the status of the
	 * parent, and their controls; and of a product gaining or losing its parent under a condition reading only whether
	 * a parent exists.
	 *
	 * @return arguments of {@link #shouldFollowParentWhenProductChangesIt}
	 */
	@Nonnull
	static Stream<Arguments> parentScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(8);
		final Consumer<EntityBuilder> underActiveParent = product -> withShare(product.setParent(ACTIVE_PARENT_PK));
		final Consumer<EntityBuilder> underInactiveParent =
			product -> withShare(product.setParent(INACTIVE_PARENT_PK));
		scenarios.add(new Scenario(
			"PARENT_CHANGED_TO_ACTIVE", PARENT_ACTIVE, underInactiveParent, ANCHORS_ONLY,
			upsertOf(new SetParentMutation(ACTIVE_PARENT_PK)), WITH_PRODUCT, ANCHORS_ONLY
		));
		scenarios.add(new Scenario(
			"PARENT_CHANGED_TO_INACTIVE", PARENT_ACTIVE, underActiveParent, WITH_PRODUCT,
			upsertOf(new SetParentMutation(INACTIVE_PARENT_PK)), ANCHORS_ONLY, ANCHORS_ONLY
		));
		scenarios.add(new Scenario(
			"PARENT_REMOVED", PARENT_ACTIVE, underActiveParent, WITH_PRODUCT,
			upsertOf(new RemoveParentMutation()), ANCHORS_ONLY, ANCHORS_ONLY
		));
		// a product created with the final state must end with the same histogram as a product changed into it
		scenarios.add(new Scenario(
			"CONTROL_CREATED_UNDER_ACTIVE_PARENT", PARENT_ACTIVE, null, ANCHORS_ONLY,
			createProduct(underActiveParent), WITH_PRODUCT, ANCHORS_ONLY
		));
		scenarios.add(new Scenario(
			"CONTROL_CREATED_UNDER_INACTIVE_PARENT", PARENT_ACTIVE, null, ANCHORS_ONLY,
			createProduct(underInactiveParent), ANCHORS_ONLY, ANCHORS_ONLY
		));
		// a later upsert touching the reference evaluates the condition against the new parent
		scenarios.add(new Scenario(
			"CONTROL_PARENT_CHANGED_TO_ACTIVE_THEN_SHARE_REWRITTEN", PARENT_ACTIVE, underInactiveParent, ANCHORS_ONLY,
			session -> {
				upsertOf(new SetParentMutation(ACTIVE_PARENT_PK)).accept(session);
				upsertOf(shareOf(TOUCHED_SHARE)).accept(session);
			},
			range(TOUCHED_SHARE, SECOND_ANCHOR_VALUE), ANCHORS_ONLY
		));
		// any parent admits the product under the existence-only condition, the inactive one included
		final Consumer<EntityBuilder> withoutParent = product -> withShare(product);
		scenarios.add(new Scenario(
			"PARENT_SET_UNDER_EXISTENCE_CONDITION", PARENT_EXISTS, withoutParent, ANCHORS_ONLY,
			upsertOf(new SetParentMutation(INACTIVE_PARENT_PK)), WITH_PRODUCT, ANCHORS_ONLY
		));
		scenarios.add(new Scenario(
			"PARENT_REMOVED_UNDER_EXISTENCE_CONDITION", PARENT_EXISTS, underInactiveParent, WITH_PRODUCT,
			upsertOf(new RemoveParentMutation()), ANCHORS_ONLY, ANCHORS_ONLY
		));
		return inBothCatalogStates(scenarios);
	}

	/**
	 * The scenarios of a product changing or removing its associated data under a condition reading it, and their
	 * controls; and of a product adding, changing or removing its English localized label under a condition reading
	 * it, where adding the first localized value gives the product its only locale and removing it takes the locale
	 * away again.
	 *
	 * @return arguments of {@link #shouldFollowAssociatedDataWhenProductChangesIt}
	 */
	@Nonnull
	static Stream<Arguments> associatedDataScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(10);
		final Consumer<EntityBuilder> shown =
			product -> withShare(product.setAssociatedData(ASSOCIATED_DATA_LABEL, LABEL_SHOWN));
		final Consumer<EntityBuilder> hidden =
			product -> withShare(product.setAssociatedData(ASSOCIATED_DATA_LABEL, LABEL_HIDDEN));
		scenarios.add(new Scenario(
			"LABEL_CHANGED_TO_SHOWN", LABEL_IS_SHOWN, hidden, ANCHORS_ONLY,
			upsertOf(new UpsertAssociatedDataMutation(ASSOCIATED_DATA_LABEL, LABEL_SHOWN)), WITH_PRODUCT, ANCHORS_ONLY
		));
		scenarios.add(new Scenario(
			"LABEL_CHANGED_TO_HIDDEN", LABEL_IS_SHOWN, shown, WITH_PRODUCT,
			upsertOf(new UpsertAssociatedDataMutation(ASSOCIATED_DATA_LABEL, LABEL_HIDDEN)), ANCHORS_ONLY, ANCHORS_ONLY
		));
		scenarios.add(new Scenario(
			"LABEL_REMOVED", LABEL_IS_SHOWN, shown, WITH_PRODUCT,
			upsertOf(new RemoveAssociatedDataMutation(ASSOCIATED_DATA_LABEL)), ANCHORS_ONLY, ANCHORS_ONLY
		));
		// a product created with the final state must end with the same histogram as a product changed into it
		scenarios.add(new Scenario(
			"CONTROL_CREATED_SHOWN", LABEL_IS_SHOWN, null, ANCHORS_ONLY,
			createProduct(shown), WITH_PRODUCT, ANCHORS_ONLY
		));
		scenarios.add(new Scenario(
			"CONTROL_CREATED_HIDDEN", LABEL_IS_SHOWN, null, ANCHORS_ONLY,
			createProduct(hidden), ANCHORS_ONLY, ANCHORS_ONLY
		));
		// a later upsert touching the reference evaluates the condition against the new associated data
		scenarios.add(new Scenario(
			"CONTROL_LABEL_CHANGED_TO_SHOWN_THEN_SHARE_REWRITTEN", LABEL_IS_SHOWN, hidden, ANCHORS_ONLY,
			session -> {
				upsertOf(new UpsertAssociatedDataMutation(ASSOCIATED_DATA_LABEL, LABEL_SHOWN)).accept(session);
				upsertOf(shareOf(TOUCHED_SHARE)).accept(session);
			},
			range(TOUCHED_SHARE, SECOND_ANCHOR_VALUE), ANCHORS_ONLY
		));
		final Consumer<EvitaSessionContract> moveShare = upsertOf(shareOf(INSIDE_ANCHOR_RANGE));
		final Consumer<EntityBuilder> withoutLocale = product -> withShare(product);
		final Consumer<EntityBuilder> localizedShown = product -> withShare(
			product.setAssociatedData(ASSOCIATED_DATA_LOCALIZED_LABEL, Locale.ENGLISH, LABEL_SHOWN)
		);
		// the final add reads the locale the upsert gives the product, the withdrawal the locale it takes away
		scenarios.add(new Scenario(
			"LOCALIZED_LABEL_ADDED_AS_SHOWN", LOCALIZED_LABEL_IS_SHOWN, withoutLocale, ANCHORS_ONLY,
			upsertOf(new UpsertAssociatedDataMutation(ASSOCIATED_DATA_LOCALIZED_LABEL, Locale.ENGLISH, LABEL_SHOWN)),
			WITH_PRODUCT, moveShare, ANCHORS_ONLY, Fixture.LOCALIZED_LABEL
		));
		scenarios.add(new Scenario(
			"LOCALIZED_LABEL_CHANGED_TO_HIDDEN", LOCALIZED_LABEL_IS_SHOWN, localizedShown, WITH_PRODUCT,
			upsertOf(new UpsertAssociatedDataMutation(ASSOCIATED_DATA_LOCALIZED_LABEL, Locale.ENGLISH, LABEL_HIDDEN)),
			ANCHORS_ONLY, moveShare, ANCHORS_ONLY, Fixture.LOCALIZED_LABEL
		));
		scenarios.add(new Scenario(
			"LOCALIZED_LABEL_REMOVED", LOCALIZED_LABEL_IS_SHOWN, localizedShown, WITH_PRODUCT,
			upsertOf(new RemoveAssociatedDataMutation(ASSOCIATED_DATA_LOCALIZED_LABEL, Locale.ENGLISH)),
			ANCHORS_ONLY, moveShare, ANCHORS_ONLY, Fixture.LOCALIZED_LABEL
		));
		scenarios.add(new Scenario(
			"CONTROL_CREATED_WITH_LOCALIZED_LABEL_SHOWN", LOCALIZED_LABEL_IS_SHOWN, null, ANCHORS_ONLY,
			createProduct(localizedShown), WITH_PRODUCT, moveShare, ANCHORS_ONLY, Fixture.LOCALIZED_LABEL
		));
		return inBothCatalogStates(scenarios);
	}

	/**
	 * The scenarios of a product changing an input and rewriting the share of its reference in one upsert, in both
	 * orders, for both inputs. A share rewritten after the change evaluates the condition against the new input, so
	 * it withdraws a value only when the change withdrew the old one first.
	 *
	 * @return arguments of {@link #shouldFollowInputWhenChangedTogetherWithShare}
	 */
	@Nonnull
	static Stream<Arguments> shareRewrittenScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(8);
		final String touched = range(TOUCHED_SHARE, SECOND_ANCHOR_VALUE);
		for (final Input input : Input.values()) {
			final String condition = input.condition();
			final Consumer<EntityBuilder> holding = input.storedWith(true);
			final Consumer<EntityBuilder> failing = input.storedWith(false);
			final LocalMutation<?, ?> toHolding = input.changeTo(true);
			final LocalMutation<?, ?> toFailing = input.changeTo(false);
			scenarios.add(new Scenario(
				input.changedTo(true) + "_WITH_SHARE_REWRITTEN", condition, failing, ANCHORS_ONLY,
				upsertOf(toHolding, shareOf(TOUCHED_SHARE)), touched, ANCHORS_ONLY
			));
			scenarios.add(new Scenario(
				input.changedTo(false) + "_WITH_SHARE_REWRITTEN", condition, holding, WITH_PRODUCT,
				upsertOf(toFailing, shareOf(TOUCHED_SHARE)), ANCHORS_ONLY, ANCHORS_ONLY
			));
			// the share rewritten first withdraws against the old input, which the change must not withdraw again
			scenarios.add(new Scenario(
				"CONTROL_SHARE_REWRITTEN_THEN_" + input.changedTo(true), condition, failing, ANCHORS_ONLY,
				upsertOf(shareOf(TOUCHED_SHARE), toHolding), touched, ANCHORS_ONLY
			));
			scenarios.add(new Scenario(
				"CONTROL_SHARE_REWRITTEN_THEN_" + input.changedTo(false), condition, holding, WITH_PRODUCT,
				upsertOf(shareOf(TOUCHED_SHARE), toFailing), ANCHORS_ONLY, ANCHORS_ONLY
			));
		}
		return inBothCatalogStates(scenarios);
	}

	/**
	 * The scenarios of a product changing an input in one upsert with the removal or insertion of a reference, or
	 * with a scope change following the input change, for both inputs.
	 *
	 * - A reference removed after the change cancels the final add the change scheduled; removed before it, the
	 *   reference is skipped by the change.
	 * - A reference inserted before the change awaits its final add already, so the change must not schedule a second
	 *   one: its value would be added twice, and the follow-up moving both references among the anchors would leave
	 *   one copy of it behind.
	 * - A scope change after the input change runs the final add of the change in the scope the product leaves,
	 *   before it unindexes the product from there.
	 *
	 * @return arguments of {@link #shouldFollowInputWhenChangedTogetherWithReference}
	 */
	@Nonnull
	static Stream<Arguments> referenceChangedScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(10);
		final Consumer<EvitaSessionContract> reinsert = session -> session
			.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, INSIDE_ANCHOR_RANGE)
			)
			.upsertVia(session);
		for (final Input input : Input.values()) {
			final String condition = input.condition();
			final Consumer<EntityBuilder> holding = input.storedWith(true);
			final Consumer<EntityBuilder> failing = input.storedWith(false);
			final LocalMutation<?, ?> toHolding = input.changeTo(true);
			final LocalMutation<?, ?> toFailing = input.changeTo(false);
			scenarios.add(new Scenario(
				input.changedTo(true) + "_WITH_REFERENCE_REMOVED", condition, failing, ANCHORS_ONLY,
				upsertOf(toHolding, new RemoveReferenceMutation(brand(BRAND_PK))), ANCHORS_ONLY,
				reinsert, ANCHORS_ONLY
			));
			scenarios.add(new Scenario(
				input.changedTo(false) + "_WITH_REFERENCE_REMOVED", condition, holding, WITH_PRODUCT,
				upsertOf(toFailing, new RemoveReferenceMutation(brand(BRAND_PK))), ANCHORS_ONLY,
				reinsert, ANCHORS_ONLY
			));
			// the change meets the reference removed before it as a dropped one and must leave it alone
			scenarios.add(new Scenario(
				"CONTROL_REFERENCE_REMOVED_THEN_" + input.changedTo(false), condition, holding, WITH_PRODUCT,
				upsertOf(new RemoveReferenceMutation(brand(BRAND_PK)), toFailing), ANCHORS_ONLY,
				reinsert, ANCHORS_ONLY
			));
			scenarios.add(new Scenario(
				input.changedTo(true) + "_AFTER_REFERENCE_INSERTED", condition, failing, ANCHORS_ONLY,
				upsertOf(
					new InsertReferenceMutation(brand(INSERTED_BRAND_PK)),
					shareOf(INSERTED_BRAND_PK, INSERTED_SHARE),
					toHolding
				),
				WITH_PRODUCT,
				upsertOf(shareOf(BRAND_PK, INSIDE_ANCHOR_RANGE), shareOf(INSERTED_BRAND_PK, INSIDE_ANCHOR_RANGE)),
				ANCHORS_ONLY
			));
			// the final add scheduled by the change runs before the scope change unindexes the product
			scenarios.add(new Scenario(
				"CONTROL_" + input.changedTo(true) + "_WITH_SCOPE_CHANGE", condition, failing, ANCHORS_ONLY,
				upsertOf(toHolding, new SetEntityScopeMutation(Scope.ARCHIVED)), ANCHORS_ONLY,
				upsertOf(new SetEntityScopeMutation(Scope.LIVE), shareOf(INSIDE_ANCHOR_RANGE)), ANCHORS_ONLY
			));
		}
		return inBothCatalogStates(scenarios);
	}

	/**
	 * The scenarios of a product moving to the archive and changing an input afterwards in the same upsert, for both
	 * inputs and both directions of the verdict. The scope change re-indexes the product in the archive from its
	 * stored state, so the input change that follows must withdraw in the archive what the re-index added there.
	 *
	 * The product of the parent scenarios has no parent until the upsert sets an archived one after the move, so the
	 * parent the condition reads always sits in the scope it is evaluated in - a parent in the other scope reads as
	 * absent, which `CrossScopeParentConditionFunctionalTest` covers. The verdict turns true under a condition that
	 * fails without a parent, and false under one that holds without a parent.
	 *
	 * @return arguments of {@link #shouldFollowInputWhenChangedAfterScopeChange}
	 */
	@Nonnull
	static Stream<Arguments> scopeChangedScenarios() {
		return inBothCatalogStates(scopeChangeScenarios(Fixture.BOTH_SCOPES));
	}

	/**
	 * The scenarios of {@link #scopeChangedScenarios} on a catalog whose histogram exists in the archive only, so the
	 * scope the product leaves has no histogram trigger the input change could withdraw through.
	 *
	 * @return arguments of {@link #shouldFollowInputWhenChangedAfterScopeChangeIntoOnlyHistogramScope}
	 */
	@Nonnull
	static Stream<Arguments> scopeChangedIntoOnlyHistogramScopeScenarios() {
		return inBothCatalogStates(scopeChangeScenarios(Fixture.ARCHIVE_ONLY));
	}

	/**
	 * Creates the scenarios of a product moving to the archive and changing an input afterwards in the same upsert.
	 *
	 * @param fixture the catalog the scenarios run on, checking the archive
	 * @return the scenarios
	 */
	@Nonnull
	private static List<Scenario> scopeChangeScenarios(@Nonnull Fixture fixture) {
		final List<Scenario> scenarios = new ArrayList<>(4);
		final Consumer<EvitaSessionContract> moveShare = upsertOf(shareOf(INSIDE_ANCHOR_RANGE));
		final Consumer<EntityBuilder> withoutParent = product -> withShare(product);
		scenarios.add(new Scenario(
			"SCOPE_ARCHIVED_THEN_PARENT_SET_TO_ACTIVE", PARENT_ACTIVE, withoutParent, ANCHORS_ONLY,
			upsertOf(new SetEntityScopeMutation(Scope.ARCHIVED), new SetParentMutation(ARCHIVED_ACTIVE_PARENT_PK)),
			WITH_PRODUCT, moveShare, ANCHORS_ONLY, fixture
		));
		scenarios.add(new Scenario(
			"SCOPE_ARCHIVED_THEN_PARENT_SET_TO_INACTIVE", PARENT_ACTIVE_OR_ABSENT, withoutParent, ANCHORS_ONLY,
			upsertOf(new SetEntityScopeMutation(Scope.ARCHIVED), new SetParentMutation(ARCHIVED_INACTIVE_PARENT_PK)),
			ANCHORS_ONLY, moveShare, ANCHORS_ONLY, fixture
		));
		scenarios.add(new Scenario(
			"SCOPE_ARCHIVED_THEN_" + Input.LABEL.changedTo(true), LABEL_IS_SHOWN,
			Input.LABEL.storedWith(false), ANCHORS_ONLY,
			upsertOf(new SetEntityScopeMutation(Scope.ARCHIVED), Input.LABEL.changeTo(true)),
			WITH_PRODUCT, moveShare, ANCHORS_ONLY, fixture
		));
		scenarios.add(new Scenario(
			"SCOPE_ARCHIVED_THEN_" + Input.LABEL.changedTo(false), LABEL_IS_SHOWN,
			Input.LABEL.storedWith(true), ANCHORS_ONLY,
			upsertOf(new SetEntityScopeMutation(Scope.ARCHIVED), Input.LABEL.changeTo(false)),
			ANCHORS_ONLY, moveShare, ANCHORS_ONLY, fixture
		));
		return scenarios;
	}

	/**
	 * The scenarios of a reference carrying a histogram conditioned on the parent and another conditioned on the
	 * label, where the product changes one of the two inputs. The change withdraws the reference from both histograms,
	 * so the final add must give the histogram whose input did not change its value back. Each description lists the
	 * share histogram first and the label share histogram second.
	 *
	 * @return arguments of {@link #shouldFollowOnlyChangedInputWhenReferenceHasHistogramPerInput}
	 */
	@Nonnull
	static Stream<Arguments> histogramPerInputScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(3);
		final Consumer<EvitaSessionContract> moveShare = upsertOf(shareOf(INSIDE_ANCHOR_RANGE));
		final Consumer<EntityBuilder> shownUnderActive = product -> withShare(
			product.setParent(ACTIVE_PARENT_PK).setAssociatedData(ASSOCIATED_DATA_LABEL, LABEL_SHOWN)
		);
		final Consumer<EntityBuilder> hiddenUnderActive = product -> withShare(
			product.setParent(ACTIVE_PARENT_PK).setAssociatedData(ASSOCIATED_DATA_LABEL, LABEL_HIDDEN)
		);
		final String bothAnchorsOnly = both(ANCHORS_ONLY, ANCHORS_ONLY);
		scenarios.add(new Scenario(
			"LABEL_CHANGED_TO_HIDDEN_BESIDE_PARENT_HISTOGRAM", PARENT_ACTIVE, shownUnderActive,
			both(WITH_PRODUCT, WITH_PRODUCT), upsertOf(Input.LABEL.changeTo(false)),
			both(WITH_PRODUCT, ANCHORS_ONLY), moveShare, bothAnchorsOnly, Fixture.TWO_HISTOGRAMS
		));
		scenarios.add(new Scenario(
			"LABEL_CHANGED_TO_SHOWN_BESIDE_PARENT_HISTOGRAM", PARENT_ACTIVE, hiddenUnderActive,
			both(WITH_PRODUCT, ANCHORS_ONLY), upsertOf(Input.LABEL.changeTo(true)),
			both(WITH_PRODUCT, WITH_PRODUCT), moveShare, bothAnchorsOnly, Fixture.TWO_HISTOGRAMS
		));
		scenarios.add(new Scenario(
			"PARENT_CHANGED_TO_INACTIVE_BESIDE_LABEL_HISTOGRAM", PARENT_ACTIVE, shownUnderActive,
			both(WITH_PRODUCT, WITH_PRODUCT), upsertOf(Input.PARENT.changeTo(false)),
			both(ANCHORS_ONLY, WITH_PRODUCT), moveShare, bothAnchorsOnly, Fixture.TWO_HISTOGRAMS
		));
		return inBothCatalogStates(scenarios);
	}

	/**
	 * Combines both inputs with both catalog states.
	 *
	 * @return arguments of {@link #shouldRemoveProductWhoseConditionHolds} and
	 * {@link #shouldRemoveProductWhoseConditionFails}
	 */
	@Nonnull
	static Stream<Arguments> inputsInBothCatalogStates() {
		final List<Arguments> result = new ArrayList<>(4);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final Input input : Input.values()) {
				result.add(Arguments.of(state, input));
			}
		}
		return result.stream();
	}

	/**
	 * Applies the upsert of the scenario - a change of the parent of the product, or the creation of a product under
	 * its final parent - and checks the histogram right after it, after a restart, after the follow-up that moves
	 * the share among the anchors, and after one more restart.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("parentScenarios")
	@DisplayName("a changed parent of the product decides the histogram as a parent the product was created with")
	void shouldFollowParentWhenProductChangesIt(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		runScenario(state, scenario);
	}

	/**
	 * Applies the upsert of the scenario - a change of the associated data of the product, or the creation of a
	 * product with its final associated data - and checks the histogram right after it, after a restart, after the
	 * follow-up that moves the share among the anchors, and after one more restart.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("associatedDataScenarios")
	@DisplayName("changed associated data of the product decide the histogram as data the product was created with")
	void shouldFollowAssociatedDataWhenProductChangesIt(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		runScenario(state, scenario);
	}

	/**
	 * Applies an upsert changing an input of the condition and rewriting the share of the reference, in either order,
	 * and checks the histogram as {@link #runScenario} does.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("shareRewrittenScenarios")
	@DisplayName("an input changed together with the share decides the histogram in either order")
	void shouldFollowInputWhenChangedTogetherWithShare(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		runScenario(state, scenario);
	}

	/**
	 * Applies an upsert changing an input of the condition together with the removal or insertion of a reference, or
	 * followed by a scope change, and checks the histogram as {@link #runScenario} does.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("referenceChangedScenarios")
	@DisplayName("an input changed with a removed or inserted reference or a scope change decides the histogram")
	void shouldFollowInputWhenChangedTogetherWithReference(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		runScenario(state, scenario);
	}

	/**
	 * Applies an upsert moving the product to the archive and changing an input of the condition afterwards, and
	 * checks the histogram of the archive as {@link #runScenario} does.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("scopeChangedScenarios")
	@DisplayName("an input changed after a scope change in one upsert decides the histogram of the new scope")
	void shouldFollowInputWhenChangedAfterScopeChange(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		runScenario(state, scenario);
	}

	/**
	 * Applies an upsert moving the product to the archive, the only scope with the histogram, and changing an input of
	 * the condition afterwards, and checks the histogram of the archive as {@link #runScenario} does.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("scopeChangedIntoOnlyHistogramScopeScenarios")
	@DisplayName("an input changed after a scope change into the only scope with the histogram decides it")
	void shouldFollowInputWhenChangedAfterScopeChangeIntoOnlyHistogramScope(
		@Nonnull CatalogState state, @Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Applies an upsert changing one input of a reference that carries a histogram per input, and checks both
	 * histograms as {@link #runScenario} does.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("histogramPerInputScenarios")
	@DisplayName("a changed input decides only the histogram reading it when the reference has one per input")
	void shouldFollowOnlyChangedInputWhenReferenceHasHistogramPerInput(
		@Nonnull CatalogState state, @Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Removes a product whose condition holds and checks that its value leaves the histogram. The follow-up stores the
	 * product again with an input making the condition fail, which adds nothing, so a value the removal left behind
	 * stays visible after it as well.
	 *
	 * @param state the state the catalog is in when the product is removed
	 * @param input the input the condition reads
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("inputsInBothCatalogStates")
	@DisplayName("removing a product whose condition holds takes its value out of the histogram")
	void shouldRemoveProductWhoseConditionHolds(@Nonnull CatalogState state, @Nonnull Input input) {
		runScenario(state, new Scenario(
			"PRODUCT_REMOVED_WHILE_" + input + "_HOLDS", input.condition(), input.storedWith(true),
			WITH_PRODUCT, session -> {
				session.deleteEntity(ENTITY_PRODUCT, PRODUCT_PK);
			},
			ANCHORS_ONLY, createProduct(input.storedWith(false)), ANCHORS_ONLY
		));
	}

	/**
	 * Removes a product whose condition fails, so it holds no value, and checks that the removal succeeds and leaves
	 * the histogram as it is. Entity removal drops the parent before the references, so a withdrawal on the parent
	 * removal would schedule a final add for a reference that leaves with the product; it drops the associated data
	 * after the references, so the associated-data removal meets only dropped references, which have nothing to
	 * withdraw. The follow-up stores the product again with an input making the condition hold, whose value must then
	 * enter the histogram.
	 *
	 * @param state the state the catalog is in when the product is removed
	 * @param input the input the condition reads
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("inputsInBothCatalogStates")
	@DisplayName("removing a product whose condition fails leaves the histogram as it is")
	void shouldRemoveProductWhoseConditionFails(@Nonnull CatalogState state, @Nonnull Input input) {
		runScenario(state, new Scenario(
			"PRODUCT_REMOVED_WHILE_" + input + "_FAILS", input.condition(), input.storedWith(false),
			ANCHORS_ONLY, session -> {
				session.deleteEntity(ENTITY_PRODUCT, PRODUCT_PK);
			},
			ANCHORS_ONLY, createProduct(input.storedWith(true)), WITH_PRODUCT
		));
	}

	/**
	 * Runs one scenario: sets the catalog up, checks the starting histogram, applies the upsert under test and checks
	 * the histogram before and after a restart, applies the follow-up and checks the histogram before and after one
	 * more restart. All failed checks are reported together.
	 *
	 * @param state    the state the catalog is in when the upsert is applied
	 * @param scenario the scenario
	 */
	private void runScenario(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		final TestPaths paths = createTestPaths("ParentAndAssociatedDataHistogram_" + state + "_" + scenario.name());
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final List<Executable> checks = new ArrayList<>(8);
		final Fixture fixture = scenario.fixture();
		Evita evita = new Evita(configuration);
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpCatalog(session, scenario.condition(), fixture);
				if (scenario.product() != null) {
					createProduct(scenario.product()).accept(session);
				}
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			recordHistogram(evita, "before the upsert", scenario.before(), fixture, checks);

			applyUpdate(evita, "the upsert", checks, scenario.upsert());
			recordHistogram(evita, "after the upsert", scenario.afterUpsert(), fixture, checks);
			evita = restart(evita, configuration);
			recordHistogram(evita, "after the upsert and restart", scenario.afterUpsert(), fixture, checks);

			// a value the upsert left behind survives the removal the follow-up makes, and nothing else shows it
			applyUpdate(evita, "the follow-up", checks, scenario.followUp());
			recordHistogram(evita, "after the follow-up", scenario.afterFollowUp(), fixture, checks);
			evita = restart(evita, configuration);
			recordHistogram(evita, "after the follow-up and restart", scenario.afterFollowUp(), fixture, checks);
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Gives the product its reference to the brand with the share 5.
	 *
	 * @param product the builder of the product
	 */
	private static void withShare(@Nonnull EntityBuilder product) {
		product.setReference(
			REFERENCE_BRAND, BRAND_PK, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, PRODUCT_SHARE)
		);
	}

	/**
	 * Creates the upsert storing a new product set up by `product`.
	 *
	 * @param product sets up the new product
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProduct(@Nonnull Consumer<EntityBuilder> product) {
		return session -> {
			final EntityBuilder builder = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK);
			product.accept(builder);
			builder.upsertVia(session);
		};
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
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final Scenario scenario : scenarios) {
				result.add(Arguments.of(state, scenario));
			}
		}
		return result.stream();
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
	 * Creates the key of the product's reference to the brand `brandPk`, with no internal primary key.
	 *
	 * @param brandPk the primary key of the brand
	 * @return the reference key
	 */
	@Nonnull
	private static ReferenceKey brand(int brandPk) {
		return new ReferenceKey(REFERENCE_BRAND, brandPk);
	}

	/**
	 * Creates the change of the share of the product's reference to the brand to `share`.
	 *
	 * @param share the new share
	 * @return the mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation shareOf(int share) {
		return shareOf(BRAND_PK, share);
	}

	/**
	 * Creates the change of the share of the product's reference to the brand `brandPk` to `share`.
	 *
	 * @param brandPk the primary key of the referenced brand
	 * @param share   the new share
	 * @return the mutation
	 */
	@Nonnull
	private static ReferenceAttributeMutation shareOf(int brandPk, int share) {
		return new ReferenceAttributeMutation(brand(brandPk), new UpsertAttributeMutation(ATTRIBUTE_SHARE, share));
	}

	/**
	 * Creates one upsert of the existing product made of `mutations`.
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
	 * Records the check that the histograms `fixture` checks are described as `expected` at the moment `stage` names.
	 *
	 * @param evita    the engine
	 * @param stage    names the moment of the check
	 * @param expected the expected description of the histograms
	 * @param fixture  the catalog the scenario runs on, naming the scope and the histograms checked
	 * @param checks   the collected checks
	 */
	private static void recordHistogram(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull String expected,
		@Nonnull Fixture fixture,
		@Nonnull List<Executable> checks
	) {
		final Scope scope = fixture.checkedScope;
		try {
			final String actual = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return fixture == Fixture.TWO_HISTOGRAMS ?
						both(
							describeHistogram(session, scope, HISTOGRAM_SHARE),
							describeHistogram(session, scope, HISTOGRAM_LABEL_SHARE)
						) :
						describeHistogram(session, scope, HISTOGRAM_SHARE);
				}
			);
			checks.add(() -> assertEquals(expected, actual, stage + ": histograms in " + scope));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": query of the histograms in " + scope + " threw " + ex, ex));
		}
	}

	/**
	 * Describes the share histogram and the label share histogram of {@link Fixture#TWO_HISTOGRAMS} together.
	 *
	 * @param share      the description of the share histogram
	 * @param labelShare the description of the label share histogram
	 * @return the description of both, in this order
	 */
	@Nonnull
	private static String both(@Nonnull String share, @Nonnull String labelShare) {
		return HISTOGRAM_SHARE + " " + share + ", " + HISTOGRAM_LABEL_SHARE + " " + labelShare;
	}

	/**
	 * Describes the histogram `histogramName` the reference summary of `scope` computes: its smallest and largest
	 * value, or the absence of the histogram.
	 *
	 * @param session       the session to query
	 * @param scope         the scope whose histogram is described
	 * @param histogramName the name of the histogram
	 * @return the description
	 */
	@Nonnull
	private static String describeHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope scope,
		@Nonnull String histogramName
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(scope(scope)),
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
	 * Defines the schemas and stores the brands, the two parents and the anchor product.
	 *
	 * The product is hierarchical and has the filterable `status` attribute and the `label` associated data. Its
	 * reference to a brand is indexed for filtering and faceted, and carries the `share` attribute, the share
	 * histogram over it, and `condition` as the condition of the histogram. The parent with the primary key 200 is
	 * active and the one with 300 is not; neither references a brand, and each gets its status in an upsert of its
	 * own after it is stored. The anchor product is a child of the active parent, its label is shown, and it
	 * references two brands with the shares 10 and 20, so the histogram spans the range 10 to 20 under either
	 * condition before the product enters it.
	 *
	 * {@link Fixture#BOTH_SCOPES} and {@link Fixture#ARCHIVE_ONLY} index the hierarchy and the reference in both
	 * scopes and keep the histogram with its condition in both scopes or in the archive only; the archive holds its own
	 * pair of parents - 400 active and 500 inactive - and its own anchor product 101 under the active one, because a
	 * condition reads a parent only in the scope the parent is in. {@link Fixture#TWO_HISTOGRAMS} gives the reference
	 * the label share histogram beside the share histogram, each with its own condition.
	 * {@link Fixture#LOCALIZED_LABEL} gives the product the English locale and the localized `localizedLabel`
	 * associated data, which the anchor product shows in English.
	 *
	 * @param session   the session of the catalog in warm-up
	 * @param condition the condition admitting a reference to the share histogram
	 * @param fixture   the catalog the scenario runs on
	 */
	private static void setUpCatalog(
		@Nonnull EvitaSessionContract session,
		@Nonnull String condition,
		@Nonnull Fixture fixture
	) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		switch (fixture) {
			case BOTH_SCOPES -> defineProductIndexedInBothScopes(session, condition, Scope.values());
			case ARCHIVE_ONLY -> defineProductIndexedInBothScopes(session, condition, new Scope[]{Scope.ARCHIVED});
			case TWO_HISTOGRAMS -> defineProductWithHistogramPerInput(session, condition);
			case LIVE_ONLY, LOCALIZED_LABEL -> {
				final EntitySchemaBuilder product = session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withAttribute(ATTRIBUTE_STATUS, String.class, whichIs -> whichIs.filterable().nullable())
					.withAssociatedData(ASSOCIATED_DATA_LABEL, String.class, whichIs -> whichIs.nullable())
					.withReferenceToEntity(
						REFERENCE_BRAND, ENTITY_BRAND,
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.faceted()
							.withAttribute(ATTRIBUTE_SHARE, Integer.class, thatIs -> thatIs.filterable().nullable())
							.bucketed(HISTOGRAM_SHARE, ExpressionFactory.parse(SHARE_VALUE))
							.bucketedPartially(ExpressionFactory.parse(condition))
					);
				if (fixture == Fixture.LOCALIZED_LABEL) {
					product.withLocale(Locale.ENGLISH)
						.withAssociatedData(
							ASSOCIATED_DATA_LOCALIZED_LABEL, String.class, whichIs -> whichIs.localized().nullable()
						);
				}
				product.updateVia(session);
			}
		}

		for (int brandPk = BRAND_PK; brandPk <= FIRST_ANCHOR_BRAND_PK + 1; brandPk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND, brandPk));
		}
		// each parent is stored first and gets its status in a second upsert: a new product stored together with
		// the status the parent condition reads fails once the collection holds a product, because the condition's
		// cross-entity pre-pass looks the new product up in the hierarchy before it is placed there
		for (final int parentPk : new int[]{ACTIVE_PARENT_PK, INACTIVE_PARENT_PK}) {
			session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, parentPk));
			session.getEntity(ENTITY_PRODUCT, parentPk, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setAttribute(ATTRIBUTE_STATUS, parentPk == ACTIVE_PARENT_PK ? STATUS_ACTIVE : STATUS_INACTIVE)
				.upsertVia(session);
		}
		final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, ANCHOR_PK)
			.setParent(ACTIVE_PARENT_PK)
			.setAssociatedData(ASSOCIATED_DATA_LABEL, LABEL_SHOWN);
		if (fixture == Fixture.LOCALIZED_LABEL) {
			anchor.setAssociatedData(ASSOCIATED_DATA_LOCALIZED_LABEL, Locale.ENGLISH, LABEL_SHOWN);
		}
		int brandPk = FIRST_ANCHOR_BRAND_PK;
		for (final int anchorValue : new int[]{ANCHOR_VALUE, SECOND_ANCHOR_VALUE}) {
			anchor.setReference(
				REFERENCE_BRAND, brandPk++, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, anchorValue)
			);
		}
		anchor.upsertVia(session);
		if (fixture.archive) {
			storeArchivedParentsAndAnchor(session);
		}
	}

	/**
	 * Defines the product of {@link #setUpCatalog} with the share histogram conditioned on `condition` and the label
	 * share histogram conditioned on the label being shown, both in the live scope and each with its own condition.
	 *
	 * @param session   the session of the catalog in warm-up
	 * @param condition the condition admitting a reference to the share histogram
	 */
	private static void defineProductWithHistogramPerInput(
		@Nonnull EvitaSessionContract session,
		@Nonnull String condition
	) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withHierarchy()
			.withAttribute(ATTRIBUTE_STATUS, String.class, whichIs -> whichIs.filterable().nullable())
			.withAssociatedData(ASSOCIATED_DATA_LABEL, String.class, whichIs -> whichIs.nullable())
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.faceted()
					.withAttribute(ATTRIBUTE_SHARE, Integer.class, thatIs -> thatIs.filterable().nullable())
					.bucketedInScope(
						Scope.LIVE, HISTOGRAM_SHARE, ExpressionFactory.parse(SHARE_VALUE),
						ExpressionFactory.parse(condition)
					)
					.bucketedInScope(
						Scope.LIVE, HISTOGRAM_LABEL_SHARE, ExpressionFactory.parse(SHARE_VALUE),
						ExpressionFactory.parse(LABEL_IS_SHOWN)
					)
			)
			.updateVia(session);
	}

	/**
	 * Defines the product of {@link #setUpCatalog} with its hierarchy, its `status` attribute and its reference to a
	 * brand indexed in both scopes, and the share histogram with `condition` in `histogramScopes`.
	 *
	 * @param session         the session of the catalog in warm-up
	 * @param condition       the condition admitting a reference to the share histogram
	 * @param histogramScopes the scopes the share histogram exists in
	 */
	private static void defineProductIndexedInBothScopes(
		@Nonnull EvitaSessionContract session,
		@Nonnull String condition,
		@Nonnull Scope[] histogramScopes
	) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withHierarchyIndexedInScope(Scope.values())
			.withAttribute(
				ATTRIBUTE_STATUS, String.class, whichIs -> whichIs.filterableInScope(Scope.values()).nullable()
			)
			.withAssociatedData(ASSOCIATED_DATA_LABEL, String.class, whichIs -> whichIs.nullable())
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE,
				whichIs -> {
					whichIs.indexedForFilteringInScope(Scope.values())
						.facetedInScope(Scope.values())
						.withAttribute(
							ATTRIBUTE_SHARE, Integer.class,
							thatIs -> thatIs.filterableInScope(Scope.values()).nullable()
						);
					for (final Scope scope : histogramScopes) {
						whichIs.bucketedInScope(scope, HISTOGRAM_SHARE, ExpressionFactory.parse(SHARE_VALUE))
							.bucketedPartiallyInScope(scope, ExpressionFactory.parse(condition));
					}
				}
			)
			.updateVia(session);
	}

	/**
	 * Stores the archived parents - 400 active and 500 inactive, each getting its status in an upsert of its own as
	 * the live parents do - and the archived anchor product under the active one, with the label shown and the shares
	 * 10 and 20.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void storeArchivedParentsAndAnchor(@Nonnull EvitaSessionContract session) {
		for (final int parentPk : new int[]{ARCHIVED_ACTIVE_PARENT_PK, ARCHIVED_INACTIVE_PARENT_PK}) {
			session.createNewEntity(ENTITY_PRODUCT, parentPk).setScope(Scope.ARCHIVED).upsertVia(session);
			session.getEntity(ENTITY_PRODUCT, parentPk, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setAttribute(
					ATTRIBUTE_STATUS, parentPk == ARCHIVED_ACTIVE_PARENT_PK ? STATUS_ACTIVE : STATUS_INACTIVE
				)
				.upsertVia(session);
		}
		final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, ARCHIVED_ANCHOR_PK)
			.setScope(Scope.ARCHIVED)
			.setParent(ARCHIVED_ACTIVE_PARENT_PK)
			.setAssociatedData(ASSOCIATED_DATA_LABEL, LABEL_SHOWN);
		int brandPk = FIRST_ANCHOR_BRAND_PK;
		for (final int anchorValue : new int[]{ANCHOR_VALUE, SECOND_ANCHOR_VALUE}) {
			anchor.setReference(
				REFERENCE_BRAND, brandPk++, whichIs -> whichIs.setAttribute(ATTRIBUTE_SHARE, anchorValue)
			);
		}
		anchor.upsertVia(session);
	}

}
