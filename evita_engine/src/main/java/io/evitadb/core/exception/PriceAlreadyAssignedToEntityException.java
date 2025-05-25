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

package io.evitadb.core.exception;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.price.PriceSuperIndex;
import lombok.Getter;

import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Exception is thrown when price with same price id is about to be inserted into the {@link PriceSuperIndex}.
 * Price primary key is unique identifier and two entities (either one entity) cannot have multiple prices with
 * identical {@link PriceContract#priceId()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceAlreadyAssignedToEntityException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -2772738660272840174L;
	@Getter private final int priceId;
	@Getter private final int entityPrimaryKey;
	@Getter private final Integer innerRecordId;

	public PriceAlreadyAssignedToEntityException(int priceId, int entityPrimaryKey, @Nullable Integer innerRecordId) {
		super(
			"Price " + priceId + " is already assigned to entity with primary key " + entityPrimaryKey +
				(innerRecordId == null ? "" : " (inner record id " + innerRecordId + ")") +
				". Please remove price first!"
		);
		this.priceId = priceId;
		this.entityPrimaryKey = entityPrimaryKey;
		this.innerRecordId = innerRecordId;
	}
}
