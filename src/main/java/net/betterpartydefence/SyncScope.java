package net.betterpartydefence;

import lombok.Value;

/**
 * Immutable encounter identity used to correlate party messages.
 */
@Value
class SyncScope
{
	int type;
	int id;
}
