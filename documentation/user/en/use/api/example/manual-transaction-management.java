// open session manually
final EvitaSessionContract session = evita.createReadWriteSession("evita");
// initiate session manually
final long txId = session.openTransaction();
// in case of error, mark transaction as rollback only
session.setRollbackOnly();
// close the transaction
session.closeTransaction();
// or close the entire session
// this implicitly closes the internal transaction
session.close();