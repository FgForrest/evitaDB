/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;

/**
 * Represents base {@link StripList} for entities.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface RecordStripDescriptor extends StripListDescriptor {

	PropertyDescriptor DATA = PropertyDescriptor.builder()
		.name("data")
		.description("""
			Actual found sorted page/strip of records.
			""")
		.type(nonNullListRef(EntityDescriptor.THIS_CLASSIFIER))
		.build();

	ObjectDescriptor THIS_INTERFACE = ObjectDescriptor.implementing(StripListDescriptor.THIS_INTERFACE)
		.name("RecordStrip")
		.description("Strip of entity records according to pagination rules in input query.")
		.staticProperty(DATA)
		.build();
}
