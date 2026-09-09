package net.betterpartydefence;

/** Whether the Defence readout follows the NPC or is a movable RuneLite overlay. */
public enum DefenceDisplayMode
{
	NPC("Attached to NPC"),
	DETACHED("Detached");

	private final String label;

	DefenceDisplayMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
