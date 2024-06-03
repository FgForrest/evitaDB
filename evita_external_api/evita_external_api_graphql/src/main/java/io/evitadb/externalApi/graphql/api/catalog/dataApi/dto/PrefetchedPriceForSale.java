/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.dto;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;

/**
 * DTO representing prefetched price for sale with other precomputed prices. Other prices can be computed using the
 * {@link #getParentEntity()}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class PrefetchedPriceForSale implements PriceContract {

	@Delegate @Nonnull private final PriceContract priceForSale;

	@Nonnull @Getter private final EntityDecorator parentEntity;
	@Nonnull @Getter private final Map<String, Optional<PriceContract>> accompanyingPrices;
}
