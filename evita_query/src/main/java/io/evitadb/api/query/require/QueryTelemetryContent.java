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

package io.evitadb.api.query.require;

import io.evitadb.dataType.SupportedEnum;

/**
 * How much the engine should put into the query profile a {@link QueryTelemetry} require constraint asks for.
 *
 * The levels are ordered by what they cost to produce, and each is a deliberate choice rather than a set to
 * combine: a profile carries the timings, or the timings *and* the plan. {@link #TIMINGS} is the default and is
 * what a bare `queryTelemetry()` resolves to.
 *
 * This enum is marked as {@link SupportedEnum}, making it available for use in generated API schemas.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SupportedEnum
public enum QueryTelemetryContent {

	/**
	 * Phase timings and the root's metrics, and nothing beyond them. **This is the default** - a bare
	 * `queryTelemetry()` means `queryTelemetry(TIMINGS)`, and the constant is treated as an implicit argument, so
	 * it is omitted again from the constraint's EvitaQL string form.
	 *
	 * It is the level to profile with by default, for the reason spelled out under {@link #PLAN}: it is the only
	 * one whose numbers are not perturbed by the work of producing the profile itself.
	 */
	TIMINGS,

	/**
	 * Includes the **formula plan** - the structure of the boolean formula the planner built - alongside the
	 * timings, as {@link io.evitadb.api.requestResponse.extraResult.FormulaPlan} nodes attached to the phases that
	 * produced them. This is what answers "*why* was this slow" rather than "*where* was it slow": it exposes the
	 * shape of the computation, the cost the planner estimated for each part of it, and the cost that part really
	 * incurred.
	 *
	 * Rendering it is O(formula nodes) of structure building per described phase, which is why it is not free and
	 * not implied. **Asking for the plan can change the profile's own numbers** - the rendering happens inside the
	 * measured query - so a run made with this flag is not directly comparable with one made without it. That is
	 * an accepted trade: re-running the query to get the deeper view is the expected workflow, and it is strictly
	 * better than charging every telemetry consumer for a plan they did not ask for.
	 *
	 * What it never does is compute anything. Rejected plan alternatives are described but not executed, so the
	 * parts of the plan the engine decided not to run report their result as "not computed" rather than being run
	 * to fill the field in.
	 */
	PLAN

}
