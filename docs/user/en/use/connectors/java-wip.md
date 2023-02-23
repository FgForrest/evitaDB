**Work in progress**

The <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
is thread safe and only single instance of it is expected to be used in the application.

<Note type="info">
The client instance is created regardless of whether the server is available. In order to verify that the server can be
reached you need to call some method on it. The usual scenario would be [opening a new session](#open-session-to-catalog)
to existing <Term document="docs/user/en/index.md">catalog</Term>.
</Note>

<Note type="warning">
The <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
keeps a pool of opened resources and should be terminated by a `close()` method when you stop using it.  
</Note>