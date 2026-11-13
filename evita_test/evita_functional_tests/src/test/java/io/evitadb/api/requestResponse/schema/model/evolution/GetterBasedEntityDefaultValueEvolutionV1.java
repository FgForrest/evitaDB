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

package io.evitadb.api.requestResponse.schema.model.evolution;

import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.dataType.BigDecimalNumberRange;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * Base V1 interface for default-value schema-evolution tests.
 *
 * Defines a minimal schema with a single `range` attribute whose default value is the range `[10,20]`, so that
 * a follow-up V2 interface can replace it by a range whose bounds encode to the same numbers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityDefaultValueEvolutionV1.ENTITY_NAME)
public interface GetterBasedEntityDefaultValueEvolutionV1 {

	String ENTITY_NAME = "GetterBasedEntityDefaultValueEvolution";

	@PrimaryKey
	int getId();

	@Attribute
	@Nonnull
	default BigDecimalNumberRange getRange() {
		return BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"));
	}

}
