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

package io.evitadb.externalApi.rest.api.system.model;

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureCriteriaDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;

/**
 * Descriptor for {@link ChangeSystemCaptureRequest}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface ChangeSystemCaptureRequestDescriptor {

	PropertyDescriptor SINCE_VERSION = PropertyDescriptor.builder()
		.name("sinceVersion")
		.description("""
            Specifies the initial capture point (engine version) for the system CDC stream, if not specified
            it is assumed to begin at the most recent available version.
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
            Optional list of criteria of the system capture stream, OR-ed together. Each criterion
            currently selects a single `SystemCaptureArea` (`ENGINE` or `HOST`).

            **Default-criteria divergence vs the catalog stream:** when this property is not
            provided (or is `null`), the subscription is treated as `ENGINE`-only —
            `HOST` events (host-local, non-replicable, live-tail-only) are
            **never** delivered without an explicit criteria element opting into them.
            The catalog stream defaults to all areas; the system stream defaults to
            engine-only because `HOST` here carries semantics that existing
            clients have not opted in to.
            """)
		.type(nullableListRef(ChangeSystemCaptureCriteriaDescriptor.THIS))
		.build();
	PropertyDescriptor CONTENT = PropertyDescriptor.builder()
		.name("content")
		.description("""
             Specifies the requested content of the capture, by default, only the header information is sent
             """)
		.type(nullable(ChangeCaptureContent.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.representedClass(ChangeSystemCaptureRequest.class)
		.description("""
             Record describing the capture request for the CDC stream of `ChangeSystemCapture`.
             The request contains the recipe for the messages that the subscriber is interested in, and that are sent to it by the CDC stream.
             """)
		.staticProperty(SINCE_VERSION)
		.staticProperty(SINCE_INDEX)
		.staticProperty(CRITERIA)
		.staticProperty(CONTENT)
		.build();
}
