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

package io.evitadb.test;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.driver.EvitaClient;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Example of the test with database filled with initial data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ExtendWith(EvitaParameterResolver.class)
public class PrefilledDataSetWebApiTest implements EvitaTestSupport {
	private static final String DATA_SET_WITH_A_FEW_DATA = "dataSetWebApiWithAFewData";
	private static final String ENTITY_BRAND = "Brand";

	@DataSet(
		value = DATA_SET_WITH_A_FEW_DATA,
		openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}
	)
	void setUpData(EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND);
		session.createNewEntity(ENTITY_BRAND)
			.setAttribute(
				"name", Locale.ENGLISH, "Siemens"
			)
			.upsertVia(session);
	}

	@Test
	@UseDataSet(DATA_SET_WITH_A_FEW_DATA)
	void shouldWriteTest(EvitaClient client, String catalogName) {
		client.queryCatalog(
			catalogName,
			session -> {
				assertEquals(1, session.getEntityCollectionSize(ENTITY_BRAND));

				final SealedEntity theBrand = session.getEntity(
					ENTITY_BRAND, 1,
					attributeContentAll(),
					dataInLocales(Locale.ENGLISH)
				).orElseThrow();

				assertEquals("Siemens", theBrand.getAttribute("name"));
			}
		);
	}

}
