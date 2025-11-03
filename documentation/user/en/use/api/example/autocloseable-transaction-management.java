// open session
try (
	final EvitaSessionContract session =
		evita.createReadWriteSession("evita")
) {
	try {
		// do your work
	} catch (Exception ex) {
		// mark transaction rollback only when exception occurs
		session.setRollbackOnly();
	}
}
