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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.store.kryo.ObservableOutput;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ReadOnlyHandle protects access to the {@link ObservableOutput}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface WriteOnlyHandle extends Closeable {

	/**
	 * Executes an operation if a premise is met and returns the result.
	 *
	 * @param operation the description of the operation
	 * @param premise   the condition that must be met before executing the operation
	 * @param logic     the logic to be executed if the premise is met, which takes an ObservableOutput as input and returns a result of type T
	 * @param <T>       the type of the result
	 * @return the result of executing the logic
	 */
	<T> T checkAndExecute(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Function<ObservableOutput<?>, T> logic);

	/**
	 * Checks a premise and executes a logic function synchronously.
	 *
	 * @param operation the description of the operation
	 * @param premise   the condition that must be met before executing the operation
	 * @param logic     the logic to be executed if the premise is met, which takes an ObservableOutput as input and does not return a result
	 */
	void checkAndExecuteAndSync(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Consumer<ObservableOutput<?>> logic);

	/**
	 * Executes an operation if a premise is met and returns the result. The logic function is executed synchronously.
	 * Flushes output buffers to the disk and calls fsync so that we can be sure all data are safely persisted on disk.
	 *
	 * @param operation          the description of the operation
	 * @param premise            the condition that must be met before executing the operation
	 * @param logic              the logic to be executed if the premise is met, which takes an ObservableOutput as
	 *                           input and returns a result of type T
	 * @param postExecutionLogic the post-execution logic to be applied to the output and the result of the logic
	 *                           function, which takes an ObservableOutput and the result of the logic
	 *                           function as input and returns a result of type T
	 * @param <S>                the type of the result of the logic function
	 * @param <T>                the type of the result
	 * @return the result of executing the post-execution logic
	 */
	<S, T> T checkAndExecuteAndSync(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Function<ObservableOutput<?>, S> logic, @Nonnull BiFunction<ObservableOutput<?>, S, T> postExecutionLogic);

	/**
	 * Returns the last position that was already written to the output.
	 *
	 * @return last written position
	 */
	long getLastWrittenPosition();

	/**
	 * Creates new ReadOnlyHandle that protects read access to the ObservableInput.
	 *
	 * @return a ReadOnlyHandle object
	 */
	@Nonnull
	ReadOnlyHandle toReadOnlyHandle();

	/**
	 * Closes underlying output.
	 */
	@Override
	void close();
}
