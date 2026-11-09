package net.betterpartydefence;

/** Successful spell impacts in the same timeline as defence-draining special attacks. */
enum SpellDrain
{
	VULNERABILITY(100),
	VULNERABILITY_TOME(150),
	SHADOW_BARRAGE(165);

	private final int perThousand;

	SpellDrain(int perThousand)
	{
		this.perThousand = perThousand;
	}

	long reduce(long current, long base, long floor)
	{
		return current == base ? Math.max(floor, current - current * perThousand / 1000) : current;
	}
}
