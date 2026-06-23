package com.membershipflow.collect.collector;

import java.util.List;

public interface PriceCollector {

    String sourceName();

    List<CollectedPrice> collect();
}
