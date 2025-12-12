/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.performance.client.state;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.performance.client.ClientDataFullDatabaseState;
import io.evitadb.performance.generators.RandomQueryGenerator;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;

/**
 * Base state class for paginatedEntityRead tests on client data set.
 * See benchmark description on the method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ClientPageReadState extends ClientDataFullDatabaseState
	implements RandomQueryGenerator {

	/**
	 * Client entity type of product.
	 */
	public static final String PRODUCT_ENTITY_TYPE = "Product";
	/**
	 * Client entity type of brand.
	 */
	public static final String BRAND_ENTITY_TYPE = "Brand";
	/**
	 * Client entity type of category.
	 */
	public static final String CATEGORY_ENTITY_TYPE = "Category";
	/**
	 * Client entity type of group.
	 */
	public static final String GROUP_ENTITY_TYPE = "Group";
	/**
	 * Pseudo-randomizer for picking random entities to fetch.
	 */
	private final Random random = new Random(SEED);
	/**
	 * Query prepared for the measured invocation.
	 */
	@Getter protected Query query;
	/**
	 * Map contains set of all filterable attributes with statistics about them, that could be used to create random queries.
	 */
	private final GlobalPriceStatistics priceStatistics = new GlobalPriceStatistics();

	@Override
	public void setUp() {
		super.setUp();
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(getCatalogName())) {
			this.productSchema = session.getEntitySchema(PRODUCT_ENTITY_TYPE)
				.orElseThrow(() -> new GenericEvitaInternalError("Schema for entity `" + PRODUCT_ENTITY_TYPE + "` was not found!"));
		}
	}

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Invocation)
	public void prepareCall() {
		final Set<RequireConstraint> requirements = new HashSet<>();
		final Set<EntityContentRequire> contentRequirements = new HashSet<>();
		/* 75% times fetch attributes */
		if (this.random.nextInt(4) != 0) {
			contentRequirements.add(attributeContent());
		}
		/* 75% times fetch associated data */
		if (this.random.nextInt(4) != 0) {
			contentRequirements.add(
				associatedDataContent(
					this.productSchema
						.getAssociatedData()
						.keySet()
						.stream()
						.filter(it -> this.random.nextInt(4) != 0)
						.toArray(String[]::new)
				)
			);
		}
		/* 50% times fetch prices */
		final FilterConstraint priceConstraint;
		if (this.random.nextBoolean()) {
			priceConstraint = createRandomPriceFilterBy(
				this.random, this.priceStatistics, this.productSchema.getIndexedPricePlaces()
			);

			/* 75% only filtered prices */
			if (this.random.nextInt(4) != 0) {
				contentRequirements.add(priceContentRespectingFilter());
			} else {
				contentRequirements.add(priceContentAll());
			}
		} else {
			priceConstraint = null;
		}

		/* 25% times load references */
		if (this.random.nextInt(4) == 0) {
			/* 50% times load all references */
			if (this.random.nextBoolean()) {
				contentRequirements.add(referenceContentAll());
			} else {
				/* 50% select only some of them */
				contentRequirements.add(
					referenceContent(
						Stream.of(BRAND_ENTITY_TYPE, CATEGORY_ENTITY_TYPE, GROUP_ENTITY_TYPE)
							.filter(it -> this.random.nextBoolean())
							.toArray(String[]::new)
					)
				);
			}
		}

		final Locale randomExistingLocale = this.productSchema
			.getLocales()
			.stream()
			.skip(this.random.nextInt(this.productSchema.getLocales().size()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No locales found!"));

		requirements.add(
			page(this.random.nextInt(5) + 1, 20)
		);
		requirements.add(
			entityFetch(contentRequirements.toArray(EntityContentRequire[]::new))
		);

		final List<Integer> productIds = this.generatedEntities.get(PRODUCT_ENTITY_TYPE);
		this.query = Query.query(
			collection(PRODUCT_ENTITY_TYPE),
			filterBy(
				and(
					entityPrimaryKeyInSet(
						Stream.iterate(
							productIds.get(this.random.nextInt(productIds.size())),
							aLong -> productIds.get(this.random.nextInt(productIds.size()))
						)
							.limit(100)
							.toArray(Integer[]::new)
					),
					entityLocaleEquals(randomExistingLocale),
					priceConstraint
				)
			),
			require(
				requirements.toArray(new RequireConstraint[0])
			)
		);
	}

	@Override
	protected void processEntity(@Nonnull SealedEntity entity) {
		entity.getPrices().forEach(it -> this.priceStatistics.updateValue(it, this.random));
	}

}
