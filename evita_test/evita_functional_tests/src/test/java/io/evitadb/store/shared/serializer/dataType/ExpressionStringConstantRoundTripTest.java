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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.evitadb.test.TestTags.EXPRESSION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a string constant of an EvitaEL expression survives being stored. The stored text is the printed
 * expression tree, so the printing has to escape every character the grammar does not take as it is - the
 * apostrophe, the backslash and the control characters - the way the parser reads it back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A string constant of an expression")
@Tag(STORAGE)
@Tag(EXPRESSION)
class ExpressionStringConstantRoundTripTest implements EvitaTestSupport {
	private TestPaths paths;
	private Evita evita;

	@AfterEach
	void tearDown() {
		if (this.evita != null) {
			this.evita.close();
		}
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
		}
	}

	@DisplayName("reads back from storage as it was written")
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		// value `C:\new` - read back with a line break in place of `\n`
		"$value == 'C:\\\\new'",
		// value `end\` - the stored `\'` reads as an escaped apostrophe, so the text does not parse at all
		"$value == 'end\\\\'",
		// value `a\x` - the stored `\x` is no escape the grammar knows, so the text does not parse at all
		"$value == 'a\\\\x' || $value == 'b'",
		// value with a line break and a control character - the grammar refuses both raw
		"$value == 'line\\nbreak' || $value == 'bell\\u0007'",
		// control: an apostrophe is escaped by the printing and round-trips
		"$value == 'it\\'s'"
	})
	void shouldReadBackFromStorageAsWritten(String text) {
		final Expression expression = ExpressionFactory.parse(text);
		final ExpressionSerializer serializer = new ExpressionSerializer();
		final Output output = new Output(256);
		serializer.write(new Kryo(), output, expression);

		final Expression read = serializer.read(new Kryo(), new Input(output.toBytes()), Expression.class);

		assertEquals(expression, read);
	}

	@DisplayName("in a reference schema survives a restart")
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"($reference.referencedEntity.attributes['code'] ?? '') == 'C:\\\\new'",
		"($reference.referencedEntity.attributes['code'] ?? '') == 'end\\\\'"
	})
	void shouldSurviveRestartInReferenceSchema(String text) {
		this.paths = createTestPaths("ExpressionStringConstantRoundTripTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		final Expression expression = ExpressionFactory.parse(text);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY).updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.facetedPartially(expression)
					)
					.updateVia(session);
			}
		);

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());

		final Expression stored = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.getReferenceOrThrowException(Entities.CATEGORY)
					.getFacetedPartially();
			}
		);
		assertEquals(expression, stored);
	}

}
