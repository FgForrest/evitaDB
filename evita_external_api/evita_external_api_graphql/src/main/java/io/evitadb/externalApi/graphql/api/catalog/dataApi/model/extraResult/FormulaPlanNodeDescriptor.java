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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult;

import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FormulaPlanDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * GraphQL representation of a single node of a {@link io.evitadb.api.requestResponse.extraResult.FormulaPlan}, which
 * this API publishes **flattened** for the same reason it flattens the telemetry tree itself - see
 * {@link QueryTelemetryNodeDescriptor}.
 *
 * There is one difference that matters. The telemetry tree is a tree, so pre-order plus {@link #LEVEL} reconstructs
 * it completely. The plan is a **DAG**: a sub-formula reachable by two paths is one object, computed once. Level
 * alone cannot express that, which is why {@link FormulaPlanDescriptor#ID} and {@link FormulaPlanDescriptor#REF_TO}
 * are load-bearing here rather than decorative - a node whose `refTo` is set is a pointer to a node described
 * earlier in the same list, and contributes no cost of its own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface FormulaPlanNodeDescriptor {

	/**
	 * Depth of this node within the plan, the root being `1`. Together with the pre-order emission it carries the
	 * nesting that flattening removed, exactly as {@link QueryTelemetryNodeDescriptor#LEVEL} does for the phases.
	 */
	PropertyDescriptor LEVEL = PropertyDescriptor.builder()
		.name("level")
		.description("""
			Depth of this formula in the plan, where the root formula is always on level 1. The nodes are returned
			in pre-order, so the parent of any node is the closest preceding node with a lower level.
			""")
		.type(nonNull(Integer.class))
		.build();
	/**
	 * Number of inner formulas, the counterpart of {@link QueryTelemetryNodeDescriptor#STEPS_COUNT}, and always
	 * zero on a back-reference node.
	 */
	PropertyDescriptor CHILDREN_COUNT = PropertyDescriptor.builder()
		.name("childrenCount")
		.description("""
			Number of inner formulas this one directly combines. Zero for a leaf, and always zero for a
			back-reference node, whose children are described at its first occurrence instead.
			""")
		.type(nonNull(Integer.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("FormulaPlan")
		.description("""
			Single node of the formula plan the query engine built. The whole plan is returned as a pre-order list
			of these; use `level` to reconstruct the nesting and `refTo` to resolve shared sub-formulas.
			""")
		.staticProperties(
			List.of(
				LEVEL,
				FormulaPlanDescriptor.ID,
				FormulaPlanDescriptor.REF_TO,
				FormulaPlanDescriptor.HASH,
				FormulaPlanDescriptor.DESCRIPTION,
				FormulaPlanDescriptor.ESTIMATED_COST,
				FormulaPlanDescriptor.ACTUAL_COST,
				FormulaPlanDescriptor.RESULT_COUNT,
				CHILDREN_COUNT
			)
		)
		.build();
}
