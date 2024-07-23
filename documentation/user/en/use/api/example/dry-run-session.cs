// open both session
using EvitaClientSession session = evita.CreateSession(
		new SessionTraits(
			"evita",
			SessionFlags.ReadWrite,
			SessionFlags.DryRun
		)
);
// do your work