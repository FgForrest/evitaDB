/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.cdc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Record describing the capture request for the {@link ChangeSystemCaptureSubscriber}. The request contains the recipe
 * for the messages that the subscriber is interested in and that are sent to it by {@link ChangeSystemCaptureSubscriber#onNext(ChangeSystemCapture)}
 * method.
 *
 * @param id                 unique identifier of the subscriber, id must be unique among all subscribers and must change when
 *                           the instance of the subscriber is finalized and a new instance is created
 * @param area               the requested area of the capture
 * @param site               the filter for the events to be sent, limits the amount of events sent to the subscriber
 * @param content            the requested content of the capture, by default only the header information is sent
 * @param sinceTransactionId specifies the initial capture point for the CDC stream, it must always provide a last
 *                           known transaction id from the client point of view
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record ChangeDataCaptureRequest(
	/* TODO TPO - this was added */
	@Nonnull UUID id,
	@Nullable CaptureArea area,
	@Nullable CaptureSite site,
	@Nonnull CaptureContent content,
	long sinceTransactionId
) {
}
