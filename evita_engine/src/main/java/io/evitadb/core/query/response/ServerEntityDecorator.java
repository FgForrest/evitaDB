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

package io.evitadb.core.query.response;

import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * This class is a server-side model decorator that adds the number of I/O fetches and bytes fetched from underlying
 * storage to the entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ServerEntityDecorator extends EntityDecorator implements EntityFetchAwareDecorator {
	@Serial private static final long serialVersionUID = 3606251189133258706L;

	/**
	 * The count of I/O fetches used to load this entity from underlying storage.
	 */
	private final int ioFetchCount;
	/**
	 * The count of bytes fetched from underlying storage to load this entity and all referenced entities.
	 */
	private final int ioFetchedBytes;
	/**
	 * Memoized ioFetchCount when {@link #getIoFetchCount()} is called for the first time.
	 */
	private int memoizedIoFetchCount = -1;
	/**
	 * Memoized ioFetchedBytes when {@link #getIoFetchedBytes()} is called for the first time.
	 */
	private int memoizedIoFetchedBytes = -1;

	/**
	 * Method allows creating the entityDecorator object with up-to-date schema definition. Data of the entity are kept
	 * untouched.
	 */
	@Nonnull
	public static ServerEntityDecorator decorate(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataValuePredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		return new ServerEntityDecorator(
			entity, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			ioFetchCount, ioFetchedBytes
		);
	}

	/**
	 * Method allows creating the entityDecorator object with up-to-date schema definition. Data of the entity are kept
	 * untouched.
	 */
	@Nonnull
	public static ServerEntityDecorator decorate(
		@Nonnull ServerEntityDecorator entity,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataValuePredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		return new ServerEntityDecorator(
			entity, parentEntity,
			localePredicate, hierarchyPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow,
			ioFetchCount, ioFetchedBytes
		);
	}

	/**
	 * Method allows to create copy of the entity object with up-to-date schema definition. Data of the original
	 * entity are kept untouched.
	 */
	@Nonnull
	public static ServerEntityDecorator decorate(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataValuePredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes,
		@Nullable ReferenceFetcher referenceFetcher
	) {
		return referenceFetcher == null || referenceFetcher == ReferenceFetcher.NO_IMPLEMENTATION ?
			new ServerEntityDecorator(
				entity, entitySchema, parentEntity,
				localePredicate, hierarchyPredicate,
				attributePredicate, associatedDataValuePredicate,
				referencePredicate, pricePredicate,
				alignedNow,
				ioFetchCount, ioFetchedBytes
			)
			:
			new ServerEntityDecorator(
				entity, entitySchema, parentEntity,
				localePredicate, hierarchyPredicate,
				attributePredicate, associatedDataValuePredicate,
				referencePredicate, pricePredicate,
				alignedNow,
				ioFetchCount,
				ioFetchedBytes,
				referenceFetcher
			);
	}

	public ServerEntityDecorator(
		@Nonnull Entity delegate,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		super(
			delegate, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate, attributePredicate, associatedDataPredicate,
			referencePredicate, pricePredicate,
			alignedNow
		);
		this.ioFetchCount = ioFetchCount;
		this.ioFetchedBytes = ioFetchedBytes;
	}

	public ServerEntityDecorator(
		@Nonnull ServerEntityDecorator delegate,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		super(
			delegate, parentEntity,
			localePredicate, hierarchyPredicate, attributePredicate, associatedDataPredicate,
			referencePredicate, pricePredicate,
			alignedNow
		);
		this.ioFetchCount = ioFetchCount;
		this.ioFetchedBytes = ioFetchedBytes;
	}

	public ServerEntityDecorator(
		@Nonnull ServerEntityDecorator entity,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		super(entity, parentEntity, referenceFetcher);
		this.ioFetchCount = entity.getIoFetchCount();
		this.ioFetchedBytes = entity.getIoFetchedBytes();
	}

	public ServerEntityDecorator(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		int ioFetchCount,
		int ioFetchedBytes,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		super(
			entity, entitySchema, parentEntity,
			localePredicate, hierarchyPredicate, attributePredicate, associatedDataPredicate,
			referencePredicate, pricePredicate,
			alignedNow, referenceFetcher
		);
		this.ioFetchCount = ioFetchCount;
		this.ioFetchedBytes = ioFetchedBytes;
	}

	public ServerEntityDecorator(
		@Nonnull Entity delegate,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parent,
		@Nonnull EvitaRequest evitaRequest,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		super(delegate, entitySchema, parent, evitaRequest);
		this.ioFetchCount = ioFetchCount;
		this.ioFetchedBytes = ioFetchedBytes;
	}

	@Override
	public int getIoFetchCount() {
		if (this.memoizedIoFetchCount == -1) {
			this.memoizedIoFetchCount = this.ioFetchCount +
				(
					parentAvailable() ?
						getParentEntity()
							.filter(ServerEntityDecorator.class::isInstance)
							.map(ServerEntityDecorator.class::cast)
							.map(ServerEntityDecorator::getIoFetchCount)
							.orElse(0)
						:
						0
				) +
				(
					referencesAvailable() ?
						getReferences().stream()
							.map(ReferenceContract::getReferencedEntity)
							.filter(Optional::isPresent)
							.map(Optional::get)
							.map(ServerEntityDecorator.class::cast)
							.mapToInt(ServerEntityDecorator::getIoFetchedBytes)
							.sum()
						:
						0
				);
		}
		return this.memoizedIoFetchCount;
	}

	@Override
	public int getIoFetchedBytes() {
		if (this.memoizedIoFetchedBytes == -1) {
			this.memoizedIoFetchedBytes = this.ioFetchedBytes +
				(
					parentAvailable() ?
						getParentEntity()
							.filter(ServerEntityDecorator.class::isInstance)
							.map(ServerEntityDecorator.class::cast)
							.map(ServerEntityDecorator::getIoFetchCount)
							.orElse(0)
						:
						0
				) +
				(
					referencesAvailable() ?
						getReferences().stream()
							.map(ReferenceContract::getReferencedEntity)
							.filter(Optional::isPresent)
							.map(Optional::get)
							.map(ServerEntityDecorator.class::cast)
							.mapToInt(ServerEntityDecorator::getIoFetchedBytes)
							.sum()
						:
						0
				);
		}
		return this.memoizedIoFetchedBytes;
	}
}
