evita.updateCatalog(
	"evita",
	session -> {
		// get existing product in a read-only form - safe for multi-threaded use
		final Product readOnlyInstance = session.getEntity(
			Product.class, 100, entityFetchAllContent()
		).orElseThrow();

		// now create a new instance that is open for write - not safe for multi-threaded use
		final ProductEditor readWriteInstance = readOnlyInstance.openForWrite();
		
		// then we can alter the data in it
		readWriteInstance.setCode("updated-code");
		// we can list all the mutations recorded in this instance by calling, it'll be empty if none were recorded
		final Optional<EntityMutation> entityMutation =  readWriteInstance.toMutation();
		// we can also create new read-only instance without storing the changes to the database
		final Product readOnlyInstanceWithChanges = readWriteInstance.toInstance();
		// or we can, send the mutations to back to the database and update the entity for all other clients
		readWriteInstance.upsertVia(session);
	}
);
