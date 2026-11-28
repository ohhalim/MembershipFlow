package com.membershipflow.subscription.client;

import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

public class PaddleCircuitFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof HttpClientErrorException clientError) {
            return clientError.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value();
        }
        return true;
    }
}
