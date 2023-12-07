// open session
using EvitaClientSession session = evita.CreateReadWriteSession("testCatalog");
try
{
    // do your work
}
catch (Exception ex)
{
    // mark transaction rollback only when exception occurs
    session.SetRollbackOnly();
}
// at the end of scope used, session will be disposed and transaction will rolled back
