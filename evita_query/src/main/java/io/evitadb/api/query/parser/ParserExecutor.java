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

package io.evitadb.api.query.parser;

import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Executes parsing function with thread local {@link ParseContext} with client metadata. This executor must be used
 * for all parsing functions that require access to {@link ParseContext}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ParserExecutor {

	private static final ThreadLocal<ParseContext> CONTEXT = new ThreadLocal<>();

	@Nonnull
	public static <T> T execute(@Nonnull ParseContext context, @Nonnull Supplier<T> executable) {
		try {
			CONTEXT.set(context);
			final T result = executable.get();
			Assert.notNull(
				result,
				() -> new EvitaQLInvalidQueryError(0, 0, "Result of parse execution is null.")
			);
			return result;
		} catch (EvitaQLInvalidQueryError e) {
			throw e;
		} catch (ParseCancellationException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof EvitaQLInvalidQueryError evitaQLInvalidQueryError) {
				throw evitaQLInvalidQueryError;
			} else {
				// probably missed to wrap error with EvitaQL error, therefore it should be checked
				throw new EvitaInternalError(cause.getMessage(), "Internal error occurred during query parsing.", cause);
			}
		} catch (Exception e) {
			throw new EvitaInternalError(e.getMessage(), "Internal error occurred during query parsing.", e);
		} finally {
			CONTEXT.remove();
		}
	}

	@Nonnull
	public static ParseContext getContext() {
		final ParseContext context = CONTEXT.get();
		Assert.isPremiseValid(context != null, "Missing query parse context.");
		return context;
	}
}
