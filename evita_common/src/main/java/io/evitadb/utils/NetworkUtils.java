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

package io.evitadb.utils;

import javax.annotation.Nonnull;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Utility class for network related operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class NetworkUtils {

	/**
	 * Returns human comprehensible host name of the given host.
	 *
	 * @param host host to get the name for
	 * @return human comprehensible host name of the given host
	 */
	@Nonnull
	public static String getHostName(@Nonnull InetAddress host) {
		try {
			return host.isAnyLocalAddress() ? InetAddress.getLocalHost().getHostName() : host.getCanonicalHostName();
		} catch (UnknownHostException ignored) {
			return host.getCanonicalHostName();
		}
	}

	/**
	 * Returns human comprehensible host name of the local host.
	 *
	 * @return human comprehensible host name of the local host
	 */
	@Nonnull
	public static String getLocalHostName() {
		try {
			return getHostName(InetAddress.getLocalHost());
		} catch (UnknownHostException e) {
			return InetAddress.getLoopbackAddress().getCanonicalHostName();
		}
	}

}
