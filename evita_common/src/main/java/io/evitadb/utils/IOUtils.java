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

package io.evitadb.utils;


import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * IOutils contains various utility methods for work with input/output streams.
 *
 * We know these functions are available in Apache Commons, but we try to keep our transitive dependencies as low as
 * possible, so we rather went through duplication of the code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class IOUtils {

	/**
	 * Closes multiple resources encapsulated in {@link IOExceptionThrowingRunnable} instances, suppressing
	 * and aggregating any {@link IOException} that occurs during execution. If any exceptions are thrown, they
	 * are encapsulated and re-thrown as a single exception provided by the {@code exceptionFactory}.
	 *
	 * @param <T>               the type of exception that will be thrown if any {@link IOException} occurs
	 * @param exceptionFactory  a supplier that provides an exception of type {@code T}, used to wrap any
	 *                          {@link IOException} thrown during the execution of the provided runnables
	 * @param runnable          varargs of {@link IOExceptionThrowingRunnable} instances which encapsulate
	 *                          the resources/actions to be closed or executed
	 * @throws T                the consolidated exception containing any {@link IOException}s that were
	 *                          thrown by the provided runnables
	 */
	public static <T extends RuntimeException> void close(
		@Nonnull Supplier<T> exceptionFactory,
		@Nonnull IOExceptionThrowingRunnable... runnable
	) throws T {
		T exception = null;
		for (IOExceptionThrowingRunnable lambda : runnable) {
			try {
				lambda.run();
			} catch (IOException e) {
				exception = exception == null ? exceptionFactory.get() : exception;
				exception.addSuppressed(e);
			}
		}
		if (exception != null) {
			throw exception;
		}
	}


	@FunctionalInterface
	public interface IOExceptionThrowingRunnable {

		void run() throws IOException;

	}

}
