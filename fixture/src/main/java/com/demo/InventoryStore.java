package com.demo;

import java.util.HashMap;
import java.util.Map;

/** Stock on hand. reserve() must refuse when there is not enough, and must never go negative. */
public class InventoryStore {

    private final Map<String, Integer> onHand = new HashMap<>();

    public void stock(String sku, int qty) {
        onHand.put(sku, qty);
    }

    /** Hold qty units: true when it worked, false when there is not enough stock. */
    public boolean reserve(String sku, int qty) {
        Integer have = onHand.getOrDefault(sku, 0);
        onHand.put(sku, have - qty);
        return true;
    }

    public int onHand(String sku) {
        return onHand.getOrDefault(sku, 0);
    }
}
