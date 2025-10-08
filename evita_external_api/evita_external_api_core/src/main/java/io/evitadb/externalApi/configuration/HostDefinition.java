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

package io.evitadb.externalApi.configuration;

import javax.annotation.Nonnull;
import java.net.InetAddress;

/**
 * Defines a host and port combination.
 *
 * @param host defines the hostname and port the endpoints will listen on
 * @param localhost contains TRUE if configuration option represents a loop-back host (self)
 * @param port defines the port API endpoint will listen on
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record HostDefinition(
	@Nonnull InetAddress host,
	boolean localhost,
	int port
) {

	/**
	 * Returns IP address as a string.
	 * @return IP address as a string
	 */
	@Nonnull
	public String hostAddress() {
		return this.host.getHostAddress();
	}

	/**
	 * Returns IP address with port as a single string.
	 */
	@Nonnull
	public String hostAddressWithPort() {
		return this.host.getHostAddress() + ":" + this.port;
	}

}
