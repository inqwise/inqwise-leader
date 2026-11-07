package com.inqwise.leader;

/**
 * An operation cannot currently proceed, for example while awaiting admission, leadership or an idle participant.
 * This expected operational failure has no captured stack trace. Retry only after observing the relevant state change;
 * this type does not guarantee that quorum or availability will return.
 * It does not prove a remote mutation was unapplied; retries still require idempotency or checking the committed outcome.
 */
public final class RaftUnavailableException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an expected, stackless operational failure. Suppressed cleanup failures remain supported.
	 * @param message reason the operation is unavailable
	 */
	public RaftUnavailableException(String message) { super(message, null, true, false); }
}
