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

package io.evitadb.exception;

import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;

/**
 * Derives the {@link EvitaError#getErrorCode() error code} of an exception from the stack trace the exception
 * captured when it was created. Shared by the two roots of the evitaDB error hierarchy,
 * {@link EvitaInternalError} and {@link EvitaInvalidUsageException}, which are otherwise unrelated types.
 *
 * The code is a stable identifier of the *place the exception was created*, safe to hand to a client because it
 * leaks neither class names nor messages - see {@link EvitaError#getErrorCode()}.
 *
 * ## Why the frames come from the throwable
 *
 * The JVM omits the throwable hierarchy's own constructor frames when it fills in a stack trace, so
 * `getStackTrace()[0]` is already the statement that created the exception. Walking
 * `Thread.currentThread().getStackTrace()` instead - as this code used to - sees those constructor frames and has to
 * skip them, which is what made the code a constant: the skip loop compared each frame against the *runtime* class,
 * while the frame it needed to skip belonged to the *declaring* class, so it stopped on the first frame every time.
 *
 * ## Why assertion frames are skipped
 *
 * {@link Assert} throws on its callers' behalf, so its own frame is never the origin anybody is looking for - and
 * with several hundred call sites across the engine it would otherwise become a second constant. Helper methods that
 * merely *build* an exception and let the caller throw it are deliberately **not** skipped: a named factory such as
 * `TransactionTrunkFinalizer#wrapPostReplayCorruption` is a meaningful origin in its own right.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ErrorCodeResolver {
	/**
	 * Returned when the exception carries no stack trace at all - which happens when the JVM runs with
	 * `-XX:-StackTraceInThrowable`, or when a throwable was constructed with stack trace collection disabled.
	 * Deliberately not in the `hash:hash:line` shape of a real code, so it is obvious in a log that no origin was
	 * available rather than looking like a site that cannot be found.
	 */
	static final String UNKNOWN_ERROR_CODE = "?:?:0";

	/**
	 * Fully qualified name of the assertion helper whose frames are skipped, resolved from the class rather than
	 * spelled out so that renaming {@link Assert} cannot silently turn every assertion into one shared error code.
	 */
	private static final String ASSERTION_HELPER_CLASS_NAME = Assert.class.getName();

	private ErrorCodeResolver() {
		// this class is a static helper and must never be instantiated
	}

	/**
	 * Resolves the error code of the passed exception from its captured stack trace.
	 *
	 * @param exception exception to resolve the code for; only its stack trace is read, so it is safe to call on a
	 *                  partially constructed instance
	 * @return the resolved code, or {@link #UNKNOWN_ERROR_CODE} when no usable frame is available
	 */
	@Nonnull
	static String resolveErrorCode(@Nonnull Throwable exception) {
		final StackTraceElement[] stackTrace = exception.getStackTrace();
		int index = 0;
		while (index < stackTrace.length && ASSERTION_HELPER_CLASS_NAME.equals(stackTrace[index].getClassName())) {
			index++;
		}
		if (index >= stackTrace.length) {
			return UNKNOWN_ERROR_CODE;
		}
		final StackTraceElement origin = stackTrace[index];
		return StringUtils.hashChars(origin.getClassName()) + ":" +
			StringUtils.hashChars(origin.getMethodName()) + ":" +
			origin.getLineNumber();
	}

}
