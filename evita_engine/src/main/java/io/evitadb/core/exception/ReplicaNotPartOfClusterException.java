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

package io.evitadb.core.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * This exception is thrown when the current replica is not part of the configured cluster members.
 * It contains details about the current replica's address and the list of configured cluster members.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReplicaNotPartOfClusterException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -1519279571370742061L;

	/**
	 * Constructor that creates the exception with detailed private message and generic public message.
	 *
	 * @param inetAddress    address of the current replica
	 * @param clusterMembers list of configured cluster members
	 */
	public ReplicaNotPartOfClusterException(
		@Nonnull InetAddress inetAddress,
		@Nonnull InetAddress[] clusterMembers
	) {
		super(
			"InetAddress '" + inetAddress + "' is not mentioned in configured cluster members: " +
				Arrays.toString(clusterMembers) + ".",
			"The current replica is not part of the configured cluster."
		);
	}
}
