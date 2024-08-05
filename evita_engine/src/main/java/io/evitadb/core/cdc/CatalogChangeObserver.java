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

package io.evitadb.core.cdc;

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.CaptureSite;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.Catalog;
import io.evitadb.dataType.ContainerType;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.UUIDUtil;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * TODO JNO - document me
 * TODO JNO - toto je na přepis
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class CatalogChangeObserver {
	private final String catalogName;
	private final Map<UUID, RequestWithObserver> catalogObservers = new ConcurrentHashMap<>();
	private final SchemaOperationObservers schemaObservers = new SchemaOperationObservers();
	private final DataOperationObservers dataObservers = new DataOperationObservers();

	@Nonnull
	public UUID registerObserver(
		@Nonnull Catalog catalog,
		@Nonnull ChangeCatalogCaptureRequest request
	) {
		final UUID uuid = UUIDUtil.randomUUID();
		final RequestWithObserver requestWithObserver = new RequestWithObserver(uuid, request/*, callback*/);
		catalogObservers.put(uuid, requestWithObserver);
		/* TODO JNO - tohle je špatně */
		final ChangeCatalogCaptureCriteria criteria = request.criteria()[0];
		final CaptureArea[] areas = criteria.area() == null ?
			CaptureArea.values() : new CaptureArea[]{criteria.area()};
		for (CaptureArea area : areas) {
			switch (area) {
				case SCHEMA -> schemaObservers.registerObserver(catalog, criteria, requestWithObserver);
				case DATA -> dataObservers.registerObserver(catalog, criteria, requestWithObserver);
			}
		}
		return uuid;
	}

	public boolean unregisterObserver(@Nonnull UUID uuid) {
		return catalogObservers.remove(uuid) != null;
	}

	public void notifyObservers(
		@Nonnull Operation operation,
		@Nonnull CaptureArea captureArea,
		@Nonnull String entityType,
		@Nonnull Integer entityTypePk,
		@Nullable Integer entityPk,
		@Nullable Integer version,
		@Nonnull Mutation mutation,
		@Nonnull CatalogChangeCaptureBlock captureBlock
	) {
		switch (captureArea) {
			case SCHEMA ->
				schemaObservers.notifyObservers(catalogName, operation, entityType, entityTypePk, version, mutation, captureBlock);
			case DATA ->
				dataObservers.notifyObservers(catalogName, operation, entityType, entityTypePk, version, entityPk, mutation, captureBlock);
		}
	}

	// todo jno: reimplement
//	@Nullable
//	public ChangeDataCaptureObserver getObserver(@Nonnull UUID uuid) {
//		return catalogObservers.get(uuid).observer();
//	}

	private record RequestWithObserver(
		@Nonnull UUID uuid,
		@Nonnull ChangeCatalogCaptureRequest request
		// todo jno: reimplement
//		@Nonnull ChangeDataCaptureObserver observer
	) {

	}

	private static class SchemaOperationObservers {
		private final EntityTypeSchemaObservers createObservers = new EntityTypeSchemaObservers();
		private final EntityTypeSchemaObservers updateObservers = new EntityTypeSchemaObservers();
		private final EntityTypeSchemaObservers removeObservers = new EntityTypeSchemaObservers();

		public void registerObserver(
			@Nonnull Catalog catalog,
			@Nonnull ChangeCatalogCaptureCriteria criteria,
			@Nonnull RequestWithObserver requestWithObserver
		) {
			final CaptureSite site = criteria.site() == null ? SchemaSite.ALL : criteria.site();
			if (site instanceof SchemaSite schemaSite) {
				final Operation[] operations = ArrayUtils.isEmpty(schemaSite.operation()) ?
					Operation.values() : schemaSite.operation();

				for (Operation operation : operations) {
					switch (operation) {
						case UPSERT -> createObservers.registerObserver(catalog, schemaSite, requestWithObserver);
						// case UPSERT -> updateObservers.registerObserver(catalog, schemaSite, requestWithObserver);
						case REMOVE -> removeObservers.registerObserver(catalog, schemaSite, requestWithObserver);
					}
				}
			} else {
				throw new IllegalStateException("Unknown site type: " + site.getClass().getName());
			}
		}

		public void notifyObservers(
			@Nonnull String catalog,
			@Nonnull Operation operation,
			@Nullable String entityType,
			@Nullable Integer entityTypePk,
			@Nullable Integer version,
			@Nonnull Mutation mutation,
			@Nonnull CatalogChangeCaptureBlock captureBlock
		) {
			switch (operation) {
				case UPSERT ->
					createObservers.notifyObservers(catalog, operation, entityType, entityTypePk, version, mutation, captureBlock);
				/*case UPDATE ->
					updateObservers.notifyObservers(catalog, operation, entityType, entityTypePk, version, mutation, captureBlock);*/
				case REMOVE ->
					removeObservers.notifyObservers(catalog, operation, entityType, entityTypePk, version, mutation, captureBlock);
			}
		}
	}

	private static class EntityTypeSchemaObservers {
		private final List<RequestWithObserver> genericObservers = new CopyOnWriteArrayList<>();
		private final Map<Integer, List<RequestWithObserver>> entityTypeObservers = new ConcurrentHashMap<>();

		public void registerObserver(@Nonnull Catalog catalog, @Nonnull SchemaSite site, @Nonnull RequestWithObserver requestWithObserver) {
			if (site.entityType() == null) {
				genericObservers.add(requestWithObserver);
			} else {
				entityTypeObservers.merge(
					catalog.getCollectionForEntityOrThrowException(site.entityType()).getEntityTypePrimaryKey(),
					List.of(requestWithObserver),
					(rwo1, rwo2) -> {
						final List<RequestWithObserver> combined = new ArrayList<>(rwo1);
						combined.addAll(rwo2);
						return combined;
					}
				);
			}
		}

		public void notifyObservers(
			@Nullable String catalog,
			@Nullable Operation operation,
			@Nullable String entityType,
			@Nullable Integer entityTypePk,
			@Nullable Integer version,
			@Nonnull Mutation mutation,
			@Nonnull CatalogChangeCaptureBlock captureBlock
		) {
			ChangeCatalogCapture captureHeader = null;
			ChangeCatalogCapture captureBody = null;
			for (RequestWithObserver observer : genericObservers) {
				final ChangeCatalogCaptureRequest request = observer.request();
				switch (request.content()) {
					case HEADER -> {
						captureHeader = captureHeader == null ?
							// todo jno implement counter
							new ChangeCatalogCapture(0, 0, CaptureArea.SCHEMA, entityType, operation, null) : captureHeader;
						captureBlock.notify(observer.uuid(), captureHeader);
					}
					case BODY -> {
						captureBody = captureBody == null ?
							// todo jno implement counter
							new ChangeCatalogCapture(0, 0, CaptureArea.SCHEMA, entityType, operation, mutation) : captureBody;
						captureBlock.notify(observer.uuid(), captureBody);
					}
				}
			}
			if (entityTypePk != null) {
				final List<RequestWithObserver> observers = entityTypeObservers.get(entityTypePk);
				if (observers != null) {
					for (RequestWithObserver observer : observers) {
						final ChangeCatalogCaptureRequest request = observer.request();
						switch (request.content()) {
							case HEADER -> {
								captureHeader = captureHeader == null ?
									// todo jno implement counter
									new ChangeCatalogCapture(0, 0, CaptureArea.SCHEMA, entityType, operation, null) : captureHeader;
								captureBlock.notify(observer.uuid(), captureHeader);
							}
							case BODY -> {
								captureBody = captureBody == null ?
									// todo jno implement counter
									new ChangeCatalogCapture(0, 0, CaptureArea.SCHEMA, entityType, operation, mutation) : captureBody;
								captureBlock.notify(observer.uuid(), captureBody);
							}
						}
					}
				}
			}
		}
	}

	private static class DataOperationObservers {
		private final EntityTypeDataObservers createObservers = new EntityTypeDataObservers();
		private final EntityTypeDataObservers updateObservers = new EntityTypeDataObservers();
		private final EntityTypeDataObservers removeObservers = new EntityTypeDataObservers();

		public void registerObserver(
			@Nonnull Catalog catalog,
			@Nonnull ChangeCatalogCaptureCriteria criteria,
			@Nonnull RequestWithObserver requestWithObserver
		) {
			final CaptureSite site = criteria.site() == null ? DataSite.ALL : criteria.site();
			if (site instanceof DataSite dataSite) {
				final Operation[] operations = ArrayUtils.isEmpty(dataSite.operation()) ?
					Operation.values() : dataSite.operation();

				for (Operation operation : operations) {
					switch (operation) {
						case UPSERT -> createObservers.registerObserver(catalog, dataSite, requestWithObserver);
						/*case UPDATE -> updateObservers.registerObserver(catalog, dataSite, requestWithObserver);*/
						case REMOVE -> removeObservers.registerObserver(catalog, dataSite, requestWithObserver);
					}
				}
			} else {
				throw new IllegalStateException("Unknown site type: " + site.getClass().getName());
			}
		}

		public void notifyObservers(
			@Nonnull String catalog,
			@Nonnull Operation operation,
			@Nullable String entityType,
			@Nullable Integer entityTypePk,
			@Nullable Integer entityPk,
			@Nullable Integer version,
			@Nonnull Mutation mutation,
			@Nonnull CatalogChangeCaptureBlock captureBlock
		) {
			switch (operation) {
				case UPSERT ->
					createObservers.notifyObservers(catalog, operation, entityType, entityTypePk, entityPk, version, mutation, captureBlock);
				/*case UPDATE ->
					updateObservers.notifyObservers(catalog, operation, entityType, entityTypePk, entityPk, version, mutation, captureBlock);*/
				case REMOVE ->
					removeObservers.notifyObservers(catalog, operation, entityType, entityTypePk, entityPk, version, mutation, captureBlock);
			}
		}
	}

	private static class EntityTypeDataObservers {
		private final ClassifierTypeObserver genericObservers = new ClassifierTypeObserver();
		private final Map<Integer, EntityObserver> entityTypeObservers = new ConcurrentHashMap<>();

		public void registerObserver(
			@Nonnull Catalog catalog,
			@Nonnull DataSite site,
			@Nonnull RequestWithObserver requestWithObserver
		) {
			if (site.entityType() == null) {
				Assert.isTrue(
					site.entityPrimaryKey() == null,
					"Entity primary key is expected to be null for data site without entity type defined!"
				);
				genericObservers.registerObserver(site, requestWithObserver);
			} else {
				entityTypeObservers.computeIfAbsent(
					catalog.getCollectionForEntityOrThrowException(site.entityType()).getEntityTypePrimaryKey(),
					entityTypePk -> new EntityObserver()
				).registerObserver(site, requestWithObserver);
			}
		}

		public void notifyObservers(
			@Nullable String catalog,
			@Nullable Operation operation,
			@Nullable String entityType,
			@Nullable Integer entityTypePk,
			@Nullable Integer entityPk,
			@Nullable Integer version,
			@Nonnull Mutation mutation,
			@Nonnull CatalogChangeCaptureBlock captureBlock
		) {
			genericObservers.notifyObservers(catalog, operation, entityType, version, mutation, captureBlock);
			if (entityTypePk != null) {
				final EntityObserver observer = entityTypeObservers.get(entityTypePk);
				if (observer != null) {
					observer.notifyObservers(catalog, operation, entityType, entityPk, version, mutation, captureBlock);
				}
			}
		}

	}

	private static class EntityObserver {
		private final ClassifierTypeObserver genericObservers = new ClassifierTypeObserver();
		private final Map<Integer, ClassifierTypeObserver> entityObservers = new ConcurrentHashMap<>();

		public void registerObserver(@Nonnull DataSite site, @Nonnull RequestWithObserver requestWithObserver) {
			entityObservers.computeIfAbsent(
				site.entityPrimaryKey(),
				entityPk -> new ClassifierTypeObserver()
			).registerObserver(site, requestWithObserver);
		}

		public void notifyObservers(String catalog, Operation operation, String entityType, Integer entityPk, Integer version, Mutation mutation, CatalogChangeCaptureBlock captureBlock) {
			genericObservers.notifyObservers(catalog, operation, entityType, version, mutation, captureBlock);
			final ClassifierTypeObserver entityObserver = this.entityObservers.get(entityPk);
			if (entityObserver != null) {
				entityObserver.notifyObservers(catalog, operation, entityType, version, mutation, captureBlock);
			}
		}
	}

	private static class ClassifierTypeObserver {
		private final List<RequestWithObserver> genericObservers = new CopyOnWriteArrayList<>();
		private final List<RequestWithObserver> entityObservers = new CopyOnWriteArrayList<>();
		private final List<RequestWithObserver> attributeObservers = new CopyOnWriteArrayList<>();
		private final List<RequestWithObserver> associatedDataObservers = new CopyOnWriteArrayList<>();
		private final List<RequestWithObserver> referenceObservers = new CopyOnWriteArrayList<>();
		private final List<RequestWithObserver> referenceAttributeObservers = new CopyOnWriteArrayList<>();

		public void registerObserver(@Nonnull DataSite site, @Nonnull RequestWithObserver requestWithObserver) {
			final ContainerType[] classifierTypes = ArrayUtils.isEmpty(site.containerType()) ?
				ContainerType.values() : site.containerType();
			for (ContainerType classifierType : classifierTypes) {
				switch (classifierType) {
					case ENTITY -> entityObservers.add(requestWithObserver);
					case ATTRIBUTE -> attributeObservers.add(requestWithObserver);
					case ASSOCIATED_DATA -> associatedDataObservers.add(requestWithObserver);
					case REFERENCE -> referenceObservers.add(requestWithObserver);
					/* TODO JNO - tohle je blbě */
					case PRICE -> referenceAttributeObservers.add(requestWithObserver);
				}
			}
		}

		public void notifyObservers(String catalog, Operation operation, String entityType, Integer version, Mutation mutation, CatalogChangeCaptureBlock captureBlock) {
			final ContainerType containerType = mutation instanceof LocalMutation<?, ?> localMutation ? localMutation.containerType() : ContainerType.ENTITY;
			final AtomicReference<ChangeCatalogCapture> captureHeader = new AtomicReference<>();
			final AtomicReference<ChangeCatalogCapture> captureBody = new AtomicReference<>();

			final Consumer<RequestWithObserver> notify = requestWithObserver -> {
				final ChangeCatalogCaptureRequest request = requestWithObserver.request();
				switch (request.content()) {
					case HEADER -> {
						captureBlock.notify(
							requestWithObserver.uuid(),
							captureHeader.updateAndGet(
								cdc -> cdc == null ?
									// todo jno implement counter
									new ChangeCatalogCapture(0, 0, CaptureArea.DATA, entityType, operation, null) :
									cdc
							)
						);
					}
					case BODY -> {
						captureBlock.notify(
							requestWithObserver.uuid(),
							captureBody.updateAndGet(
								cdc -> cdc == null ?
									// todo jno implement counter
									new ChangeCatalogCapture(0, 0, CaptureArea.DATA, entityType, operation, mutation) :
									cdc
							)
						);
					}
				}
			};

			// notify generic observers first
			genericObservers.forEach(notify);

			// notify observers for specific classifier type
			switch (containerType) {
				case ENTITY -> entityObservers.forEach(notify);
				case ATTRIBUTE -> attributeObservers.forEach(notify);
				case ASSOCIATED_DATA -> associatedDataObservers.forEach(notify);
				case REFERENCE -> referenceObservers.forEach(notify);
				/* TODO JNO - tohle je blbě*/
				case PRICE -> referenceAttributeObservers.forEach(notify);
			}
		}
	}

}
