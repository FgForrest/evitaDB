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

package io.evitadb.externalApi.api.system.model.cdc;

import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;

/**
 * API-independent reference to the {@link SystemCaptureArea} enum exposed on the system CDC
 * stream. Kept as a thin marker so the same enum type can be referenced from both REST and
 * GraphQL descriptors without duplicating its identity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface SystemCaptureAreaDescriptor {

	/**
	 * Reference to the underlying enum class. External API builders use this to register
	 * the enum type once and reference it consistently from {@link ChangeSystemCaptureCriteriaDescriptor}.
	 */
	Class<SystemCaptureArea> REPRESENTED_CLASS = SystemCaptureArea.class;

	/**
	 * The name under which the enum type is registered in the API schemas. Frozen contract
	 * shared by REST and GraphQL — do not rename.
	 */
	String TYPE_NAME = "SystemCaptureArea";

}
