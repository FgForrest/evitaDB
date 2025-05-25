/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.mock.MockCatalogStructuralChangeSubscriber;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EvitaSchemaCallbackTest implements EvitaTestSupport {
	public static final String DIR_EVITA_NOTIFICATION_TEST = "evitaNotificationTest";
	public static final String DIR_EVITA_NOTIFICATION_TEST_EXPORT = "evitaNotificationTest_export";
	private Evita evita;
	private final MockCatalogStructuralChangeSubscriber subscriber = new MockCatalogStructuralChangeSubscriber();

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_NOTIFICATION_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_NOTIFICATION_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.registerSystemChangeCapture(new ChangeSystemCaptureRequest(ChangeCaptureContent.BODY)).subscribe(this.subscriber);
		// todo lho implement
//		evita.updateCatalog(
//			TEST_CATALOG, session -> {
//				session.registerChangeCatalogCapture(new ChangeCatalogCaptureRequest(CaptureArea.SCHEMA, null, CaptureContent.BODY, 0L), subscriber);
//			}
//		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_NOTIFICATION_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_NOTIFICATION_TEST_EXPORT);
	}

	@Test
	void shouldNotifyCallbackAboutCatalogCreation() {
		this.evita.defineCatalog("newCatalog");

		assertEquals(1, this.subscriber.getCatalogUpserted("newCatalog"));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogDelete() {
		this.evita.deleteCatalogIfExists(TEST_CATALOG);

		assertEquals(1, this.subscriber.getCatalogDeleted(TEST_CATALOG));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogSchemaUpdate() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema()
				.openForWrite()
				.withAttribute("newAttribute", int.class)
				.updateVia(session);
		});

		assertEquals(1, this.subscriber.getCatalogSchemaUpdated(TEST_CATALOG));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogSchemaDescriptionChange() {
		this.evita.update(
			new ModifyCatalogSchemaMutation(
				TEST_CATALOG,
				new ModifyCatalogSchemaDescriptionMutation("Brand new description.")
			)
		);

		assertEquals(1, this.subscriber.getCatalogSchemaUpdated(TEST_CATALOG));

		assertEquals(
			"Brand new description.",
			this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG).getSchema().getDescription()
		);
	}

	@Test
	void shouldNotifyCallbackAboutEntitySchemaDescriptionChange() {
		this.evita.update(
			new ModifyCatalogSchemaMutation(
				TEST_CATALOG,
				new CreateEntitySchemaMutation(Entities.PRODUCT),
				new ModifyEntitySchemaMutation(
					Entities.PRODUCT,
					new ModifyEntitySchemaDescriptionMutation("Brand new description.")
				)
			)
		);

		assertEquals(
			"Brand new description.",
			this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG).getEntitySchema(Entities.PRODUCT).orElseThrow().getDescription()
		);

		assertEquals(1, this.subscriber.getEntityCollectionCreated(TEST_CATALOG, Entities.PRODUCT));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogSchemaUpdateInTransactionalMode() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema()
				.openForWrite()
				.withAttribute("newAttribute", int.class)
				.updateVia(session);
		});

		assertEquals(1, this.subscriber.getCatalogSchemaUpdated(TEST_CATALOG));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionCreate() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("newEntity");
		});

		assertEquals(1, this.subscriber.getEntityCollectionCreated(TEST_CATALOG, "newEntity"));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionCreateInTransactionalMode() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("newEntity");
		});

		assertEquals(1, this.subscriber.getEntityCollectionCreated(TEST_CATALOG, "newEntity"));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionDelete() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);
		});

		this.subscriber.reset();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		assertEquals(1, this.subscriber.getEntityCollectionDeleted(TEST_CATALOG, Entities.PRODUCT));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionDeleteInTransactionalMode() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);
			session.goLiveAndClose();
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		assertEquals(1, this.subscriber.getEntityCollectionDeleted(TEST_CATALOG, Entities.PRODUCT));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionSchemaUpdate() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);
		});

		this.subscriber.reset();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withAttribute("code", String.class)
				.updateVia(session);
		});

		assertEquals(1, this.subscriber.getEntityCollectionSchemaUpdated(TEST_CATALOG, Entities.PRODUCT));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionSchemaUpdateInTransactionalMode() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);
			session.goLiveAndClose();
		});

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withAttribute("code", String.class)
				.updateVia(session);
		});

		assertEquals(1, this.subscriber.getEntityCollectionSchemaUpdated(TEST_CATALOG, Entities.PRODUCT));
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return getEvitaConfiguration(-1);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(int inactivityTimeoutInSeconds) {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_EVITA_NOTIFICATION_TEST))
					.exportDirectory(getTestDirectory().resolve(DIR_EVITA_NOTIFICATION_TEST_EXPORT))
					.build()
			)
			.build();
	}

}
