package com.membershipflow.subscription.client;

public class PaddleCircuitOpenException extends RuntimeException {
    public PaddleCircuitOpenException(Throwable cause) {
        super(cause);
    }
}
