/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.observability.trace;

import io.evitadb.api.query.head.Label;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Client metadata carried by the headers of an incoming call - who called, what they addressed, and whatever they
 * chose to tag the call with.
 *
 * One shape for every external API surface: {@link JsonApiTracingContext} reads it from Armeria's request headers and
 * {@link GrpcTracingContext} from the gRPC call metadata, but both publish the same three values into the same MDC
 * keys, so both describe them with the same record. The header *reading* stays per-surface - `RequestHeaders` and
 * gRPC `Metadata` are genuinely different shapes - while the vocabulary and the label syntax are shared here.
 *
 * Which header names each surface consults is the surface's own business and deliberately not described here.
 *
 * @param clientIpAddress the caller's IP address, or null when the call carries no header naming it
 * @param clientUri       the URI the caller addressed, or null when the call carries no header naming it
 * @param labels          client-provided labels, empty when the call carries none
 */
record ClientMetadata(
	@Nullable String clientIpAddress,
	@Nullable String clientUri,
	@Nonnull Label[] labels
) {

	/**
	 * Parses one value of a configured label header into a {@link Label}.
	 *
	 * The syntax is `name=value`, split on the **first** separator only, so a value may itself contain `=`. A header
	 * with no separator, and one that begins with it, carry no usable label name and yield null: a label named by the
	 * empty string is not something a traffic recording can ever be filtered by.
	 *
	 * @param header one raw value of a label header
	 * @return the parsed label, or null when the header carries no usable label name
	 */
	@Nullable
	static Label parseLabel(@Nonnull String header) {
		final int separator = header.indexOf('=');
		if (separator <= 0) {
			return null;
		}
		return new Label(header.substring(0, separator), header.substring(separator + 1));
	}

}
