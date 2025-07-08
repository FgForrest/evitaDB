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

package io.evitadb.api.query.require;

import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `accompanyingPrice` constraint defines the ordered price list names that should be used for calculation of
 * so-called accompanying price, which is a price not used for selling, but rather for displaying additional price
 * information (such as "previous price", "recommended price", etc.).
 *
 * <pre>
 * defaultAccompanyingPriceLists(
 *     "reference",
 *     "basic"
 * )
 * </pre>
 *
 * This constraint doesn't trigger the accompanying price calculation itself, but rather defines the default price lists
 * that should be used in place where {@link AccompanyingPriceContent} requirement is used.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/price#accompanying-price">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "defaultAccompanyingPriceLists",
	shortDescription = "The requirement defines the ordered price list names that should be used for calculation of" +
		" so-called accompanying price, which is a price not used for selling, but rather for displaying additional" +
		" price information (such as \"previous price\", \"recommended price\", etc.)..",
	userDocsLink = "/documentation/query/requirements/price#accompanying-price"
)
public class DefaultAccompanyingPriceLists extends AbstractRequireConstraintLeaf implements PriceConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -5786325458930138452L;

	private DefaultAccompanyingPriceLists(@Nonnull Serializable... priceLists) {
		super(priceLists);
	}

	@Creator
	public DefaultAccompanyingPriceLists(@Nonnull String... priceLists) {
		super(priceLists);
	}

	/**
	 * Returns primary keys of all price lists that should be used for default accompanying price calculation.
	 */
	@Nonnull
	public String[] getPriceLists() {
		return Arrays.stream(getArguments()).map(String.class::cast).toArray(String[]::new);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull();
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new DefaultAccompanyingPriceLists(newArguments);
	}

}