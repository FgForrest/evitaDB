/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.driver;

import javax.annotation.Nonnull;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Represents a function that can be called asynchronously.
 *
 * @param <S> the type of the input to the function
 * @param <T> the type of the result of the function
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface AsyncCallFunction<S, T> {

	/**
	 * Applies the function to the given service stub asynchronously.
	 *
	 * @param serviceStub the service stub to apply the function on
	 * @return the result of applying the function to the service stub
	 * @throws InterruptedException if the execution is interrupted while waiting for the result
	 * @throws ExecutionException if the execution encounters an exception
	 * @throws TimeoutException if the execution times out
	 */
	T apply(@Nonnull S serviceStub) throws InterruptedException, ExecutionException, TimeoutException;

}
