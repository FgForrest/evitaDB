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

package io.evitadb.test.upgrade;

/**
 * Says which released engines wrote the bytes of a release fixture, and in which order. The origin decides which
 * generator run writes the fixture (see {@link ReleaseFixtureWriter}) and what the storage protocol of its bootstrap
 * record is when the current engine first opens it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum ReleaseFixtureOrigin {

	/**
	 * Written by the v2026.2.18 engine only; the catalog arrives at the current engine at storage protocol 6.
	 */
	RELEASE_2026_2,
	/**
	 * Written by the v2026.1.20 engine, then opened by the v2026.2.18 engine, which upgrades it to storage protocol 6
	 * and runs the recipe's {@link ReleaseFixtureRecipe#release2026_2Transactions()} on it. The catalog arrives at the
	 * current engine at storage protocol 6, holding parts the 2026.1 engine wrote and the 2026.2 upgrade kept.
	 */
	RELEASE_2026_1_OPENED_BY_2026_2,
	/**
	 * Written by the v2026.1.20 engine only and never opened by a later release; the catalog arrives at the current
	 * engine at storage protocol 4, so the current engine runs the whole chain of storage migrations over it.
	 */
	RELEASE_2026_1

}
