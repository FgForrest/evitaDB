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

package io.evitadb.index.virtual;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * A generated catalog shaped like the parameter values of a production e-commerce catalog, for the read parity tests of
 * the block-by-block orderings and chains: the parameter values reference their products, the
 * products reflect the reference, and nearly every partition of the reflected side is single-owner - a parameter value
 * is held by one product - while every product holds hundreds of rows.
 *
 * - `parameterValue` is hierarchical: roots `1` - {@link #GROUP_COUNT} group the values, every value sits under one of
 *   them. Its reference `products` carries two chains - {@link ReferencedEntityPredecessor} `order` ordering the products
 *   of a value, {@link Predecessor} `position` ordering the values of a product - and a sortable `code`;
 * - `product` reflects the reference as `parameterValues`, inheriting the attributes, so the same rows are chained on
 *   both sides;
 * - every product `1` - {@link #PRODUCT_COUNT} holds {@link #ROWS_PER_PRODUCT} values of its own (single-owner
 *   partitions on the product side) and the {@link #SHARED_VALUE_COUNT} shared values every product holds, chained in
 *   descending product order (shared partitions with a real chain);
 * - product {@link #LONELY_OWNER} holds value {@link #LONELY_PARAMETER_VALUE} alone, and nothing else references the
 *   product - the partition is single-owner on both sides;
 * - value {@link #MIXED_PARAMETER_VALUE} holds product {@link #SECOND_LONELY_OWNER}, which nothing else references,
 *   and product `1` - a single-owner and a shared partition of the original side in one entity.
 *
 * Every entity stays live: archiving a product whose reflected rows carry the chains fails in the engine with
 * "Chain index for attribute `position` not found!" (a pre-existing defect), so the scope parity
 * of the block routes is covered by {@link RelatedProductsShapedFixture}.
 *
 * Primary keys of products and parameter values never coincide, so an owner primary key mistaken for a target one
 * names nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ParameterValuesShapedFixture {
	static final String ENTITY_PRODUCT = "product";
	static final String ENTITY_PARAMETER_VALUE = "parameterValue";
	static final Set<String> ENTITY_TYPES = Set.of(ENTITY_PRODUCT, ENTITY_PARAMETER_VALUE);
	static final String REFERENCE_PRODUCTS = "products";
	static final String REFERENCE_PARAMETER_VALUES = "parameterValues";
	static final String ATTRIBUTE_ORDER = "order";
	static final String ATTRIBUTE_CODE = "code";
	static final String ATTRIBUTE_POSITION = "position";
	/**
	 * How many products the catalog holds, the lonely one aside.
	 */
	static final int PRODUCT_COUNT = 30;
	/**
	 * How many values of its own every product holds.
	 */
	static final int ROWS_PER_PRODUCT = 120;
	/**
	 * How many roots group the parameter values.
	 */
	static final int GROUP_COUNT = 5;
	/**
	 * The first shared parameter value.
	 */
	static final int FIRST_SHARED_VALUE = 901;
	/**
	 * How many values every product holds.
	 */
	static final int SHARED_VALUE_COUNT = 3;
	/**
	 * The first value of a product's own.
	 */
	static final int FIRST_OWN_VALUE = 1_001;
	/**
	 * The value held by {@link #LONELY_OWNER} alone.
	 */
	static final int LONELY_PARAMETER_VALUE = 9_001;
	/**
	 * The only owner of {@link #LONELY_PARAMETER_VALUE}, holding nothing else.
	 */
	static final int LONELY_OWNER = PRODUCT_COUNT + 1;
	/**
	 * The value holding {@link #SECOND_LONELY_OWNER} and product `1` - the only value referencing the former, so
	 * the partition of the original side keyed by it is single-owner, while the one keyed by product `1` is shared.
	 */
	static final int MIXED_PARAMETER_VALUE = 9_002;
	/**
	 * The product referenced by {@link #MIXED_PARAMETER_VALUE} alone.
	 */
	static final int SECOND_LONELY_OWNER = PRODUCT_COUNT + 2;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};

	private ParameterValuesShapedFixture() {
	}

	/**
	 * Defines the schema and writes the entities - meant to run in the warm-up session of a fresh catalog whose
	 * collections are maintained from the first write.
	 *
	 * @param session the session to write in
	 */
	static void setUp(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withoutGeneratedPrimaryKey()
			.withHierarchyIndexedInScope(BOTH_SCOPES)
			.withReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(BOTH_SCOPES)
					.withAttribute(
						ATTRIBUTE_ORDER, ReferencedEntityPredecessor.class,
						thatIs -> thatIs.sortableInScope(BOTH_SCOPES)
					)
					.withAttribute(
						ATTRIBUTE_POSITION, Predecessor.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES)
					)
					.withAttribute(
						ATTRIBUTE_CODE, String.class,
						thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES)
					)
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReflectedReferenceToEntity(
				REFERENCE_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, REFERENCE_PRODUCTS,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited()
					.indexedForFilteringInScope(BOTH_SCOPES)
			)
			.updateVia(session);
		for (int product = 1; product <= SECOND_LONELY_OWNER; product++) {
			session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, product));
		}
		for (int group = 1; group <= GROUP_COUNT; group++) {
			session.upsertEntity(session.createNewEntity(ENTITY_PARAMETER_VALUE, group));
		}
		// the shared values chain every product, in descending product order
		for (int value = FIRST_SHARED_VALUE; value < FIRST_SHARED_VALUE + SHARED_VALUE_COUNT; value++) {
			final EntityBuilder builder = newValue(session, value);
			final int theValue = value;
			for (int product = PRODUCT_COUNT; product >= 1; product--) {
				final int predecessor = product + 1;
				builder.setReference(
					REFERENCE_PRODUCTS, product,
					whichIs -> whichIs
						.setAttribute(
							ATTRIBUTE_ORDER,
							predecessor > PRODUCT_COUNT ?
								ReferencedEntityPredecessor.HEAD : new ReferencedEntityPredecessor(predecessor)
						)
						.setAttribute(ATTRIBUTE_CODE, "s" + (predecessor % 4))
						.setAttribute(ATTRIBUTE_POSITION, sharedPosition(theValue))
				);
			}
			session.upsertEntity(builder);
		}
		for (int product = 1; product <= PRODUCT_COUNT; product++) {
			for (int row = 0; row < ROWS_PER_PRODUCT; row++) {
				final int value = FIRST_OWN_VALUE + (product - 1) * ROWS_PER_PRODUCT + row;
				final int theRow = row;
				final int theValue = value;
				session.upsertEntity(
					newValue(session, value).setReference(
						REFERENCE_PRODUCTS, product,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_ORDER, ReferencedEntityPredecessor.HEAD)
							.setAttribute(ATTRIBUTE_CODE, "c" + (theRow % 7))
							.setAttribute(
								ATTRIBUTE_POSITION,
								theRow == 0 ?
									new Predecessor(FIRST_SHARED_VALUE + SHARED_VALUE_COUNT - 1) :
									new Predecessor(theValue - 1)
							)
					)
				);
			}
		}
		session.upsertEntity(
			newValue(session, LONELY_PARAMETER_VALUE).setReference(
				REFERENCE_PRODUCTS, LONELY_OWNER,
				whichIs -> whichIs
					.setAttribute(ATTRIBUTE_ORDER, ReferencedEntityPredecessor.HEAD)
					.setAttribute(ATTRIBUTE_CODE, "z")
					.setAttribute(ATTRIBUTE_POSITION, Predecessor.HEAD)
			)
		);
		session.upsertEntity(
			newValue(session, MIXED_PARAMETER_VALUE)
				.setReference(
					REFERENCE_PRODUCTS, SECOND_LONELY_OWNER,
					whichIs -> whichIs
						.setAttribute(ATTRIBUTE_ORDER, ReferencedEntityPredecessor.HEAD)
						.setAttribute(ATTRIBUTE_CODE, "y")
						.setAttribute(ATTRIBUTE_POSITION, Predecessor.HEAD)
				)
				.setReference(
					REFERENCE_PRODUCTS, 1,
					whichIs -> whichIs
						.setAttribute(ATTRIBUTE_ORDER, ReferencedEntityPredecessor.HEAD)
						.setAttribute(ATTRIBUTE_CODE, "x")
						.setAttribute(ATTRIBUTE_POSITION, Predecessor.HEAD)
				)
		);
	}

	/**
	 * Returns the position of a shared value in the chain of the parameter values of every product: the shared values
	 * head the chain in ascending order, the values of the product's own follow them.
	 *
	 * @param value the shared value
	 * @return its predecessor
	 */
	@Nonnull
	private static Predecessor sharedPosition(int value) {
		return value == FIRST_SHARED_VALUE ? Predecessor.HEAD : new Predecessor(value - 1);
	}

	/**
	 * Creates a parameter value under its group.
	 *
	 * @param session the session to create the entity in
	 * @param value   the primary key of the value
	 * @return the entity builder
	 */
	@Nonnull
	private static EntityBuilder newValue(@Nonnull EvitaSessionContract session, int value) {
		return session.createNewEntity(ENTITY_PARAMETER_VALUE, value).setParent(1 + value % GROUP_COUNT);
	}

}
