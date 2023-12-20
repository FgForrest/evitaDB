/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.offsetIndex.io;

import io.evitadb.store.kryo.ObservableInput;

import javax.annotation.Nonnull;
import java.util.function.Function;

/**
 * ReadOnlyHandle protects access to the {@link ObservableInput}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface ReadOnlyHandle {

	/**
	 * Executes the provided logic on the ObservableInput and returns the result.
	 *
	 * @param logic the function that takes an ObservableInput and returns a result
	 * @param <T> the type of the result
	 * @return the result of executing the provided logic
	 */
	<T> T execute(@Nonnull Function<ObservableInput<?>, T> logic);

	/**
	 * This method closes the read handle ignoring the current lock.
	 */
	void forceClose();

	/**
	 * This method returns the last position that can be read.
	 */
	long getLastWrittenPosition();

}