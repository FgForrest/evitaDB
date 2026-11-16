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
import io.evitadb.dataType.BigDecimalNumberRange;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * V2 evolution: replaces the default value `[10,20]` of the `range` attribute by `[1.0,2.0]`, a range whose bounds
 * encode to the same numbers and which `equals` therefore finds equal, although a reader tells the two apart.
 *
 * Mirrors the V1 entity name so the analyzer re-evaluates the same existing schema.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface GetterBasedEntityDefaultValueEvolutionV2RescaledRange
	extends GetterBasedEntityDefaultValueEvolutionV1 {

	@Override
	@Attribute
	@Nonnull
	default BigDecimalNumberRange getRange() {
		return BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
	}

}
