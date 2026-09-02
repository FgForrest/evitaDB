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

package io.evitadb.externalApi.api.catalog.dataApi.dto;

import io.evitadb.api.requestResponse.extraResult.FormulaPlan;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * External API DTO for {@link FormulaPlan}, published as-is: unlike the telemetry timings, nothing here is derived
 * or reshaped at this boundary, because the plan is structure the engine measured rather than presentation.
 *
 * The only thing this record adds over the engine object is that it is a serialization target - which is also why
 * it repeats the engine type's contract in its own words rather than deferring to it: these names and their
 * nullability are published schema.
 *
 * @param id            identity of the formula instance this node stands for, stable across its occurrences
 * @param refTo         `null` on the occurrence that describes the instance, equal to `id` on every later one
 * @param hash          structural hash of the formula, i.e. what the cache keys on
 * @param description   human readable description of the formula, `null` on a back-reference node
 * @param estimatedCost cost the planner estimated before running anything
 * @param actualCost    cost the formula really incurred, `null` when it was never computed
 * @param resultCount   number of records the formula produced, `null` when it was never computed
 * @param children      inner formulas, always empty on a back-reference node
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record FormulaPlanDto(
	int id,
	@Nullable Integer refTo,
	long hash,
	@Nullable String description,
	long estimatedCost,
	@Nullable Long actualCost,
	@Nullable Integer resultCount,
	@Nonnull List<FormulaPlanDto> children
) {

	/**
	 * Converts the passed plan node, and everything below it, to its DTO form.
	 *
	 * @param plan node to convert, or `null` when the step carries no plan - which is every step of every query
	 *             that did not ask for one
	 * @return the converted node, or `null` when nothing was passed
	 */
	@Nullable
	public static FormulaPlanDto from(@Nullable FormulaPlan plan) {
		if (plan == null) {
			return null;
		}
		final List<FormulaPlanDto> children = new ArrayList<>(plan.children().size());
		for (final FormulaPlan child : plan.children()) {
			children.add(from(child));
		}
		return new FormulaPlanDto(
			plan.id(),
			plan.refTo(),
			plan.hash(),
			plan.description(),
			plan.estimatedCost(),
			plan.actualCost(),
			plan.resultCount(),
			children
		);
	}
}
