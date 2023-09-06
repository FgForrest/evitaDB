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

public class CShell {
    private static final String VALIDATOR_ZIP_URL = "https://github.com/FgForrest/evitaDB-C-Sharp-client/releases/download/latest/Validator"+(isWindows() ? "-win" : "")+".zip";
    private static final Path VALIDATOR_TEMP_FOLDER_PATH = Path.of(System.getProperty("java.io.tmpdir"), "evita-query-validator");
    private static final String VALIDATOR_PATH = Path.of(VALIDATOR_TEMP_FOLDER_PATH.toString(), "Validator" + getExtensionForOs()).toString();
    private static final Pattern COMPILE = Pattern.compile("\\p{C}");

    public CShell() {
        if (!Files.exists(Paths.get(VALIDATOR_PATH))) {
            downloadValidator();
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

    private static void unzip(String zipPath, String destDir) throws IOException {
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

    private static void setExecutablePermission() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("chmod", "+x", VALIDATOR_PATH);
            processBuilder.start();
        } catch (IOException e) {
            throw new EvitaInternalError("Failed to set executable permission on C# query validator executable.");
        }
    }

    @Nonnull
    private static ProcessBuilder getProcessBuilder(@Nonnull String command, @Nonnull String outputFormat, @Nullable String sourceVariable) {
        final ProcessBuilder processBuilder;
        final String commandToSend = COMPILE.matcher(isWindows() ? command.replace("\"", "\\\"") : command).replaceAll("");
        if (sourceVariable == null) {
            processBuilder = new ProcessBuilder(VALIDATOR_PATH, commandToSend, outputFormat);
        } else {
            processBuilder = new ProcessBuilder(VALIDATOR_PATH, commandToSend, outputFormat, sourceVariable);
        }

		/*System.out.println("\""+commandToSend+"\"" + "\n");
		System.out.println("\""+outputFormat+"\"" + "\n");
		if (sourceVariable != null) {
			System.out.println("\""+sourceVariable+"\"" + "\n");
		}*/

        // Redirect the standard output to be read by the Java application
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }

    public void close() {
        // do nothing, we clear the downloaded query validator in CsharpExecutable static constructor
    }

    public static void clearDownloadedValidator() {
        try {
            Files.deleteIfExists(Paths.get(VALIDATOR_PATH));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String getExtensionForOs() {
        return isWindows() ? ".exe" : "";
    }
}
