/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.grammar.EvitaQLParser;
import io.evitadb.api.query.parser.grammar.EvitaQLVisitor;

/**
 * <p>Implementation of {@link EvitaQLVisitor} for parsing parameters used in {@link EvitaQLValueTokenVisitor} and
 * {@link EvitaQLClassifierTokenVisitor}.</p>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class EvitaQLParameterVisitor extends EvitaQLBaseVisitor<Object> {

	@Override
	public Object visitPositionalParameter(EvitaQLParser.PositionalParameterContext ctx) {
		return parse(
			ctx,
			() -> {
				final ParseContext context = ParserExecutor.getContext();
				return context.getNextPositionalArgument();
			}
		);
	}

	@Override
	public Object visitNamedParameter(EvitaQLParser.NamedParameterContext ctx) {
		return parse(
			ctx,
			() -> {
				final ParseContext context = ParserExecutor.getContext();

				final String parameterName = ctx.getText().substring(1);
				return context.getNamedArgument(parameterName);
			}
		);
	}
}
