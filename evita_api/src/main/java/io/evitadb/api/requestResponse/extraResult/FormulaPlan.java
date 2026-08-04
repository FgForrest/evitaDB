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

package io.evitadb.api.requestResponse.extraResult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;

/**
 * One node of the formula plan a query phase was carried out with - the structural counterpart of
 * {@link QueryTelemetry}, which measures *time*. Requested by parametrizing the `queryTelemetry` require constraint
 * with `PLAN`, and absent otherwise.
 *
 * **The plan is a DAG, not a tree.** A formula's result is memoized per *instance*, so a subtree reachable by two
 * paths is computed once and every later occurrence of it is free. Rendering it as a plain tree would show the same
 * expensive subtree three times and invite the reader to conclude it cost three times as much. {@link #refTo} is
 * what prevents that: the first occurrence of an instance is described in full, and every later occurrence is a
 * bare node pointing back at it by {@link #id}, with no children of its own.
 *
 * **A node may legitimately be undescribed by numbers.** {@link #actualCost} and {@link #resultCount} are `null`
 * whenever the value is not available for free, which is the normal state for a rejected plan alternative (the
 * planner costs every candidate but executes only the winner) and for a short-circuited branch of the winning one.
 * They are `null` rather than zero because the plan is rendered **without ever computing anything** - see
 * `Formula#getMemoizedResult()` and `Formula#getMemoizedCost()`. A renderer that filled them in would make asking
 * for the plan change what the query does, which is the one thing telemetry must never do.
 *
 * The two are read independently, so **a node can carry a `resultCount` with no `actualCost` beside it**: pricing a
 * formula is itself work the renderer will not do, so that combination reads as "it ran, but nothing has priced it"
 * rather than "it never ran". The reverse combination does not occur.
 *
 * @param id             identity of the formula *instance* this node stands for, unique within the plan and stable
 *                       across its occurrences - it is what makes "computed once, reused twice" visible
 * @param refTo          `null` on the occurrence that describes the instance; equal to {@link #id} on every later
 *                       occurrence, which carries no detail and no children and means "see the node with this id"
 * @param hash           structural hash of the formula, i.e. what the cache keys on - two nodes with the same hash
 *                       are interchangeable computations, whereas two nodes with the same `id` are the same object.
 *                       The two answer different questions and can disagree
 * @param description    human readable description of the formula, `null` on a back-reference node
 * @param estimatedCost  cost the planner estimated for this formula before running anything
 * @param actualCost     cost the formula really incurred, or `null` when it was never computed
 * @param resultCount    number of records the formula produced, or `null` when it was never computed
 * @param children       inner formulas, always empty on a back-reference node
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record FormulaPlan(
	int id,
	@Nullable Integer refTo,
	long hash,
	@Nullable String description,
	long estimatedCost,
	@Nullable Long actualCost,
	@Nullable Integer resultCount,
	@Nonnull List<FormulaPlan> children
) implements Serializable {

	/**
	 * Returns TRUE when this node merely points at an instance described earlier in the plan, and therefore carries
	 * neither detail nor children of its own.
	 *
	 * @return TRUE for a back-reference node
	 */
	public boolean isReference() {
		return this.refTo != null;
	}
}
