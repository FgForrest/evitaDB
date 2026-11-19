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
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.params.provider.Arguments;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;

/**
 * The fixture of the functional tests of a group histogram whose owners spread over several reduced indexes of the
 * group: the names, the primary keys and the ranks they share, the reference schema, and the upserts and descriptions
 * both build their scenarios from.
 *
 * The reference of a product to a brand allows duplicates, is grouped by a brand group, and its representative
 * attribute `rank` decides which reduced index of the group a reference sits in. The product references the brand 10,
 * the anchor product the brands 12 and 13, all in the group 1.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
interface GroupHistogramTestSupport {

	String ENTITY_PRODUCT = "product";
	String ENTITY_BRAND = "brand";
	String ENTITY_BRAND_GROUP = "brandGroup";
	String REFERENCE_BRAND = "brand";
	String ATTRIBUTE_SHARE = "share";
	String ATTRIBUTE_RANK = "rank";
	String HISTOGRAM_SHARE = "shareHistogram";
	int PRODUCT_PK = 1;
	int ANCHOR_PK = 100;
	int GROUP_PK = 1;
	int BRAND_PK = 10;
	int FIRST_ANCHOR_BRAND_PK = 12;
	int SECOND_ANCHOR_BRAND_PK = 13;
	/**
	 * The share of the reference of the anchor product to the brand 12.
	 */
	int ANCHOR_VALUE = 10;
	/**
	 * The share of the reference of the anchor product to the brand 13.
	 */
	int SECOND_ANCHOR_VALUE = 20;
	int FIRST_RANK = 1;
	int SECOND_RANK = 2;
	int THIRD_RANK = 3;
	int BUCKET_COUNT = 10;
	/**
	 * The description of a histogram that does not exist.
	 */
	String NO_HISTOGRAM = "no histogram";

	/**
	 * Pairs every scenario with each catalog state the upserts are applied in, warm-up first.
	 *
	 * @param scenarios the scenarios
	 * @param <S>       the type of the scenarios
	 * @return arguments of a parameterized test: the catalog state and the scenario
	 */
	@Nonnull
	static <S> Stream<Arguments> withBothCatalogStates(@Nonnull List<S> scenarios) {
		final List<Arguments> result = new ArrayList<>(scenarios.size() * 2);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final S scenario : scenarios) {
				result.add(Arguments.of(state, scenario));
			}
		}
		return result.stream();
	}

	/**
	 * Defines the product with its reference to a brand: the reference allows duplicates, is grouped by a brand group,
	 * indexed for filtering and faceted, indexed with both its entity and its group component, and carries the
	 * filterable `share` attribute and the representative `rank` attribute. Its histograms are left to `histograms`.
	 *
	 * @param session    the session of the catalog in warm-up
	 * @param histograms defines the histograms of the reference
	 */
	static void defineProduct(
		@Nonnull EvitaSessionContract session,
		@Nonnull Consumer<ReferenceSchemaBuilder> histograms
	) {
		defineProduct(session, Integer.class, 0, new Scope[]{Scope.LIVE}, histograms);
	}

	/**
	 * Defines the product as {@link #defineProduct(EvitaSessionContract, Consumer)} does, with the `share` attribute of
	 * the type `shareType` indexed with `shareIndexedDecimalPlaces` decimal places, and with the reference and its
	 * attributes indexed in each of `scopes` instead of the live scope alone.
	 *
	 * @param session                   the session of the catalog in warm-up
	 * @param shareType                 the type of the `share` attribute
	 * @param shareIndexedDecimalPlaces the decimal places the `share` attribute is indexed with, `0` unless it is a
	 *                                  `BigDecimal`
	 * @param scopes                    the scopes the reference is indexed, faceted and filterable in
	 * @param histograms                defines the histograms of the reference
	 */
	static void defineProduct(
		@Nonnull EvitaSessionContract session,
		@Nonnull Class<? extends Number> shareType,
		int shareIndexedDecimalPlaces,
		@Nonnull Scope[] scopes,
		@Nonnull Consumer<ReferenceSchemaBuilder> histograms
	) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> {
					whichIs
						.withGroupTypeRelatedToEntity(ENTITY_BRAND_GROUP)
						.indexedForFilteringInScope(scopes)
						.facetedInScope(scopes);
					for (final Scope scope : scopes) {
						whichIs.indexedWithComponentsInScope(
							scope,
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						);
					}
					whichIs
						.withAttribute(
							ATTRIBUTE_SHARE, shareType,
							thatIs -> thatIs.filterableInScope(scopes)
								.nullable()
								.indexDecimalPlaces(shareIndexedDecimalPlaces)
						)
						.withAttribute(
							ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.filterableInScope(scopes).representative()
						);
					histograms.accept(whichIs);
				}
			)
			.updateVia(session);
	}

	/**
	 * Creates the upsert storing the anchor product with its references to the brands 12 and 13 in the group 1 at
	 * `rank`, with the shares 10 and 20 where `withShares` says so. Without the shares the anchor product keeps the
	 * reduced index of its rank advertised without contributing to the share histogram.
	 *
	 * @param rank       the rank of the references
	 * @param withShares TRUE to give the references their shares
	 * @return the upsert
	 */
	@Nonnull
	static Consumer<EvitaSessionContract> createAnchor(int rank, boolean withShares) {
		return session -> {
			final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, ANCHOR_PK);
			addReference(anchor, FIRST_ANCHOR_BRAND_PK, GROUP_PK, rank, withShares ? ANCHOR_VALUE : null);
			addReference(anchor, SECOND_ANCHOR_BRAND_PK, GROUP_PK, rank, withShares ? SECOND_ANCHOR_VALUE : null);
			anchor.upsertVia(session);
		};
	}

	/**
	 * Adds a new reference to the brand `brandPk` in the group `groupPk` at `rank` to `owner`, carrying `share` when
	 * it is not `null`.
	 *
	 * @param owner   the builder of the owner
	 * @param brandPk the primary key of the referenced brand
	 * @param groupPk the primary key of the group
	 * @param rank    the rank of the reference
	 * @param share   the share of the reference, or `null` for none
	 */
	static void addReference(
		@Nonnull EntityBuilder owner,
		int brandPk,
		int groupPk,
		int rank,
		@Nullable Number share
	) {
		// the predicate matches no stored reference, so a new one is created - the only form a reference that allows
		// duplicates accepts
		owner.setOrUpdateReference(
			REFERENCE_BRAND, brandPk, reference -> false,
			whichIs -> {
				whichIs.setGroup(ENTITY_BRAND_GROUP, groupPk).setAttribute(ATTRIBUTE_RANK, rank);
				if (share != null) {
					whichIs.setAttribute(ATTRIBUTE_SHARE, share);
				}
			}
		);
	}

	/**
	 * Creates the upsert giving the references of the stored anchor product their shares, `firstShare` to the one to
	 * the brand 12 and `secondShare` to the one to the brand 13, in one upsert.
	 *
	 * @param firstShare  the share of the reference to the brand 12
	 * @param secondShare the share of the reference to the brand 13
	 * @return the upsert
	 */
	@Nonnull
	static Consumer<EvitaSessionContract> anchorShares(int firstShare, int secondShare) {
		return session -> {
			final Collection<ReferenceContract> references = storedReferences(session, ANCHOR_PK);
			final List<LocalMutation<?, ?>> mutations = new ArrayList<>(references.size());
			for (final ReferenceContract reference : references) {
				final int share = reference.getReferencedPrimaryKey() == FIRST_ANCHOR_BRAND_PK ?
					firstShare : secondShare;
				mutations.add(
					new ReferenceAttributeMutation(
						reference.getReferenceKey(), new UpsertAttributeMutation(ATTRIBUTE_SHARE, share)
					)
				);
			}
			upsert(session, ANCHOR_PK, mutations);
		};
	}

	/**
	 * Creates the upsert setting the attribute `attributeName` of every reference of the stored owner `ownerPk` to
	 * `value`. The references allow duplicates, so each mutation addresses its reference by the full key read from
	 * storage right before the upsert.
	 *
	 * @param ownerPk       the primary key of the owner
	 * @param attributeName the name of the reference attribute
	 * @param value         the new value
	 * @return the upsert
	 */
	@Nonnull
	static Consumer<EvitaSessionContract> everyReferenceAttribute(
		int ownerPk,
		@Nonnull String attributeName,
		@Nonnull Number value
	) {
		return session -> {
			final Collection<ReferenceContract> references = storedReferences(session, ownerPk);
			final List<LocalMutation<?, ?>> mutations = new ArrayList<>(references.size());
			for (final ReferenceContract reference : references) {
				mutations.add(
					new ReferenceAttributeMutation(
						reference.getReferenceKey(), new UpsertAttributeMutation(attributeName, value)
					)
				);
			}
			upsert(session, ownerPk, mutations);
		};
	}

	/**
	 * Returns the stored references of the owner `ownerPk` to a brand.
	 *
	 * @param session the session to read from
	 * @param ownerPk the primary key of the owner
	 * @return the stored references
	 */
	@Nonnull
	static Collection<ReferenceContract> storedReferences(@Nonnull EvitaSessionContract session, int ownerPk) {
		return session.getEntity(ENTITY_PRODUCT, ownerPk, entityFetchAllContent())
			.orElseThrow()
			.getReferences(REFERENCE_BRAND);
	}

	/**
	 * Applies one upsert of the existing owner `ownerPk` made of `mutations`.
	 *
	 * @param session   the session to write in
	 * @param ownerPk   the primary key of the owner
	 * @param mutations the local mutations of the upsert, in the order they are applied
	 */
	static void upsert(
		@Nonnull EvitaSessionContract session,
		int ownerPk,
		@Nonnull List<LocalMutation<?, ?>> mutations
	) {
		session.upsertEntity(
			new EntityUpsertMutation(
				ENTITY_PRODUCT, ownerPk, EntityExistence.MUST_EXIST, mutations.toArray(LocalMutation<?, ?>[]::new)
			)
		);
	}

	/**
	 * Closes the engine and starts a new one on the stored data.
	 *
	 * @param evita         the running engine
	 * @param configuration the configuration of the engine
	 * @return the new engine, fully initialized
	 */
	@Nonnull
	static Evita restart(@Nonnull Evita evita, @Nonnull EvitaConfiguration configuration) {
		evita.close();
		final Evita restarted = new Evita(configuration);
		restarted.waitUntilFullyInitialized();
		return restarted;
	}

	/**
	 * Describes a histogram spanning the values `min` to `max`, anchored at the brands `minBrandPk` and `maxBrandPk`.
	 *
	 * @param min        the smallest value
	 * @param minBrandPk the primary key of the brand anchoring the smallest value
	 * @param max        the largest value
	 * @param maxBrandPk the primary key of the brand anchoring the largest value
	 * @return the description of such a histogram with its boundaries
	 */
	@Nonnull
	static String anchoredRange(int min, int minBrandPk, int max, int maxBrandPk) {
		return "min=" + min + "@" + minBrandPk + " max=" + max + "@" + maxBrandPk;
	}

	/**
	 * Describes a boundary entity of a histogram by its primary key.
	 *
	 * @param boundary the boundary entity, or `null` when the histogram has none
	 * @return the primary key of the entity, or `none`
	 */
	@Nonnull
	static String describeBoundary(@Nullable SealedEntity boundary) {
		return boundary == null ? "none" : String.valueOf(boundary.getPrimaryKey());
	}

	/**
	 * Formats `number` without trailing zeros and without an exponent.
	 *
	 * @param number the number
	 * @return the plain form of the number
	 */
	@Nonnull
	static String plain(@Nullable BigDecimal number) {
		return number == null ? "null" : number.stripTrailingZeros().toPlainString();
	}

}
