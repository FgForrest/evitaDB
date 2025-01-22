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


import io.evitadb.exception.UnexpectedIOException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

/**
 * IOUtils contains various utility methods for work with input/output streams.
 *
 * We know these functions are available in Apache Commons, but we try to keep our transitive dependencies as low as
 * possible, so we rather went through duplication of the code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class IOUtils {

	/**
	 * Copies all bytes from the provided input stream to the output stream using the specified buffer.
	 *
	 * @param inputStream the input stream from which bytes are read (non-null)
	 * @param outputStream the output stream to which bytes are written (non-null)
	 * @param buffer the buffer used for transferring bytes between streams (non-null)
	 * @return the total number of bytes that were copied
	 * @throws UnexpectedIOException if an I/O error occurs during the copying process
	 */
	public static long copy(
		@Nonnull InputStream inputStream,
		@Nonnull OutputStream outputStream,
		@Nonnull byte[] buffer
	) throws UnexpectedIOException {
		try {
			int count;
			int n;
			for (count = 0; -1 != (n = inputStream.read(buffer, 0, buffer.length)); count += n) {
				outputStream.write(buffer, 0, n);
			}

			return count;
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"An unexpected I/O exception occurred while copying data from the input stream to the output stream: " + e.getMessage(),
				"An unexpected I/O exception occurred while copying data from the input stream to the output stream.",
				e
			);
		}
	}

	/**
	 * Copies specified number of bytes from the provided input stream to the output stream using the specified buffer.
	 *
	 * @param inputStream the input stream from which bytes are read (non-null)
	 * @param outputStream the output stream to which bytes are written (non-null)
	 * @param length the number of bytes to copy
	 * @param buffer the buffer used for transferring bytes between streams (non-null)
	 * @return the total number of bytes that were copied
	 * @throws UnexpectedIOException if an I/O error occurs during the copying process
	 */
	public static long copy(
		@Nonnull InputStream inputStream,
		@Nonnull OutputStream outputStream,
		int length,
		@Nonnull byte[] buffer
	) throws UnexpectedIOException {
		try {
			int count;
			int n;
			for (count = 0; -1 != (n = inputStream.read(buffer, 0, Math.min(buffer.length, length - count))) && length - count > 0; count += n) {
				outputStream.write(buffer, 0, n);
			}

			return count;
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"An unexpected I/O exception occurred while copying data from the input stream to the output stream: " + e.getMessage(),
				"An unexpected I/O exception occurred while copying data from the input stream to the output stream.",
				e
			);
		}
	}

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


	/**
	 * Represents a functional interface that can be used to encapsulate a block of code
	 * that may throw an {@link IOException}. This interface is effectively a specialized
	 * form of {@link Runnable} for operations where checked I/O exceptions need to be handled.
	 *
	 * Implementations of this interface enable the execution of operations with the awareness
	 * and explicit handling of {@link IOException}. This is especially useful in scenarios
	 * where multiple such operations need to be executed or wrapped with exception aggregation,
	 * for example, in utility methods like resource handling or cleanup.
	 *
	 * Method {@code run} is similar to the {@link Runnable#run()} method but allows an
	 * {@link IOException} to be thrown.
	 *
	 * Functional-style programming can make use of this interface to define inline behaviors
	 * for operations expected to throw {@link IOException}. It can also be integrated with
	 * various utility methods that leverage this interface for exception handling and resource management.
	 */
	@FunctionalInterface
	public interface IOExceptionThrowingRunnable {

		void run() throws IOException;

	}

}
