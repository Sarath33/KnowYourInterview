package com.knowyourinterview.api.common;

/**
 * Thrown when a call to a third-party/upstream service (e.g. the Razorpay Orders API)
 * fails for reasons outside our control — network error, provider outage, unexpected
 * response. Distinct from InvalidStateException (a 400-level client/business-rule
 * problem): this maps to a 502 Bad Gateway so clients can tell "the payment provider is
 * unreachable, retry" apart from "your request was invalid". See ApiExceptionHandler.
 */
public class UpstreamServiceException extends RuntimeException {

    public UpstreamServiceException(String message) {
        super(message);
    }

    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
