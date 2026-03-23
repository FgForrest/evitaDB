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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Immutable definition of a bucketed histogram configuration for a reference schema.
 * Each scope where a reference is "bucketed" carries one instance of this record,
 * specifying the histogram index name and the optional value expression used to
 * compute the bucket value for each referenced entity.
 *
 * @param nameOfTheIndex  the name identifying the histogram index, must not be null
 * @param valueExpression the expression computing the histogram bucket value, or null if not specified
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record HistogramIndexDefinition(
	@Nonnull String nameOfTheIndex,
	@Nullable Expression valueExpression
) implements Serializable {

	/**
	 * Compact constructor that validates the name is not null and not blank.
	 */
	public HistogramIndexDefinition {
		Assert.notNull(nameOfTheIndex, "Name of the index must not be null!");
		Assert.isTrue(!nameOfTheIndex.isBlank(), "Name of the index must not be blank!");
	}

}
