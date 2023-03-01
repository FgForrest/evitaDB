# evitaDB server docker image

## Usage

```shell
## run interactively, destroy container after exit, use host ports without NAT
docker run -i --rm --net=host index.docker.io/evitadb/evitadb:latest

## run interactively, destroy container after exit, use host ports without NAT, specify your own data directory
docker run -i --rm --net=host -v "__data_dir__:/evita/data" index.docker.io/evitadb/evitadb:latest

## run interactively, destroy container after exit, use host ports without NAT, specify your own data directory and configuration file
docker run -i --rm --net=host -v "__config_file__:/evita/conf/evita-configuration.yaml" -v "__data_dir__:/evita/data" index.docker.io/evitadb/evitadb:latest
```

## Environment variables
- **EVITA_CONFIG_FILE** - Path to configuration file. Default: `/evita/conf/evita-configuration.yaml`
- **EVITA_STORAGE_DIR** - Path to storage directory. Default: `/evita/data`
- **EVITA_CERTIFICATE_DIR** - Path to directory with automatically generated server certificates. Default: `/evita/certificates`
- **EVITA_JAVA_OPTS** - Java commandline options. Default: none (empty string)
- **EVITA_ARGS** - Evita Server command-line arguments. Default: none (empty string) 

## Entrypoint
Evita Server is started in entrypoint shell script:
```shell
exec java \
    $EVITA_JAVA_OPTS \
    -jar "evita/bin/evita-server.jar" \
    "-DconfigFile=$EVITA_CONFIG_FILE" \
    "-Dstorage.storageDirectory=$EVITA_STORAGE_DIR" \
    $EVITA_ARGS
```

For more information, go to [evitaDB homepage](https://evitadb.io)