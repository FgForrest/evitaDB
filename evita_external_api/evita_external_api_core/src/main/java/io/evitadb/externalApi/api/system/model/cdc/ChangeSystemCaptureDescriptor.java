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

package io.evitadb.externalApi.api.system.model.cdc;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.cdc.ChangeCaptureDescriptor;

import java.util.List;

/**
 * Descriptor interface for system-wide Change Data Capture (CDC) events. Extends the base
 * {@link ChangeCaptureDescriptor} to define the structure of CDC events that occur at the
 * system level, affecting the entire evitaDB instance rather than specific catalogs.
 * These events typically include system-wide configuration changes and administrative operations.
 *
 * @author Lukáš Hornych, 2023
 */
public interface ChangeSystemCaptureDescriptor extends ChangeCaptureDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ChangeSystemCapture")
		.description("""
            Record represents a system-wide CDC event that is sent to the subscriber if it matches to the request he made.
			""")
		.staticFields(List.of(VERSION, INDEX, OPERATION))
		.build();
}
