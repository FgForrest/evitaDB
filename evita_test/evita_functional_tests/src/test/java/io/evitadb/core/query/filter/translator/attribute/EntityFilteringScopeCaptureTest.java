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

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.filter.AttributeLessThan;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins that the filter of entities found without a collection - `createEntityFilteringBitmapFilter` - judges the
 * constraint by the scopes the constraint was translated in, not by the scopes the visitor requests when the filter
 * finally runs. No query a client can send was found to reach the difference: an `inScope` container over a query
 * that names no collection answered no entity at all, so the filter had nothing to judge. The wiring is therefore
 * pinned where it can be observed - on the translator and the visitor it is given.
 *
 * The visitor is a mock because a real one needs a whole query planning context; the processing scope is a mock
 * because the scopes it reports are the very thing the test varies between the translation and the run.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Entity filtering judges a constraint by the scopes it was translated in")
@Tag(ENGINE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
class EntityFilteringScopeCaptureTest {
	private static final String ATTRIBUTE_NAME = "price";
	private static final BigDecimal BEYOND_INDEX_KEY = new BigDecimal("99999999999");

	@Test
	@DisplayName("a decimal beyond the index key is not refused for a scope with no index, whatever scopes the run requests")
	void shouldNotRefuseWhenTheTranslationScopeHoldsNoIndex() {
		final ScopeSwitch scopes = new ScopeSwitch(EnumSet.of(Scope.ARCHIVED));
		final AttributeBitmapFilter filter = scopes.createFilter();
		scopes.run(EnumSet.of(Scope.LIVE));

		final Bitmap result = filter.filter(scopes.executionContext());

		assertEquals(1, result.size());
	}

	@Test
	@DisplayName("a decimal beyond the index key is refused for a scope with an index, whatever scopes the run requests")
	void shouldRefuseWhenTheTranslationScopeHoldsAnIndex() {
		final ScopeSwitch scopes = new ScopeSwitch(EnumSet.of(Scope.LIVE));
		final AttributeBitmapFilter filter = scopes.createFilter();
		scopes.run(EnumSet.of(Scope.ARCHIVED));

		assertThrows(EvitaInvalidUsageException.class, () -> filter.filter(scopes.executionContext()));
	}

	/**
	 * The visitor whose processing scope reports the scopes that were set last, over an attribute that is indexed in
	 * the live scope only.
	 */
	private static final class ScopeSwitch {
		private final AtomicReference<Set<Scope>> reportedScopes;
		private final FilterByVisitor visitor;
		private final QueryExecutionContext executionContext;

		/**
		 * @param translationScopes the scopes the constraint is translated in
		 */
		ScopeSwitch(@Nonnull Set<Scope> translationScopes) {
			this.reportedScopes = new AtomicReference<>(translationScopes);

			final AttributeSchemaContract attributeSchema = mock(AttributeSchemaContract.class);
			doReturn(BigDecimal.class).when(attributeSchema).getPlainType();
			when(attributeSchema.getName()).thenReturn(ATTRIBUTE_NAME);
			when(attributeSchema.getIndexedDecimalPlaces()).thenReturn(2);
			when(attributeSchema.hasFilterIndexInScope(Scope.LIVE)).thenReturn(true);
			when(attributeSchema.hasFilterIndexInScope(Scope.ARCHIVED)).thenReturn(false);

			// answered by name because the schema lookup is a varargs method
			final Answer<Object> processingScopeAnswer = invocation -> switch (invocation.getMethod().getName()) {
				case "getScopes" -> this.reportedScopes.get();
				case "getAttributeSchema" -> attributeSchema;
				case "getAttributeValueStream" -> Stream.of(
					Optional.of(new AttributeValue(1, new AttributeKey(ATTRIBUTE_NAME), new BigDecimal("1.5"), false))
				);
				default -> Answers.RETURNS_DEFAULTS.answer(invocation);
			};
			final ProcessingScope<?> processingScope = mock(ProcessingScope.class, processingScopeAnswer);

			this.visitor = mock(FilterByVisitor.class);
			doReturn(processingScope).when(this.visitor).getProcessingScope();

			final EntitySchemaContract entitySchema = mock(EntitySchemaContract.class);
			when(entitySchema.getName()).thenReturn("product");
			final ServerEntityDecorator entity = mock(ServerEntityDecorator.class);
			when(entity.getSchema()).thenReturn(entitySchema);
			this.executionContext = mock(QueryExecutionContext.class);
			when(this.executionContext.getPrefetchedEntities()).thenReturn(List.of(entity));
			when(this.executionContext.translateEntity(entity)).thenReturn(1);
		}

		/**
		 * Translates `attributeLessThan` with a decimal beyond the index key.
		 */
		@Nonnull
		AttributeBitmapFilter createFilter() {
			return new AttributeLessThanTranslator().createEntityFilteringBitmapFilter(
				new AttributeLessThan(ATTRIBUTE_NAME, (Serializable) BEYOND_INDEX_KEY), this.visitor
			);
		}

		/**
		 * Sets the scopes the processing scope reports from now on.
		 */
		void run(@Nonnull Set<Scope> scopesAtRun) {
			this.reportedScopes.set(scopesAtRun);
		}

		@Nonnull
		QueryExecutionContext executionContext() {
			return this.executionContext;
		}
	}

}
