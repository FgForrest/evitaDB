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

package io.evitadb.api.query.parser.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception to notify about any query or expression parsing error (syntax or logical).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class EvitaSyntaxException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = -4647520133884807181L;

	private static final String DEFAULT_ERROR_MSG = "Invalid query, error at position %d:%d: %s";

	@Getter
	private final int line;
	@Getter
	private final int charPositionInLine;
	@Getter
	@Nonnull
	private final String reason;

	public EvitaSyntaxException(@Nonnull ParserRuleContext ctx, @Nonnull String publicMessage) {
		super(String.format(DEFAULT_ERROR_MSG, ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine() + 1, publicMessage));
		this.line = ctx.getStart().getLine();
		this.charPositionInLine = ctx.getStart().getCharPositionInLine() + 1;
		this.reason = publicMessage;
	}

	public EvitaSyntaxException(@Nonnull Token offendingToken, @Nonnull String publicMessage) {
		super(String.format(DEFAULT_ERROR_MSG, offendingToken.getLine(), offendingToken.getCharPositionInLine() + 1, publicMessage));
		this.line = offendingToken.getLine();
		this.charPositionInLine = offendingToken.getCharPositionInLine() + 1;
		this.reason = publicMessage;
	}

	public EvitaSyntaxException(int line, int charPositionInLine, @Nonnull String publicMessage) {
		super(String.format(DEFAULT_ERROR_MSG, line, charPositionInLine + 1, publicMessage));
		this.line = line;
		this.charPositionInLine = charPositionInLine + 1;
		this.reason = publicMessage;
	}
}
