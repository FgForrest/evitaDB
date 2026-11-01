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

package io.evitadb.documentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.gap;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.spacing;
import static io.evitadb.test.TestTags.EXPRESSION;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Java source {@link JavaPrettyPrintingVisitor} generates for the documentation examples.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Java source of documentation examples")
@Tag(TEST_HARNESS)
@Tag(QUERY)
@Tag(EXPRESSION)
class JavaPrettyPrintingVisitorTest {

	@DisplayName("parse an expression argument from the text it parses from")
	@Test
	void shouldParseExpressionArgumentFromItsText() {
		final String source = JavaPrettyPrintingVisitor.toString(
			query(
				collection("Product"),
				require(page(1, 5, spacing(gap(1, "$pageNumber == 1 || 'a\"b' == 'a\"b'"))))
			)
		);

		// a quoted text would parse to a string constant, never the condition; the Java string literal escapes quotes
		assertTrue(
			source.contains("ExpressionFactory.parse(\"$pageNumber == 1 || 'a\\\"b' == 'a\\\"b'\")"), source
		);
	}

}
