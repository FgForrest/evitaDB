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

package io.evitadb.externalApi.api.catalog.dataApi.model;

import io.evitadb.dataType.StripList;
import io.evitadb.externalApi.api.model.ObjectDescriptor;

/**
 * Represents {@link StripList} for entities.
 *
 * Note: this descriptor is meant be template for generated specific entity DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated entity DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface RecordStripDescriptor extends StripListDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.from(StripListDescriptor.THIS)
		.name("*RecordStrip")
		.build();
}
