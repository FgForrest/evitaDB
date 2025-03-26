/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Descriptor of header arguments of field {@link ResponseDescriptor#RECORD_PAGE}
 */
public interface RecordPageFieldHeaderDescriptor extends PaginatedListFieldHeaderDescriptor {

	PropertyDescriptor SPACING = PropertyDescriptor.builder()
		.name("spacing")
		.description("""
			Allows to insert artificial gaps instead of entities on particular pages. The gaps are defined by the
			spacing sub-constraints, which specify the number of entities that should be skipped on the page when the
			`onPage` expression is evaluated to true.
			""")
		.build();
}
