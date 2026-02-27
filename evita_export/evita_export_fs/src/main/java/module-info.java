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

/**
 * The implementation of evitaDB export service on local file system.
 */
module evita.export.fs {

	exports io.evitadb.export.file;

	opens io.evitadb.export.file to com.fasterxml.jackson.databind;
	exports io.evitadb.export.file.configuration;
	opens io.evitadb.export.file.configuration to com.fasterxml.jackson.databind;

	provides io.evitadb.spi.export.ExportServiceFactory with io.evitadb.export.file.ExportFileServiceFactory;

	requires static lombok;

	requires evita.api;
	requires evita.engine;
	requires evita.common;
	requires jsr305;
	requires org.slf4j;

}
