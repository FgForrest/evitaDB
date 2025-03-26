/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of header arguments of field {@link HistogramDescriptor#BUCKETS}
 */
public interface BucketsFieldHeaderDescriptor {

	PropertyDescriptor REQUESTED_COUNT = PropertyDescriptor.builder()
		.name("requestedCount")
		.description("""
			States the number of histogram buckets (columns) that can be safely visualized to the user.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor BEHAVIOR = PropertyDescriptor.builder()
		.name("behavior")
		.description("""
			Defines behavior of a histogram computer regarding the histogram buckets (columns).
			""")
		.type(nullable(HistogramBehavior.class))
		.build();
}
