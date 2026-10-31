package com.inqwise.leader;

/**
 * Local readiness observation, delivered on the node's event-loop context.
 * Readiness requires a durable current-term commit and recent quorum responses.
 * This is not a lease or a fencing token for external side effects.
 * @param leader whether this node is currently ready to act as leader
 * @param term observed protocol term
 * @param leaderId known protocol leader, possibly null
 */
public record Leadership(boolean leader, long term, String leaderId) {}
