// open both session
try (
	final EvitaSessionContract session = evita.createSession(
		new SessionTraits(
			"evita",
			SessionFlags.READ_WRITE,
			SessionFlags.DRY_RUN
		)
	)
) {
	// do your work
}
