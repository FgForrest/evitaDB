// open session manually
EvitaClientSession session = evita.CreateReadWriteSession("evita");
// initiate session manually
long txId = session.OpenTransaction();
// in case of error, mark transaction as rollback only
session.SetRollbackOnly();
// close the transaction
session.CloseTransaction();
// or close the entire session
// this implicitly closes the internal transaction
session.Close();