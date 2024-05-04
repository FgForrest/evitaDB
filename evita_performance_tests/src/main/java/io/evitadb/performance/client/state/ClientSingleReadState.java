/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
 * Base state class for singleEntityRead tests on client data set.
 * See benchmark description on the method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ClientSingleReadState extends ClientDataFullDatabaseState
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
		final Set<EntityContentRequire> requirements = new HashSet<>();
		/* 75% times fetch attributes */
		if (random.nextInt(4) != 0) {
			requirements.add(attributeContent());
		}
		/* 75% times fetch associated data */
		if (random.nextInt(4) != 0) {
			requirements.add(
				associatedDataContent(
					this.productSchema
						.getAssociatedData()
						.keySet()
						.stream()
						.filter(it -> random.nextInt(4) != 0)
						.toArray(String[]::new)
				)
			);
		}
		/* 50% times fetch prices */
		final FilterConstraint priceConstraint;
		if (random.nextBoolean()) {
			priceConstraint = createRandomPriceFilterBy(
				random, priceStatistics, productSchema.getIndexedPricePlaces()
			);

			/* 75% only filtered prices */
			if (random.nextInt(4) != 0) {
				requirements.add(priceContentRespectingFilter());
			} else {
				requirements.add(priceContentAll());
			}
		} else {
			priceConstraint = null;
		}

		/* 25% times load references */
		if (random.nextInt(4) == 0) {
			/* 50% times load all references */
			if (random.nextBoolean()) {
				requirements.add(referenceContentAll());
			} else {
				/* 50% select only some of them */
				requirements.add(
					referenceContent(
						Stream.of(BRAND_ENTITY_TYPE, CATEGORY_ENTITY_TYPE, GROUP_ENTITY_TYPE)
							.filter(it -> random.nextBoolean())
							.toArray(String[]::new)
					)
				);
			}
		}

		final Locale randomExistingLocale = this.productSchema
			.getLocales()
			.stream()
			.skip(random.nextInt(this.productSchema.getLocales().size()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No locales found!"));

		final List<Integer> productIds = generatedEntities.get(PRODUCT_ENTITY_TYPE);
		this.query = Query.query(
			collection(PRODUCT_ENTITY_TYPE),
			filterBy(
				and(
					entityPrimaryKeyInSet(productIds.get(random.nextInt(productIds.size()))),
					entityLocaleEquals(randomExistingLocale),
					priceConstraint
				)
			),
			require(entityFetch(requirements.toArray(new EntityContentRequire[0])))
		);
	}

	@Override
	protected void processEntity(@Nonnull SealedEntity entity) {
		entity.getPrices().forEach(it -> this.priceStatistics.updateValue(it, random));
	}

}
