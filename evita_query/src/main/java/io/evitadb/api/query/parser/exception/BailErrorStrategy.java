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

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.ParseCancellationException;

/**
 * Extension of {@link org.antlr.v4.runtime.BailErrorStrategy} which generates descriptive {@link EvitaSyntaxException}s wrapped in {@link ParseCancellationException}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class BailErrorStrategy extends org.antlr.v4.runtime.BailErrorStrategy {

	@Override
	public void recover(Parser recognizer, RecognitionException e) {
		try {
			super.recover(recognizer, e);
		} catch (ParseCancellationException ex) {
			throw new ParseCancellationException(
				new EvitaSyntaxException(e.getOffendingToken(), e.getMessage())
			);
		}
	}

	@Override
	public Token recoverInline(Parser recognizer) throws RecognitionException {
		try {
			return super.recoverInline(recognizer);
		} catch (ParseCancellationException e) {
			throw new ParseCancellationException(
				new EvitaSyntaxException(
					recognizer.getCurrentToken(),
					"Unexpected token, expected: " + recognizer.getExpectedTokens().toString(recognizer.getVocabulary())
				)
			);
		}
	}
}
