/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `priceInPriceLists` is query accepts one or more [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
 * arguments that represents primary keys of price lists.
 *
 * Function returns true if entity has at least one price in any of specified price lists. This function is also affected by
 * [priceInCurrency](#price-in-currency) function that limits the examined prices as well. The order of the price lists
 * passed in the argument is crucial, because it defines the priority of the price lists. Let's have a product with
 * following prices:
 *
 * | priceList       | currency | priceWithTax |
 * |-----------------|----------|--------------|
 * | basic           | EUR      | 999.99       |
 * | registered_user | EUR      | 979.00       |
 * | b2c_discount    | EUR      | 929.00       |
 * | b2b_discount    | EUR      | 869.00       |
 *
 * If query contains:
 *
 * `and(
 *     priceInCurrency('EUR'),
 *     priceInPriceLists('basic', 'b2b_discount'),
 *     priceBetween(800.0, 900.0)
 * )`
 *
 * The product will not be found - because query engine will use first defined price for the price lists in defined order.
 * It's in our case the price `999.99`, which is not in the defined price interval 800 € - 900 €. If the price lists in
 * arguments gets switched to `priceInPriceLists('b2b_discount', 'basic')`, the product will be returned, because the first
 * price is now from `b2b_discount` price list - 869 € and this price is within defined interval.
 *
 * This query affect also the prices accessible in returned entities. By default, (unless [prices](#prices) requirement
 * has ALL mode used), returned entities will contain only prices from specified price lists. In other words if entity has
 * two prices - one from price list `1` and second from price list `2` and `priceInPriceLists(1)` is used in the query
 * returned entity would have only first price fetched along with it.
 *
 * The non-sellable prices are not taken into an account in the search - for example if the product has only non-sellable
 * price it will never be returned when {@link PriceInPriceLists} query or any other price query is used in the
 * query. Non-sellable prices behaves like they don't exist. These non-sellable prices still remain accessible for reading
 * on fetched entity in case the product is found by sellable price satisfying the filter. If you have specific price list
 * reserved for non-sellable prices you may still use it in {@link PriceInPriceLists} query. It won't affect the set
 * of returned entities, but it will ensure you can access those non-sellable prices on entities even when
 * {@link PriceContentMode#RESPECTING_FILTER} is used in {@link PriceContent} requirement is used.
 *
 * Only single `priceInPriceLists` query can be used in the query. Constraint must be defined when other price related
 * constraints are used in the query.
 *
 * Example:
 *
 * ```
 * priceInPriceLists(1, 5, 6)
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inPriceLists",
	shortDescription = "The constraint filters out all entities that lack selling price in specified price lists. " +
		"Order of price lists also defines priority for selecting the entity selling price - the price from first price " +
		"list in the list will be used as a selling price for the entire entity.",
	supportedIn = ConstraintDomain.ENTITY
)
public class PriceInPriceLists extends AbstractFilterConstraintLeaf implements PriceConstraint<FilterConstraint>, IndexUsingConstraint {
	@Serial private static final long serialVersionUID = 7018968762648494243L;

	private PriceInPriceLists(@Nonnull Serializable... priceListName) {
		super(priceListName);
	}

	@Creator
	public PriceInPriceLists(@Nonnull @Value String... priceListName) {
		super(priceListName);
	}

	/**
	 * Returns primary keys of all price lists that should be considered in query.
	 */
	@Nonnull
	public String[] getPriceLists() {
		return Arrays.stream(getArguments()).map(String.class::cast).toArray(String[]::new);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceInPriceLists(newArguments);
	}
}
