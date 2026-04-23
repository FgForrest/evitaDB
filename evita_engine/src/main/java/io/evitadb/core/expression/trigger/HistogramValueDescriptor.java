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

package io.evitadb.core.expression.trigger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Immutable metadata describing how to locate and resolve the source attribute value for a bucketed histogram
 * index computation. Built at schema load time by {@link HistogramValueDescriptorFactory} and carried by
 * {@link HistogramExpressionTrigger} instances.
 *
 * Values are stored in the histogram FilterIndex using the attribute's **original numeric type** (`Byte`, `Short`,
 * `Integer`, `Long`, or `BigDecimal`) to minimize memory overhead. Conversion to `BigDecimal` is deferred to
 * query-time histogram computation (`HistogramDataCruncher`).
 *
 * @param source              classifies the origin: reference attribute or referenced entity attribute
 * @param sourceEntityType    entity type owning the source FilterIndex; null for reference attributes
 * @param sourceAttributeName attribute name in the source FilterIndex
 * @param plainType           the source attribute's plain numeric type (e.g., `Short.class`, `Integer.class`)
 * @param arrayType           true if the source attribute is array-typed (e.g., `Integer[]`)
 * @param localized           true if the source attribute is locale-sensitive (accessed via `localizedAttributes`)
 * @param defaultValue        default value from the `??` operator converted to `plainType`, or null if null
 *                            values should be skipped
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record HistogramValueDescriptor(
	@Nonnull HistogramValueSource source,
	@Nullable String sourceEntityType,
	@Nonnull String sourceAttributeName,
	@Nonnull Class<? extends Serializable> plainType,
	boolean arrayType,
	boolean localized,
	@Nullable Number defaultValue
) {

}
