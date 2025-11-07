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

package io.evitadb.externalApi.rest.api.catalog.cdcApi.model;

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureCriteriaDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;

/**
 * Descriptor for {@link ChangeCatalogCaptureRequest}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface ChangeCatalogCaptureRequestDescriptor {

	PropertyDescriptor SINCE_VERSION = PropertyDescriptor.builder()
		.name("sinceVersion")
		.description("""
			Specifies the initial capture point (catalog version) for the CDC stream, if not specified
			it is assumed to begin at the most recent / oldest available version.
			""")
		.type(nullable(Long.class))
		.build();
	PropertyDescriptor SINCE_INDEX = PropertyDescriptor.builder()
		.name("sinceIndex")
		.description("""
			Specifies the initial capture point for the CDC stream, it is optional and can be used
			to specify continuation point within an enclosing block of events.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor CRITERIA = PropertyDescriptor.builder()
		.name("criteria")
		.description("""
			The criteria of the capture, if not specified - all changes are captured, if multiple are specified
			matching any of them is sufficient (OR).
			""")
		.type(nullableListRef(ChangeCatalogCaptureCriteriaDescriptor.THIS))
		.build();
	PropertyDescriptor CONTENT = PropertyDescriptor.builder()
		.name("content")
		.description("""
             Specifies the requested content of the capture, by default, only the header information is sent
             """)
		.type(nullable(ChangeCaptureContent.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.representedClass(ChangeCatalogCaptureRequest.class)
		.description("""
             Record describing the capture request for the CDC stream of `ChangeCatalogCapture`.
			 The request contains the recipe for the messages that the subscriber is interested in, and that are sent to it by
			 CDC stream.
             """)
		.staticProperty(SINCE_VERSION)
		.staticProperty(SINCE_INDEX)
		.staticProperty(CRITERIA)
		.staticProperty(CONTENT)
		.build();
}
