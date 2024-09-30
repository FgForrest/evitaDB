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

package io.evitadb.api.query.predicate;

import io.evitadb.api.query.predicate.parser.ParseContext;
import io.evitadb.api.query.predicate.parser.ParserExecutor;
import io.evitadb.api.query.predicate.parser.ParserFactory;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser;
import io.evitadb.api.query.predicate.parser.visitor.PredicateEvaluationVisitor;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Map;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2024
 */
public class DefaultPredicateEvaluator implements PredicateEvaluator {

	private static final DefaultPredicateEvaluator INSTANCE = new DefaultPredicateEvaluator();

	private final PredicateEvaluationVisitor predicateEvaluationVisitor = new PredicateEvaluationVisitor();

	@Nonnull
	public static DefaultPredicateEvaluator getInstance() {
		return INSTANCE;
	}

	@Override
	public boolean evaluate(@Nonnull String predicate, @Nonnull Map<String, BigDecimal> variables) {
		final PredicateParser parser = ParserFactory.getParser(predicate);
        return ParserExecutor.execute(
            new ParseContext(variables),
            () -> parser.predicate().accept(predicateEvaluationVisitor)
        );
	}
}
