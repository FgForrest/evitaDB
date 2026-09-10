<h1 align="center" style="border-bottom: none">
    <a href="https://evitadb.io" target="_blank"><img src="https://raw.githubusercontent.com/FgForrest/evitaDB/dev/documentation/assets/img/evita.png"/></a><br>evitaDB
</h1>

<p align="center">Visit <a href="https://evitadb.io" target="_blank">evitadb.io</a> for the full documentation,
examples and guides.</p>

## Usage

```shell
## run interactively, destroy container after exit, use host ports without NAT
docker run -i --rm --net=host index.docker.io/evitadb/evitadb:latest

## run interactively, destroy container after exit, use host ports without NAT, specify your own data directory
docker run -i --rm --net=host \
       -v "__data_dir__:/evita/data" \
       index.docker.io/evitadb/evitadb:latest

## run interactively, destroy container after exit, use host ports without NAT, specify your own data directory and configuration file
docker run -i --rm --net=host \
       -v "__config_file__:/evita/conf/evita-configuration.yaml" \
       -v "__data_dir__:/evita/data" \
       -v "__certificate_dir__:/evita/certificates" \
      index.docker.io/evitadb/evitadb:latest
```

## Java version
The image is based on `azul/zulu-openjdk:21` - the evitaDB server requires Java 21. Client applications that
connect to it through the Java driver (`evita_java_driver`) only need Java 17 or newer.

## Environment variables
- **EVITA_CONFIG_DIR** - Path to the directory with configuration files. Default: `/evita/conf/`
- **EVITA_STRICT_CONFIG_FILE_CHECK** - Optional flag that may set configuration file validations to strict. Default: `false`
- **EVITA_STORAGE_DIR** - Path to storage directory. Default: `/evita/data/`
- **EVITA_EXPORT_DIR** - Path to export directory (temporary storage for backup / exports). Default: `/evita/export/`
- **EVITA_CERTIFICATE_DIR** - Path to directory with automatically generated server certificates. Default: `/evita/certificates/`
- **EVITA_LOG_FILE** - Path to the Logback configuration file. Default: `/evita/logback.xml`
- **EVITA_JAVA_OPTS** - Java commandline options. Default: none (empty string)
- **EVITA_ARGS** - Evita Server command-line arguments. Default: none (empty string)

## Entrypoint
Evita Server is started in the entrypoint shell script (`entrypoint.sh`). The `-javaagent` option loads the
server jar as a Java agent, which is required by the server itself:
```shell
exec java \
    -javaagent:${EVITA_BIN_DIR}${EVITA_JAR_NAME} \
    $EVITA_JAVA_OPTS \
    -jar "${EVITA_BIN_DIR}${EVITA_JAR_NAME}" \
    "strictConfigFileCheck=$EVITA_STRICT_CONFIG_FILE_CHECK" \
    "configDir=$EVITA_CONFIG_DIR" \
    "storage.storageDirectory=$EVITA_STORAGE_DIR" \
    "storage.exportDirectory=$EVITA_EXPORT_DIR" \
    "api.certificate.folderPath=$EVITA_CERTIFICATE_DIR" \
    "logback.configurationFile=$EVITA_LOG_FILE" \
    $EVITA_ARGS
```

For more information, go to [evitaDB homepage](https://evitadb.io)
