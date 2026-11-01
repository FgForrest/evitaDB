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

package io.evitadb.store.shared.serializer.dataType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.test.TestTags.EXPRESSION;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ExpressionSerializer} reads back the text of an expression it stored, and that a stored text
 * the parser no longer accepts as a whole still opens the catalog the way it was read before: as its longest valid
 * prefix, with a warning naming both.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Expression serializer should")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(EXPRESSION)
class ExpressionSerializerTest {
	private final Kryo kryo = new Kryo();
	private final ExpressionSerializer serializer = new ExpressionSerializer();

	@DisplayName("read back the expression it wrote")
	@Test
	void shouldReadBackWrittenExpression() {
		final Expression expression = ExpressionFactory.parse("$a > 1 && ($b - 2 + 3) * -1 < 0");

		final Output output = new Output(256);
		this.serializer.write(this.kryo, output, expression);
		final Expression read = this.serializer.read(this.kryo, new Input(output.toBytes()), Expression.class);

		assertEquals(expression, read);
	}

	@DisplayName("read a stored text with input after a complete expression as its prefix, and warn")
	@Test
	void shouldReadStoredTextWithTrailingInputAsItsPrefixAndWarn() {
		final Output output = new Output(64);
		output.writeString("$a > 1 2");

		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		final Logger logger = assertInstanceOf(
			Logger.class,
			LoggerFactory.getLogger(ExpressionSerializer.class),
			"Logback backend required to capture log output."
		);
		appender.start();
		logger.addAppender(appender);
		final Expression read;
		try {
			read = this.serializer.read(this.kryo, new Input(output.toBytes()), Expression.class);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		assertEquals(ExpressionFactory.parse("$a > 1"), read);
		final List<String> warnings = warningsOf(appender);
		assertEquals(1, warnings.size(), () -> "Expected one warning, got: " + warnings);
		assertTrue(warnings.get(0).contains("`$a > 1 2`"), warnings.get(0));
		assertTrue(warnings.get(0).contains("`$a > 1`"), warnings.get(0));
	}

	/**
	 * Returns the formatted messages of the warnings the appender captured.
	 *
	 * @param appender the appender to read
	 * @return the warnings in the order they were logged
	 */
	@Nonnull
	private static List<String> warningsOf(@Nonnull ListAppender<ILoggingEvent> appender) {
		return appender.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

}
