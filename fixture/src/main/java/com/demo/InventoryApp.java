package com.demo;

/** Run reserve() once by hand and look at what it does. */
public class InventoryApp {

    public static void main(String[] args) {
        InventoryStore store = new InventoryStore();
        store.stock("A", 3);
        System.out.println("reserve(5) = " + store.reserve("A", 5));
        System.out.println("onHand(A) = " + store.onHand("A"));
    }
}
