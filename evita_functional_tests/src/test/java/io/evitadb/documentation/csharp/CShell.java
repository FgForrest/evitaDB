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

package io.evitadb.documentation.csharp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

public class CShell {
	private static final String VALIDATOR_PATH = "src/test/resources/META-INF/documentation/Validator"+(System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "");
	private static final Pattern COMPILE = Pattern.compile("\\p{C}");

	public CShell(String validatorUrl) {
		if (!Files.exists(Paths.get(VALIDATOR_PATH))) {
			downloadValidator(validatorUrl);
		}
	}

	@Nonnull
	public String evaluate(String command, @Nonnull String outputFormat, @Nullable String sourceVariable) throws CsharpExecutionException, CsharpCompilationException {
		final ProcessBuilder processBuilder = getProcessBuilder(command, outputFormat, sourceVariable);
		final StringBuilder actualOutput = new StringBuilder(64);
		try {
			// Start the process
			final Process process = processBuilder.start();

			// Read the output of the process
			final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.contains("Error") || line.contains("Exception")) {
					throw new CsharpExecutionException(line);
				} else {
					actualOutput.append(line).append("\n");
				}
			}

			if (!actualOutput.isEmpty()) {
				actualOutput.setLength(actualOutput.length() - "\n".length());
			}

			// Wait for the process to complete
			try {
				int exitCode = process.waitFor();
				if (exitCode != 0) {
					throw new CsharpCompilationException("Compilation failed");
				}
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
		} catch (UnsupportedOperationException | IOException e) {
			throw new IllegalStateException(e);
		}
		return actualOutput.toString();
	}

	private static void downloadValidator(String url) {
		final Path appPath = Paths.get(VALIDATOR_PATH);

		try (InputStream in = new URL(url).openStream()) {
			Files.copy(in, Paths.get(appPath.toUri()), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			System.out.println(ex.getMessage());
		}
	}

	@Nonnull
	private static ProcessBuilder getProcessBuilder(@Nonnull String command, @Nonnull String outputFormat, @Nullable String sourceVariable) {
		final ProcessBuilder processBuilder;
		final String commandToSend = COMPILE.matcher(System.getProperty("os.name").toLowerCase().contains("win") ? command.replace("\"", "\\\"") : command).replaceAll("");
		if (sourceVariable == null) {
			processBuilder = new ProcessBuilder(VALIDATOR_PATH, commandToSend, outputFormat);
		} else {
			processBuilder = new ProcessBuilder(VALIDATOR_PATH, commandToSend, outputFormat, sourceVariable);
		}

		System.out.println(commandToSend + "\n");
		System.out.println(outputFormat + "\n");
		if (sourceVariable != null) {
			System.out.println(sourceVariable + "\n");
		}
		// Redirect the standard output to be read by the Java application
		processBuilder.redirectErrorStream(true);
		return processBuilder;
	}

	public void close() {
		//TODO tpo: Should we delete this after we're done?
		/*try {
			Files.deleteIfExists(Paths.get(VALIDATOR_PATH));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}*/
	}
}
