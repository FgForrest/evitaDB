/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.executor;

import java.util.concurrent.ExecutorService;

/**
 * Interface that extends {@link ExecutorService} with additional methods for observing the state of the executor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ObservableExecutorService extends ExecutorService {

	/**
	 * Returns the number of tasks that have been submitted to the executor to be executed.
	 * @return the number of tasks that have been submitted to the executor to be executed
	 */
	long getSubmittedTaskCount();

	/**
	 * Returns the number of tasks that have been rejected by the executor due to full queues.
	 * @return the number of tasks that have been rejected by the executor due to full queues
	 */
	long getRejectedTaskCount();

}
