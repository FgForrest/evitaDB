/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult;

import io.evitadb.api.query.require.HierarchyRequireConstraint;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of header arguments common for all {@link HierarchyRequireConstraint}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface HierarchyRequireHeaderDescriptor {

	PropertyDescriptor STOP_AT = PropertyDescriptor.builder()
		.name("stopAt")
		// TOBEDONE JNO: stopAt constraint docs
		.description("""
			Defines node at which the hierarchy will stop expanding.
			""")
		// type is expected to be a `stopAt` constraint
		.build();
	PropertyDescriptor STATISTICS_BASE = PropertyDescriptor.builder()
		.name("statisticsBase")
		.description("""
			Specifies whether the hierarchy statistics cardinality will be based on a complete query filter by
			constraint or only the part without user defined filter.
			""")
		.type(nullable(StatisticsBase.class))
		.build();
}
