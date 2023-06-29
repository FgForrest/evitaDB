/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.filter.translator;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.bitmap.ArrayBitmap;
import lombok.Getter;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Mock implementation of the {@link FilterByVisitor} that is used in alternative predicate tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class TestFilterByVisitor extends FilterByVisitor {
	@Getter private final EntitySchemaContract schema;
	@Getter private final EvitaRequest evitaRequest;
	private final Map<Integer, Entity> entities;

	public TestFilterByVisitor(CatalogSchemaContract catalogSchema, EntitySchemaContract entitySchema, Query query, Map<Integer, Entity> entities) {
		super(
			new ProcessingScope(
				Collections.emptyList(),
				AttributeContent.ALL_ATTRIBUTES,
				entitySchema, null, null,
				new AttributeSchemaAccessor(catalogSchema, entitySchema),
				(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale))
			),
			Mockito.mock(QueryContext.class),
			Collections.emptyList(),
			Mockito.mock(TargetIndexes.class),
			false
		);
		this.schema = entitySchema;
		this.evitaRequest = new EvitaRequest(
			query,
			OffsetDateTime.now(),
			EntityReference.class,
			EvitaRequest.CONVERSION_NOT_SUPPORTED
		);
		this.entities = entities;
	}

	@Nonnull
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
	}

	@Nullable
	@Override
	public List<EntityDecorator> getPrefetchedEntities() {
		return this.entities.values().stream().map(
			it -> Entity.decorate(
				it,
				getSchema(),
				null,
				new LocaleSerializablePredicate(evitaRequest),
				new AttributeValueSerializablePredicate(evitaRequest),
				new AssociatedDataValueSerializablePredicate(evitaRequest),
				new ReferenceContractSerializablePredicate(evitaRequest),
				new PriceContractSerializablePredicate(evitaRequest, (Boolean) null),
				evitaRequest.getAlignedNow(),
				null
			)
		).toList();
	}

	@Override
	public int translateEntity(@Nonnull EntityContract entity) {
		return entity.getPrimaryKey();
	}
}
