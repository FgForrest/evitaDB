/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.traffic.serializer;

import io.evitadb.api.requestResponse.trafficRecording.Label;
import io.evitadb.api.requestResponse.trafficRecording.SourceQueryContainer;
import io.evitadb.externalApi.grpc.GrpcProvider;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * This test verifies the correctness of the {@link SourceQueryContainerSerializer} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class SourceQueryContainerSerializerTest extends AbstractContainerSerializerTest {

	@Test
	void shouldSerializeAndDeserializeContainer() {
		assertSerializationRound(
			new SourceQueryContainer(
				UUID.randomUUID(),
				4,
				UUID.randomUUID(),
				OffsetDateTime.now(),
				"queryMe",
				new Label []{ new Label(io.evitadb.api.query.head.Label.LABEL_SOURCE_TYPE, GrpcProvider.CODE) },
				"error"
			)
		);
	}

}