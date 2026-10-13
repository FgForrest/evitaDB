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

package io.evitadb.core.query.fetch;

import javax.annotation.Nonnull;

/**
 * The way {@link ReferencedEntityFetcher} decides which references a filtered `referenceContent` fetches - a switch
 * that lets both ways run side by side while the index route is being replaced, and that is deleted together with
 * the index route.
 *
 * The route is read once per JVM from the system property {@link #SYSTEM_PROPERTY} and defaults to {@link #BODY}.
 * Test classes run concurrently, so a suite is run under the other route in a JVM of its own; only an isolated test
 * may switch the route in-process ({@link #switchTo(ReferenceContentRoute)}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
enum ReferenceContentRoute {
	/**
	 * The filter is translated against the reduced indexes of the reference and the verdicts are applied per
	 * referenced key.
	 */
	INDEX,
	/**
	 * The filter is compiled into a test of a single reference row and evaluated on the rows of every fetched owner.
	 */
	BODY;

	/**
	 * The system property the route is read from.
	 */
	static final String SYSTEM_PROPERTY = "evita.test.referenceContentRoute";
	/**
	 * The route in effect.
	 */
	private static volatile ReferenceContentRoute current = valueOf(
		System.getProperty(SYSTEM_PROPERTY, BODY.name())
	);

	/**
	 * Returns the route in effect.
	 *
	 * @return the route
	 */
	@Nonnull
	static ReferenceContentRoute current() {
		return current;
	}

	/**
	 * Switches the route for the whole JVM - for a test that runs isolated from all others only.
	 *
	 * @param route the route to switch to
	 * @return the route in effect before the switch
	 */
	@Nonnull
	static ReferenceContentRoute switchTo(@Nonnull ReferenceContentRoute route) {
		final ReferenceContentRoute previous = current;
		current = route;
		return previous;
	}

}
