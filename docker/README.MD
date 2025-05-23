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

## Environment variables
- **EVITA_CONFIG_FILE** - Path to configuration file. Default: `/evita/conf/evita-configuration.yaml`
- **EVITA_STRICT_CONFIG_FILE_CHECK** - Optional flag that may set configuration file validations to strict. Default: `false`
- **EVITA_STORAGE_DIR** - Path to storage directory. Default: `/evita/data`
- **EVITA_EXPORT_DIR** - Path to export directory (temporary storage for backup / exports). Default: `/evita/export`
- **EVITA_CERTIFICATE_DIR** - Path to directory with automatically generated server certificates. Default: `/evita/certificates`
- **EVITA_JAVA_OPTS** - Java commandline options. Default: none (empty string)
- **EVITA_ARGS** - Evita Server command-line arguments. Default: none (empty string) 

## Entrypoint
Evita Server is started in entrypoint shell script:
```shell
exec java \
    $EVITA_JAVA_OPTS \
    -jar "evita/bin/evita-server.jar" \
    "configDir=$EVITA_CONFIG_FILE" \
    "storage.storageDirectory=$EVITA_STORAGE_DIR" \
    $EVITA_ARGS
```

For more information, go to [evitaDB homepage](https://evitadb.io)
