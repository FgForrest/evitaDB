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

package io.evitadb.core.expression.proxy;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.expression.proxy.ExpressionProxyInstantiator.InstantiationResult;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.spi.store.catalog.persistence.accessor.EntityStoragePartAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPRESSION;
import static io.evitadb.test.TestTags.PROXY;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Evaluates expressions over `$entity.parentEntity` through the proxies the expression triggers use, for a parent in
 * the scope the expression is evaluated in, a parent in another scope, no parent, and no parent on an entity whose own
 * `status` is the one the expression looks for. An expression reading data of the parent - its attributes or its
 * primary key - sees no parent in another scope, as {@link ExpressionProxyInstantiator} promises; a bare existence
 * check reads the parent primary key from the entity body alone, so it sees the parent in another scope as present.
 * An entity without a parent never reads its own data in place of the parent's.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Expression over the parent entity in and out of the evaluated scope")
@Tag(ENGINE)
@Tag(EXPRESSION)
@Tag(PROXY)
class ParentEntityAcrossScopesExpressionTest {

	private static final String ENTITY_TYPE = "product";
	private static final String ATTRIBUTE_STATUS = "status";
	private static final int PRODUCT_PK = 1;
	private static final int PARENT_PK = 2;
	private static final String ATTRIBUTE_READ = "($entity.parentEntity?.attributes['status'] ?? '') == 'ACTIVE'";
	private static final String NULL_SAFE_ATTRIBUTE_READ = "$entity.parentEntity?.attributes['status'] == 'ACTIVE'";
	private static final String EXISTENCE = "$entity.parentEntity != null";
	private static final String EXISTENCE_AND_ATTRIBUTE_READ = EXISTENCE + " && " + ATTRIBUTE_READ;
	private static final String PRIMARY_KEY_READ = "($entity.parentEntity?.primaryKey ?? 0) == " + PARENT_PK;
	private static final String BARE_PRIMARY_KEY_READ = "$entity.parentEntity.primaryKey";
	/**
	 * The outcome of an expression navigating into a parent the entity does not have.
	 */
	private static final String THROWS = "throws ExpressionEvaluationException";
	/**
	 * The hierarchical entity schema of both the product and its parent, with the `status` attribute.
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CatalogSchema._internalBuild(
			"testCatalog", NamingConvention.generate("testCatalog"), null,
			EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
		),
		EntitySchema._internalBuild(ENTITY_TYPE)
	)
		.withHierarchy()
		.withAttribute(ATTRIBUTE_STATUS, String.class, whichIs -> whichIs.nullable())
		.toInstance();

	/**
	 * Where the parent of the product is, relative to the live scope the expression is evaluated in.
	 */
	enum ParentPlacement {
		/**
		 * The active parent is live.
		 */
		SAME_SCOPE,
		/**
		 * The active parent is archived.
		 */
		OTHER_SCOPE,
		/**
		 * The product has no parent.
		 */
		ABSENT,
		/**
		 * The product has no parent and its own `status` is active.
		 */
		ROOT_WITH_OWN_STATUS
	}

	/**
	 * Combines every expression with the placements of the parent and the outcome the expression must produce: its
	 * result, or the exception it throws.
	 *
	 * @return the arguments of {@link #shouldEvaluateParentAsTheEvaluatedScopeSeesIt}
	 */
	@Nonnull
	static Stream<Arguments> cells() {
		return Stream.of(
			Arguments.of(ATTRIBUTE_READ, ParentPlacement.SAME_SCOPE, "true"),
			Arguments.of(ATTRIBUTE_READ, ParentPlacement.OTHER_SCOPE, "false"),
			Arguments.of(ATTRIBUTE_READ, ParentPlacement.ABSENT, "false"),
			Arguments.of(ATTRIBUTE_READ, ParentPlacement.ROOT_WITH_OWN_STATUS, "false"),
			Arguments.of(NULL_SAFE_ATTRIBUTE_READ, ParentPlacement.SAME_SCOPE, "true"),
			Arguments.of(NULL_SAFE_ATTRIBUTE_READ, ParentPlacement.OTHER_SCOPE, "false"),
			Arguments.of(NULL_SAFE_ATTRIBUTE_READ, ParentPlacement.ABSENT, "false"),
			Arguments.of(NULL_SAFE_ATTRIBUTE_READ, ParentPlacement.ROOT_WITH_OWN_STATUS, "false"),
			Arguments.of(EXISTENCE, ParentPlacement.SAME_SCOPE, "true"),
			Arguments.of(EXISTENCE, ParentPlacement.OTHER_SCOPE, "true"),
			Arguments.of(EXISTENCE, ParentPlacement.ABSENT, "false"),
			Arguments.of(EXISTENCE_AND_ATTRIBUTE_READ, ParentPlacement.SAME_SCOPE, "true"),
			Arguments.of(EXISTENCE_AND_ATTRIBUTE_READ, ParentPlacement.OTHER_SCOPE, "false"),
			Arguments.of(EXISTENCE_AND_ATTRIBUTE_READ, ParentPlacement.ABSENT, "false"),
			Arguments.of(PRIMARY_KEY_READ, ParentPlacement.SAME_SCOPE, "true"),
			Arguments.of(PRIMARY_KEY_READ, ParentPlacement.OTHER_SCOPE, "false"),
			Arguments.of(PRIMARY_KEY_READ, ParentPlacement.ABSENT, "false"),
			Arguments.of(BARE_PRIMARY_KEY_READ, ParentPlacement.SAME_SCOPE, String.valueOf(PARENT_PK)),
			Arguments.of(BARE_PRIMARY_KEY_READ, ParentPlacement.OTHER_SCOPE, THROWS),
			Arguments.of(BARE_PRIMARY_KEY_READ, ParentPlacement.ABSENT, THROWS)
		);
	}

	/**
	 * Evaluates `expression` in the live scope for a live product placed under its parent as `placement` says.
	 *
	 * @param expression the expression
	 * @param placement  where the parent is
	 * @param expected   the result the expression must produce, or `throws` and the class of the exception
	 */
	@ParameterizedTest(name = "{0} with the parent {1} is {2}")
	@MethodSource("cells")
	@DisplayName("evaluates the parent as the evaluated scope sees it")
	void shouldEvaluateParentAsTheEvaluatedScopeSeesIt(
		@Nonnull String expression, @Nonnull ParentPlacement placement, @Nonnull String expected
	) {
		final InMemoryStorage storage = new InMemoryStorage();
		final EntityBodyStoragePart product = new EntityBodyStoragePart(PRODUCT_PK);
		switch (placement) {
			case SAME_SCOPE, OTHER_SCOPE -> {
				product.setParent(PARENT_PK);
				final EntityBodyStoragePart parent = new EntityBodyStoragePart(PARENT_PK);
				parent.setScope(placement == ParentPlacement.SAME_SCOPE ? Scope.LIVE : Scope.ARCHIVED);
				storage.bodies.put(PARENT_PK, parent);
				storage.attributes.put(PARENT_PK, activeStatus(PARENT_PK));
			}
			case ROOT_WITH_OWN_STATUS -> storage.attributes.put(PRODUCT_PK, activeStatus(PRODUCT_PK));
			case ABSENT -> {
				// the product has neither a parent nor a status
			}
		}
		storage.bodies.put(PRODUCT_PK, product);

		final Expression parsed = ExpressionFactory.parse(expression);
		final InstantiationResult instantiation = ExpressionProxyInstantiator.instantiate(
			ExpressionProxyFactory.buildDescriptor(parsed), SCHEMA, PRODUCT_PK, null, null, storage,
			entityType -> SCHEMA, Scope.LIVE
		);
		assertEquals(expected, outcomeOf(parsed, instantiation));
	}

	/**
	 * Computes `expression` over the entity proxy of `instantiation`.
	 *
	 * @param expression    the parsed expression
	 * @param instantiation the proxies of the product
	 * @return the result as a string, or `throws` and the simple name of the exception the computation threw
	 */
	@Nonnull
	private static String outcomeOf(@Nonnull Expression expression, @Nonnull InstantiationResult instantiation) {
		try {
			final Serializable result = expression.compute(
				new ExpressionVariableContext(Map.of("entity", instantiation.entityProxy()))
			);
			return String.valueOf(result);
		} catch (RuntimeException ex) {
			return "throws " + ex.getClass().getSimpleName();
		}
	}

	/**
	 * Creates the global attributes of the entity `primaryKey` holding the active `status`.
	 *
	 * @param primaryKey the primary key of the entity
	 * @return the attributes storage part
	 */
	@Nonnull
	private static AttributesStoragePart activeStatus(int primaryKey) {
		final AttributeKey status = new AttributeKey(ATTRIBUTE_STATUS);
		final AttributesStoragePart attributes = new AttributesStoragePart(primaryKey);
		attributes.upsertAttribute(
			status, SCHEMA.getAttribute(ATTRIBUTE_STATUS).orElseThrow(),
			existing -> new AttributeValue(status, "ACTIVE")
		);
		return attributes;
	}

	/**
	 * Storage of the bodies and global attributes of the product and its parent; the expressions read nothing else.
	 */
	private static final class InMemoryStorage implements EntityStoragePartAccessor {
		/**
		 * Entity bodies by primary key.
		 */
		private final Map<Integer, EntityBodyStoragePart> bodies = createHashMap(2);
		/**
		 * Global attributes by primary key.
		 */
		private final Map<Integer, AttributesStoragePart> attributes = createHashMap(2);

		/**
		 * Returns the stored body of the entity and fails when none is stored.
		 *
		 * @param entityType       the entity type
		 * @param entityPrimaryKey the primary key of the entity
		 * @param expects          the expected existence of the entity
		 * @return the stored body
		 */
		@Nonnull
		@Override
		public EntityBodyStoragePart getEntityStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects
		) {
			final EntityBodyStoragePart body = this.bodies.get(entityPrimaryKey);
			if (body == null) {
				throw new IllegalStateException("No body of " + entityType + " " + entityPrimaryKey + " is stored.");
			}
			return body;
		}

		/**
		 * Returns the stored global attributes of the entity, or an empty part when none are stored.
		 *
		 * @param entityType       the entity type
		 * @param entityPrimaryKey the primary key of the entity
		 * @return the global attributes storage part
		 */
		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			final AttributesStoragePart part = this.attributes.get(entityPrimaryKey);
			return part == null ? new AttributesStoragePart(entityPrimaryKey) : part;
		}

		/**
		 * Fails: the expressions read no localized attribute.
		 *
		 * @param entityType       the entity type
		 * @param entityPrimaryKey the primary key of the entity
		 * @param locale           the locale
		 * @return never
		 */
		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull Locale locale
		) {
			throw new UnsupportedOperationException("The expressions read no localized attribute.");
		}

		/**
		 * Fails: the expressions read no associated data.
		 *
		 * @param entityType       the entity type
		 * @param entityPrimaryKey the primary key of the entity
		 * @param key              the associated data key
		 * @return never
		 */
		@Nonnull
		@Override
		public AssociatedDataStoragePart getAssociatedDataStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key
		) {
			throw new UnsupportedOperationException("The expressions read no associated data.");
		}

		/**
		 * Fails: the expressions read no reference.
		 *
		 * @param entityType       the entity type
		 * @param entityPrimaryKey the primary key of the entity
		 * @return never
		 */
		@Nonnull
		@Override
		public ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			throw new UnsupportedOperationException("The expressions read no reference.");
		}

		/**
		 * Fails: the expressions read no price.
		 *
		 * @param entityType       the entity type
		 * @param entityPrimaryKey the primary key of the entity
		 * @return never
		 */
		@Nonnull
		@Override
		public PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			throw new UnsupportedOperationException("The expressions read no price.");
		}
	}
}
