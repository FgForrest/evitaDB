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

import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;

import javax.annotation.Nonnull;

/**
 * Trigger for conditional histogram indexing. Derived from the reference schema's `bucketedPartially` condition
 * expression and histogram index definitions. Each trigger encapsulates:
 *
 * - the **condition** expression (inherited from {@link ExpressionIndexTrigger}) — determines whether a reference
 *   participates in histogram indexing
 * - the **histogram index name** — identifies which histogram FilterIndex to write to
 * - the **value resolution metadata** — pre-built metadata describing how to locate the source attribute value
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface HistogramExpressionTrigger extends ExpressionIndexTrigger {

	/**
	 * Returns the name of the histogram index from the {@link HistogramIndexDefinition}. This name is used
	 * as the key in the `histogramIndexes` map on `ReducedGroupEntityIndex` and `ReferencedTypeEntityIndex`.
	 *
	 * @return the histogram index name, never null
	 */
	@Nonnull
	String getHistogramIndexName();

	/**
	 * Returns the pre-built resolution metadata for locating the source FilterIndex and attribute value.
	 * Built at schema load time by `HistogramValueDescriptorFactory`.
	 *
	 * @return the value resolution metadata, never null
	 */
	@Nonnull
	HistogramValueDescriptor getValueDescriptor();

}
