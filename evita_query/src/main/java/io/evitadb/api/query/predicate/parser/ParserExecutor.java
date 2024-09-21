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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.predicate.parser;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ParserExecutor {

	private static final ThreadLocal<ParseContext> CONTEXT = new ThreadLocal<>();

	@Nonnull
	public static <T> T execute(@Nonnull ParseContext context, @Nonnull Supplier<T> executable) {
		try {
			CONTEXT.set(context);
			final T result = executable.get();
			Assert.isPremiseValid(
				result != null,
				"Result of parse execution is null."
			);
			return result;
			// todo lho custom exception
		} catch (EvitaInvalidUsageException e) {
			throw e;
		} catch (ParseCancellationException e) {
			final Throwable cause = e.getCause();
			// todo lho custom exception
			if (cause instanceof EvitaInvalidUsageException evitaQLInvalidQueryError) {
				throw evitaQLInvalidQueryError;
			} else {
				// probably missed to wrap error with EvitaQL error, therefore it should be checked
				throw new GenericEvitaInternalError(cause.getMessage(), "Internal error occurred during predicate parsing.", cause);
			}
		} catch (Exception e) {
			throw new GenericEvitaInternalError(e.getMessage(), "Internal error occurred during predicate parsing.", e);
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
