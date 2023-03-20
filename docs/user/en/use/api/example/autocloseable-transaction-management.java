// open session
try (final EvitaSessionContract session = evita.createReadWriteSession("testCatalog")) {
	try {
		// do your work
	} catch (Exception ex) {
		// mark transaction rollback only when exception occurs
		session.setRollbackOnly();
	}
}