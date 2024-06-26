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

package io.evitadb.scheduling;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Custom rejecting executor that logs the problem when the queue gets full.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RejectingExecutor implements Executor {
	public static final RejectingExecutor INSTANCE = new RejectingExecutor();

	@Override
	public void execute(@Nonnull Runnable command) {
		log.error("Evita executor queue full. Please add more threads to the pool.");
		throw new RejectedExecutionException("Evita executor queue full. Please add more threads to the pool.");
	}

}
