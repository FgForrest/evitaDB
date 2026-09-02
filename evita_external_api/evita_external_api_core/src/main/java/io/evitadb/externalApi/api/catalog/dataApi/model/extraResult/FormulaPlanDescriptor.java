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

package io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;

/**
 * Represents {@link io.evitadb.api.requestResponse.extraResult.FormulaPlan}.
 *
 * Note: this descriptor has static structure.
 *
 * Two properties here carry the whole of what makes a plan readable, and both are easy to mistake for noise.
 * {@link #REF_TO} is what stops a shared sub-formula from being counted twice - the plan is a DAG, computed once per
 * *instance*, so without it a reader would see the same expensive subtree three times. {@link #ACTUAL_COST} and
 * {@link #RESULT_COUNT} are nullable because the engine costs every candidate plan but runs only one; `null` there
 * means "never computed", which is the normal state of a rejected alternative and is deliberately different from a
 * measured zero.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface FormulaPlanDescriptor {

	PropertyDescriptor ID = PropertyDescriptor.builder()
		.name("id")
		.description("""
			Identity of the formula instance this node stands for, unique within the plan and stable across its
			occurrences. Two nodes sharing an `id` are the same object, computed once and reused.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor REF_TO = PropertyDescriptor.builder()
		.name("refTo")
		.description("""
			Null on the occurrence that describes the formula; equal to `id` on every later occurrence, which
			carries no detail and no children and means "see the node with this id". The plan is a directed acyclic
			graph rather than a tree - a sub-formula reachable by two paths is computed once - so a back-reference
			is what keeps its cost from being counted more than once.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor HASH = PropertyDescriptor.builder()
		.name("hash")
		.description("""
			Structural hash of the formula, i.e. what the cache keys on. Two nodes with the same hash are
			interchangeable computations, whereas two nodes with the same `id` are the same object - the two answer
			different questions and can legitimately disagree.
			""")
		.type(nonNull(Long.class))
		.build();
	PropertyDescriptor DESCRIPTION = PropertyDescriptor.builder()
		.name("description")
		.description("""
			Human readable description of the formula. Null on a back-reference node, which repeats no detail.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor ESTIMATED_COST = PropertyDescriptor.builder()
		.name("estimatedCost")
		.description("""
			Cost the planner estimated for this formula before running anything. This is the unitless scale
			candidate plans are ranked on - comparable within a query, meaningless in absolute terms.
			""")
		.type(nonNull(Long.class))
		.build();
	PropertyDescriptor ACTUAL_COST = PropertyDescriptor.builder()
		.name("actualCost")
		.description("""
			Cost the formula really incurred once it ran, on the same scale as `estimatedCost`. Null when the
			formula was never computed - which is the normal state for a plan alternative the engine rejected, and
			for a branch of the winning plan it short-circuited past.
			""")
		.type(nullable(Long.class))
		.build();
	PropertyDescriptor RESULT_COUNT = PropertyDescriptor.builder()
		.name("resultCount")
		.description("""
			Number of records this formula produced. Null when it was never computed - which is not the same as
			zero, and a client that treats it as zero will report an unexecuted plan as one that matched nothing.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor CHILDREN = PropertyDescriptor.builder()
		.name("children")
		.description("""
			Inner formulas this one combines. Always empty on a back-reference node.
			""")
		.type(nonNullListRef(() -> FormulaPlanDescriptor.THIS))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("FormulaPlan")
		.description("""
			Single node of the formula plan the query engine built, describing what the engine computed rather than
			how long it took. Returned only when the query asked for it with `queryTelemetry(PLAN)`.
			""")
		.staticProperties(
			List.of(ID, REF_TO, HASH, DESCRIPTION, ESTIMATED_COST, ACTUAL_COST, RESULT_COUNT, CHILDREN)
		)
		.build();
}
