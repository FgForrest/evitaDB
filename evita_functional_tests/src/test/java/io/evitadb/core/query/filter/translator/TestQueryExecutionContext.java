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

package io.evitadb.core.query.filter.translator;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.response.ServerEntityDecorator;
import lombok.Getter;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Mock implementation of the {@link FilterByVisitor} that is used in alternative predicate tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class TestQueryExecutionContext extends QueryExecutionContext {
	@Getter private final EntitySchemaContract schema;
	@Getter private final EvitaRequest evitaRequest;
	private final Map<Integer, SealedEntity> entities;

	public TestQueryExecutionContext(
		CatalogSchemaContract catalogSchema,
		EntitySchemaContract entitySchema,
		Query query,
		Map<Integer, SealedEntity> entities
	) {
		super(Mockito.mock(QueryPlanningContext.class));
		/*super(
			new ProcessingScope<>(
				EntityIndex.class,
				Collections.emptyList(),
				AttributeContent.ALL_ATTRIBUTES,
				entitySchema, null, null,
				new AttributeSchemaAccessor(catalogSchema, entitySchema),
				(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale))
			),
			Mockito.mock(QueryPlanningContext.class),
			Collections.emptyList(),
			Mockito.mock(TargetIndexes.class),
			false
		);*/
		this.schema = entitySchema;
		this.evitaRequest = new EvitaRequest(
			query,
			OffsetDateTime.now(),
			EntityReference.class,
			null,
			EvitaRequest.CONVERSION_NOT_SUPPORTED
		);
		this.entities = entities;
	}

	/* TODO JNO - Jestli to nebude potřeba, tak smazat */
	/*@Nonnull
	@Override
	public Formula getSuperSetFormula() {
		return new ConstantFormula(
			new ArrayBitmap(
				this.entities.keySet().stream().mapToInt(it -> it).toArray()
			)
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract getSchema(@Nonnull String entityType) {
		throw new UnsupportedOperationException();
	}*/

	@Nullable
	@Override
	public List<ServerEntityDecorator> getPrefetchedEntities() {
		return this.entities.values().stream().map(
			it -> ServerEntityDecorator.decorate(
				toEntity(it),
				getSchema(),
				null,
				new LocaleSerializablePredicate(evitaRequest),
				new HierarchySerializablePredicate(evitaRequest),
				new AttributeValueSerializablePredicate(evitaRequest),
				new AssociatedDataValueSerializablePredicate(evitaRequest),
				new ReferenceContractSerializablePredicate(evitaRequest),
				new PriceContractSerializablePredicate(evitaRequest, (Boolean) null),
				evitaRequest.getAlignedNow(),
				0, 0,
				null
			)
		).toList();
	}

	@Nonnull
	private static Entity toEntity(@Nonnull SealedEntity sealedEntity) {
		if (sealedEntity instanceof Entity entity) {
			return entity;
		} else if (sealedEntity instanceof EntityDecorator entityDecorator) {
			return entityDecorator.getDelegate();
		} else {
			throw new IllegalStateException("Unknown entity type");
		}
	}

	@Override
	public int translateEntity(@Nonnull EntityContract entity) {
		return entity.getPrimaryKey();
	}
}
