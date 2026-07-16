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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ConflictGenerationContext} scoped lifecycle management.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ConflictGenerationContext")
@Tag(CONTRACT)
@Tag(SCHEMA)
class ConflictGenerationContextTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Catalog name scoping")
	class CatalogNameScoping {

		@Test
		@DisplayName("should provide catalog name within scope")
		void shouldProvideCatalogNameWithinScope() {
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

			final String result = context.withCatalogName("testCatalog", ctx -> {
				assertEquals("testCatalog", ctx.getCatalogName());
				return "done";
			});

			assertEquals("done", result);
		}

		@Test
		@DisplayName("should clear catalog name after scope")
		void shouldClearCatalogNameAfterScope() {
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

			context.withCatalogName("testCatalog", ctx -> "done");

			assertThrows(GenericEvitaInternalError.class, context::getCatalogName);
		}

		@Test
		@DisplayName("should clear catalog name on exception")
		void shouldClearCatalogNameOnException() {
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

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
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

			assertThrows(GenericEvitaInternalError.class, context::getCatalogName);
		}
	}

	@Nested
	@DisplayName("Entity type scoping")
	class EntityTypeScoping {

		@Test
		@DisplayName("should provide entity type and primary key within scope")
		void shouldProvideEntityTypeAndPrimaryKeyWithinScope() {
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

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
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

			context.withEntityType("Product", null, ctx -> {
				assertEquals("Product", ctx.getEntityType());
				assertNull(ctx.getEntityPrimaryKey());
				return "done";
			});
		}

		@Test
		@DisplayName("should clear entity type after scope")
		void shouldClearEntityTypeAfterScope() {
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

			context.withEntityType("Product", 42, ctx -> "done");

			assertFalse(context.isEntityTypePresent());
			assertThrows(GenericEvitaInternalError.class, context::getEntityType);
			assertNull(context.getEntityPrimaryKey());
		}

		@Test
		@DisplayName("should clear entity type on exception")
		void shouldClearEntityTypeOnException() {
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

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
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

			assertThrows(GenericEvitaInternalError.class, context::getEntityType);
		}

		@Test
		@DisplayName("should report entity type not present initially")
		void shouldReportEntityTypeNotPresentInitially() {
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

			assertFalse(context.isEntityTypePresent());
		}
	}

	@Nested
	@DisplayName("Nested scoping")
	class NestedScoping {

		@Test
		@DisplayName("should support catalog and entity nesting")
		void shouldSupportCatalogAndEntityNesting() {
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

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
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

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
			final ConflictGenerationContext context = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.ENTITY));

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

	@Nested
	@DisplayName("Global-backed emission predicates")
	class GlobalBackedPredicates {

		@Test
		@DisplayName("should emit granular key when the fixed resolution activates the refinement")
		void shouldEmitGranularKeyWhenFixedResolutionActivatesIt() {
			final ConflictGenerationContext context = new ConflictGenerationContext(
				new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE))
			);

			context.withEntityType("Product", 1, ctx -> {
				assertTrue(ctx.shouldEmitEntityAttributeKey("code"));
				assertEquals(ConflictPolicy.ENTITY, ctx.coarsePolicy());
				return null;
			});
		}

		@Test
		@DisplayName("should not emit granular key when the fixed resolution lacks the refinement")
		void shouldNotEmitGranularKeyWhenFixedResolutionLacksIt() {
			final ConflictGenerationContext context = new ConflictGenerationContext(
				new ConflictResolution(ConflictPolicy.ENTITY)
			);

			context.withEntityType("Product", 1, ctx -> {
				assertFalse(ctx.shouldEmitEntityAttributeKey("code"));
				return null;
			});
		}

		@Test
		@DisplayName("should not emit any granular key under a coarser policy")
		void shouldNotEmitGranularKeyUnderCoarserPolicy() {
			final ConflictGenerationContext context = new ConflictGenerationContext(
				new ConflictResolution(ConflictPolicy.CATALOG)
			);

			context.withEntityType("Product", 1, ctx -> {
				assertFalse(ctx.shouldEmitEntityAttributeKey("code"));
				assertFalse(ctx.shouldEmitPriceKey());
				assertEquals(ConflictPolicy.CATALOG, ctx.coarsePolicy());
				return null;
			});
		}
	}

	@Nested
	@DisplayName("Schema-aware emission predicates")
	class SchemaAwarePredicates {

		private static final String ATTR = "code";
		private static final String REF = "brand";

		/**
		 * Builds a schema-aware context whose catalog resolves to {@code catalogResolution} (nullable) and
		 * whose only entity type resolves to {@code entitySchema}.
		 */
		private static ConflictGenerationContext schemaAware(
			@Nonnull ConflictResolution engineDefault,
			@Nullable ConflictResolution catalogResolution,
			@Nullable EntitySchemaContract entitySchema
		) {
			final CatalogSchemaContract catalog = mock(CatalogSchemaContract.class);
			when(catalog.getConflictResolution()).thenReturn(Optional.ofNullable(catalogResolution));
			return new ConflictGenerationContext(engineDefault, catalog, entityType -> entitySchema);
		}

		/**
		 * Builds an entity schema whose own resolution is {@code entityResolution} (nullable) and whose
		 * attribute {@link #ATTR} carries the given per-item override.
		 */
		private static EntitySchemaContract entityWithAttributeOverride(
			@Nullable ConflictResolution entityResolution,
			@Nonnull ConflictResolutionOverride attrOverride
		) {
			final EntitySchemaContract entity = mock(EntitySchemaContract.class);
			when(entity.getConflictResolution()).thenReturn(Optional.ofNullable(entityResolution));
			final EntityAttributeSchemaContract attribute = mock(EntityAttributeSchemaContract.class);
			when(attribute.getConflictResolutionOverride()).thenReturn(attrOverride);
			when(entity.getAttribute(ATTR)).thenReturn(Optional.of(attribute));
			return entity;
		}

		@Test
		@DisplayName("should emit attribute key when inherited granularity set contains the refinement")
		void shouldEmitAttributeKeyWhenInheritedSetContainsIt() {
			final ConflictGenerationContext context = schemaAware(
				new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)),
				null,
				entityWithAttributeOverride(null, ConflictResolutionOverride.INHERITED)
			);

			context.withEntityType("Product", 1, ctx -> {
				assertTrue(ctx.shouldEmitEntityAttributeKey(ATTR));
				return null;
			});
		}

		@Test
		@DisplayName("should not emit attribute key when inherited granularity set lacks the refinement")
		void shouldNotEmitAttributeKeyWhenInheritedSetLacksIt() {
			final ConflictGenerationContext context = schemaAware(
				new ConflictResolution(ConflictPolicy.ENTITY),
				null,
				entityWithAttributeOverride(null, ConflictResolutionOverride.INHERITED)
			);

			context.withEntityType("Product", 1, ctx -> {
				assertFalse(ctx.shouldEmitEntityAttributeKey(ATTR));
				return null;
			});
		}

		@Test
		@DisplayName("should emit attribute key when the item override is GRANULAR despite an empty inherited set")
		void shouldEmitAttributeKeyWhenItemOverrideIsGranular() {
			final ConflictGenerationContext context = schemaAware(
				new ConflictResolution(ConflictPolicy.ENTITY),
				null,
				entityWithAttributeOverride(null, ConflictResolutionOverride.GRANULAR)
			);

			context.withEntityType("Product", 1, ctx -> {
				assertTrue(ctx.shouldEmitEntityAttributeKey(ATTR));
				return null;
			});
		}

		@Test
		@DisplayName("should not emit attribute key when the item override is ENTITY despite an active inherited set")
		void shouldNotEmitAttributeKeyWhenItemOverrideIsEntity() {
			final ConflictGenerationContext context = schemaAware(
				new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)),
				null,
				entityWithAttributeOverride(null, ConflictResolutionOverride.ENTITY)
			);

			context.withEntityType("Product", 1, ctx -> {
				assertFalse(ctx.shouldEmitEntityAttributeKey(ATTR));
				return null;
			});
		}

		@Test
		@DisplayName("should suppress a GRANULAR item override when the coarse policy is CATALOG")
		void shouldSuppressGranularItemOverrideUnderCatalogPolicy() {
			// a per-schema catalog-level CATALOG resolution dominates: even a GRANULAR attribute override
			// must not downgrade the catalog-wide serialization to an attribute key
			final ConflictGenerationContext context = schemaAware(
				new ConflictResolution(ConflictPolicy.ENTITY),
				new ConflictResolution(ConflictPolicy.CATALOG),
				entityWithAttributeOverride(null, ConflictResolutionOverride.GRANULAR)
			);

			context.withEntityType("Product", 1, ctx -> {
				assertFalse(ctx.shouldEmitEntityAttributeKey(ATTR));
				assertEquals(ConflictPolicy.CATALOG, ctx.coarsePolicy());
				return null;
			});
		}

		@Test
		@DisplayName("should resolve the coarse policy per entity from the entity schema")
		void shouldResolveCoarsePolicyPerEntity() {
			final EntitySchemaContract entity = mock(EntitySchemaContract.class);
			when(entity.getConflictResolution())
				.thenReturn(Optional.of(new ConflictResolution(ConflictPolicy.COLLECTION)));
			final ConflictGenerationContext context = schemaAware(
				new ConflictResolution(ConflictPolicy.ENTITY), null, entity
			);

			context.withEntityType("Product", 1, ctx -> {
				assertEquals(ConflictPolicy.COLLECTION, ctx.coarsePolicy());
				return null;
			});
		}

		@Test
		@DisplayName("should treat an absent per-item override as INHERITED")
		void shouldTreatAbsentOverrideAsInherited() {
			// the entity schema has no reference/associated-data element registered, so lookups miss and the
			// predicate falls back to the inherited granularity set
			final EntitySchemaContract entity = mock(EntitySchemaContract.class);
			when(entity.getConflictResolution()).thenReturn(
				Optional.of(new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.REFERENCE)))
			);
			when(entity.getReference(REF)).thenReturn(Optional.empty());
			final ConflictGenerationContext context = schemaAware(
				new ConflictResolution(ConflictPolicy.ENTITY), null, entity
			);

			context.withEntityType("Product", 1, ctx -> {
				assertTrue(ctx.shouldEmitReferenceKey(REF));
				return null;
			});
		}

		@Test
		@DisplayName("should resolve to the engine default when neither schema declares a resolution")
		void shouldResolveToEngineDefaultWhenNeitherSchemaDeclaresOne() {
			final ConflictGenerationContext context = schemaAware(
				new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.PRICE)),
				null,
				entityWithAttributeOverride(null, ConflictResolutionOverride.INHERITED)
			);

			context.withEntityType("Product", 1, ctx -> {
				// engine default carries PRICE, so the price key is emitted despite no schema-level resolution
				assertTrue(ctx.shouldEmitPriceKey());
				assertFalse(ctx.shouldEmitEntityAttributeKey(ATTR));
				return null;
			});
		}
	}
}
