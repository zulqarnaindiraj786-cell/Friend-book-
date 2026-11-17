package com.google.firebase.database;

/** Minimal shim for DataSnapshot. */
public class DataSnapshot {
    public boolean exists() { return false; }
    public Object getValue() { return null; }
    public <T> T getValue(Class<T> valueType) { return null; }
    public DataSnapshot child(String path) { return new DataSnapshot(); }
}
