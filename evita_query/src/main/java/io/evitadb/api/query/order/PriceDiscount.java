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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.order;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.require.PriceType;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `priceDiscount' constraint allows you to sort items by the difference between their selling price and
 * the discounted price, which is calculated in the same way as the selling price but using a different set of price
 * lists. It requires the order direction, the array of price lists that define the discount price, and the price
 * constraints in the `filterBy` section of the query. The price variant (with or without tax) is determined by
 * the {@link PriceType} requirement of the query (price with tax is used by default).
 *
 * If the discount result is negative (i.e. the discounted price is greater than the selling price), it's considered to
 * be zero. Sorting in ascending order means that the products with no discount are returned first, and the products
 * with the largest discount are returned last. Descending order returns the product with the largest discount first.
 *
 * Please read the <a href="https://evitadb.io/documentation/deep-dive/price-for-sale-calculation">price for sale
 * calculation algorithm documentation</a> to understand how the price for sale is calculated.
 *
 * If no order type is defined, DESC is used by default because the most common use case is to return the items with
 * the largest discount first.
 *
 * Example:
 *
 * <pre>
 * priceDiscount("discount", "basic")
 * priceDiscount(DESC, "discount", "basic")
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/price#price-discount">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 */
@ConstraintDefinition(
	name = "discount",
	shortDescription = "The constraint condition sorts the returned entities according to the difference between the sale price and the discounted price. The discounted price is based on the prioritized list of price lists in this constraint.",
	userDocsLink = "/documentation/query/ordering/price#price-discount",
	supportedIn = { ConstraintDomain.ENTITY }
)
public class PriceDiscount extends AbstractOrderConstraintLeaf implements PriceConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = 994029933423451186L;

	private PriceDiscount(Serializable... arguments) {
		super(arguments);
	}

	public PriceDiscount(@Nonnull String... priceLists) {
		this(OrderDirection.DESC, priceLists);
	}

	@Creator
	public PriceDiscount(@Nonnull OrderDirection orderDirection, @Nonnull String... inPriceLists) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[] { orderDirection },
				inPriceLists
			)
		);
		Assert.isTrue(
			inPriceLists != null && inPriceLists.length > 0,
			"Price lists must be defined in `priceDiscount` order constraint!"
		);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 1;
	}

	/**
	 * Returns the order direction of the constraint.
	 * @return order direction
	 */
	@Nonnull
	public OrderDirection getOrderDirection() {
		return (OrderDirection) getArguments()[0];
	}

	/**
	 * Returns the array of price lists that define the discount price.
	 * @return array of price lists
	 */
	@Nonnull
	public String[] getPriceLists() {
		return Arrays.stream(getArguments())
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceDiscount(newArguments);
	}

}
