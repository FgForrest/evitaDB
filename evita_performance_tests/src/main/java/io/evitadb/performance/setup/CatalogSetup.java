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

package io.evitadb.performance.setup;

import io.evitadb.core.Evita;

import javax.annotation.Nonnull;

/**
 * This interface defines methods that allow to work with test catalog universally thorough all performance tests.
 * For read only tests that share the same database contents it's highly worthwhile to reuse existing database and
 * avoid duplicate costly setups.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogSetup {

	/**
	 * Creates new empty database for specific implementation and returns instance of prepared db.
	 */
	Evita createEmptyEvitaInstance(@Nonnull String catalogName);

	/**
	 * Creates new Evita instance for specific implementation based on existing data from previous run.
	 */
	default Evita createEvitaInstanceFromExistingData(@Nonnull String catalogName) {
		throw new UnsupportedOperationException("Not implemented!");
	}

	/**
	 * Returns true if database is ready from previous runs and can be reused.
	 */
	default boolean isCatalogAvailable(@Nonnull String catalogName) {
		return false;
	}

	/**
	 * Returns true if database should be recreated from scratch every time test is run.
	 */
	default boolean shouldStartFromScratch() {
		return true;
	}


}
