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

package io.evitadb.api.requestResponse.cdc;

import javax.annotation.Nonnull;

/**
 * Record describing the capture request for the {@link ChangeCapturePublisher} of {@link ChangeSystemCapture}s.
 * The request contains the recipe for the messages that the subscriber is interested in, and that are sent to it by
 * {@link ChangeCapturePublisher}.
 *
 * @param content the requested content of the capture, by default only the header information is sent
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record ChangeSystemCaptureRequest(
	@Nonnull ChangeCaptureContent content
) implements ChangeCaptureRequest {
}
