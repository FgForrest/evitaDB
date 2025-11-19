/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.Any;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Represents a single query label (equivalent to {@link io.evitadb.api.query.head.Label} where full head part of query
 * cannot be used).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public interface QueryLabelDescriptor {

	PropertyDescriptor NAME = PropertyDescriptor.builder()
		.name("name")
		.description("""
			Specifies label name. Name can be any arbitrary string.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor VALUE = PropertyDescriptor.builder()
		.name("value")
		.description("""
			Specifies label value. Value can be any arbitrary value.
			""")
		.type(nullable(Any.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("QueryLabel")
		.description("""
			Represents a single query label. It allows a single label name with associated value to be specified in the
			query header and propagated to the trace generated for the query. A query can be tagged with multiple labels.
			Labels are also recorded with the query in the traffic record and can be used to look up the query in the traffic
			inspection or traffic replay.
			""")
		.staticProperty(NAME)
		.staticProperty(VALUE)
		.build();
}
