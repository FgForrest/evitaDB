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

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CShell is a wrapper around the C# query validator executable. It downloads the executable if it is not present in the
 * temporary folder and provides a method for executing C# code and fetching its results. The results are returned as a
 * string messages from which the actual results are parsed.
 */
public class CShell {
    /**
     * URL of the latest version of C# query validator executable.
     */
    private static final String VALIDATOR_ZIP_URL = "https://github.com/FgForrest/evitaDB-C-Sharp-client/releases/download/latest/Validator"+(isWindows() ? "-win" : "")+".zip";
    /**
     * Path to the C# query validator folder locates in a system temp folder.
     */
    private static final Path VALIDATOR_TEMP_FOLDER_PATH = Path.of(System.getProperty("java.io.tmpdir"), "evita-query-validator");
    /**
     * Path to the C# query validator executable.
     */
    private static final String VALIDATOR_PATH = Path.of(VALIDATOR_TEMP_FOLDER_PATH.toString(), "Validator" + getExtensionForOs()).toString();
    /**
     * Regex that removes any control characters from the C# code (characters which starts with `\`).
     */
    private static final Pattern COMPILE = Pattern.compile("\\p{C}");

    /**
     * Constructor for CShell. It downloads the C# query validator executable if it is not present in the temporary folder.
     */
    public CShell() {
        if (!Files.exists(Paths.get(VALIDATOR_PATH))) {
            downloadValidator();
        }
    }

    /**
     * Method for executing C# code and fetching its results.
     * @param command C# code that should be executed
     * @param outputFormat should contain `json` or `md` based on the expected output format
     * @param sourceVariable specifies JSON path to the starting point (inner json structure) of the results that should be verified
     * @return string representation of the results
     * @throws CsharpExecutionException if the C# code throws an exception
     * @throws CsharpCompilationException if the C# code fails to compile
     */
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

    /**
     * Method for downloading the latest, os-specific version of C# query validator executable.
     */
    private static void downloadValidator() {
        if (!VALIDATOR_TEMP_FOLDER_PATH.toFile().exists()) {
            try {
                Files.createDirectory(VALIDATOR_TEMP_FOLDER_PATH);
            } catch (IOException e) {
                throw new EvitaInternalError("Failed to create temporary folder for C# query validator.");
            }
        }
        final Path zipPath = Paths.get(VALIDATOR_TEMP_FOLDER_PATH.toString(), "Validator.zip");

        try (InputStream in = new URL(VALIDATOR_ZIP_URL).openStream()) {
            Files.copy(in, Paths.get(zipPath.toUri()), StandardCopyOption.REPLACE_EXISTING);
            unzip(zipPath.toString(), VALIDATOR_TEMP_FOLDER_PATH.toString());
            if (!isWindows()) {
                setExecutablePermission();
            }
        } catch (IOException ex) {
            throw new EvitaInternalError("Failed to download C# query validator.");
        }
    }

    /**
     * Method for unzipping downloaded C# query validator executable archive.
     * @param zipPath path to the zip file that should be unzipped
     * @param destDir path to the destination directory where the zip file should be unzipped
     * @throws IOException if the archive cannot be unzipped
     */
    private static void unzip(@Nonnull String zipPath, @Nonnull String destDir) throws IOException {
        final File dir = new File(destDir);
        // create output directory if it doesn't exist
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        final FileInputStream fis;
        //buffer for read and write data to file
        final byte[] buffer = new byte[1024];
        try {
            fis = new FileInputStream(zipPath);
            final ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();
            while (ze != null) {
                final String fileName = ze.getName();
                final File newFile = new File(destDir + File.separator + fileName);
                //create directories for subdirectories in zip
                //noinspection ResultOfMethodCallIgnored
                new File(newFile.getParent()).mkdirs();
                final FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                //close this ZipEntry
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            //close last ZipEntry
            zis.closeEntry();
            zis.close();
            fis.close();
        } catch (IOException e) {
            throw new EvitaInternalError("Failed to unzip C# query validator executable.");
        }
    }

    /**
     * On Unix-based systems, it's necessary to set executable permission on the downloaded C# query validator executable.
     */
    private static void setExecutablePermission() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("chmod", "+x", VALIDATOR_PATH);
            processBuilder.start();
        } catch (IOException e) {
            throw new EvitaInternalError("Failed to set executable permission on C# query validator executable.");
        }
    }

    /**
     * Method for creating {@link ProcessBuilder} for executing C# query validator executable.
     * @param command C# code that should be executed
     * @param outputFormat should contain `json` or `md` based on the expected output format
     * @param sourceVariable specifies JSON path to the starting point (inner json structure) of the results that should be verified
     * @return properly set up {@link ProcessBuilder} for executing C# query validator executable that redirects errors
     * encountered during the execution to the standard output
     */
    @Nonnull
    private static ProcessBuilder getProcessBuilder(@Nonnull String command, @Nonnull String outputFormat, @Nullable String sourceVariable) {
        final ProcessBuilder processBuilder;
        final String commandToSend = COMPILE.matcher(isWindows() ? command.replace("\"", "\\\"") : command).replaceAll("");
        if (sourceVariable == null) {
            processBuilder = new ProcessBuilder(VALIDATOR_PATH, commandToSend, outputFormat);
        } else {
            processBuilder = new ProcessBuilder(VALIDATOR_PATH, commandToSend, outputFormat, sourceVariable);
        }

        // This is here for debugging purposes of future added tests

		/*System.out.println("\""+commandToSend+"\"" + "\n");
		System.out.println("\""+outputFormat+"\"" + "\n");
		if (sourceVariable != null) {
			System.out.println("\""+sourceVariable+"\"" + "\n");
		}*/

        // Redirect the standard output to be read by the Java application
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }

    /**
     * Upon terminating CShell instance, nothing should happen here, since the C# query validator executable is shared
     * and reused between tests. Therefore, we clear the downloaded query validator in CsharpExecutable static constructor.
     */
    public void close() {
        // do nothing, we clear the downloaded query validator in CsharpExecutable static constructor
    }

    /**
     * Deletes the downloaded C# query validator executable.
     */
    public static void clearDownloadedValidator() {
        try {
            Files.deleteIfExists(Paths.get(VALIDATOR_PATH));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Helper method that returns information whether the current OS is Windows.
     * @return true if the current OS is Windows
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Helper method that adds extension to the C# query validator executable file name based on the current OS.
     * @return correct filename with appropriate extension
     */
    private static String getExtensionForOs() {
        return isWindows() ? ".exe" : "";
    }
}
