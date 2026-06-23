package com.membershipflow.collect.collector;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CollectorRegistry {

    private final Map<String, PriceCollector> collectors;

    // Spring이 PriceCollector 구현체 전체를 List로 주입
    public CollectorRegistry(List<PriceCollector> collectorList) {
        this.collectors = collectorList.stream()
                .collect(Collectors.toMap(PriceCollector::sourceName, Function.identity()));
    }

    public Optional<PriceCollector> find(String sourceName) {
        return Optional.ofNullable(collectors.get(sourceName));
    }

    public List<PriceCollector> all() {
        return List.copyOf(collectors.values());
    }
}
