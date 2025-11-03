final EvitaSessionContract session;
try{
	// open session manually, read-write sessions automatically open a transaction
	session = evita.createReadWriteSession("evita");
	// in case of error, mark transaction as rollback only
	session.setRollbackOnly();
} finally {
	try{
		// close the entire session - this implicitly closes the internal transaction
		// if `setRollbackOnly` wouldn't have been called, the transaction would have been committed
		session.close();
	} catch (RollbackException e){
		// handle information about rollback
	}
}
