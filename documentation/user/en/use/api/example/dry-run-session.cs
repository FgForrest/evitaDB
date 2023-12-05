// open both session
using EvitaClientSession session = evita.CreateSession(
		new SessionTraits(
			"testCatalog",
			SessionFlags.ReadWrite,
			SessionFlags.DryRun
		)
);
// do your work