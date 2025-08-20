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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import org.antlr.v4.runtime.ParserRuleContext;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Base visitor for all visitor implementations. Provides common helper methods.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class EvitaQLBaseVisitor<T> extends io.evitadb.api.query.parser.grammar.EvitaQLBaseVisitor<T> {

	/**
	 * Executes parsing function. Any non-parser exception will be wrapped into parser exception.
	 * Should be used by every visit* method.
	 */
	protected T parse(@Nonnull ParserRuleContext ctx, @Nonnull Supplier<T> parser) {
		try {
			return parser.get();
		} catch (EvitaSyntaxException ex) {
			throw ex;
		} catch (EvitaInvalidUsageException ex) {
			// wrap client error into QL specific client error with more QL-specific details
			throw new EvitaSyntaxException(ctx, ex.getPublicMessage());
		}
	}

	/**
	 * Tries to cast argument to target class.
	 */
	@Nonnull
	protected <A> A castArgument(@Nonnull ParserRuleContext ctx,
                                 @Nonnull Object arg,
                                 @Nonnull Class<A> argClass) {
		Assert.isTrue(
			argClass.isAssignableFrom(arg.getClass()),
			() -> new EvitaSyntaxException(ctx, "Invalid argument.")
		);
		//noinspection unchecked
		return (A) arg;
	}
}
