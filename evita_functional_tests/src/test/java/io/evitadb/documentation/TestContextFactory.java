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

package io.evitadb.documentation;

import org.junit.jupiter.api.DynamicTest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Test context factory wraps access to the {@link TestContext} instance and provides optional {@link DynamicTest}
 * required to init and tear down the {@link TestContext}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface TestContextFactory<T extends TestContext> {

	/**
	 * The test required to instantiate and init the {@link TestContext}.
	 */
	@Nullable
	DynamicTest getInitTest(@Nonnull Environment profile);

	/**
	 * The test required to tear down the {@link TestContext}.
	 */
	@Nullable
	DynamicTest getTearDownTest(@Nonnull Environment profile);

	/**
	 * Returns the {@link TestContext} instance.
	 */
	@Nonnull
	T getContext();

}
