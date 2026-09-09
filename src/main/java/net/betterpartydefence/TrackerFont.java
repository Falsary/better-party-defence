package net.betterpartydefence;

public enum TrackerFont
{
	RUNESCAPE("RuneScape"),
	SANS_SERIF("Sans serif"),
	SERIF("Serif"),
	MONOSPACED("Monospaced"),
	CUSTOM("Custom font"),
	ADD_CUSTOM("Add custom font...");

	private final String label;

	TrackerFont(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
