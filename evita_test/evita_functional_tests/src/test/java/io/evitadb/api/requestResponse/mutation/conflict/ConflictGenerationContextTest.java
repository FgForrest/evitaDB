/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.mutation.conflict;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConflictGenerationContext} scoped lifecycle management.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ConflictGenerationContext")
class ConflictGenerationContextTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Catalog name scoping")
	class CatalogNameScoping {

		@Test
		@DisplayName("should provide catalog name within scope")
		void shouldProvideCatalogNameWithinScope() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			final String result = context.withCatalogName("testCatalog", ctx -> {
				assertEquals("testCatalog", ctx.getCatalogName());
				return "done";
			});

			assertEquals("done", result);
		}

		@Test
		@DisplayName("should clear catalog name after scope")
		void shouldClearCatalogNameAfterScope() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			context.withCatalogName("testCatalog", ctx -> "done");

			assertThrows(GenericEvitaInternalError.class, context::getCatalogName);
		}

		@Test
		@DisplayName("should clear catalog name on exception")
		void shouldClearCatalogNameOnException() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			assertThrows(RuntimeException.class, () ->
				context.withCatalogName("testCatalog", ctx -> {
					throw new RuntimeException("test error");
				})
			);

			assertThrows(GenericEvitaInternalError.class, context::getCatalogName);
		}

		@Test
		@DisplayName("should throw when catalog name is not set")
		void shouldThrowWhenCatalogNameIsNotSet() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			assertThrows(GenericEvitaInternalError.class, context::getCatalogName);
		}
	}

	@Nested
	@DisplayName("Entity type scoping")
	class EntityTypeScoping {

		@Test
		@DisplayName("should provide entity type and primary key within scope")
		void shouldProvideEntityTypeAndPrimaryKeyWithinScope() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			final String result = context.withEntityType("Product", 42, ctx -> {
				assertEquals("Product", ctx.getEntityType());
				assertEquals(42, ctx.getEntityPrimaryKey());
				assertTrue(ctx.isEntityTypePresent());
				return "done";
			});

			assertEquals("done", result);
		}

		@Test
		@DisplayName("should allow null primary key")
		void shouldAllowNullPrimaryKey() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			context.withEntityType("Product", null, ctx -> {
				assertEquals("Product", ctx.getEntityType());
				assertNull(ctx.getEntityPrimaryKey());
				return "done";
			});
		}

		@Test
		@DisplayName("should clear entity type after scope")
		void shouldClearEntityTypeAfterScope() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			context.withEntityType("Product", 42, ctx -> "done");

			assertFalse(context.isEntityTypePresent());
			assertThrows(GenericEvitaInternalError.class, context::getEntityType);
			assertNull(context.getEntityPrimaryKey());
		}

		@Test
		@DisplayName("should clear entity type on exception")
		void shouldClearEntityTypeOnException() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			assertThrows(RuntimeException.class, () ->
				context.withEntityType("Product", 42, ctx -> {
					throw new RuntimeException("test error");
				})
			);

			assertFalse(context.isEntityTypePresent());
			assertNull(context.getEntityPrimaryKey());
		}

		@Test
		@DisplayName("should throw when entity type is not set")
		void shouldThrowWhenEntityTypeIsNotSet() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			assertThrows(GenericEvitaInternalError.class, context::getEntityType);
		}

		@Test
		@DisplayName("should report entity type not present initially")
		void shouldReportEntityTypeNotPresentInitially() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			assertFalse(context.isEntityTypePresent());
		}
	}

	@Nested
	@DisplayName("Nested scoping")
	class NestedScoping {

		@Test
		@DisplayName("should support catalog and entity nesting")
		void shouldSupportCatalogAndEntityNesting() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			final String result = context.withCatalogName("testCatalog", catalogCtx ->
				catalogCtx.withEntityType("Product", 42, entityCtx -> {
					assertEquals("testCatalog", entityCtx.getCatalogName());
					assertEquals("Product", entityCtx.getEntityType());
					assertEquals(42, entityCtx.getEntityPrimaryKey());
					return "nested-done";
				})
			);

			assertEquals("nested-done", result);
		}

		@Test
		@DisplayName("should preserve catalog name when entity scope exits")
		void shouldPreserveCatalogNameWhenEntityScopeExits() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			context.withCatalogName("testCatalog", catalogCtx -> {
				catalogCtx.withEntityType("Product", 42, entityCtx -> "done");

				// catalog name should still be available after entity scope exits
				assertEquals("testCatalog", catalogCtx.getCatalogName());
				assertFalse(catalogCtx.isEntityTypePresent());
				return "outer-done";
			});
		}

		@Test
		@DisplayName("should support multiple entity types within catalog scope")
		void shouldSupportMultipleEntityTypesWithinCatalogScope() {
			final ConflictGenerationContext context = new ConflictGenerationContext();

			context.withCatalogName("testCatalog", catalogCtx -> {
				catalogCtx.withEntityType("Product", 1, entityCtx -> {
					assertEquals("Product", entityCtx.getEntityType());
					return "first";
				});

				catalogCtx.withEntityType("Category", 2, entityCtx -> {
					assertEquals("Category", entityCtx.getEntityType());
					return "second";
				});

				return "done";
			});
		}
	}
}
