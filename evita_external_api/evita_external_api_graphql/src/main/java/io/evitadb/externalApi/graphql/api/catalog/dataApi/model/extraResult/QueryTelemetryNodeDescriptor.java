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

import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.QueryTelemetryDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;

/**
 * GraphQL representation of a single node of the {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry}
 * tree, which GraphQL publishes **flattened** rather than nested.
 *
 * The engine object is a tree of unbounded depth. REST can return it as-is, because a JSON response carries whatever
 * nesting the server produced; GraphQL cannot, because the client - not the server - decides how deep to select, and
 * a recursive `steps` field would force every client to write a selection set as deep as the deepest query it ever
 * expects to profile, silently truncating anything deeper. The tree is therefore emitted as a pre-order list of
 * nodes carrying {@link #LEVEL}, which is the same trade
 * {@link io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor} already makes for
 * the hierarchy extra result, and the shape a flame chart consumes directly.
 *
 * Every property except {@link #LEVEL} and {@link #STEPS_COUNT} is reused verbatim from
 * {@link QueryTelemetryDescriptor}, so the two APIs cannot drift apart on names, types or descriptions - only on
 * shape. What is deliberately *not* reused is {@link QueryTelemetryDescriptor#STEPS}: it is the nesting this object
 * replaces.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface QueryTelemetryNodeDescriptor {

	/**
	 * Depth of this node, which is what carries the structure that flattening removed. Together with the pre-order
	 * emission it reconstructs the tree unambiguously: a node's parent is the closest preceding node with a lower
	 * level. Root is `1` - matching {@link LevelInfoDescriptor#LEVEL} rather than being zero-based.
	 */
	PropertyDescriptor LEVEL = PropertyDescriptor.builder()
		.name("level")
		.description("""
			Depth of this step in the telemetry tree, where the root step is always on level 1. The steps are
			returned in pre-order, so the parent of any step is the closest preceding step with a lower level.
			""")
		.type(nonNull(Integer.class))
		.build();
	/**
	 * Number of direct children this step decomposed into, the counterpart of
	 * {@link LevelInfoDescriptor#CHILDREN_COUNT}. It is redundant with the levels of the following nodes, and exists
	 * so that a client can tell a leaf from a parent without looking ahead in the list.
	 */
	PropertyDescriptor STEPS_COUNT = PropertyDescriptor.builder()
		.name("stepsCount")
		.description("""
			Number of direct sub-steps this step decomposed into. Zero for a leaf phase - and legitimately zero on
			the root as well, for a query whose planning short-circuited.
			""")
		.type(nonNull(Integer.class))
		.build();

	/**
	 * The formula plan for this step. It is a list rather than a single object because the plan is flattened too,
	 * for the same reason this object is - it is a structure of unbounded depth, and in GraphQL the client picks
	 * the selection depth. Null, not empty, when no plan was requested.
	 */
	PropertyDescriptor PLAN = PropertyDescriptor.builder()
		.name("plan")
		.description("""
			Structure of the formula the query engine built for this step, returned as a pre-order list of nodes -
			use `level` to reconstruct the nesting and `refTo` to resolve shared sub-formulas. Present only when the
			query asked for it with `queryTelemetry(PLAN)`, and then only on the steps that own a formula.
			""")
		.type(nullableListRef(() -> FormulaPlanNodeDescriptor.THIS))
		.build();

	/**
	 * The flattened `QueryTelemetry` node. It keeps the name the object had while it was an untyped JSON scalar, so
	 * that the field selecting it reads the same as before; what changed is that it is now introspectable and that
	 * the field returns a list of these instead of one nested blob.
	 */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("QueryTelemetry")
		.description("""
			Single step of the query processing profile, carrying how long the step took and what the engine
			measured while performing it. The whole profile is returned as a pre-order list of these steps; use
			`level` to reconstruct the tree.
			""")
		.staticProperties(
			List.of(
				LEVEL,
				QueryTelemetryDescriptor.OPERATION,
				QueryTelemetryDescriptor.START,
				QueryTelemetryDescriptor.ARGUMENTS,
				QueryTelemetryDescriptor.SPENT_TIME,
				QueryTelemetryDescriptor.FORMATTED_SPENT_TIME,
				QueryTelemetryDescriptor.SELF_TIME,
				QueryTelemetryDescriptor.FORMATTED_SELF_TIME,
				STEPS_COUNT,
				QueryTelemetryDescriptor.METRICS,
				PLAN,
				QueryTelemetryDescriptor.STARTED_AT
			)
		)
		.build();
}
